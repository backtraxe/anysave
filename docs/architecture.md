# 架构

## 目标

一个自包含的 Android 应用：粘贴链接、预览、勾选、保存。不依赖服务端，
不依赖 Termux，不需要用户导出 Cookie。

## 非目标

- 不做批量爬虫，不做多账号，不做公开分享
- 不追求「什么都能下」，只做「先看、再选、再存」
- 不在 M0 阶段处理 YouTube（需要专用提取库兜底）

## 分层

```
┌─────────────────────────────────────────────┐
│ UI (MainActivity → 预览网格 / 任务列表)      │
├─────────────────────────────────────────────┤
│ PageExtractor   注入 hook、轮询、产出快照     │
├─────────────────────────────────────────────┤
│ SiteReader      声明 origin 与响应 URL 模式   │
├─────────────────────────────────────────────┤
│ WebView         真实浏览器会话（Cookie 在此） │
├─────────────────────────────────────────────┤
│ Download / MediaStore / Telegram   （后续）  │
└─────────────────────────────────────────────┘
```

## 数据流

1. 用户粘贴 URL
2. `SiteReader.forUrl()` 选 reader
3. `PageExtractor` 用 `WebViewCompat.addDocumentStartJavaScript`
   在目标 origin 注入 `hook.js`
4. WebView 加载页面；站点自己的 JS 发请求、自己签名
5. `hook.js` 包装 `fetch` / `XMLHttpRequest`，把匹配的响应记到
   `window.__anysaveHits`
6. `PageExtractor` 每 600ms 轮询一次，拿到命中或超时后回调快照
7. （M1）把命中响应解析成 `MediaItem`，进入预览与勾选
8. （M2）下载选中项、写相册；（M3）上传 Telegram

## Reader 接口

```kotlin
interface SiteReader {
    val id: String
    val origin: String
    val responsePatterns: List<String>
    fun matches(url: String): Boolean
}
```

M0 的 reader 只声明「在哪个 origin 注入、哪些 URL 是目标响应」。下一层增加
`parse(payload): List<MediaItem>`，把 JSON 变成可预览的媒体清单。

## 关键设计约束

- **响应捕获以响应体为准**，不要用「URL 里有没有 a_bogus」判断签名是否发生：
  WebView 的 `performance` 拿不到最终 URL。
- **判据是「已捕获到命中」**，不是「fetch 是否被 patch」：页面可能在加载过程中
  重新导航，JS 上下文重建会让 patched 短暂变回 false。
- 注入脚本按 origin 常驻，`addDocumentStartJavaScript` 对每次导航都生效。
- 只对白名单 origin 注入；不要把 `addJavascriptInterface` 暴露给任意页面。

## 更新策略（M5）

站点改版时，理想情况下只更新 reader 脚本包：

- 远端 `index.json`：reader id、版本、URL、sha256
- 应用启动时校验并缓存到私有目录
- 加载顺序：私有目录 → 内置 assets
- 脚本运行在页面上下文，只依赖 `__anysavePatterns` 与 hook 约定

## 为什么不用 HTTP 重放

重放路线需要持续逆向站点签名并管理 Cookie 生命周期，这是本项目明确要摆脱的
维护负担。代价是提取必须在前台 WebView 中完成、速度较慢；这是有意识的取舍。
