# Sprint Beacon — Android App (MVP BLE)

Companion Android app for your **ESP32 Sprint Beacon**. It scans for the beacon over **BLE**, connects to the custom **GATT service**, sends an **Arm** command to start a randomized countdown on the device, shows a **big live timer**, and persists finished sprints in a local **Room** database. It also includes a **History** screen with filters and multiple charts (avg MPH over time, distributions, leaderboards, and consistency).

> Firmware it pairs with: ESP32-S3 “Lidar_Beacon / Finish Gate” sketches that expose the BLE service `b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0a1` and characteristics listed below.

---

## ✨ Features

- **BLE scanner & connector** filtered by custom Service UUID
- **Arm** the beacon by writing `{"arm":1}` to the **CONTROL** characteristic
- **Live timer** during the run; **final device time** (ms) and **frozen average MPH** after finish
- **Range handling**:
  - Use live **LiDAR range** from beacon (cm → yards) _or_ enter manual range
  - **Lock** the range for a run; unlocks after result is persisted
- **Local persistence (Room)**: `UserEntity`, `RunEntity` (time, range, mph, timestamp)
- **History** with filters (runner, range, date) + tabs:
  - Data table (delete rows)
  - **Avg MPH vs Time** (multi-line, per runner)
  - **Distributions** (per-runner histograms)
  - **Leaderboards** (Top N)
  - **Consistency** (Std-Dev of MPH vs Avg MPH)
- Modern **Jetpack Compose + Material 3** UI, dark-friendly
- Robust BLE: notifications with CCCD, periodic reads (poll), MTU 247, high-priority link

---

## 🧩 BLE Protocol

**Service UUID** (scan filter): `b8c7f3f4-4b9f-4a5b-9c39-36c6b4c7e0a1`

| Characteristic | UUID                                      | Props            | Payload                                                                 |
| --- | --- | --- | --- |
| STATUS | `...0b2` | READ, NOTIFY | JSON (UTF‑8). Includes keys like `have_start` (bool), `sprint_ms` (uint32), `lidar_cm` (uint16 or 0xFFFF), `uptime`, etc. |
| RANGE | `...0d4` | READ, NOTIFY | `uint16` **cm**, **little‑endian**. `0xFFFF` means unknown. |
| SPRINT | `...0f6` | READ, NOTIFY | `uint32` **ms**, **little‑endian**. Non‑zero when a finish is computed on device. |
| CONTROL | `...0c3` | WRITE (with/without response) | JSON commands. **Arm**: `{"arm":1}` → beacon randomizes 2–5 s countdown and starts. |

App logic:
- Detect **start** on `STATUS.have_start` rising edge → start the big timer.
- Detect **finish** on `have_start` falling edge and/or a new `SPRINT` value → stop timer, compute MPH, persist.
- MPH formula: `mph = yards * 3600 / (1760 * seconds)` (yards measured/entered in UI).

---

## 📱 Screens

### Connect
- Scan filtered by Service UUID.
- Shows list of matching devices with **name, MAC, RSSI**.
- Connect → navigates to **Main**.

### Main
- **Runner selector** (Room users, “Add user” inline).
- **Range controls**: toggle **Use LiDAR** or enter manual range; range locks at **start** and unlocks after **finish**.
- **Big timer** (auto‑fitting font).  
- **Arm** button → writes `{"arm":1}` to CONTROL.
- **Results** card → final **sprint time** and **average MPH**.

### History
- Filters panel (runner, range min/max, date start/end).
- Tabs: **Data**, **Avg MPH over time**, **Distribution**, **Leaderboards**, **Consistency**.
- Delete a row with the trash icon in the Data tab.

---

## 🏗️ Project Structure (high-level)

- `MainActivity.kt` — BLE scanner/connector, GATT plumbing, timer & Arm, Room integration, **Main** and **Connect** screens (Compose).
- `HistoryScreenHost.kt` — **History** screen with filters, tables, and charts (Compose).
- `data/` (virtual) — Room entities and DAOs (embedded at top of `MainActivity` in this MVP):
  - `UserEntity(name)`
  - `RunEntity(id, userName, sprintMs, rangeYards, mph, timestamp)`
  - `UserDao`, `RunDao`, `AppDb`

