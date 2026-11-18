#include <jni.h>
#include <string>
#include <android/log.h>
#include <mutex>
#include <cmath>
#include <cstring> // memcpy, memset
#include <algorithm>
#include "pffft.h"

#define LOG_TAG "nativedsp"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Error codes returned as negative integers from nativePerformWindowedFftAndReturnMag:
static const int ERR_NOT_INITIALIZED = -1;
static const int ERR_MAG_LENGTH_MISMATCH = -2;
static const int ERR_INVALID_HOP = -3;
static const int ERR_INTERNAL = -4;
static const int ERR_INVALID_INPUT = -5;
static const int ERR_NOT_ENOUGH_SAMPLES = -6; // fewer than M samples buffered: no frame computed

// Smallest power considered when converting to dB (-200 dB), avoids log10(0) = -inf
static constexpr float POWER_EPSILON = 1e-20f;

// One spectrum FFT pipeline (FftProcessor owns one). Each instance has its own state, so an old
// FftProcessor that is still shutting down cannot touch the buffers of its successor.
struct FftState {
    std::mutex mutex;

    // FFT parameters & pffft buffers:
    int fftComplexSize = 0;     // N = number of complex samples for the FFT (transform size / bin count)
    int analysisWindowSize = 0; // M <= N = number of buffered samples copied+windowed per frame (N-M is zero-padded)
    PFFFT_Setup* setup = nullptr;
    float* scratch = nullptr;   // scratch buffer (aligned)
    float* fftInput = nullptr;  // interleaved complex input (2*N floats)
    float* fftOutput = nullptr; // interleaved complex output (2*N floats)
    float* window = nullptr;    // blackman window (M floats)
    uint8_t* magBytes = nullptr; // N bytes, quantized dB output before the copy to Java
    float dbOffset = 0;         // -20*log10(sum(window)): a full-scale complex tone reads 0 dBFS

    // Circular buffer (interleaved complex floats)
    float* circBuf = nullptr;   // size = 2 * bufCapComplex floats
    int bufCapComplex = 0;      // capacity (complex samples)
    int writeIndex = 0;         // next write position (complex index)
    int readIndex = 0;          // read position for FFT (complex index)
    int samplesAvailable = 0;   // number of complex samples currently stored
};

static void freeFftBuffers(FftState* s) {
    if (s->setup) { pffft_destroy_setup(s->setup); s->setup = nullptr; }
    if (s->scratch) { pffft_aligned_free(s->scratch); s->scratch = nullptr; }
    if (s->fftInput) { pffft_aligned_free(s->fftInput); s->fftInput = nullptr; }
    if (s->fftOutput) { pffft_aligned_free(s->fftOutput); s->fftOutput = nullptr; }
    if (s->window) { free(s->window); s->window = nullptr; }
    if (s->magBytes) { free(s->magBytes); s->magBytes = nullptr; }
    s->fftComplexSize = 0;
    s->analysisWindowSize = 0;
}

static void freeAll(FftState* s) {
    freeFftBuffers(s);
    if (s->circBuf) { pffft_aligned_free(s->circBuf); s->circBuf = nullptr; }
    s->bufCapComplex = 0;
    s->writeIndex = s->readIndex = s->samplesAvailable = 0;
}

