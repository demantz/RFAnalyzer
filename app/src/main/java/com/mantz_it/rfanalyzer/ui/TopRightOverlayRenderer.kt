package com.mantz_it.rfanalyzer.ui

import com.mantz_it.rfanalyzer.digiext.digitalMode
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.mantz_it.rfanalyzer.digiext.ExtensionConnectionState
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.analyzer.OverloadMonitor
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer.ui.composable.asSizeInBytesToString
import com.mantz_it.rfanalyzer_ipc.Ft8TimingMode
import com.mantz_it.rfanalyzer_ipc.digiext.DemodStatus
import com.mantz_it.rfanalyzer_ipc.digiext.Ft8TimingStatus
import kotlin.math.abs
import java.util.Locale

/**
 * <h1>RF Analyzer - Top-Right Overlay Renderer</h1>
 *
 * Module:      TopRightOverlayRenderer.kt
 * Description: Renders the top-right overlay of [AnalyzerSurface]: source name, channel frequency
 *              and signal meter, recording indicator, FT8 timing, the digital demodulator's
 *              diagnostics and the debug information (FPS, loads, overload). Draws with the
 *              caller's text `Paint`s, styled in `AnalyzerSurface.DrawThread`.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TopRightOverlayRenderer(
    private val textPaint: Paint,
    private val textSmallPaint: Paint,
    private val defaultPaint: Paint,
    private val squelchPaint: Paint,
    private val sourceName: AppStateRepository.State<String>,
    private val analyzerRunning: AppStateRepository.State<Boolean>,
    private val demodulationEnabled: AppStateRepository.State<Boolean>,
    private val channelFrequency: AppStateRepository.State<Long>,
    private val squelch: AppStateRepository.State<Float>,
    private val viewportVerticalScaleMin: AppStateRepository.State<Float>,
    private val viewportVerticalScaleMax: AppStateRepository.State<Float>,
    private val recordingRunning: AppStateRepository.State<Boolean>,
    private val squelchSatisfied: AppStateRepository.State<Boolean>,
    private val recordOnlyWhenSquelchIsSatisfied: AppStateRepository.State<Boolean>,
    private val basebandRecordingRunning: AppStateRepository.State<Boolean>,
    private val sourceFrequency: AppStateRepository.State<Long>,
    private val demodulationMode: AppStateRepository.State<DemodulationMode>,
    private val sourceSampleRate: AppStateRepository.State<Long>,
    private val recordingCurrentFileSize: AppStateRepository.State<Long>,
    private val showDebugInformation: AppStateRepository.State<Boolean>,
    private val demodulationWfmStereoAndRdsEnabled: AppStateRepository.State<Boolean>,
) {
    private val bounds = Rect() // scratch object for text bounds measurement
    private val hudPanelBounds = RectF() // scratch object for the compact-HUD background panel
    // Debug-information block: one text paint whose color is set per line (synced to textSmallPaint
    // before each use, whose size follows the font-size setting), on a nearly opaque panel so the
    // lines stay readable over the frequency scale and grid.
    private val debugTextPaint = Paint()
    private val debugPanelPaint = Paint().apply {
        color = Color.argb(210, 0, 0, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val debugPanelBounds = RectF()
    // Background panel behind the always-on compact elements (packet count/offset line,
    // constellation, FT8 timing), so they stay readable over the FFT.
    private val hudPanelPaint = Paint().apply {
        color = Color.argb(170, 0, 0, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val constellationBorderPaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    private val constellationDotPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val connectionWarningPaint = Paint().apply {
        color = Color.rgb(255, 152, 0) // amber - distinct from squelch GREEN/RED and recording-dot RED
        isAntiAlias = true
    }
    private val heartbeatOkPaint = Paint().apply {
        color = Color.GREEN
        isAntiAlias = true
    }
    private val heartbeatStalePaint = Paint().apply {
        color = Color.RED
        isAntiAlias = true
    }
    private val ft8PlotBorderPaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    // Translucent band: the start times the FT8 decoder searches (DT -2.5..+2.5 s around the
    // nominal start) - see drawFt8TimingIndicator().
    private val ft8WindowBandPaint = Paint().apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val ft8SyncInWindowPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val ft8SyncOutOfWindowPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val ft8NominalStartPaint = Paint().apply {
        color = Color.argb(200, 220, 220, 220)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val ft8LockedLabelPaint = Paint().apply {
        color = Color.GREEN
        isAntiAlias = true
    }
    private val ft8SearchingLabelPaint = Paint().apply {
        color = Color.rgb(255, 152, 0)
        isAntiAlias = true
    }
    // Thin sweep line showing exactly where "now" falls within the current 15s cycle.
    private val ft8SweepLinePaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    /** The last FT8 timing status: the heartbeat brings one about every second. */
    private var ft8Timing: Ft8TimingStatus? = null
    private var ft8TimingReceivedAtMs = 0L

    private companion object {
        private const val FT8_PERIOD_MS = 15_000L
        /** The decoder searches starts this far around the nominal one (its DT range). */
        private const val FT8_MAX_DT_MS = 2_500
        private const val FT8_NOMINAL_START_MS = 500
    }

    fun draw(
        c: Canvas, width: Int, height: Int, frameRate: Int, averageSignalStrength: Float,
        demodStatus: DemodStatus?, demodPacketCounts: Map<DigitalMode, Int>,
        extensionConnectionState: ExtensionConnectionState
    ) {
        var text: String
        var yPos = height * 0.01f
        val rightBorder = width * 0.99f
        val minDB = viewportVerticalScaleMin.value
        val maxDB = viewportVerticalScaleMax.value

        // Name
        text = sourceName.value
        textSmallPaint.getTextBounds(text, 0, text.length, bounds)
        c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint);
        yPos += bounds.height() * 1.1f;

        if (demodulationEnabled.value) {
            // Draw the channel frequency if demodulation is enabled:
            text = String.format("demod at %4.6f MHz", channelFrequency.value / 1_000_000.0)
            textSmallPaint.getTextBounds(text, 0, text.length, bounds)
            c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)

            // increase yPos:
            yPos += bounds.height() * 1.1f

            // Draw the average signal strength indicator if demodulation is enabled
            text = String.format("%2.1f dB", averageSignalStrength)
            textSmallPaint.getTextBounds(text, 0, text.length, bounds)

            val indicatorWidth = (width / 10).toFloat()
            val indicatorPosX = rightBorder - indicatorWidth
            val indicatorPosY = yPos + bounds.height()
            val squelchTickPos = (squelch.value - minDB) / (maxDB - minDB) * indicatorWidth
            var signalWidth = (averageSignalStrength - minDB) / (maxDB - minDB) * indicatorWidth
            if (signalWidth < 0) signalWidth = 0f
            if (signalWidth > indicatorWidth) signalWidth = indicatorWidth

            // draw signal rectangle:
            c.drawRect(
                indicatorPosX,
                yPos + bounds.height() * 0.1f,
                indicatorPosX + signalWidth,
                indicatorPosY,
                squelchPaint
            )

            // draw left border, right border, bottom line and squelch tick:
            c.drawLine(indicatorPosX, indicatorPosY, indicatorPosX, yPos, textPaint)
            c.drawLine(rightBorder, indicatorPosY, rightBorder, yPos, textPaint)
            c.drawLine(indicatorPosX, indicatorPosY, rightBorder, indicatorPosY, textPaint)
            c.drawLine(
                indicatorPosX + squelchTickPos,
                indicatorPosY + 2,
                indicatorPosX + squelchTickPos,
                yPos + bounds.height() * 0.5f,
                textPaint
            )

            // draw text:
            c.drawText(text, indicatorPosX - bounds.width() * 1.1f, indicatorPosY, textSmallPaint)

            // increase yPos:
            yPos += bounds.height() * 1.1f

            // The extension connection's state while it isn't CONNECTED, for the modes that use
            // the extension. Debug information only (see drawDemodDiagnostics()).
            val usesDigitalExtension = demodulationMode.value.isDigitalExtension ||
                (demodulationMode.value == DemodulationMode.WFM && demodulationWfmStereoAndRdsEnabled.value)
            if (showDebugInformation.value && usesDigitalExtension && extensionConnectionState != ExtensionConnectionState.CONNECTED) {
                connectionWarningPaint.textSize = textSmallPaint.textSize
                text = "IPC: ${compactConnectionLabel(extensionConnectionState)}"
                connectionWarningPaint.getTextBounds(text, 0, text.length, bounds)
                c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), connectionWarningPaint)
                yPos += bounds.height() * 1.1f
            }
        }

        // Draw recording information (raw IQ or baseband - they never run together)
        if (recordingRunning.value || basebandRecordingRunning.value) {
            if (squelchSatisfied.value || !recordOnlyWhenSquelchIsSatisfied.value) {
                val displayFrequency = if (basebandRecordingRunning.value) channelFrequency.value else sourceFrequency.value
                val displaySampleRate = if (basebandRecordingRunning.value) demodulationMode.value.basebandSamplerate.toLong() else sourceSampleRate.value
                text = String.format("%4.6f MHz @ %2.3f MSps (%s)", displayFrequency / 1_000_000.0, displaySampleRate / 1_000_000.0, recordingCurrentFileSize.value.asSizeInBytesToString())
                textSmallPaint.getTextBounds(text, 0, text.length, bounds)
                c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)
                defaultPaint.color = Color.RED
                c.drawCircle(
                    rightBorder - bounds.width() - (bounds.height() / 2) * 1.3f,
                    yPos + bounds.height() / 2,
                    (bounds.height() / 2).toFloat(),
                    defaultPaint
                )
                // increase yPos:
                yPos += bounds.height() * 1.1f
            }
        }

        if (showDebugInformation.value) {
            val lines = ArrayList<Pair<String, Int>>()
            lines += "$frameRate FPS" to textSmallPaint.color
            lines += overloadStateLines()
            GlobalPerformanceData.getAllLoads().forEach { (metricId, value) ->
                lines += String.format("$metricId: %03.1f %%", value * 100) to textSmallPaint.color
            }
            // Pipeline-health counters (queue drops, IPC poll misses, ring overwrites, ...) -
            // lifetime-cumulative, not per-frame, so a nonzero/climbing value across a session is
            // itself the signal (see GlobalPerformanceData.incrementCounter()'s call sites).
            GlobalPerformanceData.getAllCounters().forEach { (counterId, value) ->
                lines += "$counterId: $value" to textSmallPaint.color
            }
            yPos = drawDebugLines(c, rightBorder, yPos, lines)
        }

        // FT8 timing indicator - always shown while FT8 runs
        if (demodulationMode.value == DemodulationMode.FT8 && analyzerRunning.value) {
            demodStatus?.ft8Timing?.let { timing ->
                if (timing !== ft8Timing) {
                    ft8Timing = timing
                    ft8TimingReceivedAtMs = System.currentTimeMillis()
                }
            }
            yPos = drawFt8TimingIndicator(c, width, rightBorder, yPos)
        } else {
            ft8Timing = null
        }

        // Demodulator diagnostics: only a status of the active mode (DemodStatus.mode), so a
        // previous mode's diagnostics don't linger after a switch.
        val expectedTag = expectedDiagnosticsModeTag()
        if (expectedTag != null && demodStatus != null && demodStatus.mode == expectedTag) {
            drawDemodDiagnostics(c, width, rightBorder, yPos, demodStatus, demodPacketCounts)
        }
    }

    /** OverloadMonitor's current window: highest stage load (green/yellow/red) and, while overloaded,
     *  what was dropped or missed. */
    private fun overloadStateLines(): List<Pair<String, Int>> {
        val state = OverloadMonitor.state.value
        if (state.maxLoadStage == null && state.level == OverloadMonitor.Level.NORMAL) return emptyList()
        val loadColor = when {
            state.maxLoad >= OverloadMonitor.OVERLOAD_THRESHOLD -> Color.RED
            state.maxLoad >= OverloadMonitor.HIGH_LOAD_THRESHOLD -> Color.YELLOW
            else -> Color.GREEN
        }
        val lines = arrayListOf(String.format("Load %.0f%% (%s)", state.maxLoad * 100, state.maxLoadStage ?: "-") to loadColor)
        if (state.level == OverloadMonitor.Level.OVERLOAD) {
            if (state.lostFraction > 0f)
                lines += String.format("Overload: %.1f%% samples dropped (%s)", state.lostFraction * 100, state.lossStage) to Color.RED
            if (state.missedDeadlines > 0)
                lines += "Overload: ${state.missedDeadlineSource} missed ${state.missedDeadlines}x" to Color.RED
            if (state.lostFraction == 0f && state.missedDeadlines == 0)
                lines += "Overload: ${state.maxLoadStage} can't keep up" to Color.RED
        }
        return lines
    }

    /** Right-aligned (text, color) lines on one background panel, starting at [yPosStart]. Returns the new yPos. */
    private fun drawDebugLines(c: Canvas, rightBorder: Float, yPosStart: Float, lines: List<Pair<String, Int>>): Float {
        if (lines.isEmpty()) return yPosStart
        debugTextPaint.set(textSmallPaint)
        val lineHeight = debugTextPaint.textSize * 1.15f
        val pad = debugTextPaint.textSize * 0.3f
        val maxWidth = lines.maxOf { debugTextPaint.measureText(it.first) }
        debugPanelBounds.set(rightBorder - maxWidth - pad, yPosStart, rightBorder + pad, yPosStart + lines.size * lineHeight + pad)
        c.drawRoundRect(debugPanelBounds, pad, pad, debugPanelPaint)
        var yPos = yPosStart
        for ((text, color) in lines) {
            debugTextPaint.color = color
            c.drawText(text, rightBorder - debugTextPaint.measureText(text), yPos + debugTextPaint.textSize, debugTextPaint)
            yPos += lineHeight
        }
        return yPos + pad
    }

    /**
     * FT8 timing indicator: where in the 15 s cycle transmissions start, as the extension measures
     * it (the sync profile of [Ft8TimingStatus], in the time the periods are cut in). X is the
     * cycle centred on the nominal start (0.5 s after the boundary, the centre line), so with the
     * right timing the profile is one green peak in the middle. The translucent band marks the
     * start times the decoder searches (DT ±2.5 s); red bars outside it are transmissions it
     * misses - in Manual, move the offset until the peak is in the middle. The yellow line is
     * "now". The label above says how the timing is set, the line below the DT spread of the
     * recent decodes.
     */
    private fun drawFt8TimingIndicator(c: Canvas, width: Int, rightBorder: Float, yPosStart: Float): Float {
        val timing = ft8Timing
        val now = System.currentTimeMillis()
        val textSize = textSmallPaint.textSize
        val label = when {
            timing == null -> "FT8 timing: waiting"
            timing.mode == Ft8TimingMode.AUTOMATIC && !timing.locked -> String.format("Auto: searching (%+.2f s)", timing.offsetMs / 1000f)
            timing.mode == Ft8TimingMode.AUTOMATIC -> String.format("Auto: locked %+.2f s", timing.offsetMs / 1000f)
            timing.mode == Ft8TimingMode.INTERNET_TIME -> "Internet time"
            else -> String.format("Manual %+.2f s", timing.offsetMs / 1000f)
        }
        val labelPaint = if (timing != null && timing.locked) ft8LockedLabelPaint else ft8SearchingLabelPaint
        labelPaint.textSize = textSize
        val labelWidth = labelPaint.measureText(label)
        val dtText = timing?.dtMedian?.let { String.format("DT %+.2f s (%+.1f..%+.1f)", it, timing.dtLow ?: it, timing.dtHigh ?: it) }

        val pad = textSize * 0.3f
        val top = yPosStart + pad
        // at least as wide as the longest label, so the bars stay readable on a narrow portrait screen
        val boxWidth = maxOf(width * 0.26f, textSize * 11f)
        val boxHeight = boxWidth * 0.4f
        val boxLeft = rightBorder - boxWidth
        // fixed line heights (not the text bounds), so the box doesn't move when the label changes
        val boxTop = top + textSize * 1.15f
        val boxBottom = boxTop + boxHeight
        val bottom = if (dtText != null) boxBottom + textSize * 1.2f else boxBottom

        // background panel like the rest of the compact HUD, so the plot reads against the FFT
        hudPanelBounds.set(minOf(boxLeft, rightBorder - labelWidth) - pad, yPosStart, rightBorder + pad, bottom + pad)
        c.drawRoundRect(hudPanelBounds, pad, pad, hudPanelPaint)
        c.drawText(label, rightBorder - labelWidth, top + textSize * 0.8f, labelPaint)

        // whole milliseconds, so the 200 ms bins line up exactly with the edges of the box
        val periodMs = FT8_PERIOD_MS.toInt()
        val nominalMs = FT8_NOMINAL_START_MS
        fun xOfPhase(phaseMs: Long) =
            boxLeft + Math.floorMod(phaseMs - nominalMs + periodMs / 2, FT8_PERIOD_MS) * boxWidth / periodMs
        val centerX = boxLeft + boxWidth / 2
        val bandHalfWidth = FT8_MAX_DT_MS * boxWidth / periodMs
        c.drawRect(centerX - bandHalfWidth, boxTop, centerX + bandHalfWidth, boxBottom, ft8WindowBandPaint)
        timing?.syncProfile?.let { profile ->
            val binMs = Ft8TimingStatus.SYNC_PROFILE_BIN_MS
            val binWidth = binMs * boxWidth / periodMs
            val maxHeight = boxHeight * 0.9f
            for (k in profile.indices) {
                val value = profile[k].coerceIn(0f, 1f)
                if (value <= 0f) continue
                val x = xOfPhase(k.toLong() * binMs)
                val inWindow = abs(x + binWidth / 2 - centerX) <= bandHalfWidth
                c.drawRect(x, boxBottom - value * maxHeight, x + binWidth, boxBottom, if (inWindow) ft8SyncInWindowPaint else ft8SyncOutOfWindowPaint)
            }
        }
        c.drawLine(centerX, boxTop, centerX, boxBottom, ft8NominalStartPaint)
        c.drawRect(boxLeft, boxTop, rightBorder, boxBottom, ft8PlotBorderPaint)

        // "now" in the signal's own time: the newest sample's aligned time, advanced since it came
        val nowMs = if (timing != null) timing.sampleTimeMs + (now - ft8TimingReceivedAtMs) else now
        val sweepX = xOfPhase(nowMs)
        c.drawLine(sweepX, boxTop, sweepX, boxBottom, ft8SweepLinePaint)

        if (dtText != null) c.drawText(dtText, rightBorder - textSmallPaint.measureText(dtText), boxBottom + textSize * 1.0f, textSmallPaint)
        return bottom + pad + textSize * 0.25f
    }

    /** Short label for the compact overlay indicator - CONNECTED is filtered out by the caller. */
    private fun compactConnectionLabel(state: ExtensionConnectionState): String = when (state) {
        ExtensionConnectionState.DISCONNECTED -> "disconnected"
        ExtensionConnectionState.RECONNECTING -> "reconnecting…"
        ExtensionConnectionState.VERSION_MISMATCH_UPDATE_APP -> "update app"
        ExtensionConnectionState.VERSION_MISMATCH_UPDATE_EXTENSION -> "update extension"
        ExtensionConnectionState.NOT_INSTALLED -> "not installed"
        ExtensionConnectionState.NOT_PERMITTED -> "other signature"
        ExtensionConnectionState.UNAVAILABLE -> "unavailable"
        ExtensionConnectionState.CONNECTED -> ""
    }

    /** The DemodStatus.mode tag expected from the currently active demodulation mode, or null if
     * that mode doesn't (yet) produce diagnostics. */
    private fun expectedDiagnosticsModeTag(): DigitalMode? =
        demodulationMode.value.digitalMode(demodulationWfmStereoAndRdsEnabled.value)

    // Loose plausibility bound for dB-scale readings - not a precise physical limit, just enough
    // to catch a near-zero-input log10() blowing up into an absurd (if technically finite) value.
    private val MAX_SANE_DB = 150f

    // All demodulators throttle DemodStatus emission to ~1Hz, checked once per incoming
    // SamplePacket - FT8 is the tightest case (packet period ~853ms, so the throttle lands every
    // other packet, ~1706ms worst case). 2500ms gives ~800ms headroom above that for IPC/Binder
    // jitter without letting genuine staleness go unnoticed.
    private val STALE_THRESHOLD_MS = 2500L

    /**
     * Draws the digital-extension diagnostics HUD for [status]'s mode. Two tiers:
     *  - Compact (always drawn): packet count, RTTY/PSK31 parameters and frequency offset on one
     *    line, plus the constellation plot if the mode has one.
     *  - Detail (only with [showDebugInformation]): heartbeat, signal level, FEC - for
     *    troubleshooting a demodulator.
     */
    private fun drawDemodDiagnostics(c: Canvas, width: Int, rightBorder: Float, yPosStart: Float, status: DemodStatus, demodPacketCounts: Map<DigitalMode, Int>) {
        var yPos = yPosStart

        val packetCount = demodPacketCounts[status.mode]
        val offsetHz = status.frequencyOffsetHz?.takeIf { it.isFinite() }
        // RTTY: what it decodes with (detected values in auto mode).
        val rtty = status.rtty?.let {
            String.format(Locale.ROOT, "%s Bd %d Hz%s", if (it.baudRate == 45.45f) "45.45" else it.baudRate.toInt().toString(),
                it.shiftHz.toInt(), if (it.reversed) " rev" else "")
        }
        // PSK31: the symbol rate in use (detected in auto mode) as the variant's name.
        val psk = status.psk31?.let { "PSK" + Math.round(it.baudRate) }
        if (packetCount != null || offsetHz != null || rtty != null || psk != null) {
            val text = listOfNotNull(
                packetCount?.let { "#$it" },
                rtty,
                psk,
                offsetHz?.let { String.format("%+.1f Hz", it) },
            ).joinToString("   ")
            textSmallPaint.getTextBounds(text, 0, text.length, bounds)
            val padH = bounds.height() * 0.4f
            val padV = bounds.height() * 0.3f
            hudPanelBounds.set(
                rightBorder - bounds.width() - padH * 2f, yPos,
                rightBorder + padH * 0.5f, yPos + bounds.height() + padV * 2f,
            )
            c.drawRoundRect(hudPanelBounds, padV, padV, hudPanelPaint)
            c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height() + padV * 0.9f, textSmallPaint)
            yPos = hudPanelBounds.bottom + bounds.height() * 0.25f
        }

        status.constellation?.takeIf { it.size >= 2 }?.let { points ->
            yPos = drawConstellationPlot(c, width, rightBorder, yPos, status, points)
        }

        if (!showDebugInformation.value) return

        var text: String
        if (analyzerRunning.value) {
            // Heartbeat: the last status's age, recomputed every frame - a growing value means a
            // hung or disconnected demodulator.
            val elapsedMs = System.currentTimeMillis() - status.timestamp
            val heartbeatPaint = if (elapsedMs < STALE_THRESHOLD_MS) heartbeatOkPaint else heartbeatStalePaint
            heartbeatPaint.textSize = textSmallPaint.textSize
            text = "last update: ${elapsedMs}ms ago"
            heartbeatPaint.getTextBounds(text, 0, text.length, bounds)
            c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), heartbeatPaint)
            yPos += bounds.height() * 1.1f
        }

        status.signalLevelDb?.takeIf { it.isFinite() && abs(it) < MAX_SANE_DB }?.let { level ->
            text = String.format("signal: %.1f dB", level)
            textSmallPaint.getTextBounds(text, 0, text.length, bounds)
            c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)
            yPos += bounds.height() * 1.1f
        }

        // fecStats is already null whenever nothing happened in this emission window, so no
        // separate "hide if zero" check is needed here.
        status.fecStats?.let { fec ->
            text = if (fec.corrected != null)
                "FEC: ${fec.passed}/${fec.attempts} ok, ${fec.corrected} corrected"
            else
                "FEC: ${fec.passed}/${fec.attempts} ok"
            textSmallPaint.getTextBounds(text, 0, text.length, bounds)
            c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)
            yPos += bounds.height() * 1.1f
        }
    }

    /**
     * Constellation scatter plot for [points] (interleaved I/Q). Returns the yPos for whatever
     * gets drawn next, unchanged from [yPos] if every sample turned out non-finite (nothing drawn).
     */
    private fun drawConstellationPlot(c: Canvas, width: Int, rightBorder: Float, yPos: Float, status: DemodStatus, points: FloatArray): Float {
        val boxSize = width * 0.12f
        val boxLeft = rightBorder - boxSize
        val boxTop = yPos

        // Skip any non-finite sample so one bad point can't wreck the scale or get plotted
        // off-canvas.
        var maxMag = 0.01f
        var anyFinite = false
        var idx = 0
        while (idx + 1 < points.size) {
            val i = points[idx]; val q = points[idx + 1]
            if (i.isFinite() && q.isFinite()) {
                maxMag = maxOf(maxMag, abs(i), abs(q))
                anyFinite = true
            }
            idx += 2
        }
        if (!anyFinite) return yPos

        // The same background panel as the compact line above
        val pad = boxSize * 0.08f
        hudPanelBounds.set(boxLeft - pad, boxTop - pad, boxLeft + boxSize + pad, boxTop + boxSize + pad)
        c.drawRoundRect(hudPanelBounds, pad, pad, hudPanelPaint)

        // Green/red = locked/unlocked for modes with a lock state (DemodStatus.locked): receiving
        // vs. searching or squelched. Null (AX.25, R09, ADS-B - packets too short for a lock
        // state) keeps the neutral gray/cyan.
        val lockColor = status.locked?.let { if (it) Color.GREEN else Color.RED }
        constellationBorderPaint.color = lockColor ?: Color.GRAY
        constellationDotPaint.color = lockColor ?: Color.CYAN
        c.drawRect(boxLeft, boxTop, boxLeft + boxSize, boxTop + boxSize, constellationBorderPaint)
        val centerX = boxLeft + boxSize / 2f
        val centerY = boxTop + boxSize / 2f
        val plotRadius = boxSize / 2f * 0.9f
        val dotRadius = boxSize * 0.02f
        idx = 0
        while (idx + 1 < points.size) {
            val i = points[idx]; val q = points[idx + 1]
            if (i.isFinite() && q.isFinite()) {
                val x = centerX + (i / maxMag) * plotRadius
                val y = centerY - (q / maxMag) * plotRadius // invert Q: canvas Y grows downward
                c.drawCircle(x, y, dotRadius, constellationDotPaint)
            }
            idx += 2
        }
        return boxTop + boxSize + pad + boxSize * 0.1f
    }
}
