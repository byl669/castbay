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

## First time / 第一次使用

1. Unzip the download and run **CastBay Desktop.exe**. It is not code-signed yet, so Windows
   SmartScreen may say it protected your PC: choose **More info**, then **Run anyway**.
2. If the Windows firewall asks, allow **private networks**. Without it receivers cannot be found.
3. Pick your receiver in the list, choose a screen or a window, and press **Start casting**.
4. The first time, the receiver shows a 6-digit code: type it in the window. Later casts need no code.

1. 解压后运行 **CastBay Desktop.exe**。目前还没有代码签名，Windows SmartScreen 可能提示已保护你的电脑：
   点“更多信息”，再点“仍要运行”。
2. 如果 Windows 防火墙询问，请允许**专用网络**，否则找不到接收端。
3. 在列表里选接收端，选择屏幕或窗口，点“开始投屏”。
4. 第一次连接时接收端会显示 6 位配对码，在窗口里输入即可，以后不用再输。

## Settings in the window / 窗口里的选项

| | |
| --- | --- |
| Quality / 画质 | Balanced 30 fps 8 Mbit/s; Smooth 60 fps 10 Mbit/s; Light 30 fps 4 Mbit/s. 均衡 / 流畅 / 省流 |
| Encoder / 编码器 | Automatic tries AMD, then NVIDIA, then the CPU (x264). 自动：依次尝试 AMD、NVIDIA、CPU |
| Send sound too / 同时传声音 | The sound the PC plays is sent too (and still plays on the PC). 电脑播放的声音一起传过去 |

## Troubleshooting / 常见问题

- **No receiver in the list / 列表里没有接收端**: both devices on the same network (not a guest
  network); Desktop casting on at the receiver; firewall allows private networks. 两台设备在同一网络（不要用访客网络）；
  接收端已开启电脑投屏；防火墙允许专用网络。
- **"Needs someone to allow it" / 需要有人在接收端确认**: the receiver asks on its own screen the first time. 接收端第一次会在它的屏幕上询问。
- **"Another device is using it" / 接收端正被另一台设备使用**: stop the other cast first, or turn on takeover at the receiver. 先停掉另一台，或在接收端允许接管。
- **Picture frozen while the PC is locked / 电脑锁屏后画面不动**: Windows stops screen capture on the lock screen. 锁屏时 Windows 不提供屏幕画面。
- **Receiver was reset / 接收端被重置过**: if the window says its key changed, choose **Forget this receiver** and pair again. 提示密钥变化时点“忘记这个接收端”并重新配对。
- **Log files / 日志**: the **Open log folder** button at the bottom of the window. 窗口底部的“打开日志文件夹”。

## Privacy and security / 隐私与安全

The connection is encrypted (TLS) and both sides prove who they are with their own keys; a
receiver's key is remembered after the first pairing, and a changed key is refused until you
forget the receiver. Nothing leaves your network: there is no account and no server. The only
internet request is the optional check for a newer version on GitHub.
连接经过加密（TLS），双方用各自的密钥证明身份；配对后会记住接收端的密钥，密钥变化会被拒绝，直到你选择忘记它。
数据不出局域网：没有账号，也没有服务器。唯一的联网请求是到 GitHub 检查是否有新版本。
