# Copilot Instructions — wscanPLUS

> These instructions govern ALL automated coding by GitHub Copilot, Copilot agent PRs, and AI-assisted
> development in this repository. Read and follow them entirely before making any change.

---

## 0. Repository Status

**wscanPLUS is being merged into the WiFi Sentry unified platform.**

This repo is maintained as:
- The canonical source for `AttackHeuristics.kt` (Android) and `AttackHeuristics.cs` (Windows)
- The canonical source for the scanner chain services (`NexmonScanner.kt`, `ShizukuWifiScanner.kt`, etc.)
- A reference implementation for the Windows Npcap/WSL2 capture modules

**New feature development belongs in the wifisentry repo, not here.**
Bug fixes and improvements to detection logic here will be ported to wifisentry.

---

## 1. First Rule — Check Build Status

**Before writing a single line of code, check `BUILD_STATUS.md` in the repo root.**

- If CI is showing `action_required` on main: diagnose the blocked check first.
- The `Needs Attention` notification workflow loops when a required status check is failing. Find and fix the root check.
- Never add features on top of a broken build.

---

## 2. Project Overview

wscanPLUS is a cross-platform WiFi Intrusion Detection System:
- **Android:** Kotlin/Jetpack Compose, API 30+, Shizuku or root preferred
- **Windows:** .NET 8 / WinUI 3, Npcap for packet capture, WSL2 for monitor mode

Core detection targets: Evil Twin, Karma, Deauth Flood, PMKID Sniffing, KRACK

---

## 3. Android / Kotlin Rules

### Scanner Chain (Do Not Break)
Priority order is fixed. Do not change it:

```
1. NexmonScanner      — Broadcom firmware patch, root, ARM64 (~6 devices)
2. ShizukuWifiScanner — ADB-level, no throttle
3. UsbWifiScanner     — External USB OTG adapter
4. RootWifiScanner    — airmon-ng via shell
5. StandardWifiScanner — Throttled WifiManager fallback
```

- `WatchdogService` MUST catch failures in any tier and fall back silently.
- `NexmonScanner` must throw a typed exception (not crash) on unsupported hardware.
- The app MUST compile and function fully on Standard tier alone.

### Threading
- All scanner operations: `Dispatchers.IO`
- UI updates: `Dispatchers.Main`
- Never access `WifiManager` without null check

### Kotlin Standards
- Explicit types on all lambda parameters — no implicit type inference in `filterValues`, `map`, etc.
- Use `?.let` / `?: return` for null safety. No `!!` operators.
- New UI: Jetpack Compose only. No new XML layouts.
- State: `StateFlow` / `SharedFlow`. No `LiveData` for new code.

### Dependency Rules
- Pin ALL Gradle versions exactly. No `+` suffixes.
- All versions defined in `gradle/libs.versions.toml`.

---

## 4. Windows / .NET Rules

### Capture Modules
- `NpcapCaptureModule.cs` is the primary capture path. Npcap must be installed.
- `Wsl2BridgeService.cs` is the secondary path. Handle gracefully when WSL2 is not configured.
- `WlanHelperService.cs` wraps `WlanHelper.exe`. It WILL return error code 50 on most integrated adapters — this is expected. Log it, tell the user they need an external adapter, and continue.
- `ElevationHelper.cs` handles UAC. Packet capture REQUIRES admin elevation. If not elevated, prompt — do not silently fail.

### WinUI 3 Standards
- Use MVVM pattern. Logic goes in `Core/`, not in `Pages/` code-behind.
- All async operations use `async/await`. No blocking calls on the UI thread.
- All `Pages/` `.xaml.cs` files should be minimal — binding only, no business logic.

### .NET Dependency Rules
- Pin ALL NuGet package versions exactly in `.csproj`. No floating versions.
- `SharpPcap` version must match installed Npcap version. Do not upgrade independently.

---

## 5. Detection Standards

These thresholds are fixed. Do not change without explicit instruction:

| Threat | Severity | Threshold |
|---|---|---|
| Evil Twin / Karma | HIGH | Duplicate SSID + different BSSID, or probe response to any SSID |
| Deauth Flood | HIGH | 15+ deauth frames / 10 seconds |
| Deauth targeting this device | HIGH | Any deauth frame with device MAC as destination |
| PMKID Sniffing | MEDIUM | EAPOL M1 from unassociated client |
| KRACK | HIGH | Duplicate EAPOL M3 replay counter |

---

## 6. CI / Workflow Rules

- Do not modify `.github/workflows/` without explicit instruction.
- Do not bypass SonarCloud or Code Quality Gate checks.
- `android.yml` and `windows.yml` must both stay green on `main`.
- The `notify-waiting.yml` workflow fires when checks are blocked. Fix the blocked check — do not disable the notification workflow.

---

## 7. What NOT to Do

| What not to do | Why |
|---|---|
| Add features while CI shows `action_required` | Compounds failures |
| Use implicit Kotlin lambda types | Type inference breaks Gradle builds |
| Hard-crash on Nexmon unsupported hardware | Must be a typed exception, caught by watchdog |
| Block the UI thread with WifiManager / scanner calls | Crash on all Android versions |
| Hardcode port 9000 in new code | Make companion relay port configurable |
| Remove `WatchdogService` | It is the entire fallback safety net |
| Float Gradle or NuGet dependency versions | Silent breaking updates |
| Add features here instead of wifisentry | wscanPLUS is a merge source, not the target |

---

## 8. PR Standards

Every Copilot-generated PR must:
1. Reference the `KNOWN_ISSUES.md` issue ID it addresses (e.g., "Fixes KI-W001")
2. Describe what changed and why
3. Not mix unrelated changes in one PR
4. Pass all CI checks before merge
5. Update `BUILD_STATUS.md` session log with a one-line summary
