package com.wscanplus.android.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * TermuxBridge – coordinates command execution, status probing, and environment verification
 * for the Termux userland environment via the documented com.termux.RUN_COMMAND external API.
 *
 * ## Termux RUN_COMMAND Contract:
 * - Permission: com.termux.permission.RUN_COMMAND
 * - Service: com.termux.app.RunCommandService
 * - Extras:
 *   - com.termux.RUN_COMMAND_PATH: Full path to binary (e.g. /data/data/com.termux/files/usr/bin/bash)
 *   - com.termux.RUN_COMMAND_ARGUMENTS: Array of arguments
 *   - com.termux.RUN_COMMAND_WORKDIR: Working directory (/data/data/com.termux/files/home)
 *   - com.termux.RUN_COMMAND_BACKGROUND: true for headless execution
 *   - com.termux.RUN_COMMAND_PENDING_INTENT: PendingIntent to receive results
 *
 * ## Independent 5-State Connection Reporting:
 * - [TermuxStatus.isInstalled]: com.termux package present
 * - [TermuxStatus.isPermissionGranted]: RUN_COMMAND permission granted
 * - [TermuxStatus.isRunning]: Background daemon / process detected
 * - [TermuxStatus.isReachable]: Intent service can be dispatched
 * - [TermuxStatus.isExecutionVerified]: Confirmed execution with exit code and output
 */
object TermuxBridge {

    private const val TAG = "TermuxBridge"

    const val PKG_TERMUX = "com.termux"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

    // Action and Extras defined by Termux
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_RUN_COMMAND_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    // Result Extras returned by Termux
    private const val EXTRA_RESULT_CODE = "resultCode"
    private const val EXTRA_RESULT_STDOUT = "stdout"
    private const val EXTRA_RESULT_STDERR = "stderr"

    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val TERMUX_BIN_BASH = "/data/data/com.termux/files/usr/bin/bash"
    const val TERMUX_BIN_SH = "/data/data/com.termux/files/usr/bin/sh"

    data class TermuxStatus(
        val isInstalled: Boolean = false,
        val isPermissionGranted: Boolean = false,
        val isRunning: Boolean = false,
        val isReachable: Boolean = false,
        val isExecutionVerified: Boolean = false,
        val termuxVersion: String? = null,
        val statusMessage: String = "Not probed",
        val installedTools: List<String> = emptyList(),
        val lastVerifiedTimestamp: Long = 0L
    )

    data class TermuxResult(
        val success: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String?,
        val executionChannel: String
    )

    /**
     * Probes the 5 independent states of Termux integration.
     */
    suspend fun getTermuxStatus(context: Context): TermuxStatus = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val isInstalled = try {
            packageManager.getPackageInfo(PKG_TERMUX, 0)
            true
        } catch (_: Exception) {
            false
        }

        if (!isInstalled) {
            return@withContext TermuxStatus(
                isInstalled = false,
                isPermissionGranted = false,
                isRunning = false,
                isReachable = false,
                isExecutionVerified = false,
                statusMessage = "Termux is not installed on this device."
            )
        }

        val version = try {
            packageManager.getPackageInfo(PKG_TERMUX, 0).versionName
        } catch (_: Exception) {
            null
        }

        val isPermissionGranted = ContextCompat.checkSelfPermission(
            context,
            PERMISSION_RUN_COMMAND
        ) == PackageManager.PERMISSION_GRANTED

        // Check reachability by checking service existence
        val serviceIntent = Intent().apply {
            setClassName(PKG_TERMUX, RUN_COMMAND_SERVICE)
        }
        val isReachable = packageManager.queryIntentServices(serviceIntent, 0).isNotEmpty()

        // Quick verification check
        var isExecutionVerified = false
        var installedTools: List<String> = emptyList()

