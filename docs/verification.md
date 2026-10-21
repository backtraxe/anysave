# 关键假设验证

M0 的设计建立在一个假设上：**在页面上下文里，站点自己的 JavaScript 会为它发出的
请求签名；应用只需捕获响应，不需要自己实现签名。**

M1 增加了一个前提：**图片笔记页不发 detail 请求，但作品数据已经内嵌在页面里；
应用可以只读地取用这份数据。**

M2 又增加了一个前提：**页面拿到的媒体 CDN 直链可以不依赖 Cookie 直接下载。**
下面分别记录验证过程与数据。

## 实验 A：桌面 Chrome 155（Playwright 驱动）

页面：`https://www.douyin.com/video/7691296880559540623`

在页面上下文执行 `fetch('/aweme/v1/web/aweme/detail/?...')`，不计算任何签名。

| 项目 | 结果 |
|---|---|
| HTTP 状态 | 200 |
| 响应体 | 80,382 字节，含 `aweme_detail` |
| 真实网络请求 | 33 个 query 参数，含 192 字符 `a_bogus` |
| `a_bogus` 结构校验（仓库解码器） | 通过；aid=6383，page_id=6241，sdk=1.0.1.0 |

**对照组**：同一 URL、同一份 Cookie，用 HTTP 客户端直连，不带页面签名。

| 项目 | 结果 |
|---|---|
| HTTP 状态 | 403 |
| 响应体 | 46 字节：`Blocked by ArgusSecurityPlugin Uifid Not Found` |

结论：唯一差异是页面 JS 是否参与；页面参与即 200。

## 实验 B：Android 15 模拟器 / 系统 WebView 124

设备：Android 15（API 35），arm64，`com.android.webview 124.0.6367.219`
（比桌面 Chrome 155 老 31 个大版本）。

| 项目 | 结果 |
|---|---|
| 文档开始注入 | 成功（`addDocumentStartJavaScript`） |
| 实际 UA | `...; wv) ... Chrome/124.0.6367.219 Mobile Safari/537.36` |
| bdms.js 接管 fetch | 1,072 ms |
| 被动捕获页面自身响应 | 3 条 XHR，均 200，约 79.9 KB，含 `aweme_detail` |
| 主动注入 fetch | 200，80,393 字节，含 `aweme_detail` |
| 首条命中 | 7,004 ms |
| 崩溃 / hook 异常 | 无 |

## 实验 C：AnySave M0 应用内（Android 15 / WebView 124）

在模拟器上安装 M0 APK，输入抖音视频链接后点击「提取」。

| 场景 | 结果 |
|---|---|
| 无登录 Cookie | 页面是抖音 jsvm 反爬挑战页：HTML 72,912 字节、正文为空、bdms 已接管 fetch，25 秒内没有任何 detail 请求 |
| 注入登录 Cookie 后 | 8,900 ms 捕获 1 条 XHR，HTTP 200，79,952 字节 |

结论：M0 的「注入 → 捕获 → 回调」链路在应用内成立；使用前需要先在 WebView 里登录
（本次测试通过 DevTools 协议注入 Cookie 模拟登录态）。

## 实验 D：真实响应体与 M1 解析器

用登录态在页面上下文被动抓取两条真实响应，作为解析器的输入与测试 fixture：

| 载荷 | 大小 | 关键字段 |
|---|---|---|
| 视频笔记 `aweme/detail/` | 80,552 B | `aweme_type=0`、`duration=7000`、`play_addr.url_list=3`、`bit_rate` 13 档、`cover` + `origin_cover` |
| 图文笔记 `aweme/detail/` | 65,193 B | `aweme_type=68`、`images=15`，每张图 `url_list=3` + `download_url_list=3`；图片 2160×3240 起 |

解析器（`DouyinParser`）是纯 JVM 代码，`app/src/test` 下有 11 条单测，其中 3 条直接
使用从真实响应裁剪出来的 fixture：

| 用例 | 断言 |
|---|---|
| 真实视频响应 | 1 个视频条目；`play_addr` 首条、无 `playwm`；封面来自 `cover`；时长 7000ms；1080×1920 |
| 真实图文响应（API 形态） | 15 张图片、0 个视频；跳过 `dy-water` 水印版，同档位优先 jpeg；尺寸 2160×3240 |
| 真实图文响应（内嵌 SSR 形态） | 同一作品解析结果一致；`aweme.detail` camelCase 字段与 `playAddr` 数组形态可解析 |

