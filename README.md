# Hey！

Hey！是一个专为车载 Android 系统（如吉利、领克等车型及改装大屏车机）深度定制的车外扩音、音频路由与快捷音效工具。它支持从车内麦克风采集音频，经增益、滤波与防啸叫处理后实时输出至车外低速行人警示扬声器（AVAS）或车内音响，同时提供全局扬声器通道切换、自定义快捷音效插槽、方向盘方控/蓝牙按键监听绑定，以及对 EVCC / Tasker 等车载自动化工具的广播指令对接。

> [!NOTE]
> Hey！需要使用车载系统专有音频通道（如车外扩音/行人警示音 Usage）及部分系统级权限。不同车型、车机固件版本与硬件音频策略存在差异，安装成功不代表所有硬件通道均受支持。

---

## 🌟 主要功能

- **车外实时喊话**：短按中央按钮开始 60 秒限时对讲，长按进入持续喊话模式，再次点击即刻停止；支持麦克风音频电平动态光晕反馈。
- **对称双音量滑杆**：
  - **左侧**：麦克风输入与输出放大增益滑杆（支持细粒度软硬件增益限制）。
  - **右侧**：本地音乐与快捷音效独立播放音量滑杆。
- **输出扬声器通道一键切换**：
  - 📢 **车外扬声器**：切换至车外低速提示音/行人警示音通道，实现车外扩音与喊话。
  - 🎵 **车内音响**：切换至车机常规媒体通道，用作普通车内播放或测试。
- **动态快捷音效按键（1~12 个）**：
  - 支持动态添加与删除按键插槽（最多 12 个）。
  - 每个插槽可自定义名称、绑定本地音频文件（mp3/wav/flac 等）。
  - 支持直接在后台静默播放/停止，**不遮挡车机前台导航（高德/百度）或倒车影像**。
- **方向盘方控与蓝牙按键监听（智能透传）**：
  - 拥有按键录制学习弹窗，支持将方控或遥控按键录制为 **单击**、**双击** 或 **长按**。
  - **原车功能智能透传**：当按键仅绑定了【长按】或【双击】时，短按与单击将在判定超时后**自动无缝回放并还给原车系统**（原车正常切歌、调音量），只有长按或双击时才会触发 Hey!，互不冲突。
  - 集成底层 `MediaSession` 媒体会话与音频焦点机制，完美解决深度定制车机强行拦截切歌键的问题。
- **系统级无障碍后台按键监听**：
  - 借助无障碍服务，在车机处于导航、桌面、收音机或分屏时，均可在后台捕捉按键并触发对应功能。
  - 车机下拉通知栏常驻控制中心，提供一键【开启 / 暂停】按键监听，随时恢复原车按键。
- **EVCC 与外部第三方自动化完整广播对接**：
  - 专为车载第三方自动化软件（如 EVCC、Tasker、快捷指令、ADB 等）打造的独立广播调度中心 [`HeyCommandReceiver`](app/src/main/java/com/kooo/hey/HeyCommandReceiver.kt)。
  - 支持通过标准 Action 触发喊话、停止、前后台切换、静默播放指定快捷音效或一键全停。
- **跨版本协同唤醒引擎（AppWakeupHelper）**：
  - 整合 AppTask 任务栈置顶、Launcher Intent、Android 14+ BAL 后台启动豁免以及高优先级全屏通知蹦床，确保车机后台秒级稳定唤起。
- **车机大屏深度自适应**：
  - 针对 2560×1600 等大屏车机，以及 800dp、1000dp、1400dp 等横竖屏车机分辨率进行了界面组件与字体放大适配。
- **本地调试面板**：
  - 查看实时运行日志，枚举底层音频输入/输出设备与 AudioUsage，测试音频路由、测试音、Ecarx DSP 模式及车机音量组。

---

## 📋 EVCC / 第三方广播自动化配置清单

如果你的车机安装了 **EVCC** 或其他车载自动化按键映射工具，可以通过“发送广播”动作直接与 Hey！无缝联动，作为物理方控按键的可靠执行方案。

