package com.mantz_it.rfanalyzer.analyzer

import com.mantz_it.rfanalyzer.source.LocalNetworkAccess
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mantz_it.rfanalyzer.dsp.CwDemodulator
import com.mantz_it.rfanalyzer.source.ClockCorrection
import com.mantz_it.rfanalyzer.source.FileIQSource
import com.mantz_it.rfanalyzer.source.HackrfSource
import com.mantz_it.rfanalyzer.source.IQSourceInterface
import com.mantz_it.rfanalyzer.LogcatLogger
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.source.RtlsdrSource
import com.mantz_it.rfanalyzer.source.RtltcpSource
import com.mantz_it.rfanalyzer.source.UsbSdrSource
import com.mantz_it.rfanalyzer.ui.RECORDINGS_DIRECTORY
import com.mantz_it.rfanalyzer.ui.signalRange
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer.ui.composable.asStringWithUnit
import com.mantz_it.rfanalyzer.ui.UsbAttachActivity
import com.mantz_it.rfanalyzer.ui.tabs.SourceType
import com.mantz_it.rfanalyzer.ui.tabs.StopAfterUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import androidx.core.net.toUri
import com.mantz_it.rfanalyzer.BuildConfig
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.digiext.DigitalExtensionConnection
import com.mantz_it.rfanalyzer.digiext.ExtensionConnectionState
import com.mantz_it.rfanalyzer.digiext.decodes
import com.mantz_it.rfanalyzer.digiext.DigitalExtensionStatus
import com.mantz_it.rfanalyzer.digiext.DigitalFeatures
import com.mantz_it.rfanalyzer.digiext.usesDigitalExtension
import com.mantz_it.rfanalyzer.digiext.notice
import com.mantz_it.rfanalyzer.digiext.db.CaptureSessionRepository
import com.mantz_it.rfanalyzer.digiext.DigitalExtensionRepository
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.MeshCoreChannelRegistry
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.libhydrasdr.HydraSdrGainType
import com.mantz_it.rfanalyzer.database.collectAppState
import com.mantz_it.rfanalyzer.source.AirspyHfSource
import com.mantz_it.rfanalyzer.source.AirspySource
import com.mantz_it.rfanalyzer.source.HydraSdrSource
import com.mantz_it.rfanalyzer.source.KiwiSdrSource
import com.mantz_it.rfanalyzer.ui.MainActivity
import com.mantz_it.rfanalyzer.ui.tabs.FilesourceFileFormat
import com.mantz_it.rfanalyzer_ipc.Ax25Variant
import com.mantz_it.rfanalyzer_ipc.DigitalDemodParams
import com.mantz_it.rfanalyzer_ipc.DigitalDemodulationConfiguration
import com.mantz_it.rfanalyzer_ipc.digiext.Event
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import com.mantz_it.rfanalyzer.database.RecordingDao
import com.mantz_it.rfanalyzer.database.Recording
import kotlinx.coroutines.SupervisorJob
import com.mantz_it.rfanalyzer.database.calculateFileName
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.withContext

