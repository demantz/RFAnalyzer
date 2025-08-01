package com.mantz_it.nativedsp

import android.util.Log
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

class NativeDsp {

    var window: FloatArray? = null
    var inputBuf: FloatArray? = null

    protected fun makeWindow(size: Int) {
        // Make a blackman window:
        // w(n)=0.42-0.5cos{(2*PI*n)/(N-1)}+0.08cos{(4*PI*n)/(N-1)};
        window = FloatArray(size)
        for (i in window!!.indices)
            window!![i] = (0.42 - 0.5 * cos(2 * Math.PI * i / (size - 1))
                + 0.08 * cos(4 * Math.PI * i / (size - 1))).toFloat()
    }

    /**
     * Native methods implemented by the 'nativedsp' native library,
     * performing a FFT with the pffft library. Not thread safe!
     */
    private external fun performFFT(input: FloatArray?, output: FloatArray?)
    private external fun performFFTAndLogMag(input: FloatArray?, output: FloatArray?)

    companion object {
        // Used to load the 'nativedsp' library on application startup.
        init {
            System.loadLibrary("nativedsp")
        }
    }

    /**
     * Applies a Blackman Window to the input samples, followed by a FFT operation.
     * Fills the array magOut with the logarithmic magnitude of the FFT results (centered around the 0-frequency)
     * IMPORTANT: This function uses native code. The native code is NOT thread safe (TODO) and therefore
     * this method is also not Thread safe!!
     */
    fun performWindowedFftAndReturnMag(re: FloatArray, im: FloatArray, magOut: FloatArray): Boolean {
        val N = re.size
        if(im.size != N || magOut.size != N)
            return false

        if(window == null || window!!.size != N)
            makeWindow(re.size)

        if(inputBuf == null || inputBuf!!.size != 2*N)
            inputBuf = FloatArray(2*N)

        // apply window
        for(i in 0..<N) {
            inputBuf!![2*i]   = re[i] * window!![i]
            inputBuf!![2*i+1] = im[i] * window!![i]
        }

        performFFTAndLogMag(inputBuf, magOut)
        return true
    }

}