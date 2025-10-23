package com.example.mvpble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID

class MainActivity : ComponentActivity() {

    // ======= Your service & characteristic UUIDs =======
    private val SERVICE_UUID = UUID.fromString("b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0a1")
    private val UUID_STATUS  = UUID.fromString("b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0b2") // READ|NOTIFY (JSON)
    private val UUID_CONTROL = UUID.fromString("b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0c3") // WRITE (JSON cmds)
    private val UUID_RANGE   = UUID.fromString("b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0d4") // READ|NOTIFY (uint16 cm)
    private val UUID_SPRINT  = UUID.fromString("b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0f6") // READ|NOTIFY (uint32 ms)
    private val CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // 0x2902

    private val TARGET_NAME = "SprintBeacon"

    // ======= BLE plumbing =======
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false

    private var gatt: BluetoothGatt? = null
    private var chControl: BluetoothGattCharacteristic? = null
    private var chStatus:  BluetoothGattCharacteristic? = null
    private var chRange:   BluetoothGattCharacteristic? = null
    private var chSprint:  BluetoothGattCharacteristic? = null

    private var lastFoundMac: String? = null

    // ---- Notification write queue (one CCCD write at a time)
    private val notifyQueue: ArrayDeque<BluetoothGattCharacteristic> = ArrayDeque()

    // ---- Lightweight polling (fallback) ----
    private val pollIntervalMs = 1000L
    private val pollRunnable = object : Runnable {
        override fun run() {
            val g = gatt
            if (_connected.value && g != null) {
                chStatus?.let { g.readCharacteristic(it) }
                chRange?.let  { g.readCharacteristic(it) }
                chSprint?.let { g.readCharacteristic(it) }
                handler.postDelayed(this, pollIntervalMs)
            }
        }
    }

    // ======= Compose state to show in UI =======
    private var scanLogger: ((String) -> Unit)? = null
    private fun log(msg: String) { scanLogger?.invoke(msg) }

    // Data from device
    private val _connected = mutableStateOf(false)
    private val _rangeCm   = mutableStateOf<Int?>(null)
    private val _haveStart = mutableStateOf(false)
    private val _sprintMs  = mutableStateOf<Long?>(null)
    private val _laserOn   = mutableStateOf<Boolean?>(null)
    private val _autoMode  = mutableStateOf<Boolean?>(null)
    private val _uptimeMs  = mutableStateOf<Long?>(null)

