/*
 * rfax_jni.cpp - JNI glue between Rtl433Native.kt and the rfax_bridge decoder.
 *
 * Copyright (C) 2026 RF Analyzer contributors
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

#include <jni.h>
#include <android/log.h>

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <new>

#include "rfax_bridge.h"

#define LOG_TAG "RfaxRtl433"

static JavaVM *g_vm = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/)
{
    g_vm = vm;
    return JNI_VERSION_1_6;
}

/* Per-instance state. Owned by the JNI side; the Kotlin wrapper only holds the
 * opaque pointer. Feed is called on a single decoder thread; create/destroy are
 * called on the main thread once the decoder thread has been stopped. */
struct RfaxHolder {
    rfax_rtl433 *decoder;
    jobject callback; /* global ref to Rtl433Native */
    jmethodID on_json;
};

/* Invoked by the bridge on the decoder thread when an event is decoded. */
static void jni_output(char const *json, void *user)
{
    RfaxHolder *h = static_cast<RfaxHolder *>(user);
    if (!h || !h->callback || !g_vm)
        return;

    JNIEnv *env = nullptr;
    bool detach = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != 0)
            return;
        detach = true;
    }

    if (env) {
        jstring jline = env->NewStringUTF(json);
        if (jline) {
            env->CallVoidMethod(h->callback, h->on_json, jline);
            env->DeleteLocalRef(jline);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    if (detach)
        g_vm->DetachCurrentThread();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mantz_1it_librtl433_Rtl433Native_nativeCreate(JNIEnv *env, jobject /*thiz*/, jobject callback)
{
    rfax_rtl433 *decoder = rfax_rtl433_create();
    if (!decoder) {
        __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, "nativeCreate: rfax_rtl433_create failed");
        return 0;
    }

    RfaxHolder *h = new (std::nothrow) RfaxHolder();
    if (!h) {
        rfax_rtl433_destroy(decoder);
        return 0;
    }
    h->decoder = decoder;
    h->callback = callback ? env->NewGlobalRef(callback) : nullptr;

    jclass cls = callback ? env->GetObjectClass(callback) : nullptr;
    h->on_json = cls ? env->GetMethodID(cls, "onDecodedJson", "(Ljava/lang/String;)V") : nullptr;
    if (cls)
        env->DeleteLocalRef(cls);

    if (!h->callback || !h->on_json) {
        __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, "nativeCreate: onDecodedJson() not found");
        if (env->ExceptionCheck())
            env->ExceptionClear();
        if (h->callback)
            env->DeleteGlobalRef(h->callback);
        rfax_rtl433_destroy(decoder);
        delete h;
        return 0;
    }

    rfax_rtl433_set_output(decoder, jni_output, h);
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtl433_Rtl433Native_nativeConfigure(JNIEnv * /*env*/, jobject /*thiz*/,
        jlong handle, jint sampleRate, jlong centerFrequency, jint decimation)
{
    RfaxHolder *h = reinterpret_cast<RfaxHolder *>(handle);
    if (!h || !h->decoder || sampleRate <= 0)
        return -1;
    return rfax_rtl433_configure(h->decoder,
            static_cast<uint32_t>(sampleRate),
            static_cast<uint32_t>(centerFrequency),
            static_cast<uint32_t>(decimation));
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_librtl433_Rtl433Native_nativeSetCenterFrequency(JNIEnv * /*env*/, jobject /*thiz*/,
        jlong handle, jlong centerFrequency)
{
    RfaxHolder *h = reinterpret_cast<RfaxHolder *>(handle);
    if (h && h->decoder)
        rfax_rtl433_set_center_frequency(h->decoder, static_cast<uint32_t>(centerFrequency));
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_librtl433_Rtl433Native_nativeSetChannel(JNIEnv * /*env*/, jobject /*thiz*/,
        jlong handle, jint offsetHz, jint bandwidthHz)
{
    RfaxHolder *h = reinterpret_cast<RfaxHolder *>(handle);
    if (h && h->decoder)
        rfax_rtl433_set_channel(h->decoder,
                static_cast<int32_t>(offsetHz),
                static_cast<uint32_t>(bandwidthHz > 0 ? bandwidthHz : 0));
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_librtl433_Rtl433Native_nativeSetOptions(JNIEnv * /*env*/, jobject /*thiz*/,
        jlong handle, jint conversionMode, jint autoLevel, jint reportMeta, jfloat minSnr)
{
    RfaxHolder *h = reinterpret_cast<RfaxHolder *>(handle);
    if (h && h->decoder)
        rfax_rtl433_set_options(h->decoder,
                static_cast<int>(conversionMode),
                autoLevel ? 1 : 0,
                reportMeta ? 1 : 0,
                static_cast<float>(minSnr));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_librtl433_Rtl433Native_nativeAddFlex(JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring spec)
{
    RfaxHolder *h = reinterpret_cast<RfaxHolder *>(handle);
    if (!h || !h->decoder || !spec)
        return -1;
    const char *utf = env->GetStringUTFChars(spec, nullptr);
    int rc = utf ? rfax_rtl433_add_flex(h->decoder, utf) : -1;
    if (utf)
        env->ReleaseStringUTFChars(spec, utf);
    return rc;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_librtl433_Rtl433Native_nativeFeed(JNIEnv *env, jobject /*thiz*/,
        jlong handle, jfloatArray samples)
{
    RfaxHolder *h = reinterpret_cast<RfaxHolder *>(handle);
    if (!h || !h->decoder || !samples)
        return;

    const jsize len = env->GetArrayLength(samples);
    if (len < 2)
        return;

    jfloat *buf = env->GetFloatArrayElements(samples, nullptr);
    if (buf) {
        rfax_rtl433_feed(h->decoder, buf, static_cast<size_t>(len) / 2);
        env->ReleaseFloatArrayElements(samples, buf, JNI_ABORT);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_librtl433_Rtl433Native_nativeFlush(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle)
{
    RfaxHolder *h = reinterpret_cast<RfaxHolder *>(handle);
    if (h && h->decoder)
        rfax_rtl433_flush(h->decoder);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_librtl433_Rtl433Native_nativeDestroy(JNIEnv *env, jobject /*thiz*/, jlong handle)
{
    RfaxHolder *h = reinterpret_cast<RfaxHolder *>(handle);
    if (!h)
        return;
    if (h->decoder)
        rfax_rtl433_destroy(h->decoder);
    if (h->callback)
        env->DeleteGlobalRef(h->callback);
    delete h;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mantz_1it_librtl433_Rtl433Native_nativeVersion(JNIEnv *env, jobject /*thiz*/)
{
    char const *version = rfax_rtl433_version();
    return env->NewStringUTF(version ? version : "unknown");
}
