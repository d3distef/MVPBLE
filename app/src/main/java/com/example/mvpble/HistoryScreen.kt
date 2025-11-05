package com.example.mvpble

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
// If you keep AnimatedVisibility:
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreenHost(
    db: AppDb,
    isConnected: Boolean,
    onBack: () -> Unit
) {
    // ---- Live sources ----
    val allUsers by produceState(initialValue = emptyList<UserEntity>()) {
        db.userDao().observeAll().collectLatest { value = it }
    }
    val allRuns by produceState(initialValue = emptyList<RunEntity>()) {
        db.runDao().observeAll().collectLatest { value = it }
    }

    // ---- Filters (edit buffers) ----
    var selRunner by remember { mutableStateOf<String?>("All") }
    var rangeMinTxt by remember { mutableStateOf("") }
    var rangeMaxTxt by remember { mutableStateOf("") }
    var dateStartTxt by remember { mutableStateOf("") } // yyyy-MM-dd
    var dateEndTxt by remember { mutableStateOf("") }   // yyyy-MM-dd

    // ---- Applied filters snapshot (only change on Apply) ----
    var appliedRunner by remember { mutableStateOf<String?>("All") }
    var appliedRangeMin by remember { mutableStateOf<Double?>(null) }
    var appliedRangeMax by remember { mutableStateOf<Double?>(null) }
    var appliedDateStart by remember { mutableStateOf<Long?>(null) }
    var appliedDateEnd by remember { mutableStateOf<Long?>(null) }

    // ---- Tabs ----
    val tabs = listOf("Data", "Avg MPH over time", "Distribution 📊", "Leaderboards 🏆", "Consistency ⚖️")
    var tabIndex by remember { mutableStateOf(0) }

    // ---- Collapsible filter panel ----
    var showFilters by remember { mutableStateOf(false) }

    // ---- Helpers ----
    val sdfYMD = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    sdfYMD.timeZone = TimeZone.getTimeZone("UTC")

    fun parseYmdToUtcMillis(s: String): Long? = try {
        if (s.isBlank()) null else sdfYMD.parse(s)?.time
    } catch (_: Throwable) { null }

    // ---- Apply / Clear ----
    fun applyFilters() {
        appliedRunner = selRunner
        appliedRangeMin = rangeMinTxt.toDoubleOrNull()
        appliedRangeMax = rangeMaxTxt.toDoubleOrNull()
        appliedDateStart = parseYmdToUtcMillis(dateStartTxt)?.let { atStartOfDayUtc(it) }
        appliedDateEnd   = parseYmdToUtcMillis(dateEndTxt)?.let { atEndOfDayUtc(it) }
        showFilters = false
    }
    fun clearFilters() {
        selRunner = "All"
        rangeMinTxt = ""
        rangeMaxTxt = ""
        dateStartTxt = ""
        dateEndTxt = ""
        appliedRunner = "All"
        appliedRangeMin = null
        appliedRangeMax = null
        appliedDateStart = null
        appliedDateEnd = null
        showFilters = false
    }

    // ---- Auto-discard obvious junk ----
    // Keep only: 5 yd ≤ range ≤ 120 yd, 0.5 s ≤ sprint ≤ 30 s, 2 mph ≤ mph ≤ 30 mph
    fun sane(r: RunEntity): Boolean {
        if (r.rangeYards !in 5.0..120.0) return false
        if (r.sprintMs !in 500..30_000) return false
        if (r.mph !in 2.0..30.0) return false
        return true
    }

    // ---- Apply filters to full data ----
    val filteredRuns by remember(
        allRuns, appliedRunner, appliedRangeMin, appliedRangeMax, appliedDateStart, appliedDateEnd
    ) {
        mutableStateOf(
            allRuns
                .asSequence()
                .filter(::sane)
                .filter { r ->
                    when {
                        appliedRunner == null || appliedRunner == "All" -> true
                        else -> r.userName == appliedRunner
                    }
                }
                .filter { r ->
                    val okMin = appliedRangeMin?.let { r.rangeYards >= it } ?: true
                    val okMax = appliedRangeMax?.let { r.rangeYards <= it } ?: true
                    okMin && okMax
                }
                .filter { r ->
                    val okStart = appliedDateStart?.let { r.timestamp >= it } ?: true
                    val okEnd   = appliedDateEnd?.let { r.timestamp <= it } ?: true
                    okStart && okEnd
                }
                .sortedByDescending { it.timestamp }
                .toList()
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top bar with Back + title + Filter/Clear
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text(
                "History",
                fontSize = MaterialTheme.typography.titleLarge.fontSize,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { showFilters = !showFilters }) { Text(if (showFilters) "Hide Filters" else "Filters") }
                TextButton(onClick = { clearFilters() }) { Text("Clear") }
            }
        }

        // Collapsible filters
        AnimatedVisibility(visible = showFilters) {
            FilterPanel(
                allUsers = allUsers.map { it.name }.sorted(),
                selRunner = selRunner,
                onSelRunner = { selRunner = it },
                rangeMinTxt = rangeMinTxt,
                onRangeMin = { rangeMinTxt = it.filter { c -> c.isDigit() || c == '.' } },
                rangeMaxTxt = rangeMaxTxt,
                onRangeMax = { rangeMaxTxt = it.filter { c -> c.isDigit() || c == '.' } },
                dateStartTxt = dateStartTxt,
                onDateStart = { dateStartTxt = it },
                dateEndTxt = dateEndTxt,
                onDateEnd = { dateEndTxt = it },
                onApply = { applyFilters() },
                onClear = { clearFilters() }
            )
        }

        // Tabs
        TabRow(selectedTabIndex = tabIndex) {
            tabs.forEachIndexed { idx, label ->
                Tab(selected = tabIndex == idx, onClick = { tabIndex = idx }, text = { Text(label) })
            }
        }

        when (tabIndex) {
            0 -> DataTab(db, filteredRuns)                              // Data table
            1 -> AvgMphOverTimeTab(filteredRuns)                        // Multi-line chart
            2 -> DistributionTab(filteredRuns)                          // Histograms by runner
            3 -> LeaderboardsTab(filteredRuns, appliedRunner, 5)        // Top-5 overall or per-runner
            4 -> ConsistencyTab(filteredRuns)                           // Std-dev vs avg MPH
        }
    }
}

/* ----------------------------- Filters UI ----------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterPanel(
    allUsers: List<String>,
    selRunner: String?,
    onSelRunner: (String?) -> Unit,
    rangeMinTxt: String,
    onRangeMin: (String) -> Unit,
    rangeMaxTxt: String,
    onRangeMax: (String) -> Unit,
    dateStartTxt: String,
    onDateStart: (String) -> Unit,
    dateEndTxt: String,
    onDateEnd: (String) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start // Alignment.Horizontal (correct type)
    ) {
        // First row: Runner + Apply / Clear
        Row(
            Modifier.fillMaxWidth(),
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
                    value = selRunner ?: "All",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Runner") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("All") }, onClick = { onSelRunner("All"); expanded = false })
                    allUsers.forEach { u ->
                        DropdownMenuItem(text = { Text(u) }, onClick = { onSelRunner(u); expanded = false })
                    }
                }
            }

            Button(onClick = onApply) { Text("Apply") }
            TextButton(onClick = onClear) { Text("Clear") }
        }

        // Second row: Range filters
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = rangeMinTxt,
                onValueChange = onRangeMin,
                label = { Text("Range ≥ (yd)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = rangeMaxTxt,
                onValueChange = onRangeMax,
                label = { Text("Range ≤ (yd)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        // Third row: Date filters (yyyy-MM-dd)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = dateStartTxt,
                onValueChange = onDateStart,
                label = { Text("Date start (yyyy-MM-dd)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = dateEndTxt,
                onValueChange = onDateEnd,
                label = { Text("Date end (yyyy-MM-dd)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/* ----------------------------- Data tab ----------------------------- */

