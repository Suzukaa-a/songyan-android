# 松烟 Android 版

[English](README.md)

松烟是一款基于 [RIME 中州韵](https://rime.im) 引擎的 Android 通用中文输入法，是 [松烟 iOS 版](https://github.com/Suzukaa-a/songyan-ios) 的 Android 姊妹项目：引擎、内置方案与数据格式相同，键盘和设置界面按 Android 的习惯重新设计，而不是从 iOS 直译。

## 当前状态

仍在开发中，尚不能日常使用。

## 功能

- RIME 引擎（librime 1.17），包含 `lua`、`octagram`（语法模型）与 `predict` 插件和九键处理器，从源码为 Android 构建并链接成单个原生库。
- 内置全拼和小鹤双拼两种输入方案，可用于 26 键、14 键、18 键、9 键四种键盘布局；14、18、9 键版本由方案自动生成。
- 在合并键上左右滑动可直接打出其中一个字母。
- 支持导入 RIME 输入方案包（文件、文件夹、链接，或从其他 App 打开）与词库，可在设置中启用、更新或删除。
- 键盘外观、候选栏与键盘布局编辑；动态取色、单手模式、大屏与拆分键盘。外观与布局文件可与 iOS 版互相导入导出。
- 可按需开启模糊音、错键纠正、联想（需自行导入 `predict.db`）和语法模型，并支持逐项开关 Lua 组件。
- 符号库、常用语、剪贴板历史、表情面板，以及学习数据的备份与合并（备份格式与 iOS 版通用）。
- 遵循 Android 惯例：`EditorInfo` 与输入法动作、系统输入法切换、边到边 insets、TalkBack、Material 3 设置界面。
- 隐私：输入法进程不发起任何网络请求，只有你在设置里主动操作时才会联网（通过链接导入，或下载词库、模型文件）。密码框和要求不做个性化学习的输入框不会学习，也不记录剪贴板。

## 环境要求

- 运行：Android 8.0（API 26）及以上。
- 构建：JDK 17 或更高版本（CI 用 21）、Android SDK 37（`platforms;android-37.0`、`build-tools;37.0.0`）、NDK 30.0.16248370、CMake 3.31.6。确切版本钉在 [gradle/libs.versions.toml](gradle/libs.versions.toml) 与 [core/rime/build.gradle.kts](core/rime/build.gradle.kts)。用不入库的 `local.properties`（`sdk.dir=...`）或 `ANDROID_HOME` 告诉 Gradle SDK 的位置。
- 首次构建会下载钉住版本的引擎源码并编译，需要一些时间。

## 构建与安装

```bash
./gradlew assembleDebug                  # APK 位于 app/build/outputs/apk/debug/
./gradlew ktlintCheck lint test          # 格式检查、Android Lint、全部 JVM 测试
scripts/install-debug.sh --enable-ime    # 安装到已连接的设备并启用键盘
```

不用脚本时，运行 `./gradlew :app:installDebug`，再到「设置 > 系统 > 键盘 > 屏幕键盘 > 管理键盘」启用松烟（路径因机型而异）。

ABI：引擎默认为 `arm64-v8a`、`x86_64`、`armeabi-v7a` 三种 ABI 构建。本机构建可用 `-Psongyan.abis=arm64-v8a`（逗号分隔）只编指定的 ABI；发布构建不要加。

应用 ID：release 为 `app.songyan.ime`，debug 为 `app.songyan.ime.debug`，基准测试变体为 `app.songyan.ime.benchmark`，测试构建不会替换已安装的正式版。

只有存在 `.local/release-signing.properties`（不入库；键为 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`，`storeFile` 相对于仓库根目录）时，release 才会签名；否则 `assembleRelease` 产出未签名、已压缩的 APK。

原生引擎：Rime 及其插件按 `native/*.env` 钉住的版本由 `native/android/CMakeLists.txt` 从源码构建，静态链接成单个 `libsongyan_rime.so`。`native/` 下的源码、内置方案、OpenCC 数据、示例布局与测试用例从 iOS 仓库同步，不在此仓库直接修改。

## 目录

| 目录 | 内容 |
| --- | --- |
| `app/` | 输入法服务、设置入口、部署服务、引擎运行时 |
| `core/rime/` | 原生引擎构建、JNI、Kotlin 引擎接口、部署 |
| `core/keyboard/` | 输入会话状态机与引擎契约（JVM） |
| `core/layout/` | 布局 JSON 模型、内置布局、滑动与几何（JVM） |
| `core/candidate/` | 候选栏模型与面板几何（JVM） |
| `core/appearance/` | 键盘外观 / 皮肤模型（JVM） |
| `core/content/` | 符号库、常用语、剪贴板历史（JVM） |
| `core/packages/` | 方案包导入、方案清单 / 配套 schema 适配、模糊音、错键纠正、联想、词库导入（JVM） |
| `ime/` | 自绘键盘、候选栏、编辑器桥接 |
| `settings/` | Compose Material 3 设置界面 |
| `native/` | 同步来的 C/C++ 引擎源码、补丁与 Android CMake |
| `benchmark/`、`baselineprofile/` | Macrobenchmark 与 Baseline Profile 生成 |

架构事实见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 与 iOS 版的关系

引擎版本钉、补丁、内置方案与数据格式来自 iOS 仓库，只通过 `scripts/sync-from-ios.sh` 更新，详见 [CONTRIBUTING.md](CONTRIBUTING.md)。引擎行为的改动先在 iOS 仓库落地。

## 隐私政策

松烟不收集任何个人数据，也不向开发者或任何人发送数据。

- 你输入的内容只留在你的设备上。学到的词、常用语、剪贴板记录（默认关闭，你开启后才记录）、设置、布局和外观都存在应用的私有存储里。
- 键盘本身从不联网。只有你在设置里主动操作时才会联网：下载输入方案、词库或模型，或者从你填写的链接导入。这些请求发往存放文件的网站（例如 GitHub），对方和任何下载一样会看到你的 IP 地址；不会发送任何关于你或你输入内容的信息。
- 密码框和要求不做个性化学习的输入框不会学习，也不记录剪贴板。
- 开启 Android 备份时，备份包括学到的词、常用语、设置、布局、外观和你的 Rime 配置文件，不包括剪贴板记录。备份由系统和你的 Google 账号完成，不经过松烟。
- 权限：联网与网络状态用于上面的下载；准备输入方案时使用前台服务和通知；按键反馈使用振动。
- 卸载应用会删除它的全部数据。有问题请在本仓库提 issue。

## 参与贡献与安全

参见 [CONTRIBUTING.md](CONTRIBUTING.md)。安全问题请按 [SECURITY.md](SECURITY.md) 私下报告。

## 许可

松烟采用 [GPL-3.0-only](LICENSE) 许可。第三方组件及其许可见应用内「设置 > 关于 > 第三方许可」，以及 `settings/src/main/assets/legal/`、`settings/src/main/assets/legal-android/`、`app/src/main/assets/rime/starter/`、`ime/src/main/assets/emoji/` 下的声明文件。
