package app.songyan.ime.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Glyph forms of one candidate under the running scheme's input language.
 *
 * The scheme switch is real: the shipped engine runs a Japanese fixture schema and the built-in
 * full pinyin scheme, so the cached input language comes from the production resolver. The
 * candidate list itself is pinned to one word so both languages draw exactly the same text in
 * the same cell; that pinned word is a rendering fixture and is not passed off as engine output.
 */
@RunWith(AndroidJUnit4::class)
class CandidateGlyphLocaleDeviceTest {
    @Test
    fun theSamePinnedCandidateRendersDifferentlyInJapaneseAndSimplifiedChineseAndStaysStable() {
        withHost { host ->
            host.start()
            switchTo(host, "kagiroi_test")
            val japanese = drawPinnedCandidate(host, CANDIDATE)
            assertTrue("pinned candidate in the bar: " + barTexts(host), CANDIDATE in barTexts(host))
            switchTo(host, "songyan_quanpin")
            val simplified = drawPinnedCandidate(host, CANDIDATE)
            val simplifiedAgain = drawPinnedCandidate(host, CANDIDATE)
            assertTrue(
                "the same language and text must render the same pixels",
                simplified.sameAs(simplifiedAgain),
            )
            assertFalse(
                "the Japanese and simplified Chinese glyph forms of " + CANDIDATE + " differ",
                japanese.sameAs(simplified),
            )
        }
    }

    @Test
    fun theKeyPaintFollowsTheRunningSchemeLanguage() {
        withHost { host ->
            host.start()
            switchTo(host, "kagiroi_test")
            assertEquals("ja", keyPaintLocales(host))
            switchTo(host, "songyan_quanpin")
            assertEquals("zh-Hans", keyPaintLocales(host))
        }
    }

    /** Draws [text] as the bar's only candidate through the production view, after a real composition starts. */
    private fun drawPinnedCandidate(
        host: FeatureHost,
        text: String,
    ): Bitmap {
        // A real composition first: the bar is only visible while the engine is composing.
        host.type("ka")
        host.awaitIdle()
        lateinit var bitmap: Bitmap
        host.main {
            val bar = host.find(CandidateBarView::class.java)
            // Page size 0: this row never pages, so no read-only page load can replace the pinned text.
            bar.update(
                EngineSnapshot(
                    schemaID = "glyph-locale-fixture",
                    rawInput = "zhi",
                    isComposing = true,
                    candidates = listOf(Candidate(0, text)),
                    pageSize = 0,
                    isLastPage = true,
                ),
            )
            host.relayout()
            bitmap = Bitmap.createBitmap(bar.width, bar.height, Bitmap.Config.ARGB_8888)
            bar.draw(Canvas(bitmap))
        }
        return bitmap
    }

    private fun barTexts(host: FeatureHost): List<String> =
        host.main2 {
            val bar = host.find(CandidateBarView::class.java)
            bar.entries.map { it.text }
        }

    private fun keyPaintLocales(host: FeatureHost): String =
        host.main2 {
            val keyboard = host.find(KeyboardView::class.java)
            val locales = keyboard.paint.textLocales
            locales.toLanguageTags()
        }

    private fun switchTo(
        host: FeatureHost,
        schemaID: String,
    ) {
        host.main { runBlocking { host.controller.session?.selectSchema(schemaID) } }
        host.awaitIdle()
    }

    private fun withHost(block: (FeatureHost) -> Unit) {
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            block(host)
        } finally {
            host.close()
            runBlocking { host.backend.shutdown() }
        }
    }

    private companion object {
        const val CANDIDATE = "直"

        lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deployTheFixtureSchemes() {
            engine = DeployedEngine("glyph-locale", english = false, patch = ::addInputLanguageFixtureSchemes)
        }

        @AfterClass
        @JvmStatic
        fun removeTheFixtureSchemes() {
            engine.delete()
        }
    }
}
