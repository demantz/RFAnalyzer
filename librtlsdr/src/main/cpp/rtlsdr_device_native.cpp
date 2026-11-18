#include <jni.h>
#include <string>
#include <cstring>
#include <cstdint>
#include <atomic>
#include <mutex>
#include <thread>
#include <android/log.h>
#include <libusb.h>
#include "librtlsdr/rtl-sdr.h"
#include "sample_handoff.h"

/**
 * <h1>RF Analyzer - librtlsdr device native code</h1>
 *
 * Module:      rtlsdr_device_native.cpp
 * Description: The native jni code which is used by RtlSdrDevice.kt
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2026 Dennis Mantz
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

#define LOG_TAG "NativeLibRtlSdr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// librtlsdr's own DEFAULT_BUF_NUMBER / DEFAULT_BUF_LENGTH (16 * 32 * 512 = 262144 bytes).
// Kept explicit (rather than passing 0/0 to let librtlsdr pick its own defaults) so the
// Kotlin-side buffer pool size (RtlSdrDevice.BUFFER_SIZE) is guaranteed to match.
#define RTLSDR_BUF_NUM 15
#define RTLSDR_BUFFER_SIZE 262144

// ============================================================
// Globals
// ============================================================
static JavaVM *g_vm = nullptr;

// Hands the transfers of the RX callback to RtlSdrDevice (bound at start, released at stop/close).
static SampleHandoff g_handoff;

// Serializes lifecycle/state transitions for the single native device handle.
static std::mutex g_nativeStateMutex;

// Makes nativeClose() idempotent across threads.
static std::atomic<bool> g_nativeCloseStarted{false};

// The thread running the blocking rtlsdr_read_async(): unlike the other drivers, librtlsdr
// has no RX thread of its own.
static std::thread* g_rxThread = nullptr;

static inline bool is_close_started() {
    return g_nativeCloseStarted.load(std::memory_order_acquire);
}

// ============================================================
// JNI: Cache JavaVM
// ============================================================
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    g_handoff.init(vm);
    return JNI_VERSION_1_6;
}

// Helper to cast long to rtlsdr_dev_t*
static inline rtlsdr_dev_t* get_device_ptr(jlong nativePtr) {
    return reinterpret_cast<rtlsdr_dev_t*>(nativePtr);
}

// ============================================================
// JNI: Open device by FD
// Java: private static native long nativeOpenFd(int fd);
// Returns: jlong pointer on success OR error code (negative) on failure (same pattern as HackRF/Airspy wrappers)
// ============================================================
extern "C" JNIEXPORT jlong JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeOpenFd(
        JNIEnv* env,
        jclass /* clazz */,
        jint fd) {

    std::lock_guard<std::mutex> lock(g_nativeStateMutex);

    rtlsdr_dev_t* device = nullptr;
    LOGI("Attempting to open RTL-SDR device with fd: %d", fd);

    // LibUSB does not support device discovery on Android
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, NULL);

    int result = rtlsdr_open_fd(fd, &device);
    if (result != 0) {
        LOGE("rtlsdr_open_fd failed: %d", result);
        return static_cast<jlong>(result);
    }

    if (device == nullptr) {
        LOGE("nativeOpenFd: rtlsdr_open_fd returned success but device is null");
        return static_cast<jlong>(-1);
    }

    // Allow a later close for this newly opened device.
    g_nativeCloseStarted.store(false, std::memory_order_release);

    LOGI("RTL-SDR device opened successfully, pointer: %p", device);
    return reinterpret_cast<jlong>(device);
}

// ============================================================
// JNI: Close device
// Java: private native int nativeClose(long nativePtr);
// ============================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeClose(
        JNIEnv* env,
        jobject /* thiz */,
        jlong nativePtr) {

    std::lock_guard<std::mutex> lock(g_nativeStateMutex);

    if (is_close_started()) {
        LOGI("nativeClose: close already in progress or already done, skipping");
        return 0;
    }

    rtlsdr_dev_t* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeClose: Invalid native pointer or device already closed");
        return -1;
    }

    g_nativeCloseStarted.store(true, std::memory_order_release);

    // If RX is still running, cancel and join it first - otherwise the RX thread
    // could call back into a device pointer that rtlsdr_close() is about to free.
    if (g_rxThread != nullptr) {
        LOGI("nativeClose: RX still running, stopping it first");
        rtlsdr_cancel_async(device);
        g_rxThread->join();
        delete g_rxThread;
        g_rxThread = nullptr;
    }

    g_handoff.unbind(env);

    LOGI("Closing RTL-SDR device, pointer: %p", device);
    int result = rtlsdr_close(device);
    if (result != 0) {
        LOGE("Failed to close RTL-SDR device, error: %d", result);
    }
    return result;
}

