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

## 实验 L：小红书笔记页（M4）

小红书笔记页同样是服务端渲染：页面上下文 hook 看不到 feed/detail XHR（只有
`edith.xiaohongshu.com/.../zones` 之类埋点），因此只能从
`window.__INITIAL_STATE__` 读数据。

| 项目 | 结果 |
|---|---|
| 可用 URL 形态 | `/discovery/item/<id>?xsec_token=<urlencoded>&xsec_source=pc_feed&type=normal`，未登录也能打开笔记页 |
| 裸链接 | `/explore/<id>`（无 token）被重定向到 `/website-login/captcha` 或 404 风控页 |
| 状态结构（桌面页） | `note.noteDetailMap[<id>].note`（作者、标题、`imageList`、`video`） |
| 状态结构（移动页） | `noteData.data.noteData`（同一批字段，`urlDefault` 可能为 null） |
| 图片字段 | `imageList[].{fileId,width,height,url,infoList[H5_DTL,H5_PRV]}`；原图 = `https://ci.xiaohongshu.com/<fileId>`（无扩展名），显示版 webp 在 `xhscdn`（可能带水印） |
| 视频字段 | `video.media.stream.h264[]`：`qualityType`（HD/SD/LD）、`videoBitrate`、`masterUrl`（单个 MP4，**含音轨**）、`duration`（毫秒）；封面 `video.image.firstFrameFileid/thumbnailFileid` |
| 媒体直链可用性 | 原图 JPEG（Android `HttpURLConnection` 实测下载成功）、显示版 webp、视频 MP4 均可直取；状态里多为 `http://`，必须升级 https（release 禁止明文） |

## 实验 M：小红书应用内端到端（APK 0.5.0 / debug）

| 场景 | 证据 |
|---|---|
| 图文笔记（12 图） | 日志 `embedded payload found: 9414 chars` → `embedded parse ok in 2188ms, items=12` → `parsed aweme=6ad8558d… author=你好小舒🐈 images=12 videos=0`；面板 `12 items`、作者/标题/话题、12/12 缩略图加载 |
| 保存 3 张原图 | `xiaohongshu_6ad8558d000000003303128c_0{1,2,3}.jpg`：1,775,889 / 1,763,912 / 2,073,854 B；MediaStore `Pictures/AnySave/`、`image/jpeg`、2730×4096、`is_pending=0`；拉回文件 PIL 校验通过 |
| 视频笔记（1 条） | 面板 `1 item`、Videos(1)；保存 `xiaohongshu_6ad7206a00000000290087e3_video.mp4` = 11,285,434 B（与 payload 一致）；`ffprobe`：h264 720×1280 + AAC 48 kHz 立体声、时长 118.14 s（payload 118,143 ms）；MediaStore `Movies/AnySave/`、`video/mp4`、`is_pending=0` |
| 失败/崩溃 | 无；保存后无 pending 残留 |

## 实验 N：抖音回归（本次未完成，非应用缺陷）

同一会话里重跑抖音笔记（M3 曾通过）时，WebView 收到的页面标题为空、正文是
`Please wait...`、HTML 仅 2.6 KB、`window.__pace_f` 为 undefined —— 抖音对该模拟器
会话下发了反爬挑战页。日志表现为 `no embedded payload for douyin` 重试后超时
（25.5 s），不是 parser 或注入链路的回归：M4 对抖音路径的改动仅有两处（SSR 兜底
改为重试、缩略图按 host 选 Referer），且 46 条单测（含抖音真实 payload 11 条）全部通过。
建议在会话恢复/换 IP/换设备后补一次抖音端到端。

## 实验 O：B 站可行性（结论：M4 只记录，不实现保存）

| 项目 | 结果 |
|---|---|
| 页面数据 | `__INITIAL_STATE__`（101,554 B）与 `__playinfo__`（44,767 B）均内嵌 SSR；www 页面**没有** playurl XHR，hook 抓不到 |
| 可播放流 | `__playinfo__.dash`：6 个视频轨（h264/hevc/av01，当前 cookie 只有 480P）+ 3 个音频轨；**没有 `durl` 单文件 MP4** |
| 直链实测 | 视频 m4s 20,872,903 B（852×480，纯视频）；音频 m4s 5,408,198 B（AAC 48 kHz 立体声，纯音频）；合起来才能得到可播放文件 |
| player 域 | `player.bilibili.com` 的 `/x/player/wbi/playurl` XHR 可见（200、51,031 B），但仍是 DASH 分离流 |
| 结论 | 没有音视频合流就不存在「单文件可保存」路径；B 站保存推迟到后续里程碑，YouTube 需要专用提取库，同样不在 M4 范围 |

## 实现结论（M4 追加）

15. 多站点需要显式的 `MediaSite`：文件名前缀、CDN Referer 都由它决定，reader/parser
    只负责产出 `PostMedia(site=…)`，下载/预览层不再硬编码站点。
16. SSR-only reader 的 embedded 数据应作为主路径并重试（小红书 2.2 s 成功），
    有网络路径的站点（抖音）仍先等网络响应、9 s 后兜底。
17. 原图与显示图分离：`url` 放原图、`previewUrl` 放显示图、`fallbackUrl` 兜底；
    落地后按首字节嗅探容器类型并修正 MediaStore 名称/MIME。本次小红书 3 张原图
    全部保存成功，未触发兜底。
18. 小红书的 `xsec_token` 是站点风控要求，应用只接受带 token 的分享链接，不做绕过。
19. B 站/YouTube 的可行性边界已经量清：B 站缺单文件流，YouTube 缺页面级可观察数据；
    两者都不适合「只观察、不重放」的当前架构。

## 未验证项（M4 追加）

- 抖音端到端回归（本次遇到反爬挑战页，待会话恢复后补测）
- 小红书在真实登录态下的其它笔记形态、实况图视频、以及 token 过期后的错误提示
- B 站音视频合流与保存（后续里程碑）
- YouTube 专用提取库（后续里程碑）


## 实验 P：B 站页面数据与 CDN 访问矩阵（M5）

页面：`https://www.bilibili.com/video/BV1GJ411x7h7`。`__INITIAL_STATE__`（104 KB）与
`__playinfo__`（44 KB）都内嵌在 SSR HTML 里，www 页面**没有** playurl XHR。

