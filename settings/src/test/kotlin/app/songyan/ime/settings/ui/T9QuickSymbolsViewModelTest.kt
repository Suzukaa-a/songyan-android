package app.songyan.ime.settings.ui

import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.T9FirstKeyBehavior
import app.songyan.ime.settings.state.InMemoryLayoutLibrarySource
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class T9QuickSymbolsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun theRulesMatchTheLayoutFieldAndAllowEmptyForBuiltinDefaults() {
        assertNull(T9QuickSymbolsDraft.check(""))
        assertEquals(T9QuickSymbolsProblem.INVALID, T9QuickSymbolsDraft.check("，。 ？"))
        assertEquals(T9QuickSymbolsProblem.INVALID, T9QuickSymbolsDraft.check("一二三四五六七八九十甲乙丙"))
        assertEquals(T9QuickSymbolsProblem.DUPLICATE, T9QuickSymbolsDraft.check("，。，"))
        assertNull(T9QuickSymbolsDraft.check("一二三四五六七八九十甲乙"))
        assertNull(T9QuickSymbolsDraft.check("，"))
    }

    @Test fun thePreviewSplitsTheListIntoTheSideRailAndTheFirstKeyCycle() {
        val (rail, cycle) = T9QuickSymbolsDraft.preview("；：")
        assertEquals(listOf("；", "：", "，"), rail)
        assertEquals("。？！、…", cycle.take(5).joinToString(""))
    }

    @Test fun aFirstKeyEditThatLeavesTheNineKeyShapeStillSaves() =
        runTest(dispatcher) {
            val source = InMemoryLayoutLibrarySource()
            val model = LayoutEditorViewModel(source, "phone9", preferences = InMemorySettingsPreferences())
            advanceUntilIdle()
            model.update { draft ->
                val layout = draft.layout
                val first =
                    layout.rows[0].keys[0].copy(action = LayoutAction(LayoutActionKind.BACKSPACE), longPress = null)
                val rows = layout.rows.toMutableList()
                rows[0] = LayoutRow(listOf(first) + rows[0].keys.drop(1))
                draft.copy(
                    layout =
                        layout.copy(
                            rows = rows,
                            t9FirstKeyBehavior = T9FirstKeyBehavior.CUSTOM,
                            t9QuickSymbols = "；：",
                        ),
                )
            }
            model.save()
            advanceUntilIdle()
            assertEquals(SaveOutcome.Saved, model.outcome.value)
            val saved = source.registry.value!!.layout("phone9")!!
            assertNull(saved.t9FirstKeyBehavior)
            assertNull(saved.t9QuickSymbols)
            assertFalse(model.draft.value!!.hasChanges)
        }

    @Test fun quickSymbolsArePartOfTheLayoutDraftAndInvalidOnesNeverReplaceTheSavedLayout() =
        runTest(dispatcher) {
            val preferences = InMemorySettingsPreferences()
            preferences.setT9QuickSymbols("！")
            val source = InMemoryLayoutLibrarySource()
            val model = LayoutEditorViewModel(source, "phone9", preferences = preferences)
            advanceUntilIdle()
            assertEquals("！", model.inheritedQuickSymbols.value)
            model.update { it.copy(layout = it.layout.copy(t9QuickSymbols = "；：，")) }
            assertNull(
                source.registry.value!!
                    .layout("phone9")!!
                    .t9QuickSymbols,
            )
            assertTrue(model.draft.value!!.hasChanges)
            model.save()
            advanceUntilIdle()
            assertEquals(
                "；：，",
                source.registry.value!!
                    .layout("phone9")!!
                    .t9QuickSymbols,
            )
            assertEquals("！", preferences.t9QuickSymbols.first())
            assertFalse(model.draft.value!!.hasChanges)
            model.update { it.copy(layout = it.layout.copy(t9QuickSymbols = "，，")) }
            model.save()
            advanceUntilIdle()
            assertTrue(model.outcome.value is SaveOutcome.Failed)
            assertEquals(
                "；：，",
                source.registry.value!!
                    .layout("phone9")!!
                    .t9QuickSymbols,
            )
            assertTrue(model.draft.value!!.hasChanges)
        }

    @Test fun restoringDefaultsWritesOnlyTheLayoutAndDeclaringRailsFreezesItsEffectiveSymbols() =
        runTest(dispatcher) {
            val preferences = InMemorySettingsPreferences()
            preferences.setT9QuickSymbols("；：")
            val source = InMemoryLayoutLibrarySource()
            val model = LayoutEditorViewModel(source, "phone9", preferences = preferences)
            advanceUntilIdle()
            model.update { it.declareRails(model.inheritedQuickSymbols.value.orEmpty()) }
            assertEquals(
                "；",
                model.draft.value!!
                    .railKeys(app.songyan.ime.layout.LayoutAuxiliaryArea.Position.LEADING)
                    .first()
                    .label,
            )
            model.update { it.copy(layout = it.layout.copy(t9QuickSymbols = "")) }
            model.save()
            advanceUntilIdle()
            assertEquals(
                "",
                source.registry.value!!
                    .layout("phone9")!!
                    .t9QuickSymbols,
            )
            assertEquals("；：", preferences.t9QuickSymbols.first())
            assertEquals(
                BuiltinLayouts.t9QuickSymbolValues("", false),
                T9QuickSymbolsDraft.preview("").let {
                    it.first +
                        it.second
                },
            )
        }
}
