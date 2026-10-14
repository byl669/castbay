#include <jni.h>
#include "file_log.h"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <pthread.h>
#include <string>
#include <thread>
#include <vector>

#include "alac_decoder.h"
#include "audio_sink.h"
#include "video_sink.h"

extern "C" {
#include "dnssd.h"
#include "logger.h"
#include "raop.h"
#include <plist/plist.h>
#include "raop_ext.h"
#include "stream.h"
}

#define LOGI(...) castbay_logf(ANDROID_LOG_INFO, "CastBayProtocol", __VA_ARGS__)
#define LOGE(...) castbay_logf(ANDROID_LOG_ERROR, "CastBayProtocol", __VA_ARGS__)

namespace {
/* AirPlay compression type (ct) reported by audio_get_format and in each audio packet. */
constexpr unsigned char kAudioCtAlac = 2;
constexpr unsigned char kAudioCtAacEld = 8;

constexpr double kPlaybackNotStarted = -1;
constexpr double kPlaybackFinished = 0;

std::mutex g_server_mutex;
raop_t *g_raop = nullptr;
dnssd_t *g_dnssd = nullptr;
jclass g_native_class = nullptr;
jmethodID g_on_connection_started = nullptr;
jmethodID g_on_video_play = nullptr;
jmethodID g_on_video_scrub = nullptr;
jmethodID g_on_video_rate = nullptr;
jmethodID g_on_video_stop = nullptr;
jmethodID g_playback_info = nullptr;
jmethodID g_on_remote_control = nullptr;
jmethodID g_on_client = nullptr;
jmethodID g_on_feedback = nullptr;
jmethodID g_on_pin = nullptr;
jmethodID g_on_sender_report = nullptr;
jmethodID g_on_paired = nullptr;
/*
 * PIN pairing (UxPlay's pin_pw = 1): a new sender enters a PIN shown on the TV, once. The
 * public keys of senders that did are kept (by the app, passed in at start), and only they
 * may skip the PIN; any other client claiming to be paired is refused.
 */
std::atomic<bool> g_use_pin{false};
std::mutex g_register_mutex;
std::vector<std::string> g_registered_keys;
/* raop keeps a pointer to this for HLS audio/subtitle selection; it must outlive g_raop. */
std::string g_lang_system;
/* Client-access password senders must enter; empty = open access. Read on protocol threads. */
std::string g_password;
/*
 * One sender session spans several connections (RTSP, AirPlay video, reverse, and the
 * local player's HLS fetches), so only the first opening and last closing count.
 */
std::atomic<int> g_open_connections{0};
/* The sender's last volume (AirPlay dB), reported back as the receiver's initial volume. */
std::atomic<float> g_volume_db{0.0f};
/* Whether H.265 mirroring was offered (feature bit 42); see nativeStart. */
std::atomic<bool> g_h265_enabled{false};

// UxPlay's threads call into Java with a JNIEnv from here, which detaches them as they end.
using castbay::currentEnv;

void audioProcess(void *, raop_ntp_t *, audio_decode_struct *data) {
    if (!data) return;
    const auto ptsUs = static_cast<int64_t>(data->ntp_time_remote / 1000);
    if (data->ct == kAudioCtAacEld) {
        // Screen mirroring: decoded by MediaCodec on the Kotlin side.
        castbay::dispatchAudio(data->data, data->data_len, ptsUs);
    } else if (data->ct == kAudioCtAlac) {
        // Audio streaming (music apps): Android has no ALAC codec, so decode here.
        // audio_process always runs on the session's single RTP thread.
        static castbay::AlacDecoder decoder;
        static std::vector<int16_t> pcm;
        if (decoder.decode(data->data, data->data_len, pcm)) {
            // When the sender means it to be heard, on this device's wall clock (CLOCK_REALTIME):
            // its sync packets place it about two seconds on, which the sender allows for.
            // UxPlay never marks its NTP as answered, so ntp_time_local stays 0; but the sender's
            // clock it reads is already shifted onto ours at the first NTP reply, leaving only
            // the few milliseconds its sync offset would correct.
            const uint64_t playAtNs = data->ntp_time_local ? data->ntp_time_local : data->ntp_time_remote;
            const auto playAtUs = static_cast<int64_t>(playAtNs / 1000);
            castbay::dispatchPcm(pcm.data(), static_cast<int>(pcm.size()), playAtUs, data->data_len);
        } else {
            LOGE("Dropped a corrupt ALAC frame (%d bytes)", data->data_len);
        }
    }
}

void audioFlush(void *) {
    castbay::dispatchAudioFlush();
}

void videoProcess(void *, raop_ntp_t *, video_decode_struct *data) {
    if (!data) return;
    castbay::dispatchVideo(data->data, data->data_len, static_cast<int64_t>(data->ntp_time_remote / 1000),
                             data->is_h265);
}

/* Calls a static void AirPlayNative method; returns false if Java is unavailable. */
template <typename... Args>
bool callStatic(jmethodID method, Args... args) {
    JNIEnv *env = currentEnv();
    if (!env || !g_native_class || !method) return false;
    env->CallStaticVoidMethod(g_native_class, method, args...);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    return true;
}

void connectionStarted(void *) {
    if (g_open_connections.fetch_add(1) != 0) return;
    LOGI("AirPlay sender connected");
    callStatic(g_on_connection_started);
}

void onVideoStop(void *);

/*
 * Runs shortly after a connection closed, once UxPlay has taken it out of its table: if the
 * sender dropped its AirPlay video connection but left the reverse one open, the video
 * session is over. Closing the reverse connection then lets the session end normally.
 */
void dropOrphanedReverseSoon() {
    std::thread([] {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        bool ended = false;
        {
            std::lock_guard<std::mutex> lock(g_server_mutex);
            if (g_raop && raop_ext_drop_orphaned_reverse(g_raop)) {
                LOGI("Sender left without closing its video session; ending it");
                raop_destroy_airplay_video(g_raop, -1);
                ended = true;
            }
        }
        if (ended) {
            onVideoStop(nullptr);
            if (JavaVM *vm = castbay::jvm()) vm->DetachCurrentThread();
        }
    }).detach();
}

void connectionStopped(void *) {
    // Never below zero: a connection opened before the last restart may close afterwards.
    int open = g_open_connections.load();
    while (open > 0 && !g_open_connections.compare_exchange_weak(open, open - 1)) {}
    if (open != 1) {
        if (open > 1) dropOrphanedReverseSoon();
        return;
    }
    LOGI("AirPlay sender disconnected");
    castbay::dispatchSessionEnd();
}

/* The sender's heartbeat (POST /feedback, every two seconds while it is connected). */
void connFeedback(void *) { callStatic(g_on_feedback); }

void connectionReset(void *, int reason) {
    LOGI("AirPlay connection reset (reason %d)", reason);
    castbay::dispatchSessionEnd();
}

void onVideoStop(void *) {
    LOGI("AirPlay video: stop");
    callStatic(g_on_video_stop);
}

void videoReset(void *cls, reset_type_t type) {
    LOGI("AirPlay video reset (type %d)", static_cast<int>(type));
    switch (type) {
    case RESET_TYPE_NOHOLD:
        // A new sender took over: UxPlay is dropping the old connections. End the old
        // session on screen and show the new sender as connecting.
        LOGI("New sender took over the receiver");
        onVideoStop(cls);
        if (g_raop) raop_destroy_airplay_video(g_raop, -1);
        castbay::dispatchSessionEnd();
        callStatic(g_on_connection_started);
        break;
    case RESET_TYPE_HLS_SHUTDOWN:
        // Same cleanup as UxPlay's own video_reset: forget the playlist and drop the
        // local player's HLS connections.
        onVideoStop(cls);
        if (g_raop) {
            raop_destroy_airplay_video(g_raop, -1);
            raop_remove_hls_connections(g_raop);
        }
        break;
    case RESET_TYPE_HLS_EOS:
        onVideoStop(cls);
        break;
    default:
        break;
    }
}

void onVideoPlay(void *, const char *location, const float startPosition) {
    LOGI("AirPlay video: play %s from %.1fs", location ? location : "(null)", startPosition);
    JNIEnv *env = currentEnv();
    if (!env || !location) return;
    jstring url = env->NewStringUTF(location);
    callStatic(g_on_video_play, url, static_cast<jfloat>(startPosition));
    env->DeleteLocalRef(url);
}

void onVideoScrub(void *, const float position) {
    LOGI("AirPlay video: seek to %.1fs", position);
    callStatic(g_on_video_scrub, static_cast<jfloat>(position));
}

void onVideoRate(void *, const float rate) {
    LOGI("AirPlay video: rate %.1f", rate);
    callStatic(g_on_video_rate, static_cast<jfloat>(rate));
}

/*
 * Polled by the sender (about once a second). Kotlin returns
 * [durationSec, positionSec, rate, state, bufferEmpty, bufferFull], where state is
 * kPlaybackNotStarted, kPlaybackActive or kPlaybackFinished.
 */
bool readPlaybackInfo(double values[6]) {
    JNIEnv *env = currentEnv();
    if (!env || !g_native_class || !g_playback_info) return false;
    auto array = static_cast<jdoubleArray>(env->CallStaticObjectMethod(g_native_class, g_playback_info));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    if (!array) return false;
    bool ok = env->GetArrayLength(array) >= 6;
    if (ok) env->GetDoubleArrayRegion(array, 0, 6, values);
    env->DeleteLocalRef(array);
    return ok;
}

void onVideoAcquirePlaybackInfo(void *, playback_info_t *info) {
    double v[6] = {0, 0, 0, kPlaybackNotStarted, 1, 0};
    readPlaybackInfo(v);
    info->duration = v[0];
    info->position = v[1];
    info->rate = static_cast<float>(v[2]);
    info->seek_start = 0.0;
    info->seek_duration = v[0];
    info->playback_buffer_empty = v[4] != 0;
    info->playback_buffer_full = v[5] != 0;
    info->ready_to_play = true;
    info->playback_likely_to_keep_up = true;
    // UxPlay's /playback-info: position -1 = "not available yet" (e.g. while the playlist
    // is still being fetched), duration -1 = "finished", which tears the session down.
    if (v[3] == kPlaybackNotStarted) {
        info->position = -1.0;
    } else if (v[3] == kPlaybackFinished) {
        info->position = -1.0;
        info->duration = -1.0;
    }
}

/* The sender switched to another video; returns where to resume this one. */
float onVideoPlaylistRemove(void *) {
    double v[6] = {0, 0, 0, kPlaybackNotStarted, 1, 0};
    readPlaybackInfo(v);
    callStatic(g_on_video_rate, 0.0f);
    return static_cast<float>(v[1]);
}

void audioGetFormat(void *, unsigned char *ct, unsigned short *spf, bool *usingScreen, bool *isMedia,
                    uint64_t *audioFormat) {
    LOGI("Audio format ct=%u spf=%u usingScreen=%d isMedia=%d audioFormat=0x%llx", *ct, *spf,
         *usingScreen, *isMedia, static_cast<unsigned long long>(*audioFormat));
    if (*ct != kAudioCtAacEld && *ct != kAudioCtAlac) {
        LOGI("Audio format ct=%u has no decoder yet; audio will be silent", *ct);
    }
}

/*
 * UxPlay invokes many callbacks without null checks, so every slot is populated.
 * The ones below have no Android behaviour yet.
 */
void noop(void *) {}
double audioSetClientVolume(void *) { return g_volume_db.load(); }

void audioSetVolume(void *, float db) {
    LOGI("AirPlay volume %.1f dB", db);
    g_volume_db = db;
    castbay::dispatchVolume(db);
}
void audioSetMetadata(void *, const void *buffer, int length) {
    castbay::dispatchMetadata(buffer, length);
}

void audioSetCoverart(void *, const void *buffer, int length) {
    castbay::dispatchCoverArt(buffer, length);
}

/* Called when a sender tears its audio stream down, which iOS does a few seconds into a pause:
 * the cover stays, as the song is only paused. A new song brings its own cover, and a session
 * ending clears it. */
void audioStopCoverartRendering(void *) {}
void audioRemoteControlId(void *, const char *, const char *) {}
/* RTP timestamps at 44.1 kHz; unsigned subtraction handles wraparound. */
void audioSetProgress(void *, uint32_t *start, uint32_t *current, uint32_t *end) {
    constexpr double kRate = 44100.0;
    castbay::dispatchProgress(static_cast<uint32_t>(*current - *start) / kRate,
                                static_cast<uint32_t>(*end - *start) / kRate);
}
void videoReportSize(void *, float *, float *, float *, float *) {}
void mirrorVideoRunning(void *, bool) {}
/* Names ("eason的iPhone") and models ("iPhone15,2") go to Java as UTF-8 bytes: NewStringUTF
 * expects modified UTF-8, which emoji break. */
jbyteArray utf8Bytes(JNIEnv *env, const char *text) {
    jsize length = text ? static_cast<jsize>(strlen(text)) : 0;
    jbyteArray bytes = env->NewByteArray(length);
    if (bytes && length) env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(text));
    return bytes;
}
/*
 * A sender setting up a session: the app decides at once whether to admit it (a blocked
 * device, or a new one while new devices need approval, is not). It can't wait for an answer
 * on the TV: UxPlay serves every connection on this one thread.
 */