// (Re-)creates the pffft setup, FFT buffers and the Blackman window of length M for transform size N.
static bool allocFftBuffers(FftState* s, int N, int M) {
    freeFftBuffers(s);
    s->setup = pffft_new_setup(N, PFFFT_COMPLEX);
    if (!s->setup) {
        LOGE("pffft_new_setup failed for size %d", N);
        return false;
    }
    int floatLen = 2 * N;
    s->scratch = (float*) pffft_aligned_malloc(sizeof(float) * floatLen);
    s->fftInput = (float*) pffft_aligned_malloc(sizeof(float) * floatLen);
    s->fftOutput = (float*) pffft_aligned_malloc(sizeof(float) * floatLen);
    s->window = (float*) malloc(sizeof(float) * M);
    s->magBytes = (uint8_t*) malloc(N);
    if (!s->scratch || !s->fftInput || !s->fftOutput || !s->window || !s->magBytes) {
        LOGE("FFT buffer allocation failed for size %d", N);
        freeFftBuffers(s);
        return false;
    }
    double windowSum = 0;
    for (int n = 0; n < M; ++n) {
        double a = M > 1 ? 2.0 * M_PI * n / double(M - 1) : 0.0;
        double w = 0.42 - 0.5 * cos(a) + 0.08 * cos(2.0 * a);
        s->window[n] = (float) w;
        windowSum += w;
    }
    s->dbOffset = (float) (-20.0 * log10(std::max(windowSum, 1e-9)));
    s->fftComplexSize = N;
    s->analysisWindowSize = M;
    return true;
}

// log2(x) for x > 0 from the float's exponent and a quadratic fit of the mantissa (max error
// 0.005, i.e. 0.015 dB - far below the 0.63 dB quantization of the output). libm's log10f would be the
// largest cost of the spectrum FFT (one call per bin and frame).
static inline float fastLog2(float x) {
    union { float f; uint32_t i; } u = { x };
    float exponent = (float) (int) ((u.i >> 23) & 0xFF) - 127.0f;
    u.i = (u.i & 0x007FFFFFu) | 0x3F800000u; // mantissa in [1, 2)
    float m = u.f;
    return exponent + (-0.34484843f * m + 2.02466578f) * m - 0.67487759f;
}

// out[i] = clamp(a * log2(|X_i|^2) + b, 0, 255) for count interleaved complex values
static void quantizePower(const float* in, uint8_t* out, int count, float a, float b) {
    for (int i = 0; i < count; ++i) {
        float re = in[2*i];
        float im = in[2*i + 1];
        float v = a * fastLog2(re*re + im*im + POWER_EPSILON) + b;
        v = v < 0.0f ? 0.0f : (v > 255.0f ? 255.0f : v);
        out[i] = (uint8_t) v;
    }
}

