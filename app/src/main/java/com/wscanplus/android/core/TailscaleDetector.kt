package com.wscanplus.android.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * TailscaleDetector – discovers Tailscale VPN network state, active tailnet addresses
 * (CGNAT 100.64.0.0/10 and IPv6 fd7a:115c:a1e0::/48), interface naming, and app status.
 */
object TailscaleDetector {

    private const val TAG = "TailscaleDetector"
    const val PKG_TAILSCALE = "com.tailscale.ipn"

    data class TailscaleStatus(
        val isInstalled: Boolean = false,
        val isVpnActive: Boolean = false,
        val tailscaleIpv4: String? = null,
        val tailscaleIpv6: String? = null,
        val interfaceName: String? = null,
        val isConnected: Boolean = false,
        val statusMessage: String = "Not detected"
    )

    /**
     * Inspects active network interfaces and Android ConnectivityManager to determine Tailscale status.
     */
    fun detect(context: Context): TailscaleStatus {
        val isInstalled = isTailscalePackageInstalled(context)
        val isVpnActive = isVpnTransportActive(context)

        var tailscaleIpv4: String? = null
        var tailscaleIpv6: String? = null
        var ifaceName: String? = null

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp) continue

                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr.isLoopbackAddress) continue

                    if (isTailscaleIpv4(addr)) {
                        tailscaleIpv4 = addr.hostAddress
                        ifaceName = iface.name
                    } else if (isTailscaleIpv6(addr)) {
                        tailscaleIpv6 = addr.hostAddress?.substringBefore("%")
                        if (ifaceName == null) ifaceName = iface.name
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enumerating network interfaces for Tailscale: ${e.message}")
        }

        val isConnected = tailscaleIpv4 != null || (isVpnActive && isInstalled)
        val msg = when {
            tailscaleIpv4 != null -> "Connected to Tailnet: $tailscaleIpv4 (${ifaceName ?: "vpn"})"
            isVpnActive && isInstalled -> "Tailscale VPN Active (resolving tailnet address...)"
            isInstalled -> "Tailscale installed, but VPN not connected"
            else -> "Tailscale not detected on device"
        }

        return TailscaleStatus(
            isInstalled = isInstalled,
            isVpnActive = isVpnActive,
            tailscaleIpv4 = tailscaleIpv4,
            tailscaleIpv6 = tailscaleIpv6,
            interfaceName = ifaceName,
            isConnected = isConnected,
            statusMessage = msg
        )
    }

    /**
     * Checks if a network address falls inside the Tailscale CGNAT range 100.64.0.0/10.
     */
    fun isTailscaleIpv4(addr: InetAddress): Boolean {
        if (addr !is Inet4Address) return false
        val bytes = addr.address
        if (bytes.size != 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        // 100.64.0.0 to 100.127.255.255: first octet 100, second octet 64..127 (top 2 bits = 01)
        return b0 == 100 && (b1 in 64..127)
    }

    /**
     * Checks if a network address falls inside Tailscale's ULA IPv6 range fd7a:115c:a1e0::/48.
     */
    fun isTailscaleIpv6(addr: InetAddress): Boolean {
        if (addr !is Inet6Address) return false
        val bytes = addr.address
        if (bytes.size != 16) return false
        return (bytes[0].toInt() and 0xFF) == 0xfd &&
                (bytes[1].toInt() and 0xFF) == 0x7a &&
                (bytes[2].toInt() and 0xFF) == 0x11 &&
                (bytes[3].toInt() and 0xFF) == 0x5c &&
                (bytes[4].toInt() and 0xFF) == 0xa1 &&
                (bytes[5].toInt() and 0xFF) == 0xe0
    }

    /**
     * Validates whether an incoming peer address is authorized under Tailscale network exposure policy.
     * Loopback addresses are always allowed. If [allowRemote] is true, addresses inside Tailscale ranges are allowed.
     */
    fun isAuthorizedClientAddress(addr: InetAddress, allowRemote: Boolean): Boolean {
        if (addr.isLoopbackAddress || addr.hostAddress == "127.0.0.1" || addr.hostAddress == "::1") {
            return true
        }
        if (!allowRemote) return false
        return isTailscaleIpv4(addr) || isTailscaleIpv6(addr)
    }

    private fun isTailscalePackageInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(PKG_TAILSCALE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun isVpnTransportActive(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) {
            false
        }
    }
}
