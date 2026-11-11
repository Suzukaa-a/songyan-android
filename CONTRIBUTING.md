# Contributing to Songyan for Android

Thanks for helping. Songyan (松烟) for Android is an input method built on the RIME engine and ported from the [iOS keyboard](https://github.com/Suzukaa-a/songyan-ios). This file covers setup, checks and the project rules.

## Prerequisites

- JDK 17 or later (CI uses 21), Android SDK 37 (`platforms;android-37.0`, `build-tools;37.0.0`), NDK 30.0.16248370 and CMake 3.31.6. Create a git-ignored `local.properties` with `sdk.dir=...`, or set `ANDROID_HOME`.
- Local-only files live in `.local/` and `.scratch/`; both are git-ignored and must not be committed. Release signing, if you need it, is read from the `SONGYAN_KEYSTORE_FILE`, `SONGYAN_KEYSTORE_PASSWORD`, `SONGYAN_KEY_ALIAS` and `SONGYAN_KEY_PASSWORD` environment variables or from `.local/release-signing.properties` (keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`, with `storeFile` relative to the repository root). Without either, `assembleRelease` produces an unsigned, shrunk APK. Never commit keystores or passwords.
- For local debug builds, `-Psongyan.abis=arm64-v8a` (or the ABI of your emulator) builds only one ABI and is much faster. Do not use it for release builds.
- A device or emulator is needed only for the instrumented tests and the benchmarks.
- Application IDs: release is `app.songyan.ime`, debug is `app.songyan.ime.debug` and the benchmark variants are `app.songyan.ime.benchmark`, so test builds never replace a release install.
- `scripts/install-debug.sh --enable-ime` installs the debug build and turns the keyboard on. Without it, run `./gradlew :app:installDebug` and enable Songyan under Settings > System > Keyboard > On-screen keyboard.
- The engine (librime and its plugins) is built from the sources pinned in `native/*.env` by `native/android/CMakeLists.txt` and linked into one `libsongyan_rime.so`, for `arm64-v8a`, `x86_64` and `armeabi-v7a` by default.

## Repository layout

| Path | Content |
| --- | --- |
| `app/` | input method service, deployment service, engine runtime |
| `core/*` | engine and pure Kotlin/JVM modules (keyboard session, layouts, candidates, appearance, content, packages) |
| `ime/` | self-drawn keyboard and editor bridge |
| `settings/` | Compose Material 3 settings app |
| `native/` | engine sources and patches synced from the iOS repository |
| `scripts/` | sync, install and verification scripts |

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) indexes modules, entry points, data flows and storage paths. Read it before changing a boundary.

## Checks

Run the checks that match your change. CI runs the full gate on every pull request, and all of it must pass:

```bash
./gradlew ktlintCheck lint test assembleDebug assembleRelease :benchmark:assemble
```

| Command | Use it when |
| --- | --- |
| `./gradlew ktlintCheck` | before every pull request; `./gradlew ktlintFormat` fixes most findings |
| `./gradlew lint test assembleDebug` | before every pull request |
| `./gradlew :core:keyboard:test` | iterating on one module (any module path works) |
| `./gradlew :core:rime:connectedDebugAndroidTest` | engine replay and learning cases; needs a device or emulator |
| `./gradlew :ime:connectedDebugAndroidTest` | keyboard and editor behavior; needs a device or emulator |
| `scripts/check-native-engine.sh <apk>` | native engine changes: checks 16 KB alignment, required symbols and the embedded librime version |

Run the instrumented tests one module at a time, one Gradle invocation each. Several modules in one command run in parallel on the same device and interfere with each other (the engine starts twice, Compose times out), so the failures look like code regressions.

If you cannot run a check, say so in the pull request and list what remains unverified.

## Project rules

- Do not edit the files under `native/` that come from the iOS repository, or the other files listed in [scripts/ios-sync-manifest](scripts/ios-sync-manifest). Change them in the iOS repository and pull them in with `scripts/sync-from-ios.sh /path/to/songyan-ios`; `scripts/check-ios-sync.sh /path/to/songyan-ios` compares them. Engine and dependency versions are pinned in the iOS repository (`Tools/BuildRime/VERSIONS.env` and `Tools/BuildRimeIOS/*-VERSIONS.env`); to upgrade them, change the pin there and re-sync, so both apps run the same engine.
- librime is a process-wide singleton. There is one engine instance, all engine calls run on the single engine dispatcher, and notification callbacks never call back into the engine.
- No synchronous disk access on the main thread (StrictMode is on in debug builds and must stay clean). The input method process makes no network requests; networking happens only when the user asks for it in settings.
- Password fields and fields with `IME_FLAG_NO_PERSONALIZED_LEARNING` are never learned from, never record the clipboard and never show suggestions.
- Ranking and segmentation are the engine's job. The front end must not re-rank or re-segment candidates. The nine-key preedit is display only; committed text always comes from the engine.
- When a capability cannot be proven, fail closed and fall back to native Rime behavior.
- Follow Android conventions (official documentation, Material 3, platform behavior) over copying iOS UI.
- Every bug fix comes with a regression test that reproduces the problem. Behavior in editors is tested with `:ime` instrumented tests against the real engine, not a stub.
- The recommended catalogue's archives are pinned to upstream commits with SHA-256 digests that match the iOS catalogue; change an entry in both repositories together.
- Documents state current facts only (no progress logs or dated notes), and paths and commands in them must exist. When you add or move a module or key file, update [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Do not commit build output, logs, backups or scratch files.

## Modules and directories

- Top-level directories are `app`, `core/*`, `ime`, `settings`, `native`, `benchmark`, `baselineprofile`, `build-logic`, `gradle`, `docs`, `scripts` and `.github`. Before adding a Gradle module or a top-level directory, list it in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and discuss it in the pull request.
- Modules use the standard Gradle source sets (`src/main/kotlin`, `src/test/kotlin`, `src/androidTest/kotlin`, `src/main/res`, `src/main/assets`). Packages start with `app.songyan.ime.<module>` and match the directory.
- The `core/*` modules other than `core/rime` are pure Kotlin/JVM and must not depend on the Android SDK, so their tests run on the JVM. Only `core/rime` touches the native engine.
- Scripts live in `scripts/`, and each starts with a comment giving its purpose and usage. Do not leave one-off scripts in the root or in modules.
- Do not commit build output, logs, backups or parallel copies (`*.bak`, `*_old`, commented-out blocks of old code); delete unused code instead. A committed generated file must be reproducible by a script in `scripts/`, and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) says how. The checked-in Baseline Profile is the exception that is regenerated with `scripts/generate-baseline-profile.sh`.
- Performance is user-visible. Think about the cost of a change on the key-to-preedit and keyboard-show paths, measure with the `:benchmark` module, and state the build type; only measure on builds with the engine optimized (`benchmarkRelease`).

## User-facing text

- Chinese copy reads naturally: short sentences, address the user as 「你」, full-width punctuation (，。：；？！「」（）) in Chinese text. Say what happened first, then what the user can do. The interface does not explain internals or justify itself.
- Internal terms never reach the UI: schema, recipe, deploy, manifest, generation, prism, processor, 部署, 发布, 配方, 绑定 and the like.
- Product terms follow the iOS app's `Packages/KeyboardCore/Sources/KeyboardCore/Support/UserTerminology.swift`: 输入方案, 输入方式, 键盘布局, 键盘外观, 词库, 语法模型, 我的键盘, 正在使用, 未使用, 使用, 使用新版本, 试打, 候选栏, 松烟.
- System concepts use the names Android's own Settings uses in Simplified Chinese (「屏幕键盘」「管理键盘」「默认键盘」), not iOS terms such as 「完全访问」 or 「地球键」. A tap is 「点按」 and a long press is 「长按」.
- Every user-visible string lives in `strings.xml` and is authored in four interface languages: English in `values/`, Simplified Chinese in `values-zh/`, Traditional Chinese in `values-b+zh+Hant/` and Japanese in `values-ja/`. Never build interface text in code. [StringResourceParityTest](app/src/test/kotlin/app/songyan/ime/resources/StringResourceParityTest.kt) and [ResourceContractTest](app/src/test/kotlin/app/songyan/ime/resources/ResourceContractTest.kt) check that the key sets and format arguments match.
- Configuration-file and file-editor errors show the original parser, filesystem or resource-validation message, optionally prefixed with a filename. Only prompts requiring a user decision, such as script confirmation or an oversized file, use fixed localized resources.
- Traditional Chinese resources are generated, never edited by hand. Change `values-zh/` and run `scripts/gen-zh-hant-strings.sh`; it converts with the pinned OpenCC 1.1.9 `s2twp` data and the shared Taiwan wording corrections, leaving resource names, attributes and formatting untouched. `scripts/gen-zh-hant-strings.sh --check` regenerates into a temporary directory and fails on any difference or banned term. Japanese is written by hand and must not be left as the Simplified text.

## Code style

- Comments and identifiers are in English, and comments say why. User-visible strings go into `strings.xml`.
- Formatting follows [.editorconfig](.editorconfig) (UTF-8, LF, 4 spaces, 120 columns) and ktlint. Run `./gradlew ktlintFormat` before committing. Shell scripts must pass `bash -n` and start with a comment giving their purpose and usage.
- Keep changes focused. Add or update tests with the code they cover.

## Commits and pull requests

Write a short imperative subject that says what changed, for example `Centre candidates on the ink of a Han character`, and explain why in the body when it is not obvious. In the pull request, describe the change and name the checks you ran and the ones you did not.

## Security

Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md), not in a public issue.

## License

Songyan is licensed under [GPL-3.0-only](LICENSE). By contributing you agree that your contribution is licensed under the same terms. Code taken from other projects must keep its original copyright and SPDX header and be GPL-3.0-only compatible. Third-party components are listed in the notice files under `settings/src/main/assets/legal/`, `settings/src/main/assets/legal-android/`, `app/src/main/assets/rime/starter/` and `ime/src/main/assets/emoji/`; do not add a dependency without updating them.
