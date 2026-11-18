#include <jni.h>
#include <mutex>
#include <string>
#include <android/log.h>
#include <libusb.h>
#include "libairspy/airspy.h"
#include "sample_handoff.h"

/**
 * <h1>RF Analyzer - airspy device native code</h1>
 *
 * Module:      airspy_device_native.cpp
 * Description: The native jni code which is used by AirspyDevice.kt
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

#define LOG_TAG "NativeLibAirspy"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
// Globals
// ============================================================
// Hands the transfers of the RX callback to AirspyDevice (bound at start, released at stop/close).
static SampleHandoff g_handoff;

// Serializes start/stop/close/sample-rate changes: they arrive from different threads (UI collector,
// Scheduler shutdown, service stop), and two concurrent stops would both join the driver's threads.
static std::mutex g_stateMutex;

// ============================================================
// JNI: Cache JavaVM
// ============================================================
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_handoff.init(vm);
    return JNI_VERSION_1_6;
}

// Helper to cast long to airspy_device*
static inline struct airspy_device* get_device_ptr(jlong nativePtr) {
    return reinterpret_cast<struct airspy_device*>(nativePtr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_getLibraryVersionString(
        JNIEnv* env,
        jclass) {
    airspy_lib_version_t version;
    airspy_lib_version(&version);
    const libusb_version* usb_version = libusb_get_version();

    char version_str[128];
    snprintf(version_str, sizeof(version_str), "Airspy Version: %d.%d.%d (Libusb Version: %d.%d.%d.%d%s)",
             version.major_version, version.minor_version, version.revision,
             usb_version->major, usb_version->minor, usb_version->micro, usb_version->nano,
             usb_version->rc ? usb_version->rc : "");
    return env->NewStringUTF(version_str);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeOpenFd(
        JNIEnv* env,
        jclass /* clazz */,
        jint fd) {
    struct airspy_device* device = nullptr;
    LOGI("Attempting to open Airspy device with fd: %d", fd);
    int result = airspy_open_fd(&device, fd);

    if (result != AIRSPY_SUCCESS) {
        LOGE("Failed to open Airspy device, error: %d", result);
        return result;
    }
    LOGI("Airspy device opened successfully, pointer: %p", device);

    // set sample type to signed 16-bit IQ samples
    airspy_set_sample_type(device, AIRSPY_SAMPLE_INT16_IQ);

    return reinterpret_cast<jlong>(device);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeVersionStringRead(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr) {
    struct airspy_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeVersionStringRead: Invalid native pointer");
        return nullptr;
    }

    char version[128];
    int result = airspy_version_string_read(device, reinterpret_cast<char *>(&version), sizeof(version));
    if (result != AIRSPY_SUCCESS) {
        LOGE("Failed to read version string, error: %d", result);
        return nullptr;
    }
    return env->NewStringUTF(version);
}


extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeClose(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr) {
    struct airspy_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeClose: Invalid native pointer or device already closed");
        return AIRSPY_ERROR_INVALID_PARAM;
    }
    std::lock_guard<std::mutex> lock(g_stateMutex);
    LOGI("Closing Airspy device, pointer: %p", device);
    int result = airspy_close(device); // also stops a running stream
    if (result != AIRSPY_SUCCESS) {
        LOGE("Failed to close Airspy device, error: %d", result);
    }
    g_handoff.unbind(env);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeIsStreaming(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr) {
    struct airspy_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeIsStreaming: Invalid native pointer");
        return JNI_FALSE;
    }

    int streaming_status = airspy_is_streaming(device);
    return streaming_status ? JNI_TRUE : JNI_FALSE;
}


extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeSetLnaGain(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint value) {
    struct airspy_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetLnaGain: Invalid native pointer");
        return AIRSPY_ERROR_INVALID_PARAM;
    }
    uint8_t gain_value = static_cast<uint8_t>(value);
    LOGI("Setting LNA gain to %d for device %p", gain_value, device);
    int result = airspy_set_lna_gain(device, gain_value);
    if (result != AIRSPY_SUCCESS) {
        LOGE("Failed to set LNA gain, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeSetMixerGain(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint value) {
    struct airspy_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetMixerGain: Invalid native pointer");
        return AIRSPY_ERROR_INVALID_PARAM;
    }
    uint8_t gain_value = static_cast<uint8_t>(value);
    LOGI("Setting Mixer gain to %d for device %p", gain_value, device);
    int result = airspy_set_mixer_gain(device, gain_value);
    if (result != AIRSPY_SUCCESS) {
        LOGE("Failed to set Mixer gain, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeSetVgaGain(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint value) {
    struct airspy_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetVgaGain: Invalid native pointer");
        return AIRSPY_ERROR_INVALID_PARAM;
    }
    uint8_t gain_value = static_cast<uint8_t>(value);
    LOGI("Setting VGA gain to %d for device %p", gain_value, device);
    int result = airspy_set_vga_gain(device, gain_value);
    if (result != AIRSPY_SUCCESS) {
        LOGE("Failed to set VGA gain, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeSetLinearityGain(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint value) {
    struct airspy_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetLinearityGain: Invalid native pointer");
        return AIRSPY_ERROR_INVALID_PARAM;
    }
    uint8_t gain_value = static_cast<uint8_t>(value);
    LOGI("Setting Linearity gain to %d for device %p", gain_value, device);
    int result = airspy_set_linearity_gain(device, gain_value);
    if (result != AIRSPY_SUCCESS) {
        LOGE("Failed to set Linearity gain, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeSetSensitivityGain(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint value) {
    struct airspy_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetSensitivityGain: Invalid native pointer");
        return AIRSPY_ERROR_INVALID_PARAM;
    }
    uint8_t gain_value = static_cast<uint8_t>(value);
    LOGI("Setting Sensitivity gain to %d for device %p", gain_value, device);
    int result = airspy_set_sensitivity_gain(device, gain_value);
    if (result != AIRSPY_SUCCESS) {
        LOGE("Failed to set Sensitivity gain, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeSetSampleRate(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint samplerate) {
    struct airspy_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetSampleRate: Invalid native pointer");
        return AIRSPY_ERROR_INVALID_PARAM;
    }
    uint32_t rate = static_cast<uint32_t>(samplerate);
    LOGI("Setting sample rate to %u for device %p", rate, device);
    int result = airspy_set_samplerate(device, rate);
    if (result != AIRSPY_SUCCESS) {
        LOGE("Failed to set sample rate, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeSetFrequency(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint freq_hz) {
    struct airspy_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetFrequency: Invalid native pointer");
        return AIRSPY_ERROR_INVALID_PARAM;
    }
    uint32_t freq = static_cast<uint32_t>(freq_hz);
    LOGI("Setting frequency to %u Hz for device %p", freq, device);
    int result = airspy_set_freq(device, freq);
    if (result != AIRSPY_SUCCESS) {
        LOGE("Failed to set frequency, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeSetRfBias(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jboolean value) {
    struct airspy_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetRfBias: Invalid native pointer");
        return AIRSPY_ERROR_INVALID_PARAM;
    }
    uint8_t bias_value = (value == JNI_TRUE) ? 1 : 0;
    LOGI("Setting RF bias to %d for device %p", bias_value, device);
    int result = airspy_set_rf_bias(device, bias_value);
    if (result != AIRSPY_SUCCESS) {
        LOGE("Failed to set RF bias, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeGetSamplerates(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jobject list_samplerates) { // Pass a mutableListOf<Int> object
    struct airspy_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeGetSamplerates: Invalid native pointer");
        return AIRSPY_ERROR_INVALID_PARAM;
    }

    uint32_t num_samplerates = 0;
    // First call to get the number of available sample rates
    int result = airspy_get_samplerates(device, &num_samplerates, 0);
    if (result != AIRSPY_SUCCESS) {
        LOGE("Failed to get number of samplerates, error: %d", result);
        return result;
    }

    if (num_samplerates == 0) {
        LOGI("No samplerates available for device %p", device);
        return AIRSPY_SUCCESS; // No error, just no sample rates
    }

    auto* rates_buffer = new uint32_t[num_samplerates];

    result = airspy_get_samplerates(device, rates_buffer, num_samplerates);
    if (result != AIRSPY_SUCCESS) {
        LOGE("Failed to get samplerates, error: %d", result);
        delete[] rates_buffer;
        return result;
    }

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

    return AIRSPY_SUCCESS;
}


// ============================================================
// Airspy RX callback (runs on Airspy’s thread)
// ============================================================
static int airspy_callback(airspy_transfer *transfer) {
    // INT16_IQ: sample_count IQ pairs of 2 x int16. dropped_samples counts the samples the driver
    // dropped before this transfer (not cumulative).
    g_handoff.deliverCountingDrops(transfer->samples, static_cast<size_t>(transfer->sample_count) * sizeof(int16_t) * 2,
                                   transfer->dropped_samples);
    return 0;
}


// ============================================================
// JNI: Start Airspy
// ============================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeStartRX(JNIEnv *env, jobject thiz, jlong nativePtr) {

    struct airspy_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeStartRX: Invalid native pointer");
        return AIRSPY_ERROR_INVALID_PARAM;
    }

    std::lock_guard<std::mutex> lock(g_stateMutex);

    if (!g_handoff.bind(env, thiz)) {
        LOGE("nativeStartRX: AirspyDevice lacks the sample callbacks");
        return AIRSPY_ERROR_OTHER;
    }

    int result = airspy_start_rx(device, airspy_callback, nullptr);
    if (result != AIRSPY_SUCCESS) {
        LOGE("airspy_start_rx() failed: %d", result);
        // The device stays open: nativeStopRX()/nativeClose() own its lifecycle, and the Kotlin
        // side still holds the pointer.
        g_handoff.unbind(env);
        return result;
    }

    LOGI("nativeStartRX: Airspy streaming started");
    return result;
}

// ============================================================
// JNI: Stop Airspy
// ============================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspy_AirspyDevice_nativeStopRX(JNIEnv *env, jobject thiz, jlong nativePtr) {

    struct airspy_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeStopRX: Invalid native pointer");
        return AIRSPY_ERROR_INVALID_PARAM;
    }

    std::lock_guard<std::mutex> lock(g_stateMutex);

    // Stop streaming (joins the driver's threads, so no callback is running afterwards)
    airspy_stop_rx(device);
    g_handoff.unbind(env);

    LOGI("nativeStopRX: Airspy streaming stopped");
    return 0;
}
