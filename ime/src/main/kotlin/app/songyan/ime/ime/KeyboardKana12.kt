package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.kana12.Kana12Effect
import app.songyan.ime.keyboard.kana12.Kana12FlickDirection
import app.songyan.ime.keyboard.kana12.Kana12InputModel
import app.songyan.ime.keyboard.kana12.Kana12Observation
import app.songyan.ime.keyboard.layout.KeyAction
import app.songyan.ime.keyboard.session.InputMode
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutPageTarget
import kotlinx.coroutines.launch

/**
 * Controller-owned state of the twelve-key kana surface. Kept as one value so the controller stores
 * one property.
 *
 * Everything here is touched on the controller's operation worker only: a press is queued from the
 * UI thread and resolved there, so the model always reasons about the engine state its previous
 * effect actually landed.
 */
internal class Kana12BindingState {
    /** The pure model: a kana row plus a flick direction to append / replace / literal effects. */
    val model = Kana12InputModel()

    /** The scheme and layout the model was last reconciled with; a change drops its claim. */
    var schemaId: String = ""
    var mainLayoutID: String? = null
}

/** The monotonic press time, in seconds; a queued press keeps the user's own tap timing. */
internal fun kana12Now(): Double = System.nanoTime() / NANOS_PER_SECOND

private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * Whether the twelve-key surface may type right now.
 *
 * Fail closed: only a proven Japanese scheme composing through the engine, with no engine notice,
 * no forced ASCII editor and no English direct input. Every other scheme leaves the kana keys inert
 * instead of letting them type romaji into a Chinese composition.
 */
internal fun KeyboardController.kana12Ready(): Boolean {
    if (retired || engineNotice != null || policy.ascii || privateDirectInput) return false
    val active = session?.takeIf { ready } ?: return false
    if (active.mode != InputMode.CHINESE) return false
    return kana12Scheme()
}

/** Whether the running scheme types Japanese kana, whatever the keyboard is showing right now. */
internal fun KeyboardController.kana12Scheme(): Boolean {
    val profile = session?.takeIf { ready }?.inputLanguage ?: return false
    return profile.language == InputLanguage.JA && profile.family == InputSchemeFamily.JAPANESE
}

/**
 * Handles one press from the twelve-key surface on the UI thread.
 *
 * No engine work runs here: the press goes through the same queue as every other key, so it is
 * resolved after whatever the engine is still doing and in press order, kept across a restart of
 * the same field and while the session is prepared, and never typed into an editor that replaced
 * the one it was pressed in.
 */
internal fun KeyboardController.handleKana12(action: Kana12Action) {
    val at = kana12Now()
    enqueuePress(action == Kana12Action.Backspace) { performKana12(action, at) }
}

/**
 * Drops the model's claim on the composition tail. Callers use it for anything this front end did
 * not produce through the kana keys: another key, a commit, a cursor move, or a hidden keyboard.
 */
internal fun KeyboardController.kana12Reset() {
    kana12.model.reset()
}

/**
 * The state the model may reason about.
 *
 * The composition is the engine's raw input: the kana speller has already rewritten the typed
 * romaji to kana there, while the preedit may be katakana by user option. The caret is the engine's
 * own composition caret, translated from its preedit byte offset; Rime's preeditSelStart / selEnd
 * are the highlighted candidate segment, not an editor selection, so no selection is reported.
 */
internal fun KeyboardController.kana12Observation(): Kana12Observation? {
    val snapshot = session?.takeIf { ready }?.snapshot ?: return null
    // Idle is a known, empty composition — not an unknown one: the model predicts an append from it
    // and keeps its claim, which is what lets the very next tap cycle the row. Null stays reserved
    // for state this front end cannot read at all.
    if (!snapshot.isComposing) return Kana12Observation(caret = 0)
    val composition = snapshot.rawInput
    if (composition.isEmpty()) return null
    // Only a one-to-one preedit (kana shown as hiragana or katakana) turns the engine's caret byte
    // offset into a trustworthy character offset in the composition. Anything else fails closed to a
    // caret the model can never treat as the composition's end.
    val caret =
        if (snapshot.preedit.length == composition.length) {
            snapshot.preedit.utf16CountBeforeUtf8Byte(snapshot.preeditCaretByte).coerceAtMost(composition.length)
        } else {
            0
        }
    return Kana12Observation(composition = composition, caret = caret, hasSelection = false)
}

/** UTF-16 length of the prefix of this text that ends before [byteOffset] of its UTF-8 encoding. */
internal fun String.utf16CountBeforeUtf8Byte(byteOffset: Int): Int {
    if (byteOffset <= 0) return 0
    var bytes = 0
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val units = Character.charCount(codePoint)
        bytes += utf8Length(codePoint)
        if (bytes > byteOffset) return index
        index += units
    }
    return length
}

private fun utf8Length(codePoint: Int): Int =
    when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

/**
 * Reconciles the model with the running scheme and layout before a press. False means the kana
 * surface is not the one typing here: nothing is sent to the engine and any old claim is dropped.
 */
