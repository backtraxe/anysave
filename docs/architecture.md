# 架构

## 目标

一个自包含的 Android 应用：粘贴链接、预览、勾选、保存。不依赖服务端，
不依赖 Termux，不需要用户导出 Cookie。

## 非目标

- 不做批量爬虫，不做多账号，不做公开分享
- 不追求「什么都能下」，只做「先看、再选、再存」
- 不在 M5 阶段处理 YouTube（需要专用提取库，不在 WebView 观察路线内）

## 分层

```
┌─────────────────────────────────────────────┐
│ UI   MainActivity → ui.PreviewPanel          │
│      网格 / 筛选 / 多选 / 全屏查看 / 保存状态 │
├─────────────────────────────────────────────┤
│ PageExtractor   注入 hook、轮询、取响应体、   │
│                 解析、SSR 内嵌数据兜底        │
├─────────────────────────────────────────────┤
│ SiteReader + Parser  响应 URL 模式、          │
│                  JSON → PostMedia/MediaItem  │
├─────────────────────────────────────────────┤
│ SaveService      dataSync 前台服务、3 并发、  │
│                  进度通知、SaveStateBus       │
├─────────────────────────────────────────────┤
│ MediaSaver       直连 CDN 取字节 → MediaStore │
│ DashMuxer        DASH 视频+音频 → 单 MP4      │
├─────────────────────────────────────────────┤
│ TelegramClient   Bot API：相册 / 单条 / caption│
├─────────────────────────────────────────────┤
│ WebView         真实浏览器会话（Cookie 在此） │
└─────────────────────────────────────────────┘
```

## 两类数据来源

站点把同一个作品数据放在两个地方，应用按「不构造请求」的原则分别处理：

1. **页面自己发出的网络响应**（视频详情页，`/aweme/v1/web/aweme/detail/`）。
   hook 在文档开始时包装 `fetch` / `XHR`，命中则把 URL、状态码和**响应体**写进
   `window.__anysaveHits`；应用轮询元数据，命中后一次性取回响应体，交给 reader 解析。
2. **页面内嵌的 SSR 数据**（图文笔记页 `/note/<id>`）。这类页面不请求 detail 接口，
   数据以 React Server Components 的 flight 数据（`window.__pace_f`）内嵌在 HTML 里。
   reader 提供只读 JS 表达式：拼接 flight chunks、按当前作品 id 定位 `"awemeId"`，
   再做括号配对取出 `"aweme": {...}`，交给同一个解析器。应用没有替页面发请求。

网络响应的等待优先：先轮询 9 秒左右，没有任何命中才启用内嵌数据兜底；两者都失败
才按超时处理（25 秒）。

## 数据流

1. 用户粘贴 URL
2. `SiteReader.forUrl()` 选 reader
3. `PageExtractor` 用 `WebViewCompat.addDocumentStartJavaScript` 在目标 origin 注入 hook
4. WebView 加载页面；站点自己的 JS 发请求、自己签名，hook 记录命中的响应体
5. `PageExtractor` 每 600ms 轮询元数据（不传响应体，轮询保持廉价）
6. 命中后一次性取回响应体，依次交给 `reader.parse(body, pageUrl)`，得到 `PostMedia`
7. 若没有网络命中（或 reader 本就不声明响应模式，如小红书），执行
   `embeddedPayloadScript` 读取页面内嵌数据；SSR-only reader 从 ~2 秒开始重试，
   抖音这类有网络路径的 reader 从 9 秒开始兜底重试
8. `MainActivity` 把 `PostMedia` 交给 `ui.PreviewPanel`：网格、筛选、多选、全屏查看
9. 用户点「保存」→ 选中项按作品顺序打包成 `SaveJob`（JSON，`SaveJobCodec`）
10. `SaveService` 以前台服务启动：3 个并发 worker，每个 worker 调 `MediaSaver`
11. `MediaSaver` 直接 GET 页面已经拿到的 CDN 地址；B 站 DASH 会先下载两条流、
    由 `DashMuxer` 用 MediaExtractor/MediaMuxer 合流成一个 MP4，再写入 MediaStore；
    其余站点直接写入 MediaStore 对应集合；
    `SaveStateBus` 把 `Running/Uploading/Finished` 推给面板，`NotificationManager` 同步进度
