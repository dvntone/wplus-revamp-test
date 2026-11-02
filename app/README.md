# wscan+ (Android Edition)

> Wireless threat intelligence and intrusion detection platform with native Pixel AVF terminal integration, Shizuku/Root bridges, modular red/blue toolchain, hardware GPS/SDR forward-reverse telemetry, and .nyx AI sentinel.

---

## 📡 Key Architectural Highlights

* **Single-Flight Hardware Scan Coordinator (`ScanCoordinator.kt`)**
  * Prevents overlapping hardware scans across backends.
  * Rapid tap debouncing and instant scan signaling into continuous IDS loops.
  * Live spectrum aggregation (2.4 GHz / 5 GHz / 6 GHz, security suites, signal distribution).

* **Attack Heuristics & Threat Detection (`AttackHeuristics.kt`)**
  * **Evil Twin / Rogue AP Detection**: Identifies unexpected BSSIDs, encryption downgrades (WPA2/WPA3 to Open), and OUI vendor mismatches.
  * **Karma / MANA Detection**: Detects rogue APs simultaneously broadcasting multiple disparate SSIDs.
  * **Deauth Floods**: Sliding-window tracking of 802.11 management frames.
  * **PMKID Sniffing**: Identifies anomalous EAPOL M1 frames from unassociated MAC addresses.
  * **KRACK Detection**: Nonce-reuse and duplicate EAPOL M3 replay counter detection.
  * **Forensic Evidence Maps**: Every alert exposes granular `supportingEvidence` (rogue BSSID, observed BSSID cluster, RSSI delta, OUI comparisons).

* **Multi-Tier Execution Backends (`IntegrationsManager.kt`)**
  * **Pixel AVF Linux VM (`PixelAvfDebianGuest.kt`)**: Authenticated TCP/SSH socket channel to guest Debian container with strict isolation (never substitutes Android host shell). Tracks 5 discrete states (`installed`, `authorized`, `running`, `reachable`, `verified`).
  * **Termux RUN_COMMAND Bridge (`TermuxBridge.kt`)**: Dispatches commands via the official `com.termux.RUN_COMMAND` contract with asynchronous `PendingIntent` callbacks.
  * **Shizuku Privileged Shell (`ShizukuWifiScanner.kt`)**: Executes privileged diagnostic commands without root.
  * **Root (su)**: Full superuser access for raw monitor mode and packet injection.

* **On-Device Agent MCP Server (`AgentMcpServer.kt`)**
  * Local HTTP/SSE Model Context Protocol server on `127.0.0.1:8765` for CLI AI tools (Claude Code, Gemini CLI, Cursor, GlassHaven terminal) to inspect live captures and trigger audits.

---

## 🛠️ Project Structure

```text
├── app/
│   ├── src/main/
│   │   ├── java/com/wscanplus/android/
│   │   │   ├── core/         # Heuristics, AVF guest client, Termux bridge, MCP server
│   │   │   ├── service/      # ScanCoordinator, Wi-Fi scanners, Watchdog service
│   │   │   ├── data/db/      # Room database for persistent threat logs
│   │   │   └── ui/           # Jetpack Compose M3 screens and LiveScanPane
│   │   ├── res/              # Drawables, icons, layout resources
│   │   └── AndroidManifest.xml
│   └── src/test/java/        # Unit tests (ScanCoordinator, Heuristics, Integrations)
├── gradle/                   # Gradle wrapper and version catalog (libs.versions.toml)
└── build.gradle.kts          # Top-level build configuration
```

---

## 🚀 Building & Testing

### Prerequisites
* JDK 17+
* Android SDK 34 (UpsideDownCake) / Build-Tools 34.0.0

### Run Tests
```bash
gradle :app:testDebugUnitTest
```

### Build Debug APK
```bash
gradle :app:assembleDebug
```
Built APK location: `app/build/outputs/apk/debug/app-debug.apk`

---

## 🔒 Security & Privacy Notice
wscan+ operates locally by default. Frame ingestion and MCP diagnostic endpoints bind to localhost (`127.0.0.1`) unless explicitly configured otherwise. Signing credentials, keystores, and private API keys are excluded from source control.