private fun KeyboardController.kana12Prepare(): Boolean {
    if (!kana12Ready()) {
        kana12Reset()
        return false
    }
    val active = session?.takeIf { ready } ?: return false
    val mainLayoutID = mainDefinition().id
    if (kana12.schemaId != active.selectedSchemaID || kana12.mainLayoutID != mainLayoutID) {
        kana12Reset()
        kana12.schemaId = active.selectedSchemaID
        kana12.mainLayoutID = mainLayoutID
    }
    return true
}

private suspend fun KeyboardController.performKana12(
    action: Kana12Action,
    at: Double,
) {
    when (action) {
        is Kana12Action.Kana -> kana12Press(action.rowId, action.direction.coreDirection(), at)
        Kana12Action.Modify -> kana12Modify(at)
        Kana12Action.CursorLeft -> kana12CursorLeft()
        Kana12Action.Symbols -> kana12Navigate(LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS))
        Kana12Action.Language -> kana12Navigate(LayoutAction(LayoutActionKind.LANGUAGE_SWITCH))
        Kana12Action.Backspace -> kana12Navigate(LayoutAction(LayoutActionKind.BACKSPACE))
        Kana12Action.Space -> kana12Navigate(LayoutAction(LayoutActionKind.SPACE))
        Kana12Action.Enter -> kana12Navigate(LayoutAction(LayoutActionKind.ENTER))
        Kana12Action.Globe -> kana12Navigate(LayoutAction(LayoutActionKind.GLOBE))
        // A surface action this front end does not know yet types nothing.
        else -> kana12Reset()
    }
}

/**
 * A control key of the twelve-key surface keeps the keyboard's ordinary behaviour for that key
 * (space and Return are the engine's own Japanese bindings, not front-end decisions), and it ends
 * the model's claim before it runs.
 */
private suspend fun KeyboardController.kana12Navigate(action: LayoutAction) {
    kana12Reset()
    dispatch(action)
}

/** A kana row key authored in layout data: a tap, so the model plays the row's あ段 kana. */
internal suspend fun KeyboardController.kana12Row(
    rowID: String,
    at: Double,
) {
    kana12Press(rowID, Kana12FlickDirection.Center, at)
}

/** The 小゛゜ key of an authored layout page. */
internal suspend fun KeyboardController.kana12Modifier(at: Double) {
    kana12Modify(at)
}

private suspend fun KeyboardController.kana12Press(
    rowID: String,
    direction: Kana12FlickDirection,
    at: Double,
) {
    if (!kana12Prepare()) return
    kana12Apply(kana12.model.press(rowID, direction, at, kana12Observation()))
}

private suspend fun KeyboardController.kana12Modify(at: Double) {
    if (!kana12Prepare()) return
    kana12Apply(kana12.model.pressModifier(at, kana12Observation()))
}

private suspend fun KeyboardController.kana12Apply(effect: Kana12Effect) {
    when (effect) {
        is Kana12Effect.Append -> kana12Type(effect.romaji, effect.backspaces)
        is Kana12Effect.Replace -> kana12Type(effect.romaji, effect.backspaces)
        is Kana12Effect.Literal -> kana12InsertLiteral(effect.text)
        Kana12Effect.None -> Unit
    }
}

/**
 * Sends [backspaces] then every character of [romaji] to the engine, in order.
 *
 * The whole effect runs inside this one queued operation, and the model is reconciled only after
 * all of it landed: an intermediate raw input ("k", "ka") proves nothing about the composition the
 * engine will show for the finished kana, and syncing on it would drop the model's ownership.
 */
private suspend fun KeyboardController.kana12Type(
    romaji: String,
    backspaces: Int,
) {
    val active = session?.takeIf { ready } ?: return
    repeat(backspaces) { active.tap(KeyAction.Backspace) }
    for (character in romaji) active.tap(KeyAction.Code(character.toString()))
    kana12.model.synchronize(kana12Observation())
}

/** Punctuation and brackets are text: they are inserted directly and end the composition. */
private suspend fun KeyboardController.kana12InsertLiteral(text: String) {
    kana12Reset()
    val active = session?.takeIf { ready }
    if (active != null) active.insertLiteral(text) else callbacks.commit(text, false)
}

/**
 * The left-rail cursor key: inside a composition it moves the engine's own caret, so a kana thrown
 * away by BackSpace is the one under the caret and nothing is committed; idle it takes the
 * keyboard's existing relative-cursor route. A backend that cannot move the composition caret does
 * nothing here rather than confirming the composition or typing a made-up key value.
 */
internal suspend fun KeyboardController.kana12CursorLeft() {
    kana12Reset()
    if (!kana12Ready()) return
    val active = session?.takeIf { ready } ?: return
    if (active.snapshot.isComposing) {
        active.moveCompositionCaret(-1)
    } else {
        active.moveCursor(-1)
    }
}

/**
 * Mirrors the stored 只用 flick setting into the model. The store stays the single source: the
 * keyboard only applies the value, and its own settings panel writes through the same store.
 */
internal fun KeyboardController.observeKana12Preferences() {
    val source = preferences ?: return
    scope.launch {
        source.kana12FlickOnly.collect { kana12.model.flickOnly = it }
    }
}
