//! Fitting a captured picture into the size the stream was offered at: big screens are scaled
//! down (a 4K screen goes out as 1080p), a window that changes size is centred with black
//! bars instead of freezing the picture.

use crate::bgra_to_nv12;

/// The stream size for a captured `w`x`h` picture: the same shape, no larger than `max_w`x`max_h`
/// and never upscaled, both sides even (H.264 4:2:0 needs that).
pub fn fit_size(w: u32, h: u32, max_w: u32, max_h: u32) -> (u32, u32) {
    let (w, h, max_w, max_h) = (w as u64, h as u64, max_w as u64, max_h as u64);
    let (nw, nh) = if w <= max_w && h <= max_h {
        (w, h)
    } else if w * max_h >= h * max_w {
        (max_w, h * max_w / w)
    } else {
        (w * max_h / h, max_h)
    };
    (((nw as u32).max(2)) & !1, ((nh as u32).max(2)) & !1)
}

/// Converts a BGRA picture of `sw`x`sh` to NV12 (BT.709 limited range) of exactly `dw`x`dh`
/// into `out` (`dw * dh * 3 / 2` bytes). The picture is scaled down to fit, never up, keeping its
/// shape and centred; what is left is black. Same size in and out is the plain fast conversion.
pub fn bgra_to_nv12_fit(src: &[u8], stride: usize, sw: usize, sh: usize, dw: usize, dh: usize, out: &mut [u8]) {
    assert!(dw % 2 == 0 && dh % 2 == 0 && out.len() >= dw * dh * 3 / 2 && sw >= 2 && sh >= 2);
    if sw == dw && sh == dh {
        return bgra_to_nv12(src, stride, sw & !1, sh & !1, out);
    }
    let (fw, fh) = fit_size(sw as u32, sh as u32, dw as u32, dh as u32);
    let (fw, fh) = (fw as usize, fh as usize);
    let ox = ((dw - fw) / 2) & !1;
    let oy = ((dh - fh) / 2) & !1;

    let (luma, chroma) = out.split_at_mut(dw * dh);
    if fw != dw || fh != dh {
        luma.fill(16);
        chroma[..dw * dh / 2].fill(128);
    }
    // One job per pair of output rows that the picture covers.
    let mut jobs: Vec<(usize, &mut [u8], &mut [u8])> = luma
        .chunks_mut(2 * dw)
        .zip(chroma.chunks_mut(dw))
        .enumerate()
        .filter(|(pair, _)| pair * 2 >= oy && pair * 2 < oy + fh)
        .map(|(pair, (l, c))| (pair, l, c))
        .collect();
    let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2).min(8);
    let per = jobs.len().div_ceil(threads).max(1);
    std::thread::scope(|scope| {
        while !jobs.is_empty() {
            let tail = jobs.split_off(per.min(jobs.len()));
            let head = std::mem::replace(&mut jobs, tail);
            scope.spawn(move || {
                for (pair, l, c) in head {
                    for x in (ox..ox + fw).step_by(2) {
                        let (mut r, mut g, mut b) = (0i32, 0i32, 0i32);
                        for dy in 0..2 {
                            for dx in 0..2 {
                                let (pr, pg, pb) = average(src, stride, sw, sh, fw, fh, x + dx - ox, pair * 2 + dy - oy);
                                l[dy * dw + x + dx] = (((47 * pr + 157 * pg + 16 * pb + 128) >> 8) + 16) as u8;
                                r += pr;
                                g += pg;
                                b += pb;
                            }
                        }
                        let (r, g, b) = ((r + 2) >> 2, (g + 2) >> 2, (b + 2) >> 2);
                        c[x] = ((((-26 * r - 87 * g + 112 * b + 128) >> 8) + 128).clamp(0, 255)) as u8;
                        c[x + 1] = ((((112 * r - 102 * g - 10 * b + 128) >> 8) + 128).clamp(0, 255)) as u8;
                    }
                }
            });
        }
    });
}

