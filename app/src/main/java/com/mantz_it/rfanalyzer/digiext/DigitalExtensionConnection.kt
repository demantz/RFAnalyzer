package com.mantz_it.rfanalyzer.digiext

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcelable
import android.os.RemoteException
import android.util.Log
import com.mantz_it.rfanalyzer.analyzer.OverloadMonitor
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer_ipc.DigitalDemodulationConfiguration
import com.mantz_it.rfanalyzer_ipc.IDigitalExtensionCallback
import com.mantz_it.rfanalyzer_ipc.IDigitalExtensionService
import com.mantz_it.rfanalyzer_ipc.IpcConstants
import com.mantz_it.rfanalyzer_ipc.IqSharedMemoryRingReader
import com.mantz_it.rfanalyzer_ipc.IqSharedMemoryRingWriter
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import com.mantz_it.rfanalyzer_ipc.digiext.DemodStatus
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalPacket
import com.mantz_it.rfanalyzer_ipc.digiext.Event
import com.mantz_it.rfanalyzer_ipc.digiext.PipelineHealth
import com.mantz_it.rfanalyzer_ipc.digiext.StreamElement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * <h1>RF Analyzer - Digital Extension Connection</h1>
 *
 * Module:      DigitalExtensionConnection.kt
 * Description: The Binder connection to the digital extension's service: checks without binding
 *              whether the installed extension can be used (signature, IPC protocol version), binds
 *              and starts it, passes it the demodulation configuration, exchanges IQ samples with
 *              it through the shared-memory rings and hands its packets, stream elements and
 *              demodulator status to the callbacks. [ExtensionConnectionState] is the connection
 *              health it reports.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * Connection health as observed by the client. NOT_INSTALLED/VERSION_MISMATCH_* need a user
 * action (install/update) rather than an automatic retry; DISCONNECTED is expected to be picked
 * up by an auto-reconnect loop; UNAVAILABLE means that loop gave up after its retry budget.
 *
 * A hung (alive but silent) extension or a binding that never completes looks CONNECTED or
 * DISCONNECTED here: AnalyzerService's watchdog catches those through [DigitalExtensionConnection.lastSignOfLifeMs].
 */
enum class ExtensionConnectionState {
    DISCONNECTED,
    CONNECTED,
    RECONNECTING,
    VERSION_MISMATCH_UPDATE_APP,
    VERSION_MISMATCH_UPDATE_EXTENSION,
    NOT_INSTALLED,
    /** Android refused the binding: the extension is signed with another key than this app. */
    NOT_PERMITTED,
    UNAVAILABLE,
}