static inline FftState* stateOf(jlong handle) {
    return reinterpret_cast<FftState*>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_nativeInit(JNIEnv* /*env*/, jobject /*this*/,
                                                  jint fftSizeComplex, jint bufferCapacityComplex, jint analysisWindowSizeComplex) {
    if (fftSizeComplex <= 0 || bufferCapacityComplex < fftSizeComplex || analysisWindowSizeComplex <= 0 || analysisWindowSizeComplex > fftSizeComplex) {
        LOGE("nativeInit: invalid sizes fft=%d buf=%d analysisWindow=%d", fftSizeComplex, bufferCapacityComplex, analysisWindowSizeComplex);
        return 0;
    }
    auto* s = new FftState();
    if (!allocFftBuffers(s, fftSizeComplex, analysisWindowSizeComplex)) {
        delete s;
        return 0;
    }
    s->bufCapComplex = bufferCapacityComplex;
    s->circBuf = (float*) pffft_aligned_malloc(sizeof(float) * 2 * s->bufCapComplex);
    if (!s->circBuf) {
        LOGE("circular buffer allocation failed");
        freeAll(s);
        delete s;
        return 0;
    }
    memset(s->circBuf, 0, sizeof(float) * 2 * s->bufCapComplex);
    LOGI("nativeInit done fft=%d buf=%d analysisWindow=%d", s->fftComplexSize, s->bufCapComplex, s->analysisWindowSize);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_nativeRelease(JNIEnv* /*env*/, jobject /*this*/, jlong handle) {
    FftState* s = stateOf(handle);
    if (!s) return;
    {
        std::lock_guard<std::mutex> lock(s->mutex);
        freeAll(s);
    }
    delete s;
    LOGI("nativeRelease done");
}

/**
 * Set FFT size (and analysis window size) at runtime without destroying ring buffer contents.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_nativeSetFftSize(JNIEnv* /*env*/, jobject /*this*/, jlong handle,
                                                        jint newFftSize, jint newAnalysisWindowSize) {
    FftState* s = stateOf(handle);
    if (!s) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(s->mutex);

    if (newFftSize <= 0 || newAnalysisWindowSize <= 0) {
        LOGE("nativeSetFftSize: invalid newFftSize %d analysisWindow=%d", newFftSize, newAnalysisWindowSize);
        return JNI_FALSE;
    }
    // Defensive clamp: analysis window can never exceed the transform size.
    newAnalysisWindowSize = std::min(newAnalysisWindowSize, newFftSize);
    if (newFftSize == s->fftComplexSize && newAnalysisWindowSize == s->analysisWindowSize) return JNI_TRUE;
    if (s->bufCapComplex < newFftSize) {
        LOGE("Buffer Capacity < newFftSize: %d < %d", s->bufCapComplex, newFftSize);
        return JNI_FALSE;
    }
    if (!allocFftBuffers(s, newFftSize, newAnalysisWindowSize)) return JNI_FALSE;
    LOGI("nativeSetFftSize done newSize=%d bufCap=%d analysisWindow=%d", s->fftComplexSize, s->bufCapComplex, s->analysisWindowSize);
    return JNI_TRUE;
}

/**
 * Append the first floatCount floats (interleaved complex samples) of interleavedArray to the
 * circular buffer. Returns the number of buffered complex samples afterwards.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_nativeAddNewSamples(JNIEnv* env, jobject /*this*/, jlong handle,
                                                           jfloatArray interleavedArray, jint floatCount) {
    FftState* s = stateOf(handle);
    if (!s) return ERR_NOT_INITIALIZED;
    if (!interleavedArray) return ERR_INVALID_INPUT;

    // GetPrimitiveArrayCritical is faster than GetFloatArrayElements
    // because it prevents the GC from moving the array.
    jsize totalFloats = std::min(env->GetArrayLength(interleavedArray), floatCount);
    jfloat* srcPtr = (jfloat*) env->GetPrimitiveArrayCritical(interleavedArray, nullptr);
    if (!srcPtr) return ERR_INTERNAL;

    int samplesToAppend = (int)(totalFloats / 2); // 2 floats per complex sample
    int available;
    {
        std::lock_guard<std::mutex> lock(s->mutex);

        if (!s->circBuf || s->bufCapComplex <= 0) {
            env->ReleasePrimitiveArrayCritical(interleavedArray, srcPtr, JNI_ABORT);
            return ERR_NOT_INITIALIZED;
        }

        int written = 0;
        while (written < samplesToAppend) {
            int maxContig = s->bufCapComplex - s->writeIndex;
            int cnt = std::min(maxContig, samplesToAppend - written);

            memcpy(s->circBuf + (s->writeIndex * 2), srcPtr + (written * 2), sizeof(float) * 2 * cnt);
            s->writeIndex = (s->writeIndex + cnt) % s->bufCapComplex;

            // Manage samplesAvailable and readIndex (overwriting logic)
            if (s->samplesAvailable + cnt <= s->bufCapComplex) {
                s->samplesAvailable += cnt;
            } else {
                int overwritten = (s->samplesAvailable + cnt) - s->bufCapComplex;
                s->readIndex = (s->readIndex + overwritten) % s->bufCapComplex;
                s->samplesAvailable = s->bufCapComplex;
            }
            written += cnt;
        }
        available = s->samplesAvailable;
    }

    // JNI_ABORT is used because we didn't modify the source array.
    env->ReleasePrimitiveArrayCritical(interleavedArray, srcPtr, JNI_ABORT);
    return available;
}