| 项目 | 结果 |
|---|---|
| 页面数据 | `videoData`（bvid/cid/title/owner/duration/pic）+ `playinfo`（quality 32、timelength 212393、dash.video 6 档、dash.audio 3 档） |
| 单文件流 | **没有 `durl`**，只有 DASH 分离轨道（视频 avc1 852×480 786766 bps；音频 mp4a.40.2 30280 203786 bps） |
| 下载要求 | Referer 必需（缺省/仅 Origin → 403）+ UA 必须是非移动类；Cookie 不需要。桌面 Chrome / iPhone / okhttp UA 均 206；应用旧 Pixel UA、Android Chrome 124、WebView UA、curl、python-requests 全部 403 |
| 实际下载 | 视频 20,872,903 B、音频 5,408,198 B（HTTP 200） |
| URL 有效期 | `deadline` 约 2 小时后过期（本次 13:31 +08），需解析后尽快保存 |
| 母带合流（ffmpeg 对照） | `ffmpeg -c copy` → 26,347,813 B、212.31 s、h264 852×480 + AAC 48 kHz 立体声，`-t 2` 解码无错误 |

结论：B 站必须（a）页面用桌面 UA 加载，（b）下载用桌面 UA + Referer，（c）本机合流。

## 实验 Q：B 站应用内端到端（APK 0.6.1）

| 步骤 | 结果 |
|---|---|
| 提取 | `embedded payload found: 42589 chars` → `embedded parse ok in 2089ms, items=1` → `parsed aweme=BV1GJ411x7h7 author=索尼音乐中国 images=0 videos=1` |
| 面板 | 1 item，作者「索尼音乐中国」，标题「【官方 MV】Never Gonna Give You Up - Rick Astley」，Videos(1)，时长 03:32，封面缩略图正常 |
| 修复验证 A | 桌面 UA + WebViewClient 非 http(s) 拦截后：页面在应用内加载，无 `bilibili://` 拉起外部浏览器/App，日志无 START BROWSABLE |
| 保存 | `save start` → `muxed … (v=20872903B a=5408198B -> 26309419B)` → `run finished: saved=1 failed=0`，耗时 8.2 s |
| MediaStore | `bilibili_BV1GJ411x7h7_video.mp4`、`Movies/AnySave/`、`video/mp4`、852×480、`is_pending=0`、26,309,419 B |
| 产物校验 | ffprobe：212.31 s、h264 852×480 + AAC 48 kHz 立体声；与 ffmpeg 母带合流相差 −38,394 B（−0.15%，容器布局差异），`ffmpeg -t 2 -f null -` 解码 rc=0 |
| 崩溃/残留 | 无崩溃，无 pending 行，无 `download failed`/`mux failed` 日志 |

## 实现结论（M5 追加）

20. B 站不能只靠 UA 无关的观测：其移动 UA 会触发 App 拉起流程，必须按站点覆盖页面 UA；
    同时用 `shouldOverrideUrlLoading` 拒绝非 http(s) scheme，避免任何外跳（合流前发现的
    真实阻断点）。
21. B 站 CDN 对 UA 有白名单式限制：Referer + 桌面 UA 是必需组合，Cookie 反而不需要；
    因此下载 UA 也按站点覆盖，抖音/小红书保持原 UA 不受影响。
22. DASH 合流用 MediaExtractor + MediaMuxer 即可（无 ffmpeg 依赖）：两条 fMP4 →
    单 MP4，8 秒完成 212 秒视频，产物可解码、时长正确。
23. 产物与「母带」ffmpeg 合流在流参数与时长上一致，仅容器布局差异（−0.15%）。

## 未验证项（M5 追加）

- B 站登录/大会员下的 720P/1080P 渲染（本次 Cookie 未登录，只有 480P/360P）
- B 站多 P（`?p=`）与短链 `b23.tv`
- YouTube（需要专用提取库，明确不在当前架构内）

## 实验 R：reader 热更新（M7）

用一个本地 mock pack 服务器验证「不重装应用即可替换 reader 的页面脚本/响应规则」。

| 场景 | 结果 |
|---|---|
| pack 准备 | `{"version":1,"readers":[{"id":"xiaohongshu","embeddedPayloadScript":"…","sha256":"…"}]}`；脚本 13,151 字符、pack 14,942 B；sha256 经 Python / Node / 真实 Chrome 三路核对 |
| 基线（无 pack） | 无数据的笔记 URL → 25.1 s 超时；内置脚本只能读到验证页状态，无法解析 |
| 应用 good pack | 设置页「Reader updates」→ Check：服务器收到 `UA AnySave-reader-pack/1`；状态 `Updated: 1 reader(s) updated`；日志 `reader pack applied: [xiaohongshu]`；`files/reader-pack.json` 与服务端字节一致 |
| 热覆盖（不重启） | 同一进程（pid 未变）立刻用覆盖脚本取数：`embedded payload found: 11758 chars` → `embedded parse ok in 2005ms, items=12`，面板 12 项（作者/标题/缩略图正常） |
| 坏 pack 拒绝 | sha256 故意不匹配：状态 `Update failed: sha256 mismatch for reader xiaohongshu`；持久化文件仍是 good pack 的字节（sha256 对比），后续提取仍 12 项 |
| 移除 pack（0.7.1 修复） | 清空 URL 后点 Check：`Updated: pack removed`，`files/reader-pack.json` 删除、prefs 置空；同一 URL 回到超时（内置行为） |
| 响应模式热更新（0.7.1/0.7.2） | 应用含 `responsePatterns` 的 pack 后，下一次提取日志 `hook installed … (hot=true)`，证明 document-start hook 已重新注册；但该 pattern 命中了页面真实的 `/zones` XHR，旧逻辑会「捕获到非作品响应 → 直接判 unparsed」而跳过内嵌脚本。0.7.2 修复为：捕获体解析失败且 reader 有内嵌脚本时，回退读取一次内嵌数据。实测 `captured bodies had no post, trying embedded script` → `items=12`，且只拉一次响应体（无轮询重复拉取） |

## 实现结论（M7 追加）

24. reader pack 的最小闭环（脚本 + 响应模式）足以覆盖「站点 SSR 结构变化」这类最常见的
    失效；解析器保持编译进 APK，安全边界清晰。
25. pack 的校验分层：HTTPS（release）+ 512KB 包体上限 + 200KB 脚本上限 + reader id 白名单
    + 每个脚本 sha256；坏包整包拒绝且旧包继续生效（实测持久化文件未被覆盖）。
26. 热更新必须考虑「操作顺序」：patterns 改变需要重新注册 document-start hook（已做），
    且新 hook 只在**下一次页面加载**生效；脚本覆盖则在当前进程即可用。
27. 捕获优先级缺陷：响应模式匹配到通用 XHR（如 `/zones`）时，不能因为「捕获到了但解析失败」
    就终止；应在超时前回退到内嵌脚本，且只回退一次、不重复拉取响应体（0.7.2 修复并实测）。

## 未验证项（M7 追加）

- 真实远端 pack 托管（HTTPS 域名）、灰度与回滚策略
- pack 签名（当前信任 pack URL + 每脚本 sha256；无公钥签名）
- 已加载页面上的 patterns 即时生效（当前设计为下一次导航生效）

