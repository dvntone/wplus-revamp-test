package com.wscanplus.android.core

/**
 * AttackHeuristics – core detection logic for 802.11 wireless attacks.
 *
 * This class is intentionally platform-agnostic; the same algorithms are
 * mirrored in the Windows C# implementation.
 *
 * Detects:
 *  - Evil Twin / Karma  (duplicate SSID with different BSSID or unexpected OUI)
 *  - Deauthentication flood (high-frequency Deauth/Disassoc management frames)
 *  - PMKID sniffing    (anomalous EAPOL Auth requests from unassociated MACs)
 *  - KRACK             (retransmitted WPA2 Handshake Message-3 / duplicate nonces)
 */
class AttackHeuristics {

    // ── Data models ──────────────────────────────────────────────────────────

    enum class AttackType(val displayName: String) {
        EVIL_TWIN("Evil Twin / Karma"),
        DEAUTH_FLOOD("Deauth Flood"),
        PMKID_SNIFFING("PMKID Sniffing"),
        KRACK("KRACK"),
    }

    enum class Severity { LOW, MEDIUM, HIGH }

    data class AccessPoint(
        val bssid: String,
        val ssid: String,
        val rssi: Int,
        val capabilities: String = "",
    )

    data class DeauthFrame(
        val sourceMac: String,
        val destMac: String,
        val bssid: String,
        val reasonCode: Int,
        val timestampMs: Long,
    )