12. 若配置了 Telegram 且打开「保存后自动上传」：`SaveService` 在后台线程把刚保存的
    文件按字节上传，先 `sendMediaGroup`（每组 ≤10）再退回单条 `sendPhoto/sendVideo`，
    caption 只挂首个媒体；上传结果并入同一个 `Finished` 状态
13. 完成后通知留在通知栏；面板显示「已保存」+「已上传 N 项」与「查看相册」

## Reader 接口

```kotlin
interface SiteReader {
    val id: String
    val origin: String
    val responsePatterns: List<String>
    fun matches(url: String): Boolean

    /** 把一个捕获到的响应体解析成可预览的作品；不是作品数据则返回 null。 */
    fun parse(body: String, sourceUrl: String): PostMedia? = null

    /** 可选：返回页面内嵌数据（SSR）的 JS 表达式；没有则返回 null。 */
    val embeddedPayloadScript: String? get() = null
}
```

解析器（`DouyinParser`）是纯 JVM 代码，不认识 Android API，因此可以用真实响应
fixture 做单元测试。图片选择规则：跳过带 `dy-water` 水印流水线的 URL、优先 jpeg；
视频优先 `play_addr`（网页播放端、无水印），`playwm` 一律跳过，`bit_rate` 档位兜底。

## 多站点（M4）

```
SiteReader.forUrl(url)
   ├── DouyinReader        origin www.douyin.com   网络响应（detail）+ SSR 兜底
   ├── XiaohongshuReader   origin www.xiaohongshu.com  SSR 内嵌数据（无网络模式）
   ├── BilibiliReader      origin www.bilibili.com    SSR + DASH 合流 + 桌面 UA
   ├── WeiboReader         origin weibo.com           SSR（$render_data.status）
   ├── AcfunReader         origin www.acfun.cn         SSR（window.articleInfo）
   └── YouTubeExtractor    DirectExtractor（专用提取器，不经 WebView）
```

- **MediaSite**（`model/MediaSite.kt`）是站点事实的唯一来源：文件名前缀
  （`douyin_…` / `xiaohongshu_…`）与媒体 CDN 需要的 `Referer`
  （`https://www.douyin.com/` / `https://www.xiaohongshu.com/`）。缩略图加载器按
  媒体 URL 的 host 选 Referer，下载器按 `PostMedia.site` 选 Referer。
- **小红书数据形状**有两代：桌面页的 `note.noteDetailMap[id].note` 与移动页的
  `noteData.data.noteData`，parser 两者都接受；图片的 `urlDefault` 可能为 null，
  需要回退 `url` / `infoList`（优先 `H5_DTL`/`WB_DFT`，其次 `H5_PRV`/`WB_PRV`）。
- **原图 vs 显示图**：小红书图片原图是 `ci.xiaohongshu.com/<fileId>`（无扩展名、
  全分辨率），显示版是 `xhscdn` 上的 webp（带水印、体积小）。`MediaItem.url` 放
  原图、`previewUrl` 放显示图，并把显示图写进 `fallbackUrl`；下载失败时重试兜底，
  落地后 `MediaSniffer` 按首字节修正扩展名与 MIME。
- **分享链接约束**：小红书笔记页必须带 `xsec_token`（`/discovery/item/<id>?…`），
  裸 `/explore` 会落到验证页；这是站点的风控要求，应用不做绕过。首页需要登录，
  因此本应用只处理用户粘贴的单篇笔记链接。
- **B 站**（M5）在 SSR 数据之外还要求 DASH 合流与站点 UA 覆盖，见下一节。
- **小红书（M17 视频）**：桌面视频笔记的 stream 是 `EF4/EF5/EF6/EF7` 数组（旧移动页是
  `h264`）；parser 收集所有 stream 数组，按 `width*height`、`videoBitrate`、质量等级挑选，
  取 `masterUrl` 并用 `backupUrls[0]` 作为 fallback；封面回退到 note.imageList[0].fileId。
- **小红书短链**（M27）：`xhslink.cn` / `xhslink.com` 由 `XiaohongshuReader.matches`
  识别；`originAliases` 额外包含 `https://www.xiaohongshu.com`，保证 307 跳转后的笔记页
  也装上了 document-start hook。`UrlInput` 负责从分享文案中提取 URL、给裸域名补 https。
