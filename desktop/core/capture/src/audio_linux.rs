//! The sound the PC plays, on Linux: a PipeWire capture stream on the default output's monitor.
//! Same interface as the Windows module.

use std::time::Duration;

use pipewire as pw;
use pw::{properties::properties, spa};

use crate::Error;

pub const SAMPLE_RATE: u32 = 48_000;
pub const CHANNELS: u32 = 2;

fn platform(e: impl std::fmt::Display) -> Error {
    Error::Platform(e.to_string())
}

struct State<'a> {
    on_samples: &'a mut dyn FnMut(&[f32]),
}

/// Delivers the audio the default output plays to `on_samples` (interleaved stereo floats) until
/// `stop` says so. PipeWire converts whatever the device uses to the format asked for.
pub fn run_loopback(stop: &dyn Fn() -> bool, mut on_samples: impl FnMut(&[f32])) -> Result<(), Error> {
    pw::init();
    let mainloop = pw::main_loop::MainLoopRc::new(None).map_err(platform)?;
    let context = pw::context::ContextRc::new(&mainloop, None).map_err(platform)?;
    let core = context.connect_rc(None).map_err(platform)?;

    let stream = pw::stream::StreamBox::new(
        &core,
        "castbay-audio",
        properties! {
            *pw::keys::MEDIA_TYPE => "Audio",
            *pw::keys::MEDIA_CATEGORY => "Capture",
            *pw::keys::MEDIA_ROLE => "Music",
            // Capture what the default output plays instead of the microphone.
            *pw::keys::STREAM_CAPTURE_SINK => "true",
        },
    )
    .map_err(platform)?;

    let _listener = stream
        .add_local_listener_with_user_data(State { on_samples: &mut on_samples })
        .process(|stream, state| {
            let Some(mut buffer) = stream.dequeue_buffer() else { return };
            let Some(data) = buffer.datas_mut().first_mut() else { return };
            let (offset, size) = {
                let chunk = data.chunk();
                (chunk.offset() as usize, chunk.size() as usize)
            };
            let Some(bytes) = data.data() else { return };
            let Some(bytes) = bytes.get(offset..offset + size) else { return };
            let samples: Vec<f32> = bytes.chunks_exact(4).map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]])).collect();
            if !samples.is_empty() {
                (state.on_samples)(&samples);
            }
        })
        .register()
        .map_err(platform)?;

    let mut info = spa::param::audio::AudioInfoRaw::new();
    info.set_format(spa::param::audio::AudioFormat::F32LE);
    info.set_rate(SAMPLE_RATE);
    info.set_channels(CHANNELS);
    let object = spa::pod::Object {
        type_: spa::utils::SpaTypes::ObjectParamFormat.as_raw(),
        id: spa::param::ParamType::EnumFormat.as_raw(),
        properties: info.into(),
    };
    let bytes: Vec<u8> = spa::pod::serialize::PodSerializer::serialize(std::io::Cursor::new(Vec::new()), &spa::pod::Value::Object(object))
        .map_err(|e| platform(format!("{e:?}")))?
        .0
        .into_inner();
    let mut params = [spa::pod::Pod::from_bytes(&bytes).ok_or_else(|| platform("the format request is not valid"))?];
    stream
        .connect(
            spa::utils::Direction::Input,
            None,
            pw::stream::StreamFlags::AUTOCONNECT | pw::stream::StreamFlags::MAP_BUFFERS | pw::stream::StreamFlags::RT_PROCESS,
            &mut params,
        )
        .map_err(platform)?;

    // SAFETY: the timer only runs inside `mainloop.run()` below and is gone when this function returns.
    let stop: &'static dyn Fn() -> bool = unsafe { std::mem::transmute(stop) };
    let looper = mainloop.clone();
    let timer = mainloop.loop_().add_timer(move |_| {
        if stop() {
            looper.quit();
        }
    });
    timer
        .update_timer(Some(Duration::from_millis(100)), Some(Duration::from_millis(100)))
        .into_result()
        .map_err(|e| platform(format!("{e:?}")))?;

    mainloop.run();
    Ok(())
}