/**
 * <h1>RF Analyzer - Analyzer Service</h1>
 *
 * Module:      AnalyzerService.kt
 * Description: Foreground Service which orchestrates the signal processing pipeline.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

@AndroidEntryPoint
class AnalyzerService : Service() {
    @Inject lateinit var appStateRepository: AppStateRepository
    @Inject lateinit var digitalExtensionRepository: DigitalExtensionRepository
    @Inject lateinit var digitalExtensionStatus: DigitalExtensionStatus
    @Inject lateinit var captureSessionRepository: CaptureSessionRepository
    @Inject lateinit var recordingDao: RecordingDao

    // Not cancelled with serviceScope in onDestroy: a recording that the shutdown itself finishes
    // must still be stored.
    private val recordingStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job()) // Coroutine scope for observing the App State
    private val binder = LocalBinder()
    private var isBound = false
    // The app was swiped away while recording (onTaskRemoved): when the recording ends, the
    // analyzer stops too. Cleared when the app comes back.
    @Volatile
    private var taskRemoved = false
    private var supervisionJob: Job? = null

    var source: IQSourceInterface? = null
        private set
    var scheduler: Scheduler? = null
        private set
    var demodulator: Demodulator? = null
        private set
    var fftProcessor: FftProcessor? = null
        private set
    /** The squelch's decision: made by the FFT, read by the Scheduler (Squelch.kt). */
    private val squelchGate = SquelchGate()
    var digitalExtensionConnection: DigitalExtensionConnection? = null
        private set

    inner class LocalBinder : Binder() {
        fun getService(): AnalyzerService = this@AnalyzerService
    }

    companion object {
        private const val TAG = "AnalyzerService"
        const val ACTION_STOP = "com.mantz_it.rfanalyzer.analyzer.ACTION_STOP"
        const val ACTION_STOP_RECORDING = "com.mantz_it.rfanalyzer.analyzer.ACTION_STOP_RECORDING"
        private const val ACTION_USB_SDR_ATTACHED = "com.mantz_it.rfanalyzer.analyzer.ACTION_USB_SDR_ATTACHED"
        private const val EXTRA_SOURCE_TYPE = "com.mantz_it.rfanalyzer.analyzer.EXTRA_SOURCE_TYPE"
        private const val RECORDING_NOTICE_ID = 2
        private const val USAGE_TIME_PERSIST_SECONDS = 30
        // See checkDigitalExtensionLink()
        private const val DIGITAL_EXTENSION_SILENCE_MS = 3_000L
        /** The FFT's squelch line follows the noise floor only once it moved this far. */
        private const val SQUELCH_LINE_DEAD_BAND_DB = 1f
        private const val DIGITAL_EXTENSION_CONNECT_TIMEOUT_MS = 5_000L
        private const val DIGITAL_EXTENSION_MAX_FAILURES = 3
        private const val DIGITAL_EXTENSION_FAILURE_WINDOW_MS = 60_000L
        // how long after a USB dropout re-plugging the same SDR type restarts the analyzer: a loose
        // contact reconnects within seconds; an SDR unplugged for longer was meant to stop
        private const val USB_DROPOUT_RESTART_WINDOW_MS = 10_000L
        private const val BACKGROUND_FFT_FPS = 1f
        private const val BACKGROUND_SQUELCH_FFT_FPS = 10f

        // the running service, for UsbAttachActivity (same process)
        @Volatile
        var instance: AnalyzerService? = null
            private set

        /**
         * UsbAttachActivity, with the app in the background: starts the service in the foreground
         * for an SDR of [sourceType] just plugged in (onUsbSdrAttached), leaving the app where it
         * is. Allowed while the activity is in front; false if Android refuses anyway.
         */
        fun startForUsbSdr(context: Context, sourceType: SourceType): Boolean = try {
            context.startForegroundService(Intent(context, AnalyzerService::class.java)
                .setAction(ACTION_USB_SDR_ATTACHED)
                .putExtra(EXTRA_SOURCE_TYPE, sourceType.name))
            true
        } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException
            Log.w(TAG, "startForUsbSdr: not allowed - ${e.message}")
            false
        }
    }

    // A USB SDR that stopped streaming by itself while the analyzer ran (e.g. a loose contact):
    // (source type, time). Until it is attached again (onUsbSdrAttached) or the window ends, the
    // service waits in the foreground, so it can restart also with the app in the background.
    @Volatile
    private var usbDropout: Pair<SourceType, Long>? = null
    private var usbDropoutTimeout: Job? = null
    @Volatile
    private var waitingForUsbSdr = false // in the foreground for usbDropout, see waitForUsbSdr()

    /** A dropped-out USB SDR is awaited (see [usbDropout]). */
    val isWaitingForUsbSdr: Boolean
        get() = waitingForUsbSdr

    // The source reported why streaming ended; the Scheduler's own "no more samples" then adds nothing.
    @Volatile
    private var sourceFailureReported = false

    private fun recordUsbDropout(source: IQSourceInterface?) {
        if (source is UsbSdrSource<*> && appStateRepository.analyzerRunning.value)
            usbDropout = Pair(appStateRepository.sourceType.value, System.currentTimeMillis())
    }

    // After stopAnalyzer() for a dropout: stay in the foreground until the SDR is back or the window ends
    private fun waitForUsbSdr() {
        val (sourceType, lostAtMs) = usbDropout ?: return
        Log.i(TAG, "waitForUsbSdr: waiting for $sourceType to be reconnected.")
        ServiceCompat.startForeground(this, 1, createNotification("Waiting for ${sourceType.displayName} to be reconnected..."), foregroundServiceType())
        waitingForUsbSdr = true
        updateUsbAttachHandling()
        usbDropoutTimeout?.cancel()
        usbDropoutTimeout = serviceScope.launch {
            delay(lostAtMs + USB_DROPOUT_RESTART_WINDOW_MS - System.currentTimeMillis())
            if (waitingForUsbSdr) {
                Log.i(TAG, "waitForUsbSdr: $sourceType did not come back - giving up.")
                endUsbDropoutWait()
                stopForegroundService()
            }
        }
    }

    /** Ends the wait for a dropped-out SDR; returns whether the service was waiting in the foreground. */
    private fun endUsbDropoutWait(): Boolean {
        usbDropoutTimeout?.cancel()
        usbDropoutTimeout = null
        val wasWaiting = waitingForUsbSdr
        waitingForUsbSdr = false
        usbDropout = null
        updateUsbAttachHandling()
        return wasWaiting
    }

    // Android offers RF Analyzer when an SDR is plugged in (UsbAttachActivity) only while that is
    // wanted - the user opted in, or a dropped-out SDR is awaited - so it doesn't compete with other
    // SDR apps for every plug-in.
    private fun updateUsbAttachHandling() {
        UsbAttachActivity.setEnabled(this, appStateRepository.startAnalyzerOnUsbAttach.value || waitingForUsbSdr)
    }

    private val iqSourceActions: IQSourceInterface.Callback = object : IQSourceInterface.Callback {
        override fun onIQSourceReady(source: IQSourceInterface) {
            serviceScope.launch {
                startScheduler()
            }
        }

        override fun onIQSourceError(source: IQSourceInterface, message: String) {
            val errorMessage = "Error with Source (${source?.name}): $message"
            Log.e(TAG, "onIQSourceError: $errorMessage")
            recordUsbDropout(source)
            sourceFailureReported = true
            // Android 17 lets a connection into the local network just time out without the
            // permission - say so (MainActivity asks before starting, but not every start comes from there)
            val hint = if ((source is RtltcpSource || source is KiwiSdrSource) && LocalNetworkAccess.isBlocked(this@AnalyzerService))
                " - if the server is in your local network, allow RF Analyzer's local network access (app settings: Permissions → Nearby devices)" else ""
            serviceScope.launch {
                appStateRepository.emitAnalyzerEvent(AppStateRepository.AnalyzerEvent.SourceFailure("Source: $message$hint"))
                stopAnalyzer()
            }
        }

        override fun onIQSourceInformation(source: IQSourceInterface, message: String) {
            Log.i(TAG, "onIQSourceInformation: $message")
            if (message != null) {
                serviceScope.launch {
                    appStateRepository.emitAnalyzerEvent(AppStateRepository.AnalyzerEvent.IQSourceInformation(message))
                }
            }
        }
    }

    // Invoked by the Scheduler when it ended by itself because the source stopped delivering
    // packets. Like iqSourceActions.onIQSourceError(): stops the analyzer, so the UI never shows a
    // dead pipeline as running.
    private fun onSchedulerStoppedUnexpectedly() {
        Log.e(TAG, "onSchedulerStoppedUnexpectedly: Scheduler stopped without a stop request - stopping analyzer.")
        recordUsbDropout(source)
        serviceScope.launch {
            if (!sourceFailureReported)
                appStateRepository.emitAnalyzerEvent(
                    AppStateRepository.AnalyzerEvent.SourceFailure("Streaming stopped unexpectedly (no more samples from source).")
                )
            stopAnalyzer()
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind: Service is bound.")
        isBound = true
        taskRemoved = false
        cancelRecordingNotice()
        applyFftFramesPerSecond()
        return binder
    }

    override fun onRebind(intent: Intent?) {
        Log.d(TAG, "onRebind: Service is bound.")
        isBound = true
        taskRemoved = false
        cancelRecordingNotice()
        applyFftFramesPerSecond()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isBound = false
        applyFftFramesPerSecond()
        if(appStateRepository.analyzerRunning.value || waitingForUsbSdr) {
            // also while a dropped-out SDR is awaited: the restart must work with the app in the
            // background (the activity unbinds on Home, an app switch, a rotation)
            Log.d(TAG, "onUnbind: Service is unbound. Keep running in the background")
            return true // don't terminate the service if the analyzer is still running
        } else {
            Log.d(TAG, "onUnbind: Service is unbound. Stop Service.")
            stopSelf()  // stop service if activity disconnects
            return false
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service created.")
        instance = this

        // Start Logging to file:
        if(appStateRepository.loggingEnabled.value)
            LogcatLogger.startLogging(this)

        // Load the MeshCore group-channel candidate dictionary off the main thread (used by the
        // app-side LoRa parser, LoraSpec).
        MeshCoreChannelRegistry.init(this)

        handleAppStateChanges()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        when (intent?.action) {
            ACTION_STOP_RECORDING -> {
                // "Stop recording" on the notice shown when the app was swiped away during a
                // recording: the app is gone, so the analyzer stops with the recording (which
                // stores it - storeFinishedRecording).
                Log.d(TAG, "onStartCommand: Stop recording action received.")
                cancelRecordingNotice()
                stopRecording()
                stopBasebandRecording()
                if (!isBound) {
                    stopAnalyzer()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_USB_SDR_ATTACHED -> {
                // From startForUsbSdr(): Android wants the service in the foreground within
                // seconds, whether the analyzer starts or not.
                val sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE)?.let { name -> SourceType.entries.firstOrNull { it.name == name } }
                val wasInForeground = appStateRepository.analyzerRunning.value || appStateRepository.analyzerStartPending.value || waitingForUsbSdr
                ServiceCompat.startForeground(this, 1, createNotification(notificationText()), foregroundServiceType())
                val started = sourceType != null && onUsbSdrAttached(sourceType)
                if (!started && !wasInForeground) {
                    appStateRepository.analyzerStartPending.set(false)
                    stopForegroundService()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                // If the stop action is received from the notification's stop button
                Log.d(TAG, "onStartCommand: Stop action received from Notification. Stopping service.")
                endUsbDropoutWait()
                stopAnalyzer()
                if(!isBound)
                    stopSelf() // Stop the service if activity is not connected
                return START_NOT_STICKY // Ensure the service is not restarted automatically
            }
            else -> {
                // This handles the case where the service is started by the activity or any other intent
                Log.d(TAG, "onStartCommand: Service started sticky.")
                // keep the service running:
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        endUsbDropoutWait()
        stopAnalyzer() // make sure all threads are stopped
        // stopAnalyzer() releases the extension in a coroutine of serviceScope, which is cancelled
        // right below, before that coroutine could run: unbind here (the extension shuts down when
        // its last client leaves).
        digitalExtensionConnection?.unbind()
        serviceScope.cancel() // Clean up the coroutine scope
        cancelRecordingNotice()
        Log.d(TAG, "onDestroy: Service destroyed.")
        LogcatLogger.stopLogging()
    }

    /**
     * The app was swiped away from the recent apps: that closes it, so the analyzer stops - also
     * one demodulating, which otherwise keeps running when the app is merely in the background.
     * A recording keeps running instead (stopping it could lose what the user wanted to record);
     * a heads-up notice says so and offers to stop it. A dialog isn't possible here: the task is
     * already gone, and Android blocks starting an activity from the background.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (appStateRepository.anyRecordingRunning.value) {
            Log.i(TAG, "onTaskRemoved: app swiped away while recording - recording continues in the background")
            taskRemoved = true
            showRecordingNotice()
            return
        }
        Log.i(TAG, "onTaskRemoved: app swiped away - stopping")
        val wasWaiting = endUsbDropoutWait()
        if (appStateRepository.analyzerRunning.value) stopAnalyzer()
        else if (wasWaiting) stopForegroundService()
        stopSelf()
    }

    private fun showRecordingNotice() {
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopRecordingIntent = PendingIntent.getService(
            this, 1, Intent(this, AnalyzerService::class.java).apply { action = ACTION_STOP_RECORDING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notice = NotificationCompat.Builder(this, "RECORDING_CHANNEL")
            .setContentTitle("Recording continues in the background")
            .setContentText("RF Analyzer was closed while recording. The recording goes on until you stop it or its limit is reached.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("RF Analyzer was closed while recording. The recording goes on until you stop it or its limit is reached."))
            .setSmallIcon(R.drawable.signal_wave)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .addAction(R.drawable.stop_circle, "Stop recording", stopRecordingIntent)
            .addAction(R.drawable.signal_wave, "Open", openIntent)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(RECORDING_NOTICE_ID, notice)
        } catch (e: SecurityException) {
            Log.w(TAG, "showRecordingNotice: notifications not allowed - ${e.message}")
        }
    }

    private fun cancelRecordingNotice() = NotificationManagerCompat.from(this).cancel(RECORDING_NOTICE_ID)

    private fun stopForegroundService() {
        Log.d(TAG, "stopForegroundService: removing notification.")
        stopForeground(STOP_FOREGROUND_REMOVE) // This removes the notification
        stopSelf() // remove the 'started' state so the service stops when unbound
    }

    /**
     * What the foreground service does, as Android 14+ and Google Play's declaration want it
     * named: always media playback (the demodulated audio, which Android 17 lets play in the
     * background only from a mediaPlayback service), and with an SDR - USB, or an rtl_tcp server
     * or KiwiSDR over the network - also connected device (keeping that connection streaming).
     * The file source is playback alone. connectedDevice's prerequisite is met by
     * CHANGE_NETWORK_STATE in the manifest, also before the USB permission is granted.
     */
    private fun foregroundServiceType(): Int =
        if (appStateRepository.sourceType.value == SourceType.FILESOURCE) FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        else FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    private fun startForegroundService() {
        val notification = createNotification(notificationText())
        Log.d(TAG, "startForegroundService: Moving service to foreground.")
        ServiceCompat.startForeground(this, 1, notification, foregroundServiceType())
        // Started as well as bound, so it doesn't stop when unbound
        val serviceIntent = Intent(this, this::class.java)
        startService(serviceIntent)
    }

    private var shownNotificationText: String? = null

    /** The notification says so when the selected mode's digital part decodes nothing - the app
     *  may be in the background, with the user waiting for packets. */
    private fun notificationText(): String {
        val notice = digitalExtensionStatus.status.value.notice()?.takeIf {
            DigitalFeatures.shown && usesDigitalExtension(appStateRepository.demodulationMode.value, appStateRepository.demodulationWfmStereoAndRdsEnabled.value)
        }
        return if (notice != null) "${notice.title} - ${notice.text}" else "RF Analyzer is running..."
    }

    private fun refreshNotification() {
        if (!appStateRepository.analyzerRunning.value) return
        val text = notificationText()
        if (text == shownNotificationText) return
        try {
            NotificationManagerCompat.from(this).notify(1, createNotification(text))
        } catch (e: SecurityException) {
            Log.w(TAG, "refreshNotification: notifications not allowed - ${e.message}")
        }
    }

    private fun createNotification(text: String = "RF Analyzer is running..."): Notification {
        shownNotificationText = text
        Log.d(TAG, "createNotification: Creating foreground notification.")

        // Intent to launch the main activity
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val activityPendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent to stop the service
        val stopIntent = Intent(this, AnalyzerService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "SERVICE_CHANNEL")
            .setContentTitle("RF Analyzer")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.signal_wave)
            .setContentIntent(activityPendingIntent)
            .addAction(R.drawable.stop_circle, "Stop", pendingStopIntent)
            .build()
    }

    /**
     * Will stop the RF Analyzer. This includes shutting down the scheduler (which turns off the
     * source) and the demodulator if running.
     */
    fun stopAnalyzer() {
        Log.i(TAG, "stopAnalyzer")
        // Stop the Scheduler if running:
        scheduler?.stopScheduler()

        // Stop the Demodulator if running:
        demodulator?.stopDemodulator()

        fftProcessor?.stopLoop()

        // Wait for the scheduler to stop:
        try {
            if (scheduler?.name != Thread.currentThread().name)
                scheduler?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "stopAnalyzer: Error while stopping Scheduler.")
        }

        // Wait for the FFT thread too: it releases its native state on exit (bounded, like the
        // demodulator below - stopLoop() wakes it from its frame wait).
        try {
            fftProcessor?.join(1000)
        } catch (e: InterruptedException) {
            Log.e(TAG, "stopAnalyzer: Error while stopping FftProcessor.")
        }

        // Unbind Digital Extension Service (if bound)
        stopDigitalExtensionConnection()

        // Wait for the demodulator to stop: after a quick restart a new Demodulator and this one
        // would otherwise both write into the same IqSharedMemoryRingWriter, unsynchronized.
        // Bounded, so a hung poll can't block the shutdown; stopDemodulator() interrupts it, so
        // this normally returns at once.
        try {
            if (demodulator?.name != Thread.currentThread().name)
                demodulator?.join(2000)
            if (demodulator?.isAlive == true)
                Log.w(TAG, "stopAnalyzer: Demodulator thread did not stop within timeout - proceeding anyway.")
        } catch (e: InterruptedException) {
            Log.e(TAG, "stopAnalyzer: Error while stopping Demodulator.")
        }
        captureSessionRepository.endSession()
        source?.close()
        source = null
        appStateRepository.sourceName.set("")
        appStateRepository.analyzerRunning.set(false)
        appStateRepository.analyzerStartPending.set(false)
        GlobalPerformanceData.reset()
        OverloadMonitor.reset()

        if (usbDropout != null) waitForUsbSdr() else stopForegroundService()
    }

    /**
     * Will start the RF Analyzer. This includes creating a source (if null), open a source
     * (if not open), starting the scheduler (which starts the source) and starting the
     * processing loop.
     */
    fun startAnalyzer() : Boolean {
        Log.d(TAG, "startAnalyzer")
        // still in the foreground from waiting for a dropped-out SDR: leave it only if starting fails
        val wasWaiting = endUsbDropoutWait()
        sourceFailureReported = false
        val started = startSourceAndScheduler()
        if (!started && wasWaiting) stopForegroundService()
        return started
    }

    private fun startSourceAndScheduler(): Boolean {
        if (source == null) {
            if (!this.createSource()) return false
        }

        // check if the source is open. if not, open it!
        if (!source!!.isOpen()) {
            if (!openSource()) {
                Toast.makeText(this, "Source not available (${source?.name})", Toast.LENGTH_LONG).show()
                source = null
                appStateRepository.analyzerStartPending.set(false)
                return false
            }
            return true // we have to wait for the source to become ready... onIQSourceReady() will call startScheduler()...
        } else {
            return startScheduler()
        }
    }

    /**
     * UsbAttachActivity (the USB permission is granted at this point): a USB SDR of [sourceType] was
     * plugged in. Restarts the analyzer if that source type dropped out shortly before (see
     * [usbDropout]), or starts it if the user opted in (startAnalyzerOnUsbAttach). Returns whether
     * it starts.
     */
    fun onUsbSdrAttached(sourceType: SourceType): Boolean {
        if (appStateRepository.analyzerRunning.value || appStateRepository.analyzerStartPending.value) return false
        val dropout = usbDropout
        val restart = dropout != null && dropout.first == sourceType &&
                System.currentTimeMillis() - dropout.second <= USB_DROPOUT_RESTART_WINDOW_MS
        val autostart = appStateRepository.startAnalyzerOnUsbAttach.value
        if (!restart && !autostart) return false
        if (restart) {
            Log.i(TAG, "onUsbSdrAttached: $sourceType is back after a USB dropout - restarting the analyzer.")
            appStateRepository.emitAnalyzerEvent(AppStateRepository.AnalyzerEvent.IQSourceInformation("${sourceType.displayName} is back - restarting"))
        } else {
            Log.i(TAG, "onUsbSdrAttached: $sourceType plugged in - starting the analyzer (startAnalyzerOnUsbAttach).")
        }
        appStateRepository.sourceType.set(sourceType)
        appStateRepository.analyzerStartPending.set(true)
        return startAnalyzer()
    }

    private fun startScheduler(): Boolean {
        if(source == null) return false

        // Ensure that source sample rate is supported:
        val supportedSampleRates = source!!.supportedSampleRates
        val currentSampleRate = source!!.sampleRate
        if(!supportedSampleRates.contains(source!!.sampleRate)) {
            val bestMatch = supportedSampleRates.minByOrNull { supportedSampleRate -> abs(supportedSampleRate - currentSampleRate) }
            if(bestMatch == null || bestMatch == 0)
                Log.w(TAG, "startScheduler: Source sample rate $currentSampleRate is not supported! (${supportedSampleRates})")
            else
                source!!.sampleRate = bestMatch
        }

        // Open a capture session here rather than in startAnalyzer(): this is the first point where
        // the source is confirmed open and its name/frequency/sample rate are actually known.
        captureSessionRepository.startSession(
            sourceType = appStateRepository.sourceType.value.name,
            sourceLabel = source!!.name,
            tunedFrequencyHz = source!!.frequency,
            sampleRate = source!!.sampleRate.toLong(),
            appVersion = BuildConfig.VERSION_NAME,
            extensionVersion = DigitalExtensionConnection.digitalExtensionVersionName(this),
            // No receiver location: it is opt-in (CaptureSessionEntity) and nothing asks for it yet.
        )

        // Source was successfully opened, let the UI know:
        appStateRepository.sourceName.set(source!!.name)
        appStateRepository.sourceMinimumFrequency.set(source!!.minFrequency)
        appStateRepository.sourceMaximumFrequency.set(source!!.maxFrequency)
        appStateRepository.sourceSupportedSampleRates.set(supportedSampleRates.map { it.toLong() })
        appStateRepository.sourceFrequency.set(source!!.frequency)
        appStateRepository.sourceSampleRate.set(source!!.sampleRate.toLong())
        // before the FFT and the demodulation read it
        appStateRepository.moveChannelIntoSourceSignal()?.let { frequency ->
            if (appStateRepository.demodulationEnabled.value)
                appStateRepository.emitAnalyzerEvent(AppStateRepository.AnalyzerEvent.IQSourceInformation(
                    "The channel was outside the received band - moved to ${frequency.asStringWithUnit("Hz")}"))
        }
        if (source is RtlsdrSource || source is RtltcpSource) {
            val currentGainIndex = appStateRepository.rtlsdrGainIndex.value
            val currentIFGainIndex = appStateRepository.rtlsdrIFGainIndex.value
            val gainIndexList = when (val s = source) {
                is RtlsdrSource -> s.possibleGainValues.toList()
                is RtltcpSource -> s.possibleGainValues.toList()
                else -> emptyList()
            }
            val ifGainIndexList = when (val s = source) {
                is RtlsdrSource -> s.possibleIFGainValues.toList()
                is RtltcpSource -> s.possibleIFGainValues.toList()
                else -> emptyList()
            }
            appStateRepository.rtlsdrGainIndex.set(0)
            appStateRepository.rtlsdrIFGainIndex.set(0)
            appStateRepository.rtlsdrGainSteps.set(gainIndexList)
            appStateRepository.rtlsdrIFGainSteps.set(ifGainIndexList)
            appStateRepository.rtlsdrGainIndex.set(currentGainIndex.coerceAtMost(gainIndexList.size - 1))
            appStateRepository.rtlsdrIFGainIndex.set(currentIFGainIndex.coerceAtMost(ifGainIndexList.size - 1))
        }
        if (source is HydraSdrSource) {
            // The device's gain ranges, RF ports and frequency range for the UI (the minimum and
            // maximum frequency above already come from it: HydraSdrSource prefers deviceInfo).
            appStateRepository.hydraSdrDeviceInfo.set((source as HydraSdrSource).deviceInfo)
        }

        // Create FftProcessor instance
        fftProcessor = FftProcessor(
            initialFftSize = appStateRepository.fftSize.value,
            initialSampleRate = appStateRepository.sourceSampleRate.value,
            initialFrequency = appStateRepository.sourceFrequency.value,
            initialFftFramesPerSecond = appStateRepository.waterfallFps.value.toFloat(),
            initialChannelFrequencyRange = squelchChannelRange(),
            initialFftPaddingRatio = appStateRepository.fftPaddingRatio.value,
            fftProcessorData = appStateRepository.fftProcessorData,
            waterfallBufferSize = if(appStateRepository.enableLowPerformanceMode.value) 250 else 400,
            fftPeakHold = appStateRepository.fftPeakHold.value,
            squelchGate = squelchGate,
            onChannelLevelChanged = { level ->
                appStateRepository.averageSignalStrength.set(level)
                showSquelchLine(force = false)
                appStateRepository.squelchSatisfied.set(squelchGate.open)
            },
        )
        applyFftFramesPerSecond() // started in the background (USB attach, a dropped-out SDR back)

        // Create a new instance of Scheduler
        scheduler = Scheduler(source!!, fftProcessor!!::putNewFftSamples, ::onSchedulerStoppedUnexpectedly, squelchGate)

        // Start the demodulator thread:
        demodulator = Demodulator(
            scheduler!!.demodOutputQueue,
            scheduler!!.demodInputQueue,
            source!!.packetSize / source!!.bytesPerSample,
            pushDigitalExtensionInputSamples = ::pushDigitalExtensionInputSamples,
            pollDigitalExtensionOutputSamples = ::pollDigitalExtensionOutputSamples,
            appStateRepository.enableLowPerformanceMode.value,
        )
        demodulator!!.audioVolumeLevel = appStateRepository.effectiveAudioVolumeLevel.value
        if(appStateRepository.enableLowPerformanceMode.value)
            demodulator!!.filterQuality = appStateRepository.lowPerformanceModeFilterQuality.value
        demodulator!!.start()

        applyDemodulationSettings()

        // Start the scheduler and fft processor
        scheduler!!.start()
        fftProcessor!!.start()

        // Set state to running and hand over fft processor queues to UI
        appStateRepository.analyzerRunning.set(true)
        appStateRepository.analyzerStartPending.set(false)
        digitalExtensionFailureTimes.clear()
        startForegroundService()
        launchSupervisionCoroutine()
        launchOverloadMonitorCoroutine()

        // Workaround: with manual gain enabled, the RTL-SDR doesn't accept the gain set while it starts - set it again here
        if ((source is RtlsdrSource || source is RtltcpSource) && appStateRepository.rtlsdrManualGainEnabled.value) {
            val gainValue = appStateRepository.rtlsdrGainSteps.value[appStateRepository.rtlsdrGainIndex.value]
            val ifGainValue = appStateRepository.rtlsdrIFGainSteps.value[appStateRepository.rtlsdrIFGainIndex.value]
            (source as? RtlsdrSource)?.apply { gain = gainValue; ifGain = ifGainValue }
            (source as? RtltcpSource)?.apply { gain = gainValue; ifGain = ifGainValue }
        }

        return true
    }

    // Retry schedule (delays between attempts) for reconnecting after the Digital Extension
    // disconnected unexpectedly (it crashed, or checkDigitalExtensionLink() dropped a silent
    // binding). Silent while retrying; after the last attempt fails, the connection settles into
    // ExtensionConnectionState.UNAVAILABLE.
    private val digitalExtensionReconnectDelaysMs = listOf(1000L, 2000L, 5000L)
    private var digitalExtensionReconnectJob: Job? = null

    // A connection that should deliver but doesn't must not leave the user waiting for packets
    // that can't come. checkDigitalExtensionLink() runs every second with the analyzer and drops a
    // binding whose extension went silent (no PipelineHealth - sent once a second, also without
    // input - for DIGITAL_EXTENSION_SILENCE_MS) or that doesn't complete within
    // DIGITAL_EXTENSION_CONNECT_TIMEOUT_MS; reconnecting then starts a fresh session. Each
    // reconnect counts as a failure: more than DIGITAL_EXTENSION_MAX_FAILURES within
    // DIGITAL_EXTENSION_FAILURE_WINDOW_MS give up (UNAVAILABLE - "not responding", with Retry)
    // instead of reconnecting forever to an extension that fails right away.
    private val digitalExtensionFailureTimes = ArrayDeque<Long>()

    // True after a requested stop (stopAnalyzer(), or applyDemodulationSettings() switching to a
    // mode without the extension) until the next start: lets the connectionState collector tell
    // "we stopped it" from "the extension died or hung" - both are DISCONNECTED. Reset at the top of
    // startAndConfigureDigitalExtensionConnection(), also when it is already bound.
    private var digitalExtensionStopWasIntentional = false

    private fun stopDigitalExtensionConnection() {
        digitalExtensionStopWasIntentional = true
        digitalExtensionReconnectJob?.cancel()
        digitalExtensionReconnectJob = null
        val connection = digitalExtensionConnection
        connection?.markIdle() // clears a stale RECONNECTING banner if Stop was pressed mid-retry
        if (connection?.bound == true) {
            // unbindAndAwaitStopped(), not unbind(): a following
            // startAndConfigureDigitalExtensionConnection() must not race the extension's teardown.
            // Launched, not awaited: this is called from synchronous teardown paths (stopAnalyzer()).
            serviceScope.launch { connection.unbindAndAwaitStopped() }
        }
    }

    /** "Retry" after reconnecting gave up: a fresh start of the connection. */
    fun retryDigitalExtension() {
        digitalExtensionReconnectJob?.cancel()
        digitalExtensionReconnectJob = null
        digitalExtensionFailureTimes.clear()
        digitalExtensionConnection?.markIdle()
        applyDemodulationSettings()
    }

    private fun checkDigitalExtensionLink() {
        val connection = digitalExtensionConnection ?: return
        val state = connection.connectionState.value
        val now = System.currentTimeMillis()
        if (digitalExtensionStopWasIntentional || digitalExtensionReconnectJob?.isActive == true) return
        val stuck = when (state) {
            ExtensionConnectionState.CONNECTED -> now - connection.lastSignOfLifeMs > DIGITAL_EXTENSION_SILENCE_MS
            ExtensionConnectionState.DISCONNECTED, ExtensionConnectionState.RECONNECTING ->
                connection.bound && now - connection.lastBindMs > DIGITAL_EXTENSION_CONNECT_TIMEOUT_MS
            else -> false
        }
        if (stuck) {
            Log.w(TAG, "checkDigitalExtensionLink: Digital Extension doesn't deliver ($state) - reconnecting")
            connection.dropUnresponsive()
            startDigitalExtensionReconnectLoop(connection)
        }
    }

    private fun startDigitalExtensionReconnectLoop(connection: DigitalExtensionConnection) {
        // Bound: a bind is pending or done (a start or Retry right after a disconnect, whose
        // DISCONNECTED reached the collector late) - reconnecting it would mark a working
        // connection RECONNECTING, and nothing would be pushed to it.
        if (digitalExtensionReconnectJob?.isActive == true || connection.bound) return
        val now = System.currentTimeMillis()
        digitalExtensionFailureTimes.addLast(now)
        while (digitalExtensionFailureTimes.first() < now - DIGITAL_EXTENSION_FAILURE_WINDOW_MS) digitalExtensionFailureTimes.removeFirst()
        if (digitalExtensionFailureTimes.size > DIGITAL_EXTENSION_MAX_FAILURES) {
            Log.w(TAG, "Digital Extension failed ${digitalExtensionFailureTimes.size} times within ${DIGITAL_EXTENSION_FAILURE_WINDOW_MS / 1000} s - giving up.")
            connection.markUnavailable()
            return
        }
        connection.markReconnecting()
        digitalExtensionReconnectJob = serviceScope.launch {
            for ((index, delayMs) in digitalExtensionReconnectDelaysMs.withIndex()) {
                delay(delayMs)
                if (connection.bound) return@launch // bound meanwhile by a new start
                Log.d(TAG, "Reconnecting to Digital Extension, attempt ${index + 1}/${digitalExtensionReconnectDelaysMs.size}...")
                if (connection.bind()) return@launch // outcome reported asynchronously via connectionState
                if (connection.connectionState.value == ExtensionConnectionState.NOT_INSTALLED
                    || connection.connectionState.value == ExtensionConnectionState.NOT_PERMITTED) {
                    Log.w(TAG, "Digital Extension can't be bound (${connection.connectionState.value}), stopping reconnect attempts.")
                    return@launch
                }
            }
            Log.w(TAG, "Giving up reconnecting to Digital Extension after ${digitalExtensionReconnectDelaysMs.size} attempts.")
            connection.markUnavailable()
        }
    }

    private fun startAndConfigureDigitalExtensionConnection(): Boolean {
        digitalExtensionStopWasIntentional = false
        if (digitalExtensionConnection == null) {
            val connection = DigitalExtensionConnection(
                this,
                onPacket = digitalExtensionRepository::onPacket,
                onStreamElement = digitalExtensionRepository::onStreamElement,
                onDemodStatus = digitalExtensionRepository::onDemodStatusReceived,
            )
            digitalExtensionConnection = connection
            serviceScope.launch {
                // StateFlow.collect() immediately replays the CURRENT value (DISCONNECTED, the
                // freshly-constructed default) to this collector - which can run after bind() has
                // already succeeded, since launch() dispatches asynchronously. Forward that replay
                // to the UI (it should reflect reality right away), but don't treat it as a real
                // "just disconnected" transition, or a healthy connection gets spuriously reconnected.
                var isFirstEmission = true
                connection.connectionState.collect { state ->
                    digitalExtensionRepository.onExtensionConnectionStateChanged(state)
                    if (!isFirstEmission && state == ExtensionConnectionState.DISCONNECTED && !digitalExtensionStopWasIntentional) {
                        startDigitalExtensionReconnectLoop(connection)
                    }
                    isFirstEmission = false
                }
            }
        }
        digitalExtensionConnection?.setDigitalDemodulationConfiguration(DigitalDemodulationConfiguration(
            demodulationParams = when(appStateRepository.demodulationMode.value) {
                DemodulationMode.WFM -> DigitalDemodParams.Wfm
                DemodulationMode.RTTY -> DigitalDemodParams.Rtty(
                    baudRate = appStateRepository.effectiveRttyBaudRate.value,
                    frequencyShift = appStateRepository.effectiveRttyFrequencyShift.value,
                    polarity = appStateRepository.effectiveRttyPolarity.value,
                )
                DemodulationMode.PSK31 -> DigitalDemodParams.Psk31(
                    baudRate = appStateRepository.effectivePsk31BaudRate.value,
                    captureWidthHz = appStateRepository.channelWidth.value.toFloat(),
                )
                DemodulationMode.R09 -> DigitalDemodParams.R09(baudRate = appStateRepository.effectiveR09BaudRate.value)
                DemodulationMode.FT8 -> DigitalDemodParams.Ft8(
                    timingMode = appStateRepository.ft8TimingMode.value,
                    manualOffsetMs = appStateRepository.ft8TimeOffsetMs.value,
                )
                DemodulationMode.AX25 -> DigitalDemodParams.Ax25(
                    variant = when (appStateRepository.ax25Variant.value) {
                        300 -> Ax25Variant.HF_300
                        9600 -> Ax25Variant.G3RUH_9600
                        else -> Ax25Variant.VHF_1200
                    }
                )
                DemodulationMode.ADSB -> DigitalDemodParams.Adsb
                DemodulationMode.POCSAG -> DigitalDemodParams.Pocsag(
                    baudRate = appStateRepository.effectivePocsagBaudRate.value.let { if (it == 0) null else it }
                )
                DemodulationMode.LORA -> DigitalDemodParams.Lora(
                    bandwidth = appStateRepository.channelWidth.value,
                    spreadingFactors = appStateRepository.effectiveLoraSpreadingFactors.value,
                    iqMode = appStateRepository.effectiveLoraIqMode.value,
                )
                else -> null
            },
            enableAudioOutput = true,
        ))
        if (digitalExtensionConnection?.bound == false) {
            val installed = DigitalExtensionConnection.isDigitalExtensionPackageInstalled(this)
            val resolvable = digitalExtensionConnection?.canResolveDigitalExtensionService(this)
            val permission = digitalExtensionConnection?.hasIpcPermission(this)
            Log.d(TAG, "startDigitalExtensionConnection: Binding digital extension connection (installed=$installed, resolvable=$resolvable, permission=$permission)..")
            if (digitalExtensionConnection?.bind() == false) {
                Log.w(TAG, "startDigitalExtensionConnection: failed to bind digital extension connection.")
                digitalExtensionConnection?.unbind()
                return false
            }
        }
        return true
    }

    fun pushDigitalExtensionInputSamples(inputSamples: SamplePacket): Boolean {
        captureSessionRepository.sourceClock.onSamples(inputSamples)
        return digitalExtensionConnection?.pushInputSamples(inputSamples) == true
    }

    fun pollDigitalExtensionOutputSamples(outputSamples: SamplePacket): Boolean {
        return digitalExtensionConnection?.pollOutputSamples(outputSamples) == true
    }

    private var overloadMonitorJob: Job? = null

    // Closes OverloadMonitor's 1 s window while the analyzer runs (see OverloadMonitor).
    private fun launchOverloadMonitorCoroutine() {
        OverloadMonitor.reset()
        overloadMonitorJob?.cancel()
        overloadMonitorJob = serviceScope.launch {
            while (appStateRepository.analyzerRunning.value) {
                delay(1000)
                if (!appStateRepository.analyzerRunning.value) break
                OverloadMonitor.tick()
            }
        }
    }

    private fun launchSupervisionCoroutine() {
        // Launch a coroutine to supervise the activity connection and measure running time. One at
        // a time: after a quick Stop -> Start the previous loop, still in its delay, would see
        // "running" again and count the trial's usage time twice.
        supervisionJob?.cancel()
        supervisionJob = serviceScope.launch {
            var secondsSinceActivityStopped = 0
            // The usage timer is a persisted setting: every change rewrites the whole preferences
            // file, so it is only written every USAGE_TIME_PERSIST_SECONDS and when the loop ends;
            // until then the seconds count in AppStateRepository's unsaved counters (shown live).
            // The digital trial counts actual decoding only: a digital mode with the extension
            // connected (not FM's Stereo & RDS).
            val unsavedUsage = appStateRepository.unsavedUsageSeconds
            val unsavedDigital = appStateRepository.unsavedDigitalTrialSeconds
            // The digital time first: the trial check that follows the usage time (MainViewModel)
            // reads both. On the main thread, like the UI's collectors, which see only the result.
            fun saveUsageTime() {
                if (unsavedDigital.value > 0) {
                    appStateRepository.digitalTrialUsedSeconds.set(appStateRepository.digitalTrialUsedSeconds.value + unsavedDigital.value)
                    unsavedDigital.set(0)
                }
                appStateRepository.appUsageTimeInSeconds.set(appStateRepository.appUsageTimeInSeconds.value + unsavedUsage.value)
                unsavedUsage.set(0)
            }
            // finally: a loop cancelled by the next start still saves its unsaved seconds
            try {
                while (appStateRepository.analyzerRunning.value) {
                    delay(1000) // Checks every second
                    if (!isBound)
                        secondsSinceActivityStopped += 1
                    else
                        secondsSinceActivityStopped = 0

                    // Stop Analyzer after activity is absent for 5 seconds
                    if (secondsSinceActivityStopped > 5 && !appStateRepository.recordingRunning.value && appStateRepository.demodulationMode.value == DemodulationMode.OFF) {
                        Log.i(TAG, "launchSupervisionCoroutine: Activity was unbound for 5 seconds. Stopping Analyzer..")
                        stopAnalyzer()
                    }

                    checkDigitalExtensionLink()

                    if (!appStateRepository.isFullVersion.value && appStateRepository.demodulationMode.value.isDigitalExtension
                        && digitalExtensionConnection?.connectionState?.value == ExtensionConnectionState.CONNECTED)
                        unsavedDigital.set(unsavedDigital.value + 1)
                    unsavedUsage.set(unsavedUsage.value + 1) // increase app usage timer
                    if (unsavedUsage.value >= USAGE_TIME_PERSIST_SECONDS) saveUsageTime()
                }
            } finally {
                saveUsageTime()
            }
        }
    }

    // With the activity in the background nothing draws the FFT, but its channel power drives the
    // squelch (also a squelch-gated recording): the FFT runs only as often as that needs, or once a
    // second - at the waterfall's 30+ rows/s it would keep most of a core busy for nothing. The
    // source and the demodulation are not affected.
    private fun applyFftFramesPerSecond() {
        fftProcessor?.fftFramesPerSecond = when {
            isBound -> appStateRepository.waterfallFps.value.toFloat()
            appStateRepository.effectiveSquelchEnabled.value -> minOf(appStateRepository.waterfallFps.value.toFloat(), BACKGROUND_SQUELCH_FFT_FPS)
            else -> BACKGROUND_FFT_FPS
        }
    }

    /** Where the FFT measures the squelch's channel level: the channel's signal (SSB and FT8 on their side). */
    private fun squelchChannelRange(): Pair<Long, Long> {
        val asr = appStateRepository
        val range = signalRange(asr.demodulationMode.value, asr.channelFrequency.value, asr.channelWidth.value)
        return range.first to range.last
    }

    private fun applySquelchSettings() {
        squelchGate.marginDb = appStateRepository.effectiveSquelchMargin.value
        squelchGate.enabled = appStateRepository.effectiveSquelchEnabled.value && appStateRepository.demodulationMode.value != DemodulationMode.OFF
        if (!squelchGate.enabled) appStateRepository.squelchSatisfied.set(true)
        fftProcessor?.channelFrequencyRange = squelchChannelRange()
        showSquelchLine(force = true) // a dragged line stays under the finger
    }

    /**
     * Where the FFT draws the squelch line: the threshold in 0.5 dB steps (the FFT redraws its grid
     * when the line moves). It follows the noise floor only once that moved [SQUELCH_LINE_DEAD_BAND_DB],
     * so a floor wobbling around a step doesn't make the line (and its drag handles) flicker. A
     * margin change ([force]) moves it at once.
     */
    private fun showSquelchLine(force: Boolean) {
        val threshold = squelchGate.thresholdDb.takeIf { it.isFinite() } ?: return
        if (force || abs(threshold - appStateRepository.squelchThresholdDb.value) >= SQUELCH_LINE_DEAD_BAND_DB)
            appStateRepository.squelchThresholdDb.set(Math.round(threshold * 2) / 2f)
    }

    private fun applyDemodulationSettings(): Boolean {
        if(demodulator == null || source == null || scheduler == null)
            return false
        // Every change here may rebuild a demodulator - the mode sheet's Start changes several
        // settings one after another.
        OverloadMonitor.restartGrace()

        val demodulationMode = appStateRepository.demodulationMode.value

        // Only an extension that can work is bound: one that is missing, too old or too new, or
        // signed with another key would just never deliver - the UI says why instead
        // (DigitalAvailability). WFM then plays mono without it.
        val extensionUsable = usesDigitalExtension(demodulationMode, appStateRepository.demodulationWfmStereoAndRdsEnabled.value)
                && digitalExtensionStatus.recheck().decodes
        if (extensionUsable) {
            startAndConfigureDigitalExtensionConnection()
        } else {
            stopDigitalExtensionConnection()
        }
        refreshNotification()

        // (de-)activate demodulation in the scheduler and set the sample rate accordingly:
        if (demodulationMode == DemodulationMode.OFF) {
            Log.d(TAG, "applyDemodulationSettings: De-activating demodulation")
            scheduler!!.isDemodulationActivated = false
            demodulator!!.demodulationMode = DemodulationMode.OFF
            applySquelchSettings()
            return true
        }

        Log.d(TAG, "applyDemodulationSettings: demodulation mode = ${demodulationMode.displayName}")
        demodulator!!.wfmStereoEnabled = appStateRepository.demodulationWfmStereoAndRdsEnabled.value && extensionUsable
        demodulator!!.demodulationMode = demodulationMode
        scheduler!!.isDemodulationActivated = true
        demodulator!!.channelWidth = appStateRepository.channelWidth.value
        // A digital mode's decoder finds the signal by itself: it gets every sample.
        scheduler!!.demodulationFollowsSquelch = !demodulationMode.isDigitalExtension
        applySquelchSettings()
        scheduler!!.channelFrequency = if(demodulationMode == DemodulationMode.CW)
                appStateRepository.channelFrequency.value - CwDemodulator.CW_OFFSET_FREQUENCY
            else
                appStateRepository.channelFrequency.value
        return true
    }

    /**
     * Will create a IQ Source instance according to the user settings.
     *
     * @return true on success; false on error
     */
    private fun createSource(): Boolean {
        when (appStateRepository.sourceType.value) {
            SourceType.FILESOURCE -> source =
                FileIQSource()
            SourceType.HACKRF -> {
                // Create HackrfSource
                val hackrfSource = HackrfSource()
                hackrfSource.frequency = appStateRepository.sourceFrequency.value
                hackrfSource.sampleRate = appStateRepository.sourceSampleRate.value.toInt()
                hackrfSource.vgaGain = appStateRepository.hackrfVgaGainSteps[appStateRepository.hackrfVgaGainIndex.value]
                hackrfSource.lnaGain = appStateRepository.hackrfLnaGainSteps[appStateRepository.hackrfLnaGainIndex.value]
                hackrfSource.ampEnabled = appStateRepository.hackrfAmplifierEnabled.value
                hackrfSource.antennaPowerEnabled = appStateRepository.hackrfAntennaPowerEnabled.value
                hackrfSource.frequencyOffset = appStateRepository.hackrfConverterOffset.value.toInt()
                source = hackrfSource
            }
            SourceType.RTLSDR -> {
                if (appStateRepository.rtlsdrExternalServerEnabled.value) {
                    // Create RtltcpSource (connect to an external rtl_tcp server)
                    val rtltcpSource = RtltcpSource(
                        appStateRepository.rtlsdrExternalServerIP.value,
                        appStateRepository.rtlsdrExternalServerPort.value
                    )
                    rtltcpSource.frequency = appStateRepository.sourceFrequency.value
                    rtltcpSource.sampleRate = appStateRepository.sourceSampleRate.value.toInt()

                    rtltcpSource.frequencyCorrection = appStateRepository.rtlsdrFrequencyCorrection.value
                    rtltcpSource.frequencyOffset = appStateRepository.rtlsdrConverterOffset.value.toInt()
                    rtltcpSource.isAutomaticGainControl = appStateRepository.rtlsdrAgcEnabled.value
                    rtltcpSource.setDirectSamplingMode(appStateRepository.rtlsdrDirectSamplingMode.value.intValue)

                    if (appStateRepository.rtlsdrManualGainEnabled.value) {
                        rtltcpSource.isManualGain = true
                        // not accepted while the analyzer starts: set again at the end of startScheduler()
                        rtltcpSource.gain = appStateRepository.rtlsdrGainSteps.value[appStateRepository.rtlsdrGainIndex.value]
                        rtltcpSource.ifGain = appStateRepository.rtlsdrIFGainSteps.value[appStateRepository.rtlsdrIFGainIndex.value]
                    } else {
                        rtltcpSource.isManualGain = false
                    }
                    source = rtltcpSource
                } else {
                    // Create RtlsdrSource (native, USB-connected RTL-SDR dongle)
                    val rtlsdrSource = RtlsdrSource()
                    rtlsdrSource.frequency = appStateRepository.sourceFrequency.value
                    rtlsdrSource.sampleRate = appStateRepository.sourceSampleRate.value.toInt()

                    rtlsdrSource.frequencyCorrection = appStateRepository.rtlsdrFrequencyCorrection.value
                    rtlsdrSource.frequencyOffset = appStateRepository.rtlsdrConverterOffset.value.toInt()
                    rtlsdrSource.isAutomaticGainControl = appStateRepository.rtlsdrAgcEnabled.value
                    rtlsdrSource.setDirectSamplingMode(appStateRepository.rtlsdrDirectSamplingMode.value.intValue)
                    rtlsdrSource.biasTeeEnabled = appStateRepository.rtlsdrEnableBiasT.value

                    if (appStateRepository.rtlsdrManualGainEnabled.value) {
                        rtlsdrSource.isManualGain = true
                        // not accepted while the analyzer starts: set again at the end of startScheduler()
                        rtlsdrSource.gain = appStateRepository.rtlsdrGainSteps.value[appStateRepository.rtlsdrGainIndex.value]
                        rtlsdrSource.ifGain = appStateRepository.rtlsdrIFGainSteps.value[appStateRepository.rtlsdrIFGainIndex.value]
                    } else {
                        rtlsdrSource.isManualGain = false
                    }
                    source = rtlsdrSource
                }
            }
            SourceType.AIRSPY -> {
                val airspySource = AirspySource()
                airspySource.frequency = appStateRepository.sourceFrequency.value
                airspySource.sampleRate = appStateRepository.sourceSampleRate.value.toInt()
                airspySource.lnaGain = appStateRepository.airspyLnaGain.value
                airspySource.vgaGain = appStateRepository.airspyVgaGain.value
                airspySource.mixerGain = appStateRepository.airspyMixerGain.value
                airspySource.linearityGain = appStateRepository.airspyLinearityGain.value
                airspySource.sensitivityGain = appStateRepository.airspySensitivityGain.value
                airspySource.gainPreset = appStateRepository.airspyGainPreset.value
                airspySource.advancedGainEnabled = appStateRepository.airspyAdvancedGainEnabled.value
                airspySource.rfBias = appStateRepository.airspyRfBiasEnabled.value
                airspySource.frequencyOffset = appStateRepository.airspyConverterOffset.value.toInt()
                source = airspySource
            }
            SourceType.AIRSPYHF -> {
                val airspyHfSource = AirspyHfSource()
                airspyHfSource.frequency = appStateRepository.sourceFrequency.value
                airspyHfSource.sampleRate = appStateRepository.sourceSampleRate.value.toInt()
                airspyHfSource.agcEnabled = appStateRepository.airspyHfAgcEnabled.value
                airspyHfSource.agcThreshold = appStateRepository.airspyHfAgcThreshold.value
                airspyHfSource.attenuation = appStateRepository.airspyHfAttenuation.value
                airspyHfSource.lnaEnabled = appStateRepository.airspyHfLnaEnabled.value
                airspyHfSource.frequencyOffset = appStateRepository.airspyHfConverterOffset.value.toInt()
                source = airspyHfSource
            }
            SourceType.HYDRASDR -> {
                val hydraSdrSource = HydraSdrSource()
                hydraSdrSource.frequency = appStateRepository.sourceFrequency.value
                hydraSdrSource.sampleRate = appStateRepository.sourceSampleRate.value.toInt()
                hydraSdrSource.setGain(HydraSdrGainType.LNA, appStateRepository.hydraSdrLnaGain.value)
                hydraSdrSource.setGain(HydraSdrGainType.VGA, appStateRepository.hydraSdrVgaGain.value)
                hydraSdrSource.setGain(HydraSdrGainType.MIXER, appStateRepository.hydraSdrMixerGain.value)
                hydraSdrSource.setGain(HydraSdrGainType.LINEARITY, appStateRepository.hydraSdrLinearityGain.value)
                hydraSdrSource.setGain(HydraSdrGainType.SENSITIVITY, appStateRepository.hydraSdrSensitivityGain.value)
                hydraSdrSource.presetGainType = appStateRepository.hydraSdrGainPreset.value
                hydraSdrSource.advancedGainEnabled = appStateRepository.hydraSdrAdvancedGainEnabled.value
                hydraSdrSource.rfBias = appStateRepository.hydraSdrRfBiasEnabled.value
                hydraSdrSource.rfPortIndex = appStateRepository.hydraSdrRfPortIndex.value
                hydraSdrSource.frequencyOffset = appStateRepository.hydraSdrConverterOffset.value.toInt()
                source = hydraSdrSource
            }
            SourceType.KIWISDR -> {
                val kiwiSdrSource = KiwiSdrSource(
                    appStateRepository.kiwiSdrHost.value,
                    appStateRepository.kiwiSdrPort.value,
                    appStateRepository.kiwiSdrPassword.value,
                    useTls = appStateRepository.kiwiSdrUseTls.value
                )
                kiwiSdrSource.frequency = appStateRepository.sourceFrequency.value
                kiwiSdrSource.sampleRate = appStateRepository.sourceSampleRate.value.toInt()
                source = kiwiSdrSource
            }
        }
        return true
    }

    /**
     * Will open the IQ Source instance (the file source is initialized with its file first).
     *
     * @return true on success; false on error
     */
    private fun openSource(): Boolean {
        when (appStateRepository.sourceType.value) {
            SourceType.FILESOURCE -> if (source != null && source is FileIQSource) {
                (source as FileIQSource).init(
                    appStateRepository.filesourceUri.value.toUri(),
                    this.contentResolver,
                    appStateRepository.sourceSampleRate.value.toInt(),
                    appStateRepository.sourceFrequency.value,
                    appStateRepository.filesourceRepeatEnabled.value,
                    appStateRepository.filesourceFileFormat.value,
                    appStateRepository.filesourceTimestamp.value
                )
                return source?.open(this, iqSourceActions) == true
            } else {
                Log.e(TAG,"openSource: sourceType is FILE_SOURCE, but source is null or of other type.")
                return false
            }

            SourceType.HACKRF -> if (source != null && source is HackrfSource)
                return source!!.open( this, iqSourceActions)
            else {
                Log.e(TAG, "openSource: sourceType is HACKRF_SOURCE, but source is null or of other type.")
                return false
            }

            SourceType.RTLSDR -> if (source != null && (source is RtlsdrSource || source is RtltcpSource))
                return source?.open(this, iqSourceActions) == true
            else {
                Log.e(TAG, "openSource: sourceType is RTLSDR_SOURCE, but source is null or of other type.")
                return false
            }

            SourceType.AIRSPY -> if (source != null && source is AirspySource)
                return source?.open(this, iqSourceActions) == true
            else {
                Log.e(TAG, "openSource: sourceType is AIRSPY, but source is null or of other type.")
                return false
            }

            SourceType.AIRSPYHF -> if (source != null && source is AirspyHfSource)
                return source?.open(this, iqSourceActions) == true
            else {
                Log.e(TAG, "openSource: sourceType is AIRSPYHF, but source is null or of other type.")
                return false
            }

            SourceType.HYDRASDR -> if (source != null && source is HydraSdrSource)
                return source?.open(this, iqSourceActions) == true
            else {
                Log.e(TAG, "openSource: sourceType is HYDRASDR, but source is null or of other type.")
                return false
            }

            SourceType.KIWISDR -> if (source != null && source is KiwiSdrSource)
                return source?.open(this, iqSourceActions) == true
            else {
                Log.e(TAG, "openSource: sourceType is KIWISDR, but source is null or of other type.")
                return false
            }
        }
    }

    fun startRecording() {
        if (appStateRepository.anyRecordingRunning.value) {
            Log.w(TAG, "startRecording: A recording is already running. do nothing..")
            return
        }
        val recordingStartedTimestamp = ClockCorrection.now()
        val filename = UnfinishedRecordings.RAW_FILE_NAME
        val filepath = "$RECORDINGS_DIRECTORY/$filename"
        Log.i(TAG, "startRecording: Opening file $filepath")
        val file = File(this.filesDir, filepath)
        val bufferedOutputStream = BufferedOutputStream(FileOutputStream(file))
        var maxRecordingTimeMilliseconds: Long? = null
        var maxRecordingFileSizeBytes: Long? = null
        when(appStateRepository.recordingstopAfterUnit.value) {
            StopAfterUnit.NEVER -> Unit
            StopAfterUnit.MB -> maxRecordingFileSizeBytes = appStateRepository.recordingStopAfterThreshold.value*(1024L*1024L)
            StopAfterUnit.GB -> maxRecordingFileSizeBytes = appStateRepository.recordingStopAfterThreshold.value*(1024L*1024L*1024L)
            StopAfterUnit.SEC -> maxRecordingTimeMilliseconds = appStateRepository.recordingStopAfterThreshold.value*1000L
            StopAfterUnit.MIN -> maxRecordingTimeMilliseconds = appStateRepository.recordingStopAfterThreshold.value*1000L*60L
        }
        scheduler?.startRecording(
            bufferedOutputStream = bufferedOutputStream,
            onlyWhenSquelchIsSatisfied = appStateRepository.recordOnlyWhenSquelchIsSatisfied.value,
            maxRecordingTime = maxRecordingTimeMilliseconds,
            maxRecordingFileSize = maxRecordingFileSizeBytes,
            onRecordingStopped = { finalSize -> storeFinishedRecording(finalSize, file, isBaseband = false) },
            onFileSizeUpdate = appStateRepository.recordingCurrentFileSize::set
        )
        // update ui
        appStateRepository.recordingStartedTimestamp.set(recordingStartedTimestamp)
        appStateRepository.recordingStartedElapsedRealtime.set(SystemClock.elapsedRealtime())
        appStateRepository.recordingRunning.set(true)
        UnfinishedRecordings.writeDetails(file, appStateRepository.recordingDetails(isBaseband = false))
    }

    fun stopRecording() {
        scheduler?.stopRecording()
    }

    fun startBasebandRecording() {
        if (appStateRepository.anyRecordingRunning.value) {
            Log.w(TAG, "startBasebandRecording: A recording is already running. do nothing..")
            return
        }
        val recordingStartedTimestamp = ClockCorrection.now()
        val filename = UnfinishedRecordings.BASEBAND_FILE_NAME
        val filepath = "$RECORDINGS_DIRECTORY/$filename"
        Log.i(TAG, "startBasebandRecording: Opening file $filepath")
        val file = File(this.filesDir, filepath)
        val bufferedOutputStream = BufferedOutputStream(FileOutputStream(file))
        var maxRecordingTimeMilliseconds: Long? = null
        var maxRecordingFileSizeBytes: Long? = null
        when(appStateRepository.recordingstopAfterUnit.value) {
            StopAfterUnit.NEVER -> Unit
            StopAfterUnit.MB -> maxRecordingFileSizeBytes = appStateRepository.recordingStopAfterThreshold.value*(1024L*1024L)
            StopAfterUnit.GB -> maxRecordingFileSizeBytes = appStateRepository.recordingStopAfterThreshold.value*(1024L*1024L*1024L)
            StopAfterUnit.SEC -> maxRecordingTimeMilliseconds = appStateRepository.recordingStopAfterThreshold.value*1000L
            StopAfterUnit.MIN -> maxRecordingTimeMilliseconds = appStateRepository.recordingStopAfterThreshold.value*1000L*60L
        }
        // set before the demodulator replaces it with the time of the first recorded sample
        appStateRepository.recordingStartedTimestamp.set(recordingStartedTimestamp)
        appStateRepository.recordingStartedElapsedRealtime.set(SystemClock.elapsedRealtime())
        demodulator?.startBasebandRecording(
            bufferedOutputStream = bufferedOutputStream,
            maxRecordingTime = maxRecordingTimeMilliseconds,
            maxRecordingFileSize = maxRecordingFileSizeBytes,
            onRecordingStopped = { finalSize -> storeFinishedRecording(finalSize, file, isBaseband = true) },
            onFileSizeUpdate = appStateRepository.recordingCurrentFileSize::set,
            onFirstSampleRecorded = appStateRepository.recordingStartedTimestamp::set
        )
        // update ui
        appStateRepository.basebandRecordingRunning.set(true)
        UnfinishedRecordings.writeDetails(file, appStateRepository.recordingDetails(isBaseband = true))
    }

    /**
     * Stores a finished recording once, here: renames its file from the "ongoing" name and inserts
     * its row, then tells the UI (snackbar). In the service rather than a UI collector, so it is
     * stored also with the app in the background (a size or time limit, Stop in the notification,
     * the app swiped away), and only once with several activity instances alive. Called on the
     * recording thread: the app state is read now, as it was when the recording ended.
     */
    private fun storeFinishedRecording(finalSize: Long, file: File, isBaseband: Boolean) {
        val recording = appStateRepository.recordingDetails(isBaseband).toRecording(file, finalSize)
        val target = File(file.parentFile, recording.calculateFileName(isBaseband = isBaseband))
        recordingStoreScope.launch {
            val renamed = renameRecordingFile(file, target)
            val stored = recordingDao.get(recordingDao.insert(recording.copy(filePath = (if (renamed) target else file).absolutePath)))
            UnfinishedRecordings.deleteDetails(file)
            appStateRepository.recordingStartedTimestamp.set(0L)
            if (isBaseband) appStateRepository.basebandRecordingRunning.set(false) else appStateRepository.recordingRunning.set(false)
            appStateRepository.emitAnalyzerEvent(AppStateRepository.AnalyzerEvent.RecordingFinished(stored))
            // The app was swiped away and only this recording kept the analyzer running.
            if (taskRemoved && !isBound) withContext(Dispatchers.Main) {
                cancelRecordingNotice()
                if (appStateRepository.analyzerRunning.value && !appStateRepository.anyRecordingRunning.value) {
                    stopAnalyzer()
                    stopSelf()
                }
            }
        }
    }

    private fun renameRecordingFile(file: File, target: File): Boolean {
        if (!file.exists()) {
            Log.e(TAG, "renameRecordingFile: ${file.absolutePath} does not exist")
            return false
        }
        if (target.exists() && !target.delete()) Log.w(TAG, "renameRecordingFile: could not replace ${target.absolutePath}")
        return file.renameTo(target).also { if (!it) Log.e(TAG, "renameRecordingFile: renaming to ${target.name} failed - keeping ${file.name}") }
    }

    fun stopBasebandRecording() {
        demodulator?.stopBasebandRecording()
    }

    private val deviceMaxFftSize by lazy { AutoFftSettings.deviceMaxFftSize(this) }

    /**
     * When Manual FFT Size is disabled, (re-)computes the FFT size and padding ratio for the
     * current source sample rate and waterfall speed and stores them into the fftSize/fftPaddingRatio settings, so
     * the collectAppState sync (below) propagates them into fftProcessor as usual, and
     * so the sliders (if the user later enables Manual FFT Size) show what was auto-selected.
     */
    private fun recomputeAutoFftSettingsIfNeeded() {
        if (!appStateRepository.manualFftSizeEnabled.value) {
            val (fftSize, paddingRatio) = AutoFftSettings.compute(
                appStateRepository.sourceSampleRate.value,
                appStateRepository.waterfallFps.value.toFloat(),
                deviceMaxFftSize,
                appStateRepository.enableLowPerformanceMode.value
            )
            appStateRepository.fftSize.set(fftSize)
            appStateRepository.fftPaddingRatio.set(paddingRatio)
        }
    }

    private fun handleAppStateChanges() {
        val s = serviceScope
        val asr = appStateRepository

        // source tab
        s.collectAppState(asr.sourceFrequency) {
            source?.frequency = it
            fftProcessor?.frequency = it
        }
        s.collectAppState(asr.sourceSampleRate) {
            if (source != null) OverloadMonitor.restartGrace() // the source and the chain behind it restart
            source?.sampleRate = it.toInt()
            // A device that rejected the rate stays at its old one (UsbSdrSource.setSampleRate):
            // show and process that one, not the rate asked for.
            source?.sampleRate?.takeIf { actual -> actual != it.toInt() && actual > 0 }?.let { actual ->
                asr.sourceSampleRate.set(actual.toLong())
                return@collectAppState
            }
            fftProcessor?.sampleRate = it
            recomputeAutoFftSettingsIfNeeded()
        }
        s.collectAppState(asr.hackrfVgaGainIndex) { (source as? HackrfSource)?.vgaGain = asr.hackrfVgaGainSteps[it] }
        s.collectAppState(asr.hackrfLnaGainIndex) { (source as? HackrfSource)?.lnaGain = asr.hackrfLnaGainSteps[it] }
        s.collectAppState(asr.hackrfAmplifierEnabled) { (source as? HackrfSource)?.ampEnabled = it }
        s.collectAppState(asr.hackrfAntennaPowerEnabled) { (source as? HackrfSource)?.antennaPowerEnabled = it }
        s.collectAppState(asr.hackrfConverterOffset) { (source as? HackrfSource)?.frequencyOffset = it.toInt() }
        s.collectAppState(asr.rtlsdrGainIndex) {
            (source as? RtlsdrSource)?.gain = asr.rtlsdrGainSteps.value[it]
            (source as? RtltcpSource)?.gain = asr.rtlsdrGainSteps.value[it]
        }
        s.collectAppState(asr.rtlsdrIFGainIndex) {
            (source as? RtlsdrSource)?.ifGain = asr.rtlsdrIFGainSteps.value[it]
            (source as? RtltcpSource)?.ifGain = asr.rtlsdrIFGainSteps.value[it]
        }
        s.collectAppState(asr.rtlsdrAgcEnabled) {
            (source as? RtlsdrSource)?.isAutomaticGainControl = it
            (source as? RtltcpSource)?.isAutomaticGainControl = it
        }
        s.collectAppState(asr.rtlsdrManualGainEnabled) {
            (source as? RtlsdrSource)?.isManualGain = it
            (source as? RtltcpSource)?.isManualGain = it
            if (it) {
                (source as? RtlsdrSource)?.apply {
                    gain = asr.rtlsdrGainSteps.value[asr.rtlsdrGainIndex.value]
                    ifGain = asr.rtlsdrIFGainSteps.value[asr.rtlsdrIFGainIndex.value]
                }
                (source as? RtltcpSource)?.apply {
                    gain = asr.rtlsdrGainSteps.value[asr.rtlsdrGainIndex.value]
                    ifGain = asr.rtlsdrIFGainSteps.value[asr.rtlsdrIFGainIndex.value]
                }
            }
        }
        s.collectAppState(asr.rtlsdrConverterOffset) {
            (source as? RtlsdrSource)?.frequencyOffset = it.toInt()
            (source as? RtltcpSource)?.frequencyOffset = it.toInt()
        }
        s.collectAppState(asr.rtlsdrFrequencyCorrection) {
            (source as? RtlsdrSource)?.frequencyCorrection = it
            (source as? RtltcpSource)?.frequencyCorrection = it
        }
        s.collectAppState(asr.rtlsdrDirectSamplingMode) {
            (source as? RtlsdrSource)?.setDirectSamplingMode(it.intValue)
            (source as? RtltcpSource)?.setDirectSamplingMode(it.intValue)
        }
        s.collectAppState(asr.rtlsdrEnableBiasT) { (source as? RtlsdrSource)?.biasTeeEnabled = it }
        s.collectAppState(asr.airspyAdvancedGainEnabled) { (source as? AirspySource)?.advancedGainEnabled = it }
        s.collectAppState(asr.airspyGainPreset) { (source as? AirspySource)?.gainPreset = it }
        s.collectAppState(asr.airspyVgaGain) { (source as? AirspySource)?.vgaGain = it }
        s.collectAppState(asr.airspyLnaGain) { (source as? AirspySource)?.lnaGain = it }
        s.collectAppState(asr.airspyMixerGain) { (source as? AirspySource)?.mixerGain = it }
        s.collectAppState(asr.airspyLinearityGain) { (source as? AirspySource)?.linearityGain = it }
        s.collectAppState(asr.airspySensitivityGain) { (source as? AirspySource)?.sensitivityGain = it }
        s.collectAppState(asr.airspyRfBiasEnabled) { (source as? AirspySource)?.rfBias = it }
        s.collectAppState(asr.airspyConverterOffset) { (source as? AirspySource)?.frequencyOffset = it.toInt() }
        s.collectAppState(asr.airspyHfAgcEnabled) { (source as? AirspyHfSource)?.agcEnabled = it }
        s.collectAppState(asr.airspyHfAgcThreshold) { (source as? AirspyHfSource)?.agcThreshold = it }
        s.collectAppState(asr.airspyHfAttenuation) { (source as? AirspyHfSource)?.attenuation = it }
        s.collectAppState(asr.airspyHfLnaEnabled) { (source as? AirspyHfSource)?.lnaEnabled = it }
        s.collectAppState(asr.airspyHfConverterOffset) { (source as? AirspyHfSource)?.frequencyOffset = it.toInt() }
        s.collectAppState(asr.hydraSdrAdvancedGainEnabled) { (source as? HydraSdrSource)?.advancedGainEnabled = it }
        s.collectAppState(asr.hydraSdrGainPreset) { (source as? HydraSdrSource)?.presetGainType = it }
        s.collectAppState(asr.hydraSdrVgaGain) { (source as? HydraSdrSource)?.setGain(HydraSdrGainType.VGA, it) }
        s.collectAppState(asr.hydraSdrLnaGain) { (source as? HydraSdrSource)?.setGain(HydraSdrGainType.LNA, it) }
        s.collectAppState(asr.hydraSdrMixerGain) { (source as? HydraSdrSource)?.setGain(HydraSdrGainType.MIXER, it) }
        s.collectAppState(asr.hydraSdrLinearityGain) { (source as? HydraSdrSource)?.setGain(HydraSdrGainType.LINEARITY, it) }
        s.collectAppState(asr.hydraSdrSensitivityGain) { (source as? HydraSdrSource)?.setGain(HydraSdrGainType.SENSITIVITY, it) }
        s.collectAppState(asr.hydraSdrRfBiasEnabled) { (source as? HydraSdrSource)?.rfBias = it }
        s.collectAppState(asr.hydraSdrRfPortIndex) { (source as? HydraSdrSource)?.rfPortIndex = it }
        s.collectAppState(asr.hydraSdrConverterOffset) { (source as? HydraSdrSource)?.frequencyOffset = it.toInt() }
        s.collectAppState(asr.filesourceFileFormat) { (source as? FileIQSource)?.fileFormat = it }
        s.collectAppState(asr.filesourceRepeatEnabled) { (source as? FileIQSource)?.isRepeat = it }

        // view tab
        s.collectAppState(asr.fftSize) { fftProcessor?.fftSize = it }
        s.collectAppState(asr.fftPaddingRatio) { fftProcessor?.fftPaddingRatio = it }
        s.collectAppState(asr.waterfallFps) {
            applyFftFramesPerSecond()
            recomputeAutoFftSettingsIfNeeded() // the automatic FFT size follows the waterfall speed
        }
        s.collectAppState(asr.fftPeakHold) { fftProcessor?.fftPeakHold = it }
        s.collectAppState(asr.manualFftSizeEnabled) { recomputeAutoFftSettingsIfNeeded() }

        // demodulation tab
        s.collectAppState(asr.demodulationMode) { applyDemodulationSettings() }
        s.collectAppState(asr.demodulationWfmStereoAndRdsEnabled) { applyDemodulationSettings() }
        // RFA Digital installed, updated or removed (the activity rechecks when it comes back).
        s.launch { digitalExtensionStatus.availability.collect { applyDemodulationSettings() } }
        s.launch { digitalExtensionStatus.status.collect { refreshNotification() } }
        // Stepped settings (not dragged): resending the whole DigitalDemodulationConfiguration on
        // every change is cheap - unlike channelWidth, a continuous slider (debounced below).
        s.collectAppState(asr.effectivePocsagBaudRate) { applyDemodulationSettings() }
        s.collectAppState(asr.ax25Variant) { applyDemodulationSettings() }
        s.collectAppState(asr.effectiveRttyBaudRate) { applyDemodulationSettings() }
        s.collectAppState(asr.effectiveRttyFrequencyShift) { applyDemodulationSettings() }
        s.collectAppState(asr.effectiveRttyPolarity) { applyDemodulationSettings() }
        s.collectAppState(asr.effectivePsk31BaudRate) { applyDemodulationSettings() }
        s.collectAppState(asr.effectiveR09BaudRate) { applyDemodulationSettings() }
        s.collectAppState(asr.effectiveLoraSpreadingFactors) { applyDemodulationSettings() }
        s.collectAppState(asr.effectiveLoraIqMode) { applyDemodulationSettings() }
        s.collectAppState(asr.ft8TimingMode) { applyDemodulationSettings() }
        s.collectAppState(asr.ft8TimeOffsetMs) { applyDemodulationSettings() }
        // MeshCore hashtag channels are decrypted app-side (MeshCoreChannelRegistry), not by the
        // extension. Every emission after the first also re-parses the stored group packets, so an
        // added channel reveals its past messages. (The first emission may be the setting's default
        // rather than the stored list; the extra pass that causes is a no-op.)
        var meshCoreChannelsInitialized = false
        s.collectAppState(asr.meshCoreCustomChannels) { names ->
            MeshCoreChannelRegistry.updateCustomChannels(names) // must happen before the reparse reads it
            if (meshCoreChannelsInitialized) digitalExtensionRepository.reparseStoredLoraPackets()
            else meshCoreChannelsInitialized = true
        }
        s.collectAppState(asr.channelFrequency) {
            scheduler?.channelFrequency = if(asr.demodulationMode.value == DemodulationMode.CW) it - CwDemodulator.CW_OFFSET_FREQUENCY else it
            fftProcessor?.channelFrequencyRange = squelchChannelRange()
        }
        s.collectAppState(asr.channelWidth) {
            demodulator?.channelWidth = it
            fftProcessor?.channelFrequencyRange = squelchChannelRange()
        }
        // LoRa's bandwidth and PSK31's capture width are this continuously dragged slider: sent to
        // the extension debounced, not on every drag step.
        s.launch {
            asr.channelWidth.stateFlow
                .debounce(500)
                .collect {
                    if (appStateRepository.demodulationMode.value in listOf(DemodulationMode.LORA, DemodulationMode.PSK31)) {
                        applyDemodulationSettings()
                    }
                }
        }
        s.collectAppState(asr.effectiveSquelchEnabled) {
            applySquelchSettings()
            applyFftFramesPerSecond()
        }
        s.collectAppState(asr.effectiveSquelchMargin) { applySquelchSettings() }
        s.collectAppState(asr.effectiveAudioVolumeLevel) { demodulator?.audioVolumeLevel = it }

        // settings tab
        s.collectAppState(asr.loggingEnabled) {
            if (it)
                LogcatLogger.startLogging(this@AnalyzerService)
            else if (LogcatLogger.isLogging)
                LogcatLogger.stopLogging()
        }
        s.collectAppState(asr.enableLowPerformanceMode) {
            if (it)
                demodulator?.filterQuality = appStateRepository.lowPerformanceModeFilterQuality.value
            else
                demodulator?.filterQuality = 1f
            recomputeAutoFftSettingsIfNeeded()
        }
        s.collectAppState(asr.lowPerformanceModeFilterQuality) {
            if (appStateRepository.enableLowPerformanceMode.value)
                demodulator?.filterQuality = appStateRepository.lowPerformanceModeFilterQuality.value
        }
        s.collectAppState(asr.startAnalyzerOnUsbAttach) { updateUsbAttachHandling() }
    }
}
