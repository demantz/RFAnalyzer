package com.mantz_it.rfanalyzer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mantz_it.rfanalyzer.analyzer.AnalyzerService
import com.mantz_it.rfanalyzer.analyzer.FftProcessor
import com.mantz_it.rfanalyzer.analyzer.OverloadMonitor
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import java.lang.ref.WeakReference

/**
 * <h1>RF Analyzer - Debug Control Receiver</h1>
 *
 * Module:      DebugControlReceiver.kt
 * Description: Debug and profile builds only: lets adb drive the app for on-device measurements.
 *
 *   adb shell am broadcast -n com.mantz_it.rfanalyzer/.DebugControlReceiver \
 *       --es set "fftSize=32768,manualFftSizeEnabled=true" --es get "sourceSampleRate" \
 *       --es cmd start|stop|overload --es stats on|off --es probe "97300000,200000" \
 *       --es record "name,ms"
 *
 * `cmd overload` reports one missed deadline to the OverloadMonitor, so the overload notice can be
 * looked at without actually overloading the device.
 *
 * `set`/`get` address any AppStateRepository state by its property name; values are parsed after
 * the type of the current value. `stats on` logs one line per second (tag RFA_DBG): settings,
 * actual FFT fps, every GlobalPerformanceData load/counter, and - with `probe f,bw` - the peak
 * level within f +- bw/2 and the median level of the newest FFT row. Needs the activity in front
 * (the receiver reaches the app state through the bound AnalyzerService).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class DebugControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RFA_DBG"
        private var service = WeakReference<AnalyzerService>(null)
        @Volatile private var statsThread: Thread? = null
        @Volatile private var probe: Pair<Long, Long>? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        (peekService(context, Intent(context, AnalyzerService::class.java)) as? AnalyzerService.LocalBinder)?.let {
            service = WeakReference(it.getService())
        }
        val asr = service.get()?.appStateRepository
        if (asr == null) {
            Log.e(TAG, "AnalyzerService not bound - bring the activity to the front")
            return
        }
        intent.getStringExtra("set")?.split(",")?.forEach { assignment ->
            val (name, value) = assignment.split("=", limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
            try {
                setState(asr, name, value)
                Log.i(TAG, "set $name=${stateOf(asr, name).value}")
            } catch (e: Exception) {
                Log.e(TAG, "set $name=$value failed: $e")
            }
        }
        intent.getStringExtra("get")?.split(",")?.forEach { name ->
            try { Log.i(TAG, "get $name=${stateOf(asr, name.trim()).value}") } catch (e: Exception) { Log.e(TAG, "get $name failed: $e") }
        }
        intent.getStringExtra("probe")?.let { arg ->
            probe = if (arg == "off") null else arg.split(",").let { it[0].trim().toLong() to it.getOrElse(1) { "10000" }.trim().toLong() }
            Log.i(TAG, "probe=$probe")
        }
        when (intent.getStringExtra("cmd")) {
            "start" -> {
                val s = service.get()
                if (s == null) Log.e(TAG, "start: AnalyzerService not bound (is the activity in front?)")
                else if (!asr.analyzerRunning.value) {
                    asr.analyzerStartPending.set(true)
                    Log.i(TAG, "start: ${s.startAnalyzer()}")
                }
            }
            "stop" -> service.get()?.stopAnalyzer().also { Log.i(TAG, "stop") }
            "overload" -> OverloadMonitor.reportMissedDeadline("debug").also { Log.i(TAG, "overload") }
        }
        intent.getStringExtra("record")?.let { arg ->
            // record=name,ms : raw source packets into <external files dir>/name (adb pull-able)
            val (name, ms) = arg.split(",").let { it[0].trim() to it.getOrElse(1) { "2000" }.trim().toLong() }
            val file = java.io.File(context.getExternalFilesDir(null), name)
            val scheduler = service.get()?.scheduler
            if (scheduler == null) Log.e(TAG, "record: no scheduler")
            else scheduler.startRecording(
                java.io.BufferedOutputStream(java.io.FileOutputStream(file)),
                onlyWhenSquelchIsSatisfied = false,
                maxRecordingTime = ms,
                onRecordingStopped = { size -> Log.i(TAG, "record done: ${file.absolutePath} $size bytes") },
                onFileSizeUpdate = { },
            )
        }
        when (intent.getStringExtra("stats")) {
            "on" -> startStats(asr)
            "off" -> { statsThread?.interrupt(); statsThread = null }
        }
    }

    private fun stateOf(asr: AppStateRepository, name: String): AppStateRepository.State<*> {
        val field = AppStateRepository::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(asr) as AppStateRepository.State<*>
    }

    @Suppress("UNCHECKED_CAST")
    private fun setState(asr: AppStateRepository, name: String, raw: String) {
        val state = stateOf(asr, name) as AppStateRepository.MutableState<Any?>
        val parsed: Any? = when (val current = state.value) {
            is Int -> raw.toInt()
            is Long -> raw.toLong()
            is Float -> raw.toFloat()
            is Double -> raw.toDouble()
            is Boolean -> raw.toBoolean()
            is String -> raw
            is Enum<*> -> current.declaringJavaClass.enumConstants!!.first { (it as Enum<*>).name == raw }
            else -> throw IllegalArgumentException("unsupported type ${current?.javaClass}")
        }
        state.set(parsed)
    }

    private fun startStats(asr: AppStateRepository) {
        if (statsThread != null) return
        statsThread = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(1000)
                    if (!asr.analyzerRunning.value) continue
                    val fft = service.get()?.fftProcessor
                    val sb = StringBuilder()
                    sb.append("sr=${asr.sourceSampleRate.value} f=${asr.sourceFrequency.value} fft=${asr.fftSize.value} pad=${"%.2f".format(asr.fftPaddingRatio.value)}")
                    sb.append(" fpsSet=${asr.waterfallFps.value} fps=${"%.1f".format(fft?.actualFftFramesPerSecond ?: -1f)} ovl=${OverloadMonitor.state.value.level}")
                    GlobalPerformanceData.getAllLoads().toSortedMap().forEach { (k, v) -> sb.append(" $k=${"%.0f".format(v * 100)}%") }
                    GlobalPerformanceData.getAllCounters().toSortedMap().forEach { (k, v) -> sb.append(" $k=$v") }
                    probe?.let { (f, bw) -> sb.append(" ").append(probeLevels(asr, f, bw)) }
                    Log.i(TAG, sb.toString())
                }
            } catch (_: InterruptedException) { }
        }.apply { name = "DebugStats"; isDaemon = true; start() }
    }

    private fun probeLevels(asr: AppStateRepository, freq: Long, bw: Long): String {
        val data = asr.fftProcessorData
        data.lock.readLock().lock()
        try {
            val row = data.waterfallBuffer?.get(data.readIndex) ?: return "probe=n/a"
            val f0 = data.frequency ?: return "probe=n/a"
            val sr = data.sampleRate ?: return "probe=n/a"
            val n = row.size
            fun idx(f: Long) = (((f - (f0 - sr / 2)).toDouble() / sr) * n).toInt().coerceIn(0, n - 1)
            fun db(b: Byte) = (b.toInt() and 0xFF) * FftProcessor.INV_SCALE + FftProcessor.MIN_DB
            var peak = -999f
            for (i in idx(freq - bw / 2)..idx(freq + bw / 2)) peak = maxOf(peak, db(row[i]))
            val sorted = row.map { db(it) }.sorted()
            return "probePeak=${"%.1f".format(peak)} median=${"%.1f".format(sorted[n / 2])}"
        } finally {
            data.lock.readLock().unlock()
        }
    }
}
