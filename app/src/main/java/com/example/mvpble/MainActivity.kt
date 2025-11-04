package com.example.mvpble

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.pm.ActivityInfo
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min
// ===== Imports you need somewhere at top of file =====
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
// ========================= Room (DB) =========================

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val userName: String,
    val sprintMs: Long,
    val rangeYards: Double,
    val mph: Double,
    val timestamp: Long
)

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY name ASC")
    fun observeAll(): Flow<List<UserEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(user: UserEntity)
}

@Dao
interface RunDao {
    @Query("SELECT * FROM runs ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE userName = :user ORDER BY timestamp DESC")
    fun observeForUser(user: String): Flow<List<RunEntity>>

    @Insert
    suspend fun insert(run: RunEntity)
}

@Database(entities = [UserEntity::class, RunEntity::class], version = 1)
abstract class AppDb : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun runDao(): RunDao

    companion object {
        @Volatile private var INSTANCE: AppDb? = null
        fun get(context: Context): AppDb =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDb::class.java, "sprints.db")
                    .build().also { INSTANCE = it }
            }
    }
}

// ========================= Activity =========================

class MainActivity : ComponentActivity() {

    // --- App navigation + connection name ---
    private enum class Screen { Connect, Main, History }
    private val _screen = mutableStateOf(Screen.Connect)
    private val _connectedName = mutableStateOf<String?>(null)

    // --- Discovered beacons list for Connect screen ---
    private data class Beacon(val mac: String, val name: String, val rssi: Int)
    private val beacons = mutableStateListOf<Beacon>()


    // ===== BLE UUIDs (ESP32) =====
    private val SERVICE_UUID = UUID.fromString("b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0a1")
    private val UUID_STATUS  = UUID.fromString("b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0b2") // READ|NOTIFY (JSON)
    private val UUID_RANGE   = UUID.fromString("b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0d4") // READ|NOTIFY (uint16 cm)
    private val UUID_SPRINT  = UUID.fromString("b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0f6") // READ|NOTIFY (uint32 ms)
    private val UUID_CONTROL = UUID.fromString("b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0c3") // WRITE (JSON)  ← added
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
    private var chControl: BluetoothGattCharacteristic? = null // ← added
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

    // ===== User features (state + DB) =====
    @Volatile private var selectedUserName: String? = null
    private lateinit var db: AppDb

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

        db = AppDb.get(this)

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    // Simple 2-screen navigator (Main / History)
                    val screen by _screen

                    // === Persisted UI state (survives nav/rotation) ===
                    var selectedUserSave by rememberSaveable { mutableStateOf<String?>("Unassigned") }
                    var manualRangeTextSave by rememberSaveable { mutableStateOf("") }   // textbox truth
                    var useLidarSave by rememberSaveable { mutableStateOf(true) }        // checkbox truth


                    // Lock Main to portrait; allow rotation on History
                    LaunchedEffect(screen) {
                        requestedOrientation = if (_screen.value == Screen.Main)
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        else
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }

                    // Tick big timer only when a run is active (unchanged)
                    val isRunActive by remember { derivedStateOf { _isRunActive.value } }
                    LaunchedEffect(isRunActive) {
                        if (isRunActive) {
                            while (_isRunActive.value) {
                                startMonotonicMs?.let { t0 ->
                                    _liveElapsedMs.value = max(0, System.currentTimeMillis() - t0)
                                }
                                delay(16L)
                            }
                        }
                    }

