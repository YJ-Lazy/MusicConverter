# MusicConverter 2.0

> 业务代码与资源以已验证可用的 **v1.10.0** 为基线；正式版本号为 **2.0 / versionCode 32**。

## GitHub 直接构建

此包是干净的直源码仓库版本：

- 不使用 `app/compressed-src`
- 不使用 `app/split-src`
- 不执行 Kotlin GZIP/Base64 还原
- Launcher 图标直接位于 `app/src/main/res`
- Python 解密资源直接位于 `app/src/main/python`
- FFmpegKit 两套 ABI 原生库直接位于 `ffmpeg-kit/android/libs`

解压后将**本目录内全部文件和目录**上传到 GitHub 仓库根目录。每次 push 到 `main` 会自动构建 Debug APK。

### Release 签名 Secrets

在仓库 `Settings → Secrets and variables → Actions` 配置：

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

之后推送 `v2.0` tag，或在 Actions 手动运行并勾选 `publish_release`，即可构建签名 Release APK、生成 `.sha256` 并创建/更新 GitHub Release。

### 构建环境

- JDK 17
- Python 3.11
- Gradle Wrapper 8.7
- compileSdk / targetSdk 35
- ABI: `arm64-v8a`, `armeabi-v7a`

---

## 原 v1.10.0 项目说明

## v1.8.5 GitHub 优先，腾讯文档仅兜底

版本检查逻辑调整为严格主备模式：

```text
先请求 GitHub update.json
        ↓
成功返回且能正常解析
        ↓
直接采用 GitHub 结果
        ↓
不再请求腾讯文档
```

只有以下情况才会调用腾讯文档：

- GitHub 无法连接或超时
- GitHub 返回非 2xx HTTP 状态
- GitHub 返回空内容
- GitHub `update.json` JSON 损坏或字段无法解析

```text
GitHub 失败
    ↓
腾讯文档
    ↓
成功 → 使用腾讯文档版本信息
失败 → 提示版本检查失败
```

这样国内用户在 GitHub 不可用时仍能获得更新信息，同时 GitHub 正常时不会产生额外的腾讯文档请求。


## v1.8.4 腾讯文档备用版本检查

版本检查地址现在有两级：

1. GitHub 主清单  
   `https://raw.githubusercontent.com/YJ-Lazy/MusicConverter/main/update/update.json`
2. 腾讯文档备用源  
   `https://docs.qq.com/doc/DQnB4ZVJST2xRR2h5`

APP 先检查 GitHub。只要 GitHub 成功返回并能解析，就直接采用 GitHub 结果；仅在 GitHub 检查失败时由腾讯文档独立兜底。

### 腾讯文档推荐写法

为了让 APP 稳定从腾讯文档解析版本信息，建议在文档里放下面这段：

```text
MUSICCONVERTER_UPDATE_BEGIN
{
  "versionCode": 17,
  "versionName": "1.8.4",
  "title": "MusicConverter v1.8.4",
  "changelog": [
    "更新内容 1",
    "更新内容 2"
  ],
  "quarkUrl": "https://pan.quark.cn/s/bc7cbd178f5a",
  "lanzouUrl": "https://ssssvip.lanzoue.com/b0xxjc13c",
  "lanzouPassword": "6666",
  "force": false,
  "minSupportedVersionCode": 12
}
MUSICCONVERTER_UPDATE_END
```

也支持腾讯文档正文直接只放一个 JSON 对象。

> 腾讯文档并不是官方 raw JSON API，页面结构将来可能变化，所以它作为 GitHub 的备用检查源，而不是唯一版本源。


## v1.8.3 强制更新启动门禁

远程 `update/update.json` 支持两种强制更新方式：

```json
{
  "force": true
}
```

或提高最低支持版本：

```json
{
  "minSupportedVersionCode": 16
}
```

当当前安装版本不满足强制更新要求时：

- APP 会显示不可取消的“必须更新”弹窗。
- 用户只能选择“下载更新”或“退出应用”。
- 不提供“稍后”“忽略更新”或普通关闭入口。
- 已确认的强制更新会缓存到本地；用户断网重开 APP 也不能绕过。
- 打开夸克 / 蓝奏云后如果没有安装新版，返回 APP 会再次显示门禁。
- 安装满足要求的新版本后，门禁缓存自动清除。
- 如果 GitHub 首次检查失败且本机从未确认过强制更新，仍允许离线使用，避免 GitHub 故障误锁应用。


## v1.8.2 更新提醒忽略策略

- 更新弹窗新增“忽略更新”。
- 用户忽略某个远程 `versionCode` 后：同版本及只再发布 1 个版本时，自动检查不再弹窗。
- 当远程版本相对被忽略版本累计增加 2 个 `versionCode` 时，恢复自动提醒。
- 例如忽略 `15`：`16` 不提醒，`17` 再提醒。
- 手动点击“检查更新”不会被忽略策略拦截，仍可随时查看最新版本。
- 强制/最低支持版本更新不会提供“忽略更新”。
- “下载更新”改为二级来源选择，继续支持夸克与蓝奏云；蓝奏云仍自动复制密码。

