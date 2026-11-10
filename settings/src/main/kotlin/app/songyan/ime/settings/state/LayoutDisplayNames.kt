package app.songyan.ime.settings.state

import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPageDefinition
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.settings.R

/**
 * How a keyboard's main layout is called. A bundled board is worded by the UI language; the user's
 * own layout keeps the name it was saved with, so no automatic name ever replaces it.
 */
sealed interface LayoutDisplayName {
    /** A bundled board or shipped shape; [resource] words it in the user's language. */
    data class Worded(
        val resource: Int,
    ) : LayoutDisplayName

    /** The user's own layout; its saved name is never replaced by a generated one. */
    data class Authored(
        val name: String,
    ) : LayoutDisplayName
}

/** The main layout the keyboard in use draws, resolved against the catalog the keyboard reads. */
data class CurrentMainLayout(
    /** The layout definition id: a physical board (zhuyin41, cangjie26, romaji26) or a shipped shape. */
    val id: String,
    /** The name every settings surface words this keyboard by. */
    val displayName: LayoutDisplayName,
)

/**
 * The resource that words a bundled physical board by id. Null keeps the caller's own name, so an
 * id this build does not ship is never renamed.
 */
fun bundledLayoutNameRes(layoutId: String?): Int? =
    when (layoutId) {
        InputLayoutPolicy.ZHUYIN_LAYOUT -> R.string.layout_name_zhuyin41
        InputLayoutPolicy.CANGJIE_LAYOUT -> R.string.layout_name_cangjie26
        InputLayoutPolicy.ROMAJI_LAYOUT -> R.string.layout_name_romaji26
        InputLayoutPolicy.KANA_LAYOUT -> R.string.layout_name_kana12
        else -> null
    }

/**
 * Names [layout], the main layout a keyboard draws. A bundled board whose name was never changed is
 * worded by resources, edited keys or not: the stored name is the shipped data's Chinese, not the
 * user's text. A renamed board or the user's own layout keeps the name it was saved with, so no
 * automatic name ever replaces the author's text. A null [layout] (the catalog is not read yet)
 * keeps [shape]'s name as before.
 */
fun layoutDisplayName(
    layout: LayoutDefinition?,
    shape: KeyboardLayoutOption,
): LayoutDisplayName {
    if (layout == null) return LayoutDisplayName.Worded(shape.nameRes)
    if (layout.name != BuiltinLayouts.byId(layout.id)?.name) return LayoutDisplayName.Authored(layout.name)
    bundledLayoutNameRes(layout.id)?.let { return LayoutDisplayName.Worded(it) }
    return LayoutDisplayName.Worded(shape.nameRes)
}

/**
 * The main layout the keyboard with [inputMethodId] and shape [shapeId] actually draws. The saved
 * pairing wins; without one the scheme family's preferred board answers; only then the shape's own
 * preset. Nothing is guessed from the UI language, and a saved pairing is never rewritten here.
 * Returns null until the catalog is read or when the shape is unknown.
 */
fun currentMainLayout(
    inputMethodId: String?,
    shapeId: String,
    family: InputSchemeFamily?,
    registry: LayoutRegistry?,
): CurrentMainLayout? {
    if (registry == null) return null
    val inputMethod = inputMethodId?.takeIf { it.isNotBlank() } ?: return null
    val shape = LayoutInput.Builtin.fromWire(shapeId) ?: return null
    val families = family?.let { LayoutFamilyContext.of(inputMethod to it) } ?: LayoutFamilyContext.UNKNOWN
    val definition =
        registry.resolvedLayout(
            registry.composition(KeyboardCompositionKey(inputMethod, shape), families).mainLayoutID,
        ) ?: return null
    return CurrentMainLayout(definition.id, layoutDisplayName(definition, KeyboardLayoutOption.fromId(shapeId)))
}

/**
 * Names [page] in a page list. The bundled pages are code-owned and never edited, so their stored
 * Chinese names are words of the shipped data, not of the user: the UI language words them. Every
 * other page keeps the name it was saved with.
 */
fun pageDisplayName(page: KeyboardPageDefinition): LayoutDisplayName {
    val resource =
        when (page.id) {
            KeyboardPagePresets.SYMBOLS_SYSTEM_ID -> R.string.page_name_symbols_system
            KeyboardPagePresets.SYMBOLS_CATEGORIZED_ID -> R.string.page_name_symbols_categorized
            KeyboardPagePresets.NUMERIC_AUTOMATIC_ID -> R.string.page_name_numeric_automatic
            KeyboardPagePresets.NUMERIC_KEYPAD_ID -> R.string.page_name_numeric_keypad
            KeyboardPagePresets.ENGLISH_QWERTY_ID -> R.string.page_name_english_qwerty
            else -> null
        }
    return if (resource != null && KeyboardPagePresets.preset(page.id) != null) {
        LayoutDisplayName.Worded(resource)
    } else {
        LayoutDisplayName.Authored(page.name)
    }
}
