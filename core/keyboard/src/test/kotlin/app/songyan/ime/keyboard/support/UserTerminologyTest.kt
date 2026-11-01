package app.songyan.ime.keyboard.support

import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The product terminology and the copy gate (ported from KeyboardCore). */
class UserTerminologyTest {
    @Test
    fun productTermsMatchTheAgreedWording() {
        assertEquals("输入方案", UserTerminology.INPUT_SCHEME)
        assertEquals("输入方式", UserTerminology.INPUT_METHOD)
        assertEquals("键盘布局", UserTerminology.KEYBOARD_LAYOUT)
        assertEquals("键盘外观", UserTerminology.KEYBOARD_APPEARANCE)
        assertEquals("词库", UserTerminology.DICTIONARY)
        assertEquals("语法模型", UserTerminology.GRAMMAR_MODEL)
        assertEquals("我的键盘", UserTerminology.USER_KEYBOARD)
        assertEquals("正在使用", UserTerminology.STATUS_IN_USE)
        assertEquals("未使用", UserTerminology.STATUS_NOT_APPLIED)
        assertEquals("使用", UserTerminology.START_USING)
        assertEquals("使用新版本", UserTerminology.START_USING_NEW_VERSION)
    }

    @Test
    fun inUseSummaryJoinsTheKnownParts() {
        assertEquals(
            "正在使用：雾凇拼音 · 内置全拼 · 14 键",
            UserTerminology.inUseSummary("雾凇拼音", "内置全拼", "14 键"),
        )
        assertEquals("正在使用：雾凇拼音 · 14 键", UserTerminology.inUseSummary("雾凇拼音", null, "14 键"))
        // The input method is the scheme itself: it is not repeated.
        assertEquals("正在使用：雾凇拼音 · 14 键", UserTerminology.inUseSummary("雾凇拼音", "雾凇拼音", "14 键"))
        assertEquals("尚未选择输入方案", UserTerminology.inUseSummary(null, null, null))
    }

    @Test
    fun currentKeyboardSummaryNeedsAKnownPart() {
        assertEquals(
            "正在使用：小鹤双拼 · 9 键",
            UserTerminology.currentKeyboardSummary("小鹤双拼", KeyboardLayoutKind.PHONE9),
        )
        assertEquals("正在使用：9 键", UserTerminology.currentKeyboardSummary("", KeyboardLayoutKind.PHONE9))
        assertNull(UserTerminology.currentKeyboardSummary(null, null))
    }

    @Test
    fun installedSchemeSummaryNeverCallsItASchemeCount() {
        assertEquals("已安装 2 个输入方案，正在使用「雾凇拼音」", UserTerminology.installedSchemeSummary(2, "雾凇拼音"))
        assertEquals("已安装 2 个输入方案", UserTerminology.installedSchemeSummary(2, null))
        assertEquals("尚未安装输入方案", UserTerminology.installedSchemeSummary(0, null))
    }

    @Test
    fun schemaIdsAreNeverShownAsInputMethodNames() {
        val schemas = listOf(KeyboardSchemaDescriptor("rime_ice", "雾凇拼音"))
        assertEquals("雾凇拼音", UserTerminology.inputMethodName("rime_ice", schemas))
        // A known built-in id resolves to its friendly name.
        assertEquals("内置全拼", UserTerminology.inputMethodName("songyan_quanpin", emptyList()))
        // An unknown id resolves to nothing rather than the raw id.
        assertNull(UserTerminology.inputMethodName("private_scheme_42", emptyList()))
        assertNull(UserTerminology.inputMethodName(null, null))
    }

    @Test
    fun dimensionLinesSkipEmptyEntries() {
        assertEquals("输入方式：全拼、双拼", UserTerminology.inputMethodLine(listOf("全拼", " 双拼 ")))
        assertNull(UserTerminology.inputMethodLine(listOf(" ", "")))
        assertEquals("键盘布局：26 键、9 键", UserTerminology.keyboardLayoutLine(listOf("26 键", "9 键")))
        assertNull(UserTerminology.keyboardLayoutLine(emptyList()))
    }

    @Test
    fun layoutUnavailableReasonUsesTheUserLayoutName() {
        assertEquals(
            "此输入方式暂不支持「9 键」，请改用其他键盘布局。",
            UserTerminology.layoutUnavailableReason(KeyboardLayoutKind.PHONE9),
        )
    }

    @Test
    fun theCopyGateFindsInternalTerms() {
        assertEquals("schema", UserTerminology.leaksInternalTerminology("the schema list"))
        assertEquals("部署", UserTerminology.leaksInternalTerminology("正在部署"))
        assertEquals("generation", UserTerminology.leaksInternalTerminology("Generation 3"))
        assertNull(UserTerminology.leaksInternalTerminology("当前输入方式"))
        // The shared copy must itself pass the gate.
        val copy =
            listOf(
                UserTerminology.USER_KEYBOARD_NEEDS_SOURCE_HINT,
                UserTerminology.USER_KEYBOARD_FOOTNOTE,
                UserTerminology.TRY_HOW_TO_USE_VIA_USER_KEYBOARD,
                UserTerminology.inUseSummary("雾凇拼音", null, "14 键"),
                UserTerminology.installedSchemeSummary(1, "雾凇拼音"),
                UserTerminology.layoutUnavailableReason(KeyboardLayoutKind.MERGED14),
            )
        copy.forEach { assertNull("leaked in: " + it, UserTerminology.leaksInternalTerminology(it)) }
    }

    @Test
    fun trialCopyExplainsWhatToDo() {
        assertTrue(
            UserTerminology
                .trySourceContext("雾凇拼音", inUse = true, howToUse = "请先回到上一页点「使用」")
                .startsWith("键盘正在使用「雾凇拼音」。"),
        )
        val idle = UserTerminology.trySourceContext("雾凇拼音", inUse = false, howToUse = "请先回到上一页点「使用」")
        assertTrue(idle.contains("键盘没有在使用「雾凇拼音」。"))
        assertTrue(idle.endsWith("请先回到上一页点「使用」。"))
    }

    @Test
    fun userKeyboardMessagesArePlain() {
        assertEquals("已换用「内置全拼 · 14 键」。", UserTerminology.userKeyboardNowUsing("内置全拼 · 14 键"))
        assertEquals("已保存，共 3 个键盘。", UserTerminology.userKeyboardSaved(3))
        assertEquals("已保存，共 0 个键盘。", UserTerminology.userKeyboardSaved(-1))
        assertEquals("全拼，14 键", UserTerminology.userKeyboardToggleLabel(" 全拼 ", KeyboardLayoutKind.MERGED14))
        assertEquals("14 键", UserTerminology.userKeyboardToggleLabel("", KeyboardLayoutKind.MERGED14))
    }
}
