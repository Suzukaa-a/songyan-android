package app.songyan.ime.content

import app.songyan.ime.keyboard.engine.InputLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Repeat-last-commit and the five date/time formats, with a fixed clock and zone. */
class QuickInsertCoreTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val instant: Instant = Instant.parse("2026-09-09T14:30:00Z")

    private fun core(
        preferences: QuickInsertFormatPreferences = InMemoryQuickInsertFormatPreferences(),
        now: () -> Instant = { instant },
        confirm: () -> Boolean = { true },
        insert: (String) -> Boolean = { true },
        onChange: () -> Unit = {},
        inputLanguage: () -> InputLanguage = { InputLanguage.ZH_HANS },
    ): QuickInsertCore =
        QuickInsertCore(
            preferences = preferences,
            now = now,
            zone = zone,
            confirmComposition = confirm,
            insert = insert,
            onChange = onChange,
            inputLanguage = inputLanguage,
        )

    @Test
    fun formatsAtFixedInstant() {
        val core = core()
        assertEquals("2026-09-09", core.formattedText(QuickInsertDateFormat.DATE))
        assertEquals("2026年9月9日", core.formattedText(QuickInsertDateFormat.DATE_CHINESE))
        assertEquals("14:30", core.formattedText(QuickInsertDateFormat.TIME))
        assertEquals("2026-09-09 14:30", core.formattedText(QuickInsertDateFormat.DATE_TIME))
        assertEquals("2026年9月9日 14:30", core.formattedText(QuickInsertDateFormat.DATE_TIME_CHINESE))
    }

    @Test
    fun japaneseFormatsTheChineseDateWithItsWeekday() {
        val core = core(now = { Instant.parse("2026-11-05T14:30:00Z") }, inputLanguage = { InputLanguage.JA })
        assertEquals("2026-11-05", core.formattedText(QuickInsertDateFormat.DATE))
        assertEquals("2026年11月5日(木)", core.formattedText(QuickInsertDateFormat.DATE_CHINESE))
        assertEquals("14:30", core.formattedText(QuickInsertDateFormat.TIME))
        assertEquals("2026-11-05 14:30", core.formattedText(QuickInsertDateFormat.DATE_TIME))
        assertEquals("2026年11月5日(木) 14:30", core.formattedText(QuickInsertDateFormat.DATE_TIME_CHINESE))
    }

    @Test
    fun traditionalChineseKeepsTheSimplifiedDateShapes() {
        val hant = core(inputLanguage = { InputLanguage.ZH_HANT })
        assertEquals("2026-09-09", hant.formattedText(QuickInsertDateFormat.DATE))
        assertEquals("2026年9月9日", hant.formattedText(QuickInsertDateFormat.DATE_CHINESE))
        assertEquals("14:30", hant.formattedText(QuickInsertDateFormat.TIME))
        assertEquals("2026-09-09 14:30", hant.formattedText(QuickInsertDateFormat.DATE_TIME))
        assertEquals("2026年9月9日 14:30", hant.formattedText(QuickInsertDateFormat.DATE_TIME_CHINESE))
    }

    @Test
    fun theLanguageIsReadWhenTheDateIsInserted() {
        var language = InputLanguage.ZH_HANS
        val inserted = ArrayList<String>()
        val core =
            core(
                inputLanguage = { language },
                insert = {
                    inserted.add(it)
                    true
                },
            )
        assertTrue(core.insertDateFormat(QuickInsertDateFormat.DATE_CHINESE))
        language = InputLanguage.JA
        assertTrue(core.insertDateFormat(QuickInsertDateFormat.DATE_CHINESE))
        assertEquals(listOf("2026年9月9日", "2026年9月9日(水)"), inserted)
    }

    @Test
    fun formatIdsMatchIosRawValues() {
        assertEquals(
            listOf("date", "date-chinese", "time", "date-time", "date-time-chinese"),
            QuickInsertDateFormat.entries.map { it.id },
        )
        assertEquals(
            QuickInsertDateFormat.DATE_TIME,
            QuickInsertDateFormat.fromId("date-time"),
        )
        assertNull(QuickInsertDateFormat.fromId("unknown"))
    }

    @Test
    fun insertDateFormatInsertsAndRemembers() {
        val inserted = ArrayList<String>()
        val preferences = InMemoryQuickInsertFormatPreferences()
        val core =
            core(preferences = preferences, insert = {
                inserted.add(it)
                true
            })
        assertTrue(core.insertDateFormat(QuickInsertDateFormat.DATE_CHINESE))
        assertEquals(listOf("2026年9月9日"), inserted)
        assertEquals(QuickInsertDateFormat.DATE_CHINESE, core.selectedFormat)
        assertEquals(QuickInsertDateFormat.DATE_CHINESE, preferences.read())
        assertEquals("2026年9月9日", core.lastCommittedText)
    }

    @Test
    fun insertDateFormatDoesNotRememberOnRefusal() {
        val preferences = InMemoryQuickInsertFormatPreferences()
        val core = core(preferences = preferences, insert = { false })
        assertFalse(core.insertDateFormat(QuickInsertDateFormat.TIME))
        assertEquals(QuickInsertDateFormat.DATE, core.selectedFormat)
        assertNull(preferences.read())
        assertNull(core.lastCommittedText)
    }

    @Test
    fun initialFormatComesFromPreferences() {
        val core = core(preferences = InMemoryQuickInsertFormatPreferences(QuickInsertDateFormat.TIME))
        assertEquals(QuickInsertDateFormat.TIME, core.selectedFormat)
    }

    @Test
    fun repeatConfirmsFirstThenInsertsCapturedTarget() {
        val order = ArrayList<String>()
        val core =
            core(
                confirm = {
                    order.add("confirm")
                    true
                },
                insert = {
                    order.add("insert:" + it)
                    true
                },
            )
        core.recordCommitted("你好")
        assertTrue(core.performRepeat())
        assertEquals(listOf("confirm", "insert:你好"), order)
        assertEquals("你好", core.lastCommittedText)
    }

    @Test
    fun repeatIsBlockedByConfirmRefusalOrMissingTarget() {
        val core = core(confirm = { false })
        assertFalse(core.performRepeat())
        core.recordCommitted("你好")
        assertFalse(core.performRepeat())
        assertEquals("你好", core.lastCommittedText)
    }

    @Test
    fun blankCommitsAreIgnoredAndResetClearsTheTarget() {
        val core = core()
        core.recordCommitted("保持")
        core.recordCommitted(" \n ")
        assertEquals("保持", core.lastCommittedText)
        assertTrue(core.hasRepeatableText)
        core.resetContext()
        assertFalse(core.hasRepeatableText)
        assertNull(core.repeatPreview)
        assertNull(core.lastCommittedText)
    }

    @Test
    fun nowIsEvaluatedPerTapSoMidnightIsCurrent() {
        var current = Instant.parse("2026-09-09T23:59:00Z")
        val core = core(now = { current })
        assertEquals("2026-09-09", core.formattedText(QuickInsertDateFormat.DATE))
        current = Instant.parse("2026-09-10T00:00:00Z")
        assertEquals("2026-09-10", core.formattedText(QuickInsertDateFormat.DATE))
    }

    @Test
    fun selectFormatPersistsWithoutInserting() {
        val inserted = ArrayList<String>()
        val preferences = InMemoryQuickInsertFormatPreferences()
        val core =
            core(preferences = preferences, insert = {
                inserted.add(it)
                true
            })
        core.selectFormat(QuickInsertDateFormat.TIME)
        assertEquals(QuickInsertDateFormat.TIME, core.selectedFormat)
        assertEquals(QuickInsertDateFormat.TIME, preferences.read())
        assertTrue(inserted.isEmpty())
    }

    @Test
    fun previewCollapsesAndTruncates() {
        assertEquals("你好 世界", QuickInsertRecentText.preview("你好\n\n世界"))
        assertEquals("a b", QuickInsertRecentText.preview("  a   b  "))
        assertEquals("一".repeat(23) + "\u2026", QuickInsertRecentText.preview("一".repeat(30)))
        assertEquals("😀😀\u2026", QuickInsertRecentText.preview("😀".repeat(30), maxCharacters = 3))
        assertNull(QuickInsertRecentText.preview(null))
        assertNull(QuickInsertRecentText.preview("  \n "))
        assertTrue(QuickInsertRecentText.hasVisibleContent("x"))
        assertFalse(QuickInsertRecentText.hasVisibleContent(" \n "))
    }

    @Test
    fun onChangeFiresForEveryMutation() {
        var changes = 0
        val core = core(onChange = { changes += 1 })
        core.recordCommitted("a")
        core.recordCommitted(" \n ")
        core.resetContext()
        assertEquals(3, changes)
        // A successful insert notifies the delivery and then the remembered format.
        core.insertDateFormat(QuickInsertDateFormat.TIME)
        assertEquals(5, changes)
    }
}