### 配置规范
- **目标包名**：`com.kooo.hey`
- **接收器类名**（可选）：`com.kooo.hey.HeyCommandReceiver`
- **额外参数 (Extras)**：无需填写（留空）

### 支持的广播 Action 列表

| 功能描述 | 广播 Action | 动作说明 |
|---|---|---|
| **切换对讲/喊话** | `com.kooo.hey.action.TOGGLE_TALK` | 正在喊话时停止，未喊话时启动（推荐绑在方向盘自定义键） |
| **开始对讲/喊话** | `com.kooo.hey.action.START_TALK` | 直接启动喊话（支持长按按下触发） |
| **停止对讲/喊话** | `com.kooo.hey.action.STOP_TALK` | 停止喊话并退回后台（支持长按松开触发） |
| **应用前/后台切换** | `com.kooo.hey.action.TOGGLE_APP` | 在前台时最小化到后台，在后台时拉起到前台 |
| **播放快捷音效 1** | `com.kooo.hey.action.PLAY_QUICK_1` | 后台静默播放音效 1，再次触发可停止，不遮挡原车导航 |
| **播放快捷音效 2** | `com.kooo.hey.action.PLAY_QUICK_2` | 后台静默播放音效 2 |
| **播放快捷音效 3** | `com.kooo.hey.action.PLAY_QUICK_3` | 后台静默播放音效 3 |
| **播放快捷音效 4** | `com.kooo.hey.action.PLAY_QUICK_4` | 后台静默播放音效 4 |
| ... | `com.kooo.hey.action.PLAY_QUICK_5` ~ `12` | 支持最多 12 个自定义快捷音效插槽 |
| **停止当前音效** | `com.kooo.hey.action.STOP_QUICK_SOUND` | 仅停止当前正在播放的快捷音效 |
| **紧急一键全停** | `com.kooo.hey.action.STOP_ALL` | 停止所有车外喊话与音效输出 |

#### ADB 命令行调用示例：
```bash
# 切换车外喊话
adb shell am broadcast -a com.kooo.hey.action.TOGGLE_TALK -p com.kooo.hey

# 后台播放第 1 个快捷音效
adb shell am broadcast -a com.kooo.hey.action.PLAY_QUICK_1 -p com.kooo.hey

# 一键停止所有声音
adb shell am broadcast -a com.kooo.hey.action.STOP_ALL -p com.kooo.hey
```

---

## 🎮 方向盘方控按键绑定机制

Hey！内置了智能时序判决与原车功能回放引擎：

1. **显式绑定【单击】**：
   - 按下按键时立即触发 Hey！对应的功能，0 延迟拦截，车机不执行原车功能。
2. **仅绑定【长按】**：
   - **短按松开**：如果在 500ms 内松开，系统自动识别为常规点击，**立刻将该键原样还给车机系统**（原车正常切歌/调音量）；
   - **长按不放**：按住超过 500ms 时，消费该按键并触发 Hey！快捷功能，车机不响应原车动作。
3. **仅绑定【双击】**：
   - **单次按下**：进入 300ms 判决窗口；若未按第二次，判定为普通单击并**自动将按键还给车机系统执行**；
   - **快速连按两次**：判定为双击，触发 Hey！快捷功能。

---

## 🛠️ 运行环境与权限

- **最低支持系统**：Android 9.0（API 28）及以上。
- **建议目标系统**：Android 11 ~ Android 14+（已针对高版本后台限制与全屏通知做好适配）。
- **基础权限**：
  - 录音权限（`RECORD_AUDIO`）：用于麦克风实时喊话。
  - 音频与存储权限（`READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`）：用于读取本地音效与音乐。
- **高级系统权限（可选）**：
  - 完整使用系统音频底层路由、DSP 模式切换及音量组时，通常需要系统 Platform 签名或系统特权目录（`priv-app`）支持。

---

## 🏗️ 编译与构建

### 1. 开发环境要求
- JDK 17 或更高版本。
- Android SDK 36（Build-Tools 36.0.0）。
- Gradle 9.x（项目内置 Gradle Wrapper，无需单独安装）。

