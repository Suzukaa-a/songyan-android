package app.songyan.ime.ime.panels

import app.songyan.ime.ime.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Built-in symbol category and page tab titles.
 *
 * The bundled symbol library and the built-in categorized page are built-in data, so their titles
 * are display strings and follow the app language. A user-imported catalog, and a page copied from
 * the built-in one and then renamed, carry the author's text and are never rewritten.
 */
class SymbolCategoryTitlesTest {
    private val builtinCatalogKeys =
        mapOf(
            "math" to R.string.ime_symbol_category_math,
            "numbers" to R.string.ime_symbol_category_numbers,
            "arrows" to R.string.ime_symbol_category_arrows,
            "units" to R.string.ime_symbol_category_units,
            "currency" to R.string.ime_symbol_category_currency,
            "shapes" to R.string.ime_symbol_category_shapes,
            "letters" to R.string.ime_symbol_category_letters,
            "special" to R.string.ime_symbol_category_special,
            "kaomoji" to R.string.ime_symbol_category_kaomoji,
            "punctuation" to R.string.ime_symbol_category_punctuation,
        )

    private val builtinPageKeys =
        mapOf(
            "common" to R.string.ime_symbol_page_common,
            "recent" to R.string.ime_symbol_page_recent,
            "chinese" to R.string.ime_symbol_page_chinese,
            "english" to R.string.ime_symbol_page_english,
            "brackets" to R.string.ime_symbol_page_brackets,
        )

    private fun localized(captured: (Int) -> Unit = {}): (Int) -> String =
        { key ->
            captured(key)
            "localized"
        }

    @Test
    fun everyBuiltinCatalogCategoryUsesItsOwnTitle() {
        val used = ArrayList<Int>()
        for ((id, expected) in builtinCatalogKeys) {
            val title =
                SymbolCategoryTitles.catalogTitle(
                    authorName = "作者分类",
                    id = id,
                    builtinCatalog = true,
                    lookup = localized { used += it },
                )
            assertEquals(id, "localized", title)
            assertEquals(id, listOf(expected), used)
            used.clear()
        }
        // One title per category: no two categories can share a resource.
        assertEquals(builtinCatalogKeys.size, builtinCatalogKeys.values.toSet().size)
    }

    @Test
    fun aCustomCatalogKeepsTheAuthorsNames() {
        for (id in builtinCatalogKeys.keys) {
            val title =
                SymbolCategoryTitles.catalogTitle(
                    authorName = "作者分类",
                    id = id,
                    builtinCatalog = false,
                    lookup = { error("a custom catalog must not touch resources") },
                )
            assertEquals(id, "作者分类", title)
        }
    }

    @Test
    fun anUnknownCategoryKeepsTheFallbackName() {
        val title =
            SymbolCategoryTitles.catalogTitle(
                authorName = "mine",
                id = "not-a-builtin-id",
                builtinCatalog = true,
                lookup = { error("an unknown id must not touch resources") },
            )
        assertEquals("mine", title)
    }

    @Test
    fun everyBuiltinPageUsesItsOwnTitle() {
        val used = ArrayList<Int>()
        for ((id, expected) in builtinPageKeys) {
            val title =
                SymbolCategoryTitles.pageTitle(
                    authorName = "常用",
                    id = id,
                    builtinPage = true,
                    lookup = localized { used += it },
                )
            assertEquals(id, "localized", title)
            assertEquals(id, listOf(expected), used)
            used.clear()
        }
        assertEquals(builtinPageKeys.size, builtinPageKeys.values.toSet().size)
    }

    @Test
    fun anAuthoredPageKeepsItsNameEvenWhenItReusesABuiltinId() {
        for (id in builtinPageKeys.keys) {
            val title =
                SymbolCategoryTitles.pageTitle(
                    authorName = "我的分类",
                    id = id,
                    builtinPage = false,
                    lookup = { error("an authored page must not touch resources") },
                )
            assertEquals(id, "我的分类", title)
        }
    }

    @Test
    fun aLibrarySetKeepsItsNameWhenTheCatalogIsCustom() {
        // A library set is a catalog category shown on the built-in page: the catalog decides.
        val title =
            SymbolCategoryTitles.catalogTitle(
                authorName = "作者分类",
                id = "math",
                builtinCatalog = false,
                lookup = { error("a custom catalog must not touch resources") },
            )
        assertEquals("作者分类", title)
    }
}
