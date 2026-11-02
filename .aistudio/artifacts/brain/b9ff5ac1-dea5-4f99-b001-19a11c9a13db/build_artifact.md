# wscan+ Android Build Artifact: v1.0.0-debug

**Target Device:** Pixel 10 Pro XL  
**Target OS:** Android 17 (API 35+), Native AVF Debian Terminal  
**Git Branch:** `fix/tailscale-mcp-avf-connectivity`  
**Package:** `com.aistudio.wscanplus.px10`  
**Build Status:** `BUILD SUCCESSFUL` (all 39 Gradle tasks completed, 26/26 unit tests passed)

---

## 📦 APK Artifact Locations

1. **Artifact Directory:** `wscanplus-debug.apk` (in this Artifacts panel)
2. **Build Outputs:** `/.build-outputs/app-debug.apk`
3. **Gradle Build Output:** `/app/build/outputs/apk/debug/app-debug.apk`
4. **Public Web Assets:** `/public/app-debug.apk` and `/public/wscanplus-debug.apk`

---

## 📲 Direct Web Download & ADB Install

- **Direct Download via Browser / Curl:**
  ```bash
  curl -O https://ais-dev-cgfpi53k3wc76kkyob3kpw-200260704924.us-west2.run.app/app-debug.apk
  ```

- **Direct Sideload via ADB:**
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```

---

## 🛠️ Verification Highlights

- **Tailscale Remote MCP**: Hot-toggleable listener binding to `0.0.0.0:8080` with socket-level IP firewalling allowing only loopback and verified Tailscale CGNAT addresses (`100.64.0.0/10` and `fd7a:115c:a1e0::/48`).
- **Debian Guest Command Hardening**: Sanitized tool probing and argument escaping preventing shell command injection into the AVF Debian guest.
- **7-State Diagnostics**: Real-time status reporting on the Integrations tab for local MCP, tailnet connectivity, remote reachable state, and Debian verification.
