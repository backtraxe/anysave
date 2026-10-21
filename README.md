# AnySave

> Pick, preview and keep media from any web page.

从任意网页里，先看、再选、再存。

AnySave 是一个 Android 原生应用：**无服务端、无 Termux、无 Cookie 导出**。
它用真实的 WebView 打开页面，让页面自己的 JavaScript 完成签名和请求，应用只负责
捕获响应、预览、勾选、保存，并按需上传到 Telegram。

## 状态

**M2 保存**（可编译、可运行、已端到端验证）。目前打通的完整链路：

- 输入链接 → WebView 打开页面
- 文档开始时注入响应监听（`app/src/main/res/raw/hook.js`），命中时连同响应体一起留下
- 捕获页面自身发出的 `/aweme/v1/web/aweme/detail/` 响应
- 抖音 reader 把 `aweme_detail` 解析成媒体清单：图文取 `images[]`（跳过水印版、优先
  jpeg），视频取 `play_addr`，并带作者、文案、尺寸与时长
- 图文笔记页是服务端渲染（SSR），根本不会发 detail 请求：改为读取页面已经内嵌的
  RSC 数据（`window.__pace_f`），**不构造任何请求、不算任何签名**
- 预览面板：3 列缩略图网格、全部 / 图片 / 视频筛选、多选、长按全屏查看、返回网页
- **保存**：勾选后点「保存」→ 前台服务并发下载（最多 3 个）→ 写入系统相册
  `Pictures/AnySave` / `Movies/AnySave`，带进度条与系统通知；完成后可「查看相册」

模拟器端到端实测（Android 15 / 系统 WebView 124）：

| 场景 | 结果 |
|---|---|
| 视频笔记 | 7.7 s 解析出 1 个视频，封面与时长 00:07 正常显示 |
| 图文笔记（15 图） | 9.4 s 解析出 15 张图片，内嵌数据 50,877 字符，15/15 缩略图加载成功 |
| 保存 3 张图片 | 3 个文件写入 `Pictures/AnySave`，尺寸与解析结果一致，MediaStore 无残留 pending 行 |
| 保存 1 个视频 | 1,560,178 B 写入 `Movies/AnySave`，`ftyp isom`，分辨率 1080×1920 |
| 进度与通知 | 通知渠道 `anysave_saves`：`Saving to AnySave` → `Saved to AnySave`，完成后留在通知栏 |
| 多选 / 筛选 / 全屏查看 / 返回网页 | 正常 |

![AnySave M2：选中 3 项并保存到相册](docs/images/m2-save.png)

![AnySave M1：图文笔记的预览、筛选与多选](docs/images/m1-preview.png)

## 为什么不是传统下载器

传统做法是「用 HTTP 客户端重放请求」，于是不得不持续逆向站点签名
（a_bogus、WBI、xsec_token……），还要让用户导出 Cookie。AnySave 反过来：

1. **会话不出浏览器** —— 在 WebView 里登录一次，没有 Cookie 文件，没有导入导出
2. **让页面自己签名** —— 请求由站点自己的 JS 发出，签名天然有效
3. **只观察，不重放** —— 应用只捕获页面已经拿到的响应；页面没发请求的 SSR 页面，
   就读取它已经渲染进 HTML 的数据，而不是替它构造一次请求
4. **下载不重演签名** —— 保存时只用页面已经收到的媒体 CDN 地址（带时效签名），
   直接 GET 字节；实测图文/视频 CDN 均不需要 Cookie，需要时也只从 WebView 的
   `CookieManager` 进程内读取，永不落盘
5. **先看、再选、再存** —— 预览和过滤是产品核心，不是附加功能

## 目录结构

```
app/src/main/
├── java/io/github/backtraxe/anysave/
│   ├── MainActivity.kt              链接输入 + 提取 + 面板/保存接线 + 权限
│   ├── extractor/PageExtractor.kt   注入、轮询、取响应体、解析、SSR 兜底
│   ├── reader/SiteReader.kt         站点 reader 接口与注册表
│   ├── reader/DouyinReader.kt       抖音 reader（响应模式 + 内嵌数据脚本）
│   ├── reader/DouyinParser.kt       aweme_detail → MediaItem（纯 JVM，可单测）
│   ├── model/MediaItem.kt           MediaItem / PostMedia
│   ├── model/MediaHit.kt            MediaHit / ExtractionSnapshot
│   ├── model/SaveState.kt           保存流程的 UI 状态
│   ├── download/MediaNaming.kt      相册路径 / 文件名 / MIME（纯 JVM，可单测）
│   ├── download/SaveJobCodec.kt     Activity ↔ Service 的任务编解码
│   ├── download/MediaSaver.kt       CDN 下载 + MediaStore 写入
│   ├── download/SaveStateBus.kt     进程内保存状态分发（主线程）
│   ├── download/SaveService.kt      dataSync 前台服务、3 并发、进度通知
│   └── ui/                          预览面板、网格、缩略图加载、全屏查看
└── res/raw/hook.js                  fetch / XHR 响应监听（页面上下文运行）
app/src/test/                        解析器 / 命名 / 任务编解码单测（21 条）
docs/
├── architecture.md                  设计说明
└── verification.md                  关键假设的验证记录
```

## 构建与测试

需要 JDK 17、Android SDK 35。

```bash
cp local.properties.example local.properties   # 改成你本机的 SDK 路径
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest               # 21 条单测，含真实响应 fixture
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 路线图

- **M0** 工程骨架 + WebView 会话 + 响应捕获 ✅
- **M1** 抖音：解析 `aweme_detail` → 媒体清单 → 预览网格（缩略图、多选、筛选）✅
- **M2** 下载：CDN 直取、3 并发、MediaStore 写相册、进度与通知 ✅
- **M3** Telegram：Bot API 上传（相册分组、caption）
- **M4** 更多 reader：小红书 `__INITIAL_STATE__`、B 站；YouTube 走专用库兜底
- **M5** reader 热更新（脚本包 + 哈希校验）

## 已知限制

- 目前只支持抖音；短链 `v.douyin.com` 尚未展开
- 未登录时可能捕获不到数据——先在 WebView 里登录，再点提取
- 图文笔记依赖页面内嵌数据：站点改版时需要更新 reader 的内嵌数据脚本
- 实况图（live photo）的动态部分暂不预览、不保存，只取静态图
- 保存不支持取消 / 断点续传；同名文件重复保存时由 MediaStore 自动加 `(1)` 后缀
- API 26–28 走 `DATA` 路径 + 存储权限的兼容分支，未在真机上验证（模拟器为 API 35）
- 没有通知权限时保存仍然可用，只是看不到进度通知
- 视频本身暂不能在应用内播放；Telegram 上传尚未接入（M3）
- 项目尚未选择开源许可证

## 免责声明

本项目仅供个人备份自己有权保存的内容。使用前请遵守目标站点的服务条款与
当地法律，不要用于批量抓取、二次分发或任何侵犯版权与隐私的用途。
