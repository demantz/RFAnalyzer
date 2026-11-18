/* ============================================================
   File: iq_byte_mixer_unsigned.c
   The per-sample mixing loop of Unsigned8BitIQConverter (NativeIQMixerUnsigned). Identical to
   iq_byte_mixer.c except for the table index: byte & 0xFF (unsigned) instead of byte + 128
   (signed) - different mappings, e.g. (byte)-128 gives 128 here and 0 there.

   Same operation order as ByteIQConverter's Kotlin fallback (plain subtract-then-add, no FMA), so
   the output is bit-identical - see CMakeLists.txt for the per-file flags this depends on
   (-fno-fast-math -ffp-contract=off). The [tableLength][256] mixer tables are built on the Kotlin
   side and copied here by nativeSetMixerTable().
   ============================================================ */

#include <jni.h>
#include <stdlib.h>

typedef struct {
    int tableLength;
    float *realTable;   // flattened [tableLength][256]
    float *imagTable;   // flattened [tableLength][256]
    int cosineIndex;    // persists across nativeMix() calls
} IQMixerUnsignedInstance;

JNIEXPORT jlong JNICALL
Java_com_mantz_1it_nativedsp_NativeIQMixerUnsigned_nativeCreate(JNIEnv *env, jobject thiz) {
    IQMixerUnsignedInstance *inst = (IQMixerUnsignedInstance *) calloc(1, sizeof(IQMixerUnsignedInstance));
    return (jlong) inst;
}

JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeIQMixerUnsigned_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    if (!handle) return;
    IQMixerUnsignedInstance *inst = (IQMixerUnsignedInstance *) handle;
    if (inst->realTable) free(inst->realTable);
    if (inst->imagTable) free(inst->imagTable);
    free(inst);
}

// realTableFlat/imagTableFlat are the Kotlin-side [tableLength][256] tables flattened
// row-major (index = row*256 + col). Resets cosineIndex to 0, as
// ByteIQConverter.generateMixerLookupTable() does when it regenerates them.
JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeIQMixerUnsigned_nativeSetMixerTable(
        JNIEnv *env, jobject thiz, jlong handle,
        jfloatArray realTableFlat, jfloatArray imagTableFlat, jint tableLength) {

    if (!handle) return;
    IQMixerUnsignedInstance *inst = (IQMixerUnsignedInstance *) handle;

    if (inst->realTable) free(inst->realTable);
    if (inst->imagTable) free(inst->imagTable);
    inst->realTable = NULL;
    inst->imagTable = NULL;
    inst->tableLength = 0;
    inst->cosineIndex = 0;

    if (tableLength <= 0) return;

    size_t n = (size_t) tableLength * 256;
    float *realTable = (float *) malloc(n * sizeof(float));
    float *imagTable = (float *) malloc(n * sizeof(float));
    if (!realTable || !imagTable) {
        if (realTable) free(realTable);
        if (imagTable) free(imagTable);
        return;
    }
    (*env)->GetFloatArrayRegion(env, realTableFlat, 0, (jsize) n, realTable);
    (*env)->GetFloatArrayRegion(env, imagTableFlat, 0, (jsize) n, imagTable);

    inst->realTable = realTable;
    inst->imagTable = imagTable;
    inst->tableLength = tableLength;
}

// Returns a packed result: (produced << 32) | (consumed & 0xFFFFFFFF), both counted in complex
// samples (not bytes) and always equal.
JNIEXPORT jlong JNICALL
Java_com_mantz_1it_nativedsp_NativeIQMixerUnsigned_nativeMix(
        JNIEnv *env, jobject thiz, jlong handle,
        jbyteArray packet, jint offset, jint length,
        jfloatArray outRe, jfloatArray outIm, jint outOffset, jint outCapacity) {

    if (!handle) return 0;
    IQMixerUnsignedInstance *inst = (IQMixerUnsignedInstance *) handle;
    if (inst->tableLength == 0) return 0;

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

    const int tableLength = inst->tableLength;
    const float *realTable = inst->realTable;
    const float *imagTable = inst->imagTable;
    int cosineIndex = inst->cosineIndex;

    int indexOut = 0;
    int count = 0;
    for (int i = 0; i + 1 < length; i += 2) {
        // Unsigned interpretation: byte & 0xFF (not signed byte + 128, see file header comment).
        int idxI = ((int) bufPacket[i]) & 0xFF;
        int idxQ = ((int) bufPacket[i + 1]) & 0xFF;
        const float *row = realTable + (size_t) cosineIndex * 256;
        const float *rowImag = imagTable + (size_t) cosineIndex * 256;
        float realI = row[idxI];
        float imagI = rowImag[idxI];
        float realQ = row[idxQ];
        float imagQ = rowImag[idxQ];
        bufOutRe[indexOut] = realI - imagQ;
        bufOutIm[indexOut] = realQ + imagI;
        cosineIndex = cosineIndex + 1;
        if (cosineIndex >= tableLength) cosineIndex = 0;
        indexOut++;
        count++;
        if (indexOut >= maxProduced) break;
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
