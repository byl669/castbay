# CastBay Desktop: user guide / 使用说明

CastBay Desktop casts a Windows PC's screen or a single window, with its sound, to a device
running CastBay (a TV, a tablet, a car display). It uses its own encrypted connection, not
AirPlay or DLNA, so the delay is low (about a quarter of a second on a home network).

映湾桌面端把 Windows 电脑的屏幕（或某个窗口）连同声音投到运行映湾的设备上（电视、平板、车机）。
它使用自己的加密连接，不是 AirPlay 或 DLNA，所以延迟很低（家庭网络里约四分之一秒）。

## What you need / 需要什么

- Windows 10 or 11, 64-bit. The Microsoft Edge WebView2 runtime (already part of Windows 11 and
  recent Windows 10).
- A device with CastBay (Android 6 or newer) on the same network, with **Desktop casting**
  turned on in its settings.
- Windows 10/11 64 位，带有 Edge WebView2 运行库（Windows 11 和较新的 Windows 10 已自带）。
- 同一网络里运行映湾的设备（Android 6 及以上），并在它的设置里开启“电脑投屏”。

## Install / 安装

Two ways, same program. / 两种方式，程序是同一个。

- **Installer** (`CastBay-Desktop-…-setup.exe`): double-click, next, finish. No administrator
  rights needed. It adds a Start menu entry (and, if you tick it, a desktop shortcut) and an entry
  in Settings > Apps for uninstalling. Running a newer installer updates in place and keeps your
  pairings. It installs the WebView2 runtime too if the PC has none.
- **Portable** (`CastBay-Desktop-…-windows-x64.zip`): unzip anywhere and run
  **CastBay Desktop.exe**. Nothing is installed; delete the folder to remove it.
- **安装包**（`-setup.exe`）：双击、下一步、完成，不需要管理员权限。它会添加开始菜单项（可选桌面快捷方式）
  和“设置→应用”里的卸载入口；运行新版安装包会原地升级并保留配对记录；电脑没有 WebView2 时会一并装上。
- **便携版**（`.zip`）：解压到任意位置，运行 **CastBay Desktop.exe**，不安装任何东西，删掉文件夹就卸载了。

## First time / 第一次使用

1. Run the installer or the program. It is not code-signed yet, so Windows SmartScreen may say it
   protected your PC: choose **More info**, then **Run anyway**.
2. If the Windows firewall asks, allow **private networks**. Without it receivers cannot be found.
3. Pick your receiver in the list, choose a screen or a window, and press **Start casting**.
4. The first time, the receiver shows a 6-digit code: type it in the window. Later casts need no code.

1. 运行安装包或程序。目前还没有代码签名，Windows SmartScreen 可能提示已保护你的电脑：
   点“更多信息”，再点“仍要运行”。
2. 如果 Windows 防火墙询问，请允许**专用网络**，否则找不到接收端。
3. 在列表里选接收端，选择屏幕或窗口，点“开始投屏”。
4. 第一次连接时接收端会显示 6 位配对码，在窗口里输入即可，以后不用再输。

## Linux / Linux 版

Ubuntu or Debian, 64-bit, with GNOME (or another desktop with a working screen-sharing portal) on
Wayland, PipeWire for the sound and the desktop portal (`xdg-desktop-portal`). Install the
`.deb` from the release page: `sudo apt install ./castbay-desktop_<version>_amd64.deb`, then start
**CastBay Desktop** from the applications menu. It was tested on Ubuntu 26.04 with GNOME.

适用于 64 位 Ubuntu / Debian，桌面为 GNOME（或其他屏幕共享门户可用的桌面），使用 Wayland，声音走 PipeWire，
并带有桌面门户（`xdg-desktop-portal`）。从发布页下载 `.deb`，执行
`sudo apt install ./castbay-desktop_<版本>_amd64.deb`，然后在应用程序菜单里打开 **CastBay Desktop**。
已在装有 GNOME 的 Ubuntu 26.04 上测试。

- **What to share / 投什么:** there is no screen or window list in the window. When you press
  **Start casting** the system asks which screen or window to share. Tick **remember this choice**
  and it does not ask again. 窗口里没有屏幕/窗口列表，点“开始投屏”时由系统询问要共享哪个屏幕或窗口，
  勾选“记住此选择”后不再询问。
- **If the remembered choice goes stale / 记住的选择失效时:** the system may stop answering for it. After 15 seconds
  CastBay Desktop forgets it and the system's dialog comes back; choose again. (The choice is kept in
  `~/.local/share/castbay-desktop/screencast-token`; deleting that file does the same.)
  系统可能不再响应记住的选择：15 秒后 CastBay Desktop 会忘掉它，系统弹窗重新出现，再选一次即可。
  （选择保存在 `~/.local/share/castbay-desktop/screencast-token`，删除该文件效果相同。）
- **Tray / 托盘:** closing the window during a cast keeps the cast running in the tray. GNOME shows no tray icons by default
  (install the "AppIndicator" extension to see one); without it, open **CastBay Desktop** from the applications menu again and
  the running window comes back. 投屏中关闭窗口，投屏会继续在托盘里运行。GNOME 默认不显示托盘图标（安装 AppIndicator 扩展即可）；
  没有扩展时，再次从应用程序菜单打开 **CastBay Desktop**，正在运行的窗口会回到前台。
