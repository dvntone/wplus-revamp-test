# Capture Technology Notes

This document records the research findings behind wscan+'s packet capture
strategy on both platforms and explains why specific technologies were chosen
or rejected.

---

## Windows — Npcap

**Npcap** (https://npcap.com/) is the recommended Windows packet capture
driver. It replaces the legacy WinPcap (abandoned 2013) and is actively
maintained by the nmap project.

### Monitor Mode via WlanHelper

Npcap ships a utility `WlanHelper.exe` (installed to
`C:\Windows\System32\Npcap\WlanHelper.exe`) when the installer option
**"Support raw 802.11 traffic (and monitor mode) for wireless adapters"** is
selected. wscan+ uses this utility to automatically enable/disable monitor mode
on a Wi-Fi adapter when a scan session starts/stops.

**Usage in code:** `WlanHelperService` wraps `WlanHelper.exe` via
`Process.Start()`:

```csharp
// Enable
WlanHelper.exe "Wi-Fi" mode monitor

// Disable (always call on session end to restore connectivity)
WlanHelper.exe "Wi-Fi" mode managed
```

**Adapter compatibility:** Not all Windows Wi-Fi adapters support monitor mode.
Known working adapters:
- **Alfa AWUS036NHA** (Atheros AR9271) — best Linux/Windows compat
- **Alfa AWUS036ACH** (Realtek RTL8812AU) — dual-band, widely supported
- **TP-Link TL-WN722N v1** (AR9271) — budget option

Intel, Qualcomm, and most OEM integrated adapters return error code 50
(not supported) when WlanHelper attempts to set monitor mode.

Reference: https://secwiki.org/w/Npcap/WiFi_adapters

---

## Windows — WSL2 + airmon-ng + airodump-ng

For maximum capture capability, wscan+ integrates with WSL2 to leverage the
full **aircrack-ng** suite. This path provides:

- **airmon-ng** – enables monitor mode, kills conflicting processes
  (NetworkManager, wpa_supplicant) before switching modes.
- **airodump-ng** – channel-hopping passive scan that discovers all
  visible APs/clients across all 2.4 GHz and 5 GHz channels. Captures
  WPA2 4-way handshakes for KRACK detection. CSV output is parsed by
  `Wsl2BridgeService.ReadAirodumpCsvAsync()`.
- **tshark** – fallback PCAP capture when aircrack-ng is unavailable.

### USB Wi-Fi Passthrough — usbipd-win

WSL2 does not have direct access to USB devices. The `usbipd-win` tool
(https://github.com/dorssel/usbipd-win) exports USB devices from Windows
to WSL2 over USB/IP. Steps:

```powershell
# Windows (as admin)
usbipd list                       # find your adapter BUSID
usbipd bind   --busid 2-3
usbipd attach --wsl --busid 2-3
```

```bash
# WSL2 (inside Ubuntu)
lsusb                             # confirm adapter visible
sudo airmon-ng start wlan0
sudo airodump-ng wlan0mon --write /mnt/c/Users/Public/wscanplus --output-format csv,pcap
```

### WSL2 Kernel Limitation

The default WSL2 kernel does **not** include 802.11 drivers. For full
monitor-mode support a custom kernel is required with `cfg80211`, `mac80211`,
and the target driver compiled in. A ready-made guide is available at:
https://github.com/Mauville/Wireless-for-WSL

This is a significant setup burden. The `DependencyChecker` and `SetupPage`
guide users through this process.

---

## Android — Capture Technology Comparison

| Technology     | Monitor Mode | ARM64 | Root | Notes                                    |
|----------------|:------------:|:-----:|:----:|------------------------------------------|
| **bcmon**      | ✅           | ❌    | ✅   | Legacy, BCM4329/4330 only, last update 2013. Unsupported. |
| **Nexmon**     | ✅           | ✅*   | ✅   | Modern bcmon successor, specific Broadcom chips only |
| **airmon-ng**  | ✅           | ✅    | ✅   | Via Termux aircrack-ng or Kali NetHunter |
| **Shizuku**    | ❌           | ✅    | ❌   | ADB-level privileges, no monitor mode, bypasses OS throttle |
| **iw dev**     | ✅*          | ✅    | ✅   | Driver-dependent, Qualcomm custom kernels |
| **tcpdump**    | ✅*          | ✅    | ✅   | Capture only if adapter already in monitor mode |
| **Standard WifiManager** | ❌ | ✅  | ❌   | Throttled to 4 scans/2 min on Android 10+ |
| **PCAPdroid**  | ❌           | ✅    | ❌   | Device traffic only via VPN service, no OTA sniffing |

*Depends on adapter/kernel driver support.

### bcmon — Why Not Used

bcmon (https://code.google.com/archive/p/bcmon/) was the first Android tool to
enable Wi-Fi monitor mode. It targets Broadcom BCM4329 and BCM4330 chips only,
found in very old devices (Samsung Galaxy S1/S2, HTC Desire etc.). It:

- Has not been maintained since 2013.
- Only works on 32-bit ARM (ARMv7) devices.
- Is incompatible with all Android devices manufactured after ~2013.

**wscan+ does not include bcmon integration.** Users with legacy devices that
have Nexmon patches should use [NexmonScanner] instead.

### Nexmon — Modern Successor to bcmon

[Nexmon](https://github.com/seemoo-lab/nexmon) patches Broadcom/Cypress Wi-Fi
firmware to expose monitor mode and raw packet injection. It supports ARM64 on
specific devices:

| Device         | Chipset    | ARM64 |
|----------------|------------|-------|
| Nexus 5        | BCM4339    | No    |
| Nexus 6P       | BCM4358    | Yes   |
| Galaxy S7 (Exynos) | BCM4359 | Yes  |
| Galaxy S8 (Exynos) | BCM4361 | Yes  |
| Galaxy S10 (Exynos)| BCM4375B1| Yes |

Modern Google Pixel phones (Pixel 3+) use Qualcomm Wi-Fi chips and are
**not supported** by Nexmon. No software-only monitor mode solution exists
for these devices.

wscan+ includes `NexmonScanner` which detects Nexmon by checking for
`nexutil` and `libnexmon.so` at runtime.

### airmon-ng on Android — Termux/Kali NetHunter

On rooted devices with Termux installed, `airmon-ng` and `airodump-ng` are
available via `pkg install aircrack-ng`. This provides the same channel-hopping
passive scan capability as the Windows WSL2 path.

The `RootWifiScanner` class detects airmon-ng in standard Termux paths and uses
it automatically when available.

### Scanner Priority in WifiScanningService

```
1. NexmonScanner      (Broadcom firmware patch, full monitor mode)
2. ShizukuWifiScanner (ADB-level privileges, no throttle)
3. RootWifiScanner    (airmon-ng if available, else iw dev scan)
4. StandardWifiScanner (WifiManager, throttled)
```

---

## AI Integration (Gemini)

Both platforms pass formatted threat log summaries to the Google Gemini API
(`gemini-1.5-flash` model) for natural-language risk assessment. Users supply
their own API key in Settings; no key is ever hard-coded or committed to VCS.

The Gemini integration benefits from richer capture data:
- Monitor mode captures → more accurate deauth flood counts
- airodump-ng channel hopping → more complete SSID/BSSID map for Evil Twin detection
- PCAP export → can be attached to prompts for frame-level analysis (future)

---

## References

| Resource | URL |
|----------|-----|
| Npcap user guide | https://npcap.com/guide/ |
| Npcap compatible adapters | https://secwiki.org/w/Npcap/WiFi_adapters |
| WlanHelper.exe | https://deepwiki.com/nmap/npcap/5.1-wlanhelper |
| usbipd-win | https://github.com/dorssel/usbipd-win |
| airmon-ng | https://www.aircrack-ng.org/doku.php?id=airmon-ng |
| airodump-ng | https://www.aircrack-ng.org/doku.php?id=airodump-ng |
| Nexmon | https://github.com/seemoo-lab/nexmon |
| Nexmon Android setup | https://book.hacktricks.wiki/en/generic-methodologies-and-resources/pentesting-wifi/enable-nexmon-monitor-and-injection-on-android.html |
| WSL2 + Wireless | https://github.com/Mauville/Wireless-for-WSL |
| PCAPdroid (no-root capture) | https://github.com/emanuele-f/PCAPdroid |
