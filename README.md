# MusicConverter

一款面向 Android 的本地音乐格式转换与音频处理工具。

当前正式版本：**v2.1**  
最低 Android 版本：**Android 10（API 29）**

## 主要功能

- 单文件音频格式转换
- 批量扫描与批量转换
- 支持多任务并行转换，可选择 1～4 路并行
- 批量任务支持“完成当前任务后暂停”、继续和停止
- 音频剪辑与选区试听
- 转换或剪辑完成后支持保留、删除或置换源文件
- 支持自定义批量扫描时需要忽略的音频格式
- 批量转换使用 Android 前台服务，可在离开主界面后继续执行
- Room 本地处理历史
- FFmpeg 运行检测
- 支持深色 / 浅色界面

## 支持格式

### 常规音频

- MP3
- FLAC
- M4A / AAC
- WAV
- OGG / OPUS

### 加密音乐

工程包含对以下类型的识别与处理能力：

- 网易云音乐 NCM
- QQ 音乐 QMC / MFLAC 等格式
- 酷狗 KGM / KGMA / VPR
- 酷我 KWM

部分新版加密格式可能依赖额外密钥或受到上游解密能力限制，因此不能保证所有文件都可以成功处理。

## 批量处理

批量页面支持通过 Android SAF 选择目录并递归扫描音乐文件。

可以设置扫描时需要忽略的格式，例如 MP3、FLAC、M4A / AAC、WAV、OGG / OPUS 以及部分加密音乐格式。默认忽略 MP3，避免 MP3 → MP3 的重复有损转码。

批量转换支持：

- 1～4 路并行任务
- 转换进度与成功 / 失败统计
- 前台服务与常驻通知
- 通知栏暂停、继续和停止
- 完成当前任务后再暂停
- 可选置换源文件

多数设备建议使用 **2 路并行**。更高并行度会增加温度、耗电和存储 I/O，并不一定更快。

## 音频编辑

可以选择普通音频或支持的加密音乐文件进入编辑器。

目前包含选区剪辑、试听等音频处理能力。剪辑优先尝试 FFmpeg stream copy，无法直接复制时自动使用重新编码方式处理。

处理完成后可以根据实际文件与存储权限选择保留源文件、删除源文件或使用处理结果置换源文件。

## 输出位置

默认输出目录：

```text
Music/MusicConverter/
```

输出通过 Android MediaStore / SAF 处理，遵守 Android 存储权限和沙箱机制。

Android 11 及以上版本中，`Android/data`、`Android/obb` 等目录仍受系统限制。

## 构建环境

- JDK 17
- Python 3.11
- Gradle Wrapper 8.7
- compileSdk 35
- targetSdk 35
- minSdk 29
- ABI：`arm64-v8a`、`armeabi-v7a`

项目使用 Chaquopy 调用 Python 音频处理组件，并使用工程内的 FFmpegKit module，不依赖已经停止发布的 FFmpegKit Maven artifact。

### 本地构建

```bash
./gradlew assembleDebug
```

Release 构建需要配置对应的签名信息。

## 使用提示

- 批量处理大量文件前，建议先使用少量文件确认输出结果。
- 置换或删除源文件的能力取决于 Android SAF / MediaStore 以及文件提供方授予的权限；无法安全修改源文件时，应用会尽量保留原文件和已经生成的输出。
- MIUI / HyperOS 可能额外限制后台任务。长时间批量转换时，可根据需要将应用电池策略调整为允许后台运行。
- FFmpegKit 及其他第三方组件具有各自的开源许可要求，重新分发 APK 时请根据实际构建配置核对相应许可。

## 技术组件

- Kotlin / Android Framework
- FFmpegKit
- Chaquopy / Python 3.11
- PyCryptodome
- Mutagen
- Android Room

## 项目定位

MusicConverter 主要用于处理用户自己拥有并有权处理的本地音频文件。应用不会绕过 Android 系统的存储沙箱限制。