//! H.264 through an `ffmpeg` child process: raw NV12 pictures in, an Annex B stream out.
//! It reaches every vendor's hardware encoder (AMF, NVENC, Quick Sync) with one set of
//! options, and x264 as the software fallback. A stopgap for measuring; see docs/design.md
//! (experiment E2) for what replaces it.

use std::io::{Read, Write};
use std::path::PathBuf;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::mpsc::{channel, Receiver};
use std::thread::JoinHandle;

use crate::{nal_types, Config, Encoded, Error};

/// Which encoder FFmpeg should use.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Codec {
    Amf,
    Nvenc,
    Qsv,
    X264,
}

impl Codec {
    pub fn parse(name: &str) -> Option<Codec> {
        match name {
            "amf" => Some(Codec::Amf),
            "nvenc" => Some(Codec::Nvenc),
            "qsv" => Some(Codec::Qsv),
            "x264" | "sw" => Some(Codec::X264),
            _ => None,
        }
    }

    fn args(self, config: &Config) -> Vec<String> {
        let rate = config.bitrate_bps.to_string();
        let gop = config.gop.to_string();
        let mut a: Vec<&str> = Vec::new();
        match self {
            Codec::Amf => a.extend(["-c:v", "h264_amf", "-usage", "ultralowlatency", "-quality", "speed", "-rc", "cbr", "-bf", "0"]),
            Codec::Nvenc => a.extend(["-c:v", "h264_nvenc", "-preset", "p1", "-tune", "ll", "-zerolatency", "1", "-rc", "cbr", "-bf", "0"]),
            Codec::Qsv => a.extend(["-c:v", "h264_qsv", "-preset", "veryfast", "-look_ahead", "0", "-bf", "0"]),
            Codec::X264 => a.extend(["-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-bf", "0"]),
        }
        let mut out: Vec<String> = a.into_iter().map(String::from).collect();
        out.extend(["-b:v".into(), rate.clone(), "-maxrate".into(), rate, "-g".into(), gop]);
        out
    }
}

pub struct FfmpegEncoder {
    child: Child,
    stdin: Option<ChildStdin>,
    units: Receiver<Vec<u8>>,
    reader: Option<JoinHandle<()>>,
    pending_pts: std::collections::VecDeque<i64>,
    pub name: String,
}

fn ffmpeg_path() -> PathBuf {
    std::env::var_os("CASTBAY_FFMPEG").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("ffmpeg"))
}

impl FfmpegEncoder {
    pub fn new(config: &Config, codec: Codec) -> Result<Self, Error> {
        let size = format!("{}x{}", config.width, config.height);
        let mut command = Command::new(ffmpeg_path());
        command
            .args(["-hide_banner", "-loglevel", "error", "-fflags", "nobuffer", "-flags", "low_delay"])
            .args(["-f", "rawvideo", "-pix_fmt", "nv12", "-s", &size, "-r", &config.fps.to_string(), "-i", "-"])
            .args(codec.args(config))
            .args(["-bsf:v", "h264_metadata=aud=insert", "-flush_packets", "1", "-f", "h264", "-"])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
        }
        let mut child = command.spawn().map_err(|e| Error(format!("cannot start ffmpeg: {e}")))?;
        let stdin = child.stdin.take();
        let mut stdout = child.stdout.take().ok_or_else(|| Error("no ffmpeg output".into()))?;
        let (send, units) = channel();
        let reader = std::thread::spawn(move || {
            // Cut the stream at access unit delimiters (NAL type 9): a unit is complete when
            // the next delimiter starts.
            let mut pending: Vec<u8> = Vec::new();
            let mut chunk = vec![0u8; 256 * 1024];
            loop {
                let n = match stdout.read(&mut chunk) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => n,
                };
                pending.extend_from_slice(&chunk[..n]);
                let mut start = 0;
                let mut i = 1;
                while i + 3 < pending.len() {
                    if pending[i] == 0 && pending[i + 1] == 0 && pending[i + 2] == 1 && pending[i + 3] & 0x1F == 9 {
                        let cut = if pending[i - 1] == 0 { i - 1 } else { i };
                        // A unit that is only a delimiter (the encoder wrote its own) belongs to the next one.
                        if cut > start + 6 && send.send(pending[start..cut].to_vec()).is_err() {
                            return;
                        }
                        if cut > start + 6 {
                            start = cut;
                        }
                        i = cut + 4;
                    } else {
                        i += 1;
                    }
                }
                pending.drain(..start);
            }
        });
        Ok(FfmpegEncoder {
            child,
            stdin,
            units,
            reader: Some(reader),
            pending_pts: Default::default(),
            name: format!("ffmpeg {codec:?}"),
        })
    }

    /// Sends one NV12 picture; returns the units that have come out meanwhile. The unit of
    /// a picture is complete only when the next picture's delimiter arrives, so output lags
    /// the input by one picture.
    pub fn encode(&mut self, nv12: &[u8], pts_us: i64) -> Result<Vec<Encoded>, Error> {
        let stdin = self.stdin.as_mut().ok_or_else(|| Error("ffmpeg closed".into()))?;
        let trace = std::env::var_os("CASTBAY_TRACE").is_some();
        if trace {
            eprintln!("[ffmpeg] writing {} bytes", nv12.len());
        }
        stdin.write_all(nv12).map_err(|e| Error(format!("ffmpeg input: {e}")))?;
        if trace {
            eprintln!("[ffmpeg] written");
        }
        self.pending_pts.push_back(pts_us);
        let mut out = Vec::new();
        while let Ok(data) = self.units.try_recv() {
            let key = nal_types(&data).contains(&5);
            out.push(Encoded { data, key, pts_us: self.pending_pts.pop_front().unwrap_or(0) });
        }
        Ok(out)
    }
}

impl FfmpegEncoder {
    /// CPU time the ffmpeg process has used so far.
    #[cfg(windows)]
    pub fn child_cpu(&self) -> std::time::Duration {
        use std::os::windows::io::AsRawHandle;
        use windows::Win32::Foundation::{FILETIME, HANDLE};
        use windows::Win32::System::Threading::GetProcessTimes;
        let (mut create, mut exit, mut kernel, mut user) =
            (FILETIME::default(), FILETIME::default(), FILETIME::default(), FILETIME::default());
        unsafe {
            let _ = GetProcessTimes(HANDLE(self.child.as_raw_handle()), &mut create, &mut exit, &mut kernel, &mut user);
        }
        let ticks = |t: FILETIME| ((t.dwHighDateTime as u64) << 32) | t.dwLowDateTime as u64;
        std::time::Duration::from_nanos((ticks(kernel) + ticks(user)) * 100)
    }
}

impl Drop for FfmpegEncoder {
    fn drop(&mut self) {
        drop(self.stdin.take());
        let _ = self.child.kill();
        let _ = self.child.wait();
        if let Some(reader) = self.reader.take() {
            let _ = reader.join();
        }
    }
}