结论：解析规则对真实数据成立；图文 payload 里那个 `duration=0` 的 `video` 对象是
占位（非视频），必须跳过——否则 15 张图会变成 16 个条目。

## 实验 E：图文笔记页是 SSR，没有 detail 请求

页面：`https://www.douyin.com/note/7630244974127660645`

| 客户端 | detail XHR | 说明 |
|---|---|---|
| 桌面 Chrome 155（Playwright，带登录 Cookie） | 0 | 页面完整渲染（标题、文案、15 张图都在），请求列表 433 条，无一条 detail |
| 移动 Android UA 上下文 | 0 | 同上 |
| `m.douyin.com` / `iesdouyin.com` 分享页 | 0 | 都会跳转回 www，仍是 SSR |
| 应用内 WebView（M1 旧构建） | 0 | 25,332 ms 超时，日志只有「hook installed」+「timeout」 |

进一步确认：

- 页面 HTML 里没有 `aweme_detail`，但有 `window.__pace_f`（React Server Components
  flight chunks，共 11 段、拼接约 557 KB）。
- 在其中按 `"awemeId":"<id>"` 定位、再做括号配对，可以取出合法的
  `"aweme": {...}` JSON（50,866 字符），内含 `detail.images` 15 项（camelCase：
  `urlList` / `downloadUrlList` / `width` / `height`）。
- 该页还会请求作者的 `/aweme/v1/web/aweme/post/` 作品列表，但目标笔记不在其中
  （作者最新 18 篇里没有它），不能作为被动来源。
- 直接访问 `/video/<笔记 id>` 会先触发 3 条 detail 请求再重定向到 `/note/<id>`，
  但用户分享出来的链接是 `/note/`，所以不作为方案。

结论：图文笔记只能走「读取页面内嵌数据」的兜底，且无需构造请求。

## 实验 F：M1 应用内端到端

模拟器 Android 15 / 系统 WebView 124，通过 DevTools 协议注入登录 Cookie 后，
在应用里输入链接、点「提取」。两种链接都各跑一次，日志取自 logcat `AnySave`。

| 场景 | 耗时 | 结果 |
|---|---|---|
| 视频笔记 | 7,735 ms | 捕获 3 条响应 → 1 个视频；面板显示 1 item、Videos(1)、封面 + 时长 00:07 |
| 图文笔记（15 图） | 9,379 ms | 内嵌数据 50,877 字符 → 15 张图片；面板显示 15 items、Images(15)、15/15 缩略图加载成功 |

图文笔记的日志：

```
I AnySave : reader=douyin hook installed for https://www.douyin.com
I AnySave : embedded payload found: 50877 chars
I AnySave : embedded parse ok in 9379ms, items=15
I AnySave : parsed aweme=7630244974127660645 author=TANG images=15 videos=0
```

交互验证（同一台模拟器，真实图文数据）：

| 操作 | 结果 |
|---|---|
| 点选 2 张卡片 | 品牌色描边 + 勾选角标；页脚 "Selected 2 / 15" |
| 切到 Videos(0) | 空状态 "No videos in this post" + "Show all"；已选数量保持 2/15 |
| 长按卡片 | 全屏查看器 "1 / 15"，关闭按钮 44dp，可返回 |
| 点「返回网页」 | 面板收起，WebView 恢复，状态栏显示 "Parsed 15 item(s) in 9379 ms" |
| 软键盘 | 提取时自动收起，不再遮挡网格 |

结论：M1 的「解析 → 预览网格 → 多选 / 筛选」链路在应用内成立。

## 实验 G：媒体 CDN 直链能否不带 Cookie 下载

M2 的保存只消费页面已经收到的 CDN 直链。先验证这些直链本身是否还需要会话凭据：

| 类型 | 主机 | 状态 | 内容类型 | 字节数 | 其他 |
|---|---|---|---|---|---|
| 图片 1 | `p9-pc-sign.douyinpic.com` | 200 | image/jpeg | 1,145,320（与 content-length 一致） | JPEG SOI，2160×3240 |
| 图片 2 | `p3-pc-sign.douyinpic.com` | 200 | image/jpeg | 1,309,473 | 2160×3840 |
| 视频 | `v26-web.douyinvod.com` | 200 | video/mp4 | 1,560,178 | `ftyp isom`，1080×1920 |

请求只带 `Referer: https://www.douyin.com/` 与移动 UA，**没有 Cookie**，也没有重定向。
图片 CDN 甚至对 Referer / UA 都不敏感（去掉仍是 200、字节数相同）。