class DigitalExtensionConnection(
    private val context: Context,
    private val onPacket: (DigitalPacket) -> Unit,
    private val onStreamElement: (StreamElement) -> Unit,
    private val onDemodStatus: (DemodStatus) -> Unit,
) : ServiceConnection {

    companion object {
        private const val TAG = "DigitalExtensionConnection"
        private const val IPC_PERMISSION = "com.mantz_it.rfanalyzer_digital.IPC"
        private const val SERVICE_INTENT_ACTION = "com.mantz_it.rfanalyzer_digital.BIND_EXTENSION"
        private const val SERVICE_CLASS = "com.mantz_it.rfanalyzer_digital.DigitalExtensionService"
        private const val SERVICE_PACKAGE = "com.mantz_it.rfanalyzer_digital"
        /** The protocol the extension speaks, in its service's manifest entry (see [checkPackage]). */
        private const val PROTOCOL_VERSION_META_DATA = "com.mantz_it.rfanalyzer_digital.IPC_PROTOCOL_VERSION"

        fun isDigitalExtensionPackageInstalled(context: Context): Boolean =
            try {
                context.packageManager.getPackageInfo(SERVICE_PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }

        /**
         * What the installed extension allows, read from the package manager without binding (so
         * without starting the extension): installed, signed with this app's key (else Android
         * refuses the binding - its IPC permission is signature-level) and speaking this app's
         * protocol. [DigitalAvailability.READY] says nothing about the license.
         */
        fun checkPackage(context: Context): DigitalAvailability {
            if (!DigitalFeatures.inBuild) return DigitalAvailability.NOT_IN_BUILD
            val pm = context.packageManager
            if (!isDigitalExtensionPackageInstalled(context)) return DigitalAvailability.NOT_INSTALLED
            if (pm.checkSignatures(context.packageName, SERVICE_PACKAGE) != PackageManager.SIGNATURE_MATCH)
                return DigitalAvailability.FOREIGN_SIGNATURE
            val remoteVersion = try {
                pm.getServiceInfo(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS), PackageManager.GET_META_DATA).metaData
                    ?.getInt(PROTOCOL_VERSION_META_DATA, 0) ?: 0
            } catch (_: PackageManager.NameNotFoundException) {
                return DigitalAvailability.UPDATE_EXTENSION // no service of that name: an unrelated, older build
            }
            return when {
                remoteVersion == 0 -> DigitalAvailability.READY // no version in its manifest: checked on connect
                remoteVersion > IpcConstants.IPC_PROTOCOL_VERSION -> DigitalAvailability.UPDATE_APP
                remoteVersion < IpcConstants.IPC_PROTOCOL_VERSION -> DigitalAvailability.UPDATE_EXTENSION
                else -> DigitalAvailability.READY
            }
        }

        /** Installed extension's version name, or null when it isn't installed. Recorded per
         *  capture session so a stored packet can be traced back to the decoder that produced it -
         *  which matters for the modes decoded inside the extension, where the interpretation is
         *  frozen at capture time and cannot be regenerated. */
        fun digitalExtensionVersionName(context: Context): String? =
            try {
                context.packageManager.getPackageInfo(SERVICE_PACKAGE, 0).versionName
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
    }

    var bound = false
        private set
    private val _connectionState = MutableStateFlow(ExtensionConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ExtensionConnectionState> = _connectionState.asStateFlow()
    private var control: IDigitalExtensionService? = null
    private var iqSharedMemoryOutputReader: IqSharedMemoryRingReader? = null
    private var iqSharedMemoryInputWriter: IqSharedMemoryRingWriter? = null
    private var digitalDemodulationConfiguration = DigitalDemodulationConfiguration()

    /** When the extension last sent anything - its PipelineHealth comes once a second while its
     *  thread runs, so silence for longer means it stopped or hangs (see AnalyzerService's watchdog). */
    @Volatile var lastSignOfLifeMs = 0L
        private set
    /** When the last bind was requested - the watchdog's measure for a binding that doesn't complete. */
    @Volatile var lastBindMs = 0L
        private set

    // Completed by the STOPPED event unbindAndAwaitStopped() waits for.
    @Volatile private var stoppedSignal: CompletableDeferred<Unit>? = null

    private val eventCallback = object : IDigitalExtensionCallback.Stub() {
        override fun onEvent(event: Event) {
            lastSignOfLifeMs = System.currentTimeMillis()
            when (event.typeAsEnum()) {
                Event.Type.STARTED -> {}
                // An unrequested STOPPED (the thread ended by itself) isn't acted on here: STOPPED
                // names no session, so a late one from the session before can't be told apart. The
                // silence after a real one is caught by AnalyzerService's watchdog within seconds.
                Event.Type.STOPPED -> { stoppedSignal?.complete(Unit) }
                Event.Type.ERROR -> {
                    Log.d(TAG, "onEvent: ERROR: $event")
                    // TODO: not wired to connectionState. The extension crashes rather than sending
                    // ERROR, and onServiceDisconnected/onBindingDied catch that.
                }
                Event.Type.PACKET -> event.payload<DigitalPacket>()?.let(onPacket)
                Event.Type.STREAM -> event.payload<StreamElement>()?.let(onStreamElement)
                Event.Type.DEMOD_STATUS -> event.payload<DemodStatus>()?.let(onDemodStatus)
                Event.Type.PIPELINE_HEALTH -> event.payload<PipelineHealth>()?.let(OverloadMonitor::reportExtensionHealth)
                null -> {
                    Log.w(TAG, "onEvent: Digital Extension event (version=${event.version}) type is unknown: ${event.type}")
                }
            }
        }
    }

    /** The event's parcelled payload. A Bundle arriving over Binder has no class loader for app
     *  classes, so the payload type's own has to be set before unparcelling. */
    private inline fun <reified T : Parcelable> Event.payload(): T? {
        data?.classLoader = T::class.java.classLoader
        return data?.getParcelable(Event.PAYLOAD_KEY)
    }

    fun canResolveDigitalExtensionService(context: Context): Boolean {
        val intent = Intent(SERVICE_INTENT_ACTION).apply {
            setPackage(SERVICE_PACKAGE)
        }
        val services = context.packageManager.queryIntentServices(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return services.isNotEmpty()
    }

    fun hasIpcPermission(context: Context): Boolean {
        return context.packageManager.checkPermission(IPC_PERMISSION, context.packageName) == PackageManager.PERMISSION_GRANTED
    }

    fun bind(): Boolean {
        if (bound) {
            Log.w(TAG, "bind: Already bound! returning..")
            return false
        }
        if (!isDigitalExtensionPackageInstalled(context)) {
            Log.w(TAG, "bind: Digital Extension package is not installed.")
            _connectionState.value = ExtensionConnectionState.NOT_INSTALLED
            return false
        }
        Log.d(TAG, "bind: Binding to Digital Extension Service...")
        val intent = Intent().apply {
            component = ComponentName(
                SERVICE_PACKAGE,
                SERVICE_CLASS
            )
        }
        val ret = try {
            context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.e(TAG, "bind: not permitted - the extension is signed with another key: ${e.message}")
            _connectionState.value = ExtensionConnectionState.NOT_PERMITTED
            return false
        }
        Log.d(TAG, "bind: bindService() = $ret")
        bound = ret
        lastBindMs = System.currentTimeMillis()
        if (!ret) {
            _connectionState.value = ExtensionConnectionState.DISCONNECTED
        }
        return ret
    }

    fun unbind() {
        if (!bound) {
            Log.w(TAG, "unbind: Not bound. return..")
            return
        }
        Log.d(TAG, "unbind: calling unbindService()...")
        context.unbindService(this)
        bound = false
        control = null
        _connectionState.value = ExtensionConnectionState.DISCONNECTED
    }

    /**
     * Stops the extension and unbinds once it has confirmed its teardown with STOPPED (or after
     * [timeoutMs]), so a following bind never meets a session still shutting down. A plain
     * [unbind] does not wait: the extension tears down asynchronously after it.
     *
     * Call from a coroutine (AnalyzerService's serviceScope). [bound] stays true until the wait
     * ends, so a [bind] meanwhile is refused with a warning instead of reusing a connection that is
     * mid-teardown.
     */
    suspend fun unbindAndAwaitStopped(timeoutMs: Long = 5_000L) {
        if (!bound) return
        val c = control
        if (c != null) {
            val signal = CompletableDeferred<Unit>()
            stoppedSignal = signal
            try {
                c.stop()
                if (withTimeoutOrNull(timeoutMs) { signal.await() } == null) {
                    Log.w(TAG, "unbindAndAwaitStopped: timed out waiting for STOPPED event - unbinding anyway")
                }
            } catch (_: RemoteException) {
                // remote already gone - nothing to wait for
            } finally {
                stoppedSignal = null
            }
        }
        unbind()
    }

    /**
     * Gives up a binding that doesn't deliver: the extension stopped sending, or the binding never
     * completed. Doesn't wait for STOPPED - a hung extension wouldn't send it. The extension's
     * onUnbind ends its session; reconnecting starts a fresh one.
     */
    fun dropUnresponsive() {
        Log.w(TAG, "dropUnresponsive: state=${_connectionState.value}, last sign of life ${if (lastSignOfLifeMs > 0) "${System.currentTimeMillis() - lastSignOfLifeMs} ms ago" else "never"}")
        control = null
        if (bound) safeUnbindService()
        bound = false
        _connectionState.value = ExtensionConnectionState.DISCONNECTED
    }

    /** Called by the reconnect loop right before it starts retrying. */
    fun markReconnecting() {
        _connectionState.value = ExtensionConnectionState.RECONNECTING
    }

    /** Called by the reconnect loop once its retry budget is exhausted. */
    fun markUnavailable() {
        _connectionState.value = ExtensionConnectionState.UNAVAILABLE
    }

    /**
     * Called when a pending reconnect is cancelled without ever having bound (e.g. Stop pressed
     * mid-retry) - clears a stale RECONNECTING/etc state so it doesn't linger in the UI. No-ops if
     * actually bound (unbind() already transitions state correctly in that case).
     */
    fun markIdle() {
        if (!bound) _connectionState.value = ExtensionConnectionState.DISCONNECTED
    }

    // Best-effort: releases the binding on the framework side, so a later bind() with this
    // ServiceConnection isn't rejected as "already bound" after the remote died.
    private fun safeUnbindService() {
        try {
            context.unbindService(this)
        } catch (_: IllegalArgumentException) {
            // ServiceConnection wasn't registered - nothing to clean up
        }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        try {
            control = IDigitalExtensionService.Stub.asInterface(service)
            val remoteVersion = control!!.getProtocolVersion()
            if (remoteVersion != IpcConstants.IPC_PROTOCOL_VERSION) {
                Log.e(TAG, "onServiceConnected: protocol version mismatch (local=${IpcConstants.IPC_PROTOCOL_VERSION}, remote=$remoteVersion)")
                val mismatchState = if (remoteVersion > IpcConstants.IPC_PROTOCOL_VERSION)
                    ExtensionConnectionState.VERSION_MISMATCH_UPDATE_APP
                else
                    ExtensionConnectionState.VERSION_MISMATCH_UPDATE_EXTENSION
                control = null
                unbind() // don't attempt to use an incompatible connection; no auto-retry, this needs a user update
                _connectionState.value = mismatchState // unbind() above resets to DISCONNECTED - override with the more specific state
                return
            }

            Log.d(TAG, "onServiceConnected: version=$remoteVersion")
            control!!.registerEventCallback(eventCallback)

            val shmInput = control!!.initIqSharedMemoryInput()
            val inputBuffer = shmInput.mapReadWrite()
            iqSharedMemoryInputWriter = IqSharedMemoryRingWriter(inputBuffer) // the extension sizes the rings
            lastKnownInputRingOverwriteCount = 0L // a new writer counts from zero

            val shmOutput = control!!.initIqSharedMemoryOutput()
            val outputBuffer = shmOutput.mapReadWrite()
            iqSharedMemoryOutputReader = IqSharedMemoryRingReader(outputBuffer)

            control!!.setConfiguration(digitalDemodulationConfiguration)

            val startSuccess = control!!.start()
            if (startSuccess) {
                lastSignOfLifeMs = System.currentTimeMillis()
                _connectionState.value = ExtensionConnectionState.CONNECTED
                Log.d(TAG, "onServiceConnected: Digital Extension started! (start() == true)")
            } else {
                Log.w(TAG, "onServiceConnected: start() failed!")
                control = null
                unbind() // release the binding - otherwise bind() would keep no-op'ing on every reconnect attempt (bound stays true)
            }
        } catch (e: Exception) {
            // RemoteException, or a runtime exception the extension threw across Binder, or a failed
            // mapping of the shared memory: either way the connection is unusable, but not fatal here.
            Log.e(TAG, "onServiceConnected: ${e.javaClass.simpleName} while setting up connection: ${e.message}")
            control = null
            bound = false
            _connectionState.value = ExtensionConnectionState.DISCONNECTED
            safeUnbindService()
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        Log.d(TAG, "onServiceDisconnected")
        control = null
        bound = false
        _connectionState.value = ExtensionConnectionState.DISCONNECTED
    }

    override fun onBindingDied(name: ComponentName?) {
        Log.w(TAG, "onBindingDied")
        control = null
        bound = false
        _connectionState.value = ExtensionConnectionState.DISCONNECTED
        safeUnbindService()
    }

    private var lastKnownInputRingOverwriteCount = 0L

    fun pushInputSamples(inputSamples: SamplePacket): Boolean {
        if (_connectionState.value != ExtensionConnectionState.CONNECTED) return false
        val writer = iqSharedMemoryInputWriter ?: return false
        writer.write(inputSamples)
        // overwriteCount is the writer's lifetime count; report the growth since the last write.
        if (writer.overwriteCount != lastKnownInputRingOverwriteCount) {
            GlobalPerformanceData.incrementCounter("ring.inputWriterOverwrites", writer.overwriteCount - lastKnownInputRingOverwriteCount)
            // Each overwriting write means the extension fell behind and about one packet of input is lost.
            if (inputSamples.sampleRate > 0)
                OverloadMonitor.reportLoss("Digital extension input", (writer.overwriteCount - lastKnownInputRingOverwriteCount) * inputSamples.size() * 1_000_000_000L / inputSamples.sampleRate)
            lastKnownInputRingOverwriteCount = writer.overwriteCount
        }
        return true
    }

    fun pollOutputSamples(outputSamples: SamplePacket): Boolean {
        if (_connectionState.value != ExtensionConnectionState.CONNECTED) return false
        val reader = iqSharedMemoryOutputReader ?: return false
        return reader.read(outputSamples)
    }

    fun setDigitalDemodulationConfiguration(configuration: DigitalDemodulationConfiguration) {
        digitalDemodulationConfiguration = configuration
        try {
            control?.setConfiguration(configuration)
        } catch (e: Exception) { // RemoteException or a runtime exception from the extension
            Log.w(TAG, "setDigitalDemodulationConfiguration: ${e.javaClass.simpleName}: ${e.message}")
            control = null
            bound = false
            _connectionState.value = ExtensionConnectionState.DISCONNECTED
            safeUnbindService()
        }
    }
}
