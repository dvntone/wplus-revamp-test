# wscanPLUS — Known Issues

> Last updated: 2026-03-05
> Update this file whenever a new issue is found or an existing issue is resolved.
> NOTE: wscanPLUS is being merged INTO wifisentry. New feature development should target the unified wifisentry repo. This repo is maintained for reference and the detection engine code.

---

## Status Key
- `[OPEN]` — Active issue, not yet fixed
- `[IN PROGRESS]` — Being worked on
- `[FIXED]` — Resolved (keep for 30 days then archive)
- `[WONT FIX]` — Deliberate decision not to fix
- `[MERGE TARGET]` — Will be resolved by the wifisentry merge

---

## Critical — CI / Build

### KI-W001 [OPEN] All Recent CI Runs Show `action_required`
- **Affected:** `.github/workflows/` — all workflows on `main`
- **Symptom:** "Needs Attention – Notify on Blocked / Failing Checks" workflow fires repeatedly, concluding `action_required`.
- **Root cause:** Likely a protected branch rule or required status check that is failing, causing the notification workflow to loop.
- **Impact:** Unclear if Android and Windows builds are passing. Last confirmed Android build was on branch `copilot/optimize-file-structure-and-troubleshoot`, not main.
- **Fix needed:** Inspect the blocked checks. Resolve the underlying failing status check on main.

### KI-W002 [OPEN] SonarCloud Failing on Feature Branch
- **Affected:** `copilot/optimize-file-structure-and-troubleshoot` branch
- **Symptom:** SonarCloud analysis concludes `failure`; Code Quality Gate also failing.
- **Root cause:** Likely missing `SONAR_TOKEN` secret or misconfigured `sonar-project.properties`.
- **Fix needed:** Verify `SONAR_TOKEN` secret and `SONAR_ORGANIZATION` variable in repo settings.

---

## High Priority — Hardware / Platform Limitations

### KI-W003 [WONT FIX] Most Integrated WiFi Chips Cannot Enter Monitor Mode on Windows
- **Affected:** `windows/WscanPlus/Capture/WlanHelperService.cs`
- **Symptom:** `WlanHelper.exe` returns error code 50 (unsupported) on most integrated adapters.
- **Decision:** This is a hardware limitation, not a code bug. The app requires a compatible external USB adapter (e.g., Alfa AWUS036NHA, TP-Link TL-WN722N v1) for Windows monitor mode.
- **User guidance:** Document clearly in README. Show hardware requirements during `SetupPage`.

### KI-W004 [OPEN] WSL2 Setup Is High Friction
- **Affected:** `windows/WscanPlus/Capture/Wsl2BridgeService.cs`
- **Symptom:** Enabling WSL2 monitor mode via `airodump-ng` requires: WSL2 installation, custom kernel with 802.11 drivers, `usbipd-win` for USB passthrough. This is documented as "a significant setup burden."
- **Impact:** Most Windows users cannot use the WSL2 capture path without guided setup.
- **Fix needed:** The `SetupPage` must detect WSL2 state and walk the user through each step. Alternatively, fall back gracefully to Npcap-only mode.

### KI-W005 [OPEN] Nexmon Scanner Limited to ~6 Phone Models
- **Affected:** `android/app/src/main/java/com/wscanplus/android/service/NexmonScanner.kt`
- **Symptom:** Nexmon firmware patching only works on: Galaxy S7, S8, S9, S10 (ARM64 with Broadcom chip). Other devices silently fall through to next scanner tier.
- **Status:** This is expected behavior — the scanner chain handles fallback. But if Nexmon init fails loudly (exception vs. silent fail), it must be caught and logged.
- **Fix needed:** Verify `NexmonScanner` throws a typed exception on unsupported hardware (not a crash). Confirm `WatchdogService` catches it and promotes to next tier.

---

## Medium Priority — Detection

### KI-W006 [OPEN] No Active Deauth Detection Against This Device
- **Affected:** `android/app/.../AttackHeuristics.kt`
- **Symptom:** Deauth flood detection counts frames globally (15+ frames/10s). Does not specifically detect deauth frames targeting this device's MAC address.
- **Impact:** A targeted deauth attack on this device could be missed if overall frame count is low.
- **Fix needed:** Add targeted deauth check: deauth frame where destination MAC == this device's MAC = HIGH severity alert regardless of frame count.

### KI-W007 [OPEN] No GPS Context in Threat Analysis
- **Affected:** Android threat logging, AI analysis
- **Symptom:** Threat logs record BSSID/SSID/severity but not GPS coordinates. AI analysis cannot use location to reduce false positives.
- **Note:** This will be resolved in the wifisentry merge, which has GPS infrastructure.

### KI-W008 [OPEN] Firebase Sync Unverified
- **Affected:** `android/app/.../FirebaseSyncService.kt`
- **Symptom:** Firebase sync code exists but `google-services.json` in repo is a placeholder. Real credentials must be provided by user.
- **Fix needed:** Document clearly that Firebase sync requires user to provide their own `google-services.json`. The app must function fully without it.

---

## Low Priority

### KI-W009 [OPEN] `CompanionClient.kt` Port Hardcoded to 9000
- **Affected:** `android/app/.../CompanionClient.kt`
- **Symptom:** Android companion relay connects to Windows host on port 9000, hardcoded.
- **Fix needed:** Make port configurable via `AppPreferences`. Default 9000 is fine, but must be changeable for networks where port 9000 is blocked.

### KI-W010 [OPEN] No Scan History Export on Android
- **Affected:** `android/app/.../DataExporter.kt`
- **Symptom:** `DataExporter` appears to support CSV and Wireshark-compatible formats but export trigger from UI is unverified.
- **Fix needed:** Confirm export works end-to-end from `AlertsScreen` UI to file system. Test on Android 10+.

---

## Merge Targets (Resolved by wifisentry Merge)

These issues will be resolved when wscanPLUS scanner/detection code is merged into wifisentry:

| Issue | Resolution in wifisentry merge |
|---|---|
| KI-W007 (no GPS) | wifisentry has GPS + WiGLE infrastructure |
| KI-W004 (WSL2 friction) | wifisentry onboarding wizard will cover this |
| KI-W008 (Firebase) | wifisentry uses MongoDB primary, Firebase optional |

---

## Archive (Resolved > 30 Days)

_Nothing archived yet._