// ============================================================
// JNI: Simple setters
// ============================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeSetCenterFreq(
        JNIEnv* env, jobject /* thiz */, jlong nativePtr, jlong freqHz) {
    std::lock_guard<std::mutex> lock(g_nativeStateMutex);
    if (is_close_started()) return -1;
    rtlsdr_dev_t* device = get_device_ptr(nativePtr);
    if (device == nullptr) { LOGE("nativeSetCenterFreq: Invalid native pointer"); return -1; }
    int result = rtlsdr_set_center_freq(device, static_cast<uint32_t>(freqHz));
    if (result != 0) LOGE("Failed to set center frequency, error: %d", result);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeSetSampleRate(
        JNIEnv* env, jobject /* thiz */, jlong nativePtr, jint sampleRate) {
    std::lock_guard<std::mutex> lock(g_nativeStateMutex);
    if (is_close_started()) return -1;
    rtlsdr_dev_t* device = get_device_ptr(nativePtr);
    if (device == nullptr) { LOGE("nativeSetSampleRate: Invalid native pointer"); return -1; }
    int result = rtlsdr_set_sample_rate(device, static_cast<uint32_t>(sampleRate));
    if (result != 0) LOGE("Failed to set sample rate, error: %d", result);
    // rtlsdr_set_sample_rate() soft-resets the demod over I2C, which can desync the device's
    // USB FIFO: while streaming, resync it with rtlsdr_reset_buffer() as nativeStartRX() does.
    if (result == 0 && g_rxThread != nullptr) {
        rtlsdr_reset_buffer(device);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeSetFreqCorrection(
        JNIEnv* env, jobject /* thiz */, jlong nativePtr, jint ppm) {
    std::lock_guard<std::mutex> lock(g_nativeStateMutex);
    if (is_close_started()) return -1;
    rtlsdr_dev_t* device = get_device_ptr(nativePtr);
    if (device == nullptr) { LOGE("nativeSetFreqCorrection: Invalid native pointer"); return -1; }
    int result = rtlsdr_set_freq_correction(device, static_cast<int>(ppm));
    if (result != 0) LOGE("Failed to set frequency correction, error: %d", result);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeSetTunerGainMode(
        JNIEnv* env, jobject /* thiz */, jlong nativePtr, jboolean manual) {
    std::lock_guard<std::mutex> lock(g_nativeStateMutex);
    if (is_close_started()) return -1;
    rtlsdr_dev_t* device = get_device_ptr(nativePtr);
    if (device == nullptr) { LOGE("nativeSetTunerGainMode: Invalid native pointer"); return -1; }
    int result = rtlsdr_set_tuner_gain_mode(device, manual == JNI_TRUE ? 1 : 0);
    if (result != 0) LOGE("Failed to set tuner gain mode, error: %d", result);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeSetTunerGain(
        JNIEnv* env, jobject /* thiz */, jlong nativePtr, jint gainTenthsDb) {
    std::lock_guard<std::mutex> lock(g_nativeStateMutex);
    if (is_close_started()) return -1;
    rtlsdr_dev_t* device = get_device_ptr(nativePtr);
    if (device == nullptr) { LOGE("nativeSetTunerGain: Invalid native pointer"); return -1; }
    int result = rtlsdr_set_tuner_gain(device, static_cast<int>(gainTenthsDb));
    if (result != 0) LOGE("Failed to set tuner gain, error: %d", result);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeSetAgcMode(
        JNIEnv* env, jobject /* thiz */, jlong nativePtr, jboolean on) {
    std::lock_guard<std::mutex> lock(g_nativeStateMutex);
    if (is_close_started()) return -1;
    rtlsdr_dev_t* device = get_device_ptr(nativePtr);
    if (device == nullptr) { LOGE("nativeSetAgcMode: Invalid native pointer"); return -1; }
    int result = rtlsdr_set_agc_mode(device, on == JNI_TRUE ? 1 : 0);
    if (result != 0) LOGE("Failed to set AGC mode, error: %d", result);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeSetDirectSampling(
        JNIEnv* env, jobject /* thiz */, jlong nativePtr, jint mode) {
    std::lock_guard<std::mutex> lock(g_nativeStateMutex);
    if (is_close_started()) return -1;
    rtlsdr_dev_t* device = get_device_ptr(nativePtr);
    if (device == nullptr) { LOGE("nativeSetDirectSampling: Invalid native pointer"); return -1; }
    int result = rtlsdr_set_direct_sampling(device, static_cast<int>(mode));
    if (result != 0) LOGE("Failed to set direct sampling mode, error: %d", result);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeSetBiasTee(
        JNIEnv* env, jobject /* thiz */, jlong nativePtr, jboolean on) {
    std::lock_guard<std::mutex> lock(g_nativeStateMutex);
    if (is_close_started()) return -1;
    rtlsdr_dev_t* device = get_device_ptr(nativePtr);
    if (device == nullptr) { LOGE("nativeSetBiasTee: Invalid native pointer"); return -1; }
    int result = rtlsdr_set_bias_tee(device, on == JNI_TRUE ? 1 : 0);
    if (result != 0) LOGE("Failed to set bias tee, error: %d", result);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeGetTunerType(
        JNIEnv* env, jobject /* thiz */, jlong nativePtr) {
    std::lock_guard<std::mutex> lock(g_nativeStateMutex);
    if (is_close_started()) return -1;
    rtlsdr_dev_t* device = get_device_ptr(nativePtr);
    if (device == nullptr) { LOGE("nativeGetTunerType: Invalid native pointer"); return -1; }
    return static_cast<jint>(rtlsdr_get_tuner_type(device));
}

// ============================================================
// JNI: Whether the opened device is an RTL-SDR Blog V4 - librtlsdr's own
// rtlsdr_check_dongle_model(), the check it uses to pick the V4's 28.8 MHz tuner crystal.
// Java: private native boolean nativeIsBlogV4(long nativePtr);
// ============================================================
extern "C" JNIEXPORT jboolean JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeIsBlogV4(
        JNIEnv* env, jobject /* thiz */, jlong nativePtr) {
    std::lock_guard<std::mutex> lock(g_nativeStateMutex);
    if (is_close_started()) return JNI_FALSE;
    rtlsdr_dev_t* device = get_device_ptr(nativePtr);
    if (device == nullptr) { LOGE("nativeIsBlogV4: Invalid native pointer"); return JNI_FALSE; }
    int result = rtlsdr_check_dongle_model(device, (char*)"RTLSDRBlog", (char*)"Blog V4");
    return result != 0 ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// JNI: Get the list of discrete gain values the tuner supports (tenths of a dB)
// Java: private native int nativeGetTunerGains(long nativePtr, MutableList<Int> gains);
// ============================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeGetTunerGains(
        JNIEnv* env, jobject /* thiz */, jlong nativePtr, jobject list_gains) {
    std::lock_guard<std::mutex> lock(g_nativeStateMutex);
    if (is_close_started()) return -1;
    rtlsdr_dev_t* device = get_device_ptr(nativePtr);
    if (device == nullptr) { LOGE("nativeGetTunerGains: Invalid native pointer"); return -1; }

    // Passing NULL for the buffer makes rtlsdr_get_tuner_gains() just return the count.
    int num_gains = rtlsdr_get_tuner_gains(device, nullptr);
    if (num_gains <= 0) {
        return num_gains;
    }

    auto* gains_buffer = new int[num_gains];
    int result = rtlsdr_get_tuner_gains(device, gains_buffer);
    if (result <= 0) {
        LOGE("Failed to get tuner gains, error: %d", result);
        delete[] gains_buffer;
        return result;
    }

    jclass listClass = env->GetObjectClass(list_gains);
    jmethodID addMethod = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID integerConstructor = env->GetMethodID(integerClass, "<init>", "(I)V");

    for (int i = 0; i < num_gains; ++i) {
        jobject gain_obj = env->NewObject(integerClass, integerConstructor, static_cast<jint>(gains_buffer[i]));
        env->CallBooleanMethod(list_gains, addMethod, gain_obj);
        env->DeleteLocalRef(gain_obj);
    }

    delete[] gains_buffer;
    env->DeleteLocalRef(listClass);
    env->DeleteLocalRef(integerClass);

    return result;
}

// ============================================================
// RTL-SDR RX callback (runs on the RX thread spawned by nativeStartRX, see below)
// callback signature: void (*rtlsdr_read_async_cb_t)(unsigned char *buf, uint32_t len, void *ctx)
// ============================================================
static void rtlsdr_callback(unsigned char* buf, uint32_t len, void* ctx) {
    if (is_close_started()) {
        return;
    }
    // librtlsdr reports no dropped samples
    g_handoff.deliver(buf, len, 0);
}

// ============================================================
// RX thread entry point: rtlsdr_read_async() blocks until rtlsdr_cancel_async() is
// called from another thread (nativeStopRX / nativeClose), so this thread lives as long
// as the stream.
// ============================================================
static void rtlsdr_rx_thread_func(rtlsdr_dev_t* device) {
    if (g_vm == nullptr) return;

    JNIEnv* env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != 0) {
        LOGE("rtlsdr_rx_thread_func: Failed to attach thread to JVM");
        return;
    }

    LOGI("rtlsdr_rx_thread_func: starting rtlsdr_read_async");
    int result = rtlsdr_read_async(device, rtlsdr_callback, nullptr, RTLSDR_BUF_NUM, RTLSDR_BUFFER_SIZE);
    LOGI("rtlsdr_rx_thread_func: rtlsdr_read_async returned %d", result);

    // Non-zero: librtlsdr gave up (device lost, persistent USB transfer errors); a stop
    // (rtlsdr_cancel_async()) returns 0. Tell the Kotlin side, so the app reports it
    // instead of waiting for samples.
    if (result != 0) {
        LOGE("rtlsdr_rx_thread_func: device lost, notifying Kotlin");
        g_handoff.notifyStreamingError();
    }

    g_vm->DetachCurrentThread();
}

// ============================================================
// JNI: Start RX
// Java: private native int nativeStartRX(long nativePtr);
// ============================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeStartRX(
        JNIEnv* env,
        jobject thiz,
        jlong nativePtr) {

    std::lock_guard<std::mutex> lock(g_nativeStateMutex);

    if (is_close_started()) {
        LOGE("nativeStartRX: device is closing/closed");
        return -1;
    }

    rtlsdr_dev_t* device = get_device_ptr(nativePtr);
    if (device == nullptr) {
        LOGE("nativeStartRX: Invalid native pointer");
        return -1;
    }

    if (g_rxThread != nullptr) {
        LOGE("nativeStartRX: RX already running");
        return -1;
    }

    if (!g_handoff.bind(env, thiz)) {
        LOGE("nativeStartRX: RtlSdrDevice lacks the sample callbacks");
        return -1;
    }

    rtlsdr_reset_buffer(device);

    g_rxThread = new std::thread(rtlsdr_rx_thread_func, device);

    LOGI("nativeStartRX: RTL-SDR streaming started");
    return 0;
}

// ============================================================
// JNI: Stop RX
// Java: private native int nativeStopRX(long nativePtr);
// ============================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtlsdr_RtlSdrDevice_nativeStopRX(
        JNIEnv* env,
        jobject /* thiz */,
        jlong nativePtr) {

    std::lock_guard<std::mutex> lock(g_nativeStateMutex);

    rtlsdr_dev_t* device = get_device_ptr(nativePtr);
    if (device != nullptr) {
        rtlsdr_cancel_async(device);
    }

    if (g_rxThread != nullptr) {
        g_rxThread->join();
        delete g_rxThread;
        g_rxThread = nullptr;
    }

    g_handoff.unbind(env);

    LOGI("nativeStopRX: RTL-SDR streaming stopped");
    return 0;
}
