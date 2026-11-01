package app.songyan.ime.keyboard.support

import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind

/**
 * The single source of user-facing terminology for input configuration.
 *
 * The user model is a generic Rime front end: the settings app has two places to act, 输入方案
 * (what is installed) and 我的键盘 (what is on the keyboard). Internal terms (schema, recipe,
 * deploy, generation, ...) never appear in ordinary copy; [leaksInternalTerminology] is the gate.
 *
 * This object holds text only. Android UI strings still live in `res/values/strings.xml`; this
 * keeps the shared wording (statuses, layout names, composed sentences) identical across the
 * keyboard, the settings app and unit tests.
 */
object UserTerminology {
    // MARK: Objects and groups

    /** An installable/usable unit of data. */
    const val INPUT_SCHEME = "输入方案"

    /** A concrete way of typing within a scheme. */
    const val INPUT_METHOD = "输入方式"

    /** Front-end keyboard layout (26 / 14 / 18 / 9 key or custom). */
    const val KEYBOARD_LAYOUT = "键盘布局"

    /** Colors/fonts/candidate-area light-dark. */
    const val KEYBOARD_APPEARANCE = "键盘外观"

    /** Supplementary vocabulary. */
    const val DICTIONARY = "词库"

    /** Language model that predicts whole sentences from the composition. */
    const val GRAMMAR_MODEL = "语法模型"

    /** A set of added "input method + keyboard layout" combinations. */
    const val USER_KEYBOARD = "我的键盘"

    // MARK: Status

    const val STATUS_IN_USE = "正在使用"
    const val STATUS_NOT_APPLIED = "未使用"

    // MARK: Actions

    /** Apply action: switch to a keyboard in 我的键盘, or to a scheme without one. */
    const val START_USING = "使用"

    /** Apply action when a newer version is installed. */
    const val START_USING_NEW_VERSION = "使用新版本"

    const val ADD_INPUT_SCHEME = "添加输入方案"

    /** Plain wording when reading the current selection fails (cause and retry are supplied in place). */
    const val READ_FAILURE_WITH_RETRY = "无法读取当前选择，请重试。"

    /** Generic plain wording for an operation that did not complete. */
    const val GENERIC_OPERATION_FAILURE = "操作未完成，请重试。"

    /** Prefix of the in-use summaries. */
    const val IN_USE_PREFIX = "正在使用"

    // MARK: Home summary

    /**
     * Scheme summary: "正在使用：<scheme> · <input method> · <keyboard layout>". [scheme] null means
     * there is no usable product input scheme.
     */
    fun inUseSummary(
        scheme: String?,
        inputMethod: String?,
        layout: String?,
    ): String {
        if (scheme.isNullOrEmpty()) return "尚未选择输入方案"
        val parts = mutableListOf(scheme)
        if (!inputMethod.isNullOrEmpty() && inputMethod != scheme) parts += inputMethod
        if (!layout.isNullOrEmpty()) parts += layout
        return "$IN_USE_PREFIX：${parts.joinToString(" · ")}"
    }

    /** 我的键盘 row: the keyboard in use, or null when neither part is known. */
    fun currentKeyboardSummary(
        inputMethod: String?,
        layout: KeyboardLayoutKind?,
    ): String? {
        val parts =
            listOfNotNull(
                inputMethod?.takeIf { it.isNotEmpty() },
                layout?.let { layoutName(it) },
            ).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return "$IN_USE_PREFIX：${parts.joinToString(" · ")}"
    }

    /** 输入方案 row: how many schemes are installed and which one the keyboard uses. */
    fun installedSchemeSummary(
        count: Int,
        inUse: String?,
    ): String {
        val installed = installedSourceSummary(count)
        if (count <= 0 || inUse.isNullOrEmpty()) return installed
        return "$installed，$IN_USE_PREFIX「$inUse」"
    }

    /** Installed source count; never called a "scheme count". */
    fun installedSourceSummary(count: Int): String = if (count <= 0) "尚未安装输入方案" else "已安装 $count 个输入方案"

    // MARK: Dynamic dimension display

    fun layoutName(layout: KeyboardLayoutKind): String = layout.userTitle

    fun inputMethodName(schema: KeyboardSchemaDescriptor): String = schema.displayName

    /**
     * Resolves the input method display name from a schema id. Null when no user-readable name can
     * be resolved; an internal schema id is never shown as ordinary text.
     */
    fun inputMethodName(
        schemaID: String?,
        schemas: List<KeyboardSchemaDescriptor>?,
    ): String? {
        if (schemaID == null) return null
        val named = schemas?.firstOrNull { it.id == schemaID }
        if (named != null && named.name.isNotEmpty()) return named.displayName
        val normalized = KeyboardSchemaDescriptor(id = schemaID, name = "").displayName
        return if (normalized == schemaID) null else normalized
    }

    /** The "输入方式：A、B" line; null when there are none (no placeholder is invented). */
    fun inputMethodLine(names: List<String>): String? {
        val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        return "$INPUT_METHOD：${cleaned.joinToString("、")}"
    }

    /** The "键盘布局：26 键、9 键" line; null when no layout is available. */
    fun keyboardLayoutLine(names: List<String>): String? {
        val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        return "$KEYBOARD_LAYOUT：${cleaned.joinToString("、")}"
    }

    /** Plain-language reason when the input method does not support a layout. */
    fun layoutUnavailableReason(layout: KeyboardLayoutKind): String = "此输入方式暂不支持「${layoutName(layout)}」，请改用其他键盘布局。"

