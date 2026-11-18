/* ============================================================
   File: rational_resampler.c
   A polyphase rational resampler (NativeRationalResampler). Each instance owns its
   native state (no static state here), so different instances may run on different
   threads.

   The loop of the Kotlin RationalResampler.resample() (app/src/main/java/.../dsp/
   RationalResampler.kt) in the same accumulation order (forward tap iteration,
   backward delay-line walk, plain multiply-then-add, no FMA), so the output is
   bit-identical - with the per-file flags of CMakeLists.txt (-fno-fast-math
   -ffp-contract=off), without which it would not match the JVM's float results.

   Like RationalResampler.kt, derived from GNU Radio's rational_resampler_ccf
   (Copyright (C) 2004-2012 Free Software Foundation, Inc.) and therefore licensed under the
   GNU General Public License version 3 or later.
   ============================================================ */

#include <jni.h>
#include <stdlib.h>

typedef struct {
    int interpolation;
    int decimation;
    int nt;             // taps per phase == delayReal/delayImag length
    float *firTaps;      // interpolation * nt, row-major: firTaps[phase*nt + i]
    float *delayReal;    // length nt
    float *delayImag;    // length nt
    int delayIndex;      // circular write pointer, persists across calls
    int ctr;             // phase/fractional-position accumulator, persists across calls
} ResamplerInstance;

JNIEXPORT jlong JNICALL
Java_com_mantz_1it_nativedsp_NativeRationalResampler_nativeCreate(
        JNIEnv *env, jobject thiz,
        jint interpolation, jint decimation,
        jfloatArray flatFirTaps, jint nt) {

    if (interpolation <= 0 || decimation <= 0 || nt <= 0) return 0;

    ResamplerInstance *inst = (ResamplerInstance *) malloc(sizeof(ResamplerInstance));
    if (!inst) return 0;

    inst->interpolation = interpolation;
    inst->decimation = decimation;
    inst->nt = nt;
    inst->delayIndex = 0;
    inst->ctr = 0;

    size_t tapsCount = (size_t) interpolation * (size_t) nt;
    inst->firTaps = (float *) malloc(tapsCount * sizeof(float));
    inst->delayReal = (float *) calloc((size_t) nt, sizeof(float));
    inst->delayImag = (float *) calloc((size_t) nt, sizeof(float));

    if (!inst->firTaps || !inst->delayReal || !inst->delayImag) {
        if (inst->firTaps) free(inst->firTaps);
        if (inst->delayReal) free(inst->delayReal);
        if (inst->delayImag) free(inst->delayImag);
        free(inst);
        return 0;
    }

    (*env)->GetFloatArrayRegion(env, flatFirTaps, 0, (jsize) tapsCount, inst->firTaps);

    return (jlong) inst;
}

JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeRationalResampler_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong handle) {

    if (!handle) return;
    ResamplerInstance *inst = (ResamplerInstance *) handle;
    free(inst->firTaps);
    free(inst->delayReal);
    free(inst->delayImag);
    free(inst);
}

// Returns a packed result: (produced << 32) | (consumed & 0xFFFFFFFF)
JNIEXPORT jlong JNICALL
Java_com_mantz_1it_nativedsp_NativeRationalResampler_nativeResample(
        JNIEnv *env, jobject thiz, jlong handle,
        jfloatArray inRe, jfloatArray inIm, jint offset, jint length,
        jfloatArray outRe, jfloatArray outIm, jint outOffset, jint outCapacity) {

    if (!handle) return 0;
    ResamplerInstance *inst = (ResamplerInstance *) handle;

    // Copy only the sub-ranges touched: [offset, offset+length) of the input, and up to
    // (outCapacity - outOffset) of the output. Assumes length >= 1, like the Kotlin version:
    // the delay-line push before the loop reads bufInRe[0] whatever `length` is.
    float *bufInRe = (float *) malloc((size_t) length * sizeof(float));
    float *bufInIm = (float *) malloc((size_t) length * sizeof(float));
    int maxProduced = outCapacity - outOffset;
    if (maxProduced < 0) maxProduced = 0;
    float *bufOutRe = (float *) malloc((size_t) maxProduced * sizeof(float));
    float *bufOutIm = (float *) malloc((size_t) maxProduced * sizeof(float));

    if (!bufInRe || !bufInIm || !bufOutRe || !bufOutIm) {
        if (bufInRe) free(bufInRe);
        if (bufInIm) free(bufInIm);
        if (bufOutRe) free(bufOutRe);
        if (bufOutIm) free(bufOutIm);
        return 0;
    }

    (*env)->GetFloatArrayRegion(env, inRe, offset, length, bufInRe);
    (*env)->GetFloatArrayRegion(env, inIm, offset, length, bufInIm);

    const int interpolation = inst->interpolation;
    const int decimation = inst->decimation;
    const int nt = inst->nt;
    float *delayReal = inst->delayReal;
    float *delayImag = inst->delayImag;
    int delayIndex = inst->delayIndex;
    int ctr = inst->ctr;

    int indexOut = 0;
    int consumed = 0;
    int inIdx = 0; // index into bufInRe/bufInIm, mirrors Kotlin's inIdx-offset

    delayReal[delayIndex] = bufInRe[inIdx];
    delayImag[delayIndex] = bufInIm[inIdx];

    // ctr might be still too high from a previous call:
    while (ctr >= interpolation) {
        ctr -= interpolation;
        inIdx++;
        delayIndex++;
        if (delayIndex >= nt) delayIndex = 0;
        consumed++;
        if (consumed >= length) {
            break;
        } else {
            delayReal[delayIndex] = bufInRe[inIdx];
            delayImag[delayIndex] = bufInIm[inIdx];
        }
    }

    while (consumed < length && indexOut < maxProduced) {
        float reSum = 0.0f;
        float imSum = 0.0f;
        const float *taps = &inst->firTaps[ctr * nt];
        int di = delayIndex;
        for (int t = 0; t < nt; t++) {
            reSum += taps[t] * delayReal[di];
            imSum += taps[t] * delayImag[di];
            di--;
            if (di < 0) di = nt - 1;
        }
        bufOutRe[indexOut] = reSum;
        bufOutIm[indexOut] = imSum;
        indexOut++;

        ctr += decimation;
        while (ctr >= interpolation) {
            ctr -= interpolation;
            inIdx++;
            delayIndex++;
            if (delayIndex >= nt) delayIndex = 0;
            consumed++;
            if (consumed >= length) {
                break;
            } else {
                delayReal[delayIndex] = bufInRe[inIdx];
                delayImag[delayIndex] = bufInIm[inIdx];
            }
        }
    }

    inst->delayIndex = delayIndex;
    inst->ctr = ctr;

    if (indexOut > 0) {
        (*env)->SetFloatArrayRegion(env, outRe, outOffset, indexOut, bufOutRe);
        (*env)->SetFloatArrayRegion(env, outIm, outOffset, indexOut, bufOutIm);
    }

    free(bufInRe);
    free(bufInIm);
    free(bufOutRe);
    free(bufOutIm);

    return (((jlong) indexOut) << 32) | ((jlong) consumed & 0xFFFFFFFFLL);
}
