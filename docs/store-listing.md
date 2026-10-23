# AnySave · 商店发布资料

> 状态：内部发布资料（M20）。APK 由 GitHub Actions 构建并签名，Release 页面直接下载；
> 本文档用于应用商店/分发渠道上架时复制使用。

## 基本信息

| 项 | 内容 |
|---|---|
| 应用名 | AnySave |
| 包名 | `io.github.backtraxe.anysave` |
| 版本 | 0.20.0（versionCode 26） |
| 分类 | 工具 / 媒体与视频 |
| 平台 | Android 8.0+（minSdk 26，targetSdk 35） |
| 语言 | 简体中文 / English |
| 价格 | 免费、无广告、无内购 |
| 许可证 | GPL-3.0（含 NewPipeExtractor，GPL-3.0） |
| 应用图标 | `docs/images/app-icon.png`（扁平青绿 + 奶油书签；自适应 + monochrome） |
| 源码 | https://github.com/backtraxe/anysave |
| 下载 | https://github.com/backtraxe/anysave/releases |

## 文案

### 一句话简介（≤30 字）

粘贴链接，预览挑选，一键保存到相册（可选同步 Telegram）。

### 短描述（≤80 字）

AnySave 用真实 WebView 打开你粘贴的链接，展示页面已产生的图片/视频，支持筛选、多选、相册保存，并可把保存结果自动上传到你自己的 Telegram 机器人。

### 长描述

AnySave 是一个「先看、再选、再存」的媒体保存工具，不破解、不签名、不重放站点接口：
它用真实 WebView 打开你给的链接，只读取页面自己已经加载出来的数据。

支持站点
- 抖音（图文/视频，需要登录）
- 小红书（图文/视频；支持 `xhslink.cn`/`xhslink.com` 分享短链，普通链接需带 xsec_token）
- B 站（视频 DASH 合流，登录后可选 1080P/720P/480P）
- 微博（图文/视频）
- YouTube（专用提取器，最高 720p）
- AcFun（文章图文）

主要功能
- 预览网格：全部/图片/视频筛选、多选、全选，视频可查看
- 保存到相册：并发下载、进度通知、容器嗅探纠正扩展名（PNG/JPG/MP4）
- Telegram 同步（可选）：相册分组（每组 ≤10）、相册发送失败自动重试、失败降级与单条重试、
  失败清单可查看并只重传失败文件；caption 自动带 `#作者 #平台` 标签；超大图片自动压缩入相册
- 登录态：应用内扫码登录（站点桌面登录页），或粘贴浏览器导出的 Cookie（Cookie-Editor JSON / Cookie 头 / Netscape）
- 登录态检测、按站点退出、超时后的登录诊断
- 保存失败一键重试（面板与通知）
- B 站画质选择
- reader 热更新：站点改版时可通过远端包更新页面提取脚本（无需发版）

隐私
- 所有解析、保存、上传都在设备本地完成；没有 AnySave 的服务器
- 登录态只存在系统 WebView 的应用私有存储，不导出、不打印
- Telegram token 只保存在应用私有设置；上传目标是你自己的机器人/会话
- 无广告、无统计 SDK、无追踪

免责声明：请只保存你有权保存的内容，并遵守各站点服务条款；本项目与各站点无关联。

## 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` | 打开链接、下载媒体、可选的 Telegram 上传 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 保存/上传期间保持任务存活并显示进度通知 |
| `POST_NOTIFICATIONS`（Android 13+，运行时） | 显示保存/上传进度与完成通知；拒绝不影响保存 |
| `WRITE_EXTERNAL_STORAGE`（仅 Android ≤28） | 旧系统写入相册 |

应用不请求通讯录、位置、相机、麦克风、短信等权限。

## 隐私声明（可粘贴到商店后台）

AnySave 不收集、不上传任何个人数据。应用在设备本地使用 WebView 打开用户提供的链接；
站点 Cookie/登录态保存在系统 WebView 的应用私有目录中，不会导出或发送到第三方服务器。
Telegram 上传是可选功能，由用户配置自己的 Bot Token 与 Chat ID，媒体文件只会发送到该用户
指定的会话。应用不含广告、分析或追踪 SDK。源码以 GPL-3.0 公开，可自行审计。

## 图标

- 自适应图标（API 26+）：`res/mipmap-anydpi-v26/ic_launcher.xml`（+ `ic_launcher_round.xml`）
- 背景：`res/drawable/ic_launcher_background.xml`（扁平青绿 `#0F766E`）
- 前景：`res/drawable/ic_launcher_foreground.xml`（一枚奶油 `#FFF6E9` 书签，
  全部落在 66dp 安全区；无箭头、无播放三角、无渐变/装饰）
- 主题图标：`res/drawable/ic_launcher_monochrome.xml`
- 预览：`docs/images/app-icon.png`
- 语义：书签 = 保存/收藏，一眼可辨且不会被误读为播放器

## 色彩系统（与图标一致）