## 实验 S：YouTube（M8，专用提取器）

按路线图预留的「专用库兜底」接入 NewPipeExtractor（GPL-3.0）。先用纯 JVM 测试确认
在本机网络下可解析：

| 项目 | 结果 |
|---|---|
| 页面数据（WebView 观察） | `ytInitialPlayerResponse` 里只有 `signatureCipher` + `serverAbrStreamingUrl`，无直链；embed 页面也只加载播放器 API |
| NewPipeExtractor 解析 | `StreamInfo.getInfo(YouTube, …)` 成功：title/uploader/duration、1 个 progressive 360p MP4、5 个 video-only（AVC/VP9）、5 个音频（M4A mp4a.40.5/40.2 + Opus） |
| 选流规则（单测 5 条） | 优先 AVC MP4 video-only（≤720p）+ `mp4a.40.2` M4A；没有可合流组合时回退 progressive MP4 |
| 应用内提取 | `parsed(direct) id=jNQXAC9IVRw author=jawed items=1 elapsed=6514ms`，面板 1 item / “Me at the zoo” / 00:19 |
| 应用内保存 | `muxed youtube_jNQXAC9IVRw_video.mp4 (v=433081B a=309288B -> 746914B)`，45.6 s（含 CDN 下载）；MediaStore `Movies/AnySave/`、`video/mp4`、320×240、`is_pending=0` |
| 产物校验 | ffprobe：h264 320×240 18.87 s + AAC 44.1 kHz 立体声 19.06 s；`ffmpeg -t 2 -f null -` rc=0 |
| 网络备注 | 本机 DNS 对 `*.googlevideo.com` 返回被劫持的代理 IP，其中一个（29.240.1.61）不可达导致首次保存 TCP 超时；验证时用 rooted 模拟器临时 iptables 把该 IP DNAT 到可用 IP 完成，规则已移除。应用侧无 403/证书问题 |

## 实验 T：微博（M8，SSR 观察）

| 项目 | 结果 |
|---|---|
| 页面形态 | `weibo.com/<uid>/<bid>` 在 Android WebView UA 下重定向到 `m.weibo.cn/status/<bid>?jumpfrom=weibocom`，SSR 内嵌 `window.$render_data.status`；无需登录（自动 visitor cookie） |
| 图文笔记 | 3.2 s 解析出 3 项（作者杨伊琳10）；保存 3 张 sinaimg 原图：229,606 / 256,742 / 221,566 B；MediaStore 尺寸 1440×1080 / 1740×1242 / 1440×1080，`is_pending=0`；拉回 PIL 校验通过 |
| 视频笔记 | 2.0 s 解析出 1 项；保存 `weibo_RnPXb1X4T_video.mp4` 1,896,528 B（720p 档），ffprobe h264 720×1280 + AAC 44.1 kHz 立体声 13.72 s，MediaStore pending 0，解码 2 s 无错误 |
| CDN 要求 | `wx*.sinaimg.cn` / `f.video.weibocdn.com` 需要 `Referer: https://weibo.com/`（缺失 403）；Cookie 不需要；视频直链带 `Expires`（约 2 小时） |

## 实现结论（M8 追加）

28. YouTube 只能走专用提取器：页面数据里没有可播放直链（cipher/ABR），这与「只观察」路线
    不兼容，按路线图例外处理；封装为 `DirectExtractor`，不影响其它站点的观察模型。
29. GPL-3.0 是引入 NewPipeExtractor 的必然结果：项目整体改为 GPL-3.0 并附第三方声明；
    若将来要换许可证，必须替换/移除该依赖。
30. 微博是干净的 SSR 观察站点：移动页 `$render_data.status` + 媒体 CDN 只需 Referer，
    与抖音/小红书的 SSR 兜底管线一致，直接复用。
31. 下载管线复用价值高：YouTube 的 AVC+M4A 与 B 站的 DASH 都直接走已有 DashMuxer，
    没有引入新的下载/合流实现。

## 未验证项（M8 追加）

- YouTube 720p 以上档位与登录态画质（本次取 ≤720p，未登录）
- 微博主页/话题/评论区、长文（头条文章）形态
- 本机 googlevideo DNS 劫持属于验证环境问题，真实用户网络未必受影响

## 实验 U：登录与会话（M9）

| 场景 | 结果 |
|---|---|
| 登录卡片 | 设置页底部「登录」卡片含 抖音/B站/小红书 三个按钮 + 「清除所有登录态」；EN/ZH 均正常渲染 |
| 打开抖音登录页 | App 内 MainActivity 以桌面 UA 打开 `https://www.douyin.com/`：标题「抖音-记录美好生活」、页面含「登录」入口，**不是**移动端「下载 APP」页；状态栏提示扫码登录 |
| 打开 B 站登录页 | 桌面 UA 打开 `https://www.bilibili.com/`：标题「哔哩哔哩…」、含「登录」弹层入口 |
| 打开小红书登录页 | 直接重定向到 `https://www.xiaohongshu.com/login?...`，页面含「扫码」入口（QR 元素 2 个） |
| 会话持久化 | 用 debug CDP 注入测试 Cookie（douyin `sessionid`、bilibili `SESSDATA`、xhs `web_session`）→ 杀进程/重启应用 → 三者在 `app_webview/Default/Cookies` 中仍存在（3/3） |
| 清除登录态 | 点「清除所有登录态」→ 状态「Cleared 3 session(s).」；三站 Cookie 行数均为 0（DB 实测） |
| 超时提示 | 无 Cookie 提取抖音视频 → 25 s 超时，状态变为「…If this page needs a login, use Settings → Login first.」 |
| 登录后回归 | 注入抖音 Cookie 后提取成功：`Parsed 1 item(s) in 8179 ms`；访问登录页后 B 站仍正常解析 480P：`Parsed 1 item(s) in 5764 ms` |

## 实现结论（M9 追加）

32. 登录页不能直接用 App 的默认移动 UA：抖音会渲染「下载 APP」营销页，B 站首页又不在
    reader 匹配内；用桌面 UA 打开站点登录页是可靠路径（扫码登录入口均在），且不影响提取 UA。
33. 登录态天然由 WebView CookieStore 持久化，不需要导出/注入；清理必须同时处理
    Cookie 与 WebStorage，否则部分站点仍保持登录。
34. 不做「登录状态」展示是刻意取舍：HttpOnly Cookie 无法枚举，猜测状态会误导用户；
    用页面表现 + 提取结果判断更真实。
35. 清除登录态是全局操作（WebView 没有按域删除 API），文档与提示需讲清会清掉访客 Cookie。

## 未验证项（M9 追加）

- 真实手机扫码完成登录的完整交互（自动化环境无法扫码；已验证登录页渲染、Cookie 注入
  后的持久化/清除/提取链路）
