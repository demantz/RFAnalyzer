package com.mantz_it.rfanalyzer

import com.mantz_it.rfanalyzer.dsp.ComplexFirFilter
import com.mantz_it.rfanalyzer.dsp.FirFilter.Companion.createLowPass
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import com.mantz_it.rfanalyzer.ui.composable.toTimeSpanString
import java.util.Locale
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * <h1>RF Analyzer - Application Tests</h1>
 *
 * Module:      ApplicationTest.kt
 * Description: Instrumented tests of small helpers: the text of toTimeSpanString(), the output of
 *              FirFilter low-pass filters against stored values and their speed on the device, and
 *              the creation of ComplexFirFilter band-pass filters. testLocation prints the ISO
 *              country codes with their names.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class ApplicationTest {
    @Test
    fun testLocation() {
        val locales = Locale.getISOCountries()
            .map { code -> Locale("", code) }
            .sortedBy { it.displayCountry }
        fun localeToString(locale: Locale): String {
            return "${locale.displayCountry} (${locale.country})"
        }
        for (locale in locales) {
            println("\"${locale.country}\" to \"${locale.displayName}\",")
        }
    }

    @Test
    fun testTimeSpanToString() {
        val duration = 4L * 24 * 60 * 60 * 1000 +
                11L * 60 * 60 * 1000 +
                1L * 60 * 1000 +
                53L * 1000
        val text = duration.toTimeSpanString()
        assertEquals("4 days, 11 hours, 1 minute and 53 seconds", text)
    }

    @Test
    fun testFirFilter() {
        val samples = 128
        val reIn = FloatArray(samples)
        val imIn = FloatArray(samples)
        val reOut = FloatArray(samples / 4)
        val imOut = FloatArray(samples / 4)
        val sampleRate = 1000
        val `in` = SamplePacket(reIn, imIn, 0, sampleRate)
        val out = SamplePacket(reOut, imOut, 0, sampleRate / 4)
        out.setSize(0)
        val f1 = 50
        val f2 = 200

        for (i in reIn.indices) {
            reIn[i] =
                cos(2 * Math.PI * f1 * i / sampleRate.toFloat()).toFloat() + cos(2 * Math.PI * f2 * i / sampleRate.toFloat()).toFloat()
            imIn[i] =
                sin(2 * Math.PI * f1 * i / sampleRate.toFloat()).toFloat() + sin(2 * Math.PI * f2 * i / sampleRate.toFloat()).toFloat()
        }

        val filter = createLowPass(4, 1f, sampleRate.toFloat(), 100f, 50f, 60f)
        println("Created filter with " + filter!!.numberOfTaps + " taps!")

        filter.filter(`in`, out, 0, `in`.size())

        val reOutExpected = floatArrayOf(
            1.7833801E-4f,            5.6521903E-4f,
            -0.008516869f,
            0.028878199f,
            -0.049486578f,
            0.03608987f,
            0.6999547f,
            0.35845482f,
            -0.81208223f,
            -0.8160145f,
            0.31258956f,
            0.9993112f,
            0.3089788f,
            -0.8089169f,
            -0.808917f,
            0.3089788f,
            0.99987644f,
            0.3089788f,
            -0.8089169f,
            -0.808917f,
            0.3089788f,
            0.99987644f,
            0.3089788f,
            -0.8089169f,
            -0.808917f,
            0.3089788f,
            0.99987644f,
            0.3089788f,
            -0.8089169f,
            -0.808917f,
            0.3089788f,
            0.99987644f
        )
        val imOutExpected = floatArrayOf(
            -1.0358797E-5f,
            0.001164517f,
            -0.0048069474f,
            0.007154221f,
            0.0013908893f,
            -0.035754967f,
            0.33497554f,
            0.9150993f,
            0.5890511f,
            -0.5805061f,
            -0.95566136f,
            0.0011645338f,
            0.95084405f,
            0.5876602f,
            -0.58766025f,
            -0.9508543f,
            1.675597E-8f,
            0.9508544f,
            0.5876602f,
            -0.58766025f,
            -0.9508543f,
            1.675597E-8f,
            0.9508544f,
            0.5876602f,
            -0.58766025f,
            -0.9508543f,
            1.675597E-8f,
            0.9508544f,
            0.5876602f,
            -0.58766025f,
            -0.9508543f,
            1.675597E-8f
        )

        for (i in 0..<out.size()) {
            assertEquals(reOutExpected[i].toDouble(), reOut[i].toDouble(), 1e-9)
            assertEquals(imOutExpected[i].toDouble(), imOut[i].toDouble(), 1e-9)
        }
    }

    @Test
    fun testFirFilter2() {
        val samples = 64
        val reIn = FloatArray(samples)
        val imIn = FloatArray(samples)
        val reOut = FloatArray(samples)
        val imOut = FloatArray(samples)
        val sampleRate = 1000
        val `in` = SamplePacket(reIn, imIn, 0, sampleRate)
        val out = SamplePacket(reOut, imOut, 0, sampleRate / 4)
        out.setSize(0)
        val f1 = 50
        val f2 = 200

        for (i in reIn.indices) {
            reIn[i] =
                cos(2 * Math.PI * f1 * i / sampleRate.toFloat()).toFloat() + cos(2 * Math.PI * f2 * i / sampleRate.toFloat()).toFloat()
            imIn[i] =
                sin(2 * Math.PI * f1 * i / sampleRate.toFloat()).toFloat() + sin(2 * Math.PI * f2 * i / sampleRate.toFloat()).toFloat()
        }

        val filter = createLowPass(1, 1f, sampleRate.toFloat(), 100f, 100f, 40f)
        println("Created filter with " + filter!!.numberOfTaps + " taps!")

        filter.filter(`in`, out, 0, `in`.size())

        val reOutExpected = floatArrayOf(
            -9.0414577E-4f, -0.005165519f, -0.011364181f, -0.005235526f, 0.04240741f, 0.15844727f, 0.3370772f, 0.52623874f, 0.65431756f, 0.67847294f, 0.6059719f, 0.4669314f, 0.2750504f, 0.027459646f, -0.25864854f, -0.5279595f, -0.72069085f, -0.8192188f, -0.8463746f, -0.8192188f, -0.7206908f, -0.52852917f, -0.26154414f, 0.022224117f, 0.27527937f, 0.4925698f, 0.6847314f, 0.8329541f, 0.8908228f, 0.83295405f, 0.6847314f, 0.49256986f, 0.27527937f, 0.022224123f, -0.2615441f, -0.52852917f, -0.72069085f, -0.8192188f, -0.8463746f, -0.8192188f, -0.7206908f, -0.52852917f, -0.26154414f, 0.022224117f, 0.27527937f, 0.4925698f, 0.6847314f, 0.8329541f, 0.8908228f, 0.83295405f, 0.6847314f, 0.49256986f, 0.27527937f, 0.022224123f, -0.2615441f, -0.52852917f, -0.72069085f, -0.8192188f, -0.8463746f, -0.8192188f, -0.7206908f, -0.52852917f, -0.26154414f
        )
        val imOutExpected = floatArrayOf(
            4.077212E-10f, -5.696449E-4f, -0.0034270133f, -0.0081369355f, -0.005486103f, 0.025185125f, 0.105308466f, 0.23624158f, 0.3894831f, 0.52578974f, 0.628921f, 0.71483326f, 0.799464f, 0.8604618f, 0.8437958f, 0.71520454f, 0.4974865f, 0.24727537f, 7.567263E-9f, -0.24727538f, -0.4974865f, -0.7157741f, -0.8472228f, -0.86859876f, -0.80495006f, -0.68964803f, -0.5236125f, -0.2895482f, -2.0979844E-8f, 0.28954813f, 0.52361256f, 0.68964815f, 0.80495006f, 0.8685987f, 0.8472228f, 0.7157742f, 0.4974865f, 0.24727537f, 7.567263E-9f, -0.24727538f, -0.4974865f, -0.7157741f, -0.8472228f, -0.86859876f, -0.80495006f, -0.68964803f, -0.5236125f, -0.2895482f, -2.0979844E-8f, 0.28954813f, 0.52361256f, 0.68964815f, 0.80495006f, 0.8685987f, 0.8472228f, 0.7157742f, 0.4974865f, 0.24727537f, 7.567263E-9f, -0.24727538f, -0.4974865f, -0.7157741f, -0.8472228f
        )

        for (i in 0..<out.size()) {
            assertEquals(reOutExpected[i].toDouble(), reOut[i].toDouble(), 1e-9)
            assertEquals(imOutExpected[i].toDouble(), imOut[i].toDouble(), 1e-9)
        }
    }

    @Test
    fun testFirFilterPerformance() {
        val samples = 1000000
        val reIn = FloatArray(samples)
        val imIn = FloatArray(samples)
        val reOut = FloatArray(samples / 4)
        val imOut = FloatArray(samples / 4)
        val sampleRate = 1000000
        val `in` = SamplePacket(reIn, imIn, 0, sampleRate)
        val out = SamplePacket(reOut, imOut, 0, sampleRate / 4)
        out.setSize(0)
        val f1 = 50000
        val f2 = 200000

        for (i in reIn.indices) {
            reIn[i] =
                cos(2 * Math.PI * f1 * i / sampleRate.toFloat()).toFloat() + cos(2 * Math.PI * f2 * i / sampleRate.toFloat()).toFloat()
            imIn[i] =
                sin(2 * Math.PI * f1 * i / sampleRate.toFloat()).toFloat() + sin(2 * Math.PI * f2 * i / sampleRate.toFloat()).toFloat()
        }

        val filter = createLowPass(4, 1f, sampleRate.toFloat(), 100000f, 50000f, 60f)
        println("Created filter with " + filter!!.numberOfTaps + " taps for performance test!")

        val startTime = System.nanoTime()
        for (i in 0..9) {
            filter.filter(`in`, out, 0, `in`.size())
            out.setSize(0)
        }
        val endTime = System.nanoTime()

        val durationNs = endTime - startTime
        val durationMs = durationNs / 1000000.0

        println("FirFilter performance test: Filtering " + samples + " samples took " + durationMs + " ms.")

        // Threshold from a Pixel 4a 5G (Android 12), which took about 624 ms.
        assertTrue("Filter operation took too long: " + durationMs + " ms", durationMs < 650)
    }


    @Test
    fun testComplexFirFilter() {
        val samples = 32
        val reIn = FloatArray(samples)
        val imIn = FloatArray(samples)
        val reOut = FloatArray(samples)
        val imOut = FloatArray(samples)
        val sampleRate = 1000
        val `in` = SamplePacket(reIn, imIn, 0, sampleRate)
        val out = SamplePacket(reOut, imOut, 0, sampleRate)
        out.setSize(0)
        val f1 = 250
        val f2 = -250

        for (i in reIn.indices) {
            reIn[i] =
                cos(2 * Math.PI * f1 * i / sampleRate.toFloat()).toFloat() + cos(2 * Math.PI * f2 * i / sampleRate.toFloat()).toFloat()
            imIn[i] =
                sin(2 * Math.PI * f1 * i / sampleRate.toFloat()).toFloat() + sin(2 * Math.PI * f2 * i / sampleRate.toFloat()).toFloat()
        }

        val filter =
            ComplexFirFilter.createBandPass(1, 1f, sampleRate.toFloat(), -450f, -50f, 50f, 60f)
        println("Created filter with " + filter!!.numberOfTaps + " taps!")
    }

    @Test
    fun testComplexFirFilter2() {
        val samples = 32
        val reIn = FloatArray(samples)
        val imIn = FloatArray(samples)
        val reOut = FloatArray(samples)
        val imOut = FloatArray(samples)
        val sampleRate = 1000
        val `in` = SamplePacket(reIn, imIn, 0, sampleRate)
        val out = SamplePacket(reOut, imOut, 0, sampleRate)
        out.setSize(0)

        reIn[0] = 1f
        imIn[0] = 1f
        for (i in 1..<reIn.size) {
            reIn[i] = 0f
            imIn[i] = 0f
        }

        val filter =
            ComplexFirFilter.createBandPass(1, 1f, sampleRate.toFloat(), 50f, 450f, 50f, 60f)
        println("Created filter with " + filter!!.numberOfTaps + " taps!")
    }

    companion object {
        private fun printReIm(re: FloatArray, im: FloatArray) {
            print("Re: [")
            for (i in re.indices) print(((re[i] * 1000).toInt() / 1000.0).toString() + " ")

            print("]\nIm: [")
            for (i in im.indices) print(((im[i] * 1000).toInt() / 1000.0).toString() + " ")

            println("]")
        }
    }
}