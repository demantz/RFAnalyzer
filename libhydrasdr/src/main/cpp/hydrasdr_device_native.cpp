#include <jni.h>
#include <string>
#include <android/log.h>
#include <libusb.h>
#include "libhydrasdr/hydrasdr.h"

/**
 * <h1>RF Analyzer - hydrasdr device native code</h1>
 *
 * Module:      hydrasdr_device_native.cpp
 * Description: The native jni code which is used by HydraSdrDevice.kt
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

#define LOG_TAG "NativeLibHydraSdr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
// Globals
// ============================================================
static JavaVM *g_vm = NULL;
static jobject g_hydrasdrDeviceObj = NULL;
static jmethodID g_getEmptyBufferMethod = NULL;
static jmethodID g_onSamplesReadyMethod = NULL;

// ============================================================
// JNI: Cache JavaVM
// ============================================================
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// Helper to cast long to hydrasdr_device*
static inline struct hydrasdr_device* get_device_ptr(jlong nativePtr) {
    return reinterpret_cast<struct hydrasdr_device*>(nativePtr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_getLibraryVersionString(
        JNIEnv* env,
        jclass) {
    hydrasdr_lib_version_t version;
    hydrasdr_lib_version(&version);
    const libusb_version* usb_version = libusb_get_version();

    char version_str[128];
    snprintf(version_str, sizeof(version_str), "HydraSdr Version: %d.%d.%d (Libusb Version: %d.%d.%d.%d%s)",
             version.major_version, version.minor_version, version.revision,
             usb_version->major, usb_version->minor, usb_version->micro, usb_version->nano,
             usb_version->rc ? usb_version->rc : "");
    return env->NewStringUTF(version_str);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeOpenFd(
        JNIEnv* env,
        jclass /* clazz */, // For static methods, it's jclass
        jint fd) {
    struct hydrasdr_device* device = nullptr;
    LOGI("Attempting to open HydraSdr device with fd: %d", fd);
    int result = hydrasdr_open_fd(&device, fd);

    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to open HydraSdr device, error: %d", result);
        return result;
    }
    LOGI("HydraSdr device opened successfully, pointer: %p", device);

    // set sample type to signed 16-bit IQ samples
    hydrasdr_set_sample_type(device, HYDRASDR_SAMPLE_INT16_IQ);

    return reinterpret_cast<jlong>(device);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeVersionStringRead(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeVersionStringRead: Invalid native pointer");
        return nullptr;
    }

    char version[128];
    int result = hydrasdr_version_string_read(device, reinterpret_cast<char *>(&version), sizeof(version));
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to read version string, error: %d", result);
        return nullptr;
    }
    return env->NewStringUTF(version);
}


extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeClose(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeClose: Invalid native pointer or device already closed");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }
    LOGI("Closing HydraSdr device, pointer: %p", device);
    int result = hydrasdr_close(device);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to close HydraSdr device, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeIsStreaming(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeIsStreaming: Invalid native pointer");
        return JNI_FALSE;
    }

    int streaming_status = hydrasdr_is_streaming(device);
    return streaming_status ? JNI_TRUE : JNI_FALSE;
}


extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeSetLnaGain(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint value) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetLnaGain: Invalid native pointer");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }
    uint8_t gain_value = static_cast<uint8_t>(value);
    LOGI("Setting LNA gain to %d for device %p", gain_value, device);
    int result = hydrasdr_set_lna_gain(device, gain_value);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to set LNA gain, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeSetMixerGain(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint value) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetMixerGain: Invalid native pointer");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }
    uint8_t gain_value = static_cast<uint8_t>(value);
    LOGI("Setting Mixer gain to %d for device %p", gain_value, device);
    int result = hydrasdr_set_mixer_gain(device, gain_value);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to set Mixer gain, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeSetLinearityGain(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint value) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetLinearityGain: Invalid native pointer");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }
    uint8_t gain_value = static_cast<uint8_t>(value);
    LOGI("Setting Linearity gain to %d for device %p", gain_value, device);
    int result = hydrasdr_set_linearity_gain(device, gain_value);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to set Linearity gain, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeSetSensitivityGain(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint value) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetSensitivityGain: Invalid native pointer");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }
    uint8_t gain_value = static_cast<uint8_t>(value);
    LOGI("Setting Sensitivity gain to %d for device %p", gain_value, device);
    int result = hydrasdr_set_sensitivity_gain(device, gain_value);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to set Sensitivity gain, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeSetVgaGain(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint value) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetVgaGain: Invalid native pointer");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }
    uint8_t gain_value = static_cast<uint8_t>(value);
    LOGI("Setting VGA gain to %d for device %p", gain_value, device);
    int result = hydrasdr_set_vga_gain(device, gain_value);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to set VGA gain, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeSetSampleRate(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint samplerate) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetSampleRate: Invalid native pointer");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }
    uint32_t rate = static_cast<uint32_t>(samplerate);
    LOGI("Setting sample rate to %u for device %p", rate, device);
    int result = hydrasdr_set_samplerate(device, rate);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to set sample rate, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeSetFrequency(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint freq_hz) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetFrequency: Invalid native pointer");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }
    uint32_t freq = static_cast<uint32_t>(freq_hz);
    LOGI("Setting frequency to %u Hz for device %p", freq, device);
    int result = hydrasdr_set_freq(device, freq);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to set frequency, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeSetRfBias(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jboolean value) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetRfBias: Invalid native pointer");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }
    uint8_t bias_value = (value == JNI_TRUE) ? 1 : 0;
    LOGI("Setting RF bias to %d for device %p", bias_value, device);
    int result = hydrasdr_set_rf_bias(device, bias_value);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to set RF bias, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeSetRfPort(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint port) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetRfPort: Invalid native pointer");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }
    auto rf_port = static_cast<hydrasdr_rf_port_t>(port);
    LOGI("Setting RF port to %d for device %p", rf_port, device);
    int result = hydrasdr_set_rf_port(device, rf_port);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to set RF port, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeGetSamplerates(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jobject list_samplerates) { // Pass a mutableListOf<Int> object
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeGetSamplerates: Invalid native pointer");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }

    uint32_t num_samplerates = 0;
    // First call to get the number of available sample rates
    int result = hydrasdr_get_samplerates(device, &num_samplerates, 0);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to get number of samplerates, error: %d", result);
        return result;
    }

    if (num_samplerates == 0) {
        LOGI("No samplerates available for device %p", device);
        return HYDRASDR_SUCCESS; // No error, just no sample rates
    }

    // Allocate a temporary buffer to hold the sample rates
    auto* rates_buffer = new uint32_t[num_samplerates];

    result = hydrasdr_get_samplerates(device, rates_buffer, num_samplerates);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to get samplerates, error: %d", result);
        delete[] rates_buffer;
        return result;
    }

    // Get the List.add method
    jclass listClass = env->GetObjectClass(list_samplerates);
    jmethodID addMethod = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID integerConstructor = env->GetMethodID(integerClass, "<init>", "(I)V");

    for (uint32_t i = 0; i < num_samplerates; ++i) {
        jobject rate_obj = env->NewObject(integerClass, integerConstructor, static_cast<jint>(rates_buffer[i]));
        env->CallBooleanMethod(list_samplerates, addMethod, rate_obj);
        env->DeleteLocalRef(rate_obj); // Avoid local reference table overflow
    }

    delete[] rates_buffer;
    env->DeleteLocalRef(listClass);
    env->DeleteLocalRef(integerClass);

    return HYDRASDR_SUCCESS;
}


// ============================================================
// HydraSdr RX callback (runs on HydraSdr’s thread)
// ============================================================
static int hydrasdr_callback(hydrasdr_transfer *transfer) {
    JNIEnv *env = nullptr;

    if (g_hydrasdrDeviceObj == nullptr) {
        LOGE("hydrasdr_callback: g_hydrasdrDeviceObj is null");
        return 0;
    }

    if (g_vm->AttachCurrentThread(&env, nullptr) != 0) {
        LOGE("hydrasdr_callback: Failed to attach HydraSdr thread to JVM");
        return 0;
    }

    // Request an empty buffer from Kotlin (blocks if none free)
    jbyteArray buffer = (jbyteArray)env->CallObjectMethod(g_hydrasdrDeviceObj, g_getEmptyBufferMethod);

    // Copy samples into buffer
    jbyte *buf_ptr = env->GetByteArrayElements(buffer, nullptr);
    memcpy(buf_ptr, transfer->samples, transfer->sample_count * sizeof(int16_t) * 2);
    env->ReleaseByteArrayElements(buffer, buf_ptr, 0);

    // Notify Kotlin that samples are ready
    env->CallVoidMethod(g_hydrasdrDeviceObj, g_onSamplesReadyMethod, buffer);

    // Release local ref to avoid leaks
    env->DeleteLocalRef(buffer);

    return 0;
}


// ============================================================
// JNI: Start HydraSdr
// ============================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeStartRX(JNIEnv *env, jobject thiz, jlong nativePtr) {

    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeStartRX: Invalid native pointer");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }

    // Keep global reference to HydraSdrDevice instance
    g_hydrasdrDeviceObj = env->NewGlobalRef(thiz);

    // Resolve Java methods for hydrasdr_callback()
    jclass cls = env->GetObjectClass(thiz);
    g_getEmptyBufferMethod = env->GetMethodID(cls, "getEmptyBuffer", "()[B");
    g_onSamplesReadyMethod = env->GetMethodID(cls, "onSamplesReady", "([B)V");

    // Start streaming with callback
    int result = hydrasdr_start_rx(device, hydrasdr_callback, nullptr);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("hydrasdr_start_rx() failed: %d", result);
        hydrasdr_close(device);
        return result;
    }

    LOGI("nativeStartRX: HydraSdr streaming started");
    return result;
}

// ============================================================
// JNI: Stop HydraSdr
// ============================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeStopRX(JNIEnv *env, jobject thiz, jlong nativePtr) {

    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeStopRX: Invalid native pointer");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }

    // Stop streaming
    hydrasdr_stop_rx(device);

    // Free global ref
    if (g_hydrasdrDeviceObj) {
        env->DeleteGlobalRef(g_hydrasdrDeviceObj);
        g_hydrasdrDeviceObj = nullptr;
    }

    LOGI("nativeStopRX: HydraSdr streaming stopped");
    return 0;
}