## v1.8.1 GitHub + 夸克 + 蓝奏云更新

- GitHub 负责 `update/update.json` 版本检查。
- APK 下载提供两个入口：
  - 夸克网盘：`https://pan.quark.cn/s/bc7cbd178f5a`
  - 蓝奏云：`https://ssssvip.lanzoue.com/b0xxjc13c`
- 蓝奏云提取密码：`6666`
- 用户点击“蓝奏云下载”时，应用会自动把密码复制到系统剪贴板，然后打开分享页。
- 不解析、不绕过网盘下载机制，仅调用系统浏览器/对应网盘 APP 打开分享链接。


## v1.8.0 GitHub + 夸克远程更新

- GitHub Raw `update/update.json` 负责提供版本号、更新日志和更新策略。
- APK 由夸克网盘分享链接提供。应用发现新版本后拉起夸克或浏览器进行下载。
- 启动时每天最多自动检查一次；“介绍”页提供手动“检查更新”。
- 不依赖夸克私有接口，不解析临时下载地址，因此兼容性和维护性更高。
- 修改仓库中的 `update/update.json` 即可发布下一版更新提示，无需修改旧版 APK。

## v1.7.0 用户可选忽略格式

批量页新增“忽略格式”设置，用户可以决定磁盘扫描时跳过哪些音频类型。

默认仍忽略 MP3，以避免 MP3 → MP3 的重复有损转码。可选择：

- MP3
- FLAC
- M4A / AAC
- WAV
- OGG / OPUS
- 网易云 NCM
- QQ 音乐加密格式
- 酷狗 KGM / KGMA / VPR
- 酷我 KWM

设置会持久保存。修改后需要重新扫描目录，扫描结果、批量任务和后台转换服务都会应用同一套忽略规则。单文件转换和音频剪辑不受影响。



## v1.6.2 批量扫描跳过 MP3

- 磁盘 / 目录批量扫描识别到 `.mp3` 时自动跳过，不加入批量转换队列。
- 扫描进度和扫描结果会显示“已跳过 MP3”数量。
- 批量转换服务增加二次过滤，旧扫描任务中即使包含 MP3 也不会再次转码。
- 单文件页面仍可手动选择 MP3 进行编辑或主动格式转换，不影响剪辑功能。
- 这样可以减少重复转码、节省时间，并避免 MP3 → MP3 带来的额外有损压缩。

## v1.5.0 Background Progress

- 批量转换迁移到 Android 前台服务，即使离开主界面也继续执行。
- 新增常驻转换通知：显示当前文件、已处理/总数、成功/失败数量和系统进度条。
- 通知中加入“停止”操作，可主动结束当前批量队列。
- 转换期间使用 PARTIAL_WAKE_LOCK，降低熄屏后 CPU 被暂停导致任务中断的概率；任务结束立即释放。
- 第二页工具区新增“允许后台耗电 / 电池优化”，可请求系统忽略电池优化。
- Android 13+ 会请求通知权限；Android 14+ 声明 dataSync 前台服务类型。
- MIUI / HyperOS 仍可能有厂商级后台限制，必要时还需在系统应用信息中把电池策略设置为“无限制”。

## v1.4.0 Workspace UI

- 主界面重构为三页底部导航，不再把全部功能堆在同一个长页面。
- 首页“单文件工作台”：只保留选择音乐、单文件格式转换、音频编辑/剪辑以及源文件处理策略。
- 第二页“批量与工具”：集中放置磁盘/目录扫描、一键批量转换、音乐 APP 扫描、处理历史与 FFmpeg 运行检测。
- 第三页“介绍”：保留应用说明、支持能力、隐私/存储说明与开源组件信息。
- 新增固定底部状态条，切换页面时仍可看到扫描、转换和诊断状态。
- 继续沿用 v1.3.2 的批量置换源文件、单文件置换源文件与剪辑试听暂停功能。
- 本次只重构 UI 信息架构和视觉层级，没有改动现有解密、FFmpegKit、Chaquopy、Room 和扫描核心。

## v1.3.2 Source Replace + Preview Pause

- 批量转换弹窗新增“用转换结果置换源文件”选项，默认关闭。
- 置换只在转换成功后执行；优先在 SAF 原位置重命名并覆盖内容。若存储提供方不支持重命名/写入，则保留源文件和已生成输出，避免强制丢失。
- 单文件转换完成后新增“保留 / 删除 / 置换源文件”三种处理方式。
- 音频剪辑完成后同样支持“保留 / 删除 / 用剪辑结果置换源文件”。
- 剪辑器“试听选区”改为播放/暂停切换，可暂停后继续，并继续遵守选区终点自动停止。

## v1.3.1 Batch Confirm Fix

- 修复批量转换弹窗缺少明确“确认转换”按钮的问题。
- 输出格式改为单选列表，默认 MP3（推荐）。
- 同一弹窗中显示扫描文件数、加密/普通音频数量、输出目录和原文件保留策略。
- 点击“确认转换”后直接开始整批处理，不再通过二级确认弹窗。