### 2. 编译命令
```bash
# 编译 Debug 版
./gradlew assembleDebug

# 编译 Platform 签名的 Release 版
./gradlew assembleRelease
```

生成安装包输出路径：
```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/release-distribution/Hey-v*.apk
```

### 3. CI/CD 自动构建
项目已配置 GitHub Actions 自动化工作流（`.github/workflows/release.yml`）：
- 只要在 Git 仓库推送形如 `v*` 的 Release 标签（例如 `git tag v1.5.3 && git push origin --tags`）；
- GitHub 跑道会自动使用 JDK 17 和 Android SDK 36 完成编译、自动打包并使用 Platform 密钥签名，生成发布页面供直接下载。

---

## 📂 项目结构

```text
app/src/main/
├── AndroidManifest.xml          # 核心权限、Activity、Service 与广播 Action 声明
├── java/com/kooo/hey/
│   ├── MainActivity.kt          # 主控界面（双滑块、中央对讲按钮、扬声器通道切换）
│   ├── DebugActivity.kt         # 底层音频测试与高级参数调试面板
│   ├── HeyCommandReceiver.kt    # EVCC 与外部自动化广播指令调度中心
│   ├── AppWakeupHelper.kt       # 跨版本多重协同前台唤醒工具
│   ├── FloatingWindowService.kt # 快捷悬浮对讲窗服务
│   ├── MusicPlaybackService.kt  # 后台媒体播放前台服务
│   ├── audio/
│   │   ├── HotkeyAccessibilityService.kt # 系统级无障碍按键监听服务
│   │   ├── HotkeyManager.kt              # 方控按键时序判决与原车按键重放状态机
│   │   ├── KeyNameHelper.kt              # 按键键值与名称映射
│   │   ├── AudioEngine.kt                # 麦克风录音、实时增益与防啸叫引擎
│   │   ├── AudioFocusHelper.kt           # 车载音频焦点管理
│   │   ├── CarVolumeHelper.kt            # 车载音量组管理接口
│   │   ├── EcarxWorkMode.kt              # 亿咖通/吉利 DSP 降噪与回声消除工作模式切换
│   │   ├── MusicPlaybackController.kt    # 本地音效与音乐控制器
│   │   └── MusicPlayerManager.kt         # 音频播放核心管理
│   ├── config/AppConfig.kt      # 用户偏好设置持久化
│   ├── log/AppLog.kt            # 运行时日志持久化与控制台输出
│   └── ui/
│       ├── KeyLearnDialog.kt    # 方控/蓝牙按键学习与手势录制弹窗
│       ├── CountdownRingView.kt # 对讲环形倒计时动画自定义 View
│       ├── GainSliderView.kt    # 麦克风增益与音量对称滑块控件
│       └── MusicPlayerPanel.kt  # 本地音乐播放选择面板
└── res/
    ├── layout/                  # 默认横竖屏自适应布局
    ├── layout-sw800dp/          # 车机中大屏专属布局
    ├── layout-sw1000dp/         # 车机超大横屏专属布局
    ├── layout-sw1400dp/         # 超宽比例车机大屏布局
    ├── xml/                     # 无障碍服务配置
    └── values*/                 # 国际化字串、颜色、尺寸资源
```

---

## ⚠️ 免责与安全声明

1. **行车安全第一**：请在确保行车安全、遵守当地法律法规且不影响周围交通参与者的前提下使用车外扩音。
2. **声学防啸叫提示**：高麦克风增益在近距离声学回路下可能产生突然的声音反馈或啸叫；首次使用请先在安全停泊状态下从小增益开始测试。
3. **平台签名**：本项目内置的标准测试签名仅供本地测试，不作为私有车机系统的商业生产凭证。

---

## 📄 开源许可证

本项目自有代码遵循 [GNU General Public License v3.0 or later (GPL-3.0-or-later)](LICENSE)。  
Copyright (C) 2026 The Hey Authors.
