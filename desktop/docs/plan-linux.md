# 方案二：桌面端 Linux 版（在 R9000 上开发）

## 1. 目标与范围

在 R9000 上做出和 Windows 版**功能对等**的 Linux 版：发现接收端、配对、投屏（整屏/窗口）、声音、
硬件编码、断线重连、码率自适应、托盘、开机自启、单实例、投屏时保持清醒、锁屏提示、日志、更新提示，
并打成安装包。第一版以 R9000 的环境为准：Ubuntu 26.04、GNOME 50（Wayland）、PipeWire 1.6、RTX 4070。

## 2. R9000 环境调查结果（只读，未安装任何东西）

| 项目 | 情况 |
| --- | --- |
| 桌面 | GNOME Shell 50.1，**Wayland 会话**（Ubuntu 新版已不再提供 GNOME 的 X11 会话），已登录在本机 tty2 |
| 音视频 | PipeWire 1.6.2 + WirePlumber + pipewire-pulse |
| 屏幕录制 | xdg-desktop-portal 的 **ScreenCast v5 可用**（支持"记住选择"） |
| 托盘 | `ubuntu-appindicators` 扩展已启用 |
| GPU | RTX 4070 Laptop，驱动 580，`libnvidia-encode` 在（前面已验证 NVENC 编码可用） |
| 工具链 | gcc、cmake、pkg-config、node 有；**没有 Rust**；没有 nasm |
| 开发包 | WebKitGTK、GTK3、AppIndicator、PipeWire、OpenSSL、D-Bus 等**都没装** |
| 权限/网络 | sudo 免密可用；可直连 rust-lang、crates.io、GitHub、Ubuntu 源 |
| 资源 | 32 线程、60 GB 内存、379 GB 空闲 |

## 3. 各部分的技术方案

| 功能 | Windows 现状 | Linux 方案 |
| --- | --- | --- |
| 屏幕/窗口捕获 | Windows Graphics Capture | **xdg-desktop-portal ScreenCast + PipeWire**（Rust：`ashpd` 调门户，`pipewire` 收帧）。Wayland 下只能走这条，弹的是系统自己的"共享屏幕"选择框；用 `restore_token` 记住选择，之后不再弹。先走内存拷贝（SHM），DMA-BUF 作为以后的优化 |
| 来源选择 UI | 应用里自己列屏幕/窗口 | Linux 上改成一个"选择要投的内容…"按钮，由系统对话框选择（应用拿不到窗口列表，这是 Wayland 的安全设计） |
| 声音 | WASAPI 环回 | PipeWire：捕获默认输出的 monitor（48 kHz 立体声 float），之后复用同一个 AAC 编码和协议 |
| 视频编码 | FFmpeg 进程内：AMF / NVENC / x264 | 同一套 C 封装 + 同一批子模块，在 Linux 上用 autotools 编静态库：**NVENC（已验证）+ x264**；VAAPI（AMD/Intel）作为后续 |
| 保持清醒 | SetThreadExecutionState | 通过 logind/门户的 Inhibit（`idle`+`sleep`），投屏结束释放 |
| 锁屏检测 | OpenInputDesktop | 订阅 logind 会话的 `LockedHint`（D-Bus）+ GNOME ScreenSaver 的 `ActiveChanged` |
| 开机自启 | 注册表 Run | `~/.config/autostart/*.desktop`（带 `--minimized`） |
| 托盘 | Tauri tray | 同一份 Tauri 代码（Linux 用 libayatana-appindicator；GNOME 靠已启用的扩展） |
| 单实例 | tauri 单实例插件 | 同一个插件（Linux 走 D-Bus） |
| 界面 | WebView2 | **WebKitGTK 4.1**，同一份 HTML/CSS/JS，需要逐屏检查渲染差异 |
| 打开日志目录/链接 | explorer / rundll32 | `xdg-open` |
| 发现/协议/重连/码率/配对 | 已跨平台 | 无需改（mdns-sd、rustls 本来就是跨平台） |
| 数据目录 | `%APPDATA%` | Tauri 自带的 XDG 目录（`~/.local/share/com.weenas.castbay.desktop`） |
| 安装包 | MSI + 便携 zip | `.deb`（先做）+ 便携 tar.gz；AppImage 视情况后做 |

## 4. 代码改造（保证 Windows 版不受影响）

