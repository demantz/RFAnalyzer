/* ============================================================
   File: iq_word_mixer.c
   The per-sample mixing loop of Signed16BitIQConverter (NativeIQMixer16). Each NativeIQMixer16
   instance owns its native state (no static state here), so different instances may run on
   different threads.

   A precombined table like the 8-bit mixers' would be [tableLength][65536] here: instead a
   65536-entry value table (raw unsigned 16-bit sample -> float, set once by nativeSetValueTable)
   and a cos/sin NCO table (nativeSetNcoTable, when the mix frequency changes), multiplied per
   sample. Same operation order as the Kotlin fallback (plain multiply-then-subtract/add, no FMA),
   so the output is bit-identical - see CMakeLists.txt for the per-file flags this depends on
   (-fno-fast-math -ffp-contract=off).

   Both bytes are zero-extended before they are combined: the same low 16 bits as the Kotlin
   index ((lo and 0xFF) or (hi shl 8), masked to 0xFFFF), without left-shifting a negative value.
   ============================================================ */

#include <jni.h>
#include <stdlib.h>
#include <stdint.h>

typedef struct {
    float *valueTable;   // 65536-entry value LUT: raw unsigned 16-bit sample -> float
    float *cosTable;      // NCO cos array, length ncoLength
    float *sinTable;      // NCO sin array, length ncoLength
    int ncoLength;
    int cosineIndex;      // persists across nativeMix() calls
} IQMixer16Instance;

JNIEXPORT jlong JNICALL
Java_com_mantz_1it_nativedsp_NativeIQMixer16_nativeCreate(JNIEnv *env, jobject thiz) {
    IQMixer16Instance *inst = (IQMixer16Instance *) calloc(1, sizeof(IQMixer16Instance));
    return (jlong) inst;
}

JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeIQMixer16_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    if (!handle) return;
    IQMixer16Instance *inst = (IQMixer16Instance *) handle;
    if (inst->valueTable) free(inst->valueTable);
    if (inst->cosTable) free(inst->cosTable);
    if (inst->sinTable) free(inst->sinTable);
    free(inst);
}

// valueTable is Signed16BitIQConverter's 65536-entry lookupTable16 (unsigned 16-bit sample ->
// float), built once in its generateLookupTable().
JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeIQMixer16_nativeSetValueTable(
        JNIEnv *env, jobject thiz, jlong handle, jfloatArray valueTable) {
    if (!handle) return;
    IQMixer16Instance *inst = (IQMixer16Instance *) handle;
    if (inst->valueTable) free(inst->valueTable);
    inst->valueTable = NULL;

    jsize n = (*env)->GetArrayLength(env, valueTable);
    if (n <= 0) return;
    float *table = (float *) malloc((size_t) n * sizeof(float));
    if (!table) return;
    (*env)->GetFloatArrayRegion(env, valueTable, 0, n, table);
    inst->valueTable = table;
}

// cosArr/sinArr are the Kotlin-side per-time-step NCO arrays. Resets cosineIndex to 0, as
// Signed16BitIQConverter.generateMixerLookupTable() does when it regenerates them.
JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeIQMixer16_nativeSetNcoTable(
        JNIEnv *env, jobject thiz, jlong handle,
        jfloatArray cosArr, jfloatArray sinArr, jint ncoLength) {
    if (!handle) return;
    IQMixer16Instance *inst = (IQMixer16Instance *) handle;

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
// samples and always equal (4 input bytes per output sample).
JNIEXPORT jlong JNICALL
Java_com_mantz_1it_nativedsp_NativeIQMixer16_nativeMix(
        JNIEnv *env, jobject thiz, jlong handle,
        jbyteArray packet, jint offset, jint length,
        jfloatArray outRe, jfloatArray outIm, jint outOffset, jint outCapacity) {

    if (!handle) return 0;
    IQMixer16Instance *inst = (IQMixer16Instance *) handle;
    if (!inst->valueTable || inst->ncoLength == 0) return 0;

    int maxProduced = outCapacity - outOffset;
    if (maxProduced <= 0) return 0;

    jbyte *bufPacket = (jbyte *) malloc((size_t) length * sizeof(jbyte));
    float *bufOutRe = (float *) malloc((size_t) maxProduced * sizeof(float));
    float *bufOutIm = (float *) malloc((size_t) maxProduced * sizeof(float));
    if (!bufPacket || !bufOutRe || !bufOutIm) {
        if (bufPacket) free(bufPacket);
        if (bufOutRe) free(bufOutRe);
        if (bufOutIm) free(bufOutIm);
        return 0;
    }
    (*env)->GetByteArrayRegion(env, packet, offset, length, bufPacket);

    const float *valueTable = inst->valueTable;
    const float *cosTable = inst->cosTable;
    const float *sinTable = inst->sinTable;
    const int ncoLength = inst->ncoLength;
    int cosineIndex = inst->cosineIndex;
    if (cosineIndex >= ncoLength) cosineIndex = 0;

    int indexOut = 0;
    int count = 0;
    int i = 0;
    // Each complex sample: 4 bytes (Ilo, Ihi, Qlo, Qhi), little-endian.
    while (i + 3 < length && indexOut < maxProduced) {
        unsigned int iLo = (unsigned int) (uint8_t) bufPacket[i];
        unsigned int iHi = (unsigned int) (uint8_t) bufPacket[i + 1];
        unsigned int idxI = iLo | (iHi << 8);
        unsigned int qLo = (unsigned int) (uint8_t) bufPacket[i + 2];
        unsigned int qHi = (unsigned int) (uint8_t) bufPacket[i + 3];
        unsigned int idxQ = qLo | (qHi << 8);

        float iF = valueTable[idxI];
        float qF = valueTable[idxQ];
        float c = cosTable[cosineIndex];
        float s = sinTable[cosineIndex];

        // re = I*cos - Q*sin ; im = Q*cos + I*sin
        bufOutRe[indexOut] = iF * c - qF * s;
        bufOutIm[indexOut] = qF * c + iF * s;

        cosineIndex++;
        if (cosineIndex >= ncoLength) cosineIndex = 0;

        i += 4;
        indexOut++;
        count++;
    }

    inst->cosineIndex = cosineIndex;

    if (indexOut > 0) {
        (*env)->SetFloatArrayRegion(env, outRe, outOffset, indexOut, bufOutRe);
        (*env)->SetFloatArrayRegion(env, outIm, outOffset, indexOut, bufOutIm);
    }

    free(bufPacket);
    free(bufOutRe);
    free(bufOutIm);

    return (((jlong) count) << 32) | ((jlong) count & 0xFFFFFFFFLL);
}
