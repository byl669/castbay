//! The in-process encoders against the real FFmpeg libraries (feature `ffmpeg-lib`): one access
//! unit per picture, key frames on request, an AAC stream a receiver can decode.
#![cfg(feature = "ffmpeg-lib")]

use castbay_encode::ffmpeg::Codec;
use castbay_encode::inproc::{AacEncoder, InProcessEncoder};
use castbay_encode::{nal_types, Config};

const W: usize = 640;
const H: usize = 360;

fn picture(i: usize) -> Vec<u8> {
    let mut nv12 = vec![128u8; W * H * 3 / 2];
    for y in 0..H {
        for x in 0..W {
            nv12[y * W + x] = ((x + y * 2 + i * 7) & 0xFF) as u8;
        }
    }
    nv12
}

fn config() -> Config {
    Config { width: W as u32, height: H as u32, fps: 30, bitrate_bps: 2_000_000, gop: 60 }
}

/// 60 pictures, a key frame forced at 20 and 40: every picture comes out at once, the first
/// carries the parameter sets, the forced ones are key frames.
fn check(codec: Codec) {
    let mut encoder = InProcessEncoder::new(&config(), codec).unwrap_or_else(|e| panic!("{codec:?}: {e}"));
    let mut keys = Vec::new();
    for i in 0..60 {
        let unit = encoder
            .encode(&picture(i), i as i64 * 33_333, i == 20 || i == 40)
            .unwrap_or_else(|e| panic!("{codec:?} picture {i}: {e}"))
            .unwrap_or_else(|| panic!("{codec:?} held picture {i} back"));
        if i == 0 {
            let types = nal_types(&unit.data);
            assert!(types.contains(&7) && types.contains(&8) && types.contains(&5), "{codec:?}: the first unit lacks SPS, PPS or IDR: {types:?}");
        }
        if unit.key {
            keys.push(i);
        }
    }
    assert!(keys.contains(&0) && keys.contains(&20) && keys.contains(&40), "{codec:?}: key frames at {keys:?}");
}

#[test]
fn x264_makes_one_unit_per_picture_and_key_frames_on_request() {
    check(Codec::X264);
}

#[test]
fn nvenc_does_the_same_where_there_is_an_nvidia_gpu() {
    match InProcessEncoder::new(&config(), Codec::Nvenc) {
        Ok(_) => check(Codec::Nvenc),
        Err(e) => eprintln!("skipped: no NVENC here ({e})"),
    }
}

#[test]
fn aac_gives_frames_and_the_config_a_decoder_needs() {
    let mut aac = AacEncoder::new(48_000, 2, 128_000).expect("aac");
    // AAC-LC, 48 kHz, stereo: the two bytes 0x11 0x90. FFmpeg's encoder appends a three byte
    // "no SBR" marker after them, which decoders (and the receiver, up to five bytes) accept.
    let config = aac.config();
    assert!(config.starts_with(&[0x11, 0x90]) && config.len() <= 5, "config {config:02x?}");
    assert_eq!(aac.frame_samples(), 1024);
    let samples: Vec<f32> = (0..1024 * 2).map(|i| ((i / 2) as f32 * 0.05).sin() * 0.5).collect();
    let mut frames = 0;
    for _ in 0..20 {
        if let Some(frame) = aac.encode(&samples).expect("encode") {
            assert!(!frame.is_empty() && frame.len() < 4000);
            frames += 1;
        }
    }
    assert!(frames >= 18, "{frames} frames out of 20 inputs");
    assert!(aac.encode(&samples[..100]).is_err(), "a wrong sample count is refused");
}
