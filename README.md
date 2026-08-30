# MusicConverter MIUI Pro

Android 本地音乐解密、格式转换、批量处理与轻量音频编辑工具。界面采用 MIUI / HyperOS 风格，支持日间 / 夜间主题。

> 当前源码版本：`1.10.0-ace-style-ui`（versionCode 31）

## 功能

- 单文件转换：MP3 / FLAC / M4A / WAV / OGG。
- 加密音乐解密：NCM、QMC/MFLAC/MGG、KGM/KGMA/VPR、KWM 等。
- 音频编辑：波形预览、选区、试听、暂停、剪辑与导出。
- SAF 目录扫描与一键批量转换。
- 可配置批量扫描过滤格式。
- 用户可选申请“所有文件访问权限”；授权后可主动选择全盘扫描。
- 1–4 路并行批量处理与“完成当前任务后暂停”。
- 前台服务后台转换、通知进度与 WakeLock。
- 源文件保留、删除或成功后置换。
- 日间 / 夜间主题切换并记忆选择。
- GitHub 主更新源；GitHub 失败时使用腾讯文档备用源。
- 夸克 / 蓝奏云双 APK 下载入口。
- Room 记录处理历史。

## v1.10.0 UI

本版重新优化整体界面层级：

- 首页：大标题、中央 Hero 音乐卡片、两列功能中心。
- 批量页：扫描、任务状态、工具分组展示。
- 介绍页：应用 Hero、能力列表、软件更新和信息区域。
- 日间主题使用蓝色强调，夜间主题保持深色紫色强调。
- 底部导航、卡片圆角、留白和系统栏视觉统一。

## 构建环境

- JDK 17
- Gradle 8.7
- Android Gradle Plugin 8.5.2
- Kotlin 1.9.24
- compileSdk / targetSdk 35
- minSdk 29
- Python 3.11（Chaquopy）
- `pycryptodome==3.21.0`
- `mutagen==1.47.0`

Windows：

```bat
gradlew.bat :app:assembleDebug --no-daemon
```

## GitHub 源码同步说明

仓库接口对超大 Kotlin 文件的单次同步有限制，因此部分当前 Kotlin 源码以 `app/compressed-src/` 中的 gzip + Base64 分片保存。`app/build.gradle` 在 `preBuild` 前无损恢复这些文件到生成源码目录；其余未变化的小文件仍保留普通源码形式。

## 输出目录

```text
Music/MusicConverter/
```

## 权限说明

“所有文件访问权限”是可选权限。不开启时仍可使用 Android SAF 选择目录；开启后才会额外显示“全盘扫描”，且必须由用户主动点击并确认。Android/data、Android/obb 等位置仍受 Android 系统限制。

请仅处理你有权访问和转换的本地音频文件。更多版本记录见 [CHANGELOG.md](CHANGELOG.md)，第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
