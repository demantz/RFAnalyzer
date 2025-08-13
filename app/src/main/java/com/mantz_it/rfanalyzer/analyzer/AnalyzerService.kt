package com.mantz_it.rfanalyzer.analyzer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mantz_it.rfanalyzer.source.FileIQSource
import com.mantz_it.rfanalyzer.source.HackrfSource
import com.mantz_it.rfanalyzer.source.IQSourceInterface
import com.mantz_it.rfanalyzer.LogcatLogger
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.source.RtlsdrSource
import com.mantz_it.rfanalyzer.ui.RECORDINGS_DIRECTORY
import com.mantz_it.rfanalyzer.ui.composable.DemodulationMode
import com.mantz_it.rfanalyzer.ui.composable.SourceType
import com.mantz_it.rfanalyzer.ui.composable.StopAfterUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import androidx.core.net.toUri
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer.database.collectAppState
import com.mantz_it.rfanalyzer.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * <h1>RF Analyzer - Analyzer Service</h1>
 *
 * Module:      AnalyzerService.kt
 * Description: Foreground Service which orchestrates the signal processing pipeline.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

@AndroidEntryPoint
class AnalyzerService : Service() {
    @Inject lateinit var appStateRepository: AppStateRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job()) // Coroutine scope for observing the App State
    private val binder = LocalBinder()
    private var isBound = false

    var source: IQSourceInterface? = null
        private set
    var scheduler: Scheduler? = null
        private set
    var demodulator: Demodulator? = null
        private set
    var fftProcessor: FftProcessor? = null
        private set

    inner class LocalBinder : Binder() {
        fun getService(): AnalyzerService = this@AnalyzerService
    }

    companion object {
        private const val TAG = "AnalyzerService"
        const val ACTION_STOP = "com.mantz_it.rfanalyzer.analyzer.ACTION_STOP"
    }

    private val iqSourceActions: IQSourceInterface.Callback = object : IQSourceInterface.Callback {
        override fun onIQSourceReady(source: IQSourceInterface?) {
            serviceScope.launch {
                startScheduler()
            }
        }

        override fun onIQSourceError(source: IQSourceInterface?, message: String?) {
            val errorMessage = "Error with Source (${source?.getName()}): $message"
            Log.e(TAG, "onIQSourceError: $errorMessage")
            serviceScope.launch {
                appStateRepository.emitAnalyzerEvent(AppStateRepository.AnalyzerEvent.SourceFailure("Source: $message"))
                stopAnalyzer()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind: Service is bound.")
        isBound = true
        return binder
    }

    override fun onRebind(intent: Intent?) {
        Log.d(TAG, "onRebind: Service is bound.")
        isBound = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isBound = false
        if(appStateRepository.analyzerRunning.value) {
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

        // Start Logging to file:
        if(appStateRepository.loggingEnabled.value)
            LogcatLogger.startLogging(this)

        handleAppStateChanges()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        when (intent?.action) {
            ACTION_STOP -> {
                // If the stop action is received from the notification's stop button
                Log.d(TAG, "onStartCommand: Stop action received from Notification. Stopping service.")
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
        serviceScope.cancel() // Clean up the coroutine scope
        stopAnalyzer() // make sure all threads are stopped
        Log.d(TAG, "onDestroy: Service destroyed.")
        LogcatLogger.stopLogging()
    }

    private fun stopForegroundService() {
        Log.d(TAG, "stopForegroundService: removing notification.")
        stopForeground(STOP_FOREGROUND_REMOVE) // This removes the notification
        stopSelf() // remove the 'started' state so the service stops when unbound
    }

    private fun startForegroundService() {
        val notification = createNotification()
        Log.d(TAG, "startForegroundService: Moving service to foreground.")
        ServiceCompat.startForeground(this, 1, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        // **Explicitly start the service so it doesn't stop when unbound**
        val serviceIntent = Intent(this, this::class.java)
        startService(serviceIntent)
    }

    private fun createNotification(): Notification {
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
            .setContentText("RF Analyzer is running...")
            .setSmallIcon(R.drawable.signal_wave)
            .setContentIntent(activityPendingIntent)
            .addAction(R.drawable.stop_circle, "Stop", pendingStopIntent)
            .build()
    }

    /**
     * Will stop the RF Analyzer. This includes shutting down the scheduler (which turns of the
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

        // Wait for the demodulator to stop
        //try {
        //    demodulator?.join()
        //} catch (e: InterruptedException) {
        //    Log.e(TAG, "stopAnalyzer: Error while stopping Demodulator.")
        //}
        source?.close()
        source = null
        appStateRepository.sourceName.set("")
        appStateRepository.analyzerRunning.set(false)
        appStateRepository.analyzerStartPending.set(false)
        GlobalPerformanceData.reset()

        stopForegroundService()
    }

    /**
     * Will start the RF Analyzer. This includes creating a source (if null), open a source
     * (if not open), starting the scheduler (which starts the source) and starting the
     * processing loop.
     */
    fun startAnalyzer() : Boolean {
        Log.d(TAG, "startAnalyzer")
        if (source == null) {
            if (!this.createSource()) return false
        }

        // check if the source is open. if not, open it!
        if (!source!!.isOpen()) {
            if (!openSource()) {
                Toast.makeText(this, "Source not available (${source?.getName()})", Toast.LENGTH_LONG).show()
                source = null
                appStateRepository.analyzerStartPending.set(false)
                return false
            }
            return true // we have to wait for the source to become ready... onIQSourceReady() will call startScheduler()...
        } else {
            return startScheduler()
        }
    }

    private fun startScheduler(): Boolean {
        if(source == null) return false

        // Source was successfully opened, let the UI know:
        appStateRepository.sourceName.set(source!!.name)
        appStateRepository.sourceMinimumFrequency.set(source!!.minFrequency)
        appStateRepository.sourceMaximumFrequency.set(source!!.maxFrequency)
        appStateRepository.sourceMinimumSampleRate.set(source!!.minSampleRate.toLong())
        appStateRepository.sourceMaximumSampleRate.set(source!!.maxSampleRate.toLong())
        appStateRepository.sourceOptimalSampleRates.set(source!!.supportedSampleRates.map { it.toLong() })
        appStateRepository.sourceFrequency.set(source!!.frequency)
        appStateRepository.sourceSampleRate.set(source!!.sampleRate.toLong())
        if (source is RtlsdrSource) {
            val currentGainIndex = appStateRepository.rtlsdrGainIndex.value
            val currentIFGainIndex = appStateRepository.rtlsdrIFGainIndex.value
            val gainIndexList = (source as RtlsdrSource).possibleGainValues.toList()
            val ifGainIndexList = (source as RtlsdrSource).possibleIFGainValues.toList()
            appStateRepository.rtlsdrGainIndex.set(0)
            appStateRepository.rtlsdrIFGainIndex.set(0)
            appStateRepository.rtlsdrGainSteps.set(gainIndexList)
            appStateRepository.rtlsdrIFGainSteps.set(ifGainIndexList)
            appStateRepository.rtlsdrGainIndex.set(currentGainIndex.coerceAtMost(gainIndexList.size - 1))
            appStateRepository.rtlsdrIFGainIndex.set(currentIFGainIndex.coerceAtMost(ifGainIndexList.size - 1))
        }

        // Create a new instance of Scheduler
        scheduler = Scheduler(appStateRepository.fftSize.value, source!!)

        // Start the demodulator thread:
        demodulator = Demodulator(
            scheduler!!.demodOutputQueue,
            scheduler!!.demodInputQueue,
            source!!.packetSize / source!!.bytesPerSample
        )
        demodulator!!.audioVolumeLevel = appStateRepository.effectiveAudioVolumeLevel.value
        demodulator!!.start()

        applyNewDemodulationMode(appStateRepository.demodulationMode.value)

        // Start the scheduler
        scheduler!!.start()

        fftProcessor = FftProcessor(
            initialFftSize = appStateRepository.fftSize.value,
            inputQueue = scheduler!!.fftOutputQueue,  // Reference to the input queue for the processing loop
            returnQueue = scheduler!!.fftInputQueue,  // Reference to the buffer-pool-return queue
            fftProcessorData = appStateRepository.fftProcessorData,
            appStateRepository.waterfallSpeed.value,
            fftPeakHold = appStateRepository.fftPeakHold.value,
            getChannelFrequencyRange = {
                val schedulerHandle = scheduler
                val demodulatorHandle = demodulator
                if(schedulerHandle != null && demodulatorHandle != null)
                    Pair(
                        schedulerHandle.channelFrequency - demodulatorHandle.channelWidth,
                        schedulerHandle.channelFrequency + demodulatorHandle.channelWidth)
                else
                    null
            },
            onAverageSignalStrengthChanged = appStateRepository.averageSignalStrength::set
        )
        fftProcessor!!.start()

        // Set state to running and hand over fft processor queues to UI
        appStateRepository.analyzerRunning.set(true)
        appStateRepository.analyzerStartPending.set(false)
        startForegroundService()
        launchSupervisionCoroutine()

        // workaround for bug: when manual gain is enabled and the rtlsdr is started, the manual gain value is not accepted. so set it again here
        if (source is RtlsdrSource && appStateRepository.rtlsdrManualGainEnabled.value) {
            (source as RtlsdrSource).gain = appStateRepository.rtlsdrGainSteps.value[appStateRepository.rtlsdrGainIndex.value]
            (source as RtlsdrSource).ifGain = appStateRepository.rtlsdrIFGainSteps.value[appStateRepository.rtlsdrIFGainIndex.value]
        }

        return true
    }

    private fun launchSupervisionCoroutine() {
        // Launch a coroutine to supervise the activity connection and measure running time
        serviceScope.launch {
            var secondsSinceActivityStopped = 0
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

                appStateRepository.appUsageTimeInSeconds.set(appStateRepository.appUsageTimeInSeconds.value + 1)  // increase app usage timer
            }
        }
    }

    private fun applyNewDemodulationMode(newDemodulationMode: DemodulationMode): Boolean {
        if(demodulator == null || source == null || scheduler == null)
            return false

        // (de-)activate demodulation in the scheduler and set the sample rate accordingly:
        if (newDemodulationMode == DemodulationMode.OFF) {
            scheduler!!.isDemodulationActivated = false
            demodulator!!.demodulationMode = DemodulationMode.OFF
            return true
        }

        if(source!!.sampleRate % Demodulator.MIN_INPUT_RATE != 0) {
            Log.i( TAG, "startScheduler: Source sample rate (${source!!.sampleRate} Sps) is not a multiple of Demodulator input rate (${Demodulator.MIN_INPUT_RATE})." )
            Toast.makeText( this, "Sample rate not supported Demodulator (needs multiple of ${Demodulator.MIN_INPUT_RATE} Sps)", Toast.LENGTH_LONG ).show()
            return false
        }
        demodulator!!.demodulationMode = newDemodulationMode
        scheduler!!.isDemodulationActivated = true
        demodulator!!.channelWidth = appStateRepository.channelWidth.value
        scheduler!!.squelchSatisfied = appStateRepository.squelchSatisfied.value
        scheduler!!.channelFrequency = if(newDemodulationMode == DemodulationMode.CW)
                Demodulator.CW_OFFSET_FREQUENCY - appStateRepository.channelFrequency.value
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
                hackrfSource.setFrequency(appStateRepository.sourceFrequency.value)
                hackrfSource.setSampleRate(appStateRepository.sourceSampleRate.value.toInt())
                hackrfSource.vgaRxGain = appStateRepository.hackrfVgaGainSteps[appStateRepository.hackrfVgaGainIndex.value]
                hackrfSource.lnaGain = appStateRepository.hackrfVgaGainSteps[appStateRepository.hackrfVgaGainIndex.value]
                hackrfSource.setAmplifier(appStateRepository.hackrfAmplifierEnabled.value)
                hackrfSource.setAntennaPower(appStateRepository.hackrfAntennaPowerEnabled.value)
                hackrfSource.frequencyOffset = appStateRepository.hackrfConverterOffset.value.toInt()
                source = hackrfSource
            }
            SourceType.RTLSDR -> {
                // Create RtlsdrSource
                val rtlsdrSource = if(appStateRepository.rtlsdrExternalServerEnabled.value) {
                    RtlsdrSource(
                        appStateRepository.rtlsdrExternalServerIP.value,
                        appStateRepository.rtlsdrExternalServerPort.value
                    )
                } else
                    RtlsdrSource(
                        "127.0.0.1",
                        1234
                    )
                rtlsdrSource.setAllowOutOfBoundFrequency(appStateRepository.rtlsdrAllowOutOfBoundFrequency.value)
                rtlsdrSource.setFrequency(appStateRepository.sourceFrequency.value)
                rtlsdrSource.setSampleRate(appStateRepository.sourceSampleRate.value.toInt())

                rtlsdrSource.frequencyCorrection = appStateRepository.rtlsdrFrequencyCorrection.value
                rtlsdrSource.frequencyOffset = appStateRepository.rtlsdrConverterOffset.value.toInt()
                rtlsdrSource.isAutomaticGainControl = appStateRepository.rtlsdrAgcEnabled.value

                if (appStateRepository.rtlsdrManualGainEnabled.value) {
                    rtlsdrSource.isManualGain = true
                    // note: there is a bug: when manual gain is enabled and the analyzer is started, the manual gain value set by the following statements is not accepted
                    // therefore the gain is later set again at the end of startScheduler.
                    rtlsdrSource.gain = appStateRepository.rtlsdrGainSteps.value[appStateRepository.rtlsdrGainIndex.value]
                    rtlsdrSource.ifGain = appStateRepository.rtlsdrIFGainSteps.value[appStateRepository.rtlsdrIFGainIndex.value]
                } else {
                    rtlsdrSource.isManualGain = false
                }
                source = rtlsdrSource
            }
        }
        return true
    }

    /**
     * Will open the IQ Source instance.
     * Note: some sources need special treatment on opening, like the rtl-sdr source.
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
                    1024*256,
                    appStateRepository.filesourceRepeatEnabled.value,
                    appStateRepository.filesourceFileFormat.value.ordinal
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

            SourceType.RTLSDR -> if (source != null && source is RtlsdrSource)
                return source?.open(this, iqSourceActions) == true
            else {
                Log.e(TAG, "openSource: sourceType is RTLSDR_SOURCE, but source is null or of other type.")
                return false
            }
        }
    }

    fun startRecording() {
        if (appStateRepository.recordingRunning.value) {
            Log.w(TAG, "startRecording: Recording is already running. do nothing..")
            return
        }
        val recordingStartedTimestamp = System.currentTimeMillis()
        val filename = "ongoing_recording.iq"
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
            onRecordingStopped = { finalSize -> appStateRepository.emitAnalyzerEvent(AppStateRepository.AnalyzerEvent.RecordingFinished(finalSize, file)) },
            onFileSizeUpdate = appStateRepository.recordingCurrentFileSize::set
        )
        // update ui
        appStateRepository.recordingStartedTimestamp.set(recordingStartedTimestamp)
        appStateRepository.recordingRunning.set(true)
    }

    fun stopRecording() {
        scheduler?.stopRecording()
    }

    private fun handleAppStateChanges() {
        val s = serviceScope
        val asr = appStateRepository

        // source tab
        s.collectAppState(asr.sourceFrequency) { source?.frequency = it }
        s.collectAppState(asr.sourceSampleRate) { source?.sampleRate = it.toInt() }
        s.collectAppState(asr.hackrfVgaGainIndex) { (source as? HackrfSource)?.vgaRxGain = asr.hackrfVgaGainSteps[it] }
        s.collectAppState(asr.hackrfLnaGainIndex) { (source as? HackrfSource)?.lnaGain = asr.hackrfLnaGainSteps[it] }
        s.collectAppState(asr.hackrfAmplifierEnabled) { (source as? HackrfSource)?.setAmplifier(it) }
        s.collectAppState(asr.hackrfAntennaPowerEnabled) { (source as? HackrfSource)?.setAntennaPower(it) }
        s.collectAppState(asr.hackrfConverterOffset) { (source as? HackrfSource)?.frequencyOffset = it.toInt() }
        s.collectAppState(asr.rtlsdrGainIndex) { (source as? RtlsdrSource)?.gain = asr.rtlsdrGainSteps.value[it] }
        s.collectAppState(asr.rtlsdrIFGainIndex) { (source as? RtlsdrSource)?.ifGain = asr.rtlsdrIFGainSteps.value[it] }
        s.collectAppState(asr.rtlsdrAgcEnabled) { (source as? RtlsdrSource)?.isAutomaticGainControl = it }
        s.collectAppState(asr.rtlsdrManualGainEnabled) {
            (source as? RtlsdrSource)?.isManualGain = it
            if (it) {
                (source as? RtlsdrSource)?.apply {
                    gain = asr.rtlsdrGainSteps.value[asr.rtlsdrGainIndex.value]
                    ifGain = asr.rtlsdrIFGainSteps.value[asr.rtlsdrIFGainIndex.value]
                }
            }
        }
        s.collectAppState(asr.rtlsdrConverterOffset) { (source as? RtlsdrSource)?.frequencyOffset = it.toInt() }
        s.collectAppState(asr.rtlsdrFrequencyCorrection) { (source as? RtlsdrSource)?.frequencyCorrection = it }
        s.collectAppState(asr.filesourceFileFormat) { (source as? FileIQSource)?.fileFormat = it.ordinal }
        s.collectAppState(asr.filesourceRepeatEnabled) { (source as? FileIQSource)?.isRepeat = it }

        // view tab
        s.collectAppState(asr.fftSize) { scheduler?.fftSize = it }
        s.collectAppState(asr.waterfallSpeed) { fftProcessor?.waterfallSpeed = it }
        s.collectAppState(asr.fftPeakHold) { fftProcessor?.fftPeakHold = it }

        // demodulation tab
        s.collectAppState(asr.demodulationMode) { applyNewDemodulationMode(it) }
        s.collectAppState(asr.channelFrequency) { scheduler?.channelFrequency = if(asr.demodulationMode.value == DemodulationMode.CW) it - Demodulator.CW_OFFSET_FREQUENCY else it }
        s.collectAppState(asr.channelWidth) { demodulator?.channelWidth = it }
        s.collectAppState(asr.squelchSatisfied) { scheduler?.squelchSatisfied = it }
        s.collectAppState(asr.effectiveAudioVolumeLevel) { demodulator?.audioVolumeLevel = it }

        // settings tab
        s.collectAppState(asr.loggingEnabled) {
            if (it)
                LogcatLogger.startLogging(this@AnalyzerService)
            else if (LogcatLogger.isLogging)
                LogcatLogger.stopLogging()
        }
        s.collectAppState(asr.rtlsdrAllowOutOfBoundFrequency) { (source as? RtlsdrSource)?.isAllowOutOfBoundFrequency = it }
    }
}
