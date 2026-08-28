# MusicConverter

Android 本地音乐解密、格式转换与音频剪辑工具。项目以 Kotlin + Chaquopy + FFmpegKit 实现，界面采用偏 MIUI / HyperOS 的三页工作区布局。

> 当前版本：`1.7.0-user-ignore-formats`

## 功能

- 单文件转换：MP3 / FLAC / M4A / WAV / OGG。
- 加密音乐解密：NCM、QMC/MFLAC/MGG、KGM/KGMA/VPR、KWM 等。
- 音频编辑：选区试听、暂停/继续、单段剪切、失败时自动重编码。
- 磁盘/目录扫描：通过 Android SAF 递归扫描用户授权目录。
- 一键批量转换：支持 1–4 路并行工作器。
- 优雅暂停：点击暂停后，先完成正在执行的任务，再停止派发新任务。
- 后台转换：前台服务 + 常驻进度通知 + WakeLock。
- 源文件策略：保留、删除，或在成功后用转换结果置换源文件。
- 可选忽略格式：批量扫描时由用户决定要跳过哪些格式，默认忽略 MP3。
- Room 记录处理历史。

## 页面结构

1. **首页**：单文件选择、转换、编辑/剪辑。
2. **批量与工具**：扫描磁盘、一键转换、忽略格式、历史、FFmpeg 检测、电池优化。
3. **介绍**：功能、格式与开源组件说明。

## 可忽略格式

批量页可多选扫描时要忽略的格式：

- MP3（默认）
- FLAC
- M4A / AAC
- WAV
- OGG / OPUS
- 网易云 NCM
- QQ 音乐加密格式
- 酷狗 KGM / KGMA / VPR
- 酷我 KWM

该设置只影响批量扫描与批量任务；单文件转换和剪辑不受影响。

## 构建环境

- Android Studio
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

FFmpegKit 使用 Maven Central 上的 16 KB page-size 兼容维护版本：

```gradle
implementation 'io.github.jamaismagic.ffmpeg:ffmpeg-kit-lts-full-16kb:6.1.4'
```

该依赖继续提供 `com.arthenica.ffmpegkit.*` API，因此应用层调用不需要改变。项目不再把数十 MB 的 FFmpeg `.so` 直接提交到本仓库。

仓库保持 source-only：首次运行 `gradlew` 时会自动下载 Gradle 8.7 wrapper JAR；构建前会从 `music-geshizhuanhuan` 固定提交下载 `kugou_key.xz`。

## 编译

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

转换后的文件通过 MediaStore 写入：

```text
Music/MusicConverter/
```

## Android 存储限制

Android 11+ 对 `Android/data`、`Android/obb` 等目录有系统级限制。MusicConverter 只扫描用户通过 SAF 明确授权的目录，不绕过系统沙箱。

## 后台运行

批量任务使用前台服务并显示通知进度。MIUI / HyperOS 等系统仍可能额外限制后台活动，必要时可在应用信息中将电池策略设为“无限制”。

## 第三方组件

- `music-geshizhuanhuan`：MIT；本仓库保留对应许可证文件。
- Chaquopy：Android Python 集成。
- PyCryptodome / Mutagen：Python 运行时依赖。
- FFmpegKit / FFmpeg：通过 Maven Central 获取维护版 Android AAR；具体许可取决于所使用的构建变体和 FFmpeg 组件。
- AndroidX Room：处理历史数据库。

更多信息见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 [CHANGELOG.md](CHANGELOG.md)。

## 说明

QQ 音乐部分新版格式在缺少内嵌 EKey 时仍可能需要额外密钥信息；该限制来自上游解密机制。
