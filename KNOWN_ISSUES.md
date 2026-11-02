# wscanPLUS — Known Issues

> Last updated: 2026-11-02
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

### KI-W001 [OPEN — needs owner] No workflow has ever started on `main` (`startup_failure`)
- **Affected:** every push / dispatch on `wplus-revamp-test` (runs #1–#11, 2026-11-02)
- **Symptom:** Each push produces exactly one run with `conclusion: startup_failure`, no jobs, an empty
  workflow name, and `workflow_id 404152221` whose path is `BuildFailed` (state `deleted`). The six
  real workflows (`android.yml`, `code-quality.yml`, …) have **zero** runs.
- **Ruled out:** YAML syntax — all six files parse, and the embedded `github-script` JS compiles.
- **Likely cause (repo/account settings, not code):** Actions blocked before job creation. Check, in order:
  1. Settings → Actions → General → *Actions permissions* — must allow `actions/*` **and** third-party
     actions used here (`softprops/action-gh-release`, `SonarSource/sonarcloud-github-action`).
  2. Account Billing → Actions spending limit / payment method (a billing lock gives `startup_failure` with no jobs).
  3. Any repository ruleset requiring a workflow that no longer exists (the deleted `BuildFailed` entry).
- **Note:** `BUILD_STATUS.md` reports "HEALTHY & VERIFIED"; that has not been verified by CI on this repo.

### KI-W002 [FIXED — pending merge] SonarCloud job misconfigured
- `projectBaseDir: android` (directory removed in the root-level refactor) → now `.`.
- `./gradlew jacocoTestReport` — task does not exist; aborted Gradle before tests ran (masked by `|| true`).
- No `sonar-project.properties` and no `sonar.projectKey` → now passed explicitly
  (`vars.SONAR_PROJECT_KEY`, default `<owner>_<repo>`).
- Windows Sonar job removed (no `windows/` tree in this repo).
- Still requires `SONAR_TOKEN` secret + `SONAR_ORGANIZATION` variable (job is skipped without the variable).

### KI-W011 [FIXED — pending merge] Workflow / build defects found in triage
| Area | Defect | Effect |
|---|---|---|
| `code-quality.yml` | `./gradlew … \| tee log` under default `bash -e` (no `pipefail`) | Compile/test/APK failures always reported ✅ — gate could never fail |
| `code-quality.yml` | Windows job targets non-existent `windows/WscanPlus.sln` | Gate failed on every run; `build-confirmed` marker never uploaded |
| `code-quality.yml` | PR comment body spliced into a JS template literal; body contains backticks | `post-summary` threw a syntax error on every PR; also a script-injection path via branch name |
| `code-quality.yml` | `post-summary` had no `pull-requests: write` | Comment creation 403s on repos with read-only default token |
| `code-quality.yml`, `android-release.yml` | Boolean `inputs.x == 'true'` (string) | Always false → `android_checks` dispatch skipped the job; `create_release` never created a release |
| `android.yml`, `android-release.yml` (×2) | Signing secrets interpolated into an unquoted heredoc | Passwords containing `$` or `` ` `` silently corrupted |
| `android-release.yml` | `apksigner` hardcoded to `build-tools/35.0.0`, errors swallowed | Verification silently skipped when that version is absent |
| `notify-waiting.yml` | Reads `SONAR_TOKEN_PRESENT` that is never set; flags signing secrets unconditionally | Every Code Quality / Android failure blamed on missing secrets |
| `notify-waiting.yml` | `inputs.run_id` spliced into JS | Script injection via dispatch input |
| `app/build.gradle.kts` | `google-services` plugin applied with no `google-services.json` in repo | `processDebugGoogleServices` fails → no build can succeed. Plugin now applied only when the file exists |
| `gradlew` | Committed without the executable bit | `./gradlew` fails locally/out of CI (CI worked around with `chmod`) |
| `scripts/validate.{sh,ps1}` | `cd android/` | Local validator broken since root-level refactor |

### KI-W012 [OPEN] Remaining CI follow-ups (not changed)
- `windows.yml` is dormant (path filter `windows/**` never matches). Delete it, or restore the Windows tree.
- `android.yml` and `code-quality.yml` both run `test` + `assembleDebug` on every push — duplicate runner minutes.
- `android-release.yml` auto-bump counts **draft** releases as "previous"; publishing a draft created by
  `workflow_dispatch` makes a new tag push, which re-runs the pipeline and can create a second draft.
- `ktlint` binary is downloaded without checksum verification.
- `notify-waiting.yml` opens a `ci-attention` issue for every failure with no PR — noisy on `main`.
- `SonarSource/sonarcloud-github-action` is deprecated upstream; migrate to `SonarSource/sonarqube-scan-action`.
- AGP 8.5.2 + `compileSdk 35` relies on `android.suppressUnsupportedCompileSdk`; upgrade AGP to ≥ 8.7.
- Doc drift: `KI-W005`–`KI-W010` and `README` still reference `android/…` paths.
- versionCode formula: PATCH ≥ 100 collides with the next minor (`1.0.100` and `1.1.0` both → `10100900`),
  and a malformed `version_name` on dispatch (e.g. `foo`) is accepted with versionCode `200`. Validate input.
- Release builds without a `google-services.json` now succeed (Firebase config absent). Harmless today —
  `FirebaseSyncService` is never instantiated — but wire a `GOOGLE_SERVICES_JSON` secret before enabling sync.

### KI-W013 [FIXED — pending merge] Code-review follow-ups on the triage commit
| Finding | Resolution |
|---|---|
| Backslashes in signing secrets eaten by `Properties.load()` | `\` doubled before writing; round-trip tested |
| `google-services.json` per-variant locations ignored | Plugin applied if any `src/**/google-services.json` exists |
| Fork PRs: `post-summary` 403s | Job skipped when head repo ≠ base repo |
| Marker comment not found past 30 comments | `github.paginate` (code-quality **and** notify-waiting) |
| Backticks in branch name inject Markdown | Stripped from ref in comment |
| `validate.sh/.ps1` abort when `dotnet` present | Windows checks skipped when `windows/` is absent |
| Default Sonar project key undocumented | `SONAR_PROJECT_KEY` documented in `REQUIRED_SETUP.md` §2 |
| `apksigner` lookup (env fallback, empty glob, `-rc` dirs) | SDK path fallback, stable versions only, explicit error |
| Sonar test step results unused | `sonar.junit.reportPaths` passed |
| Release built without Firebase | Not changed — no runtime path today (see KI-W012) |

Offline tests for the workflow logic: `./scripts/workflow-tests/run.sh`.

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