void reportClientRequest(void *, char *deviceid, char *model, char *name, bool *admit) {
    *admit = true;
    JNIEnv *env = currentEnv();
    if (!env || !g_native_class || !g_on_client) return;
    jstring id = env->NewStringUTF(deviceid ? deviceid : "");
    jbyteArray jname = utf8Bytes(env, name);
    jbyteArray jmodel = utf8Bytes(env, model);
    if (id && jname && jmodel) {
        *admit = env->CallStaticBooleanMethod(g_native_class, g_on_client, id, jname, jmodel) == JNI_TRUE;
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            *admit = true;
        }
        if (!*admit) LOGI("Refused a sender's session");
    }
    if (id) env->DeleteLocalRef(id);
    if (jname) env->DeleteLocalRef(jname);
    if (jmodel) env->DeleteLocalRef(jmodel);
}
/* A new sender asked to pair: the TV shows [pin] for it to enter. */
void displayPin(void *, char *pin) {
    if (!pin) return;
    LOGI("Showing a pairing PIN");
    JNIEnv *env = currentEnv();
    if (!env) return;
    jstring text = env->NewStringUTF(pin);
    if (text) callStatic(g_on_pin, text);
    if (text) env->DeleteLocalRef(text);
}
/* Every admitted session reports its sender; one that has just paired is remembered. */
void registerClient(void *, const char *device_id, const char *pk, const char *name) {
    if (!g_use_pin.load() || !pk) return;
    {
        std::lock_guard<std::mutex> lock(g_register_mutex);
        for (const auto &key : g_registered_keys) {
            if (key == pk) return;
        }
        g_registered_keys.emplace_back(pk);
    }
    LOGI("Paired a new sender");
    JNIEnv *env = currentEnv();
    if (!env) return;
    jstring key = env->NewStringUTF(pk);
    jstring id = env->NewStringUTF(device_id ? device_id : "");
    jbyteArray jname = utf8Bytes(env, name);
    if (key && id && jname) callStatic(g_on_paired, key, id, jname);
    if (key) env->DeleteLocalRef(key);
    if (id) env->DeleteLocalRef(id);
    if (jname) env->DeleteLocalRef(jname);
}
/* A sender that says it paired before: only if it really did (its key is on the list). */
bool checkRegister(void *, const char *pk) {
    if (!g_use_pin.load()) return true;
    std::lock_guard<std::mutex> lock(g_register_mutex);
    for (const auto &key : g_registered_keys) {
        if (pk && key == pk) return true;
    }
    LOGI("Refused a sender that is not paired with this TV");
    return false;
}
/* Password mode (UxPlay's pin_pw = 2): every sender enters the same password. */
const char *passwd(void *, int *len) {
    if (g_password.empty()) {
        *len = 0;  // no access control
        return nullptr;
    }
    *len = static_cast<int>(g_password.size());
    return g_password.c_str();
}
/*
 * The sender's DACP identity: its remote-control server is advertised over mDNS as
 * "iTunes_Ctrl_<dacp_id>" and accepts commands carrying "Active-Remote: <active_remote>".
 */
