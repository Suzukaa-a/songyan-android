package app.songyan.ime.ime.tools

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import app.songyan.ime.ime.EditorConnection
import app.songyan.ime.ime.EditorPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The source rule of the tools panel against a small in-memory editor: a selection is the source,
 * else the whole field; an over-long source is refused; and a replacement is written only when the
 * field still holds exactly what the tool was handed.
 */
class KeyboardToolsEditorTest {
    /** A text field with a caret or a selection; every edit the tools make goes through it. */
    private class FakeField(
        initial: String,
        var start: Int = initial.length,
        var end: Int = initial.length,
    ) {
        var text: String = initial
        var batchDepth = 0
        var edits = 0

        val connection: InputConnection =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(InputConnection::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "beginBatchEdit" -> {
                        batchDepth++
                        true
                    }
                    "endBatchEdit" -> {
                        batchDepth--
                        batchDepth > 0
                    }
                    "getSelectedText" ->
                        if (start ==
                            end
                        ) {
                            null
                        } else {
                            text.substring(minOf(start, end), maxOf(start, end))
                        }
                    "getTextBeforeCursor" ->
                        text.substring(
                            maxOf(0, minOf(start, end) - args[0] as Int),
                            minOf(start, end),
                        )
                    "getTextAfterCursor" ->
                        text.substring(maxOf(start, end), minOf(text.length, maxOf(start, end) + args[0] as Int))
                    "deleteSurroundingText" -> {
                        check(batchDepth > 0) { "an edit outside a batch" }
                        edits++
                        val low = minOf(start, end)
                        val high = maxOf(start, end)
                        val from = maxOf(0, low - args[0] as Int)
                        val to = minOf(text.length, high + args[1] as Int)
                        text = text.substring(0, from) + text.substring(low, high) + text.substring(to)
                        start = low - (low - from)
                        end = start + (high - low)
                        true
                    }
                    "commitText" -> {
                        check(batchDepth > 0) { "an edit outside a batch" }
                        edits++
                        val low = minOf(start, end)
                        val high = maxOf(start, end)
                        text = text.substring(0, low) + args[0] + text.substring(high)
                        start = low + (args[0] as CharSequence).length
                        end = start
                        true
                    }
                    else -> if (method.returnType == Boolean::class.javaPrimitiveType) true else null
                }
            } as InputConnection
    }

    private fun toolsFor(
        field: FakeField,
        privateInput: Boolean = false,
        fieldId: () -> Int = { 7 },
    ): EditorConnectionKeyboardTools {
        val editor = EditorConnection { field.connection }
        val policy = EditorPolicy(false, false, privateInput, EditorInfo.IME_ACTION_NONE)
        return EditorConnectionKeyboardTools(
            editor = editor,
            info = {
                EditorInfo().also {
                    it.packageName = "app.test"
                    it.fieldId = fieldId()
                }
            },
            policy = { policy },
            clipboard = null,
        )
    }

    @Test
    fun withoutASelectionTheWholeFieldIsTheSourceAroundTheCaret() {
        val field = FakeField("first line\nsecond line", start = 5, end = 5)
        val source = requireNotNull(toolsFor(field).field())

        assertEquals("first line\nsecond line", source.text)
        assertEquals(KeyboardToolSourceKind.FIELD, source.kind)
        assertEquals(5, source.caret)
    }

    @Test
    fun aSelectionIsTheOnlySourceWhileTextIsSelected() {
        val field = FakeField("hello world", start = 0, end = 5)
        val tools = toolsFor(field)

        assertEquals("hello", tools.selection()?.text)
        assertNull("the field was offered over a selection", tools.field())
    }

    @Test
    fun aBlankFieldIsNotASource() {
        assertNull(toolsFor(FakeField("  \n ")).field())
        assertNull(toolsFor(FakeField("")).field())
    }

    @Test
    fun aFieldAtTheLimitIsOfferedAndOneUnitOverIsRefusedWhole() {
        val limit = EditorConnectionKeyboardTools.TOOL_TEXT_LIMIT
        assertEquals(limit, toolsFor(FakeField("字".repeat(limit))).field()?.text?.length)
        // Text on both sides of the caret counts together, and nothing is cut to fit.
        assertNull(toolsFor(FakeField("字".repeat(limit + 1), start = limit / 2, end = limit / 2)).field())
        assertNull(toolsFor(FakeField("a".repeat(limit) + "b", start = limit, end = limit)).field())
    }

    @Test
    fun aPrivateEditorOffersNothing() {
        val field = FakeField("secret")
        val tools = toolsFor(field, privateInput = true)

        assertNull(tools.field())
        assertNull(tools.selection())
    }

    @Test
    fun replacingWritesTheWholeFieldInOneBatchAndKeepsTheCaretAfterIt() {
        val field = FakeField("hello world", start = 5, end = 5)
        val tools = toolsFor(field)
        val source = requireNotNull(tools.field())

        assertTrue(tools.replace(source, "HELLO WORLD"))

        assertEquals("HELLO WORLD", field.text)
        assertEquals(0, field.batchDepth)
        assertEquals(2, field.edits)
        assertEquals(11, field.start)
    }

    @Test
    fun textAfterTheCaretIsReplacedToo() {
        val field = FakeField("abc def", start = 3, end = 3)
        val tools = toolsFor(field)
        val source = requireNotNull(tools.field())

        assertTrue(tools.replace(source, "ABC DEF"))

        assertEquals("ABC DEF", field.text)
    }

    @Test
    fun aFieldEditedAfterTheCaptureIsNotOverwritten() {
        val field = FakeField("abc def", start = 3, end = 3)
        val tools = toolsFor(field)
        val source = requireNotNull(tools.field())

        // Text typed after the caret, and text typed before it, both change what the tool saw.
        field.text = "abc def!"
        assertFalse(tools.replace(source, "ABC DEF"))
        field.text = "xabc def"
        field.start = 4
        field.end = 4
        assertFalse(tools.replace(source, "ABC DEF"))

        assertEquals("xabc def", field.text)
        assertEquals(0, field.edits)
    }

    @Test
    fun aMovedCaretOrANewSelectionBlocksTheReplacement() {
        val field = FakeField("abc def", start = 3, end = 3)
        val tools = toolsFor(field)
        val source = requireNotNull(tools.field())

        field.start = 1
        field.end = 1
        assertFalse("the caret moved, so before and after differ", tools.replace(source, "X"))
        field.start = 0
        field.end = 3
        assertFalse("text got selected", tools.replace(source, "X"))

        assertEquals("abc def", field.text)
        assertEquals(0, field.edits)
    }

    @Test
    fun anotherFieldNeverReceivesTheReplacement() {
        val field = FakeField("abc", start = 3, end = 3)
        var id = 7
        val tools = toolsFor(field, fieldId = { id })
        val source = requireNotNull(tools.field())

        id = 8

        assertFalse(tools.replace(source, "ABC"))
        assertEquals("abc", field.text)
    }

    @Test
    fun aSourceThatWasNeverCapturedCannotBeReplaced() {
        val field = FakeField("abc", start = 3, end = 3)
        val tools = toolsFor(field)
        assertNotNull(tools.field())

        assertFalse(tools.replace(KeyboardToolText("abc", KeyboardToolSourceKind.FIELD, caret = 1), "X"))
        assertFalse(tools.replace(KeyboardToolText("abc", KeyboardToolSourceKind.CLIPBOARD), "X"))
        assertEquals("abc", field.text)
    }
}