结论：保存不需要 Cookie 导出，也不需要签名；CDN 直链自带时效签名即可。

## 实验 H：应用内保存端到端（M2）

模拟器 Android 15 / WebView 124，登录 Cookie 通过 DevTools 协议注入 WebView，
在应用内提取 → 勾选 → 点「保存」。APK 0.3.0。

| 场景 | 结果 |
|---|---|
| 图文保存 3 张 | `save start: 3 item(s)` → 3 条 `saved` → `save finished: 3 saved, 0 failed`；约 300 ms |
| 文件与相册 | `Pictures/AnySave/douyin_7630244974127660645_0{1,2,3}.jpg`，大小 1,145,320 / 1,309,473 / 648,842 B |
| MediaStore 校验 | 3 行 `relative_path=Pictures/AnySave/`、`is_pending=0`、`mime=image/jpeg`，尺寸 2160×3240 / 2160×3840 / 2160×3840，与解析结果一致 |
| 视频保存 1 条 | 1 行 `Movies/AnySave/douyin_7691296880559540623_video.mp4`，1,560,178 B、1080×1920、`video/mp4`、`is_pending=0`，与 CDN content-length 一致 |
| 残留检查 | 无 `is_pending=1` 行，无半截文件，无 `download failed` / `insert failed` 日志 |
| 通知 | 渠道 `anysave_saves`：`Saving to AnySave` / `0 / 15 saved`（15 图补充跑时抓到的进度）→ `Saved to AnySave` / `3 item(s) added to the AnySave album`，完成后留在通知栏 |
| 失败/崩溃 | 无崩溃、无权限或服务错误 |

独立复跑（Lead）：同一构建上再保存 2 张，MediaStore 行数 3→5、`pending=[]`，
两次都成功；重复文件名由 MediaStore 自动加 `(1)` 后缀（复跑后已清理）。

## 实验 I：保存期间的 UI 状态

| 状态 | 现象 |
|---|---|
| Idle | 页脚 `Selected 3 / 15` + `Save 3`（0 选中时禁用） |
| Running | 顶部 4dp 品牌色进度条 + `Saving 1 / 3`，主按钮禁用为 `Saving…`；网格仍可滚动，重复点保存不会再起任务 |
| Finished | `Saved 3 items to AnySave` + `View album`；`Done` 或变更选择回到 Idle；结果通过无障碍 live region 播报 |
| 返回网页 | 面板收起、WebView 恢复；保存任务不受影响，通知独立推进 |

## 实现结论

1. 系统 WebView 124 也能跑通，不依赖新版浏览器特性。
2. 真机首条数据比桌面慢约 4 倍，等待策略必须是「轮询 + 超时」，不能固定 sleep；
   M1 的兜底时延取 9 秒（视频首条命中实测 6.6–7.7 秒）。
3. 页面可能在加载过程中重新导航（实测 `onPageFinished` 触发两次），
   JS 上下文重建会让「fetch 是否被 patch」短暂变回 false。
   因此判据必须是「已捕获到命中」。
4. WebView 的 `performance` 拿不到最终请求 URL，`a_bogus` 不会出现在
   记录到的 URL 里；不要用它判断签名是否发生，用响应内容判断。
5. 默认 UA 带 `; wv` 也能正常工作，抖音不需要伪装 UA。
6. 图文笔记必须走内嵌 SSR 数据：网络路径对这类页面永远超时；而内嵌数据是站点的
   自渲染数据，读取它不违反「不重放请求」的原则。
7. 缩略图 CDN 请求带 `Referer: https://www.douyin.com/` 与移动 UA，15/15 加载成功。
8. 媒体 CDN 直链不需要 Cookie：保存环节只做「按页面收到的地址 GET」，不需要重放
   detail 接口，也不需要重新签名。
9. 写入相册用 MediaStore + IS_PENDING：写完才发布，失败即删除，实测无残留 pending 行；
   图片与视频按 `Pictures` / `Movies` 分集合，兼容 API 29+（26–28 走 DATA 分支）。
10. 保存状态由前台服务写入、Activity 只渲染：保存期间返回网页/重建界面不会丢进度。

## 未验证项