- **小红书**（M15）：笔记页固定桌面 UA（与 M9 登录页一致）。移动页在风控压力下会跳
  `/404/sec_*`，桌面页仍返回 `__INITIAL_STATE__.note.noteDetailMap[<id>].note`，
  现有 parser 直接兼容；图片/视频 CDN 不变。
- **微博**（M8）：`weibo.com/<uid>/<bid>` 重定向到 `m.weibo.cn/status/<bid>`，SSR 里
  有 `$render_data.status`；图片取 `pics[].large`，视频取 `page_info.urls` 最优档；
  `MediaSite.WEIBO` 提供 `https://weibo.com/` Referer（sinaimg / weibocdn 缺它会 403）。
- **AcFun**（M10）：文章页 `/a/ac<id>` 的 `window.articleInfo` 含 title/author/
  cover/`parts[].content` HTML；parser 从 HTML 取 `<img src>` 原图（aixifan 免 Referer），
  封面兜底。A 站视频页是 HLS（`videoInfo` 里的 m3u8），当前保存管线不处理。
- **YouTube** 走专用提取器，见下一节。

## B 站与 DASH 合流（M5）

```
BilibiliReader（SSR: __INITIAL_STATE__.videoData + __playinfo__）
        │ 1 个 VIDEO MediaItem：url=视频 m4s, audioUrl=音频 m4s
        ▼
MediaSaver.saveMuxed()
   ├─ 下载视频流到 cache（~20.9 MB）
   ├─ 下载音频流到 cache（~5.4 MB）
   ├─ DashMuxer.mux()：MediaExtractor 读两条 fMP4 → MediaMuxer 写普通 MP4
   ├─ 拷贝到 MediaStore（Movies/AnySave），清 cache
   └─ 失败则删除 pending 行、按失败计数（不留半截文件）
```

- **为什么必须合流**：B 站 `__playinfo__.dash` 只有分离的 video/audio 轨道，没有
  `durl` 单文件 MP4；不合并就无法得到一个可播放的文件。合流是容器重封装（不转码），
  实测 26.3 MB/212 s 的视频约 8 秒完成。
- **清晰度**：未登录最多 480P（AVC），登录/大会员才有 720P/1080P；parser 在 AVC
  （`codecid=7`）里取最高 id/码率档。
- **UA 覆盖**：B 站对 Android WebView UA 会走 `bilibili://` 打开 App 的移动流程，
  导致 WebView 导航被外部浏览器接管；其 CDN 也会对移动 UA 返回 403。因此
  `MediaSite.BILIBILI` 的页面加载与下载都改用桌面 Chrome UA，其余站点不受影响。
- **导航防护**：`MainActivity` 的 `WebViewClient.shouldOverrideUrlLoading` 拒绝所有
  非 http(s) scheme（`bilibili://`、`intent://` 等），页面永远留在应用内。

## 保存管线（M2）

```
PreviewPanel ──onSaveRequested(items)──▶ MainActivity
                                           │ 权限（通知/旧版存储）
                                           ▼
                                   SaveService.start(job)
                                           │ foregound(dataSync)
                                           ▼
                            SaveJobCodec.decode → MediaSaver ×3
                                           │
                    ┌──────────────────────┴──────────────────────┐
                    ▼                                             ▼
             MediaStore（相册条目）                    SaveStateBus + Notification
```

- **命名与位置**（`MediaNaming`，纯 JVM 可测）：
  `Pictures/AnySave/douyin_<awemeId>_<NN>.jpg`、
  `Movies/AnySave/douyin_<awemeId>_video.mp4`
- **写入方式**：API 29+ 用 `RELATIVE_PATH` + `IS_PENDING=1`，写完置 0；失败时删除
  该行，避免半截文件。API 26–28 用 `DATA` 路径 + `WRITE_EXTERNAL_STORAGE`。
- **下载请求**：只用解析结果里的 CDN 直链，带 `Referer` 与移动 UA；Cookie 需要时
  从 `CookieManager` 进程内取，不落盘、不导出。实测当前图文/视频 CDN 均不需要 Cookie。
