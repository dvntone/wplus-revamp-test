# Changelog

All notable changes to wscanPLUS are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [1.0.0] – 2025-03-04

### Added – Android
- Foreground Wi-Fi IDS scanning service with Shizuku, root/tcpdump, and standard WifiManager backends
- Evil Twin / Karma, Deauth Flood, PMKID Sniffing, and KRACK detection heuristics
- Room/SQLite local threat log persistence
- Alerts screen with live log observation (Flow-backed)
- **Settings screen** – Firebase URL, Device ID, Gemini API key, desktop companion host
- **AI Analysis screen** – Gemini 1.5 Flash threat assessment
- **Companion client** – forwards Android Wi-Fi scan results to the Windows desktop relay server
- Light / Dark Material 3 theme toggle
- Firebase Realtime Database sync boilerplate
- Adaptive launcher icon (vector, API 26+)
- Release build signing via `keystore.properties` (template provided)

### Added – Windows
- Npcap / SharpPcap raw 802.11 packet capture
- WSL2 bridge service for monitor-mode captures via usbipd-win
- OUI IEEE database lookup (SQLite)
- Threat log persistence (SQLite via Microsoft.Data.Sqlite)
- Export to `.csv` and `.pcap` (Wireshark-compatible, libpcap format)
- **Dependency checker** – checks for Npcap and WSL2 at startup; shows install links
- **Companion relay server** – HTTP endpoint (port 9000) for Android companion app integration
- **Setup page** – visual dependency status with install buttons
- Firebase Firestore sync boilerplate
- Gemini AI Analysis page
- Settings page – Firebase URL, Device ID, WSL2 distro
- Light / Dark theme toggle

### Added – Shared
- Attack heuristics: Evil Twin/Karma, Deauth Flood, PMKID Sniffing, KRACK
- DataExporter: CSV and libpcap PCAP serialisation
- Unit tests: C# (xUnit) and Kotlin (JUnit 4)
- GitHub Actions CI workflows for both Android and Windows builds
- GitHub Pages landing page (`docs/index.html`)

---

## Threat Detection Reference

| Attack | Detection Method | Severity |
|--------|-----------------|----------|
| Evil Twin / Karma | Duplicate SSID with different BSSID; optional OUI mismatch | HIGH |
| Deauth Flood | ≥15 Deauth/Disassoc frames in 10-second window | HIGH (broadcast) / MEDIUM |
| PMKID Sniffing | EAPOL M1 from unassociated client MAC | MEDIUM |
| KRACK | Duplicate EAPOL M3 replay counter | HIGH |

---

## AI Integration Readiness

Both apps accept a user-supplied **Gemini API key** (stored in secure preferences,
never hard-coded) and send formatted threat log summaries to the
`gemini-1.5-flash` model for natural-language threat assessment.

The `CompanionRelayServer` (Windows) + `CompanionClient` (Android) JSON protocol
enables real-time cross-device threat correlation, making AI analysis richer by
combining raw packet-level data from the desktop with mobile scan results.
