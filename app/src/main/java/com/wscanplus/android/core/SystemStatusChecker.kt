package com.wscanplus.android.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * SystemStatusChecker – queries every Android system setting and permission
 * that affects wscan+ functionality, and returns a prioritised list of
 * [StatusItem]s describing what is missing and how to fix it.
 *
 * This object is intentionally pure (no side effects) so that it can be
 * called repeatedly from Composables on lifecycle resume without risk.
 */
object SystemStatusChecker {

    // ── Public data types ─────────────────────────────────────────────────────

    enum class Severity { ERROR, WARNING, INFO }

    /**
     * Identifies which system action will fix this status item.
     * Consumed by [HomeScreen] to dispatch the correct intent or launcher.
     */
    enum class ActionTarget {
        REQUEST_FINE_LOCATION,
        REQUEST_BACKGROUND_LOCATION,
        OPEN_NOTIFICATION_SETTINGS,
        OPEN_DEVELOPER_OPTIONS,
        OPEN_DEVICE_INFO,
        REQUEST_BATTERY_EXEMPTION,
        OPEN_WIFI_SETTINGS,
    }

    data class StatusItem(
        val id: String,
        val title: String,
        val detail: String,
        val actionLabel: String,
        val actionTarget: ActionTarget,
        val severity: Severity,
    )

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Run all checks and return issues in priority order (errors first).
     * Safe to call on the main thread; all checks are synchronous reads.
     */
    fun checkAll(context: Context): List<StatusItem> = buildList {
        if (!hasLocationPermission(context))        add(locationPermissionItem())
        if (!areNotificationsEnabled(context))      add(notificationsDisabledItem())
        if (!isBatteryOptimizationExempt(context))  add(batteryOptimizationItem())
        if (!hasBackgroundLocationPermission(context)) add(backgroundLocationItem())
        if (!isScanThrottleDisabled(context))       add(scanThrottleItem(isDeveloperModeEnabled(context)))
        if (!hasInternetConnectivity(context))      add(noInternetItem())
    }.sortedBy { it.severity.ordinal.inv() } // ERROR first

    // ── Individual checks ─────────────────────────────────────────────────────

    /** ACCESS_FINE_LOCATION – required to see SSID/BSSID in scan results. */
    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * ACCESS_BACKGROUND_LOCATION – required on Android 10+ so the foreground
     * IDS service can continue scanning when the screen is off.
     * Returns true on API < 29 (permission didn't exist).
     */
    fun hasBackgroundLocationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** POST_NOTIFICATIONS – needed on Android 13+ for threat alert banners. */
    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * wifi_scan_throttle_enabled == 0 means throttling is OFF (desired).
     * Changing this requires Developer Options to be enabled first.
     */
    fun isScanThrottleDisabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, "wifi_scan_throttle_enabled", 1) == 0

    /**
     * DEVELOPMENT_SETTINGS_ENABLED != 0 means Developer Mode is active.
     * Users must tap "Build Number" 7× on the About Phone screen to enable it.
     */
    fun isDeveloperModeEnabled(context: Context): Boolean =
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) != 0

    /**
     * When Android battery optimisation is active, the OS may kill or suspend
     * the background IDS service during Doze mode.
     */
    fun isBatteryOptimizationExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    /** Returns false when the device has no active internet-capable network. */
    fun hasInternetConnectivity(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── Status item builders ──────────────────────────────────────────────────

    private fun locationPermissionItem() = StatusItem(
        id = "location",
        title = "Location permission required",
        detail = "ACCESS_FINE_LOCATION is needed to read SSID/BSSID from Wi-Fi scans.",
        actionLabel = "Grant Location",
        actionTarget = ActionTarget.REQUEST_FINE_LOCATION,
        severity = Severity.ERROR,
    )

    private fun notificationsDisabledItem() = StatusItem(
        id = "notifications",
        title = "Notifications are disabled",
        detail = "Threat alerts won't appear outside the app. Tap to enable for this app.",
        actionLabel = "Enable Notifications",
        actionTarget = ActionTarget.OPEN_NOTIFICATION_SETTINGS,
        severity = Severity.ERROR,
    )

    private fun backgroundLocationItem() = StatusItem(
        id = "background_location",
        title = "Background location not set to \"Always\"",
        detail = "The IDS scanner runs while the screen is off and needs continuous " +
            "location access. Open App Permissions → Location → Allow all the time.",
        actionLabel = "Set to Always",
        actionTarget = ActionTarget.REQUEST_BACKGROUND_LOCATION,
        severity = Severity.WARNING,
    )

    private fun scanThrottleItem(devModeEnabled: Boolean) = StatusItem(
        id = "scan_throttle",
        title = "Wi-Fi scan throttling is ON",
        detail = if (devModeEnabled)
            "Android limits scans to ~4 per 2 minutes. Disable via " +
                "Developer Options → Wi-Fi scan throttling."
        else
            "Enable Developer Mode first: go to About Phone and tap " +
                "\"Build Number\" 7 times, then disable Wi-Fi scan throttling.",
        actionLabel = if (devModeEnabled) "Open Developer Options" else "Open About Phone",
        actionTarget = if (devModeEnabled) ActionTarget.OPEN_DEVELOPER_OPTIONS
                       else ActionTarget.OPEN_DEVICE_INFO,
        severity = Severity.WARNING,
    )

    private fun batteryOptimizationItem() = StatusItem(
        id = "battery",
        title = "Battery optimisation is active",
        detail = "Android may pause the background IDS service during Doze mode. " +
            "Tap to request an exemption.",
        actionLabel = "Disable for wscan+",
        actionTarget = ActionTarget.REQUEST_BATTERY_EXEMPTION,
        severity = Severity.WARNING,
    )

    private fun noInternetItem() = StatusItem(
        id = "internet",
        title = "No internet connection",
        detail = "Firebase cloud sync and Gemini AI analysis are unavailable offline.",
        actionLabel = "Wi-Fi Settings",
        actionTarget = ActionTarget.OPEN_WIFI_SETTINGS,
        severity = Severity.INFO,
    )
}
