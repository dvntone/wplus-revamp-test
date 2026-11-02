# wscanplus Stream Deep-Dive #1: Android WiFi Scanning

## The problem statement

wscanplus has WiFi scanning wired up, but we don't know if the code is extracting every available data point, if silent failures are eating results on some devices, or if Android version-specific behavior is throttling output without any visible error. This document maps the complete field inventory, every known failure mode, every device-specific quirk across the wscanplus test lab, and provides a diagnostic harness that answers: "what is this device actually capable of returning?"

---

## Part 1 — Every extractable field from ScanResult

The `android.net.wifi.ScanResult` class has grown dramatically across API levels. Most apps only extract SSID, BSSID, level, and frequency. For a threat detection scanner, that's leaving 80% of the fingerprint on the table.

### Fields available at every API level (API 1+)

| Field | Type | Threat value |
|-------|------|-------------|
| `SSID` | String | Evil twin matching, SSID flood detection, hidden network (empty string) |
| `BSSID` | String | MAC-based OUI lookup → vendor classification, MAC randomization detection |
| `capabilities` | String | Auth/cipher fingerprint — WPA3-SAE vs WPA2-PSK vs OWE vs open; downgrade detection |
| `level` | int (dBm) | RSSI for proximity estimation, sudden-appearance detection, triangulation |
| `frequency` | int (MHz) | Band classification (2.4/5/6 GHz), channel overlap analysis |

### Fields added API 17+ (Android 4.2 — Nexus 6P era)

| Field | Type | Threat value |
|-------|------|-------------|
| `timestamp` | long (µs since boot) | Precise timing for correlation across nodes, scan freshness validation |

### Fields added API 23+ (Android 6.0 — most of your lab)

| Field | Type | Threat value |
|-------|------|-------------|
| `channelWidth` | int | 20/40/80/160/320 MHz — AP capability fingerprint, enterprise vs consumer classification |
| `centerFreq0` | int | Primary center frequency — precise channel identification |
| `centerFreq1` | int | Secondary segment (80+80 MHz only) — advanced AP fingerprint |
| `operatorFriendlyName` | CharSequence | Hotspot 2.0 / Passpoint operator name — carrier AP identification |
| `venueName` | CharSequence | Hotspot 2.0 venue name — context for legitimate vs rogue |
| `is80211mcResponder()` | boolean (via flags) | RTT ranging capability — if true, can range to this AP for position |

### Fields added API 30+ (Android 11)

| Field | Type | Threat value |
|-------|------|-------------|
| `getInformationElements()` | List<InformationElement> | **THE BIG ONE** — raw 802.11 IEs from the beacon/probe response frame |
| `getWifiStandard()` | int | WIFI_STANDARD_LEGACY / _11N / _11AC / _11AX / _11BE — protocol version fingerprint |

### Fields added API 31+ (Android 12)

| Field | Type | Threat value |
|-------|------|-------------|
| `isTwtResponder()` | boolean | Target Wake Time support — Wi-Fi 6 feature indicator |

### Fields added API 33+ (Android 13)

| Field | Type | Threat value |
|-------|------|-------------|
| `getSecurityTypes()` | int[] | All security types this AP supports — catches dual WPA2/WPA3 transitional mode |
| `getApMldMacAddress()` | MacAddress? | Wi-Fi 7 Multi-Link Device MAC — new identity anchor |
| `getApMloLinkId()` | int | Wi-Fi 7 MLO link ID |
| `getAffiliatedMloLinks()` | List<MloLink> | All Wi-Fi 7 MLO links — multi-radio AP fingerprint |

### The InformationElements goldmine (API 30+)

`ScanResult.getInformationElements()` returns the raw 802.11 Information Elements from the beacon frame. Each IE has an `id` (int) and `bytes` (ByteBuffer). This is the same data TShark would parse from a pcap, but available without monitor mode or root.

Key IEs for threat detection:

