/**
 * <h1>RF Analyzer - Sample Handoff</h1>
 *
 * Module:      sample_handoff.h
 * Description: Hands the USB transfers of an SDR driver's RX thread to the Kotlin device object
 * (its SampleBufferPool, through getEmptyBuffer() and onSamplesReady(buffer, validByteCount,
 * droppedSamples)). Shared by the JNI code of all USB SDR drivers; header-only, so every driver
 * library has its own instance.
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
#pragma once

#include <jni.h>
#include <pthread.h>
#include <cstdint>
#include <mutex>

class SampleHandoff {
public:
    /** From JNI_OnLoad. */
    void init(JavaVM* vm) {
        vm_ = vm;
        pthread_key_create(&detachKey_, &detachThread);
    }

    /**
     * Before the driver starts streaming: binds the Kotlin device object (getEmptyBuffer and
     * onSamplesReady are required, onStreamingError() is used if present) and restarts the drop
     * count. False (nothing bound) if a required method is missing.
     */
    bool bind(JNIEnv* env, jobject device) {
        jclass cls = env->GetObjectClass(device);
        jmethodID getEmptyBuffer = method(env, cls, "getEmptyBuffer", "()[B");
        jmethodID onSamplesReady = method(env, cls, "onSamplesReady", "([BIJ)V");
        jmethodID onStreamingError = method(env, cls, "onStreamingError", "()V");
        env->DeleteLocalRef(cls);
        if (getEmptyBuffer == nullptr || onSamplesReady == nullptr) return false;
        jobject ref = env->NewGlobalRef(device);
        if (ref == nullptr) return false;

        std::lock_guard<std::mutex> lock(mutex_);
        if (device_ != nullptr) env->DeleteGlobalRef(device_);
        device_ = ref;
        getEmptyBuffer_ = getEmptyBuffer;
        onSamplesReady_ = onSamplesReady;
        onStreamingError_ = onStreamingError;
        droppedSamples_ = 0;
        return true;
    }

    /**
     * Releases the device object; later transfers are ignored. Waits for a delivery in
     * progress, so after this no callback touches the object anymore.
     */
    void unbind(JNIEnv* env) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (device_ != nullptr) env->DeleteGlobalRef(device_);
        device_ = nullptr;
    }

    /** From the RX thread, per transfer; droppedSamples is the driver's cumulative count. */
    void deliver(const void* samples, size_t byteCount, uint64_t droppedSamples) {
        JNIEnv* env = attachedEnv();
        if (env == nullptr) return;
        std::lock_guard<std::mutex> lock(mutex_);
        droppedSamples_ = droppedSamples;
        deliverLocked(env, samples, byteCount);
    }

    /** Same as deliver() for drivers that report the samples dropped before each transfer. */
    void deliverCountingDrops(const void* samples, size_t byteCount, uint64_t droppedBeforeThis) {
        JNIEnv* env = attachedEnv();
        if (env == nullptr) return;
        std::lock_guard<std::mutex> lock(mutex_);
        droppedSamples_ += droppedBeforeThis;
        deliverLocked(env, samples, byteCount);
    }

    /** From the RX thread: streaming ended without a stop request (device lost). */
    void notifyStreamingError() {
        JNIEnv* env = attachedEnv();
        if (env == nullptr) return;
        std::lock_guard<std::mutex> lock(mutex_);
        if (device_ == nullptr || onStreamingError_ == nullptr) return;
        env->CallVoidMethod(device_, onStreamingError_);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

private:
    JavaVM* vm_ = nullptr;
    pthread_key_t detachKey_ = 0;
    std::mutex mutex_; // guards the fields below
    jobject device_ = nullptr;
    jmethodID getEmptyBuffer_ = nullptr;
    jmethodID onSamplesReady_ = nullptr;
    jmethodID onStreamingError_ = nullptr;
    uint64_t droppedSamples_ = 0;

    void deliverLocked(JNIEnv* env, const void* samples, size_t byteCount) {
        if (device_ == nullptr) return; // not streaming (anymore)
        // waits a bounded time for a free buffer; null: pool exhausted, the transfer is dropped
        auto buffer = static_cast<jbyteArray>(env->CallObjectMethod(device_, getEmptyBuffer_));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return;
        }
        if (buffer == nullptr) return;
        jsize length = env->GetArrayLength(buffer);
        jint validByteCount = byteCount < static_cast<size_t>(length) ? static_cast<jint>(byteCount) : length;
        env->SetByteArrayRegion(buffer, 0, validByteCount, static_cast<const jbyte*>(samples));
        env->CallVoidMethod(device_, onSamplesReady_, buffer, validByteCount, static_cast<jlong>(droppedSamples_));
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(buffer);
    }

    // The driver's own threads call back into Java: attach them once, and detach them when they
    // exit (a thread must not end while attached to the VM).
    JNIEnv* attachedEnv() {
        if (vm_ == nullptr) return nullptr;
        JNIEnv* env = nullptr;
        if (vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
        if (vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
        pthread_setspecific(detachKey_, vm_);
        return env;
    }

    static void detachThread(void* vm) {
        static_cast<JavaVM*>(vm)->DetachCurrentThread();
    }

    static jmethodID method(JNIEnv* env, jclass cls, const char* name, const char* signature) {
        jmethodID id = env->GetMethodID(cls, name, signature);
        if (env->ExceptionCheck()) env->ExceptionClear(); // NoSuchMethodError
        return id;
    }
};
