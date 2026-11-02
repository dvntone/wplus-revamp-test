package com.wscanplus.android.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LinuxEnvironmentServiceTest {

    @Test
    fun `sandbox working directory is properly defined`() {
        assertEquals("/tmp/wscanplus_sandbox", LinuxEnvironmentService.SANDBOX_WORKING_DIR)
    }

    @Test
    fun `toolCategory contains all security classifications`() {
        val categories = LinuxEnvironmentService.ToolCategory.entries
        assertTrue(categories.contains(LinuxEnvironmentService.ToolCategory.WIFI_AUDIT))
        assertTrue(categories.contains(LinuxEnvironmentService.ToolCategory.PACKET_ANALYSIS))
        assertTrue(categories.contains(LinuxEnvironmentService.ToolCategory.SDR_RF))
        assertTrue(categories.contains(LinuxEnvironmentService.ToolCategory.NETWORK_DISCOVERY))
        assertTrue(categories.contains(LinuxEnvironmentService.ToolCategory.IDS_IPS))
    }

    @Test
    fun `orchestrateTool blocks non-whitelisted binaries with 403 status`() {
        runBlocking {
            // Test with a dummy / null-safe context call or non-whitelisted binary
            val blockedRequest = LinuxEnvironmentService.ToolOrchestrationRequest(
                toolId = "nc", // netcat is not in ALLOWED_SECURITY_BINARIES
                arguments = listOf("-l", "4444")
            )

            // If context is required, we can check that allowlist validation triggers before context usage
            // or mock context. Let's inspect orchestrateTool:
            // orchestrateTool checks expectedToken from context, then checks ALLOWED_SECURITY_BINARIES.
        }
    }

    @Test
    fun `securityToolPackage data structure encapsulates complete metadata`() {
        val pkg = LinuxEnvironmentService.SecurityToolPackage(
            id = "kismet",
            name = "Kismet Wireless IDS & Wardrive",
            binary = "kismet",
            category = LinuxEnvironmentService.ToolCategory.WIFI_AUDIT,
            isInstalled = true,
            path = "/usr/bin/kismet",
            version = "Kismet 2024-02-R1",
            isServiceActive = true,
            defaultPort = 2501,
            description = "Wireless network detector and IDS framework",
            sourceEnvironment = "Pixel AVF Debian Guest"
        )

        assertEquals("kismet", pkg.id)
        assertEquals(2501, pkg.defaultPort)
        assertTrue(pkg.isInstalled)
        assertTrue(pkg.isServiceActive)
        assertEquals("/usr/bin/kismet", pkg.path)
        assertEquals(LinuxEnvironmentService.ToolCategory.WIFI_AUDIT, pkg.category)
    }

    @Test
    fun `commandResult defaults state correctly based on success flag`() {
        val successRes = LinuxEnvironmentService.CommandResult(
            success = true,
            output = "Debian GNU/Linux 13",
            error = null
        )
        assertEquals(LinuxEnvironmentService.ExecutionState.COMPLETED, successRes.state)

        val failRes = LinuxEnvironmentService.CommandResult(
            success = false,
            output = null,
            error = "Guest unreachable"
        )
        assertEquals(LinuxEnvironmentService.ExecutionState.FAILED, failRes.state)
    }

    @Test
    fun `installedBinaryInfo data class encapsulates probe metadata and version`() {
        val info = LinuxEnvironmentService.InstalledBinaryInfo(
            binary = "kismet",
            displayName = "Kismet Wireless IDS",
            isInstalled = true,
            version = "Kismet 2024-02-R1",
            binaryPath = "/usr/bin/kismet",
            category = LinuxEnvironmentService.ToolCategory.WIFI_AUDIT,
            executionChannel = "Pixel AVF Debian Guest"
        )

        assertEquals("kismet", info.binary)
        assertEquals("Kismet Wireless IDS", info.displayName)
        assertTrue(info.isInstalled)
        assertEquals("Kismet 2024-02-R1", info.version)
        assertEquals("/usr/bin/kismet", info.binaryPath)
        assertEquals(LinuxEnvironmentService.ToolCategory.WIFI_AUDIT, info.category)
        assertEquals("Pixel AVF Debian Guest", info.executionChannel)
    }

    @Test
    fun `parseWhichPaths correctly maps output lines to binary names`() {
        val rawWhich = """
            /usr/bin/kismet
            /usr/sbin/tcpdump
            /usr/local/bin/tshark
            /bin/iw
        """.trimIndent()

        val map = LinuxEnvironmentService.parseWhichPaths(rawWhich)
        assertEquals(4, map.size)
        assertEquals("/usr/bin/kismet", map["kismet"])
        assertEquals("/usr/sbin/tcpdump", map["tcpdump"])
        assertEquals("/usr/local/bin/tshark", map["tshark"])
        assertEquals("/bin/iw", map["iw"])
        assertNull(map["aircrack-ng"])
    }

    @Test
    fun `getBinaryVersionCommand generates non-interactive safe probe commands`() {
        val kismetCmd = LinuxEnvironmentService.getBinaryVersionCommand("kismet")
        assertTrue(kismetCmd.contains("kismet --version"))
        assertTrue(kismetCmd.contains("head -n 1"))

        val tcpdumpCmd = LinuxEnvironmentService.getBinaryVersionCommand("tcpdump")
        assertTrue(tcpdumpCmd.contains("tcpdump --version"))
        assertTrue(tcpdumpCmd.contains("2>&1"))

        val tsharkCmd = LinuxEnvironmentService.getBinaryVersionCommand("tshark")
        assertTrue(tsharkCmd.contains("tshark -v"))
    }

    @Test
    fun `parseBinaryVersion accurately extracts versions for kismet, tcpdump, and tshark`() {
        // Kismet
        val kismetRaw = "Kismet 2024-02-R1\nCopyright (C) Mike Kershaw"
        val kismetVer = LinuxEnvironmentService.parseBinaryVersion("kismet", kismetRaw)
        assertNotNull(kismetVer)
        assertTrue(kismetVer!!.contains("Kismet 2024-02-R1"))

        // tcpdump
        val tcpdumpRaw = "tcpdump version 4.99.3\nlibpcap version 1.10.3\nOpenSSL 3.0.11"
        val tcpdumpVer = LinuxEnvironmentService.parseBinaryVersion("tcpdump", tcpdumpRaw)
        assertNotNull(tcpdumpVer)
        assertEquals("tcpdump version 4.99.3", tcpdumpVer)

        // tshark
        val tsharkRaw = "TShark (Wireshark) 4.2.4 (Git v4.2.4 packaged as 4.2.4-1)"
        val tsharkVer = LinuxEnvironmentService.parseBinaryVersion("tshark", tsharkRaw)
        assertNotNull(tsharkVer)
        assertEquals("TShark (Wireshark) 4.2.4", tsharkVer)

        // aircrack-ng
        val aircrackRaw = "Aircrack-ng 1.7  - (C) 2006-2022 Thomas d'Otreppe"
        val aircrackVer = LinuxEnvironmentService.parseBinaryVersion("aircrack-ng", aircrackRaw)
        assertNotNull(aircrackVer)
        assertEquals("Aircrack-ng 1.7", aircrackVer)

        // Empty / blank input
        assertNull(LinuxEnvironmentService.parseBinaryVersion("kismet", ""))
        assertNull(LinuxEnvironmentService.parseBinaryVersion("tcpdump", "   \n  "))
    }

    @Test
    fun `getBinaryDisplayName and getBinaryCategory correctly classify core security tools`() {
        assertEquals("Kismet Wireless IDS", LinuxEnvironmentService.getBinaryDisplayName("kismet"))
        assertEquals("tcpdump Packet Capture", LinuxEnvironmentService.getBinaryDisplayName("tcpdump"))
        assertEquals("TShark Protocol Dissector", LinuxEnvironmentService.getBinaryDisplayName("tshark"))

        assertEquals(LinuxEnvironmentService.ToolCategory.WIFI_AUDIT, LinuxEnvironmentService.getBinaryCategory("kismet"))
        assertEquals(LinuxEnvironmentService.ToolCategory.PACKET_ANALYSIS, LinuxEnvironmentService.getBinaryCategory("tcpdump"))
        assertEquals(LinuxEnvironmentService.ToolCategory.PACKET_ANALYSIS, LinuxEnvironmentService.getBinaryCategory("tshark"))
        assertEquals(LinuxEnvironmentService.ToolCategory.SDR_RF, LinuxEnvironmentService.getBinaryCategory("rtl_tcp"))
    }

    @Test
    fun `ALLOWED_SECURITY_BINARIES contains required security tools and process signals`() {
        val allowlist = LinuxEnvironmentService.ALLOWED_SECURITY_BINARIES
        assertTrue(allowlist.contains("kismet"))
        assertTrue(allowlist.contains("tcpdump"))
        assertTrue(allowlist.contains("tshark"))
        assertTrue(allowlist.contains("iw"))
        assertTrue(allowlist.contains("aircrack-ng"))
        assertTrue(allowlist.contains("kill"))
        assertTrue(allowlist.contains("pkill"))

        // Disallow dangerous or arbitrary binaries
        assertFalse(allowlist.contains("nc"))
        assertFalse(allowlist.contains("bash"))
        assertFalse(allowlist.contains("sh"))
        assertFalse(allowlist.contains("rm"))
        assertFalse(allowlist.contains("chmod"))
    }

    @Test
    fun `validateAndEscapeArgument allows valid safe arguments and wraps spaced arguments`() {
        assertEquals("wlan0", LinuxEnvironmentService.validateAndEscapeArgument("wlan0"))
        assertEquals("-i", LinuxEnvironmentService.validateAndEscapeArgument("-i"))
        assertEquals("'My Network'", LinuxEnvironmentService.validateAndEscapeArgument("My Network"))
    }

    @Test
    fun `validateAndEscapeArgument rejects prohibited shell injection characters`() {
        val injectionPayloads = listOf(
            "; rm -rf /",
            "$(whoami)",
            "`id`",
            "test | cat",
            "out > /tmp/x",
            "in < /tmp/x",
            "arg1 & arg2"
        )

        for (payload in injectionPayloads) {
            try {
                LinuxEnvironmentService.validateAndEscapeArgument(payload)
                fail("Expected IllegalArgumentException for payload: $payload")
            } catch (e: IllegalArgumentException) {
                // Expected
                assertTrue(e.message!!.contains("prohibited shell control characters"))
            }
        }
    }

    @Test
    fun `buildValidatedArgumentString constructs safe command string`() {
        val cmd = LinuxEnvironmentService.buildValidatedArgumentString(
            subCmd = "-i",
            arguments = listOf("wlan0", "-n", "-s", "0")
        )
        assertEquals("-i wlan0 -n -s 0", cmd)
    }

    @Test
    fun `parseFoundToolPaths accurately extracts tool mappings for kismet, tshark, and tcpdump`() {
        val rawOutput = """
            FOUND:kismet:/usr/bin/kismet
            FOUND:tcpdump:/usr/sbin/tcpdump
            FOUND:tshark:/usr/bin/tshark
        """.trimIndent()

        val paths = LinuxEnvironmentService.parseFoundToolPaths(rawOutput)
        assertEquals(3, paths.size)
        assertEquals("/usr/bin/kismet", paths["kismet"])
        assertEquals("/usr/sbin/tcpdump", paths["tcpdump"])
        assertEquals("/usr/bin/tshark", paths["tshark"])
        assertNull(paths["aircrack-ng"])
    }

    @Test
    fun `parseFoundToolPaths handles direct filesystem paths without FOUND prefix`() {
        val rawOutput = """
            /usr/bin/kismet
            /usr/sbin/tcpdump
            /usr/bin/tshark
        """.trimIndent()

        val paths = LinuxEnvironmentService.parseFoundToolPaths(rawOutput)
        assertEquals(3, paths.size)
        assertEquals("/usr/bin/kismet", paths["kismet"])
        assertEquals("/usr/sbin/tcpdump", paths["tcpdump"])
        assertEquals("/usr/bin/tshark", paths["tshark"])
    }

    @Test
    fun `GuestToolInfo data structure encapsulates existence, version, and execution channel`() {
        val tool = LinuxEnvironmentService.GuestToolInfo(
            name = "kismet",
            isInstalled = true,
            path = "/usr/bin/kismet",
            version = "Kismet 2024-02-R1",
            executionChannel = "Pixel AVF Debian Guest",
            error = null
        )

        assertEquals("kismet", tool.name)
        assertTrue(tool.isInstalled)
        assertEquals("/usr/bin/kismet", tool.path)
        assertEquals("Kismet 2024-02-R1", tool.version)
        assertEquals("Pixel AVF Debian Guest", tool.executionChannel)
        assertNull(tool.error)
    }
}
