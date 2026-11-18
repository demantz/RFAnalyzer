/* ============================================================
   File: iq_byte_convert.c
   8/16-bit IQ samples -> floats: the spectrum FFT feed of Signed8BitIQConverter,
   Unsigned8BitIQConverter and Signed16BitIQConverter (fillPacketIntoInterleavedBuffer). Computes
   the same expressions their lookup tables are built from - signed 8 bit: b / 128, unsigned
   (RTL-SDR): (u - 127.4) / 128, signed 16 bit little endian: s / 32768 - so the result is
   bit-identical (compiled without -ffast-math, see CMakeLists.txt), but vectorizes (a table
   lookup per value does not).
   ============================================================ */

#include <jni.h>
#include <stdint.h>

JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeByteConverter_nativeToFloats(
        JNIEnv *env, jclass clazz, jbyteArray packet, jint count, jboolean isUnsigned, jfloatArray out) {
    if (count <= 0) return;
    jbyte *in = (*env)->GetPrimitiveArrayCritical(env, packet, NULL);
    jfloat *dst = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (in && dst) {
        if (isUnsigned) {
            const uint8_t *u = (const uint8_t *) in;
            for (jint i = 0; i < count; i++) dst[i] = ((float) u[i] - 127.4f) / 128.0f;
        } else {
            for (jint i = 0; i < count; i++) dst[i] = (float) in[i] / 128.0f;
        }
    }
    if (dst) (*env)->ReleasePrimitiveArrayCritical(env, out, dst, 0);
    if (in) (*env)->ReleasePrimitiveArrayCritical(env, packet, in, JNI_ABORT);
}

// count: number of int16 values (little endian, 2 bytes each) at the start of packet
JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeByteConverter_nativeS16ToFloats(
        JNIEnv *env, jclass clazz, jbyteArray packet, jint count, jfloatArray out) {
    if (count <= 0) return;
    jbyte *in = (*env)->GetPrimitiveArrayCritical(env, packet, NULL);
    jfloat *dst = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (in && dst) {
        const uint8_t *b = (const uint8_t *) in;
        for (jint i = 0; i < count; i++)
            dst[i] = (float) (int16_t) (b[2*i] | (b[2*i + 1] << 8)) / 32768.0f;
    }
    if (dst) (*env)->ReleasePrimitiveArrayCritical(env, out, dst, 0);
    if (in) (*env)->ReleasePrimitiveArrayCritical(env, packet, in, JNI_ABORT);
}