- 登录过期后的自动提示（当前依赖提取超时提示）
- YouTube 登录（不支持，不需要）

## 实验 V：AcFun 图文（M10）

| 步骤 | 结果 |
|---|---|
| 页面形态 | `https://www.acfun.cn/a/ac48924768` 公开文章，SSR 暴露 `window.articleInfo`（title/author/coverUrl/`parts[].content` HTML）；无需登录（只有自动 visitor cookie） |
| 提取 | `reader=acfun hook installed` → `embedded payload found: 7062 chars` → `embedded parse ok in 2039ms, items=3` → `parsed aweme=ac48924768 author=AcFun活动 images=3 videos=0`；面板 3 项、标题/作者/缩略图正常（aixifan CDN） |
| 保存 | `saved=3 failed=0`：`acfun_ac48924768_01.png` 1,658,046 B（2048×1152）、`_02.png` 2,256,625 B（2048×2048）、`_03.jpg` 2,642,498 B（8000×4500 封面）；MediaStore `Pictures/AnySave/` 三行 `is_pending=0`；拉回 PIL 解码通过，容器嗅探把封面的 `.png` 纠正为 `.jpg` |
| 负例 | `https://www.acfun.cn/v/ac48905515`（A 站视频，HLS）→ 状态「No reader for this site yet.」，logcat 无任何提取日志，无崩溃 |
| 登录需求 | 从已清除会话的状态开始，无需登录即可提取/保存 |

## 实现结论（M10 追加）

36. AcFun 文章是干净的 SSR 观察站点（`window.articleInfo` + HTML 图片），与微博/小红书
    同一条「内嵌脚本 + 纯 JVM parser」路径，无需 UA/Referer 覆盖（aixifan 图片可直接下载）。
37. 媒体 URL 后缀不可信：文章原图/封面可能是 PNG 却带 `.jpg`/无后缀，保存端必须继续
    依赖 `MediaSniffer` 的容器嗅探纠正扩展名（本次封面 `.png → .jpg` 实测生效）。
38. A 站视频走 HLS（`videoInfo.currentVideoInfo.ksPlayJson` 内 m3u8），现有「下载单文件/
    DASH 合流」管线不支持；明确记录为限制，不做半吊子实现。

## 未验证项（M10 追加）

- A 站视频（HLS）与番剧页面
- 动态/评论/专栏等其他 A 站内容形态
- 文章内视频嵌入（如有）

## 实验 W：登录态检测（M11）

设置页打开时用一个隐藏的 1×1 WebView（共享会话 CookieStore、桌面 UA）依次探测三站，结果写入
Login 卡片的 `sessionsStatus`：

| 站点 | 探测方式 | 判定 |
|---|---|---|
| 抖音 | `www.douyin.com/user/self` 的 `document.title` | `<昵称>的抖音 - 抖音` = 已登录（昵称）；`抖音-记录美好生活` = 未登录 |
| B 站 | `api.bilibili.com/x/web-interface/nav` 的 JSON | `data.isLogin` + `data.uname` |
| 小红书 | `www.xiaohongshu.com/explore` 的 `__INITIAL_STATE__.user.loggedIn`（Vue ref `.value`） | true/false（游客 `web_session` 不等于登录） |

| 场景 | 结果 |
|---|---|
| 清空会话后 | 三站均 `Not logged in`；`清除所有登录态` 后**同一屏**自动刷新，仍为三站未登录（0.11.1 修复） |
| 注入抖音真实 Cookie（63 条） | `Douyin: Logged in · 背影`，B 站/小红书保持 `Not logged in`；重复 2 轮一致 |
| 隐藏探测页 | CDP 目标 1×1、alpha=0；Settings UI dump 无 WebView 节点、无白块；可正常滚动 |
| Cookie 泄漏 | 全量 logcat 检索 44 个抖音 Cookie 值（长度 ≥8）：**0 泄漏**；无 `sessionid=`/`SESSDATA=`/`web_session=` 日志 |
| 回归 | 探测后 B 站视频提取仍 `items=1`、面板正常；无崩溃 |
| 清除后的 Cookie DB | 仍会留下探测页产生的访客 Cookie（如 `.douyin.com ttwid`），但无 `sessionid`/`SESSDATA`；以状态文本为准 |

## 实现结论（M11 追加）

39. HttpOnly Cookie 无法用 `CookieManager` 枚举，登录态改为「共享会话的隐藏 WebView + 页面信号」
    判断，既避开限制也不导出 Cookie。
40. 三站信号差异大：抖音用个人页标题、B 站用 nav JSON、小红书用 Vuex ref 的 `.value`；
    统一成 `LoginSignals` 纯 JVM 解析并单测。
41. 复用同一 WebView 连续探测存在导航竞态（清除后立刻重探会读到空 title，误报「检测失败」）；
    0.11.1 用 `stopLoading` + 预期 host gate + onPageStarted 重置 + 空 payload 重试修复，
    同一屏清除回归 2 轮通过。
42. 探测页本身会产生访客 Cookie，因此「清除后 Cookie 行数为 0」不是正确验收标准；
    登录态应以状态文本/账号信息为准。

## 未验证项（M11 追加）

- B 站/小红书真实登录后的正例（环境里只有抖音的有效 Cookie 文件；其余两组仅验证未登录路径与解析单测）
- 真实扫码登录后的即时状态刷新（需要人工扫码；已验证注入会话后的检测）
- 登录过期后的状态自动降级（当前重新打开设置即重新探测）

## 实验 X：用真实浏览器登录态验证（M11.2，kimi-webbridge）

用户的本地 Chrome 已登录 B 站/小红书/抖音。通过本机 **kimi-webbridge**（`~/.kimi-code/skills/kimi-webbridge`，
daemon `127.0.0.1:10086`）在用户真实浏览器里读取会话，再用 debug CDP 注入模拟器 App，
验证登录态与登录专属能力（不打印任何 Cookie 值）：

