package app.songyan.ime.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.settings.state.InMemoryLayoutLibrarySource
import app.songyan.ime.settings.ui.LayoutLibraryViewModel
import app.songyan.ime.settings.ui.screens.LayoutsScreen
import app.songyan.ime.settings.ui.screens.PagesScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The nine-key layout is edited and copied like every other layout; the built-in categorized page is copied with its categories. */
class LayoutsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val source = InMemoryLayoutLibrarySource(LayoutRegistry.create())
    private var edited: String? = null

    private fun factory() =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras,
            ): T = LayoutLibraryViewModel(source, newId = { "copy-id" }) as T
        }

    @Test
    fun theNineKeyLayoutOffersEditAndCopy() {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                LayoutsScreen(
                    onBack = null,
                    onEdit = { edited = it },
                    onOpenPages = {},
                    viewModel = viewModel(factory = factory()),
                )
            }
        }
        compose
            .onNodeWithContentDescription(
                str(R.string.layouts_more_actions, BuiltinLayouts.phone9.name),
            ).performClick()
        compose.onNodeWithText(str(R.string.layouts_action_edit)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.layouts_action_copy)).performClick()
        compose.waitForIdle()
        val copy = source.registry.value!!.layout("copy-id")
        assertNotNull(copy)
        assertEquals(BuiltinLayouts.phone9.rows, copy!!.rows)
        assertEquals(BuiltinLayouts.phone9.name + str(R.string.layout_copy_suffix), copy.name)
    }

    @Test
    fun theSwitchKeyRowIsOffAndTurnsOnWithATap() {
        var shown by mutableStateOf(false)
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                LayoutsScreen(
                    onBack = null,
                    onEdit = {},
                    onOpenPages = {},
                    showSwitchKey = shown,
                    onShowSwitchKeyChange = { shown = it },
                    viewModel = viewModel(factory = factory()),
                )
            }
        }
        compose.onNodeWithText(str(R.string.layouts_switch_key_title)).performScrollTo().assertIsOff()
        compose.onNodeWithText(str(R.string.layouts_switch_key_title)).performClick()
        compose.waitForIdle()
        assertTrue(shown)
        compose.onNodeWithText(str(R.string.layouts_switch_key_title)).assertIsOn()
    }

    @Test
    fun tappingTheNineKeyLayoutOpensTheEditor() {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                LayoutsScreen(
                    onBack = null,
                    onEdit = { edited = it },
                    onOpenPages = {},
                    viewModel = viewModel(factory = factory()),
                )
            }
        }
        compose.onNodeWithText(BuiltinLayouts.phone9.name).performClick()
        assertEquals("phone9", edited)
    }

    @Test
    fun aCopyOfTheBuiltInCategorizedPageCarriesTheCategoriesTheKeyboardShows() {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                PagesScreen(onBack = null, onEdit = {}, viewModel = viewModel(factory = factory()))
            }
        }
        val name = KeyboardPagePresets.symbolsCategorized.name
        compose.onNodeWithContentDescription(str(R.string.layouts_more_actions, name)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.layouts_action_copy)).performClick()
        compose.waitForIdle()
        val copy = source.registry.value!!.page("copy-id")
        assertNotNull(copy)
        assertTrue(copy!!.categories!!.any { it.id == "common" })
        assertTrue(copy.categories!!.any { it.source != null && it.id == "recent" })
        assertNull(
            source.registry.value!!
                .page(KeyboardPagePresets.SYMBOLS_CATEGORIZED_ID)
                ?.categories,
        )
    }

    @Test
    fun aPageAnotherKeyboardUsesSaysWhichAndIsNotDeleted() {
        val used = KeyboardPagePresets.numericRowsPage(ascii = false).duplicated("used-digits", "九键用的数字")
        val free = KeyboardPagePresets.numericRowsPage(ascii = false).duplicated("free-digits", "没人用的数字")
        val pairing = KeyboardCompositionKey(BuiltinTemplateSchemas.FLYPY, LayoutInput.Builtin.PHONE9).rawValue
        val library =
            InMemoryLayoutLibrarySource(
                LayoutRegistry
                    .create()
                    .withPage(used)
                    .withPage(free)
                    .withComposition(KeyboardComposition(mainLayoutID = "phone9", numericPageID = used.id), pairing),
            )
        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras,
                ): T = LayoutLibraryViewModel(library) as T
            }
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                PagesScreen(onBack = null, onEdit = {}, viewModel = viewModel(factory = factory))
            }
        }
        val keyboard = str(R.string.keyboard_title, "内置小鹤双拼", str(R.string.layout_name_9))
        val usage = str(R.string.pages_used_by_one, keyboard)
        // Built-in pages the pairing falls back to say who uses them too, so look at this page's row.
        compose
            .onNode(hasText(used.name, substring = true) and hasText(usage, substring = true))
            .performScrollTo()
            .assertIsDisplayed()

        compose
            .onNodeWithContentDescription(
                str(R.string.layouts_more_actions, used.name),
            ).performScrollTo()
            .performClick()
        compose.onNodeWithText(str(R.string.layouts_action_delete)).performClick()
        compose.onNodeWithText(str(R.string.pages_delete_in_use_body, used.name, usage)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.action_ok)).performClick()
        compose.waitForIdle()
        assertNotNull(library.registry.value!!.page(used.id))

        // A page no keyboard uses is deleted as before.
        compose
            .onNodeWithContentDescription(
                str(R.string.layouts_more_actions, free.name),
            ).performScrollTo()
            .performClick()
        compose.onNodeWithText(str(R.string.layouts_action_delete)).performClick()
        compose.onNodeWithText(str(R.string.pages_delete_body, free.name)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.layouts_action_delete)).performClick()
        compose.waitForIdle()
        assertNull(library.registry.value!!.page(free.id))
    }
}
