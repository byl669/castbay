#include "audio_sink.h"
#include "video_sink.h"

#include "file_log.h"
#include <mutex>

#define LOGE(...) castbay_logf(ANDROID_LOG_ERROR, "CastBayAudioSink", __VA_ARGS__)

namespace {
jobject g_sink = nullptr;
jmethodID g_on_audio = nullptr;
jmethodID g_on_pcm = nullptr;
jmethodID g_on_flush = nullptr;
jmethodID g_on_volume = nullptr;
jmethodID g_on_metadata = nullptr;
jmethodID g_on_cover_art = nullptr;
jmethodID g_on_progress = nullptr;
std::mutex g_mutex;
}

namespace castbay {
void setAudioSink(JNIEnv *env, jobject sink) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_sink) {
        env->DeleteGlobalRef(g_sink);
        g_sink = nullptr;
        g_on_audio = nullptr;
        g_on_pcm = nullptr;
        g_on_flush = nullptr;
        g_on_volume = nullptr;
        g_on_metadata = nullptr;
        g_on_cover_art = nullptr;
        g_on_progress = nullptr;
    }
    if (!sink) return;
    g_sink = env->NewGlobalRef(sink);
    jclass cls = env->GetObjectClass(sink);
    g_on_audio = env->GetMethodID(cls, "onAudioData", "([BJ)V");
    g_on_pcm = env->GetMethodID(cls, "onPcmData", "([BJI)V");
    g_on_flush = env->GetMethodID(cls, "onAudioFlush", "()V");
    g_on_volume = env->GetMethodID(cls, "onVolume", "(F)V");
    g_on_metadata = env->GetMethodID(cls, "onMetadata", "([B)V");
    g_on_cover_art = env->GetMethodID(cls, "onCoverArt", "([B)V");
    g_on_progress = env->GetMethodID(cls, "onProgress", "(DD)V");
    env->DeleteLocalRef(cls);
    if (!g_on_audio || !g_on_pcm || !g_on_flush || !g_on_volume || !g_on_metadata || !g_on_cover_art ||
        !g_on_progress) LOGE("Audio sink methods were not found");
}

namespace {
/* Caller holds g_mutex. */
void callWithBytes(jmethodID method, const void *data, int length, int64_t ptsUs) {
    if (!g_sink || !method || !data || length <= 0) return;
    JNIEnv *env = currentEnv();
    if (!env) return;
    jbyteArray bytes = env->NewByteArray(length);
    if (!bytes) return;
    env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(data));
    env->CallVoidMethod(g_sink, method, bytes, static_cast<jlong>(ptsUs));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    env->DeleteLocalRef(bytes);
}
}  // namespace

void dispatchAudio(const uint8_t *data, int length, int64_t ptsUs) {
    std::lock_guard<std::mutex> lock(g_mutex);
    callWithBytes(g_on_audio, data, length, ptsUs);
}

void dispatchPcm(const int16_t *samples, int count, int64_t ptsUs, int compressedBytes) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const int length = count * static_cast<int>(sizeof(int16_t));
    if (!g_sink || !g_on_pcm || !samples || length <= 0) return;
    JNIEnv *env = currentEnv();
    if (!env) return;
    jbyteArray bytes = env->NewByteArray(length);
    if (!bytes) return;
    env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(samples));
    env->CallVoidMethod(g_sink, g_on_pcm, bytes, static_cast<jlong>(ptsUs), static_cast<jint>(compressedBytes));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    env->DeleteLocalRef(bytes);
}

namespace {
/* Caller holds g_mutex. Unlike callWithBytes, an empty array is passed through. */
void callWithArray(jmethodID method, const void *data, int length) {
    if (!g_sink || !method || length < 0) return;
    JNIEnv *env = currentEnv();
    if (!env) return;
    jbyteArray bytes = env->NewByteArray(length);
    if (!bytes) return;
    if (length > 0 && data) env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(data));
    env->CallVoidMethod(g_sink, method, bytes);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    env->DeleteLocalRef(bytes);
}
}  // namespace

void dispatchMetadata(const void *dmap, int length) {
    std::lock_guard<std::mutex> lock(g_mutex);
    callWithArray(g_on_metadata, dmap, length);
}

void dispatchCoverArt(const void *image, int length) {
    std::lock_guard<std::mutex> lock(g_mutex);
    callWithArray(g_on_cover_art, image, length);
}

void dispatchProgress(double positionSec, double durationSec) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_sink || !g_on_progress) return;
    JNIEnv *env = currentEnv();
    if (!env) return;
    env->CallVoidMethod(g_sink, g_on_progress, positionSec, durationSec);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

void dispatchVolume(float db) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_sink || !g_on_volume) return;
    JNIEnv *env = currentEnv();
    if (!env) return;
    env->CallVoidMethod(g_sink, g_on_volume, static_cast<jfloat>(db));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

void dispatchAudioFlush() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_sink || !g_on_flush) return;
    JNIEnv *env = currentEnv();
    if (!env) return;
    env->CallVoidMethod(g_sink, g_on_flush);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}
}