- **并发与进度**：3 个 worker；`SaveStateBus` 推送 `Running(total, done, failed)` 与
  `Finished(total, saved, failed, albumName, firstUri)`；通知同源。
- **注意**：CDN 直链带时效签名（图片约 5 小时），因此保存要在解析后尽快进行，
  不做「稍后下载」队列。

## Telegram 上传（M3）

```
PreviewPanel 完成保存 ──▶ SaveService.complete()
                              │ 读 TelegramSettings（token/chat/auto_upload）
                              ▼
                     Thread("anysave-telegram")
                              │ 从相册 Uri 读回字节
                              ▼
              TelegramClient.uploadMedia()  ──▶ https://api.telegram.org
                              │                    bot<token>/sendMediaGroup
                              ▼                    bot<token>/sendVideo ...
                    SaveState.Uploading → Finished(uploaded, uploadFailed)
```

- **配置**（`TelegramSettingsActivity`）保存在应用私有 SharedPreferences：
  `bot_token`、`chat_id`、`auto_upload`；token 不写日志、不进仓库、不入 Intent。
  「测试连接」执行 `getMe` + `sendMessage`，用户可确认真实凭据是否可用。
- **协议规则**（`TelegramProtocol`，纯 JVM 可测）：相册 2–10 项用 `sendMediaGroup`，
  单条用 `sendPhoto`/`sendVideo`；caption 上限 1024 字符，格式为「文案 + 作者 · 来源」；
  图片 10 MB、视频 50 MB 上限，超限跳过并计失败。
- **字节来源**：上传的是刚写入相册的文件（`content://` 读回），因此 Telegram 上的版本
  与本地完全一致；不依赖 CDN 直链是否过期。
- **线程**：上传不在主线程（会触发 `NetworkOnMainThreadException`）；网络在
  `anysave-telegram` 线程，进度/收尾通过 `SaveStateBus` 与主线程 Handler 回到 UI。
- **可观测**：日志只记录方法/组号/数量（不记录 token）；通知从「正在保存」切到
  「正在上传到 Telegram」再切到最终结果；面板展示与失败数一致的提示。
- **测试钩子**：debug 构建允许 SharedPreferences 里的 `api_base` 覆盖 Bot API 地址
  （release 恒为 `https://api.telegram.org`），配合本地 mock server 做协议级 E2E。

## 关键设计约束

- **响应捕获以响应体为准**，不要用「URL 里有没有 a_bogus」判断签名是否发生：
  WebView 的 `performance` 拿不到最终 URL。
- **判据是「已捕获到命中」**，不是「fetch 是否被 patch」：页面可能在加载过程中
  重新导航，JS 上下文重建会让 patched 短暂变回 false。
- 注入脚本按 origin 常驻，`addDocumentStartJavaScript` 对每次导航都生效。
- 只对白名单 origin 注入；不要把 `addJavascriptInterface` 暴露给任意页面。
- hook 只做「记录」，解析全部在 Kotlin 侧完成；站点字段知识集中在 parser 和测试里。
- **图文笔记的 `video` 对象是占位**（`duration == 0`、静态资源），不能当成视频条目。
- 缩略图加载手写（`HttpURLConnection` + `LruCache` + `inSampleSize`），不引入图片库。
- 保存状态只由服务写入、面板只负责渲染；Activity 重建后能从 `SaveStateBus` 补回状态。
- 同名文件重复保存由 MediaStore 自动加 `(1)` 后缀；M2 不做去重与断点续传。
- Telegram token 是唯一的外部凭据，只存在于应用私有配置；任何日志、任务载荷、
  文档都不得包含它。
- 所有站点数据里的媒体 URL 统一转成 https（release 禁止明文流量）。
- 页面 UA 与下载 UA 可按站点覆盖（`SiteReader.pageUserAgent` /
  `MediaSite.downloadUserAgent`）；非 http(s) scheme 一律禁止在 WebView 内跳转。
- 站点 media URL 可能不带扩展名或走转码流水线：保存后按字节嗅探容器类型，
  MediaStore 的名称与 MIME 必须与实际内容一致。
- 上传/下载等网络调用一律在后台线程；主线程只做状态渲染与通知更新。