- **Start with the session / 开机自启:** the setting writes `~/.config/autostart/castbay-desktop.desktop`. 设置里的开关会写入这个文件。
- **Encoders / 编码:** NVIDIA GPUs use NVENC, everything else x264 on the CPU (AMD and Intel hardware encoders are not built in yet). NVIDIA 显卡用 NVENC，其他用 CPU 的 x264（AMD、Intel 硬件编码暂未支持）。

## The window / 窗口

**Home / 主页:** pick a receiver, choose a screen or a window, press **Start casting**. The line above the button shows the quality and sound in use; **Change** opens the settings.
主页：选接收端，选屏幕或窗口，点“开始投屏”。按钮上方一行显示当前画质和是否带声音，点“更改”进入设置。

**Casting / 投屏中:** shows where it casts to and the size. **Stop casting** ends it. **Details** (decode time, frame rate, dropped pictures, bitrate) is folded away. Closing the window keeps the cast running in the tray.
投屏中：显示投到哪里、画面大小，“停止投屏”结束投屏；“详细信息”默认收起。关闭窗口，投屏继续在托盘里运行。

**Settings (the sliders icon) / 设置（右上角滑块图标）:**

| | |
| --- | --- |
| Quality / 画质 | Low bitrate 30 fps 4 Mbit/s; Balanced 30 fps 8 Mbit/s; High FPS 60 fps 10 Mbit/s. The chosen quality is the most that is used: when the connection cannot keep up the bitrate goes down by a third (to a sixth of it at most) and creeps back up after twenty calm seconds. 画质选项是上限：网络跟不上时码率降三成（最低降到上限的六分之一），平静二十秒后慢慢回升 |
| Send sound too / 同时传声音 | The sound the PC plays is sent too (and still plays on the PC). 电脑播放的声音一起传过去 |
| Encoder / 编码器 | Automatic tries AMD, then NVIDIA, then the CPU (x264). 自动：依次尝试 AMD、NVIDIA、CPU |
| Start with Windows / 开机自动启动 | Starts with Windows and waits in the tray. 随 Windows 启动并在系统托盘待命 |
| Language / 语言 | Follow the system, Chinese or English (also for the tray menu). 跟随系统、中文或英文（托盘菜单同步） |
| Paired receivers / 已配对的接收端 | The receivers paired before, with the last time connected; **Forget** removes one (the next cast pairs again). 配对过的接收端和上次连接时间；“忘记”后下次投屏需要重新配对 |
| About / 关于 | Version, check for updates (and the Update button, which installs the new version), open the log folder, licence and source. 版本、检查更新、日志文件夹、许可证与源码 |

Changes apply to the next cast. 修改在下一次投屏时生效。

## Uninstall / 卸载

Settings > Apps > CastBay Desktop > Uninstall; it asks whether to delete your pairings and logs too. Portable: delete the folder (settings stay in `%APPDATA%\com.weenas.castbay.desktop`). 设置→应用→CastBay Desktop→卸载，会询问是否同时删除配对记录和日志；便携版直接删文件夹（设置保存在 `%APPDATA%\com.weenas.castbay.desktop`）。

## Troubleshooting / 常见问题

- **No receiver in the list / 列表里没有接收端**: both devices on the same network (not a guest
  network); Desktop casting on at the receiver; firewall allows private networks. 两台设备在同一网络（不要用访客网络）；
  接收端已开启电脑投屏；防火墙允许专用网络。
- **"Needs someone to allow it" / 需要有人在接收端确认**: the receiver asks on its own screen the first time. 接收端第一次会在它的屏幕上询问。
- **"Another device is using it" / 接收端正被另一台设备使用**: stop the other cast first, or turn on takeover at the receiver. 先停掉另一台，或在接收端允许接管。
- **Picture frozen while the PC is locked / 电脑锁屏后画面不动**: Windows gives nothing to capture on the lock screen; the window says so and the cast goes on when you unlock. While casting, the PC does not fall asleep or switch its screen off by itself. 锁屏时 Windows 不提供屏幕画面，窗口会提示，解锁后自动继续；投屏期间电脑不会自己休眠或息屏。
- **Receiver was reset / 接收端被重置过**: if the window says its key changed, choose **Forget this receiver** and pair again. 提示密钥变化时点“忘记这个接收端”并重新配对。
- **Log files / 日志**: the **Open log folder** button at the bottom of the window. 窗口底部的“打开日志文件夹”。

## Privacy and security / 隐私与安全

The connection is encrypted (TLS) and both sides prove who they are with their own keys; a
receiver's key is remembered after the first pairing, and a changed key is refused until you
forget the receiver. Nothing leaves your network: there is no account and no server. The only
internet request is the optional check for a newer version on GitHub.
连接经过加密（TLS），双方用各自的密钥证明身份；配对后会记住接收端的密钥，密钥变化会被拒绝，直到你选择忘记它。
数据不出局域网：没有账号，也没有服务器。唯一的联网请求是到 GitHub 检查是否有新版本。
