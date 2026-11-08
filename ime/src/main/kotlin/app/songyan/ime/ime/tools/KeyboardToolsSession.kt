package app.songyan.ime.ime.tools

import app.songyan.ime.keyboard.engine.InputLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives the tools panel: which text a tool works on, which tool or app runs, and how its result is
 * used.
 *
 * The session owns every decision the panel only displays, so the panel stays a view and the editor
 * writes stay in one place. Three rules shape it:
 *
 * - A tool converts text that is already in the field. The source is captured when the panel opens
 *   and handed to the tool; the user may switch between the selection, the whole field and the
 *   clipboard before choosing a tool.
 * - The result is only written on an explicit action. "Replace" re-reads the source first: when the
 *   text changed while the panel was open, the result is inserted at the caret instead of overwriting
 *   text the user never asked to replace.
 * - An app the user turned on receives the same text through the app's relay. Its answer is only
 *   ever previewed; applying it is the user's explicit action and falls back the same way when the
 *   editor that asked is gone.
 *
 * A run happens on the caller's coroutine scope, never on the engine's queue, and a run that is no
 * longer current (the panel was closed, another tool was picked) is dropped.
 */
internal class KeyboardToolsSession(
    private val scope: CoroutineScope,
    private val panel: KeyboardToolsPanel,
    private val gateway: KeyboardToolsGateway,
    private val editor: KeyboardToolsEditor,
    /** The scheme language at the moment a tool runs; it selects the script-conversion direction. */
    private val inputLanguage: () -> InputLanguage,
    /** Commits [text] at the caret through the keyboard's own insert path, then closes the panel. */
    private val insert: (String) -> Unit,
    /** Leaves the panel for the settings screen where tools are managed. */
    private val manage: () -> Unit,
    /** Covers the key area with [panel]. */
    private val present: () -> Unit,
    /** Closes the panel and returns the key area. */
    private val dismiss: () -> Unit,
) {
    private var selection: KeyboardToolText? = null
    private var field: KeyboardToolText? = null
    private var clipboard: String? = null
    private var kind = KeyboardToolSourceKind.FIELD
    private var tools: List<KeyboardTool>? = null
    private var toolsUnavailable = false
    private var apps: List<KeyboardToolApp>? = null
    private var appsUnavailable = false
    private var speechApps: List<KeyboardToolApp>? = null
    private var selectedTool: String? = null
    private var running = false
    private var result: String? = null
    private var truncated = false
    private var failure: KeyboardToolFailure? = null
    private var copied = false

    /** The answer another app returned, shown as a preview until the user applies it. */
    private var answer: KeyboardToolAnswer? = null

    /** The app the panel just handed the text to; its answer may still be on its way. */
    private var waitingFor: String? = null

    /** The app that could not be opened; the panel says which one and writes nothing. */
    private var appFailed: String? = null

    /** Bumped whenever the panel opens or closes: a late load or run must not touch a closed panel. */
    private var generation = 0

    /** Bumped whenever a new run starts: a late result of an earlier tool is dropped. */
    private var runGeneration = 0

    private var loading: Job? = null

    /** The source text a tool would work on now, or null when the editor offers none. */
    fun open(pinnedToolId: String? = null) {
        openPanel(pinnedToolId)
    }

    /**
     * Shows the answer another app already returned; false when nothing waits. The keyboard calls
     * this when it comes back to the editor the answer belongs to, so the preview is in front of the
     * user without another tap.
     */
    fun openAnswer(): Boolean {
        if (gateway.processText?.pending() == null) return false
        openPanel(null)
        return true
    }

    private fun openPanel(pinnedToolId: String?) {
        val token = ++generation
        runGeneration++
        loading?.cancel()
        selection = editor.selection()
        field = editor.field()
        clipboard = editor.clipboard()
        kind =
            when {
                selection != null -> KeyboardToolSourceKind.SELECTION
                field != null -> KeyboardToolSourceKind.FIELD
                clipboard != null -> KeyboardToolSourceKind.CLIPBOARD
                else -> KeyboardToolSourceKind.SELECTION
            }
        tools = null
        toolsUnavailable = false
        apps = null
        appsUnavailable = false
        speechApps = null
        selectedTool = null
        running = false
        result = null
        truncated = false
        failure = null
        copied = false
        waitingFor = null
        appFailed = null
        answer = gateway.processText?.pending()
        push()
        present()
        loading =
            scope.launch {
                val processText = gateway.processText
                if (processText != null) {
                    apps =
                        try {
                            processText.apps()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            appsUnavailable = true
                            emptyList()
                        }
                    speechApps =
                        try {
                            processText.speechApps()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            emptyList()
                        }
                }
                if (token != generation) return@launch
                // An answer being previewed is not the moment to list tools the user cannot pick.
                if (answer == null) {
                    when (val catalog = gateway.catalog(inputLanguage())) {
                        is KeyboardToolList.Read -> tools = catalog.tools
                        KeyboardToolList.Unavailable -> toolsUnavailable = true
                    }
                }
                push()
                // A tool pinned to the toolbar is its one-tap entry: open it with the run already done.
                if (answer == null && pinnedToolId != null && tools?.any { it.id == pinnedToolId } == true) {
                    run(pinnedToolId)
                }
            }
    }

    /** The panel was closed by any path (its close button, Back, a write): no late update may land. */
    fun closed(preserveExternalAnswer: Boolean = false) {
        generation++
        runGeneration++
        loading?.cancel()
        loading = null
        running = false
        waitingFor = null
        // A preview the user closed without applying is dropped; the request itself is left alone
        // while its app is still open, because the panel hides when that app takes the screen.
        if (answer != null && !preserveExternalAnswer) gateway.processText?.dismiss()
        answer = null
    }

    /** Switches the text the tools work on; a result of the previous source is dropped. */
    fun chooseSource(value: KeyboardToolSourceKind) {
        if (value == kind) return
        kind = value
        selectedTool = null
        result = null
        truncated = false
        failure = null
        copied = false
        push()
    }

    /** Runs [toolId] on the current source. */
    fun run(toolId: String) {
        if (running) return
        val source = currentSource() ?: return
        val token = generation
        val run = ++runGeneration
        selectedTool = toolId
        running = true
        result = null
        truncated = false
        failure = null
        copied = false
        push()
        scope.launch {
            val outcome = gateway.run(toolId, source.text, inputLanguage())
            if (token != generation || run != runGeneration) return@launch
            running = false
            when (outcome) {
                is KeyboardToolResult.Text -> {
                    result = outcome.text
                    truncated = outcome.inputTruncated
                }
                is KeyboardToolResult.Failed -> failure = outcome.failure
            }
            push()
        }
    }

    /**
     * Hands the current source to the other app [appId]. The answer arrives through the relay, so
     * nothing is written here; the panel is in front of the user again once the answer is back.
     */
    fun runApp(
        appId: String,
        label: String,
    ) {
        if (running) return
        val processText = gateway.processText ?: return
        // The app gets exactly what the panel shows, never a source the user did not pick.
        val source = currentSource() ?: return
        appFailed = null
        waitingFor = null
        answer = null
        result = null
        truncated = false
        failure = null
        copied = false
        if (processText.open(appId, label, source)) {
            waitingFor = label
        } else {
            appFailed = label
        }
        push()
    }

    /**
     * Starts the speech app [appId]. It needs no source text: the transcript comes back through the
     * relay as an answer the user can only insert or copy.
     */
    fun runSpeech(
        appId: String,
        label: String,
    ) {
        if (running) return
        val processText = gateway.processText ?: return
        appFailed = null
        waitingFor = null
        answer = null
        result = null
        truncated = false
        failure = null
        copied = false
        if (processText.openSpeech(appId, label, inputLanguage())) {
            waitingFor = label
        } else {
            appFailed = label
        }
        push()
    }

    /** Writes the result over the source, or inserts it when the source is no longer the same. */
    fun replace() {
        if (running) return
        if (answer != null) {
            applyAnswer()
            return
        }
        val text = result ?: return
        val source = currentSource() ?: return
        if (truncated || source.kind == KeyboardToolSourceKind.CLIPBOARD) return
        // The field may have changed while the preview was on screen: never overwrite text the tool
        // never saw. A source that changed only leaves the insertion the safe action.
        val now =
            when (kind) {
                KeyboardToolSourceKind.SELECTION -> editor.selection()
                KeyboardToolSourceKind.FIELD -> editor.field()
                KeyboardToolSourceKind.CLIPBOARD -> null
            }
        if (now == null || now.text != source.text || now.caret != source.caret) {
            insert(text)
            return
        }
        if (editor.replace(source, text)) dismiss() else insert(text)
    }

    /** Commits the result at the caret. */
    fun insertResult() {
        if (running) return
        val waiting = answer
        if (waiting != null) {
            gateway.processText?.dismiss()
            answer = null
            insert(waiting.text)
            return
        }
        result?.let(insert)
    }

    /** Copies the result; a tool result keeps the panel open so the user sees that it happened. */
    fun copyResult() {
        if (running) return
        if (answer != null) {
            // An app's answer is consumed by copying: the relay's own prompt says it was copied.
            if (gateway.processText?.copy() != true) return
            answer = null
            dismiss()
            return
        }
        val text = result ?: return
        if (!editor.copy(text)) return
        copied = true
        push()
    }

    /** Opens the settings where tools are managed; the panel leaves first. */
    fun manageTools() {
        dismiss()
        manage()
    }

    /**
     * Applies the waiting answer: the app's answer is written over the source it came from, or only
     * copied when that editor is gone or changed. The relay makes both decisions with the binding it
     * still holds, so this never writes into another field.
     */
    private fun applyAnswer() {
        val processText = gateway.processText ?: return
        answer = null
        processText.apply()
        // Replaced or copied, the answer is consumed and the panel has nothing left to show; a
        // dropped answer means nothing waited any more, and the relay's prompt already explained
        // the copy when it made one.
        dismiss()
    }

    private fun currentSource(): KeyboardToolText? =
        when (kind) {
            KeyboardToolSourceKind.SELECTION -> selection
            KeyboardToolSourceKind.FIELD -> field
            KeyboardToolSourceKind.CLIPBOARD ->
                clipboard?.let { KeyboardToolText(it, KeyboardToolSourceKind.CLIPBOARD) }
        }

    private fun push() {
        panel.update(
            KeyboardToolsPanelState(
                kind = kind,
                selectionAvailable = selection != null,
                fieldAvailable = field != null,
                clipboardAvailable = clipboard != null,
                source = currentSource(),
                tools = tools,
                toolsUnavailable = toolsUnavailable,
                apps = apps,
                appsUnavailable = appsUnavailable,
                speechApps = speechApps,
                selectedTool = selectedTool,
                running = running,
                result = result,
                truncated = truncated,
                failure = failure,
                copied = copied,
                answer = answer,
                waitingFor = waitingFor,
                appFailed = appFailed,
            ),
        )
    }
}
