package com.mantz_it.rfanalyzer

import android.app.Application
import android.test.ApplicationTestCase
import com.mantz_it.rfanalyzer.dsp.ComplexFirFilter
import com.mantz_it.rfanalyzer.dsp.FirFilter.Companion.createLowPass
import com.mantz_it.rfanalyzer.source.SamplePacket
import kotlin.math.cos
import kotlin.math.sin

/**
 * [Testing Fundamentals](http://d.android.com/tools/testing/testing_android.html)
 */
class ApplicationTest : ApplicationTestCase<Application?>(Application::class.java) {
    @Throws(Exception::class)
    public override fun setUp() {
        super.setUp()
    }

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

//        println("float[] reOutExpected = {")
//        for (i in 0..<out.size()) {
//        	print("${reOut[i]}f, ")
//        }
//        println("\n};")
//        println("float[] imOutExpected = {")
//        for (i in 0..<out.size()) {
//        	print("${imOut[i]}f, ")
//        }
//        println("\n};")
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

        //println("float[] reOutExpected = {")
        //for (i in 0..<out.size()) {
        //	print("${reOut[i]}f, ")
        //}
        //println("\n};")
        //println("float[] imOutExpected = {")
        //for (i in 0..<out.size()) {
        //	print("${imOut[i]}f, ")
        //}
        //println("\n};")

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

        // Assert that the filtering operation doesn't exceed a certain threshold (e.g., 650 ms on my Pixel 4a 5G on Android 12)
        // exact measures where around 624ms
        assertTrue("Filter operation took too long: " + durationMs + " ms", durationMs < 650)
    }


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
        println("Created filter with " + filter!!.getNumberOfTaps() + " taps!")

        //FFT fft1 = new FFT(samples);

        //System.out.println("Before FILTER:");
        //spectrum(fft1, reIn, imIn);

        //filter.filter(in, out, 0, in.size());

        //FFT fft2 = new FFT(samples);

        //System.out.println("After FILTER:");
        //spectrum(fft2, reOut, imOut);
    }

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
        println("Created filter with " + filter!!.getNumberOfTaps() + " taps!")

        //FFT fft1 = new FFT(samples);

        //System.out.println("Before FILTER:");
        //spectrum(fft1, reIn, imIn);

        //filter.filter(in, out, 0, in.size());

        //FFT fft2 = new FFT(samples);

        //System.out.println("After FILTER:");
        //spectrum(fft2, reOut, imOut);
    }

    companion object {
        //public void testFFT() throws Exception {
        //	// Test the FFT to make sure it's working
        //	int N = 8;
        //	FFT fft = new FFT(N);
        //	float[] window = fft.getWindow();
        //	float[] re = new float[N];
        //	float[] im = new float[N];
        //	// Impulse
        //	re[0] = 1; im[0] = 0;
        //	for(int i=1; i<N; i++)
        //		re[i] = im[i] = 0;
        //	beforeAfter(fft, re, im);
        //	// Nyquist
        //	for(int i=0; i<N; i++) {
        //		re[i] = (float)Math.pow(-1, i);
        //		im[i] = 0;
        //	}
        //	beforeAfter(fft, re, im);
        //	// Single sin
        //	for(int i=0; i<N; i++) {
        //		re[i] = (float)Math.cos(2*Math.PI*i / N);
        //		im[i] = 0;
        //	}
        //	beforeAfter(fft, re, im);
        //	// Ramp
        //	for(int i=0; i<N; i++) {
        //		re[i] = i;
        //		im[i] = 0;
        //	}
        //	beforeAfter(fft, re, im);
        //	long time = System.currentTimeMillis();
        //	double iter = 30000;
        //	for(int i=0; i<iter; i++)
        //		fft.fft(re,im);
        //	time = System.currentTimeMillis() - time;
        //	System.out.println("Averaged " + (time/iter) + "ms per iteration");
        //}
        //protected static void beforeAfter(FFT fft, float[] re, float[] im) {
        //	System.out.println("Before: ");
        //	printReIm(re, im);
        //	//fft.applyWindow(re, im);
        //	fft.fft(re, im);
        //	System.out.println("After: ");
        //	printReIm(re, im);
        //}
        protected fun printReIm(re: FloatArray, im: FloatArray) {
            print("Re: [")
            for (i in re.indices) print(((re[i] * 1000).toInt() / 1000.0).toString() + " ")

            print("]\nIm: [")
            for (i in im.indices) print(((im[i] * 1000).toInt() / 1000.0).toString() + " ")

            println("]")
        } //protected static void spectrum(FFT fft, float[] re, float[] im) {
        //	//fft.applyWindow(re, im);
        //	int length = re.length;
        //	float[] reDouble = new float[length];
        //	float[] imDouble = new float[length];
        //	float[] mag = new float[length];
        //	for (int i = 0; i < length; i++) {
        //		reDouble[i] = re[i];
        //		imDouble[i] = im[i];
        //	}
        //	fft.fft(reDouble, imDouble);
        //	// Calculate the logarithmic magnitude:
        //	for (int i = 0; i < length; i++) {
        //		// We have to flip both sides of the fft to draw it centered on the screen:
        //		int targetIndex = (i+length/2) % length;
        //		// Calc the magnitude = log(  re^2 + im^2  )
        //		// note that we still have to divide re and im by the fft size
        //		mag[targetIndex] = (float) Math.log(Math.pow(reDouble[i]/fft.n,2) + Math.pow(imDouble[i]/fft.n,2));
        //	}
        //	System.out.print("Spectrum: [");
        //	for (int i = 0; i < length; i++) {
        //		System.out.print(" " + (int) mag[i]);
        //	}
        //	System.out.println("]");
        //}
    }
}