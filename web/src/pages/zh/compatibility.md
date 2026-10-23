---
layout: ../../layouts/Doc.astro
lang: zh
page: compatibility
title: '兼容性 – 映湾 CastBay'
description: '映湾已经测试过的电视、手机和 App，以及各自的支持情况。'
heading: '兼容性'
intro: '映湾目前已测试过的设备和 App。在列表之外的设备上用过，或者发现哪里不能用？欢迎<a href="/zh/feedback">告诉我们</a>。'
---

## 支持的投屏功能

映湾支持 **AirPlay 屏幕镜像、兼容 App 的音乐和视频投送，以及 DLNA/UPnP 媒体播放**。支持范围以下方已验证的具体功能为准，不笼统宣称“支持 AirPlay 2”，也不代表具备包括多房间同步音频在内的完整 AirPlay 2 能力。

| 功能 | 支持范围 |
| --- | --- |
| AirPlay 屏幕镜像 | 已验证 iPhone 和 Mac；iPad 尚未确认 |
| AirPlay 音乐投送 | 已验证下方列出的 iPhone App；不支持 Mac 的“音乐”App |
| AirPlay 视频投送 | 已验证下方列出的兼容 App；支持情况取决于 App 和内容 |
| DLNA/UPnP 媒体播放 | 接收兼容 App 的媒体投送，不等同于安卓屏幕镜像 |
| AirPlay 2 多房间同步音频 | 不支持 |

## 电视和车机

| 设备 | Android 版本 | 状态 |
| --- | --- | --- |
| 索尼 BRAVIA XR-55X90L（4K） | 12 | ✅ 已测试：H.265 4K 屏幕镜像及其他全部功能 |
| TCL 电视 | 9 | ✅ 已测试 |
| 小米电视 4（MiTV4，晶晨，32 位） | 6.0.1 | ✅ 已测试：iPhone 屏幕镜像、音乐；解码延迟约 0.3 秒，比新电视高 |
| Google TV Streamer | 14 | ✅ 已安装运行，带硬件 HEVC 解码（接 4K 屏幕时可 4K 镜像） |
| 比亚迪车机 DiLink 5.0（触摸屏） | 12 | ✅ 已测试：屏幕镜像、音乐、视频、方向盘按键切歌和暂停；熄火后会被系统强行停止，启动车辆后需手动打开映湾 |

映湾需要 Android 6.0 及以上的安卓设备。其他品牌的电视、电视盒子、车机和平板应该也能用，遥控器和触摸屏都支持；4K H.265 屏幕镜像需要电视带硬件 HEVC 解码（主界面会显示支持的镜像规格）。

## 手机和电脑

| 设备 | 系统 | 状态 |
| --- | --- | --- |
| iPhone 17 Pro Max | iOS 26 | ✅ 已测试：屏幕镜像、音乐、视频、PIN 码配对 |
| 三星 Galaxy（安卓手机） | Android 16 | ✅ 已测试：网易云音乐 DLNA 投屏 |
| iPad | iPadOS | ❔ 尚未确认，和 iPhone 使用相同的 AirPlay |
| MacBook：屏幕镜像 | macOS | ✅ 已测试：1080p 和 4K 屏幕镜像；延迟见[常见问题](/zh/faq) |
| Mac："音乐"App | macOS | ❌ 不支持：它使用的加密方式，开源 AirPlay 库无法兼容 |

## App

| App | 投屏方式 | 状态 |
| --- | --- | --- |
| Apple Music | AirPlay | ✅ 歌词同步，暂停后恢复无缝接上 |
| 网易云音乐（iPhone） | AirPlay | ✅ 可用；在电视上暂停后，电视会把已收到的两秒左右播完 |
| 网易云音乐（安卓） | DLNA | ⚠️ 可以播放，显示歌名和封面；手机上的播放状态不一定同步 |
| YouTube（iPhone） | AirPlay | ✅ 4K 电视上最高 4K；电视需要能直接访问 YouTube |
| YouTube（安卓） | — | ❌ 只支持 Google Cast，映湾无法接收 |
| 爱奇艺 | AirPlay、屏幕镜像 | ✅ 可用 |
| 哔哩哔哩 | DLNA | ⚠️ 可用，最高 720P（哔哩哔哩对其他设备的限制） |
| QQ 音乐 | DLNA | ❔ 尚未确认 |
| 任意 App | 屏幕镜像 | ✅ 手机屏幕上显示什么就投什么 |

## 媒体格式（DLNA）

应用自带的投屏按钮（DLNA）会把一个链接发给电视，由电视播放。映湾会告诉应用自己能播哪些格式，应用一般只在这些格式下才把映湾列为投屏目标：

| 类型 | 格式 | 状态 |
| --- | --- | --- |
| 视频文件 | MP4、MOV、MKV、WebM、FLV、MPEG-TS、MPEG-PS | ✅ 可以播放；里面的编码要电视能解码（H.264 都可以；H.265、VP9、AV1 要电视有对应的硬件解码器） |
| 流媒体视频 | HLS（.m3u8）、DASH（.mpd） | ✅ 可以播放，清晰度随网络自动调整；链接里没有 .m3u8 或 .mpd 时，只要应用说明了格式也能播放 |
| 音乐 | MP3、AAC（M4A）、WAV、Ogg（Vorbis、Opus） | ✅ 可以播放，显示音乐界面 |
| 音乐 | FLAC | ✅ Android 8.1 及以上；更早的版本没有 FLAC 解码器，映湾在这些设备上不声明支持 |
| 图片 | JPEG、PNG 等 | ❌ 不显示：相册类应用不会列出映湾，硬投过来也会被拒绝 |

电视放不了的链接，会提示"这台电视无法播放该视频格式"并结束投屏。iPhone 应用通过 AirPlay 投的视频都是 HLS 格式。有版权保护（DRM）的视频和微软 Smooth Streaming 格式不支持。

## 不支持的投屏方式

- **Google Cast（Chromecast）**：目前不支持 Google Cast／Chromecast 投送及镜像，请使用 AirPlay 或 DLNA。
- **Miracast 和安卓自带的屏幕镜像**：需要电视系统级的权限，普通 App 拿不到。
- **通过 DLNA 投照片**：映湾能播视频和音乐，不显示图片；想在电视上看照片，可以用屏幕镜像。