    // MARK: User keyboards (input method + keyboard layout)

    const val ADD_USER_KEYBOARD = "添加键盘"

    /** Marks the current item in the switcher. */
    const val USER_KEYBOARD_CURRENT = "当前键盘"

    /** Plain-language explanation when the scheme offers no keyboard at all. */
    const val USER_KEYBOARD_EMPTY_HINT = "这个输入方案没有可用的键盘。"

    /** Hint row when the in-keyboard switcher is empty. */
    const val USER_KEYBOARD_SWITCHER_EMPTY = "尚未添加键盘 · 请在松烟 App 的「我的键盘」中添加"

    /** Read-only explanation when the management transaction is not wired up. */
    const val USER_KEYBOARD_READ_ONLY_NOTICE =
        "这里暂时无法修改。请在「输入方案」中打开方案，再从它的「我的键盘」添加。"

    /** The keyboard is not using any input scheme. */
    const val USER_KEYBOARD_NO_SOURCE = "键盘还没有使用任何输入方案。"

    /** How to make the keyboard start using an input scheme. */
    val USER_KEYBOARD_HOW_TO_START: String
        get() =
            "请在「$INPUT_SCHEME」中打开要用的方案，在它的「$USER_KEYBOARD」里轻点「$START_USING」；" +
                "没有「$USER_KEYBOARD」的方案，直接轻点「$START_USING」。"

    /** Full explanation for the "not using any input scheme" state. */
    val USER_KEYBOARD_NEEDS_SOURCE_HINT: String get() = USER_KEYBOARD_NO_SOURCE + USER_KEYBOARD_HOW_TO_START

    /** Management page footnote. */
    val USER_KEYBOARD_FOOTNOTE: String
        get() = "勾选后点「保存」加入键盘，长按中英键可切换；轻点「$START_USING」立即换用。"

    const val USER_KEYBOARD_SAVING = "正在保存键盘…"

    /** Explanation when saving is blocked because no keyboard is turned on in the draft. */
    const val USER_KEYBOARD_SAVE_NEEDS_ONE = "至少打开一个键盘才能保存。"

    const val USER_KEYBOARD_ADDED = "已添加"
    const val USER_KEYBOARD_NOT_ADDED = "未添加"
    const val USER_KEYBOARD_SELECTED = "已选择"
    const val USER_KEYBOARD_NOT_SELECTED = "未选择"
    const val USER_KEYBOARD_DRAFT_HINT = "保存后生效"

    /** Accessibility label of the toggle: reads both input method and layout. */
    fun userKeyboardToggleLabel(
        inputMethod: String,
        layout: KeyboardLayoutKind,
    ): String {
        val name = inputMethod.trim()
        return if (name.isEmpty()) layoutName(layout) else "$name，${layoutName(layout)}"
    }

    /** Message after "使用" switched the keyboard. */
    fun userKeyboardNowUsing(title: String): String = "已换用「$title」。"

    /** Message for a successful save. */
    fun userKeyboardSaved(count: Int): String = "已保存，共 ${maxOf(0, count)} 个键盘。"

    /** Sentence appended after a failed or rejected write: the list is unchanged. */
    const val USER_KEYBOARD_LIST_UNCHANGED = "当前键盘列表没有改变。"

    // MARK: Trial typing

    /** General explanation on the trial page. */
    const val TRY_USES_CURRENT_SCHEME = "长按中英键切换键盘。"

    /** Source detail context sentence for the trial page. */
    fun trySourceContext(
        name: String,
        inUse: Boolean,
        howToUse: String,
    ): String {
        val trimmed = name.trim()
        val label = if (trimmed.isEmpty()) "这个$INPUT_SCHEME" else "「$trimmed」"
        return if (inUse) {
            "键盘正在使用$label。"
        } else {
            "键盘没有在使用$label。要试打$label，$howToUse。"
        }
    }

    val TRY_HOW_TO_USE_VIA_USER_KEYBOARD: String
        get() = "请先回到上一页，在「$USER_KEYBOARD」里轻点要用的键盘下方的「$START_USING」"

    val TRY_HOW_TO_USE_VIA_START_USING: String get() = "请先回到上一页点「$START_USING」"

    /** Accessibility label/hint of the tools entry. */
    const val KEYBOARD_UTILITIES_OPEN_LABEL = "打开键盘设置"

    /** Tools entry in the "ABC/Chinese" long-press menu. */
    const val KEYBOARD_MORE_TOOLS = "键盘设置…"

    /** Entry from the keyboard's own settings to the app that owns the rest of the configuration. */
    const val OPEN_HOST_APP = "打开「松烟」App"

    // MARK: Copy gate

    /** Internal terms banned from ordinary UI (case-insensitive match). */
    val BANNED_INTERNAL_TERMS =
        listOf(
            "schema",
            "recipe",
            "resource",
            "deploy",
            "prism",
            "algebra",
            "generation",
            "键型",
            "键盘样式",
            "方案配对",
            "部署",
            "发布",
            "配方",
            "绑定",
            "幂等",
            "共享容器",
            "processor",
            "lua_",
        )

    /** Returns the first internal term leaked in [text]; null when none. */
    fun leaksInternalTerminology(text: String): String? =
        BANNED_INTERNAL_TERMS.firstOrNull { text.contains(it, ignoreCase = true) }
}
