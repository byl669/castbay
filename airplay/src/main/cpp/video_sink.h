#pragma once

#include <jni.h>
#include <cstdint>

namespace castbay {
void initJvm(JavaVM *vm);
JavaVM *jvm();
/**
 * This thread's JNIEnv, attaching it to Java if needed. A thread attached here is detached
 * when it ends: Android 6 aborts the whole process when an attached native thread exits
 * without DetachCurrentThread (casting from an iPhone crashed a Xiaomi TV); later versions let
 * it go. Every callback into Java from UxPlay's threads must get its JNIEnv here.
 */
JNIEnv *currentEnv();
void setVideoSink(JNIEnv *env, jobject sink);
/** One Annex B access unit; [isH265] tells H.264 and H.265 (HEVC) mirroring apart. */
void dispatchVideo(const uint8_t *data, int length, int64_t ptsUs, bool isH265);
void dispatchSessionEnd();
}

