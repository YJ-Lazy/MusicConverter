# MusicConverter

Android 本地音乐解密、格式转换与轻量音频编辑工具。项目使用 Kotlin + Chaquopy + FFmpegKit，界面采用偏 MIUI / HyperOS 的三页工作区布局。

> 当前源码版本：`1.8.5.1-buildconfig-fix`（versionCode 19）

## 功能

- 单文件转换：MP3 / FLAC / M4A / WAV / OGG。
- 加密音乐解密：NCM、QMC/MFLAC/MGG、KGM/KGMA/VPR、KWM 等。
- 音频编辑：选区试听、暂停/继续、单段剪切、失败时自动重编码。
- SAF 目录扫描与一键批量转换。
- 1–4 路并行批量处理与“完成当前任务后暂停”。
- 前台服务后台转换、通知进度、WakeLock。
- 源文件保留、删除或成功后置换。
- 用户可自定义批量扫描要忽略的格式。
- Room 记录处理历史。

## GitHub 源码布局

为了让较大的 Kotlin 文件通过仓库接口稳定同步，当前 GitHub 版本会在构建前从 `app/compressed-src/` 恢复当前 Kotlin 源文件到 `build/generated/currentKotlin`。这是源码文本的无损压缩/分片，不改变运行逻辑。

## 构建环境

- JDK 17
- Gradle 8.7
- Android Gradle Plugin 8.5.2
- Kotlin 1.9.24
- compileSdk / targetSdk 35
- minSdk 29
- Python 3.11（Chaquopy）

Chaquopy Python 依赖：

- `pycryptodome==3.21.0`
- `mutagen==1.47.0`

Windows：

```bat
gradlew.bat :app:assembleDebug --no-daemon
```

macOS / Linux：

```bash
./gradlew :app:assembleDebug --no-daemon
```

Debug APK 默认位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 输出目录

```text
Music/MusicConverter/
```

## 说明

- Android 11+ 对 `Android/data`、`Android/obb` 等目录存在系统级限制，MusicConverter 不绕过系统沙箱。
- QQ 音乐部分新版格式在缺少内嵌 EKey 时仍可能需要额外密钥信息。
- 请仅处理你有权访问和转换的本地音频文件。

更多版本记录见 [CHANGELOG.md](CHANGELOG.md)，第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