/// The average colour of the source area that output pixel (`u`, `v`) of a `fw`x`fh` picture covers.
#[allow(clippy::too_many_arguments)]
fn average(src: &[u8], stride: usize, sw: usize, sh: usize, fw: usize, fh: usize, u: usize, v: usize) -> (i32, i32, i32) {
    let x0 = u * sw / fw;
    let x1 = ((u + 1) * sw / fw).clamp(x0 + 1, sw);
    let y0 = v * sh / fh;
    let y1 = ((v + 1) * sh / fh).clamp(y0 + 1, sh);
    let (mut r, mut g, mut b) = (0u32, 0u32, 0u32);
    for y in y0..y1 {
        let row = &src[y * stride + x0 * 4..y * stride + x1 * 4];
        for px in row.chunks_exact(4) {
            b += px[0] as u32;
            g += px[1] as u32;
            r += px[2] as u32;
        }
    }
    let n = ((x1 - x0) * (y1 - y0)) as u32;
    (((r + n / 2) / n) as i32, ((g + n / 2) / n) as i32, ((b + n / 2) / n) as i32)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn solid(w: usize, h: usize, b: u8, g: u8, r: u8) -> Vec<u8> {
        (0..w * h).flat_map(|_| [b, g, r, 255]).collect()
    }

    #[test]
    fn sizes_fit_without_upscaling_and_stay_even() {
        assert_eq!(fit_size(3840, 2160, 1920, 1080), (1920, 1080));
        assert_eq!(fit_size(1920, 1200, 1920, 1080), (1728, 1080));
        assert_eq!(fit_size(1280, 720, 1920, 1080), (1280, 720));
        assert_eq!(fit_size(3440, 1440, 1920, 1080), (1920, 803 & !1));
        assert_eq!(fit_size(1920, 1080, 1920, 1080), (1920, 1080));
        assert_eq!(fit_size(1001, 601, 1920, 1080), (1000, 600));
        assert_eq!(fit_size(1, 1, 1920, 1080), (2, 2));
    }

    #[test]
    fn the_same_size_is_the_plain_conversion() {
        let src: Vec<u8> = (0..8 * 4 * 4).map(|i| (i * 7) as u8).collect();
        let (mut a, mut b) = (vec![0u8; 8 * 4 * 3 / 2], vec![0u8; 8 * 4 * 3 / 2]);
        bgra_to_nv12(&src, 8 * 4, 8, 4, &mut a);
        bgra_to_nv12_fit(&src, 8 * 4, 8, 4, 8, 4, &mut b);
        assert_eq!(a, b);
    }

    #[test]
    fn a_solid_picture_stays_solid_when_scaled_down() {
        let src = solid(16, 16, 200, 60, 30);
        let mut plain = vec![0u8; 16 * 16 * 3 / 2];
        bgra_to_nv12(&src, 16 * 4, 16, 16, &mut plain);
        let mut small = vec![0u8; 4 * 4 * 3 / 2];
        bgra_to_nv12_fit(&src, 16 * 4, 16, 16, 4, 4, &mut small);
        assert!(small[..16].iter().all(|&y| y == plain[0]));
        assert!(small[16..].chunks(2).all(|uv| uv == &plain[256..258]));
    }

    #[test]
    fn a_wide_picture_is_centred_with_black_bars() {
        // 8x4 white into 4x6: scaled to 4x2, two black rows above and below.
        let src = solid(8, 4, 255, 255, 255);
        let mut out = vec![0u8; 4 * 6 * 3 / 2];
        bgra_to_nv12_fit(&src, 8 * 4, 8, 4, 4, 6, &mut out);
        let row = |y: usize| &out[y * 4..y * 4 + 4];
        assert!(row(0).iter().chain(row(1)).chain(row(4)).chain(row(5)).all(|&y| y == 16));
        assert!(row(2).iter().chain(row(3)).all(|&y| y >= 234));
        // The chroma of the bars is neutral.
        assert_eq!(&out[24..28], &[128, 128, 128, 128]);
    }

    #[test]
    fn a_smaller_picture_is_not_stretched() {
        // 4x4 into 8x8: stays 4x4 in the middle.
        let src = solid(4, 4, 255, 255, 255);
        let mut out = vec![0u8; 8 * 8 * 3 / 2];
        bgra_to_nv12_fit(&src, 16, 4, 4, 8, 8, &mut out);
        assert_eq!(out[0], 16);
        assert!(out[2 * 8 + 2] >= 234 && out[5 * 8 + 5] >= 234);
        assert_eq!(out[6 * 8 + 6], 16);
    }
}
