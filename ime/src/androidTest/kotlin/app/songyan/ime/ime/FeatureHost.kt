package app.songyan.ime.ime

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.content.CommonPhrase
import app.songyan.ime.content.SymbolCatalog
import app.songyan.ime.ime.panels.QuickContentPanelContent
import app.songyan.ime.ime.panels.QuickContentPanelDataSource
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.rime.EngineOptionPreferences
import app.songyan.ime.rime.RimeDeployment
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeEngineBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.io.File

/**
 * The shipped engine with the bundled schemes, deployed once per [name]: [english] adds an English
 * dictionary to the full-pinyin scheme the way an imported scheme package mounts one, because the
 * bundled dictionary has no English words. [patch] edits the copied source tree before deployment.
 */
internal class DeployedEngine(
    name: String,
    english: Boolean,
    patch: (File) -> Unit = {},
) {
    val root: File
    val source: File
    val compiled: File

    init {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "feature-engine-$name").also { it.deleteRecursively() }
        source = File(root, "source")
        copyAssetTree(context.assets, "rime/builtin", source)
        if (english) addEnglish(source)
        patch(source)
        val deployment =
            runBlocking {
                RimeDeployment.deploy(
                    sharedDataDir = source,
                    userDataDir = File(root, "deploy"),
                    appName = RimeEngine.DEFAULT_APP_NAME,
                    appVersion = "test",
                )
            }
        assertTrue("deployment failed: ${deployment.lastError}", deployment.success)
        assertNotEquals(0, deployment.schemaIds.size)
        compiled = deployment.compiledDir
    }

    /** [optionsDir] keeps the user's schema switch choices; a second backend over it is a restart. */
    fun backend(optionsDir: File? = null): EngineBackend {
        val user = File(root, "user-" + SystemClock.uptimeMillis())
        return runBlocking {
            RimeEngineBackend.create(
                source,
                user,
                compiled,
                RimeEngine.DEFAULT_APP_NAME,
                "test",
                optionPreferences = optionsDir?.let { EngineOptionPreferences(it) },
                optionResourceID = "test",
            )
        }
    }

    fun delete() {
        root.deleteRecursively()
    }

    private companion object {
        const val SOURCE_ID = "  schema_id: songyan_quanpin\n"
        const val DEPENDS_ON_ENGLISH = "  dependencies:\n    - english\n"
    }

    private fun addEnglish(source: File) {
        File(source, "english.dict.yaml").writeText(
            """
            # Rime dictionary
            ---
            name: english
            version: "1"
            sort: by_weight
            use_preset_vocabulary: false
            ...
            the	the	1000
            that	that	900
            then	then	800
            there	there	700
            thanks	thanks	600
            apple	apple	500
            """.trimIndent() + "\n",
        )
        // The way an imported package mounts one: a schema of its own that the main scheme depends on,
        // so the deployer compiles its dictionary, and a translator namespace that points at it.
        File(source, "english.schema.yaml").writeText(
            """
            schema:
              schema_id: english
              name: English
              version: "1"
            engine:
              processors:
                - speller
                - selector
                - express_editor
              segmentors:
                - abc_segmentor
              translators:
                - table_translator
            speller:
              alphabet: zyxwvutsrqponmlkjihgfedcba
            translator:
              dictionary: english
              enable_completion: true
              enable_sentence: false
              enable_user_dict: false
              initial_quality: 0.5
            """.trimIndent() + "\n",
        )
        val schema = File(source, "songyan_quanpin.schema.yaml")
        val patched =
            schema
                .readText()
                .replace("    - script_translator\n", "    - script_translator\n    - table_translator@english\n")
                .replace(SOURCE_ID, SOURCE_ID + DEPENDS_ON_ENGLISH)
                .trimEnd() +
                """

                english:
                  dictionary: english
                  enable_completion: true
                  enable_sentence: false
                  enable_user_dict: false
                  initial_quality: 0.5
                """.trimIndent() + "\n"
        assertNotEquals("the translators moved", schema.readText(), patched)
        schema.writeText(patched)
    }

    private fun copyAssetTree(
        assets: AssetManager,
        assetPath: String,
        target: File,
    ) {
        target.mkdirs()
        for (name in assets.list(assetPath).orEmpty()) {
            val childPath = "$assetPath/$name"
            val child = File(target, name)
            if (assets.list(childPath).orEmpty().isNotEmpty()) {
                copyAssetTree(assets, childPath, child)
            } else {
                assets.open(childPath).use { input -> child.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}

private class Recording(
    target: InputConnection,
    private val events: MutableList<android.view.KeyEvent>,
) : android.view.inputmethod.InputConnectionWrapper(target, false) {
    override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
        events += event
        return super.sendKeyEvent(event)
    }
}

/** An editor, the keyboard over the real engine and the editor wiring the input method service uses. */
internal class FeatureHost(
    val backend: EngineBackend,
    layout: String = "qwerty26",
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context: Context = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var field: EditText
    lateinit var connection: InputConnection
    lateinit var info: EditorInfo
    lateinit var controller: KeyboardController
    val editor = EditorConnection { connection }
    val recentEmoji = mutableListOf<String>()

    /** Every key event the keyboard sent to the editor (the edit panel must send none for movement). */
    val keyEvents = mutableListOf<android.view.KeyEvent>()
    var backOwned = false

    /** Turns the copied-text chip on (off by default so earlier clipboard use never changes other tests). */
    var chips = false
    var chipTtlMs = ToolbarChipPolicy.DEFAULT_TTL_MS
    private var hostWidth = 0

    /** Clipboard history as the panel stored it; off like a fresh install. */
    @Volatile var clipboardEnabled = false

    @Volatile var clipboardEntries = emptyList<String>()

    /** What the symbol panel and the keyboard's symbol search look in; empty like a fresh install. */
    @Volatile var symbolCatalog = SymbolCatalog()

    /** The phrases the phrase panel and the keyboard's phrase search look in. */
    @Volatile var phrases = emptyList<CommonPhrase>()
    val recentSymbols = mutableListOf<String>()

    /** A stale full snapshot lets tests distinguish saved results from unrelated reloads. */
    @Volatile private var pinnedFullRead: QuickContentPanelContent? = null

    /** Freezes what full reads answer with as it is now. */
    fun pinFullReads() {
        pinnedFullRead =
            QuickContentPanelContent(
                symbolCatalog,
                recentSymbols.toList(),
                phrases,
                clipboardEntries,
                clipboardEnabled,
            )
    }

    private val data =
        object : QuickContentPanelDataSource {
            override suspend fun loadContent(): QuickContentPanelContent =
                pinnedFullRead
                    ?: QuickContentPanelContent(
                        symbolCatalog,
                        recentSymbols.toList(),
                        phrases,
                        clipboardEntries,
                        clipboardEnabled,
                    )

            override suspend fun setClipboardEnabled(enabled: Boolean) {
                clipboardEnabled = enabled
            }

            override suspend fun recordRecentSymbol(text: String): List<String> {
                recentSymbols.remove(text)
                recentSymbols.add(0, text)
                return recentSymbols.toList()
            }

            override suspend fun deleteClipboardEntry(text: String) {
                clipboardEntries = clipboardEntries.filterNot { it == text }
            }

            override suspend fun clearRecentSymbols() = Unit

            override suspend fun loadRecentEmoji(): List<String> = recentEmoji.toList()

            override suspend fun recordRecentEmoji(text: String) {
                recentEmoji.remove(text)
                recentEmoji.add(0, text)
            }
        }

    /** What the keyboard settings panel stored, as the settings app would read it back. */
    val oneHandFlow = MutableStateFlow("off")
    val keyHeightFlow = MutableStateFlow("standard")
    val colorModeFlow = MutableStateFlow("system")
    val hideToolbarFlow = MutableStateFlow(false)
    val bottomGapFlow = MutableStateFlow(0)
    val longPressFlow = MutableStateFlow<Int?>(null)
    val switchKeyFlow = MutableStateFlow(false)
    val keyFlickFlow = MutableStateFlow(FlickDirection.UP.wire)
    val doubleSpacePeriodFlow = MutableStateFlow(true)

    /** The user's nine-key quick symbols as the store would hold them; empty is the default sequence. */
    val quickSymbolsFlow = MutableStateFlow("")

    /** What the keyboard asked of the system's input method machinery: "switch" or "pick". */
    val inputMethodCalls = mutableListOf<String>()

    private val inputMethods =
        object : InputMethodAccess {
            override fun options() = InputMethodOptions()

            override fun switchToOther() {
                inputMethodCalls += "switch"
            }

            override fun pick() {
                inputMethodCalls += "pick"
            }

            override fun startVoice() = false
        }

    /** How many times the keyboard asked to open the settings app. */
    var settingsOpened = 0

    /** The layouts the user turned off in 我的键盘, as the store's `menuHiddenLayouts` would hold them. */
    val hiddenLayoutsFlow = MutableStateFlow<Set<String>>(emptySet())

    private val preferences =
        object : KeyboardPreferencesSource {
            override val layoutId = MutableStateFlow(layout)
            override val activeSchemaId = MutableStateFlow("")
            override val oneHand = oneHandFlow
            override val keyHeight = keyHeightFlow
            override val bottomGapDp = bottomGapFlow
            override val longPressDelayMs = longPressFlow
            override val showSwitchKey = switchKeyFlow
            override val keyFlickDirection = keyFlickFlow
            override val doubleSpacePeriod = doubleSpacePeriodFlow
            override val t9QuickSymbols = quickSymbolsFlow
            override val colorMode = colorModeFlow
            override val hideIdleToolbar = hideToolbarFlow
            override val menuHiddenLayouts = hiddenLayoutsFlow

            override suspend fun setOneHand(value: String) {
                oneHandFlow.value = value
            }

            override suspend fun setKeyHeight(value: String) {
                keyHeightFlow.value = value
            }

            override suspend fun setColorMode(value: String) {
                colorModeFlow.value = value
            }

            override suspend fun setLayoutId(id: String) {
                layoutId.value = id
            }

            override suspend fun setActiveSchemaId(id: String) {
                activeSchemaId.value = id
            }
        }

    fun string(id: Int): String = context.getString(id)

    /**
     * The test app has no focused window, so the system would refuse it the clipboard the way it refuses any
     * background app; the input method service is the default input method and always may read it.
     */
    fun allowClipboardReads() {
        // Background clipboard reads are only restricted from Android 10; shell identity adoption does not exist before it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
        shellIdentityAdopted = true
    }

    private var shellIdentityAdopted = false

    fun clearClipboard() {
        context.getSystemService(android.content.ClipboardManager::class.java).clearPrimaryClip()
    }

    fun start(
        initial: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        width: Int = context.resources.displayMetrics.widthPixels,
        imeOptions: Int = 0,
    ) {
        main {
            field = EditText(context)
            // Before Android 10 the text view's paste and copy actions act on the whole text unless it has focus.
            field.requestFocus()
            field.inputType = inputType
            field.imeOptions = imeOptions
            field.setText(initial)
            field.setSelection(initial.length)
            info = EditorInfo()
            connection = Recording(requireNotNull(field.onCreateInputConnection(info)), keyEvents)
            controller =
                KeyboardController(
                    context,
                    scope,
                    editor.keyboardCallbacks(
                        action = {
                            val policy = EditorPolicy.from(info)
                            if (policy.action == EditorInfo.IME_ACTION_NONE ||
                                policy.action == EditorInfo.IME_ACTION_UNSPECIFIED
                            ) {
                                editor.sendReturn(policy.returnsNewline)
                            } else {
                                connection.performEditorAction(if (info.actionId != 0) info.actionId else policy.action)
                            }
                        },
                        onCommitted = { controller.onTextInserted() },
                        panelsChanged = { backOwned = it },
                        openSettings = { settingsOpened++ },
                        clipboard = context.getSystemService(android.content.ClipboardManager::class.java),
                        inputMethods = inputMethods,
                    ),
                    preferences,
                    quickContentDataSource = data,
                    copiedChipSource =
                        if (chips) {
                            CopiedChipSource(
                                context.getSystemService(android.content.ClipboardManager::class.java),
                                chipTtlMs,
                            )
                        } else {
                            null
                        },
                )
            hostWidth = width
            relayout()
            controller.bind(backend)
            val keep = editor.startInput(info, false)
            val policy = EditorPolicy.from(info)
            controller.configure(policy)
            controller.startInput(policy, keep)
            controller.onWindowShown()
        }
        awaitIdle()
    }

    fun relayout() {
        controller.view.measure(
            View.MeasureSpec.makeMeasureSpec(hostWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST),
        )
        controller.view.layout(0, 0, hostWidth, controller.view.measuredHeight)
    }

    fun text(): String = main2 { field.text.toString() }

    fun selection(): Pair<Int, Int> = main2 { field.selectionStart to field.selectionEnd }

    fun setText(
        text: String,
        start: Int = text.length,
        end: Int = start,
    ) {
        main {
            field.setText(text)
            field.setSelection(start, end)
        }
        // The framework reports the selection to the input method; the test plays that part.
        main {
            editor.onUpdateSelection(0, 0, start, end, -1, -1)
        }
        awaitIdle()
    }

    fun tap(
        label: String,
        view: Class<out View> = KeyboardView::class.java,
        prefix: Boolean = false,
    ) {
        main {
            val target = find(view)
            val labels = labels(target)
            val index = labels.indexOfFirst { if (prefix) it.startsWith(label) else it.equals(label, true) }
            assertTrue("no item '$label' in $labels", index >= 0)
            assertTrue(target.accessibilityNodeProvider.performAction(index, AccessibilityNodeInfo.ACTION_CLICK, null))
        }
        awaitIdle()
    }

    fun tapBar(label: String) = tap(label, CandidateBarView::class.java)

    fun tapToolbar(label: String) = tap(label, ToolbarView::class.java)

    fun type(letters: String) = letters.forEach { tap(it.toString()) }

    fun labelsOf(type: Class<out View>): List<String> = main2 { findOrNull(type)?.let(::labels).orEmpty() }

    fun barLabels(): List<String> = labelsOf(CandidateBarView::class.java)

    fun toolbarLabels(): List<String> = labelsOf(ToolbarView::class.java)

    /** Holds a finger on one item of [type] past the long-press time, then lifts it. */
    fun longPress(
        label: String,
        type: Class<out View>,
    ) {
        var center = 0f to 0f
        main {
            val view = find(type)
            val labels = labels(view)
            val index = labels.indexOfFirst { it == label }
            assertTrue("no item '$label' in $labels", index >= 0)
            val bounds = Rect()
            view.accessibilityNodeProvider.createAccessibilityNodeInfo(index)!!.getBoundsInParent(bounds)
            center = bounds.exactCenterX() to bounds.exactCenterY()
        }
        val start = SystemClock.uptimeMillis()
        main { touch(type, start, MotionEvent.ACTION_DOWN, center, start) }
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + LONG_HOLD_MARGIN_MS)
        main { touch(type, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, center, start) }
        awaitIdle()
    }

    /** Holds a finger on one key for [holdMs], reads [observe] while it is still down, then lifts it. */
    fun <R> hold(
        label: String,
        holdMs: Long,
        observe: () -> R,
    ): R {
        var center = 0f to 0f
        main {
            val view = find(KeyboardView::class.java)
            val labels = labels(view)
            val index = labels.indexOfFirst { it.equals(label, true) }
            assertTrue("no key '$label' in $labels", index >= 0)
            val bounds = Rect()
            view.accessibilityNodeProvider.createAccessibilityNodeInfo(index)!!.getBoundsInParent(bounds)
            center = bounds.exactCenterX() to bounds.exactCenterY()
        }
        val start = SystemClock.uptimeMillis()
        main { touch(KeyboardView::class.java, start, MotionEvent.ACTION_DOWN, center, start) }
        SystemClock.sleep(holdMs)
        val seen = main2(observe)
        main { touch(KeyboardView::class.java, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, center, start) }
        awaitIdle()
        return seen
    }

    /**
     * Holds a key until its popup opens, then moves the finger by each offset (px, from the key's
     * centre) in turn, reading [observe] after every move, and lifts it at the last one.
     */
    fun <R> holdAndSlide(
        label: String,
        offsets: List<Pair<Float, Float>>,
        observe: () -> R,
    ): List<R> {
        var center = 0f to 0f
        main {
            val view = find(KeyboardView::class.java)
            val labels = labels(view)
            val index = labels.indexOfFirst { it.equals(label, true) }
            assertTrue("no key '$label' in $labels", index >= 0)
            val bounds = Rect()
            view.accessibilityNodeProvider.createAccessibilityNodeInfo(index)!!.getBoundsInParent(bounds)
            center = bounds.exactCenterX() to bounds.exactCenterY()
        }
        val start = SystemClock.uptimeMillis()
        main { touch(KeyboardView::class.java, start, MotionEvent.ACTION_DOWN, center, start) }
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + LONG_HOLD_MARGIN_MS)
        var point = center
        val seen =
            offsets.map { (dx, dy) ->
                point = center.first + dx to center.second + dy
                main {
                    touch(
                        KeyboardView::class.java,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE,
                        point,
                        start,
                    )
                }
                main2(observe)
            }
        main { touch(KeyboardView::class.java, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point, start) }
        awaitIdle()
        return seen
    }

    private fun touch(
        type: Class<out View>,
        time: Long,
        action: Int,
        point: Pair<Float, Float>,
        downTime: Long,
    ) {
        val event = MotionEvent.obtain(downTime, time, action, point.first, point.second, 0)
        find(type).dispatchTouchEvent(event)
        event.recycle()
    }

    fun <R> main2(block: () -> R): R {
        var result: Any? = null
        main { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    fun <V : View> find(type: Class<V>): V = requireNotNull(findOrNull(type)) { "no visible ${type.simpleName}" }

    fun <V : View> findOrNull(type: Class<V>): V? {
        relayout()

        fun search(view: View): V? {
            if (type.isInstance(view) && view.isShownIn(controller.view)) return type.cast(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) search(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return search(controller.view)
    }

    private fun View.isShownIn(root: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            if (current === root) return true
            current = current.parent as? View
        }
        return true
    }

    private fun labels(view: View): List<String> {
        val provider = view.accessibilityNodeProvider
        val result = ArrayList<String>()
        var id = 0
        while (true) {
            val node = provider.createAccessibilityNodeInfo(id) ?: break
            result += (node.contentDescription ?: node.text ?: "").toString()
            id++
            if (id > 600) break
        }
        return result
    }

    /** Nothing changes for a few polls: the keys queued so far were answered by the engine. */
    fun awaitIdle() {
        var last = ""
        var stable = 0
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (stable < STABLE_POLLS && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(POLL_MS)
            var state = ""
            main { state = field.text.toString() + "|" + (findOrNull(CandidateBarView::class.java)?.let(::labels)) }
            if (state == last) stable++ else stable = 0
            last = state
        }
    }

    /** Polls [condition] until it holds or the time is up; the last answer is the result. */
    fun await(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(POLL_MS)
        }
        return condition()
    }

    fun assertText(expected: String) {
        await { text() == expected }
        assertEquals(expected, text())
    }

    fun close() {
        try {
            if (shellIdentityAdopted) instrumentation.uiAutomation.dropShellPermissionIdentity()
        } finally {
            closeController()
        }
    }

    private fun closeController() =
        main {
            if (::controller.isInitialized) controller.retire()
            scope.cancel()
        }

    companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 40L
        const val STABLE_POLLS = 3
        const val LONG_HOLD_MARGIN_MS = 300L
    }
}