| 项目 | 结果 |
|---|---|
| B 站会话 | `nav` 返回 `isLogin:true, uname:"Backtraxe"`；36 条 Cookie 注入 App |
| 小红书会话 | `__INITIAL_STATE__.user.loggedIn=true, nickname:"山水之间"`；18 条 Cookie 注入 App |
| 抖音会话 | 62 条 Cookie 注入 App；`user/self` 标题识别为「背影」 |
| App 登录态显示 | `Douyin: Logged in · 背影` / `Bilibili: Logged in · Backtraxe` / `Xiaohongshu: Logged in · 山水之间` |
| B 站 1080P（登录专属） | 登录后页面 playinfo `quality:80`、dash 含 `80:1920x1080:codec7`；App 提取 payload 73,032 字符（匿名时 42,589），保存 `bilibili_BV1GJ411x7h7_video (1).mp4` **75,195,677 B**（视频 69,759,161 B + 音频 5,408,198 B），ffprobe h264 **1920×1080** + AAC 212.31 s；匿名时同视频仅 480P/26.3 MB |
| 小红书真实笔记 | 用登录态浏览器得到的带 `xsec_token` 链接提取：`items=6`、作者 Ragdoll（2.1 s），缩略图正常 |
| 抖音真实视频 | 登录态下 mobile UA 会 302 到 `m.douyin.com/share/video/…`，原 hook 只装在 www 导致超时；修复：抖音页面改用桌面 UA + 同时为 `m.douyin.com` 安装 document-start hook（SiteReader.originAliases）。修复后 `captured 3 response(s) in 5486ms`、`parsed aweme=… author=背影 videos=1`，面板正常 |
| 工具链刷新 | 用真实会话刷新了 `downloader-bot/cookies/{bilibili,xiaohongshu,douyin}.txt`，`cookie_check` 三站全部通过（抖音 UIFID/ttwid/sessionid、小红书 a1/web_session 齐全） |

## 实现结论（M11.2 追加）

43. 真实浏览器会话是验证「登录专属行为」的关键：同一视频匿名 480P / 登录 1080P，App 侧只需
    WebView 里有会话，reader 无需改动即可拿到更高档 playinfo。
44. 登录态会改变站点的 UA 行为：抖音在登录后把移动 UA 重定向到 `m.douyin.com/share`，
    页面数据不再出现在 www 的 detail XHR 里；因此抖音 reader 固定桌面 UA（与 B 站同理），
    并通过 originAliases 给重定向目标也装 hook，两条路径都覆盖。
45. kimi-webbridge 的价值是「用户真实登录 + 真实页面」：既能在浏览器侧确认 isLogin/画质档位，
    又能把会话注入模拟器做端到端，无需用户反复扫码。
46. 登录态检测的三站信号（实验 W）在真实会话下全部命中；之前仅有抖音正例，现在 B 站/小红书
    正例也补齐（Backtraxe / 山水之间）。

## 未验证项（M11.2 追加）

- 抖音 1080P/更高画质（登录后 playinfo 档位未逐档核对，当前保存的是解析器选出的最高 AVC 档）
- 小红书登录态下的视频笔记/图集混合形态
- 真实扫码登录路径（本次用已登录浏览器的会话注入代替）

## 实验 Y：Cookie 导入（M12）

| 场景 | 结果 |
|---|---|
| UI | 登录卡片新增粘贴框 + 「导入抖音 / 导入B站 / 导入小红书」；与原有扫码按钮并存 |
| 格式解析（单测 4 条） | `Cookie:` 请求头（含 `k=v` 内的 `=`）、Netscape（含 `#HttpOnly_` 前缀）、Cookie-Editor JSON 数组与 `{"cookies":[…]}` 包裹、空/非法输入拒绝 |
| 真实小红书导入 | 把 984 字符的 `Cookie:` 头分两段输入 → 导入后 DB 中 17/17 条 Cookie 名称与值完全一致；状态显示 `Xiaohongshu: Logged in · 山水之间` |
| 真实 B 站导入 | 短 `Cookie:` 头（SESSDATA/bili_jct/DedeUserID/DedeUserID__ckMd5/buvid3）→ `Bilibili: Logged in · Backtraxe`；提取 BV1CpWw6BEAD 得到 1080P 并保存：9,384,650 B，ffprobe h264 **1920×1080** + AAC 17.55 s，解码 rc=0 |
| 真实抖音导入 | 短头（sessionid/ttwid/UIFID 等，分两段输入）→ `Douyin: Logged in · 背影` |
| 扫码登录回归 | 点「抖音」登录按钮 → `opened login page https://www.douyin.com/`，桌面登录页正常打开，状态提示扫码 |
| 环境观察 | 小红书在当天大量自动化请求后，无论 CDP 注入还是导入会话，笔记页都返回 `/404/sec_*` 风控页（同一链接在真实 Chrome 里正常）；这是站点风控/环境问题，不是导入本身的失败。B站 3.5 分钟长视频的 1080P 下载出现两次 HTTP 503（短视频 1080P 正常；宿主 curl 同 URL 200），疑似 CDN 边缘/限流 |

## 实现结论（M12 追加）

47. 浏览器登录态与 App 会话之间最实用的桥梁是「导出 → 粘贴导入」：不需要导出文件、
    不需要 App 读浏览器 Cookie（macOS TCC 也读不到 Chrome 的库）。
48. 导入必须做站点域校验与默认域补全：`Cookie:` 头没有 domain，JSON/Netscape 可能混入
    其它站点；`SessionManager` 按站点白名单过滤后再 `setCookie`。
49. 导入与扫码共用同一个 CookieManager，因此登录态检测、提取、下载全部自动受益；
    导入后重新探测即可看到账号名。
50. 风控/CDN 是外部变量：小红书 404 sec 页与 B站长视频 503 均在真实浏览器/宿主 curl
    对照下排除应用逻辑；报告需区分「功能失败」与「站点风控/边缘限流」。

## 未验证项（M12 追加）

- 小红书在风控解除后的完整导入→提取路径（导入与登录态已验证，提取被 sec 页拦截）
- 抖音导入后的完整提取（登录态已验证；提取路径此前用 CDP 注入会话验证过）
- Cookie-Editor JSON 的端到端 UI 导入（解析有单测；UI 实测用的是 header/Netscape）

## 实验 Z：按站点退出 + 剪贴板导入（M13）

| 场景 | 结果 |
|---|---|
| 三站已登录 | 状态 `Douyin: Logged in · 背影` / `Bilibili: Logged in · Backtraxe` / `Xiaohongshu: Logged in · 山水之间`；每站下方出现「Clear Douyin/Bilibili/Xiaohongshu」按钮 |
| 只清除 B 站 | 点「Clear Bilibili」→ 状态立即刷新为 `Bilibili: Not logged in`，**抖音/小红书保持 Logged in**；DB 中 B 站仍剩 6 条探测页访客 Cookie，但 `SESSDATA/bili_jct/DedeUserID` 行已消失 |
| 恢复 B 站 | 重新粘贴短 Cookie 头导入 → `Bilibili: Logged in · Backtraxe`，三站恢复全登录 |
| 剪贴板兜底 | 输入框留空时，导入按钮会读取 `ClipboardManager.primaryClip`（代码路径；安卓 15 模拟器没有可直接写剪贴板的 shell 命令，因此未做 UI 自动化，解析/导入主路径已用输入框验证） |

## 实现结论（M13 追加）

51. WebView 没有按域删除 Cookie 的 API：用「关键 Cookie 名 + getCookie 可见名 → 逐条过期」
    可以做到单站退出，并顺带清掉该 origin 的 WebStorage。
