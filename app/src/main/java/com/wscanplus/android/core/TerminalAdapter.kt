package com.wscanplus.android.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * TerminalAdapter – hexagonal architectural abstraction isolating physical transport,
 * discovery, and connection lifecycle from the high-level business workflow.
 *
 * ## State Machine Lifecycle:
 *  IDLE ──► DISCOVERING ──► CONNECTING ──► AUTHENTICATING ──► READY ──► BUSY ──► READY
 *    ▲            │               │                │             │       │
 *    │            ▼               ▼                ▼             ▼       ▼
 *    └────────── FAILED ◄────────────────────── RECOVERING ◄─────┴───────┘
 */
enum class TerminalConnectionState {
    IDLE,
    DISCOVERING,
    CONNECTING,
    AUTHENTICATING,
    READY,
    BUSY,
    RECOVERING,
    FAILED
}

sealed class TerminalEvent {
    data class StateChanged(val oldState: TerminalConnectionState, val newState: TerminalConnectionState) : TerminalEvent()
    data class OutputReceived(val text: String, val isStderr: Boolean = false) : TerminalEvent()
    data class EndpointDiscovered(val endpointUrl: String, val serviceName: String) : TerminalEvent()
    data class ConnectionError(val message: String, val fatal: Boolean = false) : TerminalEvent()
}

data class TerminalExecutionResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String?,
    val executionChannel: String,
    val idempotencyKey: String,
    val durationMs: Long
)

/**
 * Generic terminal adapter interface.
 */
interface TerminalAdapter {
    val id: String
    val displayName: String
    val state: StateFlow<TerminalConnectionState>

    suspend fun connect(context: Context): Boolean
    suspend fun disconnect()
    suspend fun execute(
        context: Context,
        command: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
        timeoutSeconds: Long = 30
    ): TerminalExecutionResult
    fun cancel()
    fun observeEvents(): SharedFlow<TerminalEvent>
}

// ── 1. AVF Linux VM Terminal Adapter ─────────────────────────────────────────

/**
 * AvfVmTerminalAdapter – manages connection and execution against the Pixel AVF
 * Linux VM.
 *
 * Implements:
 * 1. NsdManager mDNS discovery for the stock AOSP `_http._tcp` service named `ttyd`.
 * 2. Authenticated socket/vsock execution channel (`PixelAvfDebianGuest`).
 * 3. Strict host/guest isolation: never executes or substitutes the Android host shell.
 */
class AvfVmTerminalAdapter : TerminalAdapter {

    override val id: String = "pixel_avf"
    override val displayName: String = "Pixel AVF Linux VM"