1. `core/capture`：把现在只在 Windows 编译的捕获抽成统一接口，Windows 实现原样保留，新增 `linux.rs`
   （门户 + PipeWire）；`Target` 增加"由系统选择"的变体。
2. `core/capture` 的声音、`core/sender` 的电源/锁屏模块：各按平台拆成 `windows.rs` / `linux.rs`。
3. `core/sender` 的引擎去掉 `cfg(windows)` 门槛，改用上面的统一接口；CLI 同理。
4. `app/`：把 Windows 独有的部分（注册表自启、WebView2 检查、explorer、rundll32、防火墙说明）收进 `platform`
   模块并提供 Linux 实现；界面里的"屏幕/窗口下拉"在 Linux 上换成系统选择按钮。
5. `tools/build-ffmpeg.sh`：补 Linux 分支（或新增 `build-ffmpeg-linux.sh`）。
6. CI：新增 Ubuntu 任务（编译 + 单元测试 + 装包）；发布流程增加 Linux 产物。
7. 每改一步，都要在 Windows 笔记本上重跑回归（投屏、声音、重连），确保没有退化。

## 5. 里程碑与 PR 拆分

- **M0 环境与重构**：R9000 装工具链；FFmpeg 子模块在 Linux 编出静态库；引擎/捕获接口抽象（Windows 行为不变，回归通过）。
- **M1 画面**：Linux 捕获（门户 + PipeWire）→ 命令行从 R9000 投到 Sony，NVENC 与 x264 各测一轮延迟/CPU。
- **M2 声音与系统集成**：声音、保持清醒、锁屏提示、开机自启、托盘。
- **M3 图形界面**：Tauri 窗口在 Linux 上跑通（同一份界面），逐屏检查 WebKitGTK 渲染。
- **M4 打包与文档**：`.deb` + CI + 使用说明 + 实测数据（`benchmarks.md`）+ 限制清单更新。
- 之后（可选）：VAAPI（AMD/Intel）、AppImage、X11 回退（给仍用 X11 的发行版）、DMA-BUF 零拷贝。

> 界面优化（方案一）和 Linux 版（方案二）都动 `app/` 的界面和平台代码。**建议先做方案二的 M0–M1（后端和捕获，不碰界面），
> 与方案一并行；M3 之前先完成方案一，这样 Linux 上直接用新界面，不用做两遍。**

## 6. 需要在 R9000 上安装的东西（请你确认）

- **Rust 工具链**：`rustup`（装在 `~/.cargo`，用户级，不需要 sudo），约 1 GB。
- **apt 开发包**（需要 sudo，已确认免密）：`libwebkit2gtk-4.1-dev libgtk-3-dev libayatana-appindicator3-dev
  librsvg2-dev libssl-dev libdbus-1-dev libpipewire-0.3-dev libspa-0.2-dev libclang-dev clang nasm
  xdg-utils`（约 600 MB 含依赖）；VAAPI 阶段再加 `libva-dev`。
- **源码**：FFmpeg、x264、nv-codec-headers 子模块（约 120 MB，从 Mac 传过去，不走 GitHub）。
- 不改任何系统设置、驱动、桌面配置。

## 7. 需要你配合的事

1. 同意上面的安装清单。
2. **第一次投屏时在 R9000 屏幕前点一次**系统的"共享屏幕"弹窗，并勾选"记住选择"（GNOME 的安全设计，只能真人点；之后的测试就不用了）。
3. 保持 R9000 登录着图形桌面（现在就是）。

## 8. 风险

- Wayland 门户的交互限制（首次弹窗、权限被撤销时要重新选择）：用 `restore_token` 缓解，界面上要写清楚。
- WebKitGTK 和 WebView2 渲染/字体差异：M3 逐屏截图检查。
- 托盘依赖 GNOME 扩展：其他桌面（KDE、纯 GNOME 无扩展）行为不同，先保证 Ubuntu 默认环境，其余写进限制清单。
- 包的 glibc 兼容：CI 用较老的 Ubuntu 镜像构建，避免在旧发行版上跑不起来。
- PipeWire 的 Rust 绑定需要 clang/bindgen，构建链比 Windows 版更长，要把 CI 缓存做好。
- 许可证：沿用 GPL-3.0；FFmpeg/x264 的源码随发布附带，做法同 Windows 版。