52. 单站退出必须只写该站域名，否则会误伤其它登录态；实测清 B 站后抖音/小红书状态不变。
53. 导入按钮的空输入框 → 读剪贴板是成本极低的顺滑体验，不引入任何权限。

## 未验证项（M13 追加）

- 剪贴板兜底的 UI 自动化（模拟器剪贴板不易从 adb 写入；代码路径简单，主导入路径已验证）
- 站点自身的服务端注销（当前是本地会话清理，不是调用站点 logout 接口）

## 实验 AA：B 站画质选择（M14）

| 场景 | 结果 |
|---|---|
| 设置项 | 登录卡片上方新增「B站画质」Auto / 1080P / 720P / 480P；状态显示 `Current: Auto/720P/1080P`，选中项高亮 |
| 选择 720P | 提取 `BV1CpWw6BEAD`（17 s，登录态）→ 保存 4,095,857 B；ffprobe h264 **1280×720** + AAC 17.55 s，解码正常 |
| 不限制（Auto/1080P） | 同一视频此前保存 9,384,650 B，ffprobe h264 **1920×1080** + AAC 17.55 s |
| 单元测试（1 条新增） | 合成 1080/720/480 三档 AVC：cap=1080→1920、720→1280、480→852；cap 内无档位时回退最高档（1920） |
| 恢复 Auto | 切回 Auto 后状态 `Current: Auto`，设置持久化（SharedPreferences） |

## 实现结论（M14 追加）

54. 画质策略应在 parser 选流阶段做「上限过滤」，而不是下载后丢弃：省流量也省时间。
55. 画质偏好通过进程内配置（MainActivity 每次 onCreate/onResume 从 prefs 刷新）传给
    纯 JVM parser，parser 保持无 Android 依赖、可单测。
56. 「上限」语义要处理边界：cap 内无候选时回退完整池，避免 480P-only 视频在 720P 设置下
    直接解析失败。

## 未验证项（M14 追加）

- 480P 档位的真机保存（单测覆盖选择逻辑；720P/1080P 已真机验证）
- 高帧率/HDR/大会员专属档位

## 实验 AB：小红书桌面页（M15）

背景：同一天大量自动化请求后，小红书移动 UA 笔记页稳定跳 `/404/sec_*`（`embedded payload found: ~10366 chars` 但解析不出），即使 Cookie 完整、CDP 注入也一样；真实 Chrome 桌面页仍正常。

| 场景 | 结果 |
|---|---|
| 页面 UA | `XiaohongshuReader.pageUserAgent = DESKTOP_CHROME_UA`（与 M9 登录页一致；其余站点不变） |
| 数据形状 | 桌面页 `__INITIAL_STATE__.note.noteDetailMap[<id>].note`（bridge 侧实测 10 张 imageList），reader 脚本/parser 已支持 |
| 应用内提取 | 登录态下提取真实笔记：`embedded payload found: 11349 chars` → `items=12`，作者「让心自由」，2.9 s |
| 保存 | 全选 12 张 → `saved=12 failed=0`；单张最大约 23 MB；MediaStore `Pictures/AnySave/` |
| 回归 | 小红书分享链接仍需 `xsec_token`；其它站点 UA/Cookie 逻辑未改 |

## 实现结论（M15 追加）

57. 「站点风控」优先从**页面形态**解决：桌面页与移动页数据同构时，切换 UA 比反爬博弈稳定。
58. 桌面页数据仍在 `__INITIAL_STATE__`，parser 的两种结构兼容设计在此复用，无需新增解析分支。
59. UA 覆盖要按站点显式声明（`SiteReader.pageUserAgent`），避免影响抖音/微博/YouTube 等其它流程。

## 未验证项（M15 追加）

- 小红书视频笔记在桌面页的完整保存（本次验证的是 12 张图集）
- 极端风控下桌面页是否仍会 sec（站点策略不可控）

## 实验 AC：超时后的登录态诊断（M16）

| 场景 | 结果 |
|---|---|
| 抖音未登录 + 提取视频 | detail 请求返回 200 空体（3 条），原本显示「Captured … but found no media」；诊断后状态变为 `You are not logged in to Douyin. Open Settings → Login, sign in, then retry.` |
| 抖音已登录 + 提取 profile 页 | 页面不产生 detail XHR，25s 超时；诊断状态 `Douyin looks logged in. The site may have changed or the response was empty.`，同时 WebView 显示已登录的「背影」主页 |
| 探测资源 | 隐藏 1×1 WebView，共享 Cookie、桌面 UA；结束即销毁；不读取/打印 Cookie 值 |
| 回归 | 正常提取（douyin 视频带登录）仍直接出面板，不触发诊断；诊断只在 post==null 时执行 |

## 实现结论（M16 追加）

60. 「超时/空响应」是登录态最典型的失败表现：失败后做一次站点级登录探测，比让用户猜
    「为什么没解析出来」有效得多。
61. 诊断必须区分「未登录」与「已登录但站点改版/响应为空」，否则会把站点问题误报成登录问题。
62. 探测用独立的隐藏 WebView、只在失败后执行，避免给正常提取增加延迟或影响当前页面。

## 未验证项（M16 追加）

- B 站/小红书超时诊断的真机路径（代码共用；本次只实测抖音两分支）
- 站点挑战页（captcha/jsvm）与「未登录」的进一步区分

## 实验 AD：小红书视频笔记 + 诊断回归 + JSON 导入（M17）

### 1) 小红书视频笔记（桌面页）
| 项 | 结果 |
|---|---|
| 数据 | 桌面视频笔记 `note.video.media.stream` 用 `EF4`（2 档：960×720 HD / 1440×1080 FHD）而非 `h264`；`video.image.firstFrameFileid` 有封面；`mediaV2` 给出 1440×1080 展示尺寸 |
| 解析 | 新逻辑收集所有 stream 数组取最大分辨率/码率；`items=1`、作者「燃擎体育」、时长 13s（2.3s 解析） |
| 保存 | `saved=1 failed=0`：5,708,546 B；ffprobe h264 **1440×1080** + AAC 13.83 s，解码正常 |
| 兼容 | 旧 `h264` fixture 仍通过（77 条单测全绿） |

### 2) B 站 / 小红书超时诊断回归
| 站点 | 已登录 | 已清除登录态 |
|---|---|---|
| B 站（无效 BV，超时） | `Bilibili looks logged in. The site may have changed or the response was empty.` | `You are not logged in to Bilibili. Open Settings → Login, sign in, then retry.` |
| 小红书（坏链接/风控页） | `Xiaohongshu looks logged in. The site may have changed or the response was empty.` | `You are not logged in to Xiaohongshu. Open Settings → Login, sign in, then retry.` |

期间修复：诊断站点改用 `snapshot.pageUrl`（实际落点），不再依赖输入框（输入框残留旧链接时曾把 B 站超时误报成小红书）。

