# Songyan for Android (松烟)

[简体中文](README.zh-CN.md)

Songyan is an Android keyboard built on the [RIME](https://rime.im) input method engine. It types in
whatever your RIME input scheme types: pinyin and double pinyin, zhuyin, cangjie, Cantonese,
Japanese and more. Everything stays on your phone. It shares its engine,
schemes and file formats with [Songyan for iOS](https://github.com/Suzukaa-a/songyan-ios).

## Install

1. Download the APK from [Releases](https://github.com/Suzukaa-a/songyan-android/releases):
   `universal` works on every phone, `arm64-v8a` is smaller and fits almost all recent ones.
   Android 11 or later.
2. Open Songyan. The guide walks you through picking an input scheme and turning the keyboard on
   (Settings > System > Keyboard > On-screen keyboard; the path varies by phone).

## What it does

- **Any RIME scheme.** Pick one from the recommended list on first launch (rime-ice, wanxiang,
  bopomofo, cangjie, quick, cantonese, the Japanese カギロイ), or import your own from a file, a
  folder or a link. Nothing is downloaded until you confirm.
- **Layouts:** 26, 14, 18 and 9 keys, plus zhuyin, cangjie, Japanese romaji and
  kana pads. Edit any layout, or build your own pages for numbers and symbols.
- **Simplified or Traditional output** (Taiwan and Hong Kong standards too), switchable per input
  method.
- **Appearance:** dynamic color, built-in appearances, or your own from the
  [appearance editor](https://suzukaa-a.github.io/songyan-themes/). Appearance files work on both Android and iOS.
- **Screen sizes:** one-handed mode, adjustable key height, and on large screens a split keyboard
  with an adjustable large-screen width and side margins.
- **Cursor:** hold space and slide to move the cursor; while composing, the slide moves through the
  spelling.
- **Optional features:** fuzzy pinyin, typo correction, prediction, grammar models,
  phrases, clipboard history, emoji, and backups of the words it learned (shared with iOS).
- Interface in English, Simplified Chinese, Traditional Chinese and Japanese.

## Privacy policy

Songyan collects no personal data and sends nothing to its developers or to anyone else.

- What you type stays on your device. The words it learns, your phrases, the clipboard history
  (off until you turn it on), settings, layouts and appearances are stored in the app's private
  storage.
- The keyboard never goes online. The settings app connects to the internet only when you ask it
  to: downloading an input scheme, dictionary or model, or importing from a link you enter. Those
  requests go to the site that hosts the file (for example GitHub), which sees your IP address as
  with any download; nothing about you or your typing is sent.
- Password fields and fields that ask for no personalized learning are never learned from, and
  the clipboard is not recorded in them.
- Android backup, when you have it on, includes the learned words, phrases, settings, layouts,
  appearances and your Rime configuration files, never the clipboard history. Backup is run by
  the system and your Google account, not by Songyan.
- Permissions: internet and network state for the downloads above; a foreground service and
  notification while an input scheme is being prepared; vibration for key feedback.
- Uninstalling the app deletes all of its data. Questions: open an issue in this repository.

## Build from source

```bash
./gradlew assembleDebug                  # APK in app/build/outputs/apk/debug/
scripts/install-debug.sh --enable-ime    # install on a connected device and turn the keyboard on
```

Toolchain, checks and project rules are in [CONTRIBUTING.md](CONTRIBUTING.md); the module map is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Report security problems as described in
[SECURITY.md](SECURITY.md).

## License

[GPL-3.0-only](LICENSE). Third-party components and their licenses are listed in the app
(Settings > About > Open Source Licenses).
