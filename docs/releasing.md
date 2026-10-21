# 构建与发布

## 本地打包（按应用名 + 版本号命名）

```bash
./gradlew :app:namedReleaseApk
# dist/AnySave-<versionName>.apk
```

版本号来源：`app/build.gradle.kts` 的 `defaultConfig.versionName`。当前为 `0.4.0`，
因此产物是 `dist/AnySave-0.4.0.apk`。没有本机签名配置时文件名会带 `-unsigned`。

## 本机签名配置

`keystore.properties` 与 keystore 文件都在 `.gitignore` 中，不会进仓库：

```properties
storeFile=keystore/anysave-release.jks
storePassword=***
keyAlias=anysave
keyPassword=***
```

生成一个发布 keystore（一次即可；**务必备份，丢失后无法覆盖安装更新**）：

```bash
mkdir -p keystore
keytool -genkeypair -v \
  -keystore keystore/anysave-release.jks \
  -alias anysave -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=AnySave, O=backtraxe, C=CN"
```

`app/build.gradle.kts` 会按优先级读取环境变量（`RELEASE_STORE_FILE`、
`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`）或
`keystore.properties`；两者都没有时 release APK 不签名，构建不会失败。

校验签名与元数据：

```bash
$ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs dist/AnySave-0.4.0.apk
$ANDROID_HOME/build-tools/<ver>/aapt2 dump badging dist/AnySave-0.4.0.apk | head -1
```

## CI（push / PR）

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml)：

1. JDK 17 + Android SDK + Gradle 缓存
2. `./gradlew :app:testDebugUnitTest`
3. `./gradlew :app:assembleDebug` / `:app:assembleRelease`
4. 用 `:app:printVersionName` 取版本号，上传 `AnySave-<version>-debug.apk` artifact
5. 测试失败时附带测试报告 artifact

## 发布（tag / 手动）

[`.github/workflows/release.yml`](../.github/workflows/release.yml) 在推送 `v*` 标签时触发，
也支持 `workflow_dispatch` 手动试跑（手动模式只上传 artifact，不创建 Release）。

在仓库 Settings → Secrets and variables → Actions 配置：

| Secret | 说明 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -i keystore/anysave-release.jks` 的输出（单行） |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | 例如 `anysave` |
| `RELEASE_KEY_PASSWORD` | key 密码（多数情况下与 store 密码相同） |

发布流程：

```bash
git tag v0.4.0
git push origin v0.4.0
```

工作流会构建 `AnySave-0.4.0.apk`，上传 artifact，并用 `softprops/action-gh-release`
创建 GitHub Release 附上 APK。没有签名 secrets 时产出 `AnySave-0.4.0-unsigned.apk`
并在日志里给出 warning。

## 产物规则

| 场景 | 文件名 |
|---|---|
| 本地 release（有签名） | `AnySave-<version>.apk` |
| 本地 release（无签名） | `AnySave-<version>-unsigned.apk` |
| CI debug | `AnySave-<version>-debug.apk` |
| CI release（有 secrets） | `AnySave-<version>.apk` |
| CI release（无 secrets） | `AnySave-<version>-unsigned.apk` |

## 发布前检查

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:namedReleaseApk
$ANDROID_HOME/build-tools/<ver>/apksigner verify --verbose dist/AnySave-<version>.apk
$ANDROID_HOME/build-tools/<ver>/aapt2 dump badging dist/AnySave-<version>.apk | head -1
```

release 包应满足：无 `PreviewDemoActivity` / `SettingsDemoActivity`、无 `android:debuggable`、
WebView 调试关闭（`MainActivity` 里仅在 `BuildConfig.DEBUG` 时开启）。