### 3) Cookie-Editor JSON 的 UI 导入
- 用 5 条真实 B 站 Cookie 的 JSON 数组（Cookie-Editor 字段：name/value/domain/path/secure/httpOnly）粘贴导入；
- DB 校验：5/5 名称与值完全一致；登录态刷新为 `Bilibili: Logged in · Backtraxe`；
- 最终三站均恢复登录。

## 实现结论（M17 追加）

63. 小红书桌面视频流的键名会变化（h264 → EF4/EF5/…），parser 必须「按结构收集」而不是
    写死某个 stream key；同时保留旧结构兼容。
64. 视频 fallback 与封面回退能明显提升弱网/风控下的成功率：`backupUrls[0]` 进
    `fallbackUrl`，封面回退到 note 首图。
65. 失败诊断的站点判断必须用实际页面 URL；输入框可能残留旧链接或被重定向，会误导结论。
66. Cookie-Editor JSON 的字段解析在真实数据上验证通过（此前只有单测），导入路径完整。

## 未验证项（M17 追加）

- 小红书视频笔记在登录态丢失/风控下的失败路径（当前诊断已验证）
- 其它 stream 键（EF5/EF6/EF7）在真实笔记中的组合情况

## 实验 AE：失败重试 + 上传兜底 + 视频流细化（M18）

### 1) 失败项一键重试
| 步骤 | 结果 |
|---|---|
| 离线保存 | 飞行模式保存 3 张小红书图 → `saved=0 failed=3`；完成面板 `3 items could not be saved` + `Retry 3 failed` |
| 恢复网络后重试 | 点「Retry 3 failed」→ 面板变为 `Saved 3 items to AnySave`，无重新提取、无重复成功项 |
| 状态机 | 重试走 SaveService.start → SaveStateBus Running → Finished，与普通保存一致 |

### 2) Telegram 上传分组兜底
- 分组逻辑保持 `chunked(10)`；新增「组失败 → 逐条发送 → 失败单条再重试一次」，caption 只落在首个成功项。
- 本轮未跑 mock Bot 端到端（协议单测仍全绿）；该改动为网络失败路径的防御性改进。

### 3) 小红书视频流选择细化
- 跨 `EF4/EF5/EF6/EF7` 收集候选，跳过 `masterUrl` 为空的条目，取 `width*height → bitrate → quality` 最大者；
- 新增 2 条单测：跨 key 选择最大档、全部无可用 URL 时返回 null（单测 79 条全绿）。

## 实现结论（M18 追加）

67. 保存失败重试要只针对失败项并复用同一 PostMedia，避免二次提取与成功项重复入库。
68. 相册上传的组失败往往是单条坏媒体：组级失败降级为逐条发送，比整组重试更稳且不易重复。
69. 站点视频流键名会演进（h264 → EF4/EF5/…），收集式选择 + 过滤无效 URL 能兼容新旧结构。

## 未验证项（M18 追加）

- Telegram 分组兜底/重试的 mock Bot 端到端（代码路径已实现，协议单测通过）
- 进程被杀后的失败项重试（失败队列在内存，retryable 会置 false）

## 实验 AF：Telegram mock 端到端 + 通知重试（M19）

### 1) 正常分组（16 张图）
- mock Bot（本地 8081 + adb reverse）：App 日志 `telegram: group 1/2 sent (10 item(s))`、
  `group 2/2 sent (6 item(s))`、`finished uploaded=16 failed=0`；
- mock 收到 2 个 `sendMediaGroup` 请求（10 + 6），无单张请求。

### 2) 故障注入（首组 400 + 一张单图 400）
- App 日志：
  `group 1/2 failed: Bad Request: mock rejection, trying items individually`
  → `group 2/2 sent (6 item(s))` → `retrying 1 failed attachment(s) once`
  → `finished uploaded=16 failed=0`；
- mock 请求序列 13 条：`sendMediaGroup(fail)` → 10×`sendPhoto`（其中 1 条 fail）→
  `sendMediaGroup(ok)` → 1×`sendPhoto(ok)`，与预期完全一致。

### 3) 通知 Retry 入口
- 飞行模式保存 3 张 → 失败通知 `0 saved, 3 failed`；
- `dumpsys notification`：`actions=1`，action 标题「Retry failed」，PendingIntent 目标为
  `SaveService startService`（与面板重试共用 `retryLastFailed`）；
- 面板「Retry 3 failed」已在 M18 做过恢复网络后重试成功 3/3 的端到端验证。

### 4) 小红书多档流取样
- 通过真实浏览器取样 4 条视频笔记：`stream` 均为 `{"EF4":1,"EF5":0,"EF6":0,"EF7":0}`；
  未观察到 EF5/EF6/EF7 实际有内容（可能与账号/清晰度有关）。
- 跨 key 选择已有单测覆盖（EF4/EF5/EF6 混合 + 空 URL 跳过）。

## 实现结论（M19 追加）

70. 上传分组逻辑经真实 multipart 请求验证：10+6 分组、caption 只在首批；故障注入下
    「组失败→逐条→单条重试」完整闭环，且不会重复成功项。
71. 失败通知里放「Retry failed」action 是低成本高价值：用户不必打开 App 就能补救；
    通知与面板共用同一重试状态，避免逻辑分叉。
72. 小红书多档流实际取样仍以 EF4 为主；parser 的收集式选择保证 EF5/EF6/EF7 一旦出现
    也能直接工作。

## 未验证项（M19 追加）

- 真机点击通知按钮的 UI 路径（已用 dumpsys 校验 action/PendingIntent，服务路径与面板共享）
- 含 EF5/EF6/EF7 非空档位的真实笔记（本轮 4 条样本均只有 EF4）

## 实验 AG：上传失败清单 + 只重传失败文件（M20）

| 步骤 | 结果 |
|---|---|
| 故障注入 | mock Bot：首组 400 + 所有单张 `sendPhoto` 400 → App 日志 `uploaded=6 failed=10` |
| 完成面板 | `Telegram upload failed for 10` → 新版显示 `10 upload(s) failed · Retry`（可点击） |
| 失败清单 | 点开后对话框标题 `10 failed upload(s)`，正文列出 10 个具体文件名（`xiaohongshu_…_01.jpg` … `_11.jpg`，按失败实际顺序） |
| 只重传失败项 | mock 切到成功模式后点「RETRY UPLOAD」→ 面板变为 `Uploaded 10 items to Telegram`；已成功的 6 张没有重复上传（mock 新增请求仅针对失败文件） |
| 清理 | 删除 mock prefs、停止 mock server、移除 adb reverse |

## 实现结论（M20 追加）

73. 「上传失败」需要保留到文件粒度：数量不足以让用户判断哪张失败，清单 + 只重传失败项
    才能避免重复上传与重复下载。