    private val _state = MutableStateFlow(TerminalConnectionState.IDLE)
    override val state: StateFlow<TerminalConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = 64)
    override fun observeEvents(): SharedFlow<TerminalEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var discoveredTtydEndpoint: String? = null
    private var inFlightJob: Job? = null

    companion object {
        private const val TAG = "AvfVmTerminalAdapter"
        const val SERVICE_TYPE_TTYD = "_http._tcp"
        const val SERVICE_NAME_TTYD = "ttyd"
    }

    override suspend fun connect(context: Context): Boolean = withContext(Dispatchers.IO) {
        transitionTo(TerminalConnectionState.DISCOVERING)

        // 1. Check AVF support on device
        val avfInfo = IntegrationsManager.getPixelAvfInfo(context)
        if (!avfInfo.isAvfSupported && !avfInfo.isTerminalAppInstalled) {
            transitionTo(TerminalConnectionState.FAILED)
            _events.tryEmit(TerminalEvent.ConnectionError("AVF not supported or Terminal app not installed."))
            return@withContext false
        }

        // 2. Discover ttyd mDNS service if available
        discoverTtydService(context)

        transitionTo(TerminalConnectionState.CONNECTING)

        // 3. Test guest VM reachability
        val guestState = PixelAvfDebianGuest.probeGuestState(context)
        if (!guestState.isReachable) {
            transitionTo(TerminalConnectionState.RECOVERING)
            Log.w(TAG, "Debian guest not reachable at default port. Status: ${guestState.statusMessage}")
            transitionTo(TerminalConnectionState.FAILED)
            _events.tryEmit(TerminalEvent.ConnectionError("Debian guest unreachable: ${guestState.statusMessage}"))
            return@withContext false
        }

        transitionTo(TerminalConnectionState.AUTHENTICATING)

        // 4. Authenticate & verify Debian environment
        if (guestState.isExecutionVerified) {
            transitionTo(TerminalConnectionState.READY)
            return@withContext true
        } else {
            transitionTo(TerminalConnectionState.FAILED)
            _events.tryEmit(TerminalEvent.ConnectionError("Guest failed Debian OS identity verification."))
            return@withContext false
        }
    }

    override suspend fun execute(
        context: Context,
        command: String,
        idempotencyKey: String,
        timeoutSeconds: Long
    ): TerminalExecutionResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val previousState = _state.value
        transitionTo(TerminalConnectionState.BUSY)

        val resultDeferred = CompletableDeferred<TerminalExecutionResult>()
        inFlightJob = scope.launch {
            try {
                // Strictly execute in Debian guest environment; NEVER fallback to host shell
                val guestRes = PixelAvfDebianGuest.executeGuestCommand(
                    context = context,
                    command = command,
                    timeoutSeconds = timeoutSeconds
                )

                if (guestRes.stdout.isNotBlank()) {
                    _events.tryEmit(TerminalEvent.OutputReceived(guestRes.stdout))
                }
                if (!guestRes.stderr.isNullOrBlank()) {
                    _events.tryEmit(TerminalEvent.OutputReceived(guestRes.stderr, isStderr = true))
                }

                val result = TerminalExecutionResult(
                    success = guestRes.success,
                    exitCode = guestRes.exitCode,
                    stdout = guestRes.stdout,
                    stderr = guestRes.stderr,
                    executionChannel = guestRes.executionChannel,
                    idempotencyKey = idempotencyKey,
                    durationMs = System.currentTimeMillis() - startMs
                )
                resultDeferred.complete(result)
            } catch (e: CancellationException) {
                resultDeferred.complete(
                    TerminalExecutionResult(
                        success = false,
                        exitCode = -1,
                        stdout = "",
                        stderr = "Execution cancelled",
                        executionChannel = "CANCELLED",
                        idempotencyKey = idempotencyKey,
                        durationMs = System.currentTimeMillis() - startMs
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "AVF execution failed", e)
                resultDeferred.complete(
                    TerminalExecutionResult(
                        success = false,
                        exitCode = -1,
                        stdout = "",
                        stderr = e.message ?: "Execution error",
                        executionChannel = "ERROR",
                        idempotencyKey = idempotencyKey,
                        durationMs = System.currentTimeMillis() - startMs
                    )
                )
            } finally {
                transitionTo(previousState.takeIf { it != TerminalConnectionState.BUSY } ?: TerminalConnectionState.READY)
            }
        }

        resultDeferred.await()
    }

    override fun cancel() {
        inFlightJob?.cancel()
        inFlightJob = null
        transitionTo(TerminalConnectionState.READY)
    }

    override suspend fun disconnect() {
        cancel()
        transitionTo(TerminalConnectionState.IDLE)
    }

    private fun transitionTo(newState: TerminalConnectionState) {
        val oldState = _state.value
        if (oldState != newState) {
            _state.value = newState
            _events.tryEmit(TerminalEvent.StateChanged(oldState, newState))
        }
    }

    private fun discoverTtydService(context: Context) {
        try {
            val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.w(TAG, "NSD discovery start failed: $errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {
                    Log.d(TAG, "NSD discovery started for $serviceType")
                }
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo?.serviceName?.contains(SERVICE_NAME_TTYD, ignoreCase = true) == true) {
                        try {
                            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                                override fun onServiceResolved(resolved: NsdServiceInfo?) {
                                    val host = resolved?.host?.hostAddress
                                    val port = resolved?.port
                                    if (host != null && port != null) {
                                        val endpoint = "http://$host:$port"
                                        discoveredTtydEndpoint = endpoint
                                        _events.tryEmit(TerminalEvent.EndpointDiscovered(endpoint, resolved.serviceName ?: "ttyd"))
                                    }
                                }
                            })
                        } catch (_: Exception) {}
                    }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            }
            nsd.discoverServices(SERVICE_TYPE_TTYD, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD discovery unavailable: ${e.message}")
        }
    }
}

// ── 2. Termux Userland Terminal Adapter ───────────────────────────────────────

/**
 * TermuxTerminalAdapter – routes execution through the official Termux RUN_COMMAND
 * contract with asynchronous PendingIntent callbacks.
 */
class TermuxTerminalAdapter : TerminalAdapter {

    override val id: String = "termux"
    override val displayName: String = "Termux Userland"