| IE ID | Name | Threat value |
|-------|------|-------------|
| 0 | SSID | Verify matches the SSID field (some attacks mismatch) |
| 1 | Supported Rates | 802.11b/g/n classification, unusual rate sets flag clones |
| 3 | DS Parameter Set | Channel declaration — inconsistency with frequency = spoof |
| 7 | Country | Regulatory domain — foreign country code in domestic scan = suspicious |
| 42/47 | ERP / RSN | Security capability detail — downgrade or mismatch detection |
| 45 | HT Capabilities | 802.11n feature set — capability fingerprint |
| 48 | RSN Information | Detailed cipher suite and AKM list — WPA2/3 detail beyond `capabilities` string |
| 61 | HT Operation | Channel bonding details |
| 107 | Interworking | Hotspot 2.0 metadata — carrier/venue classification |
| 127 | Extended Capabilities | Feature flags bitmap — capability fingerprint |
| 191 | VHT Capabilities | 802.11ac feature set |
| 195 | VHT Operation | 802.11ac channel config |
| 221 | Vendor Specific | **Critical** — OUI prefix identifies vendor (Cisco, Ubiquiti, etc.), custom data |
| 255 | Extension Element | Container for HE (Wi-Fi 6) and EHT (Wi-Fi 7) capabilities |

**Vendor Specific IE (221) parsing** is where you get the most threat-relevant data that no one else extracts. The first 3 bytes are the vendor OUI:
- `00:50:F2` = Microsoft WPS, WMM, WPA
- `00:17:F2` = Apple (sometimes)
- `00:40:96` = Cisco Aironet
- `50:6F:9A` = Wi-Fi Alliance (P2P, NAN, MBO, OCE)

A rogue AP running on a Raspberry Pi will have completely different Vendor Specific IEs than a genuine Cisco/Aruba/Ubiquiti AP — this is one of the highest-signal fingerprints available.

---

## Part 2 — The capabilities string is an unparsed goldmine

The `capabilities` field is a string like:
```
[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]
```

Most code treats this as opaque. For wscanplus, parse it into structured fields:

| Token | Meaning | Threat signal |
|-------|---------|--------------|
| `WPA-PSK` | WPA1 — legacy | Weak security; if unexpected, AP may be downgrade attack |
| `WPA2-PSK-CCMP` | WPA2 Personal AES | Normal consumer |
| `RSN-SAE-CCMP` | WPA3 Personal | Modern; clone unlikely to implement correctly |
| `RSN-EAP-CCMP` | WPA2 Enterprise | Enterprise AP; rogue AP rarely implements EAP |
| `OWE` | Opportunistic Wireless Encryption | Open but encrypted; enhanced open |
| `ESS` | Extended Service Set | Infrastructure mode (normal AP) |
| `IBSS` | Independent BSS | Ad-hoc network — unusual, flag it |
| `WPS` | Wi-Fi Protected Setup | PIN brute-force vector; should be flagged |
| `P2P` | Wi-Fi Direct | Peer-to-peer — flag if unexpected |
| `WAPI` | Chinese WLAN auth | Very unusual outside China; flag immediately |

A legitimate enterprise AP will show `[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA256-CCMP+TKIP][ESS]`. A rogue AP cloning the SSID will often show simpler capabilities like `[WPA2-PSK-CCMP][ESS]` — the security downgrade is a direct threat signal.

---

## Part 3 — Silent failure modes that eat your data

### Throttling (the #1 data killer)

**Android 9+ foreground:** 4 `startScan()` calls per 2-minute window. The 5th call returns `false` from `startScan()` but you still get stale results from `getScanResults()` — the staleness is invisible unless you check the `timestamp` field against expected scan intervals.

**Android 9+ background:** All background apps combined get 1 scan per 30 minutes. If wscanplus is in the background, you're getting almost nothing.

**The workaround that doesn't actually work:** `Developer Options > Wi-Fi scan throttling` toggle is a dev-only setting. You can't ship an app that requires it. You can recommend users enable it during onboarding, but you can't depend on it.

