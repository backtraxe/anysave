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