    // ======= Activity results =======
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _: ActivityResult -> tryStartScan() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> tryStartScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    ScannerAndControlScreen(
                        connected = _connected.value,
                        rangeCm = _rangeCm.value,
                        haveStart = _haveStart.value,
                        sprintMs = _sprintMs.value,
                        laserOn = _laserOn.value,
                        autoMode = _autoMode.value,
                        uptimeMs = _uptimeMs.value,
                        onScan = { tryStartScan() },
                        onStopScan = { stopScan() },
                        onConnect = { connectToLastFound() },
                        onDisconnect = { disconnectGatt() },
                        onLaser = { on -> writeControlJson(if (on) """{"laser":true}""" else """{"laser":false}""") },
                        onAuto  = { auto -> writeControlJson(if (auto) """{"auto":true}""" else """{"auto":false}""") }
                    )
                }
            }
        }
    }

    // ================= UI =================
    @Composable
    private fun ScannerAndControlScreen(
        connected: Boolean,
        rangeCm: Int?,
        haveStart: Boolean,
        sprintMs: Long?,
        laserOn: Boolean?,
        autoMode: Boolean?,
        uptimeMs: Long?,
        onScan: () -> Unit,
        onStopScan: () -> Unit,
        onConnect: () -> Unit,
        onDisconnect: () -> Unit,
        onLaser: (Boolean) -> Unit,
        onAuto: (Boolean) -> Unit
    ) {
        var log by remember { mutableStateOf("") }
        scanLogger = { msg -> log += (if (log.isEmpty()) "" else "\n") + msg }

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // Row 1: scanning
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan, modifier = Modifier.weight(1f)) { Text("Scan for SprintBeacon") }
                Button(onClick = onStopScan, modifier = Modifier.weight(1f)) { Text("Stop") }
            }
            Spacer(Modifier.height(8.dp))

            // Row 2: connection
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, enabled = !connected) { Text("Connect") }
                Button(onClick = onDisconnect, enabled = connected) { Text("Disconnect") }
            }
            Spacer(Modifier.height(8.dp))

            // Row 3: controls
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onLaser(true) }, enabled = connected) { Text("Laser ON") }
                Button(onClick = { onLaser(false) }, enabled = connected) { Text("Laser OFF") }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAuto(true) }, enabled = connected) { Text("Auto Mode") }
                Button(onClick = { onAuto(false) }, enabled = connected) { Text("Manual Mode") }
            }
            Spacer(Modifier.height(12.dp))

            // Live values
            Text("Connected: $connected")
            Text("Range: ${rangeCm ?: "-"} cm")
            Text("Start seen: $haveStart")
            Text("Sprint: ${sprintMs?.let { "${it} ms" } ?: "-"}")
            Text("Laser: ${laserOn ?: "-"}")
            Text("Auto: ${autoMode ?: "-"}")
            Text("Uptime: ${uptimeMs?.let { formatHms(it) } ?: "-"}")
            Spacer(Modifier.height(12.dp))
            Text(
                text = log.ifEmpty { "Logs will appear here…" },
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            )
        }
    }

    // ================= Scanning (with MAC capture) =================
    private fun tryStartScan() {
        if (!hasAllNeededPermissions()) {
            requestNeededPermissions()
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBtLauncher.launch(intent)
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            log("BluetoothLeScanner is null.")
            return
        }
        if (scanning) {
            log("Already scanning…")
            return
        }

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
            ScanFilter.Builder().setDeviceName(TARGET_NAME).build()
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        log("Starting BLE scan (10s)…")
        scanning = true
        bleScanner = scanner
        scanner.startScan(filters, settings, scanCallback)

        handler.postDelayed({
            if (scanning) {
                stopScan()
                log("Scan stopped (timeout).")
            }
        }, 10_000)
    }

    private fun stopScan() {
        if (scanning) {
            bleScanner?.stopScan(scanCallback)
            scanning = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: "(no name)"
            val addr = result.device.address
            val rssi = result.rssi
            log("Found: $name | $addr | RSSI=$rssi")
            if (name == TARGET_NAME) {
                lastFoundMac = addr
                log("✅ SprintBeacon spotted → stopping scan.")
                stopScan()
            }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }
        override fun onScanFailed(errorCode: Int) {
            log("Scan failed: code=$errorCode")
        }
    }

    // ================= GATT connection & discovery =================
    private fun connectToLastFound() {
        val mac = lastFoundMac
        if (mac == null) {
            log("No MAC captured yet. Scan first.")
            return
        }
        if (!hasAllNeededPermissions()) {
            requestNeededPermissions(); return
        }
        val device = bluetoothAdapter.getRemoteDevice(mac)
        log("Connecting to $mac …")
        gatt = device.connectGatt(this, false, gattCallback)
    }

    private fun disconnectGatt() {
        stopPolling()
        gatt?.let {
            log("Disconnecting…")
            it.disconnect()
            it.close()
        }
        gatt = null
        _connected.value = false
        chControl = null; chStatus = null; chRange = null; chSprint = null
        notifyQueue.clear()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("GATT connected. Requesting HIGH priority & MTU…")
                _connected.value = true
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.requestMtu(247) // after this we'll discover services
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("GATT disconnected (status=$status)")
                _connected.value = false
                stopPolling()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU changed to $mtu (status=$status). Discovering services…")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: $status")
                return
            }
            val svc = gatt.getService(SERVICE_UUID)
            if (svc == null) {
                log("Service not found.")
                return
            }
            chControl = svc.getCharacteristic(UUID_CONTROL)
            chStatus  = svc.getCharacteristic(UUID_STATUS)
            chRange   = svc.getCharacteristic(UUID_RANGE)
            chSprint  = svc.getCharacteristic(UUID_SPRINT)

            log("Service & characteristics found. Enabling notifications (queued)…")
            notifyQueue.clear()
            chStatus?.let { notifyQueue.addLast(it) }
            chRange?.let  { notifyQueue.addLast(it) }
            chSprint?.let { notifyQueue.addLast(it) }
            enableNextNotify() // kick off first CCCD write
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristic(characteristic)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleCharacteristic(characteristic)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            log("WRITE callback for ${characteristic.uuid}: status=$status")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // One CCCD finished -> pop and do the next
            notifyQueue.firstOrNull()?.let { first ->
                if (first.getDescriptor(CCCD_UUID) == descriptor) {
                    notifyQueue.removeFirst()
                }
            }
            enableNextNotify()
        }
    }

    // ---- Sequential CCCD writer + initial reads + start polling when done
    private fun enableNextNotify() {
        val g = gatt ?: return
        val ch = notifyQueue.firstOrNull() ?: run {
            // All CCCDs done → do initial reads now and start polling
            chStatus?.let { g.readCharacteristic(it) }
            chRange?.let  { g.readCharacteristic(it) }
            chSprint?.let { g.readCharacteristic(it) }
            log("Notifications enabled for all; issued initial reads.")
            startPolling()
            return
        }

        if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
            log("Characteristic ${ch.uuid} has no NOTIFY property; skipping.")
            notifyQueue.removeFirst()
            enableNextNotify()
            return
        }

        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            log("No CCCD on ${ch.uuid}; skipping.")
            notifyQueue.removeFirst()
            enableNextNotify()
            return
        }

        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = g.writeDescriptor(cccd)
        log("Writing CCCD for ${ch.uuid} (ok=$ok)")
        if (!ok) {
            // If immediate write failed, drop and continue
            notifyQueue.removeFirst()
            enableNextNotify()
        }
    }

    private fun startPolling() {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, pollIntervalMs)
    }
    private fun stopPolling() {
        handler.removeCallbacks(pollRunnable)
    }

    private fun formatHms(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%d:%02d:%02d (%d ms)", h, m, s, ms)
    }

    private fun handleCharacteristic(ch: BluetoothGattCharacteristic) {
        when (ch.uuid) {
            UUID_STATUS -> {
                val s = ch.value?.toString(Charsets.UTF_8) ?: return
                try {
                    val j = JSONObject(s)
                    _laserOn.value   = j.optBoolean("laser", false)
                    _autoMode.value  = j.optBoolean("auto", true) // your JSON: true means auto mode
                    _rangeCm.value   = if (j.has("lidar_cm")) j.optInt("lidar_cm") else _rangeCm.value
                    _haveStart.value = j.optBoolean("have_start", false)
                    _sprintMs.value  = if (j.has("sprint_ms")) j.optLong("sprint_ms") else _sprintMs.value
                    _uptimeMs.value  = if (j.has("uptime")) j.optLong("uptime") else _uptimeMs.value
                    log("STATUS: $s")
                } catch (e: Exception) {
                    log("Bad STATUS JSON: ${e.message}")
                }
            }
            UUID_RANGE -> {
                val b = ch.value ?: return
                if (b.size >= 2) {
                    val cm = ByteBuffer.wrap(b.copyOfRange(0, 2))
                        .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    _rangeCm.value = if (cm == 0xFFFF) null else cm
                    log("RANGE notify: ${_rangeCm.value ?: "n/a"} cm")
                }
            }
            UUID_SPRINT -> {
                val b = ch.value ?: return
                if (b.size >= 4) {
                    val ms = ByteBuffer.wrap(b.copyOfRange(0, 4))
                        .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    _sprintMs.value = ms
                    log("SPRINT notify: $ms ms")
                }
            }
        }
    }

    private fun writeControlJson(json: String) {
        if (!_connected.value) { log("Not connected."); return }
        val ch = chControl ?: run { log("CONTROL characteristic missing."); return }
        if (!hasAllNeededPermissions()) { requestNeededPermissions(); return }

        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // write with response
        ch.value = json.toByteArray(Charsets.UTF_8)
        val ok = gatt?.writeCharacteristic(ch) ?: false
        log("Write CONTROL: $json (ok=$ok)")

        // Read STATUS shortly after to observe effect (covers cases where notify is slow)
        if (ok) {
            handler.postDelayed({
                chStatus?.let { gatt?.readCharacteristic(it) }
            }, 150)
        }
    }


    // ================= Permissions =================
    private fun hasAllNeededPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    private fun requestNeededPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionLauncher.launch(perms)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        disconnectGatt()
    }
}
