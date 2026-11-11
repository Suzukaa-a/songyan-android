package app.songyan.ime.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.autofill.inline.UiVersions
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The copied-text chip and the inline autofill strip in the idle toolbar position, against the
 * shipped engine and the real system clipboard. Real inline suggestions need an autofill service,
 * so the strip is fed synthetic views here; the request building is checked separately.
 */
@RunWith(AndroidJUnit4::class)
class CopiedChipDeviceTest {
    private fun host(block: (FeatureHost) -> Unit) {
        val host = FeatureHost(engine.backend())
        try {
            host.chips = true
            host.allowClipboardReads()
            // The clipboard outlives a test: a clip copied by the one before must not be on offer.
            host.main { host.clearClipboard() }
            block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    private fun FeatureHost.copy(
        text: String,
        sensitive: Boolean = false,
    ) {
        val clip = ClipData.newPlainText("test", text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        main { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip) }
    }

    private fun FeatureHost.chipLabel(text: String) = string(R.string.ime_chip_copied_paste).replace("%1\$s", text)

    private fun FeatureHost.hasChip(): Boolean = toolbarLabels().any { it == string(R.string.ime_chip_dismiss) }

    private fun FeatureHost.showAgain() {
        main {
            controller.onWindowHidden()
            controller.onWindowShown()
        }
        awaitIdle()
    }

    @Test
    fun textCopiedBeforeTheKeyboardShowsIsOfferedAndPastes() =
        host { host ->
            host.copy("pasted by chip")
            host.start()
            assertTrue(
                "chip: ${host.toolbarLabels()}",
                host.await {
                    host.chipLabel("pasted by chip") in
                        host.toolbarLabels()
                },
            )
            host.tapToolbar(host.chipLabel("pasted by chip"))
            host.assertText("pasted by chip")
            assertFalse("the used chip is gone", host.await { host.hasChip() })
            assertEquals(ToolbarView.DEFAULT_ACTIONS.size, host.toolbarLabels().size)
            host.showAgain()
            assertFalse("a used clip is not offered again", host.hasChip())
        }

    @Test
    fun textCopiedWhileTheKeyboardIsShownIsOffered() =
        host { host ->
            host.start()
            assertFalse(host.hasChip())
            host.copy("copied later")
            assertTrue(host.await { host.chipLabel("copied later") in host.toolbarLabels() })
        }

    @Test
    fun dismissingTheChipRestoresTheToolbarAndTheClipIsNotOfferedAgain() =
        host { host ->
            host.copy("dismiss me")
            host.start()
            assertTrue(host.await { host.hasChip() })
            host.tapToolbar(host.string(R.string.ime_chip_dismiss))
            assertTrue(host.await { !host.hasChip() })
            assertEquals(ToolbarView.DEFAULT_ACTIONS.size, host.toolbarLabels().size)
            host.showAgain()
            assertFalse(host.hasChip())
            assertEquals("", host.text())
        }

    @Test
    fun aSensitiveClipProducesNoChip() =
        host { host ->
            host.copy("secret", sensitive = true)
            host.start()
            Thread.sleep(QUIET_MS)
            assertFalse(host.toolbarLabels().toString(), host.hasChip())
            // A sensitive copy replaces an offered chip: the older text is not left on offer.
            host.copy("plain")
            assertTrue(host.await { host.hasChip() })
            host.copy("secret two", sensitive = true)
            assertTrue(host.await { !host.hasChip() })
        }

    @Test
    fun aPasswordFieldAndAnIncognitoFieldGetNoChip() {
        for (
        (type, options) in
        listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD to 0,
            InputType.TYPE_CLASS_TEXT to EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
        ) {
            host { host ->
                host.copy("private field text")
                host.start(inputType = type, imeOptions = options)
                Thread.sleep(QUIET_MS)
                assertFalse("type=$type options=$options ${host.toolbarLabels()}", host.hasChip())
                host.copy("copied inside it")
                Thread.sleep(QUIET_MS)
                assertFalse(host.hasChip())
            }
        }
    }

    @Test
    fun theChipExpires() =
        host { host ->
            host.chipTtlMs = EXPIRY_MS
            host.copy("short lived")
            host.start()
            assertTrue(host.await { host.hasChip() })
            assertTrue("the chip went after its lifetime", host.await { !host.hasChip() })
            host.showAgain()
            assertFalse(host.hasChip())
        }

    @Test
    fun aClipOlderThanTheLifetimeIsNotOfferedWhenTheKeyboardShows() =
        host { host ->
            host.chipTtlMs = EXPIRY_MS
            host.copy("stale")
            Thread.sleep(EXPIRY_MS + QUIET_MS)
            host.start()
            Thread.sleep(QUIET_MS)
            assertFalse(host.hasChip())
        }

    @Test
    fun aCodeMessageOffersJustTheCode() =
        host { host ->
            host.copy("Your verification code is 482915. It expires in 10 minutes.")
            host.start()
            val label = host.string(R.string.ime_chip_code_paste).replace("%1\$s", "482915")
            assertTrue(host.toolbarLabels().toString(), host.await { label in host.toolbarLabels() })
            host.tapToolbar(label)
            host.assertText("482915")
        }

    @Test
    fun theChipGivesWayWhileComposingAndTheLongTextIsReadInFull() =
        host { host ->
            val long = "a long sentence that cannot fit the chip " + "very long ".repeat(20) + "end"
            host.copy(long)
            host.start()
            assertTrue(host.await { host.chipLabel(long) in host.toolbarLabels() })
            host.type("n")
            assertTrue(
                host.await {
                    host.toolbarLabels().isEmpty() &&
                        host.main2 { host.controller.candidates.visibility == android.view.View.VISIBLE }
                },
            )
        }

    // ---- inline autofill strip --------------------------------------------------------------

    @Test
    fun inlineSuggestionsReplaceTheToolbarAndTheChipAndGoWhenComposingStarts() =
        host { host ->
            host.copy("chip text")
            host.start()
            assertTrue(host.await { host.hasChip() })
            host.main {
                host.controller.inlineSuggestions.show(listOf(TextView(host.context).apply { text = "saved login" }))
            }
            assertTrue(host.await { host.findOrNullOnMain(InlineSuggestionStrip::class.java) != null })
            assertTrue("the strip takes the toolbar's place", host.toolbarLabels().isEmpty())
            host.type("n")
            assertTrue(host.await { host.findOrNullOnMain(InlineSuggestionStrip::class.java) == null })
            assertFalse(host.main2 { host.controller.inlineSuggestions.hasSuggestions })
        }

    @Test
    fun inlineSuggestionsThatNeverGetASizeFallBackToTheToolbar() =
        host { host ->
            host.start()
            host.main {
                val unsized =
                    object : android.view.View(host.context) {
                        override fun onMeasure(
                            widthMeasureSpec: Int,
                            heightMeasureSpec: Int,
                        ) = setMeasuredDimension(0, 0)
                    }
                host.controller.inlineSuggestions.show(listOf(unsized))
                host.controller.inlineSuggestions.show(emptyList())
            }
            assertTrue("an empty response leaves the toolbar", host.await { host.toolbarLabels().isNotEmpty() })
            host.main {
                val unsized =
                    object : android.view.View(host.context) {
                        override fun onMeasure(
                            widthMeasureSpec: Int,
                            heightMeasureSpec: Int,
                        ) = setMeasuredDimension(0, 0)
                    }
                host.controller.inlineSuggestions.show(listOf(unsized))
            }
            assertTrue(
                "chips that never get a size give the band back",
                host.await { host.toolbarLabels().isNotEmpty() },
            )
            assertFalse(host.main2 { host.controller.inlineSuggestions.hasSuggestions })
        }

    @Test
    fun inlineSuggestionsGoWhenTheFieldChanges() =
        host { host ->
            host.start()
            host.main { host.controller.inlineSuggestions.show(listOf(TextView(host.context).apply { text = "x" })) }
            assertTrue(host.main2 { host.controller.inlineSuggestions.hasSuggestions })
            host.main { host.controller.startInput(EditorPolicy.from(EditorInfo()), false) }
            assertFalse(host.main2 { host.controller.inlineSuggestions.hasSuggestions })
            assertTrue(host.await { host.toolbarLabels().isNotEmpty() })
        }

    @Test
    fun theInlineRequestIsBuiltInTheBarsSizeAndStyle() {
        val context =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .targetContext
        val palette = KeyboardPalette.from(context)
        val request = InlineAutofill.buildRequest(context, palette, 1080)
        assertEquals(InlineAutofill.MAX_SUGGESTIONS, request.maxSuggestionCount)
        val spec = request.inlinePresentationSpecs.single()
        val boxPx =
            (
                BarContentBox.of(palette.tokens.candidateBarHeightDp, null).heightDp *
                    context.resources.displayMetrics.density
            ).toInt()
        assertEquals(boxPx, spec.maxSize.height)
        assertEquals(boxPx, spec.minSize.height)
        assertEquals(1080, spec.maxSize.width)
        assertTrue(spec.minSize.width in 1..1080)
        assertTrue(
            "the style declares the v1 inline UI",
            UiVersions.INLINE_UI_VERSION_1 in UiVersions.getVersions(spec.style),
        )
        assertNotNull(spec.style)
    }

    private fun <V : android.view.View> FeatureHost.findOrNullOnMain(type: Class<V>): V? = main2 { findOrNull(type) }

    companion object {
        private const val QUIET_MS = 600L
        private const val EXPIRY_MS = 1_500L
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("chips", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
