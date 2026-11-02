package com.wscanplus.android.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * IntentHelper – factory for Android deep-link [Intent]s that navigate the
 * user directly to the exact settings screen required to fix a missing
 * permission or system setting.
 *
 * Every method returns a ready-to-use [Intent]; the caller is responsible for
 * starting it via `Context.startActivity(intent)` or a result launcher.
 *
 * Design goal: **zero settings navigation** for the user – each tap should
 * land them on exactly the toggle they need to change.
 */
object IntentHelper {

    /**
     * This app's notification settings page.
     * Lands directly on the notification channel list for wscan+.
     * Requires no special permissions.
     */
    fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

    /**
     * This app's system permission list.
     * The user can tap "Location" here to set it to "Allow all the time".
     */
    fun appPermissions(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    /**
     * Developer Options screen.
     * Only available once Developer Mode is enabled (see [deviceInfo]).
     * Contains the "Wi-Fi scan throttling" toggle.
     */
    fun developerOptions(): Intent =
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

    /**
     * About Phone / Device Info screen.
     * The user must tap "Build Number" 7× here to enable Developer Mode,
     * after which [developerOptions] becomes navigable.
     */
    fun deviceInfo(): Intent =
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)

    /**
     * System dialog requesting battery optimisation exemption for this app.
     * Requires `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the manifest.
     * Shows a one-tap "Allow" dialog rather than navigating to a settings page.
     */
    fun batteryOptimizationExemption(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    /**
     * Wi-Fi network picker.
     * Useful when there is no internet connectivity.
     */
    fun wifiSettings(): Intent =
        Intent(Settings.ACTION_WIFI_SETTINGS)

    /**
     * Accessibility services list.
     * Navigate here if the user wants to enable the wscan+ Accessibility Service.
     */
    fun accessibilitySettings(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
