#include <jni.h>
#include <string>
#include <android/log.h>
#include <libusb.h>
#include <mutex>
#include <unordered_map>
#include "libairspyhf/airspyhf.h"
#include "sample_handoff.h"

/**
 * <h1>RF Analyzer - airspyHF device native code</h1>
 *
 * Module:      airspyhf_device_native.cpp
 * Description: The native jni code which is used by AirspyHFDevice.kt
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

#define LOG_TAG "NativeLibAirspyHF"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
// Globals
// ============================================================
// Hands the transfers of the RX callback to AirspyHFDevice (bound at start, released at stop/close).
static SampleHandoff g_handoff;

// ============================================================
// JNI: Cache JavaVM
// ============================================================
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_handoff.init(vm);
    return JNI_VERSION_1_6;
}

// ----------------------------
// Per-device wrapper state
// ----------------------------
struct DeviceWrapperState {
    std::mutex m;              // protects the following fields
    bool starting = false;     // set while start in progress
    bool stopping = false;     // set while stop in progress
    bool streaming = false;    // set if we think device is streaming
};

// global structures to hold states
static std::mutex g_states_mutex; // protects g_states map
static std::unordered_map<jlong, DeviceWrapperState*> g_states;

// helper: get-or-create the wrapper state for nativePtr
static DeviceWrapperState* get_or_create_state(jlong nativePtr) {
    std::lock_guard<std::mutex> lock(g_states_mutex);
    auto it = g_states.find(nativePtr);
    if (it != g_states.end()) return it->second;
    DeviceWrapperState* s = new DeviceWrapperState();
    g_states[nativePtr] = s;
    return s;
}

// helper: remove & free state (call when device is truly closed)
static void free_state(jlong nativePtr) {
    std::lock_guard<std::mutex> lock(g_states_mutex);
    auto it = g_states.find(nativePtr);
    if (it == g_states.end()) return;
    delete it->second;
    g_states.erase(it);
}

// Helper to cast long to airspyhf_device*
static inline struct airspyhf_device* get_device_ptr(jlong nativePtr) {
    return reinterpret_cast<struct airspyhf_device*>(nativePtr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mantz_1it_libairspyhf_AirspyHFDevice_getLibraryVersionString(
        JNIEnv* env,
        jclass) {
    airspyhf_lib_version_t version;
    airspyhf_lib_version(&version);
    const libusb_version* usb_version = libusb_get_version();

    char version_str[128];
    snprintf(version_str, sizeof(version_str), "AirspyHF Version: %d.%d.%d (Libusb Version: %d.%d.%d.%d%s)",
             version.major_version, version.minor_version, version.revision,
             usb_version->major, usb_version->minor, usb_version->micro, usb_version->nano,
             usb_version->rc ? usb_version->rc : "");
    return env->NewStringUTF(version_str);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mantz_1it_libairspyhf_AirspyHFDevice_nativeOpenFd(
        JNIEnv* env,
        jclass /* clazz */,
        jint fd) {
    struct airspyhf_device* device = nullptr;
    LOGI("Attempting to open Airspy device with fd: %d", fd);
    int result = airspyhf_open_fd(&device, fd);

    if (result != AIRSPYHF_SUCCESS) {
        LOGE("Failed to open Airspy device, error: %d", result);
        return result;
    }
    LOGI("Airspy device opened successfully, pointer: %p", device);

    return reinterpret_cast<jlong>(device);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mantz_1it_libairspyhf_AirspyHFDevice_nativeVersionStringRead(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr) {
    struct airspyhf_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeVersionStringRead: Invalid native pointer");
        return nullptr;
    }

    char version[128];
    int result = airspyhf_version_string_read(device, reinterpret_cast<char *>(&version), sizeof(version));
    if (result != AIRSPYHF_SUCCESS) {
        LOGE("Failed to read version string, error: %d", result);
        return nullptr;
    }
    return env->NewStringUTF(version);
}


extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspyhf_AirspyHFDevice_nativeClose(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr) {
    struct airspyhf_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeClose: Invalid native pointer or device already closed");
        return AIRSPYHF_ERROR;
    }
    LOGI("nativeClose: Closing AirspyHF device, pointer: %p", device);

    // Stop a running stream before closing
    airspyhf_stop(device);

    int result = airspyhf_close(device);
    if (result != AIRSPYHF_SUCCESS) {
        LOGE("nativeClose: Failed to close AirspyHF device, error: %d", result);
    }

    // Free wrapper state AFTER airspyhf_close()
    g_handoff.unbind(env);
    free_state(nativePtr);
    LOGI("nativeClose: wrapper state freed for device %p", device);

    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mantz_1it_libairspyhf_AirspyHFDevice_nativeIsStreaming(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr) {
    struct airspyhf_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeIsStreaming: Invalid native pointer");
        return JNI_FALSE;
    }

    int streaming_status = airspyhf_is_streaming(device);
    return streaming_status ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspyhf_AirspyHFDevice_nativeSetHfAgc(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jboolean value) {
    struct airspyhf_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetHfAgc: Invalid native pointer");
        return AIRSPYHF_ERROR;
    }
    uint8_t agc_enabled = (value == JNI_TRUE) ? 1 : 0;
    LOGI("Setting AGC enabled=%d for device %p", agc_enabled, device);
    int result = airspyhf_set_hf_agc(device, agc_enabled);
    if (result != AIRSPYHF_SUCCESS) {
        LOGE("Failed to set AGC, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspyhf_AirspyHFDevice_nativeSetHfAgcThreshold(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jboolean value) {
    struct airspyhf_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetHfAgcThreshold: Invalid native pointer");
        return AIRSPYHF_ERROR;
    }
    uint8_t agc_threshold = (value == JNI_TRUE) ? 1 : 0;
    LOGI("Setting AGC threshold=%d for device %p", agc_threshold, device);
    int result = airspyhf_set_hf_agc_threshold(device, agc_threshold);
    if (result != AIRSPYHF_SUCCESS) {
        LOGE("Failed to set AGC threshold, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspyhf_AirspyHFDevice_nativeSetHfAtt(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint value) {
    struct airspyhf_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetHfAtt: Invalid native pointer");
        return AIRSPYHF_ERROR;
    }
    uint8_t att = static_cast<uint8_t>(value);
    LOGI("Setting HF Att to %d for device %p", att, device);
    int result = airspyhf_set_hf_att(device, att);
    if (result != AIRSPYHF_SUCCESS) {
        LOGE("Failed to set Att, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspyhf_AirspyHFDevice_nativeSetHfLna(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jboolean value) {
    struct airspyhf_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetHfLna: Invalid native pointer");
        return AIRSPYHF_ERROR;
    }
    uint8_t lna = (value == JNI_TRUE) ? 1 : 0;
    LOGI("Setting HF LNA enabled=%d for device %p", lna, device);
    int result = airspyhf_set_hf_lna(device, lna);
    if (result != AIRSPYHF_SUCCESS) {
        LOGE("Failed to set HF LNA, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspyhf_AirspyHFDevice_nativeSetSampleRate(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint samplerate) {
    struct airspyhf_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetSampleRate: Invalid native pointer");
        return AIRSPYHF_ERROR;
    }
    uint32_t rate = static_cast<uint32_t>(samplerate);
    LOGI("Setting sample rate to %u for device %p", rate, device);
    int result = airspyhf_set_samplerate(device, rate);
    if (result != AIRSPYHF_SUCCESS) {
        LOGE("Failed to set sample rate, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspyhf_AirspyHFDevice_nativeSetFrequency(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint freq_hz) {
    struct airspyhf_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetFrequency: Invalid native pointer");
        return AIRSPYHF_ERROR;
    }
    uint32_t freq = static_cast<uint32_t>(freq_hz);
    LOGI("Setting frequency to %u Hz for device %p", freq, device);
    int result = airspyhf_set_freq(device, freq);
    if (result != AIRSPYHF_SUCCESS) {
        LOGE("Failed to set frequency, error: %d", result);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspyhf_AirspyHFDevice_nativeGetSamplerates(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jobject list_samplerates) { // Pass a mutableListOf<Int> object
    struct airspyhf_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeGetSamplerates: Invalid native pointer");
        return AIRSPYHF_ERROR;
    }

    uint32_t num_samplerates = 0;
    // First call to get the number of available sample rates
    int result = airspyhf_get_samplerates(device, &num_samplerates, 0);
    if (result != AIRSPYHF_SUCCESS) {
        LOGE("Failed to get number of samplerates, error: %d", result);
        return result;
    }

    if (num_samplerates == 0) {
        LOGI("No samplerates available for device %p", device);
        return AIRSPYHF_SUCCESS; // No error, just no sample rates
    }

    auto* rates_buffer = new uint32_t[num_samplerates];

    result = airspyhf_get_samplerates(device, rates_buffer, num_samplerates);
    if (result != AIRSPYHF_SUCCESS) {
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

    return AIRSPYHF_SUCCESS;
}


// ============================================================
// Airspy RX callback (runs on AirspyHF’s thread)
// ============================================================
static int airspyhf_callback(airspyhf_transfer_t *transfer) {
    // dropped_samples counts the samples the driver dropped before this transfer (not cumulative)
    g_handoff.deliverCountingDrops(transfer->samples, static_cast<size_t>(transfer->sample_count) * sizeof(airspyhf_complex_float_t),
                                   transfer->dropped_samples);
    return 0;
}


// ============================================================
// JNI: Start Airspy
// ============================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspyhf_AirspyHFDevice_nativeStartRX(JNIEnv *env, jobject thiz, jlong nativePtr) {

    struct airspyhf_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeStartRX: Invalid native pointer");
        return AIRSPYHF_ERROR;
    }

    DeviceWrapperState* st = get_or_create_state(nativePtr);

    // Fast pre-check & set "starting" under the state lock to avoid races
    {
        std::lock_guard<std::mutex> lock(st->m);
        if (st->starting) {
            LOGW("nativeStartRX: start already in progress for device %p", (void*)device);
            return AIRSPYHF_ERROR;
        }
        if (st->streaming) {
            LOGI("nativeStartRX: device already streaming, returning success");
            return AIRSPYHF_SUCCESS;
        }
        st->starting = true; // cleared below
    }

    // Bind the Java object BEFORE calling into the library, so callbacks have it during streaming.
    if (!g_handoff.bind(env, thiz)) {
        LOGE("nativeStartRX: AirspyHFDevice lacks the sample callbacks");
        std::lock_guard<std::mutex> lock(st->m);
        st->starting = false;
        return AIRSPYHF_ERROR;
    }

    int result = airspyhf_start(device, airspyhf_callback, nullptr);

    {
        std::lock_guard<std::mutex> lock(st->m);
        st->starting = false;
        if (result == AIRSPYHF_SUCCESS) {
            st->streaming = true;
            LOGI("nativeStartRX: AirspyHF streaming started (device=%p)", (void*)device);
        } else {
            // The device stays open: nativeStopRX()/nativeClose() own its lifecycle, and the
            // Kotlin side still holds the pointer.
            LOGE("nativeStartRX: airspyhf_start() failed: %d", result);
            g_handoff.unbind(env);
        }
    }

    return result;
}

// ============================================================
// JNI: Stop Airspy
// ============================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libairspyhf_AirspyHFDevice_nativeStopRX(JNIEnv *env, jobject thiz, jlong nativePtr) {

    struct airspyhf_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeStopRX: Invalid native pointer");
        return AIRSPYHF_ERROR;
    }

    DeviceWrapperState* st = get_or_create_state(nativePtr);

    // Quick check & mark "stopping" to serialize concurrent calls
    {
        std::lock_guard<std::mutex> lock(st->m);
        if (st->stopping) {
            LOGW("nativeStopRX: stop already in progress for device %p", (void*)device);
            return AIRSPYHF_ERROR;
        }
        if (!st->streaming) {
            // Not streaming — nothing to do. Still attempt to tidy up any leftover ref.
            LOGI("nativeStopRX: device not streaming (no-op)");
            g_handoff.unbind(env);
            return AIRSPYHF_ERROR;
        }
        st->stopping = true;
    }

    // Stops the driver's threads and cancels the transfers
    int result = airspyhf_stop(device);

    // Clear state, then release the Java object
    {
        std::lock_guard<std::mutex> lock(st->m);
        st->stopping = false;
        st->streaming = false;
    }
    g_handoff.unbind(env);

    LOGI("nativeStopRX: AirspyHF streaming stopped (device=%p), result=%d", (void*)device, result);

    return result;
}
