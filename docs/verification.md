# 关键假设验证

M0 的设计建立在一个假设上：**在页面上下文里，站点自己的 JavaScript 会为它发出的
请求签名；应用只需捕获响应，不需要自己实现签名。**

本文件记录验证过程与数据。

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

## 实现结论

1. 系统 WebView 124 也能跑通，不依赖新版浏览器特性。
2. 真机首条数据比桌面慢约 4 倍，等待策略必须是「轮询 + 15–25 秒超时」，
   不能固定 sleep。
3. 页面可能在加载过程中重新导航（实测 `onPageFinished` 触发两次），
   JS 上下文重建会让「fetch 是否被 patch」短暂变回 false。
   因此判据必须是「已捕获到命中」。
4. WebView 的 `performance` 拿不到最终请求 URL，`a_bogus` 不会出现在
   记录到的 URL 里；不要用它判断签名是否发生，用响应内容判断。
5. 默认 UA 带 `; wv` 也能正常工作，抖音不需要伪装 UA。

## 未验证项

- 物理真机（本次只有模拟器）
- B 站高清：移动播放器的 playurl 请求在页面级 hook 中不可见，需要专用库兜底
- 小红书笔记页：需要带 `xsec_token` 的真实分享链接才能测试
- 下载、相册写入、Telegram 上传（M2 / M3）
