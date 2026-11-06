package app.songyan.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.songyan.ime.content.SettingsInForeground
import app.songyan.ime.content.SymbolCatalogStore
import app.songyan.ime.prefs.KeyboardPreferences
import app.songyan.ime.settings.state.StoredSourceResult
import app.songyan.ime.settings.state.StoredSourcesHolder
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import app.songyan.ime.ime.R as ImeR
import app.songyan.ime.settings.R as SettingsR

/**
 * Why this runs on the real keyboard: the app module injects its own quick-content repository and
 * the live scheme's input language into the panels and the quick-insert core, so a library-level
 * fixture cannot prove those production values reach the UI. The built-in symbol and emoji titles
 * are app-UI copy, an imported catalog belongs to its author, and the inserted date follows the
 * input language rather than the app language; each test crosses the app locale with the keyboard's
 * actual state to tell those apart.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class LocalizedQuickContentDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val localeManager = context.getSystemService(LocaleManager::class.java)
    private val preferences = KeyboardPreferences(context)

    /** The production quick-content directory the keyboard repository reads (AppStorage.contentDir). */
    private val contentDirectory = File(context.filesDir, "content")
    private val catalogStore = SymbolCatalogStore(File(contentDirectory, SymbolCatalogStore.DIRECTORY_NAME).toPath())

    private var previousIme: String? = null
    private var wasEnabled = false
    private var previousLocales = LocaleList.getEmptyLocaleList()
    private var previousAccessibilityFlags = 0
    private var hidIdleToolbar = false
    private var previousSource: String? = null
    private var previousSchema: String? = null
    private var schemaChanged = false

    /** The custom catalog this install had before the test, restored instead of being dropped. */
    private var previousCatalog: ByteArray? = null

    /** Whether [previousCatalog] is a real snapshot (a null value then means there was no file). */
    private var catalogCaptured = false

    @Before
    fun setUp() {
        // The keyboard draws in its own window; only a connection asked for interactive windows
        // reports it, so the label reads below can see it.
        val serviceInfo = instrumentation.uiAutomation.serviceInfo
        previousAccessibilityFlags = serviceInfo.flags
        serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = serviceInfo
        previousLocales = localeManager.applicationLocales
        previousSchema = runBlocking { preferences.activeSchemaId.first() }
        val publisher = GenerationPublisher((context.applicationContext as SongyanApplication).storage)
        val active = publisher.readActive()
        previousSource = active?.sourceId
        previousIme = shell("settings get secure default_input_method")
        wasEnabled = shell("ime list -s").lines().contains(KEYBOARD_ID)
        hidIdleToolbar = runBlocking { preferences.hideIdleToolbar.first() }
        snapshotPreviousCatalog()
        if (active == null || active.sourceId != SourceIds.BUILTIN || !publisher.isComplete(active)) {
            useSource(SourceIds.BUILTIN)
        }
        shell("ime enable $KEYBOARD_ID")
        shell("ime set $KEYBOARD_ID")
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (shell("settings get secure default_input_method") != KEYBOARD_ID) {
            check(SystemClock.elapsedRealtime() < deadline) { "$KEYBOARD_ID did not become the default keyboard" }
            Thread.sleep(100)
        }
        // The clipboard and emoji entries of the idle toolbar are the panel doors under test; a
        // hidden idle band would leave no way in.
        runBlocking { preferences.setHideIdleToolbar(false) }
    }

    @After
    fun tearDown() {
        dismissKeyboard()
        restorePreviousCatalog()
        previousSource?.takeIf { it != SourceIds.BUILTIN }?.let(::useSource)
        localeManager.applicationLocales = previousLocales
        previousIme?.takeIf { it.isNotEmpty() && it != "null" }?.let { shell("ime set " + quote(it)) }
        if (!wasEnabled) shell("ime disable $KEYBOARD_ID")
        runBlocking { preferences.setHideIdleToolbar(hidIdleToolbar) }
        if (schemaChanged) {
            previousSchema?.let { schema -> runBlocking { preferences.setActiveSchemaId(schema) } }
        }
        val serviceInfo = instrumentation.uiAutomation.serviceInfo
        serviceInfo.flags = previousAccessibilityFlags
        instrumentation.uiAutomation.serviceInfo = serviceInfo
    }

    /**
     * Built-in symbol library categories are resources, not the catalog's Chinese names: ja 数学記号
     * and zh-Hant 數學 are strings no bundled catalog carries, so a fallback to the author's name or
     * to another language cannot pass. zh-Hans happens to word the categories the way the bundled
     * catalog does, so the two other languages are what prove the mapping.
     */
    @Test
    fun theBuiltInSymbolCategoriesFollowTheAppLanguage() {
        for ((tag, expected) in SYMBOL_CATEGORIES) {
            showKeyboardIn(tag)
            openSymbolPanel(tag)
            val seen = awaitRail(expected)
            assertEquals("$tag symbol rail: ${keyboardLabels()}", expected, seen)
            closePanel(tag)
        }
    }

    /** The built-in emoji groups are localized the same way, including the fallback group (flags). */
    @Test
    fun theBuiltInEmojiCategoriesFollowTheAppLanguage() {
        for ((tag, expected) in EMOJI_CATEGORIES) {
            showKeyboardIn(tag)
            click(inLocale(tag).getString(ImeR.string.ime_toolbar_emoji))
            val seen = awaitRail(expected, EMOJI_TIMEOUT_MS)
            assertEquals("$tag emoji rail: ${keyboardLabels()}", expected, seen)
            closePanel(tag)
        }
    }

    /**
     * A user-imported catalog carries the author's names, even when it reuses a built-in category
     * id: with the app UI in Japanese the built-in title would be 数学記号, so a rail showing that
     * instead of the author's text has rewritten user data.
     */
    @Test
    fun anImportedSymbolCatalogKeepsTheAuthorsCategoryNames() {
        catalogStore.importCatalog(AUTHOR_CATALOG.toByteArray())
        showKeyboardIn("ja-JP")
        openSymbolPanel("ja-JP")
        val authorRail = awaitRail(AUTHOR_CATEGORIES, PANEL_TIMEOUT_MS)
        // A reused built-in id whose title were localized would drop the author's text from the
        // rail, so the full expected order and count above already fail for that rewrite.
        assertEquals("the author's categories: ${keyboardLabels()}", AUTHOR_CATEGORIES, authorRail)
        closePanel("ja-JP")
    }

    /**
     * The inserted date follows the input language of the active scheme. The app UI is set to the
     * language under test first and proven by its own chrome string, so a Japanese UI inserting the
     * Japanese weekday form would fail: the shape stays Chinese whichever app language is shown.
     */
    @Test
    fun theInsertedDateFollowsTheInputLanguageNotTheAppLanguage() {
        selectBundledChineseScheme()
        for ((tag, chrome) in APP_LANGUAGES) {
            showKeyboardIn(tag)
            assertNotNull("$tag: the app UI is not in its language: ${keyboardLabels()}", awaitLabel(chrome))
            clearField()
            val rows = openQuickInsertRows(tag)
            val preview =
                rows.firstOrNull { CHINESE_DATE.matches(it) }
                    ?: throw AssertionError("$tag: no Chinese-style date row; insert rows: $rows")
            assertFalse(
                "$tag: a Japanese weekday row followed the app language: $rows",
                rows.any { JAPANESE_DATE.containsMatchIn(it) },
            )
            click(preview)
            val inserted = awaitSettled { fieldText() }
            assertEquals("$tag: the field received something other than the clicked row", preview, inserted)
            assertTrue("$tag: $inserted is not the Chinese-style date", CHINESE_DATE.matches(inserted))
            assertFalse(
                "$tag: the Japanese weekday form followed the app language",
                JAPANESE_DATE.containsMatchIn(inserted),
            )
        }
    }

    /** A fresh install has keys before it has an engine; panel actions require a published source. */
    private fun useSource(sourceId: String) {
        val settings = SettingsInForeground()
        settings.open()
        try {
            assertEquals(StoredSourceResult.Success, runBlocking { StoredSourcesHolder.current.use(sourceId) })
        } finally {
            settings.close()
        }
    }

    /** The keyboard in [tag]'s app language, in the settings try-out field, with the keys drawn. */
    private fun showKeyboardIn(tag: String) {
        dismissKeyboard()
        localeManager.applicationLocales = LocaleList.forLanguageTags(tag)
        val wanted = LocaleList.forLanguageTags(tag).toLanguageTags()
        check(await(TIMEOUT_MS) { ServiceLocale.wanted(context).toLanguageTags() == wanted }) {
            "the keyboard service did not take $tag: ${ServiceLocale.wanted(context).toLanguageTags()}"
        }
        context.startActivity(
            Intent(context, SongyanSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        clickWhenSettled(By.text(inLocale(tag).getString(SettingsR.string.row_tryout_title)))
        clickWhenSettled(FIELD)
        // Letter layouts expose the numbers page first; the symbols control appears on that page.
        requireNotNull(awaitNode(pageKeyNames(), READY_TIMEOUT_MS)) {
            "the keys never appeared for $tag; keyboard: ${keyboardLabels()}"
        }
    }

    /**
     * The bundled full-pinyin scheme, so the input language under test is the shipped Chinese one
     * whatever the device was left with; a Japanese scheme's weekday form is what the assertions
     * then rule out. The user's previous choice is put back by [tearDown].
     */
    private fun selectBundledChineseScheme() {
        if (previousSchema == KeyboardPreferences.DEFAULT_SCHEMA_ID) return
        runBlocking { preferences.setActiveSchemaId(KeyboardPreferences.DEFAULT_SCHEMA_ID) }
        schemaChanged = true
    }

    /** Hides a running keyboard and finishes the try-out screen, leaving a fresh field for the next tag. */
    private fun dismissKeyboard() {
        repeat(2) {
            device.pressBack()
            SystemClock.sleep(BACK_SETTLE_MS)
        }
    }

    /** The 符号 key's long click opens the catalog panel instead of switching page. */
    private fun openSymbolPanel(tag: String) {
        val recent = inLocale(tag).getString(ImeR.string.ime_panel_recent)
        repeat(3) {
            val key =
                findNode(symbolKeyNames()) ?: run {
                    val numbers =
                        requireNotNull(awaitNode(numberKeyNames(), TIMEOUT_MS)) {
                            "no numbers page control in ${keyboardLabels()}"
                        }
                    assertTrue(numbers.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    requireNotNull(awaitNode(symbolKeyNames(), TIMEOUT_MS)) {
                        "no symbols control on the numbers page: ${keyboardLabels()}"
                    }
                }
            val clicked = key.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            if (clicked && awaitLabel(recent, OPEN_ATTEMPT_MS) != null) return
        }
        throw AssertionError("the symbol panel did not open; keyboard: ${keyboardLabels()}")
    }

    private fun pageKeyNames(): Set<String> = numberKeyNames() + symbolKeyNames()

    private fun numberKeyNames(): Set<String> {
        val simplified = inLocale("zh-CN")
        return setOf(
            simplified.getString(ImeR.string.ime_open_numbers_page),
            simplified.getString(ImeR.string.input_open_numbers_page_hant),
            simplified.getString(ImeR.string.input_open_numbers_page_ja),
        )
    }

    /** The localized accessibility name of the 符号 key for the input languages this build can hold. */
    private fun symbolKeyNames(): Set<String> {
        // Key names use the Simplified Chinese resources independent of the app UI (see KeyNames),
        // so the same three candidates cover the Hans, Hant and Japanese input languages.
        val simplified = inLocale("zh-CN")
        return setOf(
            simplified.getString(ImeR.string.ime_open_symbols_page),
            simplified.getString(ImeR.string.input_open_symbols_page_hant),
            simplified.getString(ImeR.string.input_open_symbols_page_ja),
        )
    }

    /**
     * The quick-insert rows of the phrases panel. They are filled when the panel opens from the core
     * created on the service's own thread, so a first attempt can find no dates yet; closing and
     * reopening recomputes them.
     */
    private fun openQuickInsertRows(tag: String): List<String> {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val clipboard = inLocale(tag).getString(ImeR.string.ime_panel_clipboard)
            if (awaitLabel(clipboard) != null) {
                click(clipboard)
                val insert = inLocale(tag).getString(ImeR.string.ime_panel_quick_insert)
                val rows =
                    if (awaitLabel(insert, PANEL_TIMEOUT_MS) != null) {
                        click(insert)
                        awaitRows()
                    } else {
                        emptyList()
                    }
                if (rows.any { CHINESE_DATE.matches(it) }) return rows
                closePanelIfOpen(tag)
            }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("the quick-insert rows never appeared in $tag; keyboard: ${keyboardLabels()}")
    }

    /** All labels once a Chinese-style date row is on screen; the labels are returned either way. */
    private fun awaitRows(): List<String> {
        val deadline = SystemClock.elapsedRealtime() + PANEL_TIMEOUT_MS
        var labels = keyboardLabels()
        while (SystemClock.elapsedRealtime() < deadline) {
            labels = keyboardLabels()
            if (labels.any { CHINESE_DATE.matches(it) }) return labels
            Thread.sleep(POLL_MS)
        }
        return labels
    }

    private fun closePanel(tag: String) {
        closePanelIfOpen(tag)
        requireNotNull(awaitNode(pageKeyNames(), TIMEOUT_MS)) {
            "the keyboard did not come back after closing the panel; keyboard: ${keyboardLabels()}"
        }
    }

    private fun closePanelIfOpen(tag: String) {
        val close = inLocale(tag).getString(ImeR.string.ime_panel_close)
        if (awaitLabel(close, OPEN_ATTEMPT_MS) != null) click(close)
    }

    /** The try-out field is emptied with real backspace keys, so the next insert starts from nothing. */
    private fun clearField() {
        val field = device.findObject(FIELD) ?: return
        val current = field.text.orEmpty()
        if (current.isNotEmpty()) {
            device.pressKeyCode(KeyEvent.KEYCODE_MOVE_END)
            repeat(current.length) { device.pressKeyCode(KeyEvent.KEYCODE_DEL) }
        }
        check(await(TIMEOUT_MS) { fieldText().isEmpty() }) { "the try-out field still holds ${fieldText()}" }
    }

    /** The try-out field's text from a fresh node; an empty field reports its hint as its text. */
    private fun fieldText(): String {
        repeat(5) {
            try {
                val field = device.findObject(FIELD) ?: return ""
                return field.text?.takeUnless { field.hint != null && it == field.hint }.orEmpty()
            } catch (_: StaleObjectException) {
            }
        }
        throw AssertionError("the try-out field kept being replaced")
    }

    /** The value once two reads half a second apart agree: the editor has applied the last edit. */
    private fun awaitSettled(read: () -> String): String {
        var last = read()
        repeat(10) {
            Thread.sleep(500)
            val now = read()
            if (now == last) return now
            last = now
        }
        return last
    }

    /** Taps the node named [label] in the keyboard window. */
    private fun click(label: String) {
        val node = requireNotNull(awaitLabel(label)) { "no $label in ${keyboardLabels()}" }
        assertTrue("$label did not take the click", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(POLL_MS)
    }

    /** Settings redraws while a deploy finishes, which can replace a node between finding and clicking it. */
    private fun clickWhenSettled(selector: BySelector) {
        repeat(5) {
            val node =
                requireNotNull(device.wait(Until.findObject(selector), TIMEOUT_MS)) { "$selector did not appear" }
            try {
                node.click()
                return
            } catch (_: StaleObjectException) {
            }
        }
        throw AssertionError("$selector kept being replaced")
    }

    private fun awaitLabel(
        label: String,
        timeoutMs: Long = TIMEOUT_MS,
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            findNode(setOf(label))?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    private fun awaitNode(
        anyOf: Set<String>,
        timeoutMs: Long,
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            findNode(anyOf)?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    private fun findNode(anyOf: Set<String>): AccessibilityNodeInfo? {
        for (root in imeRoots()) {
            findIn(root, anyOf)?.let { return it }
        }
        return null
    }

    private fun findIn(
        node: AccessibilityNodeInfo,
        anyOf: Set<String>,
    ): AccessibilityNodeInfo? {
        val label = node.contentDescription?.toString()
        if (label != null && label in anyOf && node.isVisibleToUser) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> findIn(child, anyOf)?.let { return it } }
        }
        return null
    }

    /** Every label the keyboard window exposes, in node order (rail and cell order included). */
    private fun keyboardLabels(): List<String> = imeRoots().flatMap { root -> labelsIn(root) }

    private fun labelsIn(node: AccessibilityNodeInfo): List<String> {
        val labels = ArrayList<String>()
        (node.contentDescription ?: node.text)?.toString()?.takeIf { it.isNotEmpty() }?.let(labels::add)
        for (index in 0 until node.childCount) node.getChild(index)?.let { labels += labelsIn(it) }
        return labels
    }

    private fun imeRoots(): List<AccessibilityNodeInfo> =
        instrumentation.uiAutomation.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .mapNotNull { it.root }

    /** The rail's built-in titles once all of [expected] are on screen, in rail order. */
    private fun awaitRail(
        expected: List<String>,
        timeoutMs: Long = PANEL_TIMEOUT_MS,
    ): List<String> {
        val wanted = expected.toSet()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val seen = keyboardLabels().filter { it in wanted }.distinct()
            if (seen.size == wanted.size) return seen
            Thread.sleep(POLL_MS)
        }
        return keyboardLabels().filter { it in wanted }.distinct()
    }

    /** The app's own language for a tag, the way the settings screens read their text. */
    private fun inLocale(tag: String): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(configuration)
    }

    private fun await(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    /**
     * Runs [command] through a real shell. UiDevice's helper starts it without a shell, so quoting
     * and an empty argument are lost (restoring the empty app-locale list would become the literal
     * "und"); stdin to `sh` keeps the command exactly as written.
     */
    private fun shell(command: String): String {
        val descriptors = instrumentation.uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).bufferedWriter().use { it.appendLine(command) }
        return ParcelFileDescriptor
            .AutoCloseInputStream(descriptors[0])
            .bufferedReader()
            .use { it.readText() }
            .trim()
    }

    /** One shell word, exactly as written. */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Snapshots the catalog the install already had and sets it aside for the built-in checks.
     * A failure to read the previous file stops the test in @Before on purpose: [catalogCaptured]
     * then stays false, so tearDown leaves that file alone instead of guessing it was absent.
     */
    private fun snapshotPreviousCatalog() {
        val active = catalogStore.activeFile
        if (Files.exists(active)) {
            // Reading a directory or an unreadable file fails here, which is what stops the run.
            previousCatalog = Files.readAllBytes(active)
            catalogCaptured = true
            catalogStore.removeCustomCatalog()
        } else {
            catalogCaptured = true
        }
    }

    private fun restorePreviousCatalog() {
        if (!catalogCaptured) return
        val saved = previousCatalog
        previousCatalog = null
        catalogCaptured = false
        if (saved == null) {
            Files.deleteIfExists(catalogStore.activeFile)
            return
        }
        val file = catalogStore.activeFile.toFile()
        file.parentFile?.mkdirs()
        file.writeBytes(saved)
    }

    private companion object {
        /** Debug builds add .debug to the package, not to the class. */
        const val KEYBOARD_ID = "app.songyan.ime.debug/app.songyan.ime.SongyanInputMethodService"
        const val TIMEOUT_MS = 15_000L
        const val PANEL_TIMEOUT_MS = 20_000L
        const val EMOJI_TIMEOUT_MS = 60_000L
        const val OPEN_ATTEMPT_MS = 5_000L
        const val BACK_SETTLE_MS = 300L
        const val POLL_MS = 100L

        /** A debug reinstall redeploys; a large imported scheme can take minutes before the keys are back. */
        const val READY_TIMEOUT_MS = 300_000L

        val FIELD: BySelector = By.clazz("android.widget.EditText")

        /** The toolbar's settings entry in each app language: the same word, three times. */
        val APP_LANGUAGES =
            linkedMapOf(
                "ja-JP" to "キーボード設定",
                "zh-CN" to "键盘设置",
                "zh-Hant" to "鍵盤設定",
            )

        /** ime_symbol_category_*: the built-in symbol library's categories, in catalog order. */
        val SYMBOL_CATEGORIES =
            linkedMapOf(
                "zh-CN" to listOf("数学", "序号", "箭头", "单位", "货币", "图形", "希腊", "其他", "颜文字", "标点"),
                "zh-Hant" to listOf("數學", "序號", "箭頭", "單位", "貨幣", "圖形", "希臘", "其他", "顏文字", "標點"),
                "ja-JP" to
                    listOf(
                        "数学記号",
                        "番号",
                        "矢印",
                        "単位",
                        "通貨",
                        "図形",
                        "ギリシャ文字",
                        "その他",
                        "顔文字",
                        "句読点",
                    ),
            )

        /** ime_emoji_category_*: the built-in emoji groups in the order the catalog lists them. */
        val EMOJI_CATEGORIES =
            linkedMapOf(
                "zh-CN" to listOf("笑脸", "人物", "自然", "饮食", "旅行", "活动", "物品", "符号", "旗帜"),
                "zh-Hant" to listOf("笑臉", "人物", "自然", "飲食", "旅行", "活動", "物品", "符號", "旗幟"),
                "ja-JP" to
                    listOf("顔文字", "人", "動物", "食べ物", "旅行と場所", "アクティビティ", "もの", "記号", "旗"),
            )

        /** The author's names in a catalog that reuses the built-in math id and adds an own one. */
        val AUTHOR_CATALOG =
            """
            {"version":1,
             "categories":[{"id":"math","name":"作者数学分类","order":1},
                           {"id":"extra","name":"作者自建分类","order":2}],
             "items":[{"id":"times","text":"×","name":"乘号","aliases":["cheng"],"category":"math"},
                      {"id":"diamond","text":"◆","name":"方块","aliases":["fangkuai"],"category":"extra"}]}
            """

        val AUTHOR_CATEGORIES = listOf("作者数学分类", "作者自建分类")

        /** 2026年11月5日: the Chinese-style date without a weekday. */
        val CHINESE_DATE = Regex("^\\d{4}年\\d{1,2}月\\d{1,2}日$")

        /** 2026年11月5日(木): the Japanese form, which only a Japanese input language produces. */
        val JAPANESE_DATE = Regex("\\d{4}年\\d{1,2}月\\d{1,2}日\\(.+\\)")
    }
}