                    when (screen) {
                        Screen.Connect -> ConnectScreenHost(
                            scanning = _scanning.value,
                            beacons = beacons,
                            onScan = { onScanClicked() },
                            onConnect = { mac, name -> connectTo(mac, name) }   // new function below
                        )
                        Screen.Main -> MainScreenHost(
                            db = db,
                            onNavigateHistory = { _screen.value = Screen.History },
                            connectedName = _connectedName.value,   // NEW
                            onArm = { sendArmCommand() },
                            connected = _connected.value,
                            lidarCm = _lidarCm.value,
                            liveTimerMs = _liveElapsedMs.value,
                            finalDeviceSprintMs = _finalSprintMsFromDevice.value,
                            frozenMph = _frozenMph.value,
                            rangeLocked = rangeLocked.value,

                            // NEW: state hoisted to root and saved
                            selectedUser = selectedUserSave,
                            onSelectedUserChanged = { selectedUserSave = it; selectedUserName = it },

                            manualRangeText = manualRangeTextSave,
                            onManualRangeTextChanged = { manualRangeTextSave = it },

                            useLidar = useLidarSave,
                            onUseLidarChanged = { useLidarSave = it },

                            provideCalcRangeYards = { currentCalcRangeYardsProvider?.invoke() }
                        )

                        Screen.History -> HistoryScreenHost(
                            db = db,
                            onBack = { _screen.value = Screen.Main }
                        )
                    }
                }
            }
        }
    }

    // Provider set in composition to read the current calc range when locking
    private var currentCalcRangeYardsProvider: (() -> Double?)? = null

    // ================= Screens (User features) =================
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ConnectScreenHost(
        scanning: Boolean,
        beacons: List<Beacon>,
        onScan: () -> Unit,
        onConnect: (mac: String, name: String?) -> Unit
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Bluetooth Connect", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onScan,
                enabled = !scanning
            ) {
                Text(if (scanning) "Scanning…" else "Scan")
            }

            Card(Modifier.fillMaxSize()) {
                if (beacons.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (scanning) "Scanning for SprintBeacons…" else "No devices yet. Tap Scan.")
                    }
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(beacons, key = { it.mac }) { b ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(b.name.ifBlank { "SprintBeacon" }, fontWeight = FontWeight.SemiBold)
                                    Text("${b.mac}   RSSI ${b.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = { onConnect(b.mac, b.name) }) { Text("Connect") }
                            }
                            Divider()
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreenHost(
        db: AppDb,
        onNavigateHistory: () -> Unit,
        onArm: () -> Unit,
        connected: Boolean,
        lidarCm: Int?,
        liveTimerMs: Long,
        finalDeviceSprintMs: Long?,
        frozenMph: Double?,
        rangeLocked: Boolean,

        // NEW (hoisted/saved)
        selectedUser: String?,
        onSelectedUserChanged: (String?) -> Unit,
        manualRangeText: String,
        onManualRangeTextChanged: (String) -> Unit,
        useLidar: Boolean,
        onUseLidarChanged: (Boolean) -> Unit,
        provideCalcRangeYards: () -> Double?,
        connectedName: String?
    )
    {
        val context = LocalContext.current

        // Users stream
        val allUsers by produceState(initialValue = emptyList<UserEntity>()) {
            db.userDao().observeAll().collectLatest { value = it }
        }

        // Ensure default user exists
        LaunchedEffect(Unit) {
            lifecycleScope.launch(Dispatchers.IO) {
                db.userDao().insert(UserEntity("Unassigned"))
            }
        }

// Selection + add-user UI state (selectedUser now comes from parameters)
        var addingUser by remember { mutableStateOf(false) }
        var newUserText by remember { mutableStateOf("") }

        // Keep activity field updated so finalize path can persist correctly
        LaunchedEffect(selectedUser) { onSelectedUserChanged(selectedUser) }

        // Convert LiDAR cm → yards
        val lidarYards = remember(lidarCm) { lidarCm?.let { it / 91.44 } }

// Range controls — hoisted to root; textbox truth when not using LiDAR
        val useLidarState = useLidar
        val calcText = manualRangeText
        var calcYards by remember { mutableStateOf<Double?>(manualRangeText.toDoubleOrNull()) }

        // Keep textbox mirrored to LiDAR while unlocked & using LiDAR
        LaunchedEffect(lidarYards, useLidarState, rangeLocked) {
            if (!rangeLocked && useLidarState) {
                calcYards = lidarYards
                onManualRangeTextChanged(lidarYards?.let { String.format("%.2f", it) } ?: "")
            }
        }

        // Expose current calc range for START locking
        LaunchedEffect(useLidarState, calcYards, lidarYards, rangeLocked) {
            currentCalcRangeYardsProvider = {
                if (useLidarState) (lidarYards ?: calcYards) else calcYards
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top bar
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(connectedName ?: "Disconnected", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onNavigateHistory) { Text("History") }
            }

            // User picker + Add user
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Runner", style = MaterialTheme.typography.titleMedium)

                    if (addingUser) {
                        OutlinedTextField(
                            value = newUserText,
                            onValueChange = { newUserText = it },
                            label = { Text("Add new user") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    val name = newUserText.trim()
                                    if (name.isNotEmpty()) {
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            db.userDao().insert(UserEntity(name))
                                        }
                                        onSelectedUserChanged(name)
                                        newUserText = ""
                                        addingUser = false
                                    } else {
                                        addingUser = false
                                    }
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = it },
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedTextField(
                                    value = selectedUser ?: "",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Select user") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    allUsers.forEach { u ->
                                        DropdownMenuItem(
                                            text = { Text(u.name) },
                                            onClick = {
                                                onSelectedUserChanged(u.name)
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            TextButton(onClick = { addingUser = true }) {
                                Text("Add user")
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Smaller title
                    Text("Ranges (yd)", style = MaterialTheme.typography.titleSmall)

                    // One compact line: LiDAR value + checkbox
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "LiDAR: ${lidarYards?.let { String.format("%.2f", it) } ?: "-"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = useLidarState,
                                onCheckedChange = { checked ->
                                    if (!rangeLocked) {
                                        onUseLidarChanged(checked)
                                        if (checked) {
                                            calcYards = lidarYards
                                            onManualRangeTextChanged(lidarYards?.let { String.format("%.2f", it) } ?: "")
                                        }
                                    }
                                },
                                enabled = !rangeLocked
                            )
                            Text("Use LiDAR", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // Manual textbox—single line, small label, tight height
                    OutlinedTextField(
                        value = calcText,
                        onValueChange = { txt ->
                            if (!rangeLocked && !useLidarState) {
                                val clean = txt.filter { it.isDigit() || it == '.' }
                                onManualRangeTextChanged(clean)
                                calcYards = clean.toDoubleOrNull()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        label = { Text("Calc range (yd)", style = MaterialTheme.typography.bodySmall) },
                        enabled = !useLidarState && !rangeLocked,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
            }


// Big timer + Arm in one row (timer auto-fits)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),                 // this row grows to fill the available vertical space
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Timer gets most of the width and is centered in its area
                Box(
                    modifier = Modifier
                        .weight(1f),             // take all remaining width
                    contentAlignment = Alignment.Center
                ) {
                    BigTimer(
                        millis = liveTimerMs,
                        modifier = Modifier.fillMaxWidth(),
                        maxSize = 320.sp,         // whatever max/min you prefer
                        minSize = 32.sp
                    )
                }

                Spacer(Modifier.width(16.dp))

                // Button sits to the right, vertically centered by the Row’s verticalAlignment
                Button(
                    onClick = onArm,
                    enabled = connected,
                    modifier = Modifier
                        .widthIn(min = 140.dp)
                        .heightIn(min = 72.dp)
                ) {
                    Text("Arm", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
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

        // Supply current calc range provider up to activity
        currentCalcRangeYardsProvider = { if (useLidarState) (lidarYards ?: calcYards) else calcYards }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HistoryScreenHost(
        db: AppDb,
        onBack: () -> Unit
    ) {
        val allUsers by produceState(initialValue = emptyList<UserEntity>()) {
            db.userDao().observeAll().collectLatest { value = it }
        }
        var selected by remember { mutableStateOf<String?>("All") }

        val runs: List<RunEntity> by produceState(initialValue = emptyList()) {
            if (selected == null || selected == "All") {
                db.runDao().observeAll().collectLatest { value = it }
            } else {
                db.runDao().observeForUser(selected!!).collectLatest { value = it }
            }
        }

        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("History", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(48.dp))
            }

            // User filter
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selected ?: "All",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Filter user") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("All") }, onClick = { selected = "All"; expanded = false })
                        allUsers.forEach { u ->
                            DropdownMenuItem(text = { Text(u.name) }, onClick = { selected = u.name; expanded = false })
                        }
                    }
                }
            }

            // Charts
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Average MPH over time")
                    LineChart(
                        data = runs.map { it.timestamp to it.mph },
                        yLabel = "MPH"
                    )
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("MPH vs Range")
                    ScatterChart(
                        points = runs.map { it.rangeYards to it.mph },
                        xLabel = "Range (yd)",
                        yLabel = "MPH"
                    )
                }
            }

            // List
            Card(Modifier.fillMaxSize()) {
                val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(runs) { r ->
                        Column {
                            Text("${fmt.format(Date(r.timestamp))} — ${r.userName}", fontWeight = FontWeight.SemiBold)
                            Text("Sprint: ${String.format("%.3f s", r.sprintMs / 1000.0)}  |  Range: ${String.format("%.2f yd", r.rangeYards)}  |  MPH: ${String.format("%.2f", r.mph)}")
                        }
                        Divider(Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }

    // ================= Simple charts (Canvas) =================

    @Composable
    private fun LineChart(
        data: List<Pair<Long, Double>>,
        yLabel: String,
        modifier: Modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(6.dp)
    ) {
        if (data.isEmpty()) {
            Box(modifier, contentAlignment = Alignment.Center) { Text("No data") }
            return
        }
        val xs = data.map { it.first.toDouble() }
        val ys = data.map { it.second }
        val xMin = xs.minOrNull() ?: 0.0
        val xMax = xs.maxOrNull() ?: 1.0
        val yMin = min(ys.minOrNull() ?: 0.0, 0.0)
        val yMax = max(ys.maxOrNull() ?: 1.0, 1.0)
        Canvas(modifier) {
            val pad = 32f
            val w = size.width - pad * 2
            val h = size.height - pad * 2
            if (w <= 0 || h <= 0) return@Canvas

            drawLine(Color.Gray, start = androidx.compose.ui.geometry.Offset(pad, size.height - pad),
                end = androidx.compose.ui.geometry.Offset(size.width - pad, size.height - pad))
            drawLine(Color.Gray, start = androidx.compose.ui.geometry.Offset(pad, pad),
                end = androidx.compose.ui.geometry.Offset(pad, size.height - pad))

            val path = Path()
            data.sortedBy { it.first }.forEachIndexed { idx, (tx, yv) ->
                val x = pad + ((tx - xMin) / (xMax - xMin).coerceAtLeast(1.0)) * w
                val y = size.height - pad - ((yv - yMin) / (yMax - yMin).coerceAtLeast(1.0)) * h
                if (idx == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
            }
            drawPath(path, Color(0xFF90CAF9), style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
    }

    @Composable
    private fun ScatterChart(
        points: List<Pair<Double, Double>>,
        xLabel: String,
        yLabel: String,
        modifier: Modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(6.dp)
    ) {
        if (points.isEmpty()) {
            Box(modifier, contentAlignment = Alignment.Center) { Text("No data") }
            return
        }
        val xs = points.map { it.first }
        val ys = points.map { it.second }
        val xMin = xs.minOrNull() ?: 0.0
        val xMax = xs.maxOrNull() ?: 1.0
        val yMin = ys.minOrNull() ?: 0.0
        val yMax = ys.maxOrNull() ?: 1.0

        Canvas(modifier) {
            val pad = 32f
            val w = size.width - pad * 2
            val h = size.height - pad * 2
            if (w <= 0 || h <= 0) return@Canvas

            drawLine(Color.Gray, start = androidx.compose.ui.geometry.Offset(pad, size.height - pad),
                end = androidx.compose.ui.geometry.Offset(size.width - pad, size.height - pad))
            drawLine(Color.Gray, start = androidx.compose.ui.geometry.Offset(pad, pad),
                end = androidx.compose.ui.geometry.Offset(pad, size.height - pad))

            points.forEach { (xv, yv) ->
                val x = pad + ((xv - xMin) / (xMax - xMin).coerceAtLeast(1.0)) * w
                val y = size.height - pad - ((yv - yMin) / (yMax - yMin).coerceAtLeast(1.0)) * h
                drawCircle(Color(0xFFFFCC80), radius = 6f, center = androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat()))
            }
        }
    }


    @SuppressLint("UnusedBoxWithConstraintsScope")
    @Composable
    private fun AutoFitText(
        text: String,
        modifier: Modifier = Modifier,
        maxSize: TextUnit = 220.sp,   // upper bound it can grow to
        minSize: TextUnit = 24.sp,    // lower bound
        weight: FontWeight? = FontWeight.SemiBold
    ) {
        val measurer = rememberTextMeasurer()

        BoxWithConstraints(modifier) {
            // convert available size from Dp -> px
            val density = LocalDensity.current
            val maxWpx = with(density) { maxWidth.toPx() }
            val maxHpx = with(density) { maxHeight.toPx() }

            if (maxWpx <= 0f || maxHpx <= 0f) return@BoxWithConstraints

            // binary search for largest size that fits width & height
            val fitted = remember(text, maxWpx, maxHpx) {
                var lo = minSize.value
                var hi = maxSize.value
                var best = lo

                val base = TextStyle(
                    fontWeight = weight,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    // tabular numbers -> stable digit widths
                    fontFeatureSettings = "tnum"
                )

                repeat(18) { // precision
                    val mid = (lo + hi) / 2f
                    val res = measurer.measure(
                        text = text,
                        style = base.copy(fontSize = mid.sp),
                        maxLines = 1,
                        softWrap = false
                    )
                    val fits = res.size.width <= maxWpx && res.size.height <= maxHpx
                    if (fits) { best = mid; lo = mid } else { hi = mid }
                }
                best.sp
            }

            Text(
                text = text,
                style = TextStyle(
                    fontSize = fitted,
                    fontWeight = weight,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    fontFeatureSettings = "tnum"
                ),
                maxLines = 1,
                softWrap = false
            )
        }
    }
    @Composable
    private fun BigTimer(
        millis: Long,
        modifier: Modifier = Modifier,
        maxSize: TextUnit = 120.sp,   // starting/upper bound
        minSize: TextUnit = 42.sp     // smallest allowed
    ) {
        // (No need to remember here; formatting is cheap and AutoFitText remembers layout)
        val text = String.format(Locale.US, "%.3f", millis / 1000.0)
        AutoFitText(
            text = text,
            modifier = modifier,
            maxSize = maxSize,         // <-- was targetSize
            minSize = minSize,
            weight = FontWeight.SemiBold
        )
    }
    // ================= Scan → find device (unchanged) =================
    private fun onScanClicked() {
        beacons.clear()
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
            val mac = result.device.address
            val name = result.device.name ?: "SprintBeacon"
            val rssi = result.rssi

            // de-dupe by MAC and update best/latest RSSI & name
            val idx = beacons.indexOfFirst { it.mac == mac }
            if (idx >= 0) {
                beacons[idx] = beacons[idx].copy(name = name, rssi = rssi)
            } else {
                beacons.add(Beacon(mac, name, rssi))
            }

            _found.value = true
            // NOTE: we DO NOT stop scan immediately; we let the 10 s window finish
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) { stopScan() }
    }

    private fun connectTo(mac: String, name: String?) {
        if (!hasAllNeededPermissions()) { requestNeededPermissions(); return }
        stopScan()

        lastFoundMac = mac
        lastFoundName = name ?: "SprintBeacon"

        val device = bluetoothAdapter.getRemoteDevice(mac)
        gatt = device.connectGatt(this, false, gattCallback)
    }


    private fun disconnectGatt() {
        stopPolling()
        gatt?.let { it.close() }  // ✅ just close
        gatt = null
        _connected.value = false
        chStatus = null; chRange = null; chSprint = null; chControl = null // ← clear control
        notifyQueue.clear()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connected.value = true
                _connectedName.value = lastFoundName ?: "SprintBeacon"
                _screen.value = Screen.Main      // ← go to main screen
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connected.value = false
                _connectedName.value = null
                stopPolling()
                disconnectGatt()                 // ensures cleanup
                _screen.value = Screen.Connect   // ← return to connect UI
            }
        }


        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val svc = gatt.getService(SERVICE_UUID) ?: return
            chStatus  = svc.getCharacteristic(UUID_STATUS)
            chRange   = svc.getCharacteristic(UUID_RANGE)
            chSprint  = svc.getCharacteristic(UUID_SPRINT)
            chControl = svc.getCharacteristic(UUID_CONTROL) // ← added

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

    // ================= Parse incoming characteristics (unchanged except persistence glue) =================
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
                                val mph = if (deviceMs != null && deviceMs > 0 && yards != null && yards > 0.0) {
                                    val sec = deviceMs / 1000.0
                                    yards * 3600.0 / (1760.0 * sec)
                                } else null
                                _frozenMph.value = mph

                                // ===== Persist to DB (user feature) =====
                                val user = (selectedUserName ?: "Unassigned").ifBlank { "Unassigned" }
                                val ts = System.currentTimeMillis()
                                if (deviceMs != null && yards != null && mph != null) {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        db.userDao().insert(UserEntity(user))
                                        db.runDao().insert(
                                            RunEntity(
                                                userName = user,
                                                sprintMs = deviceMs,
                                                rangeYards = yards,
                                                mph = mph,
                                                timestamp = ts
                                            )
                                        )
                                    }
                                }

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

    // ================= Control write (ARM / START) =================
    private fun sendArmCommand() {
        val g = gatt ?: return
        val c = chControl ?: return
        c.value = """{"arm":1}""".toByteArray(Charsets.UTF_8)

        // Prefer write-with-response; fallback to no-response if needed.
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (!g.writeCharacteristic(c)) {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            g.writeCharacteristic(c)
        }
    }

    // ================= Permissions (unchanged) =================
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
