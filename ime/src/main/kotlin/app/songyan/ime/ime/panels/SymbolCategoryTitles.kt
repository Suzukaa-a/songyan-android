package app.songyan.ime.ime.panels

import app.songyan.ime.ime.R
import app.songyan.ime.layout.SymbolPageCategories

/**
 * Titles of the built-in symbol library categories and of the built-in categorized page.
 *
 * The bundled catalog names its categories in Chinese and the built-in page is generated in code,
 * so both are built-in data: their titles are display strings and follow the app language. A
 * user-imported catalog, and a page copied from the built-in one and then renamed, carry the
 * author's text and are never rewritten — even when they reuse a built-in id.
 *
 * [lookup] resolves a resource id; injecting it keeps the decision testable without Android.
 */
object SymbolCategoryTitles {
    /** Resource of a built-in catalog category, or null when [id] is not one. */
    internal fun catalogKey(id: String): Int? =
        when (id) {
            "math" -> R.string.ime_symbol_category_math
            "numbers" -> R.string.ime_symbol_category_numbers
            "arrows" -> R.string.ime_symbol_category_arrows
            "units" -> R.string.ime_symbol_category_units
            "currency" -> R.string.ime_symbol_category_currency
            "shapes" -> R.string.ime_symbol_category_shapes
            "letters" -> R.string.ime_symbol_category_letters
            "special" -> R.string.ime_symbol_category_special
            "kaomoji" -> R.string.ime_symbol_category_kaomoji
            "punctuation" -> R.string.ime_symbol_category_punctuation
            else -> null
        }

    /** Resource of a built-in page tab, or null when [id] is not one. */
    internal fun pageKey(id: String): Int? =
        when (id) {
            SymbolPageCategories.COMMON_ID -> R.string.ime_symbol_page_common
            SymbolPageCategories.RECENT_ID -> R.string.ime_symbol_page_recent
            // The width-based tabs of SymbolPageCategories.display(); they have no public constant.
            "chinese" -> R.string.ime_symbol_page_chinese
            "english" -> R.string.ime_symbol_page_english
            "brackets" -> R.string.ime_symbol_page_brackets
            else -> null
        }

    /**
     * The title of catalog category [id]: the localized built-in title for a built-in catalog, or
     * the author's [authorName] for a custom catalog and for ids this build does not know.
     */
    fun catalogTitle(
        authorName: String,
        id: String,
        builtinCatalog: Boolean,
        lookup: (Int) -> String,
    ): String = if (builtinCatalog) catalogKey(id)?.let(lookup) ?: authorName else authorName

    /**
     * The title of page tab [id]: the localized built-in title for a page generated from built-in
     * data, or the authored [authorName] for a page a user saved and possibly renamed.
     */
    fun pageTitle(
        authorName: String,
        id: String,
        builtinPage: Boolean,
        lookup: (Int) -> String,
    ): String = if (builtinPage) pageKey(id)?.let(lookup) ?: authorName else authorName
}
