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

## Settings in the window / 窗口里的选项

| | |
| --- | --- |
| Quality / 画质 | Balanced 30 fps 8 Mbit/s; Smooth 60 fps 10 Mbit/s; Light 30 fps 4 Mbit/s. 均衡 / 流畅 / 省流 |
| Bitrate / 码率 | The quality choice is the most it uses: when the connection cannot keep up the bitrate goes down by a third (down to a sixth of it), and it creeps back up after twenty calm seconds; the window shows the current bitrate. 画质选项是上限：网络跟不上时码率会降三成（最低降到上限的六分之一），平静二十秒后慢慢回升，窗口里会显示当前码率 |
| Encoder / 编码器 | Automatic tries AMD, then NVIDIA, then the CPU (x264). 自动：依次尝试 AMD、NVIDIA、CPU |
| Send sound too / 同时传声音 | The sound the PC plays is sent too (and still plays on the PC). 电脑播放的声音一起传过去 |
| Start with Windows / 开机自启 | Starts with Windows and waits in the tray. 随 Windows 启动并在系统托盘待命 |
| Closing the window / 关闭窗口 | While casting, the cast keeps running in the tray (click its icon to open, or Stop/Quit in its menu); otherwise the program quits. 投屏时关闭窗口，投屏继续在托盘运行（点图标打开，菜单里可停止或退出）；没在投屏则直接退出 |

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
