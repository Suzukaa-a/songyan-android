package app.songyan.ime.settings.state

import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.engine.SchemaKeyProfile
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.UserKeyboards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardListTest {
    private val all = KeyboardLayoutKind.entries.map { it.wire }.toSet()
    private val schemes = listOf(InstalledScheme("rime_ice", "冰雪", all), InstalledScheme("wubi86", "五笔"))
    private val registry = LayoutRegistry.create()

    private fun list(
        stored: UserKeyboards.Stored = UserKeyboards.Stored(),
        hiddenLayouts: Set<String> = emptySet(),
        hiddenSchemas: Set<String> = emptySet(),
        registry: LayoutRegistry = this.registry,
    ) = keyboardList(schemes, "rime_ice", "qwerty26", null, stored, hiddenSchemas, hiddenLayouts, registry)

    @Test
    fun aUserWhoNeverEditedSeesEveryShapeOfEachInputMethod() {
        val list = list()
        assertEquals(listOf("rime_ice", "wubi86"), list.groups.map { it.schemeId })
        assertEquals(KeyboardLayoutKind.entries.toList(), list.groups[0].keyboards.map { it.keyboard.layout })
        assertEquals(listOf(KeyboardLayoutKind.QWERTY26), list.groups[1].keyboards.map { it.keyboard.layout })
        assertTrue(
            list.groups[0]
                .keyboards
                .single { it.inUse }
                .keyboard.layout == KeyboardLayoutKind.QWERTY26,
        )
    }

    @Test
    fun hiddenShapesAndInputMethodsStayOutButTheOneInUseStays() {
        val list = list(hiddenLayouts = setOf("merged14"), hiddenSchemas = setOf("wubi86"))
        assertEquals(
            listOf(KeyboardLayoutKind.QWERTY26, KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9),
            list.groups[0].keyboards.map { it.keyboard.layout },
        )
        assertTrue(list.groups[1].keyboards.isEmpty())
    }

    @Test
    fun aStoredListReplacesTheDerivedOneAndOffersWhatIsLeft() {
        val stored =
            UserKeyboards.Stored(
                listOf(PublishedKeyboard("rime_ice", KeyboardLayoutKind.PHONE9, "phone9")),
                setOf("rime_ice"),
            )
        val list = list(stored)
        assertEquals(
            listOf(KeyboardLayoutKind.PHONE9, KeyboardLayoutKind.QWERTY26),
            list.groups[0].keyboards.map {
                it.keyboard.layout
            },
        )
        assertTrue(list.groups[0].addable.any { it.keyboard.layout == KeyboardLayoutKind.MERGED14 })
        assertTrue(list.groups[0].addable.none { it.keyboard.layout == KeyboardLayoutKind.PHONE9 })
    }

    @Test
    fun theAddDialogListsKeyboardsAlreadyAddedAsAdded() {
        val stored =
            UserKeyboards.Stored(
                listOf(PublishedKeyboard("rime_ice", KeyboardLayoutKind.PHONE9, "phone9")),
                setOf("rime_ice"),
            )
        val offered = list(stored).groups[0].offered
        assertTrue(offered.single { it.keyboard.layout == KeyboardLayoutKind.PHONE9 }.added)
        assertTrue(offered.single { it.keyboard.layout == KeyboardLayoutKind.QWERTY26 }.added)
        assertTrue(offered.any { it.keyboard.layout == KeyboardLayoutKind.MERGED14 && !it.added })
        assertEquals(offered.filterNot { it.added }, list(stored).groups[0].addable)
    }

    @Test
    fun aLayoutTheUserCopiedCanBeAddedAsItsOwnKeyboard() {
        val copy = BuiltinLayouts.qwerty26.copyWithId("copy", "副本")
        val list = list(registry = registry.install(copy.encoded()))
        assertTrue(
            list.groups[0].addable.any {
                it.keyboard.board == "copy" &&
                    it.keyboard.layout == KeyboardLayoutKind.QWERTY26
            },
        )
    }

    @Test
    fun aKeyboardOnAFoldNotBuiltYetIsPreparingAndARefusedOneIsUnavailable() {
        val ice =
            InstalledScheme(
                "quanpin",
                "全拼",
                setOf("qwerty26"),
                profile = SchemaKeyProfile(SchemaKeyProfile.Kind.SYLLABIC, listOf("a")),
                unavailableLayouts = setOf("phone9"),
            )

        fun k(id: String) = requireNotNull(PublishedKeyboard.parse(id))
        val stored =
            UserKeyboards.Stored(
                listOf(k("quanpin:qwerty26#qwerty26"), k("quanpin:merged14#merged14"), k("quanpin:phone9#phone9")),
                setOf("quanpin"),
            )
        val list = keyboardList(listOf(ice), "quanpin", "qwerty26", null, stored, emptySet(), emptySet(), registry)
        val rows = list.groups.single().keyboards
        assertEquals(
            listOf(KeyboardStatus.READY, KeyboardStatus.PREPARING, KeyboardStatus.UNAVAILABLE),
            rows.map { it.status },
        )
        // What can still be added leaves out the refused fold and what is listed.
        assertEquals(
            listOf(KeyboardLayoutKind.MERGED18),
            list.groups
                .single()
                .addable
                .map { it.keyboard.layout },
        )
    }
}