**What actually works:**
1. **Foreground Service** — keeps you in the "foreground" budget (4/2min)
2. **Passive listening** — on API 29+, register for `SCAN_RESULTS_AVAILABLE_ACTION` without calling `startScan()`. You piggyback on scans initiated by the OS and other apps for free. This is unlimited.
3. **Budget your `startScan()` calls** — treat each call as expensive. Scan on a 30-second interval, not continuously. 4 calls per 2 minutes = 1 every 30 seconds is the maximum sustainable rate.
4. **Track scan timestamps** — compare `ScanResult.timestamp` (boot-relative microseconds) between consecutive `getScanResults()` calls. If the timestamps haven't changed, you got stale data — don't re-process it.

### Permission failures (silent on some devices)

| Android version | Required for startScan | Required for getScanResults | Location Services required? |
|----------------|----------------------|---------------------------|--------------------------|
| 8.0-8.1 (API 26-27) | CHANGE_WIFI_STATE | Any of: FINE_LOCATION, COARSE_LOCATION, CHANGE_WIFI_STATE | No |
| 9 (API 28) | FINE or COARSE_LOCATION + CHANGE_WIFI_STATE | Same | **YES** |
| 10-11 (API 29-30) | FINE_LOCATION (COARSE no longer sufficient if targeting 29+) + CHANGE_WIFI_STATE | FINE_LOCATION + ACCESS_WIFI_STATE | **YES** |
| 12+ (API 31+) | NEARBY_WIFI_DEVICES (or FINE_LOCATION if not using neverForLocation) | Same | Depends on manifest declaration |
| 13+ (API 33+) | Same as 12 | Same | Same |