## 专用提取器：YouTube（M8）

YouTube 是唯一不走「WebView 观察」的站点：页面里的 `ytInitialPlayerResponse` 只给出
`signatureCipher` 与 `serverAbrStreamingUrl`，播放地址必须经过播放器 JS 解密/ABR
协议才能得到。按路线图里预留的「专用库兜底」，这里接
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)（GPL-3.0）。

```
MainActivity.startExtraction(url)
   ├─ DirectExtractors.forUrl(url) == YouTubeExtractor ?
   │     └─ 后台线程：NewPipe.init(Downloader) → StreamInfo.getInfo(YouTube, url)
   │           → 选流：AVC MP4 video-only（≤720p）+ AAC M4A audio
   │           → PostMedia(site=YOUTUBE, 1 个 VIDEO：url + audioUrl)
   └─ 其他站点：原 WebView 观察链路（SiteReader）
```

- 下载与保存复用现有管线：`MediaSaver` 下载 video/audio 两条轨 → `DashMuxer` 合流成
  单 MP4 → MediaStore；失败按失败计数。
- 这条路径不依赖 WebView、不需要 Cookie；它是唯一直接调用站点 API 的代码（提取器内部），
  其余站点仍然只观察页面已产生的数据。
- 因为 NewPipeExtractor 是 GPL-3.0，整个项目随之采用 GPL-3.0（见 LICENSE）；若要换许可证，
  必须替换/移除该依赖。
- `DirectExtractor` 是通用扩展点：以后若还有「只能靠专用协议」的站点，可独立接一层，
  不污染 SiteReader 的观察模型。

## 登录与会话（M9）

```
Settings「登录」卡片（SessionManager.SITES）
   │ startActivity(MainActivity, EXTRA_OPEN_URL, CLEAR_TOP|SINGLE_TOP)
   ▼
MainActivity.handleExternalOpenUrl(url)
   ├─ WebView UA 临时切到桌面 Chrome UA（站点才会给扫码登录，而不是“下载 APP”）
   ├─ loadUrl(url)；状态栏提示“已打开登录页…”
   └─ 用户扫码登录 → Cookie 写入系统 WebView 的 CookieStore
        （应用私有目录 app_webview/Default/Cookies，应用不读不写不导出）
```

- 登录态就是 WebView Cookie：后续提取页面、下载时（需要的话）都在进程内共享，
  `MediaSaver` 只通过 `CookieManager.getCookie(url)` 读取，不落盘。
- 提取时 `PageExtractor` 会按 reader 恢复 UA（B 站桌面 UA / 其余默认 UA），
  登录页的桌面 UA 只影响登录页本身。
- 「清除所有登录态」：`CookieManager.removeAllCookies()` + `flush()`，并
  `WebStorage.deleteAllData()`（部分站点把 token 放在 localStorage）。这是全局清理，
  会一并清掉微博访客 Cookie 等。
- 不提供「已登录/未登录」状态：HttpOnly Cookie（如 SESSDATA）无法通过 CookieManager
  枚举，判断会失真；以页面与提取结果为准。
### Cookie 导入（M12）

```
Settings 登录卡片 → cookieImportInput（粘贴文本）+ 三站导入按钮
   → CookieImport.parse（JSON / Cookie header / Netscape，纯 JVM）
   → SessionManager.importCookies(siteId, entries)
        · 校验 domain 属于该站（douyin/bilibili/xiaohongshu）
        · CookieManager.setCookie("https://<host>/", "name=value; Domain=…; Path=…; Secure; HttpOnly")
        · flush()；返回成功条数
   → 显示 "Imported N cookie(s)."，1.2s 后自动重跑登录态检测
```

- 与扫码登录并存：扫码路径打开站点桌面登录页，导入路径直接写 WebView 会话；
  两者最终都落到同一个 CookieManager，检测/提取逻辑不变。
- 支持 Cookie-Editor JSON（含 domain/secure/httpOnly）、`Cookie:` 请求头（domain 用站点默认值）、
  Netscape 文件行（含 `#HttpOnly_` 前缀）。
- 导入不会清除旧 Cookie，同名 Cookie 覆盖；「清除所有登录态」仍可整体重置。

