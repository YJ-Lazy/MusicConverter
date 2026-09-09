# MusicConverter Hook

这是 MusicConverter 的 QQ 音乐 Hook 测试分支。该分支在保留 MusicConverter 本地音频处理功能的基础上，加入 LSPosed 模块能力，用于让 QQ 音乐新下载的歌曲按未加密方式保存。

## 适配范围

| 项目 | 要求 |
| --- | --- |
| QQ 音乐包名 | `com.tencent.qqmusic` |
| 参考适配版本 | **20.7.0.8** |
| Hook 功能 | 新下载歌曲免加密 |
| Android | Android 10（API 29）及以上 |
| 注入框架 | LSPosed / 兼容 Xposed API 82 的框架 |
| 作用域 | QQ 音乐 |

Hook 方案基于 QQ 音乐 **20.7.0.8** 开发并完成实机验证。模块不会限制 QQ 音乐版本号，其他版本也会尝试安装 Hook；如果 QQ 音乐修改了目标类、方法名或下载流程，功能可能无法生效。

## 环境要求

设备需要具备可用的 Root 和 LSPosed 环境。常见组合为：

- Magisk 或 KernelSU
- Zygisk
- LSPosed
- QQ 音乐（20.7.0.8 已验证，其他版本可能不适配）

请确认 LSPosed 能正常识别 MusicConverter 为模块，并允许选择 QQ 音乐作为作用域。

## 安装与启用

1. 从本仓库的 [Releases](https://github.com/YJ-Lazy/MusicConverter/releases) 下载名称带有 **Hook** 的预发布 APK。
2. 安装 APK，然后在 LSPosed 中启用 **MusicConverter MIUI Pro** 模块。
3. 仅勾选 **QQ 音乐**（`com.tencent.qqmusic`）作为作用域。
4. 强制停止 QQ 音乐后重新打开；必要时重启设备。
5. 在 QQ 音乐中重新下载歌曲，并使用外部播放器或文件头检测确认文件未加密。

测试时请关闭其他具有同类 QQ 音乐下载 Hook 的模块，避免无法判断实际生效来源。

## 生效日志

LSPosed 日志中可搜索：

```text
[MusicConverter-QQDownload]
```

正常流程会出现以下状态：

- `LOADED`：模块已进入 QQ 音乐进程
- `INSTALLED`：目标方法已找到并完成 Hook
- `HIT needEncrypt=false`：下载流程已调用免加密判断
- `WARN unverified version`：当前版本未经过验证，模块仍会继续尝试 Hook
- `ERROR`：Hook 安装失败，后面会附带异常原因

## 功能边界

该 Hook 只修改 QQ 音乐下载完成流程中的加密判断：

- 作用于启用模块后新下载的歌曲
- 不处理已经存在的加密文件
- 不提供歌曲、会员或下载权限
- 20.7.0.8 以外的版本会尝试 Hook，但不保证能够匹配或正常工作
- QQ 音乐更新后，需重新确认目标方法和下载流程

已有加密音频仍可使用 MusicConverter 原有的本地格式识别与转换功能处理。

## 发布渠道

此分支使用独立的 Hook 预发布渠道：

- 分支：`test_hook`
- 标签格式：`hook-v版本-build-构建号`
- 发布标题：`MusicConverter Hook`
- 发布类型：GitHub Pre-release

主线正式版继续使用 `v*` 标签。两种 APK 的应用包名相同，安装 Hook 版本会覆盖同签名的主线版本，应用数据保持在同一包名下。

## 构建

构建环境：

- JDK 17
- Python 3.11
- Gradle Wrapper 8.7
- compileSdk 35
- targetSdk 35
- minSdk 29

本地构建 Debug APK：

```bash
./gradlew :app:assembleDebug
```

每次向 `test_hook` 推送提交都会触发 GitHub Actions。构建成功后，APK 会保存为 Actions 构建产物，并自动创建独立的 Hook 预发布。
