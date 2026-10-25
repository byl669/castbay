// With the `ffmpeg-lib` feature, compiles the C shim against the FFmpeg that
// tools/build-ffmpeg builds from the submodules (or the one FFMPEG_DIR points to) and links it.
fn main() {
    println!("cargo:rerun-if-changed=csrc/shim.c");
    println!("cargo:rerun-if-env-changed=FFMPEG_DIR");
    if std::env::var_os("CARGO_FEATURE_FFMPEG_LIB").is_none() {
        return;
    }
    let dir = std::env::var_os("FFMPEG_DIR").map(std::path::PathBuf::from).unwrap_or_else(|| {
        std::path::Path::new(&std::env::var_os("CARGO_MANIFEST_DIR").unwrap()).join("../../build/ffmpeg")
    });
    cc::Build::new().file("csrc/shim.c").include(dir.join("include")).compile("castbay_shim");
    println!("cargo:rustc-link-search=native={}", dir.join("lib").display());
    for lib in ["avcodec", "avutil", "x264"] {
        println!("cargo:rustc-link-lib=static={lib}");
    }
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        for lib in ["bcrypt", "ole32", "user32", "ws2_32", "secur32", "advapi32", "oleaut32", "uuid"] {
            println!("cargo:rustc-link-lib={lib}");
        }
    }
}
