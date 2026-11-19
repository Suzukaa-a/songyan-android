# 更新日志 · Changelog

## 未发布

### 新增

- 「键盘布局」里可以自己决定 14 键、18 键和 9 键哪几个编码合用一个键：在按键的「编码」里填写，标题只管显示；导入的布局也一样。重复的编码和缺少的字母会标出来，改好之前不能保存。

### New

- In Keyboard layouts you can choose which codes share a key on 14-, 18- and 9-key layouts: fill in a key's Codes, while its title only sets what it shows; imported layouts work the same way. Repeated codes and missing letters are named, and the layout can't be saved until they're fixed.

## 0.2.0 (2026-11-20)

### 新增

- 支持蓝牙等实体键盘：按键交给正在使用的输入方案，打字时显示候选栏；选词、翻页、标点、切换到英文和方案选单（F4）都按输入方案自己的设置。
- 「输入选项」新增「左滑删除键选择文字」（默认关闭）：按住删除键向左滑选中文字，松手删除。
- 「我的键盘」：每个输入方案可以加入多个键盘布局，长按语言切换键在键盘菜单里切换；装好输入方案后可以直接选要用的键盘。
- 键盘菜单里可以切换输入方案自带的开关。
- 「字体」：导入字体，分别设置候选栏和按键的字体。
- 候选栏可以关闭「显示注释」。
- 输入方案页单独列出依赖包，并写明哪个输入方案在用它。
- 可以像桌面版 Rime 一样导入 Rime 文件夹。
- 新的应用图标，与 iOS 版一致。

### 改进

- 「添加键盘」只列出当前输入方案用得上的布局；缺少必需的键时写明缺哪个键。
- 每个输入方案的 Rime 配置分开保存。
- 键盘布局编辑器的预览与实际键盘一致。
- 脚本出错时显示说明文字，不再显示原始报错。
- 「添加键盘」也列出已经添加的键盘，显示为灰色，标明「已添加」。
- 语言切换键按输入方案的语言显示：韩文显示「한/A」，认不出语言的显示「文/A」，不再一律显示「中/英」。
- 设置里的说明改称「语言切换键」，不再用「中/英」称呼这个键。
- 9 键键盘空格右边的键改为「符」，与 iOS 一致：点按打开符号，长按输入 0。

### 修复

- 刚打开松烟时，首页短暂误报「下载输入方式」「输入方案尚未就绪」。
- 部署出错的输入方案会被反复重试。
- 发布版读不出脚本的名称和说明。

### New

- Bluetooth and other hardware keyboards type through the input scheme in use, with the candidate bar showing while you type; picking candidates, paging, punctuation, switching to English and the scheme menu (F4) follow the input scheme's own settings.
- "Swipe left on Delete to select" in Input Options (off by default): hold Delete and slide left to select text, lift to delete it.
- My Keyboards: add several keyboard layouts to each input scheme and switch between them from the keyboard menu (press and hold the language switch key); after installing an input scheme you can pick the keyboards to use right away.
- The keyboard menu can switch the options an input scheme provides.
- Fonts: import fonts and choose one for the candidate bar and one for the keys.
- "Show notes" can be turned off for the candidate bar.
- Input Schemes lists dependency packages separately and says which input scheme uses each one.
- Import a Rime folder as you would on desktop Rime.
- A new app icon, the same as on iOS.

### Improved

- "Add keyboard" lists only layouts the current input scheme can use, and names any key it needs that a layout lacks.
- Each input scheme keeps its own Rime configuration.
- The keyboard layout editor previews exactly what the keyboard shows.
- Script errors are explained in words instead of raw error text.
- "Add keyboard" also lists the keyboards already added, greyed out and marked "Added".
- The language switch key follows the input scheme's language: 한/A for Korean and 文/A when the language is unknown, instead of always 中/英.
- Settings call it the language switch key instead of naming it by its 中/英 face.
- On the 9-key keyboard, the key right of the space bar is now 符, as on iOS: tap it for symbols, press and hold it to type 0.

### Fixed

- Right after opening Songyan, the home page briefly said "Download an input method" and "Input schemes are not ready yet."
- An input scheme whose setup failed was retried over and over.
- Release builds could not read a script's name and description.
