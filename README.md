# Kikoeru Android

连接自建 Kikoeru 服务器的原生 Android 音声播放器。采用 Kotlin、Jetpack Compose、Material 3 和 Media3，最低 Android 8.0。

## 当前实现

- 匿名 / 账号接入、连接检查、服务器切换与加密 token 存储。
- 作品分页、关键词搜索、排序、社团 / 声优 / 标签筛选。
- 作品详情、目录播放、播放全部、下一首播放和追加队列。
- MediaSessionService 后台播放、系统媒体控制、暂停与进度拖动。
- 队列移除和排序、顺序 / 单曲循环 / 列表循环 / 随机模式。
- 0.5–2.0 倍速、睡眠定时、曲目结束时停止。
- 本地最近播放、队列恢复和断点续播；每次恢复重新匹配服务器文件。
- 本地字幕与服务端 AI 字幕：自动匹配、当前句高亮、自动滚动、点击跳转、来源选择与时间调整。
- 明暗主题、动态配色及宽屏布局。
- 隐私模式：应用内封面模糊，系统媒体控制隐藏标题和封面，仅显示“正在播放中”；开关状态持久保存。

离线下载、服务端历史 / 评价同步属于后续 P1，当前不包含。

## 安装测试版

已构建的 0.2.1 调试包位于 [dist/kikoeru-0.2.1-debug.apk](dist/kikoeru-0.2.1-debug.apk)，校验值见 [SHA256SUMS](dist/SHA256SUMS)。将 APK 传到 Android 8.0 或更高版本手机，允许所用文件管理器安装应用后打开安装。

手机连接服务器所在局域网，首次输入你的 Kikoeru 服务器地址并勾选“允许此服务器使用 HTTP”（如果使用 HTTP），无需填写账号。服务器地址没有预置到应用中。

隐私开关位于“设置 → 隐私模式”，默认关闭。开启后对通知、锁屏和系统媒体控制立即生效；应用内的曲目文字仍保留，方便选择内容。支持 Android 8.0 及以上的封面模糊。

## 字幕使用

打开完整播放器，右上角字幕图标可选择来源、开关字幕和调整时间。存在匹配字幕时，封面区域自动显示字幕列表；点击某句可跳转，“自动跟随”可开关。

- 本地：读取服务器作品目录中的 LRC、SRT、VTT、ASS/SSA 文件。优先选择同目录的 `音轨名.lrc` 或 `音轨名.mp3.lrc` 等同名文件；其他名称或不同语言版本可手动选择。
- AI：读取服务器已经完成的生成结果，按作品和完整音轨路径匹配；显示等待中、生成中、失败状态。服务器生成完成后点击“刷新字幕列表”。当前版本不会从 App 提交新的生成任务。
- 同步：跟随播放器实际进度，兼容倍速和拖动；支持 ±30 秒调整，步长 0.5 秒。正值延后字幕，负值提前。字幕开关持久保存；来源选择和每份字幕的时间调整在本次应用运行期间记忆。
- 字幕按纯文本时间轴显示，不渲染 ASS 特效、字体或动画；支持 UTF-8、BOM 指示的 UTF-16，以及服务端声明的字符编码。无法识别的编码会提示转换为 UTF-8。
- 单份字幕上限 2 MB / 20000 条。字幕不写入系统媒体控制，隐私模式继续隐藏系统标题和封面。

## 构建

使用 Android Studio 打开仓库根目录，准备 JDK 17 或 21、Android SDK 36 和 Build Tools 36.0.0。通过 `local.properties` 指定 SDK 路径，然后执行：

```powershell
./gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

本机隔离工具已准备时，可直接使用：

```powershell
./scripts/build.ps1
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`。这是开发调试包，发布前需要使用项目自己的签名配置。

`scripts/bootstrap-build.py` 可下载 Gradle、JDK 和 Android 平台到忽略的 `.tooling` 目录；Android Build Tools / platform-tools 应通过 SDK Manager 安装。构建脚本将 Gradle 缓存、Android 用户目录及 JDK 套接字目录隔离在本项目内，不更改系统 JDK。

## 接入服务器

首次启动时输入完整的服务器根地址；支持端口及反向代理子路径。HTTP 地址需要勾选“允许此服务器使用 HTTP”。无认证服务器不用填写账号；需要认证时应用会显示账号表单。密码不会保存。

点击目录中的音频会从该曲目开始播放同目录音频；点击“播放全部”会按目录顺序加入所有音频。不同编码 / 语言目录不自动去重。

## 代码结构

```text
app/src/main/java/app/kikoeru/android/
  data/       API、URL 与认证策略、DTO、DataStore、Room
  playback/   Media3 Service、会话控制、本地播放快照
  ui/         Compose 页面、主题、ViewModel
scripts/      构建与测试工具
docs/         方案、接口基线与验证记录
```

当前为单 `app` 模块，使用 `AppContainer` 构造注入，保留数据 / 播放 / 界面的代码边界；尚未引入 Hilt 和多 Gradle 模块，降低首版构建与调试复杂度。

## 本地测试服务器

```powershell
python scripts/fixture-server.py
# 或测试认证
python scripts/fixture-server.py --auth
```

模拟器中输入 `http://10.0.2.2:18765`。合成测试账号是 `listener` / `test-password`；测试服务只绑定本机回环地址，提供低音量合成 WAV 与 Range 响应。

详细接口范围和验证边界见 [接口与验证记录](docs/接口与验证记录.md)；产品计划见 [安卓播放器开发方案](docs/安卓播放器开发方案.md)。
