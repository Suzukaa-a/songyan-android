package app.songyan.ime.settings.ui

import androidx.lifecycle.SavedStateHandle
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutPageJump
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.LayoutSplitConfiguration
import app.songyan.ime.settings.state.InMemoryLayoutLibrarySource
import app.songyan.ime.settings.state.LayoutEditResult
import app.songyan.ime.settings.state.LayoutLibrarySource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EditorDraftsTest {
    private class DelayedSource(
        val backing: InMemoryLayoutLibrarySource = InMemoryLayoutLibrarySource(),
    ) : LayoutLibrarySource by backing {
        val gate = CompletableDeferred<Unit>()

        override suspend fun edit(change: (LayoutRegistry) -> LayoutRegistry): LayoutEditResult {
            gate.await()
            return backing.edit(change)
        }
    }

    @Test
    fun layoutSaveKeepsLaterEditsAndIgnoresDuplicateSaveRequests() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val source = DelayedSource()
                val model = LayoutEditorViewModel(source, BuiltinLayouts.qwerty26.id)
                runCurrent()
                model.update { it.rename("submitted") }
                model.save()
                assertTrue(model.saving.value)
                model.saveAsCopy("duplicate")
                model.update { it.rename("later") }
                source.gate.complete(Unit)
                advanceUntilIdle()
                assertFalse(model.saving.value)
                assertEquals(
                    "submitted",
                    source.registry.value!!
                        .layout(BuiltinLayouts.qwerty26.id)!!
                        .name,
                )
                val draft = model.draft.value!!
                assertEquals("submitted", draft.original.name)
                assertEquals("later", draft.layout.name)
                assertTrue(model.draft.value!!.hasChanges)
                assertFalse(
                    source.registry.value!!
                        .layouts
                        .any { it.name == "duplicate" },
                )
                model.save()
                advanceUntilIdle()
                assertFalse(model.draft.value!!.hasChanges)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun pageCopyKeepsLaterEditsAndSubsequentSaveTargetsTheCopy() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val source = DelayedSource()
                val id = KeyboardPagePresets.numericAutomatic.id
                val model = PageEditorViewModel(source, id, newId = { "copy" })
                runCurrent()
                model.saveAsCopy("saved copy")
                model.saveAsCopy("duplicate")
                model.update { it.rename("later") }
                source.gate.complete(Unit)
                advanceUntilIdle()
                assertEquals(
                    "saved copy",
                    source.registry.value!!
                        .page("copy")!!
                        .name,
                )
                val draft = model.draft.value!!
                assertEquals("copy", draft.page.id)
                assertEquals("later", draft.page.name)
                assertTrue(model.draft.value!!.hasChanges)
                model.save()
                advanceUntilIdle()
                assertEquals(
                    "later",
                    source.registry.value!!
                        .page("copy")!!
                        .name,
                )
                assertEquals(
                    KeyboardPagePresets.numericAutomatic.name,
                    source.registry.value!!
                        .page(id)!!
                        .name,
                )
                assertFalse(model.draft.value!!.hasChanges)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun layoutSnapshotRestoresInvalidIntermediateTextSplitAndSelection() {
        val draft =
            LayoutDraft(BuiltinLayouts.qwerty26).rename("").copy(
                selection = KeySelection(1, 2),
                splitView = true,
            )
        val bytes =
            ByteArrayOutputStream()
                .also { buffer ->
                    DataOutputStream(buffer).use { it.writeLayoutDraft(draft) }
                }.toByteArray()
        val restored = DataInputStream(ByteArrayInputStream(bytes)).use { it.readLayoutDraft() }
        assertEquals(draft, restored)
        assertTrue(restored.hasChanges)
    }

    @Test
    fun privateDiskSnapshotRecoversLargePageAndDiscardRemovesIt() =
        runTest {
            val directory = Files.createTempDirectory("page-draft").toFile()
            try {
                val state = SavedStateHandle()
                val page = KeyboardPagePresets.numericAutomatic
                val draft = PageDraft(page).rename("草稿".repeat(40_000)).copy(categoryIndex = 2, categoryKey = 3)
                val first = EditorDraftStore(directory, state)
                first.persist { it.writePageDraft(draft) }
                first.close().join()
                val restoredState = SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) })
                val restored = EditorDraftStore(directory, restoredState)
                assertEquals(draft, restored.restore { it.readPageDraft() })
                restored.clear()
                restored.close().join()
                val oldState = EditorDraftStore(directory, state)
                assertNull(oldState.restore { it.readPageDraft() })
                oldState.close().join()
            } finally {
                directory.deleteRecursively()
            }
        }

    private fun key(label: String) = LayoutKey(label, action = LayoutAction.literal(label))

    private fun labels(rows: List<LayoutRow>) = rows.map { row -> row.keys.joinToString("") { it.label } }

    @Test
    fun theBottomRowCanBeReplacedWithoutChangingTheLetterRows() {
        for (layout in listOf(
            BuiltinLayouts.qwerty26,
            BuiltinLayouts.merged14,
            BuiltinLayouts.merged18,
            BuiltinLayouts.phone9,
        )) {
            val draft = LayoutDraft(layout)
            val footer = listOf(key("常用"), LayoutKey("空格", action = LayoutAction(LayoutActionKind.SPACE)))
            val edited = draft.withBottomKeys(footer)
            assertTrue(edited.hasChanges)
            assertEquals(footer, edited.bottomKeys)
            assertEquals(draft.letterRows, edited.letterRows)
            assertEquals(
                footer,
                edited.layout.rows
                    .last()
                    .keys,
            )
        }
    }

    @Test
    fun aPageFooterIsAuthoredDataAndCanBeReorderedOrCleared() {
        val page = KeyboardPagePresets.numericAutomatic.duplicated("mine", "我的")
        val draft = PageDraft(page)
        val keys = draft.bottomKeys.reversed() + key("常用")
        val edited = draft.withBottomKeys(keys)
        assertEquals(keys, edited.page.chrome!!.bottom)
        assertEquals(page.rows, edited.page.rows)
        assertTrue(edited.hasChanges)
        assertEquals(
            emptyList<LayoutKey>(),
            edited
                .withBottomKeys(emptyList())
                .page.chrome!!
                .bottom,
        )
    }

    @Test
    fun aLayoutDraftEditsTheLetterRowsAndKeepsTheControlRow() {
        val draft = LayoutDraft(BuiltinLayouts.qwerty26)
        assertEquals(3, draft.letterRows.size)
        assertEquals(KeySelection(0, 0), draft.selection)
        assertEquals("q", draft.selectedKey!!.label)
        val edited = draft.replaceSelectedKey(draft.selectedKey!!.copy(label = "Q1"))
        assertTrue(edited.hasChanges)
        assertEquals(
            "Q1",
            edited.layout.rows[0]
                .keys[0]
                .label,
        )
        // The control row is still the last row and untouched.
        assertEquals(BuiltinLayouts.qwerty26.rows.last(), edited.layout.rows.last())
        assertEquals(4, edited.layout.rows.size)
    }

    @Test
    fun aStructuralEditMovesTheSelectionAndKeepsTheSplitInsideItsRows() {
        val split = LayoutSplitConfiguration.of(rowBreaks = listOf(5, 5, 4, 3))
        val draft = LayoutDraft(BuiltinLayouts.qwerty26.withSplit(split))
        val shorter = draft.apply(KeyGridEditing.removeKey(draft.letterRows, KeySelection(2, 0)))
        // Seven letters plus shift/delete were nine keys; the cut of the row stays inside it.
        val row =
            shorter.layout.rows[2]
                .keys.size
        assertTrue(shorter.layout.split!!.rowBreaks[2] in 1 until row)
        assertEquals(draft.layout.rows.size, shorter.layout.rows.size)
    }

    @Test
    fun anEditThatCannotFinishLeavesTheDraftAlone() {
        val draft = LayoutDraft(BuiltinLayouts.qwerty26)
        assertEquals(draft, draft.apply(null))
        assertFalse(draft.apply(null).hasChanges)
    }

    @Test
    fun selectingAKeyThatIsNotThereClearsTheSelection() {
        val draft = LayoutDraft(BuiltinLayouts.qwerty26)
        assertNull(draft.select(KeySelection(9, 9)).selection)
        assertEquals(KeySelection(1, 2), draft.select(KeySelection(1, 2)).selection)
    }

    @Test
    fun renamingChangesOnlyTheName() {
        val draft = LayoutDraft(BuiltinLayouts.merged14).rename("我的 14 键")
        assertEquals("我的 14 键", draft.layout.name)
        assertEquals(BuiltinLayouts.merged14.rows, draft.layout.rows)
    }

    @Test
    fun aPageDraftEditsItsRowsAndKeepsItsFooter() {
        val page = KeyboardPagePresets.numericAutomatic.duplicated("mine", "我的")
        val draft = PageDraft(page)
        val edited = draft.replaceSelectedKey(draft.selectedKey!!.copy(label = "壹"))
        assertEquals(
            "壹",
            edited.page.rows[0]
                .keys[0]
                .label,
        )
        assertEquals(page.chrome, edited.page.chrome)
        assertEquals(page.name, edited.page.name)
    }

    @Test
    fun theSplitOfADraftIsClampedToItsRows() {
        val page = KeyboardPagePresets.numericAutomatic.duplicated("mine", "我的")
        val draft = PageDraft(page).withSplit(LayoutSplitConfiguration.of(rowBreaks = listOf(50, 50, 50)))
        val rows = draft.page.rows
        for ((index, cut) in draft.page.split!!
            .rowBreaks
            .withIndex()) {
            assertTrue(cut in 1 until rows[index].keys.size)
        }
    }

    // ---- the grid edits ----

    @Test
    fun aNewKeyLandsAfterTheSelectedOneAndIsSelected() {
        val rows = listOf(LayoutRow(listOf(key("a"), key("b"))))
        val edit = KeyGridEditing.addKeyAfter(rows, KeySelection(0, 0), key("x"))!!
        assertEquals(listOf("axb"), labels(edit.rows))
        assertEquals(KeySelection(0, 1), edit.selection)
    }

    @Test
    fun removingTheLastKeyOfARowSelectsTheRowBefore() {
        val rows = listOf(LayoutRow(listOf(key("a"), key("b"))), LayoutRow(listOf(key("c"))))
        val edit = KeyGridEditing.removeKey(rows, KeySelection(1, 0))!!
        assertEquals(listOf("ab"), labels(edit.rows))
        assertEquals(KeySelection(0, 0), edit.selection)
    }

    @Test
    fun removingAKeyKeepsTheSelectionInsideTheRow() {
        val rows = listOf(LayoutRow(listOf(key("a"), key("b"), key("c"))))
        assertEquals(KeySelection(0, 1), KeyGridEditing.removeKey(rows, KeySelection(0, 2))!!.selection)
        assertEquals(KeySelection(0, 0), KeyGridEditing.removeKey(rows, KeySelection(0, 0))!!.selection)
    }

    @Test
    fun movingAKeyFollowsItWithTheSelection() {
        val rows = listOf(LayoutRow(listOf(key("a"), key("b"), key("c"))))
        val edit = KeyGridEditing.moveKey(rows, KeySelection(0, 0), +1)!!
        assertEquals(listOf("bac"), labels(edit.rows))
        assertEquals(KeySelection(0, 1), edit.selection)
        assertNull(KeyGridEditing.moveKey(rows, KeySelection(0, 0), -1))
    }

    @Test
    fun anAddedRowIsSelectedAndAMovedRowTakesTheSelectionAlong() {
        val rows = listOf(LayoutRow(listOf(key("a"))), LayoutRow(listOf(key("b"))))
        val added = KeyGridEditing.addRow(rows, key("x"))!!
        assertEquals(KeySelection(2, 0), added.selection)
        val moved = KeyGridEditing.moveRow(rows, KeySelection(0, 0), +1)!!
        assertEquals(listOf("b", "a"), labels(moved.rows))
        assertEquals(KeySelection(1, 0), moved.selection)
    }

    // ---- the action choices ----

    @Test
    fun changingTheActionKindCarriesOnlyWhatMakesSense() {
        val letter = LayoutKey("q", action = LayoutAction.code("q"))
        assertEquals(LayoutAction.literal("q"), KeyActionChoice.LITERAL.actionFor(letter))
        assertEquals(LayoutAction.code("q"), KeyActionChoice.CODE.actionFor(letter))
        assertEquals(LayoutAction.pageSwitch(LayoutPageTarget.NUMERIC), KeyActionChoice.PAGE_SWITCH.actionFor(letter))
        assertEquals(LayoutAction(LayoutActionKind.SHIFT), KeyActionChoice.SHIFT.actionFor(letter))
        assertEquals("nextLayout", KeyActionChoice.MODE_SWITCH.actionFor(letter).value)
    }

    @Test
    fun pagesNeverOfferTheEngineCodeAction() {
        assertTrue(KeyActionChoice.allowed(allowCode = true).contains(KeyActionChoice.CODE))
        assertFalse(KeyActionChoice.allowed(allowCode = false).contains(KeyActionChoice.CODE))
        assertEquals(KeyActionChoice.SPACE, KeyActionChoice.of(LayoutAction(LayoutActionKind.SPACE)))
    }

    @Test
    fun theEditorOffersTheInputMethodSwitchKey() {
        assertEquals(KeyActionChoice.GLOBE, KeyActionChoice.of(LayoutAction(LayoutActionKind.GLOBE)))
        assertTrue(KeyActionChoice.allowed(allowCode = false).contains(KeyActionChoice.GLOBE))
        val letter = LayoutKey("q", action = LayoutAction.code("q"))
        assertEquals(LayoutAction(LayoutActionKind.GLOBE), KeyActionChoice.GLOBE.actionFor(letter))
    }

    // ---- jump keys ----

    private val pageLabel: (LayoutPageTarget) -> String = {
        when (it) {
            LayoutPageTarget.NUMERIC -> "123"
            LayoutPageTarget.SYMBOLS -> "符号"
            LayoutPageTarget.LETTERS -> "字母"
        }
    }

    @Test
    fun onlyPagesOfferTheJumpKey() {
        assertTrue(KeyActionChoice.allowed(allowCode = false, allowJump = true).contains(KeyActionChoice.JUMP))
        assertFalse(KeyActionChoice.allowed(allowCode = true).contains(KeyActionChoice.JUMP))
        assertFalse(KeyActionChoice.allowed(allowCode = false).contains(KeyActionChoice.JUMP))
        val jump = LayoutAction.layoutPageSwitch(LayoutPageJump(LayoutInput.Builtin.PHONE9, LayoutPageTarget.NUMERIC))
        assertEquals(KeyActionChoice.JUMP, KeyActionChoice.of(jump))
        // A key that already jumps keeps its target; any other key starts at the first target listed.
        assertEquals(jump, KeyActionChoice.JUMP.actionFor(LayoutKey("x", action = jump)))
        assertEquals(LayoutPageJump.all.first(), KeyActionChoice.JUMP.actionFor(key("x")).layoutPageJump)
    }

    @Test
    fun aJumpKeyIsNamedAfterItsPageUntilTheUserNamesIt() {
        val toNineKeyDigits = LayoutPageJump(LayoutInput.Builtin.PHONE9, LayoutPageTarget.NUMERIC)
        val toNineKeySymbols = LayoutPageJump(LayoutInput.Builtin.PHONE9, LayoutPageTarget.SYMBOLS)
        val fresh = JumpKeyEditing.withTarget(key("·").copy(label = ""), toNineKeyDigits, pageLabel)
        assertEquals("123", fresh.label)
        assertEquals(toNineKeyDigits, fresh.action.layoutPageJump)
        // The generated name follows the target's page.
        assertEquals("符号", JumpKeyEditing.withTarget(fresh, toNineKeySymbols, pageLabel).label)
        // A name the user wrote stays.
        val named = fresh.copy(label = "九键数字")
        val retargeted = JumpKeyEditing.withTarget(named, toNineKeySymbols, pageLabel)
        assertEquals("九键数字", retargeted.label)
        assertEquals(toNineKeySymbols, retargeted.action.layoutPageJump)
    }
}
