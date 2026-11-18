/* ============================================================
   File: iq_float_mixer.c
   The per-sample mixing loop of Float32IQConverter (NativeIQMixerFloat). Each NativeIQMixerFloat
   instance owns its native state (no static state here), so different instances may run on
   different threads.

   The input is little-endian CF32 (AirspyHF): no value table, only the cos/sin NCO table (as in
   iq_word_mixer.c). Same operation order as the Kotlin fallback (plain multiply-then-subtract/add,
   no FMA), so the output is bit-identical - see CMakeLists.txt for the per-file flags this depends
   on (-fno-fast-math -ffp-contract=off).

   readFloatLE() assembles each float from its 4 bytes as little-endian (as the Kotlin side's
   ByteBuffer.order(LITTLE_ENDIAN)) and copies the bits with memcpy: no unaligned pointer cast,
   no assumption about the host byte order.
   ============================================================ */

#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>

typedef struct {
    float *cosTable;      // NCO cos array, length ncoLength
    float *sinTable;      // NCO sin array, length ncoLength
    int ncoLength;
    int cosineIndex;      // persists across nativeMix() calls
} IQMixerFloatInstance;

static inline float readFloatLE(const jbyte *p) {
    uint32_t bits = ((uint32_t) (uint8_t) p[0])
                   | (((uint32_t) (uint8_t) p[1]) << 8)
                   | (((uint32_t) (uint8_t) p[2]) << 16)
                   | (((uint32_t) (uint8_t) p[3]) << 24);
    float f;
    memcpy(&f, &bits, sizeof(f));
    return f;
}

JNIEXPORT jlong JNICALL
Java_com_mantz_1it_nativedsp_NativeIQMixerFloat_nativeCreate(JNIEnv *env, jobject thiz) {
    IQMixerFloatInstance *inst = (IQMixerFloatInstance *) calloc(1, sizeof(IQMixerFloatInstance));
    return (jlong) inst;
}

JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeIQMixerFloat_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    if (!handle) return;
    IQMixerFloatInstance *inst = (IQMixerFloatInstance *) handle;
    if (inst->cosTable) free(inst->cosTable);
    if (inst->sinTable) free(inst->sinTable);
    free(inst);
}

// cosArr/sinArr are the Kotlin-side per-time-step NCO arrays. Resets cosineIndex to 0, as
// Float32IQConverter.generateMixerLookupTable() does when it regenerates them.
JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeIQMixerFloat_nativeSetNcoTable(
        JNIEnv *env, jobject thiz, jlong handle,
        jfloatArray cosArr, jfloatArray sinArr, jint ncoLength) {
    if (!handle) return;
    IQMixerFloatInstance *inst = (IQMixerFloatInstance *) handle;

    if (inst->cosTable) free(inst->cosTable);
    if (inst->sinTable) free(inst->sinTable);
    inst->cosTable = NULL;
    inst->sinTable = NULL;
    inst->ncoLength = 0;
    inst->cosineIndex = 0;

    if (ncoLength <= 0) return;

    float *cosTable = (float *) malloc((size_t) ncoLength * sizeof(float));
    float *sinTable = (float *) malloc((size_t) ncoLength * sizeof(float));
    if (!cosTable || !sinTable) {
        if (cosTable) free(cosTable);
        if (sinTable) free(sinTable);
        return;
    }
    (*env)->GetFloatArrayRegion(env, cosArr, 0, ncoLength, cosTable);
    (*env)->GetFloatArrayRegion(env, sinArr, 0, ncoLength, sinTable);

    inst->cosTable = cosTable;
    inst->sinTable = sinTable;
    inst->ncoLength = ncoLength;
}

// Returns a packed result: (produced << 32) | (consumed & 0xFFFFFFFF), both counted in complex
// samples and always equal (8 input bytes per output sample).
JNIEXPORT jlong JNICALL
Java_com_mantz_1it_nativedsp_NativeIQMixerFloat_nativeMix(
        JNIEnv *env, jobject thiz, jlong handle,
        jbyteArray packet, jint offset, jint length,
        jfloatArray outRe, jfloatArray outIm, jint outOffset, jint outCapacity) {

    if (!handle) return 0;
    IQMixerFloatInstance *inst = (IQMixerFloatInstance *) handle;
    if (inst->ncoLength == 0) return 0;

    int maxProduced = outCapacity - outOffset;
    if (maxProduced <= 0) return 0;

    // 8 bytes per complex sample (4-byte I float + 4-byte Q float), rounded down as the Kotlin
    // fallback's floatBuf.remaining() / 2
    int samplesAvailable = length / 8;
    if (samplesAvailable <= 0) return 0;
    int samplesToCopy = samplesAvailable < maxProduced ? samplesAvailable : maxProduced;

    jbyte *bufPacket = (jbyte *) malloc((size_t) length * sizeof(jbyte));
    float *bufOutRe = (float *) malloc((size_t) samplesToCopy * sizeof(float));
    float *bufOutIm = (float *) malloc((size_t) samplesToCopy * sizeof(float));
    if (!bufPacket || !bufOutRe || !bufOutIm) {
        if (bufPacket) free(bufPacket);
        if (bufOutRe) free(bufOutRe);
        if (bufOutIm) free(bufOutIm);
        return 0;
    }
    (*env)->GetByteArrayRegion(env, packet, offset, length, bufPacket);

    const float *cosTable = inst->cosTable;
    const float *sinTable = inst->sinTable;
    const int ncoLength = inst->ncoLength;
    int cosineIndex = inst->cosineIndex;
    if (cosineIndex >= ncoLength) cosineIndex = 0;

    for (int i = 0; i < samplesToCopy; i++) {
        const jbyte *p = bufPacket + (i * 8);
        float I = readFloatLE(p);
        float Q = readFloatLE(p + 4);

        float c = cosTable[cosineIndex];
        float s = sinTable[cosineIndex];

        // re = I*cos - Q*sin ; im = Q*cos + I*sin
        bufOutRe[i] = I * c - Q * s;
        bufOutIm[i] = Q * c + I * s;

        cosineIndex++;
        if (cosineIndex >= ncoLength) cosineIndex = 0;
    }

    inst->cosineIndex = cosineIndex;

    (*env)->SetFloatArrayRegion(env, outRe, outOffset, samplesToCopy, bufOutRe);
    (*env)->SetFloatArrayRegion(env, outIm, outOffset, samplesToCopy, bufOutIm);

    free(bufPacket);
    free(bufOutRe);
    free(bufOutIm);

    return (((jlong) samplesToCopy) << 32) | ((jlong) samplesToCopy & 0xFFFFFFFFLL);
}