### B 站画质选择（M14）

```
Settings「B站画质」：Auto / 1080P / 720P / 480P
   → SharedPreferences(anysave_quality)
   → MainActivity onCreate/onResume: BilibiliQualityConfig.maxHeight = prefs value
   → BilibiliReader.parse: BilibiliParser.parse(payload, url, maxHeight)
        · 先按 codecid==7（AVC）过滤；再按 height<=cap 过滤（Auto=不过滤）
        · 在候选里取 max(id, bandwidth)
        · cap 内没有任何档位时回退到完整池（cap 只做上限，不做解锁）
```

- 画质来自页面 `__playinfo__`，只会包含当前会话（登录/权限）允许的档位；
  该设置是「上限」而不是「解锁」。
- 未登录同视频只有 480P/360P，调到 1080P 也不会有效果。

### 按站点退出（M13）

```
登录卡片「清除抖音 / 清除B站 / 清除小红书」
   → SessionManager.clearSite(siteId)
        · 关键 Cookie 名（sessionid/SESSDATA/web_session…）+ getCookie 可见的全部名字
        · 每个名字按站点域写一条过期 Cookie（Max-Age=0 + 1970 Expires）
        · WebStorage.deleteOrigin(站点 origin) 清该站 localStorage/IndexedDB
        · CookieManager.flush()
   → 状态「Cleared <site> session.」→ 1s 后重新探测三站
```

- WebView 没有按域删除 Cookie 的 API，所以用「已知关键名 + 可见名 → 逐条过期」的方式；
  HttpOnly Cookie 也能通过同名过期覆盖。其它站点完全不受影响。
- 粘贴框为空时，导入按钮会读取系统剪贴板作为输入（无需额外权限）。

### 提取超时的登录态诊断（M16）

```
PageExtractor 结束但 post==null（timeout 或 captured-but-unparsed）
   → MainActivity.diagnoseTimeoutLogin()
        · SiteReader.forUrl(currentUrl).id ∈ {douyin,bilibili,xiaohongshu} ?
        · SiteLoginCheck（隐藏 1×1 WebView，共享 Cookie，桌面 UA）加载
          douyin user/self / bilibili nav / xhs explore
        · 复用 LoginSignals 解析 → 状态栏替换为具体结论
   → 站点识别用 snapshot.pageUrl（提取实际落点），避免输入框旧链接/重定向导致认错站
   → 未登录：“You are not logged in to <site>…”（引导去设置登录）
   → 已登录：“<site> looks logged in. The site may have changed…”（避免误判）
```

- 只在失败后跑一次，不拖慢正常提取；探测用独立 WebView，不影响已加载页面；
  完成/取消/Activity 销毁时立刻销毁，单站 12s 超时。
- 与设置页登录检测共用同一批信号，但只探测当前站。

### 登录态检测（M11）

```
Settings onCreate → 隐藏 1×1 WebView（alpha=0，共享同一 CookieStore，桌面 UA）
   依次 loadUrl + onPageFinished + 延迟 → evaluateJavascript（只读）
   ├─ 抖音    www.douyin.com/user/self
   │          title == "<昵称>的抖音 - 抖音" → 已登录（昵称）；"抖音-记录美好生活" → 未登录
   ├─ B 站   api.bilibili.com/x/web-interface/nav
   │          JSON data.isLogin / data.uname → 已登录（uname）/ 未登录
   └─ 小红书 www.xiaohongshu.com/explore
              window.__INITIAL_STATE__.user.loggedIn（Vue ref 的 .value）→ 已登录/未登录
   ↓
LoginSignals（纯 JVM 解析，单测覆盖）→ sessionsStatus 三行状态
```

- 探测页与主 WebView 共享会话，因此「登录页扫码回来后重新打开设置」即可看到最新状态；
  「清除所有登录态」后自动重新探测。
- 隐藏 WebView 只加载站点首页/会话状态端点，不触发内容 API、不导出 Cookie；页面在
  Activity destroy 时销毁。单站超时 12 s，失败显示「检测失败」。
- 安全边界：release 关闭 WebView 调试、禁止明文、无 JS 接口；debug 包的 CDP 仅用于测试，
  不可外发。YouTube 不需要登录；微博公开内容免登录。