- 物理真机（本次只有模拟器）
- 其它图文笔记的 SSR 形态是否一致（本次只取了一个 15 图笔记 + 18 个候选 id）
- B 站高清：移动播放器的 playurl 请求在页面级 hook 中不可见，需要专用库兜底
- 小红书笔记页：需要带 `xsec_token` 的真实分享链接才能测试
- Telegram 上传（M3）
- API 26–28 的 DATA 路径分支（模拟器为 API 35，未在旧设备验证）
- 保存取消 / 断点续传（M2 明确不做）

## 实验 J：Telegram Bot API 上传协议（mock 服务器）

没有可用的真实 bot 凭据，因此用一个本地 mock Bot API（stdlib HTTP server，模拟
`getMe` / `sendMessage` / `sendPhoto` / `sendVideo` / `sendMediaGroup`）验证协议，
通过 debug 构建的 `api_base` 覆盖把应用指向 `http://127.0.0.1:8081`
（关掉 adb reverse 后不可达；release 构建始终连官方地址）。

| 场景 | 结果 |
|---|---|
| 设置页「测试连接」 | mock 收到 `getMe` 200 + `sendMessage` 200（chat_id=424242，text=`AnySave test message`），UI 显示 `Connected: anysave_test_bot` |
| 图文保存 3 张 + 自动上传 | 1 次 `sendMediaGroup` 200：`file0/file1/file2` = 648,842 / 1,145,320 / 1,309,473 B；media JSON 为 3 条 `attach://file0..2`；caption 只在首项，内容为原文案 + `TANG · https://www.douyin.com/note/7630244974127660645` |
| 字节一致性 | mock 收到的 3 个文件 SHA-256 与相册里 3 个 jpg 完全一致 |
| 视频保存 1 条 + 自动上传 | 1 次 `sendVideo` 200，文件 1,560,178 B（SHA-256 与相册 mp4 一致），caption 非空 |
| 失败路径（token 含 `bad`，mock 返回 400） | 日志 `group 1/1 failed: Bad Request: mock rejection`；`uploaded=0 uploadFailed=3`；UI/通知显示失败数；相册文件保留、无重试风暴、进程存活 |
| 单条 vs 相册 | 1 个视频走 `sendVideo`，3 张图走 `sendMediaGroup`；单元测试覆盖 10/11/23 张的分组切分 |

独立复跑（Lead）：清理相册后重新保存 2 张并上传，mock 收到 1 次 `sendMediaGroup`
（2 个文件，caption 仅首项），日志 `uploaded=2 failed=0`，UI `Uploaded 2 items to Telegram`；
随后官方 3 图复跑 `problems=NONE`。

## 实验 K：上传期间的 UI / 通知

| 状态 | 现象 |
|---|---|
| Running | 保存进度条 + `Saving 1 / 3` |
| Uploading | 进度条 + `Uploading 2 / 3 to Telegram`（中文「正在上传」）；主按钮禁用，网格可滚动 |
| Finished | `Saved 3 items to AnySave` + `Uploaded 3 items to Telegram`；失败时 `Telegram upload failed for N`（danger 色），可 `View album` |
| 通知 | 保存中 `Saving to AnySave` → 上传中 `Uploading to Telegram` → 完成 `Saved to AnySave` / `3 item(s) saved, 3 uploaded to Telegram`，完成后留在通知栏 |
| 设置页 | 齿轮入口、token/chat 预填、自动上传开关、测试连接状态提示均正常；长文案换行不溢出 |

## 实现结论（M3 追加）

11. 上传必须放在后台线程：初版在保存完成回调（主线程）里直接调 Bot API，会抛
    `NetworkOnMainThreadException`，现已改为独立线程 + 主线程 Handler 收尾（E2E 验证通过）。
12. 上传的是相册里的字节而不是 CDN 直链：协议测试显示 mock 收到的 SHA-256 与本地文件
    完全一致，且不受 CDN 链接过期影响。
13. Bot API 的「单条 vs 相册」边界要显式处理：1 个文件用 `sendPhoto/sendVideo`，
    2–10 个用 `sendMediaGroup`；caption 只挂第一个媒体，避免每组重复刷屏。
14. 失败不丢数据：mock 400 时上传计失败，但相册文件与成功计数保留，UI 明确显示失败数。

## 未验证项（M3 追加）

- 真实 `api.telegram.org` 联调（本环境无 bot token；设置页「测试连接」可在有凭据时验证）
- Bot API 的 429 限流退避、断网重试（当前不做，失败即计失败）
- >10 张的大相册在真实 API 上的多组发送（分组逻辑有单测，live 只测到 3 张）
- 频道 / 群组场景下的实际投递（mock 只校验 chat_id 透传）