/**
 * Perform windowed FFT of transform size N, analyzing M <= N samples starting at read index.
 *
 * Behavior:
 *  - Needs M buffered samples; with fewer, nothing is computed (ERR_NOT_ENOUGH_SAMPLES) - a
 *    partially filled window would produce a distorted, too weak frame.
 *  - Applies the Blackman window (length M) and zero-pads to N (the configured FFT padding).
 *  - Runs PFFFT (ordered complex transform) of size N.
 *  - Writes the power per bin in dBFS (20*log10(|X| / sum(window)): a full-scale complex tone
 *    reads 0 dB whatever N and M are), centered (DC in the middle), quantized to a byte:
 *    byte = (dB - minDb) * bytesPerDb, clamped to 0..255.
 *  - Advances the read index by min(numberOfConsumedSamples, available samples).
 *
 * Returns:
 *  - >= 0 number of complex samples advanced (consumed)
 *  - negative error code on failure
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_nativePerformWindowedFftAndReturnMag(JNIEnv* env, jobject /*this*/, jlong handle,
                                                                            jbyteArray magOutArray, jfloat minDb, jfloat bytesPerDb,
                                                                            jint numberOfConsumedSamples) {
    FftState* s = stateOf(handle);
    if (!s || !magOutArray) return ERR_NOT_INITIALIZED;
    if (numberOfConsumedSamples <= 0) return ERR_INVALID_HOP;

    // FFT buffers, window and sizes belong to the owning thread (setFftSize is called from it as
    // well); only the ring buffer is shared with the producer, so the lock covers just the copy.
    if (!s->setup || !s->circBuf || !s->window) {
        LOGE("nativePerformWindowedFftAndReturnMag: not initialized");
        return ERR_NOT_INITIALIZED;
    }

    const int N = s->fftComplexSize;
    const int M = s->analysisWindowSize;
    if (N <= 0 || M <= 0) return ERR_INTERNAL;

    jsize magLen = env->GetArrayLength(magOutArray);
    if (magLen != N) {
        LOGE("magOut length mismatch: %d != %d", (int)magLen, N);
        return ERR_MAG_LENGTH_MISMATCH;
    }

    int actualConsumed;
    {
        std::lock_guard<std::mutex> lock(s->mutex);
        const int availableBefore = s->samplesAvailable;
        if (availableBefore < M) return ERR_NOT_ENOUGH_SAMPLES;

        // copy M complex samples (the analysis window) from the ring buffer (in up to two blocks)
        int firstBlock = std::min(M, s->bufCapComplex - s->readIndex);
        memcpy(s->fftInput, s->circBuf + (s->readIndex * 2), sizeof(float) * 2 * firstBlock);
        if (M > firstBlock) {
            memcpy(s->fftInput + (2 * firstBlock), s->circBuf, sizeof(float) * 2 * (M - firstBlock));
        }

        actualConsumed = std::min((int) numberOfConsumedSamples, availableBefore);
        s->readIndex = (s->readIndex + actualConsumed) % s->bufCapComplex;
        s->samplesAvailable -= actualConsumed;
    }

    // zero-pad the remaining N-M samples (the configured FFT padding)
    if (M < N) {
        memset(s->fftInput + 2 * M, 0, sizeof(float) * 2 * (N - M));
    }

    // Apply window in-place to the M-sample analysis window only
    const float* window = s->window;
    float* in = s->fftInput;
    for (int n = 0; n < M; ++n) {
        float w = window[n];
        in[2*n]   *= w;
        in[2*n+1] *= w;
    }

    pffft_transform_ordered(s->setup, s->fftInput, s->fftOutput, s->scratch, PFFFT_FORWARD);

    // Power in dBFS (center 0 freq), quantized: dB = 10*log10(power) + dbOffset = 3.0103*log2(power) + dbOffset
    // Two contiguous halves instead of a per-bin shifted index, so the loop vectorizes.
    const float a = 3.0102999566f * bytesPerDb;
    const float b = (s->dbOffset - minDb) * bytesPerDb;
    const int halfN = N / 2;
    quantizePower(s->fftOutput + 2 * halfN, s->magBytes, N - halfN, a, b);  // negative frequencies first
    quantizePower(s->fftOutput, s->magBytes + (N - halfN), halfN, a, b);
    const uint8_t* mag = s->magBytes;

    env->SetByteArrayRegion(magOutArray, 0, N, reinterpret_cast<const jbyte*>(mag));
    return actualConsumed;
}
