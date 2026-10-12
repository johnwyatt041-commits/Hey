# Hey！

Hey！是一个面向吉利、领克相关车型的扩音与音频工具。它从设备麦克风采集声音，经增益、滤波和音效处理后输出到系统音频通道，同时提供语音播报、本地音乐播放和音频路由调试能力。

> Hey！需要使用车载系统音频接口及部分系统级权限，不是面向普通 Android 手机的通用扩音器。不同车型、车机版本和音频策略存在差异，安装成功不代表所有功能均可用。

## 主要功能

- 实时喊话：短按开始 60 秒喊话，长按进入持续喊话，再次点击即可停止。
- 音量增益：通过主界面滑杆实时调整麦克风输出增益。
- 变声效果：支持原声、大叔、萝莉、机器人和电音效果。
- 语音播报：添加、管理并播放常用文字播报内容。
- 本地音乐：选择设备内的音乐目录，支持单曲、列表循环和单曲循环。
- 本地音频处理：提供带通滤波、噪声门、自动增益、频率偏移和混响等选项。
- 快捷操作：可启用悬浮按钮，也支持广播指令触发开始、停止或切换喊话。
- 调试面板：查看本地运行日志，枚举音频设备，测试音频输出、麦克风、音频路由及音量组。
- 本地设备配置：根据 `Build.MODEL` 加载随应用打包的默认参数。
- 自适应界面：统一版本覆盖横竖屏和不同尺寸的车机屏幕。

## 运行环境

- Android 9（API 28）及以上。
- 兼容的车载 Android 音频系统。
- 麦克风权限；播放本地音乐时还需要音频文件读取权限。
- 完整使用系统音频路由、音量组和系统日志相关功能时，通常需要兼容的平台签名或等效的系统权限配置。

项目不会提供或提交任何平台签名私钥。请仅使用你有权使用的签名材料。

## 使用方法

### 基本喊话

1. 启动应用并授予麦克风权限。
2. 短按中央按钮，松开后开始 60 秒喊话。
3. 长按中央按钮，等待进度环完成后松开，即可进入持续喊话。
4. 喊话过程中再次点击中央按钮即可停止。
5. 使用底部增益滑杆调整输出音量；出现反馈或啸叫时应立即降低增益。

### 变声、语音与音乐

- 点击“变声”，选择需要的实时音效；喊话过程中切换也会立即生效。
- 点击“语音”，输入并保存常用播报文本，然后选择条目播放。
- 点击“音乐”，授予音频文件权限并选择音乐目录，然后选择曲目和播放模式。
- 点击返回按钮可退出语音或音乐面板。

### 设置与调试

主界面的设置按钮用于打开调试面板。可以在其中调整：

- 输入、输出设备和音频用途参数。
- 软件增益范围、混响和降噪选项。
- 默认启动功能和自动播放条目。
- 悬浮按钮的开关、尺寸和透明度。
- 音频设备枚举、测试音、麦克风测试及本地日志。

这些参数直接影响系统音频行为。修改前建议记录原值，并从较低增益开始测试。

### 外部广播控制

应用公开了三个本地广播动作，可供 ADB、硬件按键映射或其他本地自动化工具调用：

```bash
adb shell am broadcast -a com.kooo.hey.action.START_TALK
adb shell am broadcast -a com.kooo.hey.action.STOP_TALK
adb shell am broadcast -a com.kooo.hey.action.TOGGLE_TALK
```

## 本地设备配置

默认配置位于：

```text
app/src/main/res/raw/device_configs.json
```

应用使用 `Build.MODEL` 精确匹配配置；没有匹配项时读取 `_default`。配置只会写入尚不存在的本地首选项，不会覆盖用户已经修改的设置。

新增设备配置时，建议只填写需要覆盖的字段：

```json
{
  "_default": {
    "audioSource": 7,
    "audioUsage": 1,
    "volumeGain": 2.0,
    "minGainLimit": 0.5,
    "maxGainLimit": 10.0,
    "enableFloatingWindow": false
  }
}
```

音频用途、设备 ID 和增益范围取决于目标系统。请通过调试面板逐项验证，不要直接照搬其他设备的参数。

## 构建

### 开发环境

- JDK 17 或更高版本。
- Android SDK 36。
- Android NDK。
- CMake 3.22.1。

项目使用 Gradle Wrapper，无需单独安装 Gradle。

### 签名

Debug 和 Release 默认使用仓库内的标准 AOSP `platform` 测试签名
（`keystore/platform.p12`），与星愿适配版保持一致，无需额外配置即可编译。

这是一套公开测试密钥，不提供私密性或生产环境的身份保证。请勿将其用作正式产品或
私有系统的安全信任根。需要改用自己的签名时，可在用户级 `gradle.properties` 中覆盖：

```properties
HEY_PLATFORM_KEYSTORE=/absolute/path/to/platform.p12
HEY_PLATFORM_STORE_PASSWORD=your_store_password
HEY_PLATFORM_KEY_ALIAS=your_key_alias
HEY_PLATFORM_KEY_PASSWORD=your_key_password
```

### 编译 APK

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

主要输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/release-distribution/*.apk
```

### 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

普通用户签名无法获得签名级或系统级权限。若目标系统要求平台签名、预装白名单或特权应用目录，需要按照该系统的合法集成方式完成部署。

## 项目结构

```text
app/src/main/
├── java/com/kooo/hey/
│   ├── audio/          # 录音、播放、音频焦点、路由和音量控制
│   ├── audio/dsp/      # 滤波、频移和变声桥接
│   ├── config/         # 本地配置及设备默认参数
│   ├── log/            # 应用运行日志
│   ├── ui/             # 主界面功能面板和自定义控件
│   └── *.kt / *.java   # Activity、Service、Receiver 与快捷控制
├── cpp/
│   ├── voice_effect/   # JNI 变声处理
│   └── soundtouch/     # SoundTouch 第三方音频处理库
└── res/
    ├── layout*/        # 自适应界面
    ├── raw/            # 本地设备配置
    └── values*/        # 主题、文字和尺寸资源
```

## 安全提示

请在安全、合法且不会影响驾驶的场景下使用。高增益和近距离声学回路可能产生突然的啸叫或过大音量；首次测试时请降低系统音量、远离扬声器，并随时准备停止输出。

## 参与贡献

欢迎通过 Issue 或 Pull Request 提交问题、适配和改进。提交代码前请确保：

- 不包含签名私钥、密码、令牌或其他敏感材料。
- 不包含未经授权的专有 SDK、固件或逆向所得受限代码。
- 新增第三方组件时同时注明来源、版权和许可证。
- 提交内容可以按照本项目许可证进行分发。

## 开源协议

除特别注明的第三方组件外，Hey！自有代码采用 [GNU General Public License v3.0 or later](LICENSE)。

这意味着你可以运行、研究、修改和分发本项目；如果向他人分发修改后的程序或 APK，需要同时按 GPLv3 提供对应源代码和相同的许可权利。

`app/src/main/cpp/soundtouch/` 中的 SoundTouch 2.4.0 继续采用 LGPL-2.1-or-later。其他依赖保留各自的许可证。完整信息参见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 [LICENSES](LICENSES/)。

Copyright (C) 2026 The Hey Authors