        if (isPermissionGranted && isReachable) {
            val verifyRes = executeCommand(context, "echo WSCAN_TERMUX_OK && which tshark kismet tcpdump 2>/dev/null", timeoutSeconds = 6)
            if (verifyRes.success && verifyRes.stdout.contains("WSCAN_TERMUX_OK")) {
                isExecutionVerified = true
                installedTools = verifyRes.stdout.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.startsWith("/") && !it.contains("WSCAN_TERMUX_OK") }
                    .map { it.substringAfterLast("/") }
                    .distinct()
            }
        }

        val isRunning = isExecutionVerified || isProcessRunning("com.termux")

        val msg = when {
            isExecutionVerified -> "Termux verified: v$version (Tools: ${if (installedTools.isEmpty()) "Standard userland" else installedTools.joinToString(", ")})"
            !isPermissionGranted -> "Termux installed, but com.termux.permission.RUN_COMMAND is not granted."
            !isReachable -> "Termux installed, but RunCommandService is not responding."
            else -> "Termux installed & authorized; execution probe pending."
        }

        TermuxStatus(
            isInstalled = true,
            isPermissionGranted = isPermissionGranted,
            isRunning = isRunning,
            isReachable = isReachable,
            isExecutionVerified = isExecutionVerified,
            termuxVersion = version,
            statusMessage = msg,
            installedTools = installedTools,
            lastVerifiedTimestamp = System.currentTimeMillis()
        )
    }

    /**
     * Executes a command through the official Termux RUN_COMMAND contract.
     * Uses a dynamic BroadcastReceiver with unique action to wait for the PendingIntent result.
     */
    suspend fun executeCommand(
        context: Context,
        command: String,
        timeoutSeconds: Long = 10
    ): TermuxResult = withContext(Dispatchers.IO) {
        // First check permission
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            PERMISSION_RUN_COMMAND
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            // Attempt elevation via Shizuku or Root targeting Termux userland if permitted
            val shizuku = IntegrationsManager.getShizukuStatus(context)
            if (shizuku.isPermissionGranted) {
                val elevatedCmd = "export PREFIX=$TERMUX_PREFIX; export PATH=\$PREFIX/bin:\$PATH; $command"
                val res = IntegrationsManager.executeShizukuCommand(elevatedCmd, timeoutSeconds)
                return@withContext TermuxResult(
                    success = res.success,
                    exitCode = res.exitCode,
                    stdout = res.output,
                    stderr = res.error,
                    executionChannel = "SHIZUKU_IN_TERMUX_USERLAND"
                )
            }

            return@withContext TermuxResult(
                success = false,
                exitCode = -1,
                stdout = "",
                stderr = "Permission $PERMISSION_RUN_COMMAND not granted to wscan+.",
                executionChannel = "NONE"
            )
        }

        val resultAction = "com.wscanplus.android.TERMUX_RESULT_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<TermuxResult>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(recvContext: Context?, intent: Intent?) {
                if (intent == null) return
                val exitCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val stdout = intent.getStringExtra(EXTRA_RESULT_STDOUT) ?: ""
                val stderr = intent.getStringExtra(EXTRA_RESULT_STDERR)
                deferred.complete(
                    TermuxResult(
                        success = exitCode == 0,
                        exitCode = exitCode,
                        stdout = stdout.trim(),
                        stderr = stderr?.trim()?.ifBlank { null },
                        executionChannel = "TERMUX_RUN_COMMAND_INTENT"
                    )
                )
            }
        }

        try {
            val filter = IntentFilter(resultAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            val callbackIntent = Intent(resultAction).apply {
                setPackage(context.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_ONE_SHOT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                System.currentTimeMillis().toInt(),
                callbackIntent,
                flags
            )

            // Prefer bash if available, else sh
            val executable = if (File(TERMUX_BIN_BASH).exists()) TERMUX_BIN_BASH else TERMUX_BIN_SH

            val runIntent = Intent().apply {
                setClassName(PKG_TERMUX, RUN_COMMAND_SERVICE)
                action = ACTION_RUN_COMMAND
                putExtra(EXTRA_RUN_COMMAND_PATH, executable)
                putExtra(EXTRA_RUN_COMMAND_ARGUMENTS, arrayOf("-c", command))
                putExtra(EXTRA_RUN_COMMAND_WORKDIR, TERMUX_HOME)
                putExtra(EXTRA_RUN_COMMAND_BACKGROUND, true)
                putExtra(EXTRA_RUN_COMMAND_PENDING_INTENT, pendingIntent)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(runIntent)
                } else {
                    context.startService(runIntent)
                }
            } catch (e: Exception) {
                // If service start fails directly, attempt startService
                context.startService(runIntent)
            }

            val result = withTimeoutOrNull(timeoutSeconds * 1000) {
                deferred.await()
            }

            if (result != null) {
                return@withContext result
            } else {
                return@withContext TermuxResult(
                    success = false,
                    exitCode = -1,
                    stdout = "",
                    stderr = "Termux command timed out after ${timeoutSeconds}s without callback response.",
                    executionChannel = "TERMUX_TIMEOUT"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch Termux command", e)
            return@withContext TermuxResult(
                success = false,
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Failed to execute in Termux",
                executionChannel = "ERROR"
            )
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    /**
     * Launches the interactive Termux TUI application.
     */
    fun launchTermux(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(PKG_TERMUX)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Termux", e)
            false
        }
    }

    private fun isProcessRunning(pkg: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -ef | grep $pkg | grep -v grep"))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out.isNotBlank()
        } catch (_: Exception) {
            false
        }
    }
}
