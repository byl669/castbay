// A flat C interface to FFmpeg's libavcodec for the Rust side, so no struct layouts are
// mirrored there. One encoder instance turns NV12 pictures into Annex B access units.
#include <stdio.h>
#include <string.h>
#include <libavcodec/avcodec.h>
#include <libavutil/opt.h>
#include <libavutil/frame.h>
#include <libavutil/error.h>

typedef struct CbEnc {
    AVCodecContext *ctx;
    AVFrame *frame;
    AVPacket *pkt;
} CbEnc;

static void fail(char *err, int len, const char *what, int code) {
    char text[128] = "";
    if (code < 0) av_strerror(code, text, sizeof text);
    snprintf(err, len, "%s%s%s", what, code < 0 ? ": " : "", text);
}

void cb_enc_close(CbEnc *e) {
    if (!e) return;
    av_packet_free(&e->pkt);
    av_frame_free(&e->frame);
    avcodec_free_context(&e->ctx);
    av_free(e);
}

CbEnc *cb_enc_open(const char *name, int width, int height, int fps, int bitrate, int gop,
                   char *err, int errlen) {
    const AVCodec *codec = avcodec_find_encoder_by_name(name);
    if (!codec) { fail(err, errlen, "encoder not built in", 0); return NULL; }
    CbEnc *e = av_mallocz(sizeof *e);
    e->ctx = avcodec_alloc_context3(codec);
    e->frame = av_frame_alloc();
    e->pkt = av_packet_alloc();
    AVCodecContext *c = e->ctx;
    c->width = width;
    c->height = height;
    c->time_base = (AVRational){1, 1000000};
    c->framerate = (AVRational){fps, 1};
    c->pix_fmt = AV_PIX_FMT_NV12;
    c->bit_rate = bitrate;
    c->rc_max_rate = bitrate;
    c->rc_buffer_size = bitrate / fps * 2;
    c->gop_size = gop;
    c->max_b_frames = 0;
    c->thread_count = 0;
    c->color_range = AVCOL_RANGE_MPEG;
    c->colorspace = AVCOL_SPC_BT709;
    c->color_primaries = AVCOL_PRI_BT709;
    c->color_trc = AVCOL_TRC_BT709;
    c->flags |= AV_CODEC_FLAG_LOW_DELAY | AV_CODEC_FLAG_GLOBAL_HEADER * 0;
    AVDictionary *o = NULL;
    if (!strcmp(name, "libx264")) {
        av_dict_set(&o, "preset", "ultrafast", 0);
        av_dict_set(&o, "tune", "zerolatency", 0);
        av_dict_set(&o, "x264-params", "repeat-headers=1:aud=0:sliced-threads=1", 0);
    } else if (!strcmp(name, "h264_amf")) {
        av_dict_set(&o, "usage", "ultralowlatency", 0);
        av_dict_set(&o, "quality", "speed", 0);
        av_dict_set(&o, "rc", "cbr", 0);
        av_dict_set(&o, "forced_idr", "1", 0);
        av_dict_set(&o, "header_spacing", "1", 0);
        av_dict_set(&o, "async_depth", "1", 0);
    } else if (!strcmp(name, "h264_nvenc")) {
        av_dict_set(&o, "preset", "p1", 0);
        av_dict_set(&o, "tune", "ull", 0);
        av_dict_set(&o, "rc", "cbr", 0);
        av_dict_set(&o, "forced-idr", "1", 0);
        av_dict_set(&o, "zerolatency", "1", 0);
        av_dict_set(&o, "delay", "0", 0);
    }
    int r = avcodec_open2(c, codec, &o);
    av_dict_free(&o);
    if (r < 0) { fail(err, errlen, "open", r); cb_enc_close(e); return NULL; }
    e->frame->format = c->pix_fmt;
    e->frame->width = width;
    e->frame->height = height;
    if ((r = av_frame_get_buffer(e->frame, 0)) < 0) { fail(err, errlen, "frame buffer", r); cb_enc_close(e); return NULL; }
    return e;
}

// Encodes one NV12 picture (tightly packed). Returns 1 with a packet in *out (valid until the
// next call), 0 when the encoder produced nothing yet, or a negative error code.
int cb_enc_encode(CbEnc *e, const uint8_t *nv12, int64_t pts_us, int force_key,
                  const uint8_t **out, int *out_len, int *key, int64_t *out_pts) {
    AVFrame *f = e->frame;
    int r = av_frame_make_writable(f);
    if (r < 0) return r;
    int w = f->width, h = f->height;
    for (int y = 0; y < h; y++) memcpy(f->data[0] + (size_t)y * f->linesize[0], nv12 + (size_t)y * w, w);
    const uint8_t *uv = nv12 + (size_t)w * h;
    for (int y = 0; y < h / 2; y++) memcpy(f->data[1] + (size_t)y * f->linesize[1], uv + (size_t)y * w, w);
    f->pts = pts_us;
    f->pict_type = force_key ? AV_PICTURE_TYPE_I : AV_PICTURE_TYPE_NONE;
    if (force_key) f->flags |= AV_FRAME_FLAG_KEY; else f->flags &= ~AV_FRAME_FLAG_KEY;
    r = avcodec_send_frame(e->ctx, f);
    if (r < 0) return r;
    av_packet_unref(e->pkt);
    r = avcodec_receive_packet(e->ctx, e->pkt);
    if (r == AVERROR(EAGAIN)) return 0;
    if (r < 0) return r;
    *out = e->pkt->data;
    *out_len = e->pkt->size;
    *key = (e->pkt->flags & AV_PKT_FLAG_KEY) != 0;
    *out_pts = e->pkt->pts;
    return 1;
}