| Token | 值 | 用途 |
|---|---|---|
| brand | `#0F766E` | 主按钮（Extract/Save）、进度条、选中 pill 文字、链接 |
| brand_pressed | `#0B5F58` | 主按钮按下 |
| accent | `#F9735B` | 选中边框 + 勾选 chip（与图标珊瑚点缀一致） |
| accent_pressed | `#E05A44` | 选中态按下 |
| canvas | `#F6F4EF` | 预览/设置奶油画布 |
| surface | `#FFFFFF` | 卡片/输入框 |
| brand_tint | `#1A0F766E` | 次级按钮/筛选选中底 |
| text_secondary | `#5F5A50` | 次级文字 |
| danger | `#A8402C` | 保存/上传失败 |

## 应用内诊断

设置页底部「诊断 → 查看日志」保留最近 2000 条运行日志（版本、系统、机型、提取/保存/上传
关键事件），支持复制/分享/清空。日志不包含 token/Cookie 值，URL 签名参数会打码。

## 截图清单

| 截图 | 文件 | 建议文案 |
|---|---|---|
| 主界面（M23） | `docs/images/m23-main.png` | 青绿主按钮，粘贴即用 |
| 选中态（M23） | `docs/images/m23-preview-selected.png` | 珊瑚选中边框/勾选 |
| 设置（M23） | `docs/images/m23-settings.png` | 统一青绿设置界面 |
| 抖音预览 | `docs/images/m0-douyin.png` | 粘贴链接，先预览再保存 |
| 小红书图文 | `docs/images/m4-xhs.png` | 图文筛选与多选 |
| B 站视频 | `docs/images/m5-bili.png` | DASH 合流保存为单 MP4 |
| Telegram 设置 | `docs/images/m3-settings.png` | 可选：保存后自动同步 |
| 登录卡片 | `docs/images/m9-settings-login.png` | 应用内扫码登录 |
| 登录态检测 | `docs/images/m11-login-all-logged-in.png` | 三站登录态一目了然 |
| B 站画质 | `docs/images/m14-quality.png` | Auto / 1080P / 720P / 480P |
| 失败重试 | `docs/images/m18-retry-failed.png` | 失败项一键重试 |
| YouTube | `docs/images/m8-youtube.png` | YouTube 专用提取器 |
| 微博 | `docs/images/m8-weibo.png` | 微博图文/视频 |

## 版本历史（摘要）

| 版本 | 内容 |
|---|---|
| 0.30.0 | 应用内日志/诊断面板（复制、分享、清空、签名打码） |
| 0.29.0 | Telegram 图片格式/尺寸兼容、重复上传修复、429 退避 |
| 0.28.0 | 下载失败自动重试；Telegram 相册重试/重平衡；caption 作者+平台标签；大图压缩 |
| 0.27.0 | 小红书 xhslink 短链支持；分享文案 URL 提取 |
| 0.26.0 | 极简书签图标（无箭头，扁平青绿） |
| 0.25.0 | 极简下载图标（箭头 + 托盘线，扁平青绿） |
| 0.24.0 | 图标改为下载箭头 + 收纳袋（青绿配色不变） |
| 0.23.0 | 青绿品牌配色；图标与 App 内颜色统一（青绿/珊瑚/奶油） |
| 0.22.0 | 品牌图标重设计（深紫/奶油/薄荷，书签+播放） |
| 0.21.0 | 首版自适应图标（橙/下载箭头） |
| 0.20.0 | 上传失败清单/重试、M18/M19 收尾、商店文档 |
| 0.19.0 | 通知内失败重试、Telegram 分组 mock 端到端 |
| 0.18.0 | 失败项重试、上传分组兜底、视频流细化 |
| 0.17.0 | 小红书视频笔记、诊断回归、JSON 导入验证 |
| 0.16.0 | 提取超时的登录态诊断 |
| 0.15.0 | 小红书桌面页（风控规避） |
| 0.14.0 | B 站画质选择 |
| 0.13.0 | 按站点退出登录 + 剪贴板导入 |
| 0.12.0 | Cookie 粘贴导入 |
| 0.11.x | 三站登录态检测 |
| 0.10.0 | AcFun 图文 |
| 0.9.0 | 登录/会话管理 |
| 0.8.x | YouTube（NewPipeExtractor）+ 微博 |
| 0.7.x | reader 热更新 |
| ≤0.6 | 抖音/小红书/B 站、保存、Telegram、CI/CD |

## 发布检查清单

- [ ] `./gradlew :app:testDebugUnitTest` 全绿（当前 79 条）
- [ ] `./gradlew :app:assembleRelease :app:namedReleaseApk` 生成签名 APK
- [ ] 模拟器冒烟：提取/预览/保存/相册可见、Telegram（可选）、登录态检测
- [ ] `git tag vX.Y.Z && git push origin vX.Y.Z` → Actions 自动发布 Release
- [ ] Release 附件为 `AnySave-X.Y.Z.apk`，签名证书 SHA-256 与历史一致
- [ ] 商店截图与文案更新（本文档 + README 截图索引）