74. 上传重试直接复用已保存的 MediaStore 条目（不重新走解析/下载），状态仍通过
    SaveStateBus 驱动面板与通知。
75. 发布资料（商店文案/权限/隐私/GPL/截图索引/检查清单）应作为仓库文档维护，
    与代码和 Release 流程一起 review。

## 未验证项（M20 追加）

- 商店渠道实际上架（本文档为资料准备，未提交任何商店）
- 上传重试在 App 进程被杀后的行为（失败清单在内存，重启后不可重试）

## 实验 AH：小红书 xhslink 短链（M27）

| 项 | 结果 |
|---|---|
| URL 识别 | `xhslink.cn/a/abc` / `http://xhslink.com/a/abc` 均映射到 `XiaohongshuReader`（单测） |
| hook 目标 | 日志 `reader=xiaohongshu hook installed for https://xhslink.cn` 且同时安装 `https://www.xiaohongshu.com`（短链 307 后的目标） |
| 输入归一 | 粘贴裸域 `xhslink.cn/a/abc` → 输入框变为 `https://xhslink.cn/a/abc`，状态进入 `Extracting…`（不再报 No reader/Invalid URL） |
| 分享文案 | 单测覆盖「看看这个笔记 https://xhslink.cn/m/abc，复制…」→ 正确取 URL 并去掉中文标点 |
| 限制 | 没有拿到有效分享码做完整成功链路；无效码会 404/跳首页并按超时处理，需用户提供一条真实短链复测 |

## 实现结论（M27 追加）

76. 短链要同时解决两件事：**入口识别**（host 白名单）与**跳转后 hook 安装**
    （originAliases），只改匹配会得到「能打开但抓不到数据」。
77. 分享文案/裸域名是短链最常见的粘贴形态；先做 URL 提取与归一化，比要求用户手工整理更可靠。

## 未验证项（M27 追加）

- 有效 xhslink 分享码的完整成功下载（待真实链接复测）
- 短链跳转到非 www 域名（如 m.xiaohongshu.com）的情况（当前按桌面 UA 预期落 www）

## 实验 AI：下载重试 + Telegram 相册/标签（M28）

### 1) Telegram 相册（真实微博 3 图）
- mock Bot 注入「首个 sendMediaGroup 400」；
- App 日志：`group 1/1 failed: Bad Request: mock rejection` → `group 1/1 sent on retry`
  → `finished uploaded=3 failed=0`；
- mock 侧只收到 2 个 `sendMediaGroup`（1 失败 + 1 成功，media_count=3），**没有 sendPhoto 单张消息**。
- caption 实测：
  `…杨伊琳10 · https://m.weibo.cn/status/RnMJ9E9RR?jumpfrom=weibocom` +
  `#杨伊琳10 #微博`（作者 tag + 平台 tag，与描述/链接同一 caption）。

### 2) 分组重平衡
- 单测：11 张 → `[9,2]`，21 张 → `[10,9,2]`；避免尾部 1 项退化成「单张一条消息」。

### 3) 下载失败重试
- MediaSaver 对 5xx/429/408、IO 超时、截断响应做最多 3 次尝试（500ms/1000ms 退避）；
- DASH 视频轨/音频轨分别重试；主 URL 失败继续走 fallback URL；
- 本次真实保存 3 张微博图片 `saved=3 failed=0`（未做故障注入，重试路径为代码级验证）。

### 4) 大图压缩
- 超过 Telegram 10MB photo 限制的图片会解码 → 最长边 ≤2560 → JPEG 质量 92..50 循环，
  仍超限再按 3/4 缩放；压缩后仍走相册而不是被丢弃（未做真实 >10MB 图端到端）。

## 实现结论（M28 追加）

78. 相册发送失败先重试整组：Bot API 的 sendMediaGroup 是原子的，重试不会产生半组重复；
    重试仍失败才降级单张，兼顾「尽量组合」与「不丢图」。
79. 分组要主动避免尾部 1 项（11 → 9+2），否则用户会看到「一张图单独一条消息」。
80. 作者/平台 tag 放在 caption 末尾，并在截断时优先保留（只截描述，不截 tag）。
81. 下载侧的抗抖动（重试 + fallback + DASH 双轨独立重试）比让用户手动重试更省事；
    大图先压缩再上传，避免被 10MB 限制静默丢弃。

## 未验证项（M28 追加）

- 下载重试的真实故障注入（本次仅代码级；真实保存 3/3 成功）
- >10MB 图片压缩后的 Telegram 端到端（压缩逻辑已实现，未构造真实大图）
- 11 张真实相册的分组外观（单测覆盖分组大小）

## 实验 AJ：Telegram 部分图片上传失败（M29）

### 1) 重复上传修复（真实复现）
- 修复前：真实微博 3 图保存后，App 日志出现 **两个** `start upload: 3 item(s)` 线程，
  mock 收到 **2 个** `sendMediaGroup`（同一批被上传两次）；
- 原因：多个 worker 在最后一批同时完成，`onItemFinished` 的 `saved+failed>=total`
  条件被多个主线程回调满足，`complete()` 被调用多次；
- 修复：`completed` 标志保证 `complete()` 只执行一次；
- 修复后：mock 只收到 **1 个** `sendMediaGroup`（3 项），无重复。

### 2) 图片兼容处理
- `needsPreparation`：mime 非 `image/jpeg`/`image/png`（WebP 等）、>10MB、
  `width+height > 9000`、长宽比 >18 时触发；
- `prepare`：采样解码 → 缩放（最长边 ≤2560、宽高和 ≤9000）→ 极端长宽比居中裁剪 →
  JPEG 质量 92→50 循环/再缩放，确保 ≤10MB；
- 普通 JPG/PNG 且尺寸合规时保持原始字节不变。

### 3) 其它
- Bot API 429：读取 `parameters.retry_after`，相册重试/单张/补发前按等待时间退避（上限 10s）；
- 诊断日志：跳过不可读附件、准备失败原因、准备前后体积。

## 实现结论（M29 追加）

82. 「部分图片上传失败」常见根因是 Telegram 的格式/尺寸/体积限制，而不是网络；
    上传前统一归一化（格式 + 尺寸 + 比例 + 体积）比失败后重试更有效。
83. 完成回调必须有幂等保护：并发 worker 的最后一个完成事件可能多次满足「全部完成」条件，
    否则会重复保存通知/重复上传。
84. 429 的 `retry_after` 必须尊重，否则降级单张会进一步触发限流，表现为「部分失败」。

## 未验证项（M29 追加）

- 真实 Telegram Bot 对 WebP/超长图的拒收复现（mock 不校验格式，无法真实复现）
- 修复后真实大图（>10MB/超长图）上传的端到端（兼容逻辑为代码级）