void exportDacp(void *, const char *active_remote, const char *dacp_id) {
    if (!active_remote || !dacp_id) return;
    LOGI("Sender remote control: DACP-ID %s", dacp_id);
    JNIEnv *env = currentEnv();
    if (!env) return;
    jstring id = env->NewStringUTF(dacp_id);
    jstring remote = env->NewStringUTF(active_remote);
    callStatic(g_on_remote_control, id, remote);
    env->DeleteLocalRef(id);
    env->DeleteLocalRef(remote);
}
/*
 * Called once per mirroring session with the codec the sender picked. A negative return
 * makes UxPlay drop the connection: better than a black screen if a sender sends H.265 when
 * it was not offered.
 */
int videoSetCodec(void *, video_codec_t codec) {
    const bool h265 = codec == VIDEO_CODEC_H265;
    LOGI("Mirroring codec: %s", h265 ? "H.265" : "H.264");
    if (h265 && !g_h265_enabled) {
        LOGE("Sender chose H.265, which this receiver did not offer; dropping the connection");
        return -1;
    }
    return 0;
}

/*
 * A mirroring sender's once-a-second streaming report (a plist UxPlay logs as XML with
 * clientFPSdata on), passed to Java for the stats overlay rather than logged. Returns whether
 * [message] was one.
 */
