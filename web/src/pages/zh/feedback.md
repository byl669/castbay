---
layout: ../../layouts/Doc.astro
lang: zh
page: feedback
title: '反馈与参与 – 映湾 CastBay'
description: '如何报告映湾的问题、提出功能建议，或者告诉我们它在你的电视上可以用。'
heading: '反馈与参与'
intro: '映湾是开源项目，用户的反馈是它变得更好的原因。一切都在 <a href="https://github.com/weenas/castbay/issues">GitHub</a> 上进行（发帖需要一个免费账号）。'
---

## 报告问题

**在映湾 Desktop（电脑端）里**：设置 → 关于 → **诊断信息** → **上传日志**，会得到一个报告编号（形如 CB-7K3F9Q）；然后到 [GitHub](https://github.com/weenas/castbay/issues) 提一个 Issue，写上你做了什么、版本号（在设置 → 关于里）和这个编号。

<p class="report-id" id="report-id" hidden>你的报告编号：<strong></strong>。打开下面的问题报告时会自动填入。</p>

提交一份[问题报告](https://github.com/weenas/castbay/issues/new?template=bug_report.yml)，表单会引导你填写找原因需要的信息：

- **映湾的版本**：在"关于"页里。
- **电视**：品牌、型号和 Android 版本（一般在电视设置的"关于"里）。
- **手机或电脑**：型号和系统版本，比如 iPhone 17、iOS 26。
- **投屏方式和 App**：屏幕镜像、App 里的 AirPlay 图标，还是 App 自带的投屏按钮（DLNA）；比如 Apple Music、YouTube。
- **发生了什么**、你期望的结果，以及怎样能复现。拍一张电视的照片或一段短视频会很有帮助。
- **诊断信息**（车机这类没法接电脑调试的设备尤其有用）：问题出现后，打开映湾的"关于 → 诊断信息"，点击**上传日志**，提交问题时附上显示的报告编号（用手机扫它的二维码会打开本页并自动带上编号）。设备没有网络时，把这一页拍照附上即可。报告包含哪些内容见[隐私政策](/zh/privacy#问题报告仅在你主动发送时)。
- **网络情况**：Wi-Fi 还是网线，以及特殊情况，比如 Mesh 组网、VPN 或代理。

提交前可以先看看[常见问题](/zh/faq)，以及是否已经有人[报告过](https://github.com/weenas/castbay/issues)。不要写入隐私信息，比如密码、住址、电话号码。可以用中文填写。

## 告诉我们在你的设备上能用

在[兼容性列表](/zh/compatibility)之外的电视、手机或 App 上用过映湾？无论能不能用，都欢迎[告诉我们](https://github.com/weenas/castbay/issues/new?template=compatibility.yml)，我们会把它加进列表。

## 功能建议

欢迎提交[功能建议](https://github.com/weenas/castbay/issues/new?template=feature_request.yml)：你想做什么，以及为什么。

## 参与开发

映湾以 GPL-3.0 协议开源，欢迎通过 Pull Request 贡献代码、翻译或修改这个网站；[README](https://github.com/weenas/castbay#readme) 里写了如何构建 App 和网站。
