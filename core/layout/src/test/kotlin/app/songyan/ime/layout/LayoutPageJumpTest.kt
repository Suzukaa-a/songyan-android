package app.songyan.ime.layout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A key that opens a non-letter page of another keyboard layout: its wire form and validation. */
class LayoutPageJumpTest {
    private val toNineKeyDigits = LayoutPageJump(LayoutInput.Builtin.PHONE9, LayoutPageTarget.NUMERIC)

    @Test
    fun theTargetIsWrittenAsLayoutColonPageUnderItsOwnKind() {
        val action = LayoutAction.layoutPageSwitch(toNineKeyDigits)
        val encoded = LayoutJson.format.encodeToString(LayoutAction.serializer(), action)
        val fields = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("layoutPageSwitch", fields.getValue("type").jsonPrimitive.content)
        assertEquals("phone9:numeric", fields.getValue("value").jsonPrimitive.content)
        assertEquals(setOf("type", "value"), fields.keys)
        assertEquals(action, LayoutJson.format.decodeFromString(LayoutAction.serializer(), encoded))
        assertEquals(toNineKeyDigits, action.layoutPageJump)
        assertNull(action.pageSwitchTarget)
    }

    @Test
    fun everyLayoutsDigitAndSymbolPagesAreTargetsAndNoLetterPageIs() {
        assertEquals(LayoutInput.Builtin.entries.size * 2, LayoutPageJump.all.size)
        for (target in LayoutPageJump.all) {
            assertEquals(target, LayoutPageJump.fromWire(target.wire))
            LayoutAction.layoutPageSwitch(target).validate()
        }
        assertTrue(LayoutPageJump.all.none { it.page == LayoutPageTarget.LETTERS })
        assertThrows(IllegalArgumentException::class.java) {
            LayoutPageJump(LayoutInput.Builtin.PHONE9, LayoutPageTarget.LETTERS)
        }
    }

    @Test
    fun anUnknownOrMalformedTargetIsRejected() {
        val bad =
            listOf(
                null,
                "",
                "numeric",
                "phone9",
                "phone9:letters",
                "phone10:numeric",
                "phone9:emoji",
                "phone9:numeric:extra",
                "PHONE9:numeric",
                " phone9:numeric",
            )
        for (value in bad) {
            assertNull(value, LayoutPageJump.fromWire(value))
            val error =
                assertThrows(LayoutException::class.java) {
                    LayoutAction(LayoutActionKind.LAYOUT_PAGE_SWITCH, value).validate()
                }
            assertEquals(
                LayoutInvalidCode.LAYOUT_PAGE_SWITCH_REQUIRES_KNOWN_TARGET,
                (error as LayoutException.Invalid).code,
            )
        }
    }

    @Test
    fun aSamePageSwitchNeverReadsAJumpTarget() {
        // An older reader of `pageSwitch` must not be handed a qualified value it would misread.
        assertThrows(LayoutException::class.java) {
            LayoutAction(LayoutActionKind.PAGE_SWITCH, "phone9:numeric").validate()
        }
        assertNull(LayoutAction.pageSwitch(LayoutPageTarget.NUMERIC).layoutPageJump)
    }

    @Test
    fun aPageWithJumpKeysInItsRowsAndRailsRoundTripsThroughTheCatalog() {
        val jump = LayoutKey("123", action = LayoutAction.layoutPageSwitch(toNineKeyDigits))
        val symbolsJump =
            LayoutKey(
                "符号",
                action =
                    LayoutAction.layoutPageSwitch(
                        LayoutPageJump(LayoutInput.Builtin.MERGED14, LayoutPageTarget.SYMBOLS),
                    ),
            )
        val base = KeyboardPagePresets.symbolsSystem.duplicated("my-symbols", "我的符号")
        val chrome = base.chrome!!
        val page =
            base.copy(
                rows = base.rows.dropLast(1) + LayoutRow(base.rows.last().keys + jump),
                chrome = chrome.copy(leading = listOf(symbolsJump)),
            )
        page.validate()
        val registry = LayoutRegistry.create().withPage(page)
        val decoded = LayoutRegistryCodec.decode(LayoutRegistryCodec.encode(registry))
        assertEquals(page, decoded.page(page.id))
    }

    @Test
    fun aCatalogWithAJumpKeyIsRejectedWhenItsTargetIsUnknown() {
        val page =
            KeyboardPagePresets.symbolsSystem.duplicated("my-symbols", "我的符号").let {
                it.copy(
                    rows =
                        it.rows +
                            LayoutRow(
                                listOf(
                                    LayoutKey(
                                        "123",
                                        action = LayoutAction(LayoutActionKind.LAYOUT_PAGE_SWITCH, "phone9:letters"),
                                    ),
                                ),
                            ),
                )
            }
        val error = assertThrows(LayoutException::class.java) { LayoutRegistry.create().withPage(page) }
        assertEquals(
            LayoutInvalidCode.LAYOUT_PAGE_SWITCH_REQUIRES_KNOWN_TARGET,
            (error as LayoutException.Invalid).code,
        )
    }

    @Test
    fun aDecoderThatDoesNotKnowTheKindRejectsTheFile() {
        // What a reader that predates the kind does: the kind is an unknown enum value, so the whole
        // file is refused instead of a key being guessed into another action.
        val json = """{"type":"layoutPageSwitchFromTheFuture","value":"phone9:numeric"}"""
        assertThrows(IllegalArgumentException::class.java) {
            LayoutJson.format.decodeFromString(LayoutAction.serializer(), json)
        }
    }
}