bool dispatchSenderReport(const char *message) {
    if (!message || !strstr(message, "<key>encoderCurrentFPS</key>")) return false;
    plist_t root = nullptr;
    plist_from_xml(message, static_cast<uint32_t>(strlen(message)), &root);
    if (!root) return false;
    // The order SenderReport.KEYS (in the app) expects.
    static const char *const kKeys[] = {
        "sentFramesAvg", "encoderCurrentFPS", "submitSurfaceFPS", "encoderDropFPS",
        "encoderQueueDropFPS", "sinkOverflowDropFPS", "rttAvg", "lossAvg", "txUsageAvg", "txCapacityAvg"
    };
    constexpr int kCount = sizeof(kKeys) / sizeof(kKeys[0]);
    jdouble values[kCount];
    for (int i = 0; i < kCount; i++) {
        plist_t node = plist_dict_get_item(root, kKeys[i]);
        values[i] = -1;  // not reported
        if (PLIST_IS_UINT(node)) {
            uint64_t value = 0;
            plist_get_uint_val(node, &value);
            values[i] = static_cast<jdouble>(value);
        } else if (PLIST_IS_REAL(node)) {
            double value = 0;
            plist_get_real_val(node, &value);
            values[i] = value;
        }
    }
    plist_free(root);
    JNIEnv *env = currentEnv();
    if (!env) return true;
    jdoubleArray array = env->NewDoubleArray(kCount);
    if (!array) return true;
    env->SetDoubleArrayRegion(array, 0, kCount, values);
    callStatic(g_on_sender_report, array);
    env->DeleteLocalRef(array);
    return true;
}

