package app.songyan.ime.rime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * On-device replay of the Japanese playback fixture over the real, pinned engine and a locally
 * compiled kagiroi package.
 *
 * The fixture (`engine-fixtures/japanese/playback_cases.json`, synced from the iOS repository) is
 * the single source of truth. Every case names the input method, the keys a keyboard sends and the
 * evidence the real engine must produce: an exact preedit, a candidate rank, the text the keys
 * themselves commit, or the text selecting a named candidate commits. Keys are printable characters
 * or librime key names in braces (`{Return}`, `{space}`, `{Page_Down}`), the same notation the iOS
 * macOS driver uses, so a fixture cannot name a key only this test understands.
 *
 * The fixture ZIP is not in the repository: push the locally compiled package into this test
 * package's cache and pass `-e kagiroiFixture <absolute path>`. A missing fixture file, a missing
 * ZIP or a key the engine does not consume fails; nothing skips into a green result without
 * replaying the real data.
 */
@RunWith(AndroidJUnit4::class)
class JapaneseReplayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun everyFixtureCaseNamesAKagiroiSchemaAndAnExpectation() {
        val cases = loadCases()
        assertTrue("the Japanese playback fixture has no cases", cases.isNotEmpty())
        for (case in cases) {
            assertTrue(
                "case ${case.label} names '${case.schema}', not a kagiroi input method",
                case.schema.startsWith(KAGIROI_PREFIX),
            )
            assertTrue("case ${case.label} names no layout", case.layout.isNotEmpty())
            assertTrue("case ${case.label} carries no expectation", case.hasExpectation)
            assertTrue("case ${case.label} types no key", case.strokes.isNotEmpty())
        }
    }

    @Test
    fun everyFixtureCaseMeetsItsExpectations() =
        runBlocking {
            val cases = loadCases()
            assertTrue("the Japanese playback fixture has no cases", cases.isNotEmpty())
            val prepared = JapaneseSchemeFixture.prepare()
            try {
                val engine = JapaneseSchemeFixture.openEngine(prepared, prepared.userDir("replay-user"))
                try {
                    replayAll(engine, cases)
                } finally {
                    engine.close()
                }
            } finally {
                JapaneseSchemeFixture.cleanup(prepared)
            }
        }

    /** Replays every case on one engine and reports all mismatches, like the iOS driver. */
    private suspend fun replayAll(
        engine: RimeEngine,
        cases: List<Case>,
    ) {
        val failures = StringBuilder()
        var passed = 0
        for ((index, case) in cases.withIndex()) {
            val reason = replayCase(engine, case)
            if (reason == null) {
                passed++
                println("PASS\t${index + 1}\t${case.label}\tas expected")
            } else {
                failures
                    .append(case.label)
                    .append(": ")
                    .append(reason)
                    .append('\n')
                println("FAIL\t${index + 1}\t${case.label}\t$reason")
            }
        }
        println("SUMMARY\tcases=${cases.size}\tpassed=$passed\tfailed=${cases.size - passed}")
        assertEquals(
            "Japanese playback did not meet its expectations:\n$failures",
            "",
            failures.toString(),
        )
    }

    /** One case: select, apply the case's switches, feed every key and check the evidence it names. */
    private suspend fun replayCase(
        engine: RimeEngine,
        case: Case,
    ): String? =
        try {
            replaySteps(engine, case)
        } finally {
            engine.clearComposition()
        }

    private suspend fun replaySteps(
        engine: RimeEngine,
        case: Case,
    ): String? {
        if (!engine.selectSchema(case.schema)) {
            return "schema '${case.schema}' is not selectable; the package offers " +
                engine.schemas().map { it.id }
        }
        engine.clearComposition()
        for ((name, value) in case.options) {
            if (!engine.setOption(name, value)) return "option '$name' could not be set"
        }
        // A requested ON switch the live schema does not resolve must fail closed instead of
        // replaying the state the previous case left behind.
        for ((name, value) in case.options) {
            if (value && !engine.option(name)) return "option '$name' did not take effect"
        }
        val committed = StringBuilder()
        for (stroke in case.strokes) {
            val result = engine.processKey(stroke.key)
            committed.append(result.snapshot.commitText)
            if (!result.consumed) return "key '${stroke.repr}' was not consumed by the engine"
        }
        val state = engine.readSnapshot()
        val problems = StringBuilder()
        if (case.expectPreedit != null && state.preedit != case.expectPreedit) {
            problems
                .append("preedit '${state.preedit}' is not '${case.expectPreedit}' (candidates ")
                .append(candidatesOf(state))
                .append(")\n")
        }
        if (case.expectTop3 != null && state.candidates.take(3).none { it.text == case.expectTop3 }) {
            problems
                .append("'${case.expectTop3}' is not within the first 3 (candidates ")
                .append(candidatesOf(state))
                .append(")\n")
        }
        if (case.expectTop5 != null && state.candidates.take(5).none { it.text == case.expectTop5 }) {
            problems
                .append("'${case.expectTop5}' is not within the first 5 (candidates ")
                .append(candidatesOf(state))
                .append(")\n")
        }
        if (problems.isNotEmpty()) return problems.toString().trimEnd()

        if (case.expectCommit != null) {
            // The keys themselves committed, so no candidate may be required to still exist here.
            val actual = committed.toString()
            return if (actual == case.expectCommit) {
                null
            } else {
                "keys committed '$actual', expected '${case.expectCommit}'"
            }
        }
        if (case.action == Action.SELECT && committed.isNotEmpty()) {
            return "keys committed '$committed' before '${case.expectText}' could be selected"
        }
        return when (case.action) {
            Action.CONFIRM -> confirm(engine, case)
            Action.SELECT -> select(engine, case, state)
            Action.NONE -> null
        }
    }

    /** The explicit native confirm action, not Space: a schema's Space binding may select a segment. */
    private suspend fun confirm(
        engine: RimeEngine,
        case: Case,
    ): String? {
        val expected = requireNotNull(case.expectText)
        val confirmed = engine.commitComposition()
        return when {
            !confirmed.consumed -> "the native confirm action was refused"
            confirmed.snapshot.commitText != expected ->
                "confirm committed '${confirmed.snapshot.commitText}', expected '$expected'"
            else -> null
        }
    }

    /** Pages until the named candidate is on the page, selects it and compares the commit. */
    private suspend fun select(
        engine: RimeEngine,
        case: Case,
        first: RimeSnapshot,
    ): String? {
        val expected = requireNotNull(case.expectText)
        var snapshot = first
        var pages = 0
        while (true) {
            val index = snapshot.candidates.indexOfFirst { it.text == expected }
            if (index >= 0) {
                val selected = engine.selectCandidate(index) ?: return "selecting '$expected' was refused"
                return if (selected.commitText == expected) {
                    null
                } else {
                    "selecting '$expected' committed '${selected.commitText}'"
                }
            }
            if (snapshot.isLastPage || pages >= MAX_PAGES) {
                return "'$expected' is not in the menu after ${pages + 1} page(s) " +
                    "(candidates ${candidatesOf(snapshot)})"
            }
            snapshot = engine.pageForward() ?: return "page forward was refused after ${pages + 1} page(s)"
            pages++
        }
    }

    private fun candidatesOf(snapshot: RimeSnapshot): String {
        val shown = snapshot.candidates.map { it.text }.take(RECORDED_CANDIDATES)
        val suffix = if (snapshot.candidates.size > RECORDED_CANDIDATES) "..." else ""
        return shown.joinToString(prefix = "[", postfix = "]") { it } + suffix
    }

    private fun loadCases(): List<Case> {
        val text =
            try {
                instrumentation.context.assets
                    .open(FIXTURE_ASSET)
                    .bufferedReader()
                    .use { it.readText() }
            } catch (_: IOException) {
                throw AssertionError(
                    "the Japanese playback fixture is missing from this test package: $FIXTURE_ASSET. " +
                        "Sync Tests/EngineFixtures/Japanese/playback_cases.json from the iOS repository " +
                        "before running this test; it fails rather than skipping without replaying",
                )
            }
        val root = Json.parseToJsonElement(text).jsonArray
        check(root.isNotEmpty()) { "$FIXTURE_ASSET has no cases" }
        return root.mapIndexed { index, element -> parseCase(index, element) }
    }

    private fun parseCase(
        index: Int,
        element: JsonElement,
    ): Case {
        val where = "fixture case ${index + 1}"
        val fields = runCatching { element.jsonObject }.getOrElse { fail("$where: expected a JSON object") }
        val schema = requiredString(fields, "schema", where)
        val layout = requiredString(fields, "layout", where)
        val keys = requiredString(fields, "keys", where)
        val expectCommit = optionalString(fields, "expect_commit", where)
        val expectText = optionalString(fields, "expect_text", where)
        val expectPreedit = optionalString(fields, "expect_preedit", where)
        val expectTop3 = optionalString(fields, "expect_top3", where)
        val expectTop5 = optionalString(fields, "expect_top5", where)
        val actionName = optionalString(fields, "commit_action", where)
        val options = parseOptions(fields["options"], where)
        if (expectCommit != null) {
            val others = listOfNotNull(expectText, expectPreedit, expectTop3, expectTop5, actionName)
            if (others.isNotEmpty()) {
                fail("$where: expect_commit cannot be combined with candidate or preedit expectations")
            }
        }
        if (listOfNotNull(expectCommit, expectText, expectPreedit, expectTop3, expectTop5).isEmpty()) {
            fail("$where: the case carries no expectation")
        }
        if (actionName != null && actionName != SELECT && actionName != CONFIRM) {
            fail("$where: commit_action must be select or confirm")
        }
        if (actionName != null && expectText == null) fail("$where: commit_action needs expect_text")
        val action =
            when {
                actionName != null -> if (actionName == SELECT) Action.SELECT else Action.CONFIRM
                expectText != null -> Action.SELECT
                else -> Action.NONE
            }
        return Case(
            schema = schema,
            layout = layout,
            keys = keys,
            strokes = parseStrokes(keys, where),
            options = options,
            expectCommit = expectCommit,
            expectText = expectText,
            expectPreedit = expectPreedit,
            expectTop3 = expectTop3,
            expectTop5 = expectTop5,
            action = action,
        )
    }

    private fun parseOptions(
        element: JsonElement?,
        where: String,
    ): Map<String, Boolean> {
        if (element == null) return emptyMap()
        val fields =
            runCatching { element.jsonObject }.getOrElse { fail("$where: options must be a JSON object") }
        if (fields.isEmpty()) fail("$where: options must not be empty")
        val values = LinkedHashMap<String, Boolean>()
        for ((name, value) in fields) {
            if (!OPTION_NAME.matches(name)) fail("$where: option name '$name' is not setOption-safe")
            val enabled =
                runCatching { value.jsonPrimitive.boolean }
                    .getOrElse { fail("$where: option '$name' must be a JSON boolean") }
            values[name] = enabled
        }
        return values
    }

    /** Printable characters are their own keycode; `{Name}` is one of librime's named keys. */
    private fun parseStrokes(
        keys: String,
        where: String,
    ): List<Stroke> {
        val strokes = ArrayList<Stroke>()
        var index = 0
        while (index < keys.length) {
            val ch = keys[index]
            when {
                ch == '{' -> {
                    val end = keys.indexOf('}', index + 1)
                    if (end < 0) fail("$where: keys has an unclosed '{'")
                    val name = keys.substring(index + 1, end)
                    if (name.isEmpty()) fail("$where: keys has an empty { } key name")
                    val key = NAMED_KEYS[name] ?: fail("$where: unknown named key '{$name}'")
                    strokes += Stroke(key, "{$name}")
                    index = end + 1
                }
                ch == '}' -> fail("$where: keys has a '}' without '{'")
                ch.code in 0x20..0x7E -> {
                    // The fixture names the keycode a layout sends, so printable ASCII passes
                    // through as itself instead of being lowercased first.
                    strokes += Stroke(RimeKey(ch.code), ch.toString())
                    index++
                }
                else -> fail("$where: keys must be printable ASCII; named keys are {Name}")
            }
        }
        if (strokes.isEmpty()) fail("$where: keys is empty")
        return strokes
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

    private class Stroke(
        val key: RimeKey,
        val repr: String,
    )

    private enum class Action { NONE, SELECT, CONFIRM }

    /** One playback case, with the optional evidence columns the Japanese acceptance adds. */
    private data class Case(
        val schema: String,
        val layout: String,
        val keys: String,
        val strokes: List<Stroke>,
        val options: Map<String, Boolean>,
        val expectCommit: String?,
        val expectText: String?,
        val expectPreedit: String?,
        val expectTop3: String?,
        val expectTop5: String?,
        val action: Action,
    ) {
        val label: String get() = "$schema/$layout '$keys'"

        val hasExpectation: Boolean
            get() = listOfNotNull(expectCommit, expectText, expectPreedit, expectTop3, expectTop5).isNotEmpty()
    }

    private companion object {
        const val FIXTURE_ASSET = "engine-fixtures/japanese/playback_cases.json"
        const val KAGIROI_PREFIX = "kagiroi"
        const val SELECT = "select"
        const val CONFIRM = "confirm"
        const val MAX_PAGES = 40
        const val RECORDED_CANDIDATES = 12

        val OPTION_NAME = Regex("^[A-Za-z0-9_]+$")

        /** Named keys a keyboard sends, written `{Name}` as librime spells them. */
        val NAMED_KEYS =
            mapOf(
                "Return" to RimeKey.RETURN,
                "space" to RimeKey.SPACE,
                "BackSpace" to RimeKey.BACKSPACE,
                "Escape" to RimeKey.ESCAPE,
                "Tab" to RimeKey.TAB,
                "Page_Down" to RimeKey(RimeKeyCode.PAGE_DOWN),
                "Page_Up" to RimeKey(RimeKeyCode.PAGE_UP),
                "Home" to RimeKey(RimeKeyCode.HOME),
                "End" to RimeKey(RimeKeyCode.END),
                "Left" to RimeKey(RimeKeyCode.LEFT),
                "Right" to RimeKey(RimeKeyCode.RIGHT),
                "Up" to RimeKey(RimeKeyCode.UP),
                "Down" to RimeKey(RimeKeyCode.DOWN),
                "Delete" to RimeKey(RimeKeyCode.DELETE),
            )
    }
}
