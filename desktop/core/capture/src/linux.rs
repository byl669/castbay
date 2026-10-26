//! Screen and window capture on Linux. Wayland lets a program see the screen only through the
//! desktop portal: it asks the person (the system's own "share your screen" dialog), and hands
//! over a PipeWire stream. This asks for what `Target` names, remembers the answer (the portal's
//! restore token, kept in the data folder) so the same share opens again without a dialog, and
//! delivers the pictures as BGRA.

use std::cell::{Cell, RefCell};
use std::os::fd::OwnedFd;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::OnceLock;
use std::time::{Duration, Instant};

use ashpd::desktop::screencast::{CursorMode, Screencast, SelectSourcesOptions, SourceType};
use ashpd::desktop::PersistMode;
use pipewire as pw;
use pw::{properties::properties, spa};

use crate::{Ended, Error, Frame, Target};

static DATA_DIR: OnceLock<PathBuf> = OnceLock::new();

pub fn set_data_dir(dir: &Path) {
    let _ = DATA_DIR.set(dir.to_path_buf());
}

fn token_file() -> Option<PathBuf> {
    DATA_DIR.get().map(|dir| dir.join("screencast-token"))
}

fn platform(e: impl std::fmt::Display) -> Error {
    Error::Platform(e.to_string())
}

/// What the portal gave: the PipeWire node to read and the connection to read it through. The
/// session must stay alive for as long as the picture is wanted (the system shows that the screen
/// is shared meanwhile), so it is kept here.
struct Share {
    node: u32,
    fd: OwnedFd,
    /// Ends the portal session; without it the next session (the engine opens a second one) waits.
    close: Box<dyn FnOnce() -> std::pin::Pin<Box<dyn std::future::Future<Output = ()>>>>,
}

async fn open_portal(target: &Target) -> Result<Share, Error> {
    let proxy = Screencast::new().await.map_err(platform)?;
    let session = proxy.create_session(Default::default()).await.map_err(platform)?;
    let token = token_file().and_then(|f| std::fs::read_to_string(f).ok()).map(|t| t.trim().to_string()).filter(|t| !t.is_empty());
    let source = match target {
        Target::Monitor(_) => SourceType::Monitor,
        Target::Window(_) => SourceType::Window,
    };
    proxy
        .select_sources(
            &session,
            SelectSourcesOptions::default()
                .set_cursor_mode(CursorMode::Embedded)
                .set_sources(ashpd::enumflags2::BitFlags::from_flag(source))
                .set_multiple(false)
                .set_restore_token(token.as_deref())
                .set_persist_mode(PersistMode::ExplicitlyRevoked),
        )
        .await
        .map_err(platform)?;
    let response = proxy
        .start(&session, None, Default::default())
        .await
        .map_err(platform)?
        .response()
        .map_err(|e| Error::NotFound(format!("nothing was shared ({e})")))?;
    if let (Some(token), Some(file)) = (response.restore_token(), token_file()) {
        if let Some(dir) = file.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let _ = std::fs::write(file, token);
    }
    let node = response.streams().first().ok_or_else(|| Error::NotFound("nothing was shared".into()))?.pipe_wire_node_id();
    let fd = proxy.open_pipe_wire_remote(&session, Default::default()).await.map_err(platform)?;
    let close = Box::new(move || {
        Box::pin(async move {
            let _ = session.close().await;
            drop(proxy);
        }) as std::pin::Pin<Box<dyn std::future::Future<Output = ()>>>
    });
    Ok(Share { node, fd, close })
}

/// The last picture, kept because the desktop sends none while nothing changes: the capture hands
/// it on again after a quiet moment so the engine always has a picture to work with.
#[derive(Default)]
struct Saved {
    width: u32,
    height: u32,
    stride: usize,
    data: Vec<u8>,
    at: Option<Instant>,
}

