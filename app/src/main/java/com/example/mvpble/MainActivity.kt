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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.max

class MainActivity : ComponentActivity() {

    // ===== BLE UUIDs (ESP32) =====
    private val SERVICE_UUID = UUID.fromString("b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0a1")
    private val UUID_STATUS  = UUID.fromString("b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0b2") // READ|NOTIFY (JSON)
    private val UUID_RANGE   = UUID.fromString("b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0d4") // READ|NOTIFY (uint16 cm)
    private val UUID_SPRINT  = UUID.fromString("b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0f6") // READ|NOTIFY (uint32 ms)
    private val CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ===== BLE plumbing =====
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())

    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var chStatus:  BluetoothGattCharacteristic? = null
    private var chRange:   BluetoothGattCharacteristic? = null
    private var chSprint:  BluetoothGattCharacteristic? = null
    private val notifyQueue: ArrayDeque<BluetoothGattCharacteristic> = ArrayDeque()

    private var lastFoundMac: String? = null
    private var lastFoundName: String? = null

    // ===== App/UI state =====
    private val _connected = mutableStateOf(false)
    private val _scanning  = mutableStateOf(false)
    private val _found     = mutableStateOf(false)

    private val _lidarCm   = mutableStateOf<Int?>(null)     // from RANGE or STATUS.lidar_cm

    // Live timer (big center) — independent
    private var startMonotonicMs: Long? = null
    private val _liveElapsedMs = mutableStateOf(0L)
    private val _isRunActive   = mutableStateOf(false)

    // Device sprint result & frozen MPH (shown in "Results")
    private val _finalSprintMsFromDevice = mutableStateOf<Long?>(null)
    private val _frozenMph = mutableStateOf<Double?>(null)

    // Range locking logic
    private val rangeLocked = mutableStateOf(false)
    private var lockedRangeYardsSnapshot: Double? = null

    // Sprint buffering with run tagging
    private var runId: Int = 0
    private var pendingSprintMs: Long? = null
    private var pendingSprintRunId: Int = -1

    // Track previous have_start to detect edges
    private var haveStartPrev = false

    // Lightweight polling
    private val pollIntervalMs = 800L
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

    // ===== Activity results =====
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
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    val connected by _connected
                    val scanningState by _scanning
                    val found by _found

                    val lidarCm by _lidarCm
                    val lidarYards = remember(lidarCm) { lidarCm?.let { it / 91.44 } }

                    val isRunActive by _isRunActive
                    val liveTimerMs by _liveElapsedMs
                    val finalDeviceSprintMs by _finalSprintMsFromDevice
                    val frozenMph by _frozenMph

                    // Range controls (textbox is user-facing truth when not using LiDAR)
                    var useLidar by remember { mutableStateOf(true) }
                    var calcText by remember { mutableStateOf("") }
                    var calcYards by remember { mutableStateOf<Double?>(null) }

                    // Keep textbox mirrored to LiDAR while unlocked & using LiDAR
                    LaunchedEffect(lidarYards, useLidar, rangeLocked.value) {
                        if (!rangeLocked.value && useLidar) {
                            calcYards = lidarYards
                            calcText = lidarYards?.let { String.format("%.2f", it) } ?: ""
                        }
                    }

                    // Tick big timer only when a run is active
                    LaunchedEffect(isRunActive) {
                        if (isRunActive) {
                            while (_isRunActive.value) {
                                startMonotonicMs?.let { t0 ->
                                    _liveElapsedMs.value = max(0, System.currentTimeMillis() - t0)
                                }
                                kotlinx.coroutines.delay(16L)
                            }
                        }
                    }

                    MainScreen(
                        scanning = scanningState,
                        connected = connected,
                        found = found,
                        foundName = lastFoundName,
                        onScan = { onScanClicked() },
                        onConnect = { connectToLastFound() },

                        lidarYards = lidarYards,

                        useLidar = useLidar,
                        onUseLidarToggle = { checked ->
                            if (!rangeLocked.value) {
                                useLidar = checked
                                if (checked) {
                                    calcYards = lidarYards
                                    calcText = lidarYards?.let { String.format("%.2f", it) } ?: ""
                                }
                            }
                        },

                        calcText = calcText,
                        onCalcTextChange = { txt ->
                            if (!rangeLocked.value && !useLidar) {
                                val clean = txt.filter { it.isDigit() || it == '.' }
                                calcText = clean
                                calcYards = clean.toDoubleOrNull()
                            }
                        },

                        rangeLocked = rangeLocked.value,

                        // Big timer is purely live
                        liveTimerMs = liveTimerMs,

                        // Results show ONLY the final device time & frozen MPH (after finish)
                        finalDeviceSprintMs = finalDeviceSprintMs,
                        frozenMph = frozenMph
                    )

                    // Range snapshot provider for locking at START
                    currentCalcRangeYardsProvider = {
                        if (useLidar) (lidarYards ?: calcYards) else calcYards
                    }
                }
            }
        }
    }

    // Provider set in composition to read the current calc range when locking
    private var currentCalcRangeYardsProvider: (() -> Double?)? = null

    // ================= UI host =================
    @Composable
    private fun MainScreen(
        scanning: Boolean,
        connected: Boolean,
        found: Boolean,
        foundName: String?,
        onScan: () -> Unit,
        onConnect: () -> Unit,

        lidarYards: Double?,

        useLidar: Boolean,
        onUseLidarToggle: (Boolean) -> Unit,

        calcText: String,
        onCalcTextChange: (String) -> Unit,

        rangeLocked: Boolean,

        liveTimerMs: Long,
        finalDeviceSprintMs: Long?,
        frozenMph: Double?
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top row: Scan / Connect
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onScan,
                    enabled = !scanning && !connected
                ) { Text(if (scanning) "Scanning…" else "Scan") }

                val connectLabel = when {
                    connected -> "Connected"
                    found && foundName != null -> "Connect to $foundName"
                    found -> "Connect to device"
                    else -> "Connect"
                }

                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onConnect,
                    enabled = !connected && found
                ) { Text(connectLabel) }
            }

            // Ranges block
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ranges (yards)", style = MaterialTheme.typography.titleMedium)
                    Text("LiDAR: ${lidarYards?.let { String.format("%.2f", it) } ?: "-"} yd")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = useLidar,
                            onCheckedChange = onUseLidarToggle,
                            enabled = !rangeLocked
                        )
                        Text("Use LiDAR for calculations")
                    }

                    OutlinedTextField(
                        value = calcText,
                        onValueChange = onCalcTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Calc range (yards)") },
                        enabled = !useLidar && !rangeLocked,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
            }

            // Big timer — LIVE ONLY
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val timeText = String.format("%.3f", liveTimerMs / 1000.0)
                Text(text = timeText, fontSize = 64.sp, fontWeight = FontWeight.SemiBold)
            }

            // Results — ONLY final device time & frozen MPH (after finish)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Results", style = MaterialTheme.typography.titleMedium)
                    Text("Sprint time (s): ${finalDeviceSprintMs?.let { String.format("%.3f", it / 1000.0) } ?: "-"}")
                    Text("Average MPH: ${frozenMph?.let { String.format("%.2f", it) } ?: "-"}")
                }
            }
        }
    }

    // ================= Scan → find device =================
    private fun onScanClicked() {
        _found.value = false
        lastFoundMac = null
        lastFoundName = null
        tryStartScan()
    }

    private fun tryStartScan() {
        if (!hasAllNeededPermissions()) { requestNeededPermissions(); return }
        if (!bluetoothAdapter.isEnabled) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBtLauncher.launch(intent); return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        if (scanning) return

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        _scanning.value = true
        bleScanner = scanner
        scanner.startScan(filters, settings, scanCallback)

        handler.postDelayed({ if (scanning) stopScan() }, 10_000)
    }

    private fun stopScan() {
        if (scanning) {
            bleScanner?.stopScan(scanCallback)
            scanning = false
            _scanning.value = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            lastFoundMac = result.device.address
            lastFoundName = result.device.name ?: "SprintBeacon"
            _found.value = true
            stopScan()
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            if (results.isNotEmpty()) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, results.first())
        }
        override fun onScanFailed(errorCode: Int) { stopScan() }
    }

    // ================= Connect & discover =================
    private fun connectToLastFound() {
        val mac = lastFoundMac ?: return
        if (!hasAllNeededPermissions()) { requestNeededPermissions(); return }
        stopScan()
        val device = bluetoothAdapter.getRemoteDevice(mac)
        gatt = device.connectGatt(this, false, gattCallback)
    }

    private fun disconnectGatt() {
        stopPolling()
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        _connected.value = false
        chStatus = null; chRange = null; chSprint = null
        notifyQueue.clear()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connected.value = true
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connected.value = false
                stopPolling()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val svc = gatt.getService(SERVICE_UUID) ?: return
            chStatus = svc.getCharacteristic(UUID_STATUS)
            chRange  = svc.getCharacteristic(UUID_RANGE)
            chSprint = svc.getCharacteristic(UUID_SPRINT)

            notifyQueue.clear()
            chStatus?.let { notifyQueue.addLast(it) }
            chRange?.let  { notifyQueue.addLast(it) }
            chSprint?.let { notifyQueue.addLast(it) }
            enableNextNotify()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristic(characteristic)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleCharacteristic(characteristic)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            notifyQueue.firstOrNull()?.let { first ->
                if (first.getDescriptor(CCCD_UUID) == descriptor) notifyQueue.removeFirst()
            }
            enableNextNotify()
        }
    }

    // CCCD writes in sequence; then initial reads and start polling
    private fun enableNextNotify() {
        val g = gatt ?: return
        val ch = notifyQueue.firstOrNull() ?: run {
            chStatus?.let { g.readCharacteristic(it) }
            chRange?.let  { g.readCharacteristic(it) }
            chSprint?.let { g.readCharacteristic(it) }
            startPolling()
            return
        }

        if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
            notifyQueue.removeFirst(); enableNextNotify(); return
        }

        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            notifyQueue.removeFirst(); enableNextNotify(); return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!g.writeDescriptor(cccd)) {
            notifyQueue.removeFirst(); enableNextNotify()
        }
    }

    private fun startPolling() {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, pollIntervalMs)
    }
    private fun stopPolling() {
        handler.removeCallbacks(pollRunnable)
    }

    // ================= Parse incoming characteristics =================
    private fun handleCharacteristic(ch: BluetoothGattCharacteristic) {
        when (ch.uuid) {
            UUID_STATUS -> {
                val s = ch.value?.toString(Charsets.UTF_8) ?: return
                try {
                    val j = JSONObject(s)

                    // ---- have_start edge detection ----
                    val haveStartNew = j.optBoolean("have_start", false)
                    val rising  = (!haveStartPrev && haveStartNew)
                    val falling = (haveStartPrev && !haveStartNew)
                    haveStartPrev = haveStartNew

                    if (rising) {
                        // NEW RUN
                        runId += 1
                        pendingSprintMs = null
                        pendingSprintRunId = runId

                        _finalSprintMsFromDevice.value = null
                        _frozenMph.value = null

                        lockedRangeYardsSnapshot = currentCalcRangeYardsProvider?.invoke()
                        rangeLocked.value = true

                        startMonotonicMs = System.currentTimeMillis()
                        _liveElapsedMs.value = 0L
                        _isRunActive.value = true
                    }

                    if (falling) {
                        // FINISH — stop/zero big timer now, then finalize after grace
                        _isRunActive.value = false
                        startMonotonicMs = null
                        _liveElapsedMs.value = 0L

                        val thisRun = runId
                        handler.postDelayed({
                            // Only finalize if we haven't started a new run meanwhile
                            if (runId == thisRun) {
                                val deviceMs = if (pendingSprintRunId == thisRun) pendingSprintMs else null
                                _finalSprintMsFromDevice.value = deviceMs

                                val yards = lockedRangeYardsSnapshot
                                _frozenMph.value = if (deviceMs != null && deviceMs > 0 && yards != null && yards > 0.0) {
                                    val sec = deviceMs / 1000.0
                                    yards * 3600.0 / (1760.0 * sec)
                                } else null

                                lockedRangeYardsSnapshot = null
                                rangeLocked.value = false
                            }
                        }, 250L) // grace window for late SPRINT packet
                    }

                    // lidar from STATUS (optional)
                    if (j.has("lidar_cm")) {
                        val cm = j.optInt("lidar_cm")
                        _lidarCm.value = if (cm == 0xFFFF) null else cm
                    }

                    // If firmware also surfaces sprint_ms in STATUS, record it for this run
                    if (j.has("sprint_ms")) {
                        val sm = j.optLong("sprint_ms")
                        if (sm > 0) {
                            pendingSprintMs = sm
                            pendingSprintRunId = runId
                        }
                    }
                } catch (_: Exception) { /* ignore malformed JSON */ }
            }

            UUID_RANGE -> {
                val b = ch.value ?: return
                if (b.size >= 2) {
                    val cm = ByteBuffer.wrap(b.copyOfRange(0, 2))
                        .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    _lidarCm.value = if (cm == 0xFFFF) null else cm
                }
            }

            UUID_SPRINT -> {
                val b = ch.value ?: return
                if (b.size >= 4) {
                    val raw = ByteBuffer.wrap(b.copyOfRange(0, 4))
                        .order(ByteOrder.LITTLE_ENDIAN).int
                    val deviceMs = raw.toLong() and 0xFFFFFFFFL
                    if (deviceMs > 0) {
                        pendingSprintMs = deviceMs
                        pendingSprintRunId = runId
                    }
                }
            }
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
        if (scanning) stopScan()
        disconnectGatt()
    }
}