void logCallback(void *, int level, const char *message) {
    if (dispatchSenderReport(message)) return;
    const int priority = level <= LOGGER_ERR ? ANDROID_LOG_ERROR :
        (level <= LOGGER_WARNING ? ANDROID_LOG_WARN :
        (level <= LOGGER_INFO ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG));
    castbay_logf(priority, "UxPlay", "%s", message ? message : "");
}

/* Splits a DNS TXT record (length-prefixed entries) into "key=value" Java strings. */
jobjectArray txtToStringArray(JNIEnv *env, const char *txt, int length) {
    std::vector<std::string> entries;
    for (int offset = 0; txt && offset < length;) {
        int entry_len = static_cast<unsigned char>(txt[offset++]);
        if (offset + entry_len > length) break;
        entries.emplace_back(txt + offset, entry_len);
        offset += entry_len;
    }
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(entries.size()), stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    if (!result) return nullptr;
    for (size_t i = 0; i < entries.size(); ++i) {
        jstring value = env->NewStringUTF(entries[i].c_str());
        if (!value) return nullptr;
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    return result;
}

void stopLocked() {
    if (g_raop) {
        raop_stop_httpd(g_raop);
        raop_destroy(g_raop);
        g_raop = nullptr;
    }
    if (g_dnssd) {
        dnssd_destroy(g_dnssd);
        g_dnssd = nullptr;
    }
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_weenas_castbay_protocol_AirPlayNative_nativeStart(
    JNIEnv *env, jclass, jstring deviceName, jbyteArray hardwareAddress, jstring keyFile,
    jstring language, jint displayWidth, jint displayHeight, jint maxFps, jstring password,
    jboolean usePin, jobjectArray pairedKeys, jboolean allowTakeover, jboolean enableH265, jint preferredPort) {
    std::lock_guard<std::mutex> lock(g_server_mutex);
    stopLocked();
    if (!deviceName || !hardwareAddress || !keyFile || !language || !password ||
        env->GetArrayLength(hardwareAddress) != 6) return 0;
    const char *pw = env->GetStringUTFChars(password, nullptr);
    g_password = pw ? pw : "";
    if (pw) env->ReleaseStringUTFChars(password, pw);
    // A password, when set, is the access control; otherwise a PIN if chosen.
    g_use_pin = usePin == JNI_TRUE && g_password.empty();
    {
        std::lock_guard<std::mutex> registerLock(g_register_mutex);
        g_registered_keys.clear();
        const jsize count = pairedKeys ? env->GetArrayLength(pairedKeys) : 0;
        for (jsize i = 0; i < count; i++) {
            auto item = reinterpret_cast<jstring>(env->GetObjectArrayElement(pairedKeys, i));
            if (!item) continue;
            const char *text = env->GetStringUTFChars(item, nullptr);
            if (text) {
                g_registered_keys.emplace_back(text);
                env->ReleaseStringUTFChars(item, text);
            }
            env->DeleteLocalRef(item);
        }
    }
    g_open_connections = 0;

    static std::once_flag ntp_once;
    std::call_once(ntp_once, ntp_global_init);

    char address[6];
    env->GetByteArrayRegion(hardwareAddress, 0, 6, reinterpret_cast<jbyte *>(address));
    char device_id[18];
    snprintf(device_id, sizeof(device_id), "%02x:%02x:%02x:%02x:%02x:%02x",
             static_cast<unsigned char>(address[0]), static_cast<unsigned char>(address[1]),
             static_cast<unsigned char>(address[2]), static_cast<unsigned char>(address[3]),
             static_cast<unsigned char>(address[4]), static_cast<unsigned char>(address[5]));

    raop_callbacks_t callbacks{};
    callbacks.audio_process = audioProcess;
    callbacks.video_process = videoProcess;
    callbacks.video_pause = noop;
    callbacks.video_resume = noop;
    callbacks.conn_feedback = connFeedback;
    callbacks.conn_reset = connectionReset;
    callbacks.video_reset = videoReset;
    callbacks.conn_init = connectionStarted;
    callbacks.conn_destroy = connectionStopped;
    callbacks.audio_flush = audioFlush;
    callbacks.video_flush = noop;
    callbacks.audio_set_client_volume = audioSetClientVolume;
    callbacks.audio_set_volume = audioSetVolume;
    callbacks.audio_set_metadata = audioSetMetadata;
    callbacks.audio_set_coverart = audioSetCoverart;
    callbacks.audio_stop_coverart_rendering = audioStopCoverartRendering;
    callbacks.audio_remote_control_id = audioRemoteControlId;
    callbacks.audio_set_progress = audioSetProgress;
    callbacks.audio_get_format = audioGetFormat;
    callbacks.video_report_size = videoReportSize;
    callbacks.mirror_video_running = mirrorVideoRunning;
    callbacks.report_client_request = reportClientRequest;
    callbacks.display_pin = displayPin;
    callbacks.register_client = registerClient;
    callbacks.check_register = checkRegister;
    callbacks.passwd = passwd;
    callbacks.export_dacp = exportDacp;
    callbacks.video_set_codec = videoSetCodec;
    callbacks.on_video_play = onVideoPlay;
    callbacks.on_video_scrub = onVideoScrub;
    callbacks.on_video_rate = onVideoRate;
    callbacks.on_video_stop = onVideoStop;
    callbacks.on_video_acquire_playback_info = onVideoAcquirePlaybackInfo;
    callbacks.on_video_playlist_remove = onVideoPlaylistRemove;

    g_raop = raop_init(&callbacks);
    if (!g_raop) {
        LOGE("raop_init failed");
        return 0;
    }
    raop_set_log_callback(g_raop, logCallback, nullptr);
#ifdef NDEBUG
    raop_set_log_level(g_raop, LOGGER_INFO);
#else
    raop_set_log_level(g_raop, LOGGER_DEBUG);
#endif

    // The key file keeps the pairing identity stable across restarts.
    const char *key_path = env->GetStringUTFChars(keyFile, nullptr);
    // nohold: 0 = a new sender is refused (409) while one is connected; 1 = it takes over.
    int init2 = key_path ? raop_init2(g_raop, allowTakeover ? 1 : 0, device_id, key_path) : -1;
    if (key_path) env->ReleaseStringUTFChars(keyFile, key_path);
    if (init2 != 0) {
        LOGE("raop_init2 failed");
        raop_destroy(g_raop);
        g_raop = nullptr;
        return 0;
    }

    // AirPlay video (HLS): the YouTube app and similar in-app players.
    raop_set_plist(g_raop, "hls", 1);
    // Mirroring senders report once a second how they stream (frame rates, drops, round
    // trip, bandwidth); UxPlay logs them, and logCallback passes them on for the stats.
    raop_set_plist(g_raop, "clientFPSdata", 1);
    // 0: a new random PIN for each pairing.
    if (g_use_pin) raop_set_plist(g_raop, "pin", 0);
    // Music senders time playback about 1.75 s out, plus the output latency a receiver
    // reports (UxPlay's default 0.25 s). The app times music to the frame against its own
    // output, so it reports none, and pause, resume and seeks are heard that much sooner.
    raop_set_plist(g_raop, "audio_delay_micros", 0);
    // The display reported in /info; senders size and pace mirroring to it. UxPlay's default
    // maxFPS of 30 suits a Raspberry Pi; TVs decode in hardware.
    raop_set_plist(g_raop, "width", displayWidth);
    raop_set_plist(g_raop, "height", displayHeight);
    raop_set_plist(g_raop, "refreshRate", maxFps);
    raop_set_plist(g_raop, "maxFPS", maxFps);
    const char *lang = env->GetStringUTFChars(language, nullptr);
    g_lang_system = lang ? lang : "en";
    if (lang) env->ReleaseStringUTFChars(language, lang);
    raop_set_lang(g_raop, nullptr, nullptr, g_lang_system.c_str());

    const char *name = env->GetStringUTFChars(deviceName, nullptr);
    if (!name) {
        stopLocked();
        return 0;
    }
    int dnsError = 0;
    // 2 = password (advertised as pw=true), 1 = PIN shown on the TV, 0 = open.
    const unsigned char pinPw = !g_password.empty() ? 2 : (g_use_pin ? 1 : 0);
    g_dnssd = dnssd_init(name, static_cast<int>(strlen(name)), address, 6, pinPw, &dnsError);
    env->ReleaseStringUTFChars(deviceName, name);
    if (!g_dnssd) {
        LOGE("dnssd_init failed: %d", dnsError);
        stopLocked();
        return 0;
    }
    // Bit 42, SupportsScreenMultiCodec: lets senders mirror in H.265 (with a 4K display, as
    // UxPlay pairs it). Only offered when the TV has a hardware HEVC decoder.
    g_h265_enabled = enableH265 == JNI_TRUE;
    dnssd_set_airplay_features(g_dnssd, 42, g_h265_enabled ? 1 : 0);
    dnssd_set_airplay_features(g_dnssd, 0, 1);  // Video
    dnssd_set_airplay_features(g_dnssd, 4, 1);  // VideoHTTPLiveStreams
    // UxPlay turns "supports legacy pairing" off in password mode, so senders ask for it.
    if (!g_password.empty()) dnssd_set_airplay_features(g_dnssd, 27, 0);

    // 0 = let the system pick free ports.
    unsigned short tcp[3] = {0, 0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);
    // The same port as last time when it is free: senders (iPhones especially) cache the
    // advertised port, and find a receiver that keeps it again sooner after a restart.
    unsigned short port = preferredPort > 0 && preferredPort <= 65535 ? static_cast<unsigned short>(preferredPort) : 0;
    if (raop_start_httpd(g_raop, &port) < 0) {
        if (port == 0) {
            LOGE("raop_start_httpd failed");
            stopLocked();
            return 0;
        }
        LOGI("Port %d is taken, using another one", static_cast<int>(preferredPort));
        port = 0;
        if (raop_start_httpd(g_raop, &port) < 0) {
            LOGE("raop_start_httpd failed");
            stopLocked();
            return 0;
        }
    }
    raop_set_port(g_raop, port);
    // Copies the pairing public key into the dnssd record, so it must precede building TXT.
    raop_set_dnssd(g_raop, g_dnssd);
    if (dnssd_register_raop(g_dnssd, port) != 0 || dnssd_register_airplay(g_dnssd, port) != 0) {
        LOGE("Could not build DNS-SD TXT records");
        stopLocked();
        return 0;
    }
    LOGI("AirPlay protocol listening on port %u", port);
    return static_cast<jint>(port);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_weenas_castbay_protocol_AirPlayNative_nativeIsRunning(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_server_mutex);
    return g_raop && raop_is_running(g_raop) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_weenas_castbay_protocol_AirPlayNative_nativeStop(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_server_mutex);
    stopLocked();
}

/* Ends the current sender's session from the TV (e.g. the remote's Back key): its connections
   close as the receiver keeps running, so the sender sees casting stop. */
extern "C" JNIEXPORT void JNICALL
Java_com_weenas_castbay_protocol_AirPlayNative_nativeDisconnect(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_server_mutex);
    if (!g_raop) return;
    LOGI("Ending the AirPlay session from the TV");
    raop_destroy_airplay_video(g_raop, -1);
    raop_remove_known_connections(g_raop);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_weenas_castbay_protocol_AirPlayNative_nativeAirPlayTxtRecord(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_server_mutex);
    int length = 0;
    const char *txt = g_dnssd ? dnssd_get_airplay_txt(g_dnssd, &length) : nullptr;
    return txtToStringArray(env, txt, length);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_weenas_castbay_protocol_AirPlayNative_nativeRaopTxtRecord(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_server_mutex);
    int length = 0;
    const char *txt = g_dnssd ? dnssd_get_raop_txt(g_dnssd, &length) : nullptr;
    return txtToStringArray(env, txt, length);
}

extern "C" JNIEXPORT void JNICALL
Java_com_weenas_castbay_protocol_AirPlayNative_nativeSetLogFile(JNIEnv *env, jclass, jstring path, jlong maxBytes) {
    const char *file = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    castbay_log_open(file, static_cast<long>(maxBytes));
    if (file) env->ReleaseStringUTFChars(path, file);
}

// Bytes, not a String: sender names may hold characters JNI's modified UTF-8 can't take.
extern "C" JNIEXPORT void JNICALL
Java_com_weenas_castbay_protocol_AirPlayNative_nativeTestThreadExit(JNIEnv *, jclass) {
    // A thread that calls into Java the way UxPlay's do, then ends: Android 6 aborted here.
    std::thread([] { currentEnv(); }).join();
    LOGI("A Java-attached native thread exited cleanly");
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_weenas_castbay_protocol_AirPlayNative_nativeRecentLog(JNIEnv *env, jclass) {
    std::string log = castbay_recent_log();
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(log.size()));
    if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(log.size()), reinterpret_cast<const jbyte *>(log.data()));
    return bytes;
}

