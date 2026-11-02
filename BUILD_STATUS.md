# wscan+ — Build Status & Technical Architecture

> Last updated: October 30, 2026  
> Engine: Native Android Kotlin & Jetpack Compose (Target SDK 35, Compile SDK 35, Min SDK 29)  
> Status: **HEALTHY & VERIFIED**

---

## Executive Summary & Source Code Audit Resolutions

Following the comprehensive static inspection and security assessment, all critical, high, and medium technical findings have been resolved:

| Category | Issue Addressed | Resolution / Hardening Status |
|---|---|---|
| **Critical Security** | Unauthenticated remote code execution on `AgentMcpServer` (`0.0.0.0:8080`, `/api/terminal`, `avf_run_command`) | **Resolved**: Defaults to loopback (`127.0.0.1`), enforces Bearer Token authentication (`Authorization: Bearer <token>`), eliminates wildcard CORS, gates root execution behind strict user preference |
| **Critical Scanner** | `UsbWifiScanner.kt` returned unconditional `emptyList()` | **Resolved**: Converts parsed `iw dev <iface> scan` records into valid `ScanResult` instances via `ScanResultCompat` |
| **High Security** | Cleartext traffic enabled globally in `AndroidManifest.xml` | **Resolved**: Configured `network_security_config.xml` strictly allowing cleartext for `127.0.0.1` and `localhost`, enforcing HTTPS for external traffic |
| **High Heuristics** | Evil Twin false positives on legitimate multi-AP mesh networks | **Resolved**: Calibrated `AttackHeuristics.kt` to check OUI prefixes, encryption capabilities, and Karma/MANA multi-SSID anomalies |
| **High Detection** | Disconnected Deauth / EAPOL frame ingestion | **Resolved**: Ingestion pipeline wired into `WifiScanningService`, `AgentMcpServer` REST/MCP endpoints (`/api/frames/deauth`, `/api/frames/eapol`) and hardware bridges |
| **High Scanner** | Synchronous, stale `StandardWifiScanner` queries | **Resolved**: Uses asynchronous `SCAN_RESULTS_AVAILABLE_ACTION` broadcast receiver and filters out stale cached entries |
| **High AVF** | Tentative `/system/bin/andlinux-run` command execution | **Resolved**: Structured execution lifecycle states (`SUBMITTED`, `RUNNING`, `COMPLETED`, `FAILED`), duration telemetry, and real AVF/su/privileged paths |
| **Medium Policy** | Broad media permissions in Manifest | **Resolved**: Removed unnecessary `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`, `READ_MEDIA_IMAGES` |
| **Medium Tooling** | Missing build wrappers | **Resolved**: Added standard `gradlew` and `gradlew.bat` scripts |

---

## Verified Build & Test Matrix

| Component | Target | Status | Verification Command |
|---|---|---|---|
| App Compilation | Android 15 (API 35) / Jetpack Compose | **PASSING** | `compile_applet` |
| Unit & Heuristics Tests | Local JVM / JUnit4 / MockK | **PASSING** | `gradle :app:testDebugUnitTest` |
| Threat Engine | Evil Twin, Deauth, PMKID, KRACK | **PASSING** | Local heuristics suite |
| MCP Bridge | JSON-RPC 2.0 / REST API | **HARDENED** | Loopback + Bearer Token gated |
| Hardware Bridges | NMEA GPS Forward / RTL-TCP / Kismet | **ACTIVE** | `AvfHardwareBridge` |

---

## Architectural Layout

```text
com.wscanplus.android/
├── MainActivity.kt                # Jetpack Compose Edge-to-Edge root
├── WscanPlusApplication.kt        # Room database & service lifecycle
├── core/
│   ├── AgentMcpServer.kt          # Hardened JSON-RPC 2.0 & REST MCP Agent bridge
│   ├── AppPreferences.kt          # Encrypted settings & secure token management
│   ├── AttackHeuristics.kt        # Multi-AP mesh aware IDS detection heuristics
│   ├── AvfHardwareBridge.kt       # Bi-directional NMEA GPS, RTL-TCP & Kismet bridge
│   ├── AvfToolchainDetector.kt    # Red/Blue team terminal tool detection
│   ├── LinuxEnvironmentService.kt # AVF terminal command runner with state tracking
│   ├── IntegrationsManager.kt     # AVF, Shizuku, Root su, and Wireless ADB manager
│   ├── EnrichedScanData.kt        # 802.11 IEs, OUIs, and capability parsers
│   └── GeminiApiService.kt        # Cloud/local AI reasoning integration
├── service/
│   ├── WifiScanningService.kt     # Foreground IDS loop & frame ingestion dispatcher
│   ├── UsbWifiScanner.kt          # External USB Wi-Fi OTG scanner (real ScanResults)
│   ├── RootWifiScanner.kt         # airmon-ng / iw dev monitor-mode scanner
│   ├── ShizukuWifiScanner.kt      # Unthrottled user-space ADB scanner
│   └── StandardWifiScanner.kt     # Asynchronous broadcast-based Android scanner
└── ui/
    ├── screens/ModularToolScreen.kt # 5-tab modular Red/Blue security console
    └── screens/HomeScreen.kt        # Real-time radar, mascot, and threat feed
```

---

## Build Commands

```bash
# Compile and assemble Android Debug APK
gradle :app:assembleDebug

# Run unit tests
gradle :app:testDebugUnitTest

# Run on-device or CI tests
./gradlew test
```