    data class EapolFrame(
        val sourceMac: String,
        val bssid: String,
        val messageNumber: Int,   // 1–4 for WPA2 4-way handshake
        val replayCounter: Long,
        val nonce: ByteArray,
        val timestampMs: Long,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EapolFrame) return false
            return sourceMac == other.sourceMac &&
                bssid == other.bssid &&
                messageNumber == other.messageNumber &&
                replayCounter == other.replayCounter &&
                nonce.contentEquals(other.nonce) &&
                timestampMs == other.timestampMs
        }

        override fun hashCode(): Int {
            var result = sourceMac.hashCode()
            result = 31 * result + bssid.hashCode()
            result = 31 * result + messageNumber
            result = 31 * result + replayCounter.hashCode()
            result = 31 * result + nonce.contentHashCode()
            result = 31 * result + timestampMs.hashCode()
            return result
        }
    }

    data class ThreatEvent(
        val attackType: AttackType,
        val severity: Severity,
        val bssid: String,
        val ssid: String,
        val description: String,
        val timestampMs: Long = System.currentTimeMillis(),
        val supportingEvidence: Map<String, String> = emptyMap(),
    )

    // ── Internal state ───────────────────────────────────────────────────────

    /** Maps SSID → set of BSSIDs seen broadcasting that SSID. */
    private val ssidToBssids = mutableMapOf<String, MutableSet<String>>()

    /** Maps SSID → map of BSSID to AccessPoint profile for capability/security comparison. */
    private val ssidToProfiles = mutableMapOf<String, MutableMap<String, AccessPoint>>()

    /** Maps BSSID → set of SSIDs broadcast by this BSSID (to detect Karma/MANA spoofing). */
    private val bssidToSsids = mutableMapOf<String, MutableSet<String>>()

    /** Maps BSSID → list of Deauth frame timestamps (rolling 10-second window). */
    private val deauthFrameLog = mutableMapOf<String, MutableList<Long>>()

    /** Maps BSSID → set of known-associated client MACs. */
    private val associatedClients = mutableMapOf<String, MutableSet<String>>()

    /** Maps "bssid+sourceMac" → EAPOL M3 replay counters (to detect KRACK). */
    private val eapolM3Counters = mutableMapOf<String, MutableList<Long>>()

    // ── Detection thresholds ─────────────────────────────────────────────────

    /** Deauth frames within [DEAUTH_WINDOW_MS] that triggers a flood alert. */
    val deauthThreshold: Int = DEFAULT_DEAUTH_THRESHOLD

    companion object {
        const val DEAUTH_WINDOW_MS = 10_000L
        const val DEFAULT_DEAUTH_THRESHOLD = 15

        /**
         * Maximum number of distinct keys tracked in each state map.
         * Prevents unbounded memory growth in long-running sessions or in
         * high-density Wi-Fi environments with extreme SSID/BSSID churn.
         */
        private const val MAX_TRACKED_ENTRIES = 1_000
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Process a freshly scanned list of visible access points and detect
     * Evil Twin / Karma scenarios while avoiding false positives on legitimate multi-AP mesh networks.
     *
     * @param scanResults current scan results (all visible APs)
     * @param ouiLookup   optional OUI resolver; if provided, vendor mismatches
     *                    are flagged as additional evidence
     * @return list of [ThreatEvent]s detected (empty when clean)
     */
    fun analyzeEvilTwin(
        scanResults: List<AccessPoint>,
        ouiLookup: OuiLookup? = null,
    ): List<ThreatEvent> {
        val threats = mutableListOf<ThreatEvent>()

        for (ap in scanResults) {
            if (ap.ssid.isBlank()) continue

            // Guard against unbounded map growth in high-density environments.
            if (ssidToBssids.size >= MAX_TRACKED_ENTRIES && !ssidToBssids.containsKey(ap.ssid)) {
                android.util.Log.w("AttackHeuristics",
                    "ssidToBssids limit ($MAX_TRACKED_ENTRIES) reached; skipping SSID '${ap.ssid}'")
                continue
            }

            // Check for Karma / MANA attack: same BSSID claiming multiple disparate SSIDs
            val ssidsForBssid = bssidToSsids.getOrPut(ap.bssid) { mutableSetOf() }
            ssidsForBssid += ap.ssid
            if (ssidsForBssid.size >= 3) {
                threats += ThreatEvent(
                    attackType = AttackType.EVIL_TWIN,
                    severity = Severity.HIGH,
                    bssid = ap.bssid,
                    ssid = ap.ssid,
                    description = "Karma/MANA rogue AP anomaly: BSSID ${ap.bssid} is simultaneously broadcasting ${ssidsForBssid.size} distinct SSIDs (${ssidsForBssid.take(4).joinToString()}).",
                    supportingEvidence = mapOf(
                        "attackSubtype" to "KARMA_MANA_MULTI_SSID",
                        "rogueBssid" to ap.bssid,
                        "observedSsidsCount" to ssidsForBssid.size.toString(),
                        "karmaMultiSsidEvidence" to ssidsForBssid.joinToString(", "),
                        "detectionScope" to "BEACON_SCAN"
                    )
                )
            }

            val knownBssids = ssidToBssids.getOrPut(ap.ssid) { mutableSetOf() }
            val knownProfiles = ssidToProfiles.getOrPut(ap.ssid) { mutableMapOf() }

            if (knownBssids.isNotEmpty() && ap.bssid !in knownBssids) {
                // New BSSID for an already-known SSID
                val refAp = knownProfiles.values.firstOrNull()
                val isSecurityDowngrade = refAp != null && isEncrypted(refAp.capabilities) && !isEncrypted(ap.capabilities)
                val ouiMismatch = isOuiMismatch(ap.bssid, knownBssids.first(), ouiLookup)

                if (isSecurityDowngrade || ouiMismatch) {
                    val conflictBssids = knownBssids.joinToString()
                    val vendor = ouiLookup?.lookupVendor(ap.bssid)
                    val existingVendor = ouiLookup?.lookupVendor(knownBssids.first())
                    val reason = if (isSecurityDowngrade) {
                        "Critical security downgrade detected: known network is encrypted (${refAp?.capabilities}) but rogue AP offers unencrypted open access."
                    } else {
                        if (vendor != null && existingVendor != null)
                            "OUI vendor mismatch: expected '$existingVendor', got rogue vendor '$vendor'."
                        else
                            "BSSID OUI prefix mismatch against known cluster (previously: $conflictBssids)."
                    }

                    val rssiDeltaStr = if (refAp != null) {
                        "${ap.rssi - refAp.rssi} dBm (Rogue: ${ap.rssi} dBm vs Legitimate: ${refAp.rssi} dBm)"
                    } else "N/A"

                    threats += ThreatEvent(
                        attackType = AttackType.EVIL_TWIN,
                        severity = Severity.HIGH,
                        bssid = ap.bssid,
                        ssid = ap.ssid,
                        description = "SSID '${ap.ssid}' seen from new BSSID ${ap.bssid}. $reason",
                        supportingEvidence = mapOf(
                            "attackSubtype" to if (isSecurityDowngrade) "SECURITY_DOWNGRADE" else "OUI_MISMATCH",
                            "observedBssids" to knownBssids.joinToString(", "),
                            "rogueBssid" to ap.bssid,
                            "rssiDelta" to rssiDeltaStr,
                            "encryptionSuiteMismatch" to if (isSecurityDowngrade) "Legitimate: ${refAp?.capabilities ?: "Unknown"} -> Rogue: ${ap.capabilities.ifBlank { "OPEN" }}" else "None",
                            "ouiMatch" to if (ouiMismatch) "Mismatch (Rogue: ${vendor ?: getOuiPrefix(ap.bssid)} vs Legitimate: ${existingVendor ?: getOuiPrefix(knownBssids.first())})" else "Match",
                            "detectionScope" to "BEACON_SCAN",
                            "spectrumLimitationNote" to "Evidence derived from broadcast 802.11 beacon/probe frames; full over-the-air frame analysis requires monitor mode / AVF guest."
                        )
                    )
                } else {
                    android.util.Log.d("AttackHeuristics", "Multi-AP ESS roaming detected for SSID '${ap.ssid}' with consistent OUI/security: ${ap.bssid}")
                }
            }

            knownBssids += ap.bssid
            knownProfiles[ap.bssid] = ap
        }

        return threats
    }

    private fun isEncrypted(capabilities: String): Boolean =
        capabilities.contains("WPA", ignoreCase = true) ||
        capabilities.contains("RSN", ignoreCase = true) ||
        capabilities.contains("WEP", ignoreCase = true) ||
        capabilities.contains("SAE", ignoreCase = true)

    private fun isOuiMismatch(bssidA: String, bssidB: String, ouiLookup: OuiLookup?): Boolean {
        if (ouiLookup != null) {
            val vA = ouiLookup.lookupVendor(bssidA)
            val vB = ouiLookup.lookupVendor(bssidB)
            if (vA != null && vB != null) return !vA.equals(vB, ignoreCase = true)
        }
        val prefixA = getOuiPrefix(bssidA)
        val prefixB = getOuiPrefix(bssidB)
        return prefixA.isNotBlank() && prefixB.isNotBlank() && prefixA != prefixB
    }

    private fun getOuiPrefix(bssid: String): String {
        val parts = bssid.replace("-", ":").uppercase().split(":")
        return if (parts.size >= 3) "${parts[0]}:${parts[1]}:${parts[2]}" else ""
    }

    /**
     * Record a Deauth/Disassociation management frame and detect flooding.
     *
     * @param frame the captured Deauth frame
     * @return a [ThreatEvent] if a flood is detected, null otherwise
     */
    fun analyzeDeauthFrame(frame: DeauthFrame): ThreatEvent? {
        val now = frame.timestampMs
        val key = frame.bssid

        val timestamps = deauthFrameLog.getOrPut(key) { mutableListOf() }
        timestamps += now
        // Evict entries older than the rolling window
        timestamps.removeAll { it < now - DEAUTH_WINDOW_MS }
        // When the rolling list drains to empty, remove the BSSID key entirely
        // to keep the map bounded (evict entries with no recent activity first).
        if (timestamps.isEmpty()) {
            deauthFrameLog.remove(key)
            return null
        }

        return if (timestamps.size >= deauthThreshold) {
            val broadcast = frame.destMac.equals("ff:ff:ff:ff:ff:ff", ignoreCase = true)
            ThreatEvent(
                attackType = AttackType.DEAUTH_FLOOD,
                severity = if (broadcast) Severity.HIGH else Severity.MEDIUM,
                bssid = frame.bssid,
                ssid = "",
                description = "${timestamps.size} Deauth frames in ${DEAUTH_WINDOW_MS / 1000}s " +
                    "from BSSID ${frame.bssid} targeting ${frame.destMac}.",
                supportingEvidence = mapOf(
                    "attackSubtype" to "DEAUTH_FLOOD",
                    "bssid" to frame.bssid,
                    "targetMac" to frame.destMac,
                    "sourceMac" to frame.sourceMac,
                    "reasonCode" to frame.reasonCode.toString(),
                    "frameCountInWindow" to timestamps.size.toString(),
                    "windowDurationSec" to (DEAUTH_WINDOW_MS / 1000).toString(),
                    "isBroadcast" to broadcast.toString()
                )
            )
        } else null
    }

    /**
     * Mark a client as associated with a given AP (needed for PMKID detection).
     */
    fun recordAssociation(clientMac: String, bssid: String) {
        associatedClients.getOrPut(bssid) { mutableSetOf() } += clientMac
    }

    /**
     * Analyse an EAPOL frame for PMKID sniffing and KRACK indicators.
     *
     * @param frame    the EAPOL frame to analyse
     * @return list of [ThreatEvent]s (may contain multiple when both attacks observed)
     */
    fun analyzeEapolFrame(frame: EapolFrame): List<ThreatEvent> {
        val threats = mutableListOf<ThreatEvent>()

        // ── PMKID detection ───────────────────────────────────────────────
        // Message 1 of the 4-way handshake (M1) can carry a PMKID.
        // If the source is not a known associate, flag it.
        if (frame.messageNumber == 1) {
            val isAssociated = associatedClients[frame.bssid]?.contains(frame.sourceMac) == true
            if (!isAssociated) {
                threats += ThreatEvent(
                    attackType = AttackType.PMKID_SNIFFING,
                    severity = Severity.MEDIUM,
                    bssid = frame.bssid,
                    ssid = "",
                    description = "EAPOL M1 from unassociated MAC ${frame.sourceMac} " +
                        "targeting AP ${frame.bssid}. Possible PMKID harvest attempt.",
                    supportingEvidence = mapOf(
                        "attackSubtype" to "PMKID_SNIFFING",
                        "bssid" to frame.bssid,
                        "unassociatedClientMac" to frame.sourceMac,
                        "handshakeMessage" to "EAPOL_M1",
                        "replayCounter" to frame.replayCounter.toString()
                    )
                )
            }
        }

        // ── KRACK detection ───────────────────────────────────────────────
        // KRACK forces retransmission of M3. Detect duplicate replay counters
        // for M3 from the same AP.
        if (frame.messageNumber == 3) {
            val key = "${frame.bssid}:${frame.sourceMac}"
            // Don't track new key pairs once the map is at capacity.
            if (eapolM3Counters.size >= MAX_TRACKED_ENTRIES && !eapolM3Counters.containsKey(key))
                return threats
            val counters = eapolM3Counters.getOrPut(key) { mutableListOf() }
            if (frame.replayCounter in counters) {
                threats += ThreatEvent(
                    attackType = AttackType.KRACK,
                    severity = Severity.HIGH,
                    bssid = frame.bssid,
                    ssid = "",
                    description = "Duplicate EAPOL M3 replay counter (${frame.replayCounter}) " +
                        "from AP ${frame.bssid} — possible KRACK nonce-reuse attack.",
                    supportingEvidence = mapOf(
                        "attackSubtype" to "KRACK_NONCE_REUSE",
                        "bssid" to frame.bssid,
                        "clientMac" to frame.sourceMac,
                        "duplicateReplayCounter" to frame.replayCounter.toString(),
                        "handshakeMessage" to "EAPOL_M3"
                    )
                )
            } else {
                counters += frame.replayCounter
            }
        }

        return threats
    }

    /** Reset all internal state (e.g. when the user stops a scan session). */
    fun reset() {
        ssidToBssids.clear()
        ssidToProfiles.clear()
        bssidToSsids.clear()
        deauthFrameLog.clear()
        associatedClients.clear()
        eapolM3Counters.clear()
    }
}