**The silent killer:** On Android 9+, if Location Services is toggled OFF system-wide (not your app's permission — the global toggle), `getScanResults()` returns an empty list with no error, no exception, no callback. wscanplus MUST check `LocationManager.isLocationEnabled()` before every scan and surface it to the user if disabled.

**The Samsung quirk:** Some Samsung devices (especially One UI 5+) have an additional "Improve accuracy" toggle under Location settings that must be enabled for WiFi scanning to return full results. Without it, you may get partial or empty results.

### Android 11+ permission denial trap

After two denials of a runtime permission (even without "Don't ask again"), the system treats it as permanently denied. `shouldShowRequestPermissionRationale()` returns false AND the permission isn't granted — this means the user must go to Settings manually. wscanplus must detect this state and show a deep-link to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.

---

## Part 4 — Device compatibility matrix for the wscanplus lab

### Your documented devices

| Device | API level | ScanResult fields available | InformationElements? | WiFi Standard? | RTT? | Key issues |
|--------|-----------|---------------------------|---------------------|---------------|------|------------|
| **Pixel 10 Pro XL** | 35+ | All fields through API 35 | Yes | Yes (11be) | Yes | Full capability baseline. Wi-Fi 7 MLO fields available |
| **OnePlus 10T** | ~33-34 | Through API 33 | Yes | Yes (11ax) | Likely yes | OxygenOS may modify scan behavior. Test early |
| **Motorola G4 Play 2024** | 34 | Through API 33 | Yes | Likely 11ax | Check at runtime | Budget Mediatek SoC — test IE parsing, may have vendor-specific gaps |
| **Revvl Tab 2** | 35 | Through API 35 | Yes | Check | Unlikely | Tablet — test scan behavior during split-screen/multi-window |

### Your additional devices

| Device | Approx API | ScanResult fields available | InformationElements? | Key issues |
|--------|-----------|---------------------------|---------------------|------------|
| **Samsung Galaxy S3** | 18 (4.3) | SSID, BSSID, capabilities, level, frequency, timestamp only | **No** | **minSdk 24 means this device is excluded.** If you want to support it, minSdk must drop to 18, which creates massive compat burden. Recommend using it only for evil-twin-as-target testing (i.e., it's the victim, not the scanner) |
| **LG H811 (G4)** | 23-24 (6.0-7.0) | Adds channelWidth, centerFreq0/1, operatorFriendlyName | **No** | Qualcomm Snapdragon 808. Good for testing API 23 fields. No IEs. |
| **Nexus 6P** | 23-27 (6.0-8.1) | Depends on installed OS version. Up to API 27 fields | **No** | Qualcomm 810. Known bootloop issues. Good for testing the API 23-27 permission model. |

### What this means architecturally

The device matrix creates three tiers of scan richness:

**Tier 1 (API 30+): Full fingerprint** — Pixel 10 Pro, OnePlus 10T, Moto G4 Play 2024, Revvl Tab 2
- All ScanResult fields
- InformationElements — raw beacon IE parsing
- WiFi standard identification
- RTT capability detection
- Full capabilities string parsing

**Tier 2 (API 23-29): Partial fingerprint** — Nexus 6P (if on Oreo), LG G4
- Channel width, center frequencies
- Capabilities string
- No InformationElements — vendor fingerprinting impossible at this tier
- No WiFi standard field

**Tier 3 (API < 23): Minimal fingerprint** — Samsung S3
- SSID, BSSID, capabilities, level, frequency only
- Below wscanplus minSdk 24 — excluded from scanner role

The capability probe at startup must determine which tier the device falls into and communicate this to the correlation engine so it knows what evidence quality to expect.

---

## Part 5 — Data points wscanplus should extract but probably isn't

Based on the field inventory above, here are the specific gaps most likely present in the current codebase:

### Almost certainly missing

1. **InformationElements parsing** (API 30+) — this is the single highest-value gap. Raw IEs give you vendor OUI fingerprinting, detailed RSN/cipher analysis, HT/VHT/HE capability fingerprinting, country code verification, and Vendor Specific IE classification. Without this, your threat fingerprint is shallow.

2. **WiFi standard identification** (API 30+) — `getWifiStandard()` tells you if the AP is 11n/11ac/11ax/11be. An "enterprise" AP that claims to be Cisco but reports as 11n-only is suspicious.

3. **Capabilities string parsing** — the raw string is probably stored but not parsed into structured fields (auth type, key management, cipher, ESS/IBSS, WPS, P2P). These are direct threat indicators.

4. **Passive scan piggyback** (API 29+) — registering for `SCAN_RESULTS_AVAILABLE_ACTION` without calling `startScan()` gives free unlimited scan results from OS-initiated scans. This should supplement active scanning.

5. **Scan timestamp tracking** — comparing `ScanResult.timestamp` between consecutive `getScanResults()` calls to detect stale data (throttled scan that returned old results).

6. **RTT capability flag** — `is80211mcResponder()` per AP, which feeds into whether ranging is possible for that AP. This should be extracted per scan result and persisted.

### Probably missing

7. **SecurityTypes array** (API 33+) — `getSecurityTypes()` returns all security types as integers, catching transitional WPA2/WPA3 modes that the capabilities string may not fully express.

8. **Wi-Fi 7 MLO fields** (API 33+) — MLD MAC address, MLO link ID, affiliated links. Future-proofing for Wi-Fi 7 AP fingerprinting.

9. **Hotspot 2.0 / Passpoint fields** — `operatorFriendlyName` and `venueName` are present on API 23+ but rarely extracted. These identify carrier APs and help distinguish legitimate hotspots from rogues.

10. **Connected network details** — `WifiInfo` (current connection) provides additional fields not in ScanResult: link speed, TX/RX link speed (API 29+), TDLS support, passpoint FQDN, and security type of the current connection.

---

## Part 6 — Diagnostic harness design

The harness is a standalone Activity (or a screen within wscanplus) that exercises every WiFi scanning path and produces a structured JSON report. Run it on each device in the lab, compare outputs, and you'll know exactly what each device can contribute.

### What the harness tests

```
Test 1: Permission audit
  - Check each required permission: FINE_LOCATION, CHANGE_WIFI_STATE, ACCESS_WIFI_STATE
  - Check NEARBY_WIFI_DEVICES (API 33+)
  - Check Location Services global toggle
  - Report: { permission: granted/denied/permanently_denied }

Test 2: Scan capability
  - Call startScan(), record return value
  - Wait for SCAN_RESULTS_AVAILABLE_ACTION
  - Record time-to-result, success boolean from intent extra
  - Report: { startScanResult: bool, broadcastReceived: bool, latencyMs: long }

Test 3: Field inventory
  - Call getScanResults()
  - For first result, probe every field:
    - SSID, BSSID, capabilities, level, frequency, timestamp (always)
    - channelWidth, centerFreq0, centerFreq1 (try/catch for API < 23)
    - operatorFriendlyName, venueName (try/catch)
    - getInformationElements() (try/catch for API < 30)
    - getWifiStandard() (try/catch for API < 30)
    - getSecurityTypes() (try/catch for API < 33)
    - getApMldMacAddress(), getApMloLinkId(), getAffiliatedMloLinks() (try/catch for API < 33)
    - is80211mcResponder() (try/catch)
  - Report: { field: available/unavailable/error_message } for each

Test 4: Throttle measurement
  - Call startScan() in a loop with 1-second intervals
  - Record which calls return true vs false
  - Record getScanResults() timestamp deltas
  - Stop after 10 attempts or 3 minutes
  - Report: { callIndex: n, startScanReturned: bool, resultTimestamp: long, isStale: bool }

Test 5: Passive scan test (API 29+)
  - Register for SCAN_RESULTS_AVAILABLE_ACTION WITHOUT calling startScan()
  - Wait 60 seconds, count how many broadcasts arrive
  - Report: { passiveBroadcastsReceived: int, averageIntervalMs: long }

Test 6: InformationElement depth (API 30+)
  - For each ScanResult, enumerate all IEs
  - Report count of IEs, list of IE IDs present, vendor OUI list from IE 221
  - Report: { apBssid: string, ieCount: int, ieIds: [int], vendorOuis: [string] }

Test 7: RTT capability survey
  - Filter scan results for is80211mcResponder() == true
  - Report: { totalAps: int, rttCapableAps: int, rttCapableBssids: [string] }

Test 8: Connected network detail
  - If connected to WiFi, extract WifiInfo fields:
    - linkSpeed, txLinkSpeedMbps, rxLinkSpeedMbps (API 29+)
    - passpointFqdn (API 29+), securityType
  - Report all available fields
```

### Harness output format

```json
{
  "device": {
    "model": "Pixel 10 Pro XL",
    "manufacturer": "Google",
    "apiLevel": 35,
    "androidVersion": "16",
    "wifiChipset": "unknown (not exposed by API)"
  },
  "timestamp": "2026-04-22T12:00:00Z",
  "tests": {
    "permissions": { ... },
    "scanCapability": { ... },
    "fieldInventory": { ... },
    "throttleMeasurement": { ... },
    "passiveScan": { ... },
    "informationElements": { ... },
    "rttSurvey": { ... },
    "connectedNetwork": { ... }
  }
}
```

When you run this on every device in the lab, you'll have a concrete answer to "what can this device actually give us" — not what the documentation says, but what the hardware and OS version actually return.

---

## Part 7 — Architectural recommendations

### 1. Implement a ScanResult enrichment pipeline

Don't pass raw ScanResult objects through the app. Convert immediately to a wscanplus `EnrichedScanResult` that includes:

```kotlin
data class EnrichedScanResult(
    // Core identity
    val ssid: String,
    val bssid: String,
    val oui: String,               // First 3 octets of BSSID → OUI lookup
    val vendorName: String?,       // From oui.csv

    // Signal
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val band: WifiBand,            // BAND_2_4GHZ, BAND_5GHZ, BAND_6GHZ
    val channel: Int,              // Derived from frequency
    val channelWidth: Int?,        // API 23+

    // Security (parsed from capabilities string)
    val authTypes: Set<AuthType>,  // WPA, WPA2, WPA3_SAE, OWE, OPEN, WEP, WAPI
    val keyMgmt: Set<KeyMgmt>,     // PSK, EAP, SAE, OWE, SUITE_B
    val ciphers: Set<Cipher>,      // CCMP, TKIP, GCMP_256
    val isEss: Boolean,
    val isIbss: Boolean,
    val hasWps: Boolean,
    val isP2p: Boolean,

    // Protocol
    val wifiStandard: WifiStandard?, // API 30+
    val isRttCapable: Boolean,

    // Raw beacon data (API 30+)
    val informationElements: List<ParsedIE>?,
    val vendorSpecificOuis: List<String>?,
    val countryCode: String?,       // From IE 7

    // Wi-Fi 7 (API 33+)
    val mldMacAddress: String?,
    val mloLinkId: Int?,

    // Hotspot 2.0
    val operatorName: String?,
    val venueName: String?,

    // Meta
    val scanTimestampUs: Long,      // Boot-relative microseconds
    val captureEpochMs: Long,       // Wall clock when we processed this
    val deviceTier: ScanTier,       // FULL, PARTIAL, MINIMAL
    val isStale: Boolean            // True if timestamp unchanged from previous scan
)
```

### 2. Parse capabilities string at capture time

```kotlin
fun parseCapabilities(caps: String): SecurityProfile {
    val authTypes = mutableSetOf<AuthType>()
    val keyMgmt = mutableSetOf<KeyMgmt>()
    val ciphers = mutableSetOf<Cipher>()
    var isEss = false
    var isIbss = false
    var hasWps = false
    var isP2p = false

    val blocks = caps.split("][")
        .map { it.trim('[', ']') }

    for (block in blocks) {
        when {
            block == "ESS" -> isEss = true
            block == "IBSS" -> isIbss = true
            block == "WPS" -> hasWps = true
            block.contains("P2P") -> isP2p = true
            block.startsWith("WPA-") -> {
                authTypes.add(AuthType.WPA)
                parseKeyAndCipher(block, keyMgmt, ciphers)
            }
            block.startsWith("WPA2-") || block.startsWith("RSN-") -> {
                if (block.contains("SAE")) {
                    authTypes.add(AuthType.WPA3_SAE)
                    keyMgmt.add(KeyMgmt.SAE)
                } else {
                    authTypes.add(AuthType.WPA2)
                }
                parseKeyAndCipher(block, keyMgmt, ciphers)
            }
            block == "OWE" -> authTypes.add(AuthType.OWE)
            block.contains("WAPI") -> authTypes.add(AuthType.WAPI)
        }
    }

    if (authTypes.isEmpty() && !hasWps) authTypes.add(AuthType.OPEN)

    return SecurityProfile(authTypes, keyMgmt, ciphers, isEss, isIbss, hasWps, isP2p)
}
```

### 3. Implement passive scan piggyback

```kotlin
// In your ForegroundService, register this ALONGSIDE your active scan timer
val passiveReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // This fires for ALL WiFi scans on the device — OS-initiated, other apps, everything
        // It's free — no throttle budget consumed
        val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
        if (success) {
            val results = wifiManager.scanResults
            processResults(results, source = ScanSource.PASSIVE)
        }
    }
}
registerReceiver(passiveReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
```

### 4. Add Location Services guard

```kotlin
fun isWifiScanReady(): ScanReadiness {
    val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager

    return when {
        !wifiManager.isWifiEnabled -> ScanReadiness.WIFI_DISABLED
        Build.VERSION.SDK_INT >= 28 && !locationManager.isLocationEnabled ->
            ScanReadiness.LOCATION_SERVICES_OFF
        !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ->
            ScanReadiness.PERMISSION_DENIED
        else -> ScanReadiness.READY
    }
}

enum class ScanReadiness {
    READY,
    WIFI_DISABLED,
    LOCATION_SERVICES_OFF,
    PERMISSION_DENIED
}
```

### 5. Track scan freshness

```kotlin
private var lastSeenTimestamps = mutableMapOf<String, Long>() // BSSID → last timestamp

fun processResults(results: List<ScanResult>, source: ScanSource) {
    val enriched = results.map { sr ->
        val previousTimestamp = lastSeenTimestamps[sr.BSSID]
        val isStale = previousTimestamp != null && sr.timestamp == previousTimestamp
        lastSeenTimestamps[sr.BSSID] = sr.timestamp

        enrichScanResult(sr, isStale = isStale, source = source)
    }

    // Filter out entirely stale scans (all results unchanged)
    val freshResults = enriched.filter { !it.isStale }
    if (freshResults.isEmpty() && source == ScanSource.ACTIVE) {
        // Active scan returned only stale data — likely throttled
        logThrottleEvent()
    }
}
```

---

## Part 8 — Threat signals derived from WiFi scan data alone

These heuristics use only WiFi ScanResult data — no monitor mode, no BLE, no additional hardware needed:

| Heuristic | Fields used | What it detects |
|-----------|------------|----------------|
| **SSID duplicate with different BSSID** | SSID, BSSID | Evil twin — same name, different MAC |
| **SSID duplicate with different security** | SSID, capabilities | Evil twin downgrade attack — clone with weaker auth |
| **New AP sudden appearance** | BSSID, timestamp, RSSI | Rogue AP deployment — strong signal AP that wasn't there before |
| **OUI mismatch for claimed identity** | BSSID (OUI), capabilities, wifiStandard | Enterprise AP SSID but consumer-grade OUI (Raspberry Pi, ESP32) |
| **Vendor Specific IE mismatch** | IE 221 vendor OUI | AP claims to be Cisco but has no Cisco vendor IEs |
| **Country code anomaly** | IE 7 | AP advertising foreign regulatory domain |
| **SSID flood** | SSID count per scan | >50 unique SSIDs = likely beacon flood attack |
| **WPS enabled on enterprise AP** | capabilities (WPS flag) | Security misconfiguration or rogue AP (enterprise APs shouldn't have WPS) |
| **IBSS / Ad-hoc detection** | capabilities (IBSS flag) | Unusual in residential — flag for investigation |
| **Channel saturation** | frequency, channelWidth | Multiple APs on overlapping channels = interference or attack |
| **RSSI triangulation anomaly** | level from multiple nodes | Same BSSID seen at very different RSSI by nearby nodes = possible MAC spoof |
| **Hidden SSID** | SSID == "" or null | Not inherently malicious but worth flagging — hidden networks should be rare in residential |
| **WPA3-SAE downgrade** | capabilities across time | AP that was WPA3 now showing WPA2-only = possible downgrade attack |
| **MAC randomization pattern** | BSSID bit 1 of first octet | Locally-administered bit set = randomized MAC, likely client not AP. If AP has this, suspicious |

---

## Next steps

1. **Run the diagnostic harness** on every device in the lab
2. **Compare output JSON** across devices to build the real capability matrix
3. **Implement the EnrichedScanResult pipeline** as the standard WiFi data model
4. **Add InformationElements parsing** (API 30+) — this is the single highest-ROI change
5. **Add passive scan piggyback** — free data, no throttle cost
6. **Wire capabilities parsing** into the threat heuristic engine

This is Stream #1. Next streams to build at the same depth:
- Stream #2: BLE scanning (partially covered by Punch Through session, needs harness)
- Stream #3: TShark / monitor mode on Linux
- Stream #4: Marauder serial integration
- Stream #5: Camera/IR sweep
- Stream #6: NSD / local network discovery
- Stream #7: Motion sensors / environmental
- Stream #8: Wi-Fi RTT ranging
- Stream #9: Acoustic sonar