# MusicConverter MIUI Pro

基于当前已能编译/启动的 Android Studio 工程整理后的下一版。

## 本版实际接入

- SAF 文件选择：支持普通音频和 ncm/qmc/mflac/kgm/kwm 等非标准扩展名。
- `music-geshizhuanhuan` Python 引擎放入 `app/src/main/python`，通过 Chaquopy 3.11 调用。
- FFmpegKit 本地 module 调用，不依赖 `com.arthenica:ffmpeg-kit-*` Maven artifact。
- FFmpeg 格式转换：MP3 / FLAC / M4A / WAV / OGG。
- 实验性音频剪辑：双滑块选择起止时间、选区试听、无损 stream-copy 剪切，失败时自动重编码。
- Room 处理历史。
- 识别常见音乐 APP，并展示“常见下载目录”（仅提示，不绕过 Android 存储沙箱）。
- 转换/剪辑完成后由用户选择是否删除原文件。
- 应用图标恢复为用户提供的图标。

## 首轮真机测试顺序

1. `FFmpeg 运行检测`：应显示 FFmpegKit 版本。
2. 选择普通 MP3/FLAC，进入 `编辑 / 剪辑音频`，先测试 5~10 秒剪切。
3. 测试 MP3 -> FLAC、FLAC -> MP3。
4. 再测试 NCM 等解密文件；如果 Python 依赖初始化异常，请抓 Logcat 中 `Python` / `Chaquopy` 异常。
5. 最后测试“删除原文件”，不同文件提供方可能拒绝删除，此时 APP 会保留源文件。

## 输出目录

Android MediaStore: `Music/MusicConverter/`

## 注意

- QQ 音乐新版无内嵌 EKey 文件仍可能需要外部 EKey/密钥库，上游引擎的限制保持不变。
- 本版编辑功能是第一版：只做单段剪切与试听，不做多轨/波形编辑。
- FFmpegKit 二进制属于第三方开源组件，发布 APP 前请根据实际 FFmpeg 构建配置核对 LGPL/GPL 合规要求。


## v1.2 UI Alpha

- 重新设计首页为 MIUI/HyperOS 风格深色卡片界面。
- 新增应用 Hero 区、文件卡片、双列快捷操作、状态卡片。
- 重做音频剪辑页面：文件信息卡、起止时间卡、着色 SeekBar、试听/重置/保存操作区。
- 保持现有 Chaquopy、FFmpegKit、Room 与转换/剪辑逻辑不变。
- 未新增第三方 UI 依赖，继续使用 Android Framework 控件，降低重新编译风险。


## v1.3 Batch Scan Alpha

- 新增 SAF 目录/磁盘递归扫描。选择“内部存储”根目录时，会扫描系统允许访问的整块存储。
- 自动识别普通音频与 NCM / QMC / MFLAC / KGM / KWM 等加密音乐。
- 新增批量转换队列：扫描一次、选择一次输出格式，即可顺序转换全部文件。
- 批量任务显示扫描数量、转换进度、成功/失败统计和失败摘要。
- 批量模式默认保留原文件，避免大规模误删；输出仍统一保存到 `Music/MusicConverter/`。
- 自动跳过 `MusicConverter` 输出目录，避免再次扫描自己的转换结果。
- Android 11+ 的 `Android/data`、`Android/obb` 等受保护目录仍受系统 SAF 限制，这是 Android 存储沙箱限制。


## v1.6 并行批量转换

- 批量转换新增 1～4 路并行任务选择，默认根据 CPU 核心数推荐 1～3 路。
- 通知栏显示并行路数、活跃任务数、完成/成功/失败进度。
- “停止”会取消当前 FFmpeg 会话，并阻止继续提交新的文件。
- 每个任务使用独立缓存输入、解密目录和临时输出文件，避免同名文件并发冲突。
- 会根据并行路数分配 FFmpeg 每任务线程预算，减少多个 FFmpeg 会话争抢全部 CPU。
- 没有强行创建多个完整 Android 应用进程：FFmpeg/Python 原生库体积较大，多 Android 进程会重复占用内存，反而更容易触发系统回收。本版采用多工作器 + 多 FFmpeg 独立会话的并行方式。

> 建议：多数手机使用 2 路；旗舰多核设备可尝试 3 路。4 路会明显增加温度、耗电和存储 I/O，并不保证更快。


## v1.6.1 优雅暂停

- 批量页新增“完成当前任务后暂停”。
- 点击暂停后不取消正在运行的转换，也不会再派发新的文件。
- 多路并行中已启动的任务全部自然完成后，队列才进入暂停。
- 暂停后可从应用内或通知栏点击“继续”恢复剩余任务。
- 等待暂停期间可点“取消暂停并继续”。
- 通知栏同时提供“暂停/继续”和“停止”：暂停是优雅暂停，停止仍是立即取消。
- 正式暂停期间释放 PARTIAL_WAKE_LOCK，前台服务与常驻通知保留，继续时重新获取唤醒锁。
