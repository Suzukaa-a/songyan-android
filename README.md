# Songyan for Android (松烟)

[简体中文](README.zh-CN.md)

Songyan is a general-purpose Chinese input method for Android built on the
[RIME](https://rime.im) engine. It is the Android sibling of the
[Songyan iOS keyboard](https://github.com/Suzukaa-a/songyan-ios): the same engine, built-in
schemes and data formats, with a keyboard and settings app designed for Android.

## Features

- RIME engine (librime 1.17) with the `lua`, `octagram` (grammar model) and `predict` plugins and
  the nine-key processor, built from source for Android and linked into one native library.
- Eight built-in keyboard layouts: 26, 14, 18 and 9 keys, plus the Dachen zhuyin (41-key), cangjie
  (26-key, also used for quick), Japanese romaji (26-key) and Japanese kana (12-key) boards. A
  scheme's own key map decides which boards it is offered on.
- Traditional output: the built-in schemes type Simplified, Traditional, Taiwan or Hong Kong text,
  seeded from the system language on first install and switchable per input method. Key names,
  candidate glyph forms and punctuation follow the choice.
- Imports RIME schemes and dictionaries from files or links. A recommended catalog offers the
  common traditional schemes — bopomofo, cangjie, quick and cantonese — as pinned upstream
  archives, downloaded only after you confirm and listed in your language's order. Japanese input
  comes from a kagiroi package you import; the install adds the paging and punctuation patches the
  keyboard expects, and the app does not download Japanese data by itself.
- Appearance, candidate bar and key layout editors; dynamic color, one-handed mode, large-screen
  and split layouts. Appearance and layout files are interchangeable with the iOS app.
- Optional fuzzy pinyin, typo correction, prediction (bring your own `predict.db`), grammar
  models and per-component Lua toggles.
- Keyboard tools that work on one piece of text: the selection, the whole input field or
  the clipboard. Three examples ship (script conversion, full/half width, letter case), and you can
  import, copy, edit and export your own. On Android you can also hand the text to another app that
  handles text; its answer is shown first and only you apply it.
- Symbols, phrases, clipboard history, emoji panel, and learned-data backup and merge (the backup
  format is shared with iOS).
- Interface in English, Simplified Chinese, Traditional Chinese and Japanese.
- Follows Android conventions: `EditorInfo` and IME actions, system input method switching,
  edge-to-edge insets, TalkBack, Material 3 settings.
- Privacy: the input method process makes no network requests. Settings goes online only when you
  ask it to (importing from a link, downloading a dictionary or model). Keyboard tools run on the
  device on the text you pick. Sending text to another app shares that text with the selected app;
  that app handles it under its own privacy policy. Password fields and fields that ask for no personalized learning are never learned
  from, and the clipboard is not recorded in them.

## Requirements

- Android 10 (API 29) or later to run.
- To build: JDK 17 or later (CI uses 21), Android SDK 37 (`platforms;android-37.0`,
  `build-tools;37.0.0`), NDK 30.0.16248370 and CMake 3.31.6. Exact versions are pinned in
  [gradle/libs.versions.toml](gradle/libs.versions.toml) and
  [core/rime/build.gradle.kts](core/rime/build.gradle.kts). Point Gradle at the SDK with a
  git-ignored `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`.

## Build and install

```bash
./gradlew assembleDebug                  # APK in app/build/outputs/apk/debug/
./gradlew ktlintCheck lint test          # formatting, Android Lint, all JVM tests
scripts/install-debug.sh --enable-ime    # install on a connected device and enable the keyboard
```

Without the script, run `./gradlew :app:installDebug`, then enable Songyan under Settings >
System > Keyboard > On-screen keyboard > Manage on-screen keyboards (the path varies by device).

ABIs: the engine is built for `arm64-v8a`, `x86_64` and `armeabi-v7a` by default. For local
builds, `-Psongyan.abis=arm64-v8a` (comma-separated list) builds only the ABIs you name; do not
use it for releases.

Application IDs: release is `app.songyan.ime`, debug is `app.songyan.ime.debug` and the
benchmark variants are `app.songyan.ime.benchmark`, so test builds never replace a release
install.

Release builds are signed only if `.local/release-signing.properties` exists (git-ignored; keys
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`, with `storeFile` relative to the
repository root). Otherwise `assembleRelease` produces an unsigned, shrunk APK.

Native engine: the Rime engine and its plugins are built from sources pinned in `native/*.env` by
`native/android/CMakeLists.txt` and linked into a single static `libsongyan_rime.so`. Sources under
`native/`, the built-in schemes, OpenCC data, example layouts and fixtures are synced from the iOS
repository and are not edited here.

## Repository layout

| Directory | Content |
| --- | --- |
| `app/` | Input method service, settings entry, deployment service, engine runtime, relay for text handed to another app |
| `core/rime/` | Native engine build, JNI, Kotlin engine API, deployment |
| `core/keyboard/` | Input session state machine, input language and layout-family rules, kana model, engine contract (JVM) |
| `core/layout/` | Layout JSON models, built-in layouts, swipe and geometry (JVM) |
| `core/candidate/` | Candidate bar models and panel geometry (JVM) |
| `core/appearance/` | Keyboard appearance/skin models (JVM) |
| `core/content/` | Symbols, phrases, clipboard history, keyboard tools (JVM) |
| `core/packages/` | Scheme-package import, recipe and compact-schema adapters, fuzzy pinyin, typo correction, prediction, dictionary import (JVM) |
| `ime/` | Self-drawn keyboard, candidate bar, editor bridge |
| `settings/` | Compose Material 3 settings app |
| `native/` | Synced C/C++ engine sources, patches, Android CMake |
| `benchmark/`, `baselineprofile/` | Macrobenchmarks and Baseline Profile generation |

Architecture facts are indexed in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Relation to the iOS project

The engine version pins, patches, built-in schemes and data formats come from the iOS repository
and are updated only through `scripts/sync-from-ios.sh`; see [CONTRIBUTING.md](CONTRIBUTING.md).
Engine behaviour changes land in the iOS repository first.

## Privacy policy

Songyan collects no personal data and sends nothing to its developers or to anyone else.

- What you type stays on your device. The words it learns, your phrases, the clipboard history
  (off until you turn it on), settings, layouts and appearances are stored in the app's private
  storage.
- The keyboard never goes online. The settings app connects to the internet only when you ask it
  to: downloading an input scheme, dictionary or model, or importing from a link you enter. Those
  requests go to the site that hosts the file (for example GitHub), which sees your IP address as
  with any download; nothing about you or your typing is sent.
- Keyboard tools work only on the text you choose: the selection, the whole input field or
  the clipboard. They run on your device and send nothing. When you hand text to another app, that
  app processes it and its own policy applies.
- Password fields and fields that ask for no personalized learning are never learned from, and
  the clipboard is not recorded in them.
- Android backup, when you have it on, includes the learned words, phrases, settings, layouts,
  appearances and your Rime configuration files, never the clipboard history. Backup is run by
  the system and your Google account, not by Songyan.
- Permissions: internet and network state for the downloads above; a foreground service and
  notification while an input scheme is being prepared; vibration for key feedback.
- Uninstalling the app deletes all of its data. Questions: open an issue in this repository.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md). Report vulnerabilities privately as described in
[SECURITY.md](SECURITY.md).

## License

Songyan is licensed under [GPL-3.0-only](LICENSE). Third-party components and their licenses are
listed in the app (Settings > About > Open Source Licenses) and in the notice files under
`settings/src/main/assets/legal/`, `settings/src/main/assets/legal-android/`, `app/src/main/assets/rime/starter/` and
`ime/src/main/assets/emoji/`.
