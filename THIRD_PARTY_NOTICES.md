# 第三方组件声明

Hey！自有代码采用 GPL-3.0-or-later。下列第三方组件仍由各自的版权持有人所有，并继续适用其原始许可证。

## SoundTouch 2.4.0

- 项目主页：<https://www.surina.net/soundtouch/>
- 仓库位置：`app/src/main/cpp/soundtouch/`
- 版权所有：Copyright (c) Olli Parviainen
- 许可证：LGPL-2.1-or-later
- 许可证全文：[LICENSES/LGPL-2.1-or-later.txt](LICENSES/LGPL-2.1-or-later.txt)

SoundTouch 源文件中的原始版权、作者和许可证声明必须保留。当前项目将其静态链接到本地 JNI 音频处理库；分发 APK 时，应同时提供能够重新编译和链接该组件的对应源代码及构建脚本。

## Apache-2.0 组件

以下构建工具和依赖采用 Apache License 2.0。版本以 `gradle/libs.versions.toml` 和 Gradle Wrapper 配置为准：

- AndroidX Core KTX
- AndroidX AppCompat
- AndroidX Activity
- AndroidX ConstraintLayout
- Material Components for Android
- Kotlin Coroutines for Android
- Gradle Wrapper

Apache License 2.0 全文见 [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)。

## 分发说明

构建系统可能解析上述组件的传递依赖。发布 APK 前，应根据最终构建产物重新生成或核对第三方许可证清单，并保留适用的版权、NOTICE 和许可证文本。