extern "C" JNIEXPORT void JNICALL
Java_com_weenas_castbay_protocol_AirPlayNative_nativeSetVideoSink(JNIEnv *env, jclass, jobject sink) {
    castbay::setVideoSink(env, sink);
}

extern "C" JNIEXPORT void JNICALL
Java_com_weenas_castbay_protocol_AirPlayNative_nativeSetAudioSink(JNIEnv *env, jclass, jobject sink) {
    castbay::setAudioSink(env, sink);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    castbay::initJvm(vm);
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = env->FindClass("com/weenas/castbay/protocol/AirPlayNative");
    if (!local) return JNI_ERR;
    g_native_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    g_on_connection_started = env->GetStaticMethodID(local, "onConnectionStarted", "()V");
    g_on_video_play = env->GetStaticMethodID(local, "onVideoPlay", "(Ljava/lang/String;F)V");
    g_on_video_scrub = env->GetStaticMethodID(local, "onVideoScrub", "(F)V");
    g_on_video_rate = env->GetStaticMethodID(local, "onVideoRate", "(F)V");
    g_on_video_stop = env->GetStaticMethodID(local, "onVideoStop", "()V");
    g_playback_info = env->GetStaticMethodID(local, "playbackInfo", "()[D");
    g_on_remote_control = env->GetStaticMethodID(local, "onRemoteControl", "(Ljava/lang/String;Ljava/lang/String;)V");
    g_on_client = env->GetStaticMethodID(local, "onClient", "(Ljava/lang/String;[B[B)Z");
    g_on_feedback = env->GetStaticMethodID(local, "onFeedback", "()V");
    g_on_pin = env->GetStaticMethodID(local, "onPin", "(Ljava/lang/String;)V");
    g_on_sender_report = env->GetStaticMethodID(local, "onSenderReport", "([D)V");
    g_on_paired = env->GetStaticMethodID(local, "onPaired", "(Ljava/lang/String;Ljava/lang/String;[B)V");
    if (!g_on_connection_started || !g_on_video_play || !g_on_video_scrub || !g_on_video_rate ||
        !g_on_video_stop || !g_playback_info || !g_on_remote_control || !g_on_client ||
        !g_on_feedback || !g_on_pin || !g_on_paired || !g_on_sender_report) return JNI_ERR;
    env->DeleteLocalRef(local);
    return JNI_VERSION_1_6;
}
