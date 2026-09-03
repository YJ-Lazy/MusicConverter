# MusicConverter MIUI Pro

[![Android APK Build & Release](https://github.com/YJ-Lazy/MusicConverter/actions/workflows/android-apk.yml/badge.svg)](https://github.com/YJ-Lazy/MusicConverter/actions/workflows/android-apk.yml)

Android 本地音乐解密、格式转换、批量处理与轻量音频编辑工具，面向 MIUI / HyperOS 风格界面设计，支持日间 / 夜间主题。

> 当前正式版：**2.0**（versionCode **32**）

## 主要功能

- 单文件格式转换：MP3 / FLAC / M4A / WAV / OGG。
- 支持 NCM、QMC / MFLAC / MGG、KGM / KGMA / VPR、KWM 等本地加密音乐处理。
- 音频编辑：波形预览、剪辑选区、试听、暂停、导出与源文件置换。
- 批量处理：SAF 目录扫描、格式过滤、并行转换、前台服务进度与暂停/继续。
- 用户可选“所有文件访问权限”；授权后可主动执行全盘扫描。
- 日间 / 夜间主题切换并持久保存。
- GitHub 更新清单优先，腾讯文档仅在 GitHub 失败时作为备用源。
- 更新支持夸克 / 蓝奏云下载入口、忽略更新与强制更新门禁。

## 2.0 正式版

2.0 将当前稳定功能线整理为正式版本，并补齐 GitHub 自动构建与发布基础设施：

- GitHub Actions 在 `main` 和 Pull Request 上自动构建 Debug APK。
- `v*` 标签、手动发布或 `release:` 开头的 main 提交可触发正式发布流程。
- 配置签名 Secrets 后自动构建签名 Release APK，并上传 GitHub Release。
- Release 同时生成 APK SHA-256 校验文件。
- Gradle Wrapper 固定为 **Gradle 8.7**，并验证官方分发包 SHA-256。
- CI 固定使用 **Java 17 + Python 3.11**。

## 构建环境

| 项目 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle | 8.7 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| Chaquopy | 15.0.1 / Python 3.11 |
| compileSdk / targetSdk | 35 |
| minSdk | 29 |
| ABI | arm64-v8a, armeabi-v7a |

本地构建：

```bash
./gradlew :app:assembleDebug
```

Windows：

```bat
gradlew.bat :app:assembleDebug
```

输出 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions 自动发布

工作流：`.github/workflows/android-apk.yml`

### 自动构建

- Push 到 `main`：构建 Debug APK 并上传 Actions Artifact。
- Pull Request 到 `main`：执行同样的 Debug APK 构建验证。
- `workflow_dispatch`：可手动执行构建，也可选择发布 Release。

### 正式 APK 签名

为了让 CI 发布可持续升级安装的 Release APK，请在 GitHub Repository Secrets 配置：

- `KEYSTORE_BASE64`：JKS / keystore 文件的 Base64 内容。
- `KEYSTORE_PASSWORD`：keystore 密码。
- `KEY_ALIAS`：签名别名。
- `KEY_PASSWORD`：签名私钥密码。

工作流会把 keystore 临时恢复到 GitHub Runner，不会提交签名文件到仓库。若 Secrets 未配置完整，工作流仍可创建 GitHub Release，但会跳过 Release APK 附件，避免发布不可持续更新的临时签名包。

### 发布方式

推荐打标签：

```bash
git tag v2.0
git push origin v2.0
```

也可使用 Actions 的 `workflow_dispatch`，或通过 `release:` 开头的 main 提交触发发布流程。工作流会校验标签版本与 `app/build.gradle` 中的 `versionName` 一致。

## 更新机制

APP 的远程版本清单位于：

```text
update/update.json
```

检查顺序保持严格主备：

1. GitHub Raw `update/update.json`。
2. 仅在 GitHub 网络、HTTP 或 JSON 解析失败时请求腾讯文档备用源。

腾讯文档：`https://docs.qq.com/doc/DQnB4ZVJST2xRR2h5`

## 存储与输出

默认使用 Android SAF 访问用户选择的文件和目录。Android 11+ 可由用户主动授予“所有文件访问权限”，授权后才显示全盘扫描入口。

默认输出目录：

```text
Music/MusicConverter/
```

## 工程说明

FFmpegKit 采用仓库内本地 module：

```text
ffmpeg-kit/android/ffmpeg-kit-android-lib
```

若某次版本没有专门修改 FFmpegKit，同步源码时无需重复改动该模块。

部分较大的当前 Kotlin 源码在 GitHub 仓库中以 `app/compressed-src/` 分片保存，`preBuild` 会自动恢复到生成源码目录后再编译。

## 第三方组件与许可

- FFmpeg / FFmpegKit
- Chaquopy
- AndroidX Room
- music-geshizhuanhuan

详见 `THIRD_PARTY_NOTICES.md` 与对应许可文件。发布 APK 时请继续根据实际 FFmpeg 构建配置核对 LGPL / GPL 合规要求。
