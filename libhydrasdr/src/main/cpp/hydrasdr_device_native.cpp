#include <jni.h>
#include <string>
#include <sstream>
#include <mutex>
#include <android/log.h>
#include <libusb.h>
#include "libhydrasdr/hydrasdr.h"
#include "sample_handoff.h"

/**
 * <h1>RF Analyzer - hydrasdr device native code</h1>
 *
 * Module:      hydrasdr_device_native.cpp
 * Description: The native jni code which is used by HydraSdrDevice.kt
 *
 * @author Dennis Mantz (with contributions by Benjamin Vernoux <bvernoux@hydrasdr.com>)
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
// Hands the transfers of the RX callback to HydraSdrDevice (bound at start, released at stop/close).
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
        jclass /* clazz */,
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
    std::lock_guard<std::mutex> lock(g_stateMutex);
    LOGI("Closing HydraSdr device, pointer: %p", device);
    int result = hydrasdr_close(device); // also stops a running stream
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to close HydraSdr device, error: %d", result);
    }
    g_handoff.unbind(env);
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


// One setter for every gain type (libhydrasdr's hardware-agnostic API, v1.1): gainType is a
// hydrasdr_gain_type_t value (HydraSdrGainType.nativeValue).
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeSetGain(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr,
        jint gainType,
        jint value) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetGain: Invalid native pointer");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }
    auto type = static_cast<hydrasdr_gain_type_t>(gainType);
    uint8_t gain_value = static_cast<uint8_t>(value);
    LOGI("Setting gain type %d to %d for device %p", gainType, gain_value, device);
    int result = hydrasdr_set_gain(device, type, gain_value);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("Failed to set gain type %d, error: %d", gainType, result);
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
    std::lock_guard<std::mutex> lock(g_stateMutex);
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
        jlong freq_hz) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeSetFrequency: Invalid native pointer");
        return HYDRASDR_ERROR_INVALID_PARAM;
    }
    auto freq = static_cast<uint64_t>(freq_hz);
    LOGI("Setting frequency to %llu Hz for device %p", static_cast<unsigned long long>(freq), device);
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

    auto* rates_buffer = new uint32_t[num_samplerates];

    result = hydrasdr_get_samplerates(device, rates_buffer, num_samplerates);
    if (result != HYDRASDR_SUCCESS) {
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

    return HYDRASDR_SUCCESS;
}


// Escapes a fixed-size char buffer (not necessarily null-terminated within maxLen) as a JSON
// string value: no JSON library is linked into this module, the device info is built by hand.
static std::string escape_json_string(const char* str, size_t maxLen) {
    size_t len = strnlen(str, maxLen);
    std::string result;
    result.reserve(len);
    for (size_t i = 0; i < len; ++i) {
        char c = str[i];
        switch (c) {
            case '"': result += "\\\""; break;
            case '\\': result += "\\\\"; break;
            case '\n': result += "\\n"; break;
            case '\r': result += "\\r"; break;
            case '\t': result += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    result += buf;
                } else {
                    result += c;
                }
        }
    }
    return result;
}

// ============================================================
// JNI: Query hardware-agnostic device capabilities (v1.1.x API)
// ============================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_mantz_1it_libhydrasdr_HydraSdrDevice_nativeGetDeviceInfo(
        JNIEnv* env,
        jobject /* this */,
        jlong nativePtr) {
    struct hydrasdr_device* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeGetDeviceInfo: Invalid native pointer");
        return nullptr;
    }

    hydrasdr_device_info_t info;
    int result = hydrasdr_get_device_info(device, &info);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("nativeGetDeviceInfo: hydrasdr_get_device_info failed, error: %d", result);
        return nullptr;
    }

    // hydrasdr_get_all_gains() returns only the gain types this device supports.
    hydrasdr_gain_info_t gains[HYDRASDR_GAIN_TYPE_COUNT];
    uint8_t gainCount = HYDRASDR_GAIN_TYPE_COUNT;
    int gainResult = hydrasdr_get_all_gains(device, gains, &gainCount);
    if (gainResult != HYDRASDR_SUCCESS) {
        LOGE("nativeGetDeviceInfo: hydrasdr_get_all_gains failed, error: %d - proceeding with no gains", gainResult);
        gainCount = 0;
    }

    std::ostringstream json;
    json << "{"
         << "\"boardName\":\"" << escape_json_string(info.board_name, sizeof(info.board_name)) << "\","
         << "\"firmwareVersion\":\"" << escape_json_string(info.firmware_version, sizeof(info.firmware_version)) << "\","
         << "\"features\":" << info.features << ","
         << "\"minFrequency\":" << info.min_frequency << ","
         << "\"maxFrequency\":" << info.max_frequency << ","
         << "\"rfPorts\":[";
    uint8_t portCount = info.rf_port_count;
    if (portCount > 32) portCount = 32; // rf_port_info holds at most 32 entries
    for (uint8_t i = 0; i < portCount; ++i) {
        const hydrasdr_rf_port_info_t& port = info.rf_port_info[i];
        if (i > 0) json << ",";
        json << "{"
             << "\"index\":" << static_cast<int>(i) << ","
             << "\"name\":\"" << escape_json_string(port.name, sizeof(port.name)) << "\","
             << "\"minFrequency\":" << port.min_frequency << ","
             << "\"maxFrequency\":" << port.max_frequency << ","
             << "\"hasBiasTee\":" << (port.has_bias_tee ? "true" : "false")
             << "}";
    }
    json << "],\"gains\":[";
    for (uint8_t i = 0; i < gainCount; ++i) {
        const hydrasdr_gain_info_t& g = gains[i];
        if (i > 0) json << ",";
        json << "{"
             << "\"type\":" << static_cast<int>(g.type) << ","
             << "\"min\":" << static_cast<int>(g.min_value) << ","
             << "\"max\":" << static_cast<int>(g.max_value) << ","
             << "\"step\":" << static_cast<int>(g.step_value) << ","
             << "\"default\":" << static_cast<int>(g.default_value) << ","
             << "\"isAgc\":" << ((g.flags & HYDRASDR_GAIN_FLAG_IS_AGC) ? "true" : "false") << ","
             << "\"isPreset\":" << ((g.flags & HYDRASDR_GAIN_FLAG_IS_PRESET) ? "true" : "false")
             << "}";
    }
    json << "]}";

    return env->NewStringUTF(json.str().c_str());
}

// ============================================================
// HydraSdr RX callback (runs on HydraSdr’s thread)
// ============================================================
static int hydrasdr_callback(hydrasdr_transfer *transfer) {
    // Below the hardware rate the driver decimates, so a transfer holds only 65536/decimation IQ
    // pairs; the rest of the pooled buffer keeps its previous content (the valid byte count tells).
    // dropped_samples is cumulative since streaming started.
    g_handoff.deliver(transfer->samples, static_cast<size_t>(transfer->sample_count) * sizeof(int16_t) * 2,
                      transfer->dropped_samples);
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

    std::lock_guard<std::mutex> lock(g_stateMutex);

    if (!g_handoff.bind(env, thiz)) {
        LOGE("nativeStartRX: HydraSdrDevice lacks the sample callbacks");
        return HYDRASDR_ERROR_OTHER;
    }

    int result = hydrasdr_start_rx(device, hydrasdr_callback, nullptr);
    if (result != HYDRASDR_SUCCESS) {
        LOGE("hydrasdr_start_rx() failed: %d", result);
        // The device stays open: nativeStopRX()/nativeClose() own its lifecycle, not this function.
        g_handoff.unbind(env);
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

    std::lock_guard<std::mutex> lock(g_stateMutex);

    // Stop streaming (joins the driver's threads, so no callback is running afterwards)
    hydrasdr_stop_rx(device);
    g_handoff.unbind(env);

    LOGI("nativeStopRX: HydraSdr streaming stopped");
    return 0;
}