> In this MVP, entities/DAOs/DB are defined in the same file for convenience. In production, consider splitting into separate packages.

---

## 🔧 Build & Run

### Requirements
- **Android Studio** Koala+ (Giraffe OK)
- **minSdk** 24+, **targetSdk** 34 (adjust as needed)
- Kotlin + Jetpack Compose + Material 3

### Gradle (module) — key dependencies
```kotlin
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

### Permissions
On **Android 12+** (API 31+):
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```
On **Android 10–11**:
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```
The app requests these at runtime in `MainActivity`.  
BLE is central‑only; no background scanning required.

### ProGuard / R8
No special rules are needed for this MVP. If you obfuscate, keep GATT UUID strings and JSON keys if used reflectively.

---

## 🔁 Data Model & Persistence (Room)

```kotlin
@Entity(tableName = "users")
data class UserEntity(@PrimaryKey val name: String, val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val userName: String,
    val sprintMs: Long,
    val rangeYards: Double,
    val mph: Double,
    val timestamp: Long
)
```
- **Auto‑discard** obvious junk before charts: `5–120 yd`, `0.5–30 s`, `2–30 mph` (configurable in `HistoryScreenHost`).  
- **Delete** a row from the Data tab to remove bad runs.

> _Export CSV_ and cloud sync are on the roadmap (see below).

---

## ▶️ Typical Flow

1. **Power** the beacon (ensure firmware exposes the service/characteristics above).
2. Open app → **Scan** on Connect screen → **Connect** to your device.
3. On **Main**:
   - Pick **Runner** (or Add user).
   - Choose **Use LiDAR** for range or type it manually.
   - Tap **Arm** → device randomizes a 2–5 s countdown and starts.
   - **Run**. Device computes finish → app shows device **sprint time** and **average MPH**.
4. Visit **History**:
   - Filter by runner/date/range.
   - Review **Data**, **Avg MPH vs Time**, **Distributions**, **Leaderboards**, **Consistency**.
   - Delete any bad runs.

---

## ⚙️ Configuration Points

- **Service/Characteristic UUIDs**: update in `MainActivity` if firmware changes.
- **Polling period**: `pollIntervalMs` (default 800 ms). Lower for snappier UI, higher for less BLE traffic.
- **Charts**: palettes, tick density, and bounds are in `HistoryScreenHost` helpers.
- **Sane data filter**: adjust in `sane(r: RunEntity)`.
- **Units**: UI uses **yards**; LiDAR arrives as **cm** → yards (`cm / 91.44`). Change if you switch to meters/feet.

---

## 🧪 Troubleshooting

- **“No devices found”**: Ensure the beacon is advertising the correct **Service UUID** and your phone’s BT is ON.
- **Connects but no data**: Notifications must be enabled (app writes CCCD). Watch for STATUS/RANGE/SPRINT reads in logcat.
- **Permissions denied**: On Android 12+, both `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` are required.
- **Timer feels off**: The authoritative time is **device‑computed** `SPRINT`. App timer is for UX; final time uses device `sprint_ms`.
- **Range stuck**: Range **locks** on start and **unlocks** after finish/persist. This prevents mid‑run edits.
- **MTU / throughput**: App sets MTU 247 and high connection priority after connect; older phones may ignore.

---

## 📈 Roadmap

- CSV **export/share** for runs
- **Cloud sync** (Drive/Sheets or Supabase) and team dashboards
- Multi‑beacon **lanes** and session management
- Firmware **controls** (toggle LiDAR, thresholds) from app
- Themes + **dark mode charts** polish, richer legends
- Robust **BLE reconnect** strategies and background service

---

## 🔒 Privacy

- Data is stored **locally** on device in a Room DB.
- No analytics or network calls in this MVP.
- BLE is used only to communicate with your beacon.

---

## 📝 License

MIT — do whatever you want, no warranty.  
Add your copyright and year if publishing.

---

## 🙏 Acknowledgements

- **ESP32‑S3** firmware & BLE protocol (your project)
- **Jetpack Compose**, **Room**, **Material 3**

---

## 📷 Screenshots (optional placeholders)

- Connect (scan results list)
- Main (big timer + Arm + range controls)
- History tabs (Data / Avg MPH / Distribution / Leaderboards / Consistency)