@Composable
private fun DataTab(
    db: AppDb,
    rows: List<RunEntity>
) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val scope = rememberCoroutineScope()

    // One shared horizontal scroll state for header + all rows
    val hScroll = rememberScrollState()

    // Fixed column widths so rows are always fully visible within the scrollable area
    val wRunner = 140.dp
    val wSprint = 120.dp
    val wRange  = 120.dp
    val wMph    = 100.dp
    val wDate   = 160.dp  // date moved to END
    val wDelete = 56.dp

    Card(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Header (horizontally scrollable)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(hScroll)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderCell("Runner", wRunner)
                HeaderCell("Sprint (s)", wSprint, Alignment.CenterEnd)
                HeaderCell("Range (yd)", wRange, Alignment.CenterEnd)
                HeaderCell("MPH", wMph, Alignment.CenterEnd)
                HeaderCell("When", wDate) // Date column LAST
                Spacer(Modifier.width(wDelete))
            }

            Divider()

            // Rows (each row participates in the same horizontal scroll)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.Start
            ) {
                items(rows, key = { it.id }) { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(hScroll) // sync with header
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BodyCell(r.userName, wRunner)
                        BodyCell(String.format(Locale.US, "%.3f", r.sprintMs / 1000.0), wSprint, Alignment.CenterEnd)
                        BodyCell(String.format(Locale.US, "%.2f", r.rangeYards), wRange, Alignment.CenterEnd)
                        BodyCell(String.format(Locale.US, "%.2f", r.mph), wMph, Alignment.CenterEnd)
                        BodyCell(fmt.format(Date(r.timestamp)), wDate)

                        Box(Modifier.width(wDelete), contentAlignment = Alignment.Center) {
                            IconButton(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        db.runDao().delete(r)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                    Divider()
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(
    text: String,
    width: Dp,
    align: Alignment = Alignment.CenterStart
) {
    Box(Modifier.width(width), contentAlignment = align) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BodyCell(
    text: String,
    width: Dp,
    align: Alignment = Alignment.CenterStart
) {
    Box(Modifier.width(width), contentAlignment = align) {
        Text(text)
    }
}

/* -------------------------- Avg MPH over time -------------------------- */

@Composable
private fun AvgMphOverTimeTab(
    rows: List<RunEntity>
) {
    // Group by runner, sort by time, then plot
    val series: Map<String, List<Pair<Long, Double>>> = remember(rows) {
        rows.groupBy { it.userName }
            .mapValues { (_, list) ->
                list.sortedBy { it.timestamp }
                    .map { it.timestamp to it.mph }
            }
    }

    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No data for current filters.")
        }
        return
    }

    Card(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Average MPH vs Time (by runner)", style = MaterialTheme.typography.titleMedium)

            // Simple legend
            LegendRow(series.keys.toList())

            MultiLineChart(
                series = series,
                yLabel = "MPH",
                xTimeLabels = true
            )
        }
    }
}

/* --------------------- Legend + format helpers --------------------- */

@Composable
private fun LegendRow(names: List<String>) {
    if (names.isEmpty()) return
    val palette = listOf(
        Color(0xFF90CAF9), Color(0xFFFFAB91), Color(0xFFA5D6A7),
        Color(0xFFFFF59D), Color(0xFFCE93D8), Color(0xFF80CBC4)
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        names.sorted().forEachIndexed { i, name ->
            val color = palette[i % palette.size]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(14.dp).background(color, shape = MaterialTheme.shapes.small))
                Text(name, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun formatDateTick(ms: Double): String {
    val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return fmt.format(Date(ms.toLong()))
}

/* --------------------------- Chart --------------------------- */

@Composable
private fun MultiLineChart(
    series: Map<String, List<Pair<Long, Double>>>,
    yLabel: String,
    xTimeLabels: Boolean = true,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(300.dp)
        .padding(8.dp)
) {
    val allPoints = series.values.flatten()
    if (allPoints.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("No data") }
        return
    }

    val density = LocalDensity.current

    val xs = allPoints.map { it.first.toDouble() }
    val ys = allPoints.map { it.second }
    val xMin = xs.minOrNull() ?: 0.0
    val xMax = xs.maxOrNull() ?: 1.0
    val yMinRaw = ys.minOrNull() ?: 0.0
    val yMaxRaw = ys.maxOrNull() ?: 1.0

    // Pad ranges a bit so lines/points don’t touch the frame
    val yPad = (yMaxRaw - yMinRaw).coerceAtLeast(1e-3) * 0.08
    val yMin = kotlin.math.floor(yMinRaw - yPad)
    val yMax = kotlin.math.ceil(yMaxRaw + yPad)

    val xSpan = (xMax - xMin).coerceAtLeast(1.0) // avoid div-by-zero
    val ySpan = (yMax - yMin).coerceAtLeast(1e-3)

    // Colors per runner
    val palette = listOf(
        Color(0xFF90CAF9), Color(0xFFFFAB91), Color(0xFFA5D6A7),
        Color(0xFFFFF59D), Color(0xFFCE93D8), Color(0xFF80CBC4)
    )
    val names = series.keys.sorted()
    val colorFor = names.withIndex().associate { it.value to palette[it.index % palette.size] }

    Canvas(modifier) {
        val padLeft = 56f     // room for Y labels
        val padRight = 16f
        val padTop = 16f
        val padBot = 44f      // room for X labels

        val w = size.width - padLeft - padRight
        val h = size.height - padTop - padBot
        if (w <= 0f || h <= 0f) return@Canvas

        // Axis label text paints
        val yTextSizePx = with(density) { 12.sp.toPx() }
        val xTextSizePx = with(density) { 12.sp.toPx() }
        val labelPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.GRAY
            textSize = yTextSizePx
        }
        val minorPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb(64, 128, 128, 128)
            strokeWidth = with(density) { 1.dp.toPx() }
        }

        // Axes
        val x0 = padLeft
        val y0 = size.height - padBot
        drawLine(
            Color.Gray,
            start = androidx.compose.ui.geometry.Offset(x0, y0),
            end   = androidx.compose.ui.geometry.Offset(x0 + w, y0)
        )
        drawLine(
            Color.Gray,
            start = androidx.compose.ui.geometry.Offset(x0, padTop),
            end   = androidx.compose.ui.geometry.Offset(x0, y0)
        )

        // ---- Y ticks (nice 5 ticks) ----
        val yTicks = buildTicks(yMin, yMax, 5)
        yTicks.forEach { yVal ->
            val yPx = y0 - ((yVal - yMin) / ySpan * h).toFloat()
            // gridline
            drawLine(
                Color(0x22888888),
                start = androidx.compose.ui.geometry.Offset(x0, yPx),
                end   = androidx.compose.ui.geometry.Offset(x0 + w, yPx)
            )
            // label
            drawIntoCanvas { c ->
                val txt = String.format(Locale.US, "%.1f", yVal)
                val tw = labelPaint.measureText(txt)
                c.nativeCanvas.drawText(
                    txt,
                    x0 - 8f - tw,
                    yPx + yTextSizePx * 0.35f,
                    labelPaint
                )
            }
        }

        // ---- X ticks: min / mid / max (time labels) ----
        val xTicks = listOf(xMin, xMin + xSpan / 2.0, xMax)
        xTicks.forEach { xVal ->
            val xPx = x0 + ((xVal - xMin) / xSpan * w).toFloat()
            // tick
            drawLine(
                Color.Gray,
                start = androidx.compose.ui.geometry.Offset(xPx, y0),
                end   = androidx.compose.ui.geometry.Offset(xPx, y0 + 6f)
            )
            // label
            drawIntoCanvas { c ->
                val txt = if (xTimeLabels) formatDateTick(xVal) else String.format(Locale.US, "%.0f", xVal)
                val tw = labelPaint.measureText(txt)
                c.nativeCanvas.drawText(
                    txt,
                    (xPx - tw / 2f).coerceIn(padLeft, padLeft + w - tw),
                    size.height - 8f,
                    labelPaint
                )
            }
        }

        // ---- Series (lines) ----
        series.forEach { (runner, points) ->
            if (points.isEmpty()) return@forEach
            val path = Path()
            points.sortedBy { it.first }.forEachIndexed { idx, (tx, yv) ->
                val x = x0 + ((tx - xMin) / xSpan * w).toFloat()
                val y = y0 - ((yv - yMin) / ySpan * h).toFloat()
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = colorFor[runner] ?: Color(0xFF90CAF9),
                style = Stroke(width = 4f, cap = StrokeCap.Round)
            )
        }
    }
}

/* ---------------------- Tick math (nicely spaced) ---------------------- */

private fun buildTicks(minVal: Double, maxVal: Double, target: Int): List<Double> {
    val span = (maxVal - minVal).coerceAtLeast(1e-6)
    val stepRaw = span / (target.coerceAtLeast(2) - 1)
    val mag = 10.0.pow(kotlin.math.floor(kotlin.math.log10(stepRaw)))
    val norm = stepRaw / mag
    val step = when {
        norm < 1.5 -> 1.0 * mag
        norm < 3.5 -> 2.0 * mag
        norm < 7.5 -> 5.0 * mag
        else       -> 10.0 * mag
    }
    val start = kotlin.math.floor(minVal / step) * step
    val ticks = mutableListOf<Double>()
    var v = start
    // limit to avoid runaway loops
    repeat(64) {
        if (v > maxVal + step * 0.5) return@repeat
        if (v >= minVal - step * 0.5) ticks += v
        v += step
    }
    return if (ticks.size >= 2) ticks else listOf(minVal, maxVal)
}


/* ------------------------- Distribution (histograms) ------------------------- */

@Composable
private fun DistributionTab(rows: List<RunEntity>) {
    val byRunner = rows.groupBy { it.userName }
    if (byRunner.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No data") }
        return
    }
    val pad = 32f
    val barW = 18f
    val maxH = 160f

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        byRunner.forEach { (runner, list) ->
            val mphs = list.map { it.mph }
            val bins = (0..30 step 2).map { it.toDouble() }
            val hist = bins.map { bin ->
                val next = bin + 2
                val count = mphs.count { it >= bin && it < next }
                bin to count
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.Start) {
                    Text("$runner — MPH distribution", fontWeight = FontWeight.SemiBold)
                    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
                        val maxCount = (hist.maxOfOrNull { it.second } ?: 1).toFloat()
                        hist.forEachIndexed { idx, (_, count) ->
                            val h = (count / maxCount) * maxH
                            drawRect(
                                color = Color(0xFF90CAF9),
                                topLeft = androidx.compose.ui.geometry.Offset(
                                    pad + idx * (barW + 4),
                                    size.height - pad - h
                                ),
                                size = androidx.compose.ui.geometry.Size(barW, h)
                            )
                        }
                    }
                    Text("X: MPH bins (2-mph)  •  Y: Count", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
    }
}

/* ------------------------------ Leaderboards ------------------------------ */

@Composable
private fun LeaderboardsTab(rows: List<RunEntity>, appliedRunner: String?, topN: Int) {
    val fmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    val isAll = appliedRunner == null || appliedRunner == "All"
    val blocks: List<Pair<String, List<RunEntity>>> = if (isAll) {
        listOf("Overall" to rows.sortedBy { it.sprintMs }.take(topN))
    } else {
        val list = rows.filter { it.userName == appliedRunner }
            .sortedBy { it.sprintMs }
            .take(topN)
        listOf((appliedRunner ?: "") to list)
    }

    if (blocks.all { it.second.isEmpty() }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No data") }
        return
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        blocks.forEach { (title, list) ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.Start) {
                    Text("$title — Top $topN sprints", fontWeight = FontWeight.SemiBold)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        list.forEachIndexed { i, r ->
                            Text(
                                "${i + 1}. ${String.format("%.3f s", r.sprintMs / 1000.0)}  |  " +
                                        "${String.format("%.2f yd", r.rangeYards)}  |  " +
                                        "${String.format("%.2f mph", r.mph)}  " +
                                        "(${fmt.format(Date(r.timestamp))}) — ${r.userName}"
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------ Consistency ------------------------------ */

@Composable
private fun ConsistencyTab(rows: List<RunEntity>) {
    val stats = rows.groupBy { it.userName }.mapValues { (_, list) ->
        val mean = list.map { it.mph }.average()
        val sd = kotlin.math.sqrt(list.map { (it.mph - mean) * (it.mph - mean) }.average())
        mean to sd
    }
    if (stats.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No data") }
        return
    }
    val points = stats.map { (runner, pair) -> Triple(runner, pair.first, pair.second) }
    val xMin = points.minOf { it.second } - 1
    val xMax = points.maxOf { it.second } + 1
    val yMin = 0.0
    val yMax = (points.maxOf { it.third }.takeIf { it > 0.0 } ?: 1.0) * 1.2

    Card(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text("Consistency: Std-Dev of MPH vs Avg MPH (by runner)", style = MaterialTheme.typography.titleMedium)
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .padding(8.dp)
            ) {
                val pad = 40f
                val w = size.width - pad * 2
                val h = size.height - pad * 2

                drawLine(
                    Color.Gray,
                    start = androidx.compose.ui.geometry.Offset(pad, size.height - pad),
                    end = androidx.compose.ui.geometry.Offset(size.width - pad, size.height - pad)
                )
                drawLine(
                    Color.Gray,
                    start = androidx.compose.ui.geometry.Offset(pad, pad),
                    end = androidx.compose.ui.geometry.Offset(pad, size.height - pad)
                )

                points.forEach { (runner, mean, sd) ->
                    val x = pad + ((mean - xMin) / (xMax - xMin).coerceAtLeast(1e-6)) * w
                    val y = size.height - pad - ((sd - yMin) / (yMax - yMin).coerceAtLeast(1e-6)) * h
                    drawCircle(
                        Color(0xFFCE93D8),
                        radius = 8f,
                        center = androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat())
                    )
                }
            }
            Text("X: Avg MPH  •  Y: Std-Dev of MPH (lower is more consistent)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

/* ------------------------------ utils ------------------------------ */

private fun atStartOfDayUtc(ms: Long): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = ms
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun atEndOfDayUtc(ms: Long): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = ms
    cal.set(Calendar.HOUR_OF_DAY, 23)
    cal.set(Calendar.MINUTE, 59)
    cal.set(Calendar.SECOND, 59)
    cal.set(Calendar.MILLISECOND, 999)
    return cal.timeInMillis
}
