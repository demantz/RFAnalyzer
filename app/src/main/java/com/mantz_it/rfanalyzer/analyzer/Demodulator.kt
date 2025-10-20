package com.mantz_it.rfanalyzer.analyzer

import android.util.Log
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer.dsp.ComplexFirFilter
import com.mantz_it.rfanalyzer.dsp.FirFilter
import com.mantz_it.rfanalyzer.source.SamplePacket
import com.mantz_it.rfanalyzer.ui.composable.DemodulationMode
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.atan2

/**
 * <h1>RF Analyzer - Demodulator</h1>
 *
 * Module:      Demodulator.kt
 * Description: This class implements demodulation of various analog radio modes (FM, AM, SSB).
 * It runs as a separate thread. It will read raw complex samples from a queue,
 * process them (channel selection, filtering, demodulating) and forward the to
 * an AudioSink thread.
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
class Demodulator(
    inputQueue: ArrayBlockingQueue<SamplePacket>,   // Queue that delivers received baseband signals
    inputReturnQueue: ArrayBlockingQueue<SamplePacket>,  // Queue to return used buffers from the inputQueue
    packetSize: Int                                 // Size of the packets in the input queue
) : Thread() {

    companion object {
        private const val LOGTAG = "Demodulator"
        private const val AUDIO_RATE = 48000
        private const val BAND_PASS_ATTENUATION = 40

        // The quadrature rate is the sample rate that is used for the demodulation and dependend on the mode:
        private val DemodulationMode.quadratureRate: Int
            get() = when(this) {
                DemodulationMode.OFF -> 2 * AUDIO_RATE  // this value is a dummy to avoid setting the decimator output rate to illegal values!
                DemodulationMode.AM  -> 2 * AUDIO_RATE
                DemodulationMode.NFM -> 2 * AUDIO_RATE
                DemodulationMode.WFM -> 8 * AUDIO_RATE
                DemodulationMode.LSB -> 2 * AUDIO_RATE
                DemodulationMode.USB -> 2 * AUDIO_RATE
                DemodulationMode.CW  -> 1 * AUDIO_RATE
            }
        val MIN_INPUT_RATE = DemodulationMode.WFM.quadratureRate

        // FILTERING (This is the channel filter controlled by the user)
        private const val USER_FILTER_ATTENUATION = 60

        const val CW_OFFSET_FREQUENCY = 750  // Offset for the CW signal. 750Hz will be the audio tone the user hears if the signal is exactly at the channel frequency
    }

    private var stopRequested = true

    // Channel Filter
    private var userFilter: FirFilter? = null
    var channelWidth: Int = 0
        set(value) { field = value.coerceIn(demodulationMode.minChannelWidth, demodulationMode.maxChannelWidth) }

    // Create internal sample buffers:
    // Note that we create the buffers for the case that there is no downsampling necessary
    // All other cases with input decimation > 1 are also possible because they only need
    // smaller buffers.
    private val quadratureSamples = SamplePacket(packetSize)

    // DEMODULATION
    private var carryOverSamplesRe: Float = 0f // used for FM demodulation
    private var carryOverSamplesIm: Float = 0f // used for FM demodulation
    private var lastMax = 0f // used for gain control in AM / SSB demodulation
    private var bandPassFilter: ComplexFirFilter? = null // used for SSB demodulation
    var demodulationMode: DemodulationMode = DemodulationMode.OFF
        /**
         * Sets a new demodulation mode. This can be done while the demodulator is running!
         * Will automatically adjust internal sample rate conversions and the user filter
         * if necessary
         *
         * @param demodulationMode    Demodulation Mode (DEMODULATION_OFF, *_AM, *_NFM, *_WFM, ...)
         */
        set(value) {
            resampler.outputSampleRate = value.quadratureRate
            field = value
            this.channelWidth = value.defaultChannelWidth
        }

    // RESAMPLING (input sample rate --> QUADRATURE_RATE)
    private val resampler = Resampler(demodulationMode.quadratureRate, packetSize, inputQueue, inputReturnQueue)

    // AUDIO OUTPUT
    private var audioSink: AudioSink = AudioSink(packetSize, AUDIO_RATE) // Will do QUADRATURE_RATE --> AUDIO_RATE and audio output
    var audioVolumeLevel = 1f        // Audio Volume (0 is mute and 1 is full volume)

    /**
     * Starts the thread. This thread will start 2 more threads for decimation and audio output.
     * These threads are managed by the Demodulator and terminated, when the Demodulator thread
     * terminates.
     */
    @Synchronized
    override fun start() {
        stopRequested = false
        super.start()
    }

    /**
     * Stops the thread
     */
    fun stopDemodulator() {
        stopRequested = true
    }

    override fun run() {
        var inputSamples: SamplePacket?
        var audioBuffer: SamplePacket?

        this.name = "Thread-Demodulator-" + System.currentTimeMillis()
        Log.i(LOGTAG, "Demodulator started. (Thread: " + this.name + ")")

        // Start the audio sink thread:
        audioSink.start()

        // Start decimator thread:
        resampler.start()

        while (!stopRequested) {
            // Get downsampled packet from the decimator:
            inputSamples = resampler.getResampledPacket(1000)
            val startTimestamp = System.nanoTime()

            // Verify the input sample packet is not null:
            if (inputSamples == null) {
                //Log.d(LOGTAG, "run: Decimated sample is null. skip this round...");
                continue
            }

            // filtering (sample rate is demodulationMode.quadratureRate)
            applyUserFilter(inputSamples, quadratureSamples) // The result from filtering is stored in quadratureSamples

            // return input samples to the decimator block:
            resampler.returnResampledPacket(inputSamples)

            val time1 = System.nanoTime() - startTimestamp
            val nsPerPacket = inputSamples.size() * 1_000_000_000f / inputSamples.sampleRate

            // get buffer from audio sink
            audioBuffer = audioSink.getPacketBuffer(1000)

            val startTimestamp2 = System.nanoTime()

            if (audioBuffer == null) {
                Log.d(LOGTAG, "run: Audio buffer is null. skip this round...")
                continue
            }

            audioBuffer.setSize(0) // mark buffer as empty

            // demodulate (sample rate is demodulationMode.quadratureRate)
            when (demodulationMode) {
                DemodulationMode.OFF -> { }
                DemodulationMode.AM  -> demodulateAM(quadratureSamples, audioBuffer)
                DemodulationMode.NFM -> demodulateFM(quadratureSamples, audioBuffer, maxDeviation = channelWidth * 0.75f)
                DemodulationMode.WFM -> demodulateFM(quadratureSamples, audioBuffer, maxDeviation = channelWidth * 0.85f)
                DemodulationMode.LSB -> demodulateSSB(quadratureSamples, audioBuffer, false)
                DemodulationMode.USB -> demodulateSSB(quadratureSamples, audioBuffer, true)
                DemodulationMode.CW  -> demodulateCW(quadratureSamples, audioBuffer)
            }

            // apply audio volume:
            val audioBufferRe = audioBuffer.re()
            for (i in 0..<audioBuffer.size()) {
                audioBufferRe[i] = audioBufferRe[i] * audioVolumeLevel
            }

            // Performance Tracking
            val processingTime = System.nanoTime() - startTimestamp2 + time1
            GlobalPerformanceData.updateLoad("Demodulator", processingTime / nsPerPacket)

            // play audio (sample rate is demodulationMode.quadratureRate)
            audioSink.enqueuePacket(audioBuffer)
        }

        // Stop the audio sink thread:
        audioSink.stopSink()

        // Stop the decimator thread:
        resampler.stopResampler()

        this.stopRequested = true
        Log.i(LOGTAG, "Demodulator stopped. (Thread: " + this.name + ")")
    }

    /**
     * Will filter the samples in input according to the user filter settings.
     * Filtered samples are stored in output. Note: All samples in output
     * will always be overwritten!
     *
     * @param input     incoming (unfiltered) samples
     * @param output    outgoing (filtered) samples
     */
    private fun applyUserFilter(input: SamplePacket, output: SamplePacket) {
        // Verify that the filter is still correct configured:
        if (userFilter == null || (userFilter!!.cutOffFrequency.toInt()) != channelWidth) {
            // We have to (re-)create the user filter:
            this.userFilter = FirFilter.createLowPass(
                1,
                1f,
                input.sampleRate.toFloat(),
                channelWidth.toFloat(),
                input.sampleRate * 0.10f,
                USER_FILTER_ATTENUATION.toFloat()
            )

            if (userFilter == null)
                return  // This may happen if input samples changed rate or demodulation was turned off. Just skip the filtering.

            Log.d(LOGTAG, ("applyUserFilter: created new user filter with " + userFilter!!.numberOfTaps
                        + " taps. Decimation=" + userFilter!!.decimation + " Cut-Off=" + userFilter!!.cutOffFrequency
                        + " transition=" + userFilter!!.transitionWidth)
            )
        }
        output.setSize(0) // mark buffer as empty
        if (userFilter!!.filter(input, output, 0, input.size()) < input.size()) {
            Log.e(LOGTAG, "applyUserFilter: could not filter all samples from input packet.")
        }
    }

    /**
     * Will FM demodulate the samples in input. Use ~75000 deviation for wide band FM
     * and ~3000 deviation for narrow band FM.
     * Demodulated samples are stored in the real array of output. Note: All samples in output
     * will always be overwritten!
     *
     * @param input        incoming (modulated) samples
     * @param output    outgoing (demodulated) samples
     */
    private fun demodulateFM(input: SamplePacket, output: SamplePacket, maxDeviation: Float) {
        val reIn = input.re()
        val imIn = input.im()
        val reOut = output.re()
        val imOut = output.im()
        val inputSize = input.size()
        val quadratureGain = demodulationMode.quadratureRate / (2 * Math.PI * maxDeviation).toFloat()

        if (inputSize == 0)
            return

        // Quadrature demodulation:
        reOut[0] = reIn[0] * carryOverSamplesRe + imIn[0] * carryOverSamplesIm
        imOut[0] = imIn[0] * carryOverSamplesRe - reIn[0] * carryOverSamplesIm
        reOut[0] = quadratureGain * atan2(imOut[0], reOut[0])
        for (i in 1..<inputSize) {
            reOut[i] = reIn[i] * reIn[i - 1] + imIn[i] * imIn[i - 1]
            imOut[i] = imIn[i] * reIn[i - 1] - reIn[i] * imIn[i - 1]
            reOut[i] = quadratureGain * atan2(imOut[i], reOut[i])
        }
        carryOverSamplesRe = reIn[inputSize - 1]
        carryOverSamplesIm = imIn[inputSize - 1]
        output.setSize(inputSize)
        output.sampleRate = demodulationMode.quadratureRate
    }

    /**
     * Will AM demodulate the samples in input.
     * Demodulated samples are stored in the real array of output. Note: All samples in output
     * will always be overwritten!
     *
     * @param input        incoming (modulated) samples
     * @param output    outgoing (demodulated) samples
     */
    private fun demodulateAM(input: SamplePacket, output: SamplePacket) {
        val reIn = input.re()
        val imIn = input.im()
        val reOut = output.re()
        var avg = 0f
        lastMax *= 0.95.toFloat() // simplest AGC

        // Complex to magnitude
        for (i in 0..<input.size()) {
            reOut[i] = (reIn[i] * reIn[i] + imIn[i] * imIn[i])
            avg += reOut[i]
            if (reOut[i] > lastMax) lastMax = reOut[i]
        }
        avg /= input.size()

        // normalize values:
        val gain = 0.75f / lastMax
        for (i in 0..<input.size()) reOut[i] = (reOut[i] - avg) * gain

        output.setSize(input.size())
        output.sampleRate = demodulationMode.quadratureRate
    }

    /**
     * Will SSB demodulate the samples in input.
     * Demodulated samples are stored in the real array of output. Note: All samples in output
     * will always be overwritten!
     *
     * @param input        incoming (modulated) samples
     * @param output    outgoing (demodulated) samples
     * @param upperBand    if true: USB; if false: LSB
     */
    private fun demodulateSSB(input: SamplePacket, output: SamplePacket, upperBand: Boolean) {
        val reOut = output.re()

        // complex band pass:
        if (bandPassFilter == null || (upperBand && ((bandPassFilter!!.highCutOffFrequency.toInt()) != channelWidth))
            || (!upperBand && ((bandPassFilter!!.lowCutOffFrequency.toInt()) != -channelWidth))
        ) {
            // We have to (re-)create the band pass filter:
            this.bandPassFilter = ComplexFirFilter.createBandPass(
                2,  // Decimate by 2; => AUDIO_RATE
                1f,
                input.sampleRate.toFloat(),
                if (upperBand) 200f else -channelWidth.toFloat(),
                if (upperBand) channelWidth.toFloat() else -200f,
                input.sampleRate * 0.01f,
                BAND_PASS_ATTENUATION.toFloat()
            )
            if (bandPassFilter == null) return  // This may happen if input samples changed rate or demodulation was turned off. Just skip the filtering.

            Log.d(
                LOGTAG,
                ("demodulateSSB: created new band pass filter with " + bandPassFilter!!.numberOfTaps
                        + " taps. Decimation=" + bandPassFilter!!.decimation + " Low-Cut-Off=" + bandPassFilter!!.lowCutOffFrequency
                        + " High-Cut-Off=" + bandPassFilter!!.highCutOffFrequency + " transition=" + bandPassFilter!!.transitionWidth)
            )
        }
        output.setSize(0) // mark buffer as empty
        if (bandPassFilter!!.filter(input, output, 0, input.size()) < input.size()) {
            Log.e(LOGTAG, "demodulateSSB: could not filter all samples from input packet.")
        }

        // gain control: searching for max:
        lastMax *= 0.95.toFloat() // simplest AGC
        for (i in 0..<output.size()) {
            if (reOut[i] > lastMax) lastMax = reOut[i]
        }
        // normalize values:
        val gain = 0.75f / lastMax
        for (i in 0..<output.size()) reOut[i] *= gain
    }

    /**
     * Will CW demodulate the samples in input. Expects the signal to be at CW_FREQUENCY_OFFSET Hz.
     * Demodulated samples are stored in the real array of output. Note: All samples in output
     * will always be overwritten!
     *
     * @param input        incoming (modulated) samples
     * @param output       outgoing (demodulated) samples
     */
    private fun demodulateCW(input: SamplePacket, output: SamplePacket) {
        val reOut = output.re()

        // complex band pass:
        if (bandPassFilter == null || (bandPassFilter!!.highCutOffFrequency.toInt() != CW_OFFSET_FREQUENCY + channelWidth/2)) {
            // We have to (re-)create the band pass filter:
            this.bandPassFilter = ComplexFirFilter.createBandPass(
                1,
                1f,
                input.sampleRate.toFloat(),
                CW_OFFSET_FREQUENCY - channelWidth/2.0f,
                CW_OFFSET_FREQUENCY + channelWidth/2.0f,
                input.sampleRate * 0.01f,
                BAND_PASS_ATTENUATION.toFloat()
            )
            if (bandPassFilter == null) return  // This may happen if input samples changed rate or demodulation was turned off. Just skip the filtering.

            Log.d(
                LOGTAG,
                ("demodulateCW: created new band pass filter with " + bandPassFilter!!.numberOfTaps
                        + " taps. Decimation=" + bandPassFilter!!.decimation + " Low-Cut-Off=" + bandPassFilter!!.lowCutOffFrequency
                        + " High-Cut-Off=" + bandPassFilter!!.highCutOffFrequency + " transition=" + bandPassFilter!!.transitionWidth)
            )
        }
        output.setSize(0) // mark buffer as empty
        if (bandPassFilter!!.filter(input, output, 0, input.size()) < input.size()) {
            Log.e(LOGTAG, "demodulateCW: could not filter all samples from input packet.")
        }

        // gain control: searching for max:
        lastMax *= 0.95.toFloat() // simplest AGC
        for (i in 0..<output.size()) {
            if (reOut[i] > lastMax) lastMax = reOut[i]
        }
        // normalize values:
        val gain = 0.75f / lastMax
        for (i in 0..<output.size()) reOut[i] *= gain
    }
}