## reader 热更新（M7）

```
Settings（Reader updates 卡片）
   │ 用户填 pack URL（release 必须 https，debug 可 http 供本地 mock）
   ▼
ReaderPackRepository.refresh(url)
   ├─ GET（超时/512KB 上限）→ ReaderPackParser.parse
   │    · version == 1；reader id ∈ {douyin, xiaohongshu, bilibili}
   │    · 每个脚本 ≤200KB 且必须带 sha256（不一致即拒绝）
   │    · 每个 reader 至少有 script 或 responsePatterns 之一
   ├─ 写入 filesDir/reader-pack.json + 替换进程内 ReaderOverrides
   └─ UI 显示成功/失败；失败时旧 pack 保持生效
```

- 覆盖范围：内置 reader 的 `embeddedPayloadScript`（页面内 JS）与
  `responsePatterns`；编译进 APK 的 parser、下载、上传逻辑不可远程修改。
- 生效点：`PageExtractor` 在每次提取时读取 `ReaderOverrides` 决定注入哪些响应模式、
  用哪个页面取数脚本；`MainActivity` 启动时加载持久化 pack。
- 安全边界：HTTPS（release）+ sha256 + 大小/白名单限制；脚本本身仍只在目标页面上下文
  运行，宿主只读它返回的 JSON，不授予任何原生接口。

## 更新策略（规划）

后续可把 reader pack 扩展为带版本的远端清单（多站点脚本、灰度、回滚），并复用同一套
校验/缓存机制；当前 M7 已把「脚本热更新」这一最小闭环落地。

站点改版时，理想情况下只更新 reader 脚本包：

- 远端 `index.json`：reader id、版本、URL、sha256
- 应用启动时校验并缓存到私有目录
- 加载顺序：私有目录 → 内置 assets
- 脚本运行在页面上下文，只依赖 `__anysavePatterns`、hook 约定与
  `embeddedPayloadScript` 的返回格式

## 为什么不用 HTTP 重放

重放路线需要持续逆向站点签名并管理 Cookie 生命周期，这是本项目明确要摆脱的
维护负担。代价是提取必须在前台 WebView 中完成、速度较慢；这是有意识的取舍。
SSR 页面也坚持「只读页面已有数据」，而不是替页面补一次签名请求——后者虽然也能
工作，但会让「只观察」的边界失效，并重新引入对接口参数的维护。
下载环节同样不做 API 重放：只消费页面已经收到的媒体 CDN 直链。

## 失败项重试与上传兜底（M18）

### 失败项保存重试

```
SaveService 记录 lastPost + lastFailedItems（进程内）
   → Finished.retryable = failed>0 && 队列非空
   → 面板「Retry N failed」/ 通知「Retry failed」action
   → retryLastFailed(context)：同一 PostMedia + 仅失败项重新 start()
```

- 只重跑失败项，不重复成功项；进程被杀后队列丢失（retryable=false）。
- 面板与通知共用同一条重试路径。

### Telegram 上传兜底

`TelegramClient.uploadMedia` 仍然 `chunked(10)` 顺序发送；新增：
- 某组失败 → 逐条降级发送（一条坏媒体不拖累整组），成功条数计入；
- 降级后仍失败的单条进入队列，全部组处理完后再各自重试一次；
- caption 只落在第一个成功项。

## 通知里的失败重试（M19）

```
SaveService.finishRun 有失败项 → buildFinishedNotification
   → addAction("Retry failed", PendingIntent.getService(SaveService, EXTRA_RETRY))
   → 点击后 onStartCommand 检测 EXTRA_RETRY → retryLastFailed(this)
```

通知正文同时显示 `N saved, M failed`；action 与面板按钮走同一重试逻辑。

## 上传失败清单与重试（M20）

```
TelegramClient.uploadMedia → UploadSummary.failedNames（最终仍失败的文件名）
   → SaveService 记录 lastUploadPost / lastUploadEntries / failedNames
   → Finished.uploadRetryable
   → 面板「N upload(s) failed · Retry」→ 对话框列出文件名
   → retryLastUpload(context)：只把失败文件重新上传（不重新下载/保存）
```

失败清单仅保存在进程内；App 被杀后不可重试。
