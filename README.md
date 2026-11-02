# wscan+ (Wi-Fi Intrusion Detection System for Android)

**wscan+** is an advanced Wi-Fi Intrusion Detection System (WIDS) for Android designed to detect and alert users to common 802.11 wireless attacks including **Evil Twin / Karma**, **Deauthentication floods**, **PMKID sniffing**, and **KRACK** in real time.

## Features

- **Heuristic Intrusion Detection Engine**:
  - **Evil Twin / Karma**: Detects duplicate SSIDs broadcasting from rogue BSSIDs and flags OUI vendor mismatches.
  - **Deauth Flood**: Detects rapid bursts of 802.11 Deauthentication/Disassociation frames.
  - **PMKID Sniffing**: Identifies anomalous EAPOL handshake M1 frames from unassociated client MACs.
  - **KRACK**: Identifies key reinstallation attacks via duplicate EAPOL Message-3 replay counters.
- **Modern Jetpack Compose UI**:
  - Clean Material 3 design with dynamic light/dark theming and edge-to-edge support.
  - Live scan status and recent detection cards with severity indicators.
  - Interactive "Simulate" test trigger to verify heuristics on devices or emulators.
- **Alerts & Data Export**:
  - Local persistence via Room SQLite database.
  - Severity filtering (All / High / Medium).
  - Wireshark-compatible **PCAP** export and standard **CSV** log export.
- **Gemini AI Threat Analysis**:
  - Leverages Google Gemini AI to analyze threat patterns, categorize attack intent, and generate mitigation guidance.
  - Built-in local security audit fallback when offline.
- **Multiple Scanner Backends**:
  - OTG USB Wi-Fi Adapter support.
  - Nexmon monitor mode for supported Broadcom chipsets.
  - Shizuku privileged user-space scanning to bypass OS throttling.
  - Root scanner (`airmon-ng` / `iw dev`).
  - Standard Android Wi-Fi manager scanner.
- **Advanced Terminal & System Integrations**:
  - **Direct Pixel AVF Linux Terminal Access**: Direct shell interaction with Google Pixel's Android Virtualization Framework (`com.google.android.virtualmachine.terminal`) Debian/Ubuntu container for deep wireless inspections and CLI workflows.
  - **On-Device MCP (Model Context Protocol) Bridge Server**: Embedded server exposing standardized MCP tools (`avf_run_command`, `wscan_get_threats`, `wscan_analyze_threats`, `wscan_system_status`) over localhost or LAN (port 8080) for seamless integration with CLI AI agents (Claude Code, Gemini CLI, Cursor) and terminal environments (GlassHaven, SSH Haven, Termux).
  - **REST API Endpoints**: `/mcp` (JSON-RPC), `/api/terminal` (direct command runner), `/api/threats` (live threat JSON feed), and `/status`.
  - **Shizuku Privileged ADB Service**: Elevates commands to ADB UID 2000 without requiring root; triggers privileged non-throttled scans (`cmd wifi start-scan`).
  - **Root Access & Superuser (su)**: Detects Magisk, KernelSU, and APatch; executes elevated commands (`airmon-ng`, `iw dev`, `tcpdump`) for monitor mode and raw capture.
  - **Wireless / ADB Debugging**: Manages USB and Wireless Debugging states, with direct shortcuts to developer options and diagnostic ADB queries.
  - **Interactive Diagnostic Terminal**: Integrated shell runner on the Terminal tab with tabbed interface for AVF Shell, AI Agent Bridge, and System Status.
- **Companion Mode & Cloud Sync**:
  - Push scan results to companion desktop relay over LAN/USB.
  - Firebase Realtime Database sync.

## Project Structure

```
wscan+/
├── app/
│   ├── src/main/java/com/wscanplus/android/
│   │   ├── core/         # Attack heuristics, OUI lookup, exporters, AI services
│   │   ├── data/db/      # Room database, ThreatLog entities, DAO
│   │   ├── service/      # Background IDS scanning service, watchdog, hardware backends
│   │   ├── ui/           # Jetpack Compose UI (Screens, Theme, Components)
│   │   ├── MainActivity.kt
│   │   └── WscanPlusApplication.kt
│   ├── src/main/res/     # Drawables, mipmaps, strings, XML resources
│   └── build.gradle.kts
├── gradle/
│   └── libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
```

## Build & Run

```bash
gradle assembleDebug
```
