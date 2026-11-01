package app.songyan.ime.ime

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleSpacePeriodTest {
    private fun policy(inputType: Int) = EditorPolicy.fromInputType(inputType, 0)

    @Test
    fun aPlainTextFieldAllowsIt() {
        assertTrue(DoubleSpacePeriod.allowedIn(policy(InputType.TYPE_CLASS_TEXT)))
        assertTrue(
            DoubleSpacePeriod.allowedIn(
                policy(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            ),
        )
    }

    @Test
    fun urlEmailNumberPhonePasswordAndTerminalFieldsRefuseIt() {
        val refused =
            listOf(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_NUMBER,
                InputType.TYPE_CLASS_PHONE,
                InputType.TYPE_CLASS_DATETIME,
                InputType.TYPE_NULL,
            )
        for (type in refused) assertFalse("type $type", DoubleSpacePeriod.allowedIn(policy(type)))
    }

    @Test
    fun aFieldThatAsksForNoPersonalizedLearningRefusesIt() {
        val policy = EditorPolicy.fromInputType(InputType.TYPE_CLASS_TEXT, 0x1000000)
        assertFalse(DoubleSpacePeriod.allowedIn(policy))
    }

    @Test
    fun theSecondSpaceMustComeWithinSixHundredMilliseconds() {
        assertTrue(DoubleSpacePeriod.withinWindow(1_000, 1_599))
        assertFalse(DoubleSpacePeriod.withinWindow(1_000, 1_600))
        assertFalse(DoubleSpacePeriod.withinWindow(1_000, 999))
        assertFalse(DoubleSpacePeriod.withinWindow(DoubleSpacePeriod.NOT_ARMED, 0))
    }

    @Test
    fun onlyAWordCharacterThenOneSpaceIsReplaced() {
        assertTrue(DoubleSpacePeriod.replaceable("ok "))
        assertTrue(DoubleSpacePeriod.replaceable("room 101 "))
        assertTrue(DoubleSpacePeriod.replaceable("café "))
        assertFalse("punctuation before", DoubleSpacePeriod.replaceable("ok. "))
        assertFalse("two spaces", DoubleSpacePeriod.replaceable("ok  "))
        assertFalse("no space", DoubleSpacePeriod.replaceable("ok"))
        assertFalse("only a space", DoubleSpacePeriod.replaceable(" "))
        assertFalse("nothing", DoubleSpacePeriod.replaceable(""))
        assertFalse("unreadable", DoubleSpacePeriod.replaceable(null))
        assertFalse("after a newline", DoubleSpacePeriod.replaceable("\n "))
    }

    @Test
    fun theWindowIsTheDocumentedOne() {
        assertEquals(600L, DoubleSpacePeriod.WINDOW_MS)
    }
}