type OnFrame = Rc<RefCell<Box<dyn FnMut(Frame<'_>) -> bool + Send>>>;

struct State {
    format: spa::param::video::VideoInfoRaw,
    started: Instant,
    on_frame: OnFrame,
    saved: Rc<RefCell<Saved>>,
    mainloop: pw::main_loop::MainLoopRc,
    ended: Rc<Cell<Option<Ended>>>,
    failure: Rc<Cell<Option<String>>>,
    seen_picture: bool,
}

pub fn run(
    target: &Target,
    stop: impl Fn() -> bool + Send + 'static,
    on_frame: impl FnMut(Frame<'_>) -> bool + Send + 'static,
) -> Result<Ended, Error> {
    // One runtime for the whole process: the portal's D-Bus connection is shared and lives on the
    // runtime that first used it, so a second capture (the engine opens one to learn the picture
    // size) would hang if that runtime were gone.
    let runtime = runtime()?;
    let share = runtime.block_on(open_portal(target))?;
    let close = share.close;
    let result = stream(share.node, share.fd, stop, Box::new(on_frame));
    runtime.block_on(close());
    result
}

fn runtime() -> Result<&'static tokio::runtime::Runtime, Error> {
    static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    if let Some(runtime) = RUNTIME.get() {
        return Ok(runtime);
    }
    let built = tokio::runtime::Builder::new_multi_thread().worker_threads(1).enable_all().build().map_err(platform)?;
    Ok(RUNTIME.get_or_init(|| built))
}

fn stream(node: u32, fd: OwnedFd, stop: impl Fn() -> bool + 'static, on_frame: Box<dyn FnMut(Frame<'_>) -> bool + Send>) -> Result<Ended, Error> {
    pw::init();
    let mainloop = pw::main_loop::MainLoopRc::new(None).map_err(platform)?;
    let context = pw::context::ContextRc::new(&mainloop, None).map_err(platform)?;
    let core = context.connect_fd_rc(fd, None).map_err(platform)?;

    let on_frame: OnFrame = Rc::new(RefCell::new(on_frame));
    let saved = Rc::new(RefCell::new(Saved::default()));
    let ended = Rc::new(Cell::new(None));
    let failure = Rc::new(Cell::new(None));
    let state = State {
        format: Default::default(),
        started: Instant::now(),
        on_frame: on_frame.clone(),
        saved: saved.clone(),
        mainloop: mainloop.clone(),
        ended: ended.clone(),
        failure: failure.clone(),
        seen_picture: false,
    };

    let stream = pw::stream::StreamBox::new(
        &core,
        "castbay-capture",
        properties! {
            *pw::keys::MEDIA_TYPE => "Video",
            *pw::keys::MEDIA_CATEGORY => "Capture",
            *pw::keys::MEDIA_ROLE => "Screen",
        },
    )
    .map_err(platform)?;

    let _listener = stream
        .add_local_listener_with_user_data(state)
        .state_changed(|_, state, _, new| match new {
            pw::stream::StreamState::Error(message) => {
                state.failure.set(Some(message));
                state.mainloop.quit();
            }
            // The shared window was closed or the person ended the share from the system's indicator.
            pw::stream::StreamState::Unconnected if state.seen_picture => {
                state.ended.set(Some(Ended::SourceClosed));
                state.mainloop.quit();
            }
            _ => {}
        })
        .param_changed(|_, state, id, param| {
            let Some(param) = param else { return };
            if id != spa::param::ParamType::Format.as_raw() {
                return;
            }
            let Ok((media_type, media_subtype)) = spa::param::format_utils::parse_format(param) else { return };
            if media_type != spa::param::format::MediaType::Video || media_subtype != spa::param::format::MediaSubtype::Raw {
                return;
            }
            let _ = state.format.parse(param);
        })
        .process(|stream, state| {
            let Some(mut buffer) = stream.dequeue_buffer() else { return };
            let Some(data) = buffer.datas_mut().first_mut() else { return };
            let (stride, chunk_size) = (data.chunk().stride(), data.chunk().size() as usize);
            let size = state.format.size();
            if size.width == 0 || size.height == 0 {
                return;
            }
            let stride = if stride > 0 { stride as usize } else { size.width as usize * 4 };
            let Some(bytes) = data.data() else { return };
            // A picture may arrive without new content (a bare buffer): there is nothing to send.
            if chunk_size == 0 {
                return;
            }
            let wanted = stride * size.height as usize;
            if bytes.len() < wanted {
                return;
            }
            state.seen_picture = true;
            {
                let mut saved = state.saved.borrow_mut();
                saved.width = size.width;
                saved.height = size.height;
                saved.stride = stride;
                saved.data.clear();
                saved.data.extend_from_slice(&bytes[..wanted]);
                saved.at = Some(Instant::now());
            }
            let frame = Frame { width: size.width, height: size.height, stride, data: &bytes[..wanted], timestamp: state.started.elapsed() };
            if !(state.on_frame.borrow_mut())(frame) {
                state.ended.set(Some(Ended::Stopped));
                state.mainloop.quit();
            }
        })
        .register()
        .map_err(platform)?;

    // BGRx / BGRA only: the same byte order as the Windows capture, so nothing needs reordering.
    // No buffer parameters are given, which keeps the pictures in shared memory (a DMA-BUF would
    // need a GPU readback this program does not do).
    let object = spa::pod::object!(
        spa::utils::SpaTypes::ObjectParamFormat,
        spa::param::ParamType::EnumFormat,
        spa::pod::property!(spa::param::format::FormatProperties::MediaType, Id, spa::param::format::MediaType::Video),
        spa::pod::property!(spa::param::format::FormatProperties::MediaSubtype, Id, spa::param::format::MediaSubtype::Raw),
        spa::pod::property!(
            spa::param::format::FormatProperties::VideoFormat,
            Choice,
            Enum,
            Id,
            spa::param::video::VideoFormat::BGRx,
            spa::param::video::VideoFormat::BGRx,
            spa::param::video::VideoFormat::BGRA,
        ),
        spa::pod::property!(
            spa::param::format::FormatProperties::VideoSize,
            Choice,
            Range,
            Rectangle,
            spa::utils::Rectangle { width: 1920, height: 1080 },
            spa::utils::Rectangle { width: 16, height: 16 },
            spa::utils::Rectangle { width: 8192, height: 8192 }
        ),
        spa::pod::property!(
            spa::param::format::FormatProperties::VideoFramerate,
            Choice,
            Range,
            Fraction,
            spa::utils::Fraction { num: 60, denom: 1 },
            spa::utils::Fraction { num: 0, denom: 1 },
            spa::utils::Fraction { num: 1000, denom: 1 }
        ),
    );
    let bytes: Vec<u8> = spa::pod::serialize::PodSerializer::serialize(std::io::Cursor::new(Vec::new()), &spa::pod::Value::Object(object))
        .map_err(|e| platform(format!("{e:?}")))?
        .0
        .into_inner();
    let mut params = [spa::pod::Pod::from_bytes(&bytes).ok_or_else(|| platform("the format request is not valid"))?];
    stream
        .connect(spa::utils::Direction::Input, Some(node), pw::stream::StreamFlags::AUTOCONNECT | pw::stream::StreamFlags::MAP_BUFFERS, &mut params)
        .map_err(platform)?;

    // A static desktop sends no pictures, but the person may still stop: look every 100 ms.
    let (looper, started, idle_ended) = (mainloop.clone(), Instant::now(), ended.clone());
    let timer = mainloop.loop_().add_timer(move |_| {
        if stop() {
            ended_stopped(&looper);
            return;
        }
        // Nothing changed for a while: hand the last picture on again.
        let mut saved = saved.borrow_mut();
        if saved.at.is_some_and(|at| at.elapsed() >= Duration::from_millis(500)) {
            saved.at = Some(Instant::now());
            let frame = Frame { width: saved.width, height: saved.height, stride: saved.stride, data: &saved.data, timestamp: started.elapsed() };
            if !(on_frame.borrow_mut())(frame) {
                idle_ended.set(Some(Ended::Stopped));
                looper.quit();
            }
        }
    });
    timer
        .update_timer(Some(Duration::from_millis(100)), Some(Duration::from_millis(100)))
        .into_result()
        .map_err(|e| platform(format!("{e:?}")))?;

    mainloop.run();

    if let Some(message) = failure.take() {
        return Err(Error::Platform(message));
    }
    Ok(ended.take().unwrap_or(Ended::Stopped))
}

fn ended_stopped(mainloop: &pw::main_loop::MainLoopRc) {
    mainloop.quit();
}
