package app.songyan.ime.rime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device replay of the synced traditional-Chinese playback fixture, over the real engine and the
 * real pinned scheme packages.
 *
 * The fixture (`engine-fixtures/traditional/playback_cases.json`, synced from the iOS repository) is
 * the single source of truth. Every case names the input method, the keys a keyboard sends and the
 * evidence the deployed data must produce: an exact preedit, a candidate rank and, when the case
 * names one, the committed text. The expectations and the replay order follow the iOS macOS driver,
 * so a mismatch here is a real difference in what the shipped engine does, never a stub's answer.
 *
 * The fixture archives are not in the repository: push them into this test package's cache first
 * (see [TraditionalSchemeFixtures]). A missing fixture fails the test; it never turns green without
 * replaying.
 */
@RunWith(AndroidJUnit4::class)
class TraditionalReplayDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun everyFixtureCaseNamesATestedEntryAndAnExpectation() {
        val cases = loadCases()
        assertTrue("the traditional playback fixture has no cases", cases.isNotEmpty())
        for (case in cases) {
            assertTrue(
                "case ${case.label} names a schema no traditional entry advertises",
                TraditionalSchemeFixtures.entryForSchema(case.schema) != null,
            )
        }
        val schemas = cases.map { it.schema }.toSet()
        for (entry in TraditionalSchemeFixtures.entries) {
            assertTrue(
                "no fixture case replays ${entry.id} (${entry.primarySchema})",
                entry.schemas.any { it in schemas },
            )
        }
    }

    @Test
    fun bopomofoPlaybackMeetsItsExpectations() = replay(TraditionalSchemeFixtures.BOPOMOFO)

    @Test
    fun cangjiePlaybackMeetsItsExpectations() = replay(TraditionalSchemeFixtures.CANGJIE)

    @Test
    fun quickPlaybackMeetsItsExpectations() = replay(TraditionalSchemeFixtures.QUICK)

    @Test
    fun cantonesePlaybackMeetsItsExpectations() = replay(TraditionalSchemeFixtures.CANTONESE)

    /** Merges, deploys and replays every case of [entry] on one real engine, then reports all mismatches. */
    private fun replay(entry: TraditionalSchemeFixtures.Entry) =
        runBlocking {
            TraditionalSchemeFixtures.requireProvisioned(entry)
            val root =
                File(instrumentation.targetContext.cacheDir, "traditional-replay-${entry.id}").also {
                    it.deleteRecursively()
                }
            try {
                val deployed = TraditionalSchemeFixtures.deploy(entry, root)
                val user = File(root, "user").also { it.mkdirs() }
                val engine =
                    RimeEngine.createPrebuilt(
                        sharedDataDir = deployed.source,
                        userDataDir = user,
                        appName = RimeEngine.DEFAULT_APP_NAME,
                        appVersion = "test",
                        compiledDir = deployed.compiled,
                    )
                try {
                    val cases = loadCases().filter { it.schema in entry.schemas }
                    assertTrue("no fixture case replays ${entry.id}", cases.isNotEmpty())
                    val problems = StringBuilder()
                    for (case in cases) replayCase(engine, case, problems)
                    assertEquals(
                        "traditional playback did not meet its expectations:\n$problems",
                        "",
                        problems.toString(),
                    )
                } finally {
                    engine.close()
                }
            } finally {
                root.deleteRecursively()
            }
        }

    /**
     * Replays one case the way the iOS driver does: select the schema, feed every key (each must be
     * consumed), assert the expectations, then either the explicit native confirm or the candidate
     * selection the case names. Mismatches are collected so one run reports all of them.
     */
    private suspend fun replayCase(
        engine: RimeEngine,
        case: Case,
        problems: StringBuilder,
    ) {
        fun problem(message: String) {
            problems
                .append(case.label)
                .append(": ")
                .append(message)
                .append('\n')
        }
        if (!engine.selectSchema(case.schema)) {
            problem("schema is not selectable")
            return
        }
        engine.clearComposition()
        for (key in case.keys) {
            val code = RimeKey.baseKeycode(key)
            if (code == null) {
                problem("'$key' is not an engine key")
                engine.clearComposition()
                return
            }
            if (!engine.processKey(RimeKey(code)).consumed) {
                problem("key '$key' was not consumed")
                engine.clearComposition()
                return
            }
        }
        val snapshot = engine.readSnapshot()
        val mismatches = StringBuilder()
        if (case.expectPreedit != null && snapshot.preedit != case.expectPreedit) {
            mismatches.append("preedit '${snapshot.preedit}' is not '${case.expectPreedit}'\n")
        }
        if (case.expectFirst != null && snapshot.candidates.firstOrNull()?.text != case.expectFirst) {
            mismatches.append("first candidate ${candidatesOf(snapshot)} is not '${case.expectFirst}'\n")
        }
        if (case.expectTop3 != null && !within(snapshot, 3, case.expectTop3)) {
            mismatches.append("'${case.expectTop3}' is not within the first 3 ${candidatesOf(snapshot)}\n")
        }
        if (case.expectTop5 != null && !within(snapshot, 5, case.expectTop5)) {
            mismatches.append("'${case.expectTop5}' is not within the first 5 ${candidatesOf(snapshot)}\n")
        }
        if (mismatches.isNotEmpty()) {
            problems.append(case.label).append(": ").append(mismatches)
            engine.clearComposition()
            return
        }
        when (case.commitAction) {
            CONFIRM ->
                if (snapshot.commitText.isNotEmpty()) {
                    // The keys already committed, so the explicit confirm the case claims is not the
                    // evidence this replay produced.
                    problem("keys committed '${snapshot.commitText}' before the confirm action")
                } else {
                    // The explicit native commit, not Space: Bopomofo's Space first selects a tone or
                    // confirms only one segment, so the fixture names the confirm action it means.
                    val confirmed = engine.commitComposition()
                    val committed = confirmed.snapshot.commitText
                    if (!confirmed.consumed) {
                        problem("the native confirm action was refused")
                    } else if (committed != case.expectText) {
                        problem("confirm committed '$committed' is not '${case.expectText}'")
                    }
                }

            SELECT -> selectAndCompare(engine, case, snapshot, problems)
        }
        engine.clearComposition()
    }

    /** Pages until the expected candidate is on the current page, selects it and compares the commit. */
    private suspend fun selectAndCompare(
        engine: RimeEngine,
        case: Case,
        firstSnapshot: RimeSnapshot,
        problems: StringBuilder,
    ) {
        fun problem(message: String) {
            problems
                .append(case.label)
                .append(": ")
                .append(message)
                .append('\n')
        }
        val expected = requireNotNull(case.expectText)
        if (firstSnapshot.commitText.isNotEmpty()) {
            problem("keys committed '${firstSnapshot.commitText}' before the candidate could be selected")
            return
        }
        var snapshot = firstSnapshot
        var pages = 0
        while (true) {
            val index = snapshot.candidates.indexOfFirst { it.text == expected }
            if (index >= 0) {
                val selected = engine.selectCandidate(index)
                if (selected == null) {
                    problem("selecting '$expected' was refused")
                } else if (selected.commitText != expected) {
                    problem("selecting '$expected' committed '${selected.commitText}'")
                }
                return
            }
            if (snapshot.isLastPage || pages >= MAX_PAGES) break
            val next = engine.pageForward()
            if (next == null) {
                problem("page forward was refused after ${pages + 1} page(s)")
                return
            }
            snapshot = next
            pages++
        }
        problem("'$expected' is not in the menu after ${pages + 1} page(s)")
    }

    private fun within(
        snapshot: RimeSnapshot,
        rank: Int,
        text: String,
    ): Boolean = snapshot.candidates.take(rank).any { it.text == text }

    private fun candidatesOf(snapshot: RimeSnapshot): String {
        val shown = snapshot.candidates.map { it.text }.take(RECORDED_CANDIDATES)
        val suffix = if (snapshot.candidates.size > RECORDED_CANDIDATES) "..." else ""
        return shown.joinToString(prefix = "[", postfix = "]") { it } + suffix
    }

    private fun loadCases(): List<Case> {
        val text =
            instrumentation.context.assets
                .open(FIXTURE_ASSET)
                .bufferedReader()
                .use { it.readText() }
        val root = Json.parseToJsonElement(text).jsonArray
        check(root.isNotEmpty()) { "${FIXTURE_ASSET} has no cases" }
        return root.mapIndexed { index, element -> parseCase(index + 1, element) }
    }

    private fun parseCase(
        position: Int,
        element: JsonElement,
    ): Case {
        val where = "fixture case $position"
        val fields = runCatching { element.jsonObject }.getOrElse { fail("$where: expected a JSON object") }
        val schema = requiredString(fields, "schema", where)
        val layout = requiredString(fields, "layout", where)
        val keys = requiredString(fields, "keys", where)
        check(keys.all { it.code in 0x20..0x7E }) { "$where: keys must be printable ASCII" }
        val action = optionalString(fields, "commit_action", where)
        check(action == null || action == SELECT || action == CONFIRM) {
            "$where: commit_action must be select or confirm"
        }
        val expectText = optionalString(fields, "expect_text", where)
        check(action == null || expectText != null) { "$where: commit_action needs expect_text" }
        val expectFirst = optionalString(fields, "expect_first", where)
        val expectTop3 = optionalString(fields, "expect_top3", where)
        val expectTop5 = optionalString(fields, "expect_top5", where)
        val expectPreedit = optionalString(fields, "expect_preedit", where)
        check(
            expectText != null ||
                expectFirst != null ||
                expectTop3 != null ||
                expectTop5 != null ||
                expectPreedit != null,
        ) { "$where: the case carries no expectation" }
        return Case(
            schema = schema,
            layout = layout,
            keys = keys,
            expectText = expectText,
            expectFirst = expectFirst,
            expectTop3 = expectTop3,
            expectTop5 = expectTop5,
            expectPreedit = expectPreedit,
            // A case that names committed text but not how to reach it selects the candidate.
            commitAction = action ?: expectText?.let { SELECT },
        )
    }

    private fun requiredString(
        fields: JsonObject,
        key: String,
        where: String,
    ): String = optionalString(fields, key, where) ?: fail("$where: missing field $key")

    /** A non-empty string when the field is present; null when absent. Tabs and newlines are refused. */
    private fun optionalString(
        fields: JsonObject,
        key: String,
        where: String,
    ): String? {
        val value = fields[key] ?: return null
        val text = runCatching { value.jsonPrimitive.content }.getOrNull()
        if (text.isNullOrEmpty()) fail("$where: $key must be a non-empty string")
        if (text.any { it == '\t' || it == '\n' || it == '\r' }) {
            fail("$where: $key must not contain a tab or newline")
        }
        return text
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    /** One playback case, with the optional evidence columns the traditional acceptance adds. */
    private data class Case(
        val schema: String,
        val layout: String,
        val keys: String,
        val expectText: String?,
        val expectFirst: String?,
        val expectTop3: String?,
        val expectTop5: String?,
        val expectPreedit: String?,
        val commitAction: String?,
    ) {
        val label: String get() = "$schema/$layout '$keys'"
    }

    private companion object {
        const val FIXTURE_ASSET = "engine-fixtures/traditional/playback_cases.json"
        const val SELECT = "select"
        const val CONFIRM = "confirm"
        const val MAX_PAGES = 40
        const val RECORDED_CANDIDATES = 12
    }
}