    private val _state = MutableStateFlow(TerminalConnectionState.IDLE)
    override val state: StateFlow<TerminalConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = 64)
    override fun observeEvents(): SharedFlow<TerminalEvent> = _events.asSharedFlow()

    override suspend fun connect(context: Context): Boolean = withContext(Dispatchers.IO) {
        _state.value = TerminalConnectionState.DISCOVERING
        val status = TermuxBridge.getTermuxStatus(context)
        if (!status.isInstalled) {
            _state.value = TerminalConnectionState.FAILED
            _events.tryEmit(TerminalEvent.ConnectionError("Termux package is not installed."))
            return@withContext false
        }

        if (!status.isPermissionGranted) {
            _state.value = TerminalConnectionState.FAILED
            _events.tryEmit(TerminalEvent.ConnectionError("com.termux.permission.RUN_COMMAND not granted."))
            return@withContext false
        }

        _state.value = TerminalConnectionState.READY
        true
    }

    override suspend fun disconnect() {
        _state.value = TerminalConnectionState.IDLE
    }

    override suspend fun execute(
        context: Context,
        command: String,
        idempotencyKey: String,
        timeoutSeconds: Long
    ): TerminalExecutionResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        _state.value = TerminalConnectionState.BUSY
        val res = TermuxBridge.executeCommand(context, command, timeoutSeconds)
        _state.value = TerminalConnectionState.READY

        TerminalExecutionResult(
            success = res.success,
            exitCode = res.exitCode,
            stdout = res.stdout,
            stderr = res.stderr,
            executionChannel = res.executionChannel,
            idempotencyKey = idempotencyKey,
            durationMs = System.currentTimeMillis() - startMs
        )
    }

    override fun cancel() {
        _state.value = TerminalConnectionState.READY
    }
}

// ── 3. Shizuku Privileged ADB Terminal Adapter ────────────────────────────────

/**
 * ShizukuTerminalAdapter – user-space privileged ADB daemon interface.
 */
class ShizukuTerminalAdapter : TerminalAdapter {

    override val id: String = "shizuku"
    override val displayName: String = "Shizuku Privileged Shell"

    private val _state = MutableStateFlow(TerminalConnectionState.IDLE)
    override val state: StateFlow<TerminalConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = 64)
    override fun observeEvents(): SharedFlow<TerminalEvent> = _events.asSharedFlow()

    override suspend fun connect(context: Context): Boolean = withContext(Dispatchers.IO) {
        _state.value = TerminalConnectionState.CONNECTING
        val status = IntegrationsManager.getShizukuStatus(context)
        if (status.isBinderAlive && status.isPermissionGranted) {
            _state.value = TerminalConnectionState.READY
            true
        } else {
            _state.value = TerminalConnectionState.FAILED
            _events.tryEmit(TerminalEvent.ConnectionError(status.statusMessage))
            false
        }
    }

    override suspend fun disconnect() {
        _state.value = TerminalConnectionState.IDLE
    }

    override suspend fun execute(
        context: Context,
        command: String,
        idempotencyKey: String,
        timeoutSeconds: Long
    ): TerminalExecutionResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        _state.value = TerminalConnectionState.BUSY
        val res = IntegrationsManager.executeShizukuCommand(command, timeoutSeconds)
        _state.value = TerminalConnectionState.READY

        TerminalExecutionResult(
            success = res.success,
            exitCode = res.exitCode,
            stdout = res.output,
            stderr = res.error,
            executionChannel = "SHIZUKU_ADB_SHELL",
            idempotencyKey = idempotencyKey,
            durationMs = System.currentTimeMillis() - startMs
        )
    }

    override fun cancel() {
        _state.value = TerminalConnectionState.READY
    }
}

// ── 4. Unified Terminal Adapter Coordinator ───────────────────────────────────

/**
 * TerminalAdapterRegistry – manages active adapter resolution and command routing.
 */
object TerminalAdapterRegistry {

    val avfAdapter = AvfVmTerminalAdapter()
    val termuxAdapter = TermuxTerminalAdapter()
    val shizukuAdapter = ShizukuTerminalAdapter()

    fun getAdapter(backend: IntegrationsManager.ExecutionBackend): TerminalAdapter {
        return when (backend) {
            IntegrationsManager.ExecutionBackend.PIXEL_AVF -> avfAdapter
            IntegrationsManager.ExecutionBackend.TERMUX -> termuxAdapter
            IntegrationsManager.ExecutionBackend.SHIZUKU -> shizukuAdapter
            else -> avfAdapter
        }
    }
}
