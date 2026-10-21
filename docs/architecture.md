# 架构

## 目标

一个自包含的 Android 应用：粘贴链接、预览、勾选、保存。不依赖服务端，
不依赖 Termux，不需要用户导出 Cookie。

## 非目标

- 不做批量爬虫，不做多账号，不做公开分享
- 不追求「什么都能下」，只做「先看、再选、再存」
- 不在 M2 阶段处理 YouTube（需要专用提取库兜底）与 Telegram 上传（M3）

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
7. 若 9 秒仍无命中，执行 reader 的 `embeddedPayloadScript`，解析页面内嵌数据
8. `MainActivity` 把 `PostMedia` 交给 `ui.PreviewPanel`：网格、筛选、多选、全屏查看
9. 用户点「保存」→ 选中项按作品顺序打包成 `SaveJob`（JSON，`SaveJobCodec`）
10. `SaveService` 以前台服务启动：3 个并发 worker，每个 worker 调 `MediaSaver`
11. `MediaSaver` 直接 GET 页面已经拿到的 CDN 地址，写入 MediaStore 对应集合；
    `SaveStateBus` 把 `Running/Finished` 推给面板，`NotificationManager` 同步进度
12. 完成后通知留在通知栏；面板显示「已保存」与「查看相册」
13. （M3）把选中的媒体上传 Telegram

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

## 更新策略（M5）

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
