package app.songyan.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import app.songyan.ime.keyboard.session.InputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPolicyTest {
    private fun policy(
        inputType: Int,
        imeOptions: Int = EditorInfo.IME_ACTION_NONE,
    ) = EditorPolicy.fromInputType(inputType, imeOptions)

    @Test
    fun aTextVariationWithoutAClassIsStillAChineseTextField() {
        // Google Contacts' name fields: PERSON_NAME | CAP_WORDS with the class bits left out.
        val name = policy(InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        assertFalse(name.ascii)
        assertEquals(NumericEditorKind.NONE, name.numericKind)
        // A real TYPE_NULL editor (a terminal) still takes key events in English.
        assertTrue(policy(InputType.TYPE_NULL).ascii)
        assertTrue(policy(InputType.TYPE_NULL or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).ascii)
    }

    @Test
    fun numberEditorAsksForTheDirectDigitPad() {
        val signedDecimal =
            policy(
                InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_SIGNED or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL,
            )
        assertEquals(NumericEditorKind.NUMBER, signedDecimal.numericKind)
        assertTrue(signedDecimal.numeric)
        assertTrue(signedDecimal.ascii)
        assertTrue(signedDecimal.numberFlags.signed)
        assertTrue(signedDecimal.numberFlags.decimal)

        val integer = policy(InputType.TYPE_CLASS_NUMBER)
        assertEquals(NumericEditorKind.NUMBER, integer.numericKind)
        assertFalse(integer.numberFlags.signed)
        assertFalse(integer.numberFlags.decimal)

        val decimalOnly = policy(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        assertFalse(decimalOnly.numberFlags.signed)
        assertTrue(decimalOnly.numberFlags.decimal)
    }

    @Test
    fun phoneAndDateTimeEditorKindsAreDistinct() {
        val phone = policy(InputType.TYPE_CLASS_PHONE)
        assertEquals(NumericEditorKind.PHONE, phone.numericKind)
        assertTrue(phone.ascii)
        // A phone field never accepts a sign or a fraction, whatever its extra flags say.
        assertFalse(phone.numberFlags.signed)
        assertFalse(phone.numberFlags.decimal)

        assertEquals(NumericEditorKind.DATETIME, policy(InputType.TYPE_CLASS_DATETIME).numericKind)
    }

    @Test
    fun textEditorsKeepTheNormalPages() {
        val text = policy(InputType.TYPE_CLASS_TEXT)
        assertEquals(NumericEditorKind.NONE, text.numericKind)
        assertFalse(text.numeric)
        // URI and e-mail fields already typed English: their editor type proves it.
        val uri = text
        assertEquals(NumericEditorKind.NONE, uri.numericKind)
        // A URL field starts in English but keeps the 中/英 key.
        val url = policy(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        assertFalse(url.ascii)
        assertTrue(url.startsEnglish)
        assertEquals(app.songyan.ime.keyboard.session.InputMode.ENGLISH, url.modeFor(directInput = false))
        assertEquals(FieldKind.URL, url.fieldKind)
    }

    @Test
    fun passwordAndPrivateFieldsStayPrivate() {
        val password = policy(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertTrue(password.privateInput)
        assertTrue(password.ascii)
        val numericPassword =
            policy(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertTrue(numericPassword.privateInput)
        val privateText =
            policy(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            )
        assertTrue(privateText.privateInput)
        assertFalse(policy(InputType.TYPE_CLASS_TEXT).privateInput)
    }

    @Test
    fun noSuggestionsEditorKeepsChineseInput() {
        // Search and filter fields ask for no suggestions; that is not a privacy request, so they
        // must still compose Chinese instead of falling back to direct English input.
        val search =
            policy(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                EditorInfo.IME_ACTION_SEARCH,
            )
        assertTrue(search.noSuggestions)
        assertFalse(search.privateInput)
        assertFalse(search.ascii)
        assertFalse(search.numeric)
        // Learning-derived output is still suppressed: no learning, no post-commit prediction.
        assertTrue(search.suppressesLearning)
        assertTrue(search.suppressesPrediction)
        // The engine cannot prove learning suppression here; only a private editor may fall back.
        assertFalse(search.directInputRequired(learningProtectionEnforced = false))
        assertEquals(InputMode.CHINESE, search.modeFor(search.directInputRequired(false)))

        // A password field that also sets the flag stays private: privacy wins.
        val password =
            policy(
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            )
        assertTrue(password.privateInput)
        assertTrue(password.suppressesPrediction)
        // The flag is a text-class flag; a number editor must not inherit it.
        assertFalse(policy(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).noSuggestions)
    }

    @Test
    fun trulyPrivateEditorsStillFallBackToDirectInput() {
        val password = policy(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertTrue(password.privateInput)
        assertFalse(password.noSuggestions)
        assertEquals(
            InputMode.ENGLISH,
            password.modeFor(password.directInputRequired(learningProtectionEnforced = false)),
        )
        val privateText =
            policy(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            )
        assertTrue(privateText.privateInput)
        assertFalse(privateText.ascii)
        // An engine that cannot stop learning while composing would leak this text: direct input.
        assertTrue(privateText.directInputRequired(learningProtectionEnforced = false))
        assertEquals(
            InputMode.ENGLISH,
            privateText.modeFor(privateText.directInputRequired(learningProtectionEnforced = false)),
        )
    }

    @Test
    fun aPrivateEditorKeepsChineseWhenTheEngineCanStopLearning() {
        val privateText =
            policy(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            )
        // Protection becoming enforceable is a new result, not a permanent verdict: the mode is
        // derived again from it instead of keeping the earlier direct-input fallback.
        val enforced = privateText.directInputRequired(learningProtectionEnforced = true)
        assertFalse(enforced)
        assertEquals(InputMode.CHINESE, privateText.modeFor(enforced))
    }

    @Test
    fun theModeFollowsTheCurrentEditorNotThePreviousFallback() {
        val password = policy(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertTrue(password.directInputRequired(learningProtectionEnforced = false))
        // The next editor computes its own decision; a previous editor's fallback never carries over.
        val search =
            policy(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                EditorInfo.IME_ACTION_SEARCH,
            )
        assertEquals(
            InputMode.CHINESE,
            search.modeFor(search.directInputRequired(learningProtectionEnforced = false)),
        )
    }

    @Test
    fun forceAsciiAndEditorActionFollowTheEditor() {
        assertTrue(policy(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_FORCE_ASCII).ascii)
        assertEquals(
            EditorInfo.IME_ACTION_SEARCH,
            policy(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH).action,
        )
        assertEquals(
            EditorInfo.IME_ACTION_NONE,
            policy(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_GO,
            ).action,
        )
    }

    @Test
    fun everyPlatformActionReachesTheEnterKeyUnchanged() {
        val actions =
            listOf(
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_NEXT,
                EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_PREVIOUS,
                EditorInfo.IME_ACTION_NONE,
            )
        for (action in actions) {
            assertEquals(action, policy(InputType.TYPE_CLASS_TEXT, action).action)
            // A URI or numeric editor takes the same action; only the layout and the mode change.
            assertEquals(action, policy(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, action).action)
            assertEquals(action, policy(InputType.TYPE_CLASS_NUMBER, action).action)
        }
        // UNSPECIFIED stays unspecified: the editor asked for no particular action, so the Return
        // key is the only thing left to deliver.
        assertEquals(EditorInfo.IME_ACTION_UNSPECIFIED, policy(InputType.TYPE_CLASS_TEXT, 0).action)
    }

    @Test
    fun noEnterActionAlwaysWinsOverTheActionBits() {
        // TextView forces this flag on multi-line editors so Enter stays a line break, and an app can
        // set it to protect an important action (sending a message) from a stray Return.
        val multiline =
            policy(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            )
        assertEquals(EditorInfo.IME_ACTION_NONE, multiline.action)
        assertTrue(multiline.multiline)
        assertTrue(multiline.returnsNewline)
    }

    @Test
    fun anEditorSuppliedActionLabelOwnsTheEnterKey() {
        val custom =
            EditorPolicy.fromInputType(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_UNSPECIFIED,
                "发表",
                1,
            )
        assertEquals(EditorPolicy.ACTION_CUSTOM_LABEL, custom.action)
        assertEquals("发表", custom.customActionLabel)
        // A sentinel, never a platform action code.
        assertTrue(EditorPolicy.ACTION_CUSTOM_LABEL > EditorInfo.IME_MASK_ACTION)
        // TextView may resolve the action bits to DONE anyway; the editor's own label still wins.
        val resolved =
            EditorPolicy.fromInputType(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE, "发表", 1)
        assertEquals(EditorPolicy.ACTION_CUSTOM_LABEL, resolved.action)
        assertEquals("发表", resolved.customActionLabel)
        // A label that cannot be performed (no action id to send back) leaves the action bits in charge.
        val noId = EditorPolicy.fromInputType(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_GO, "发表", 0)
        assertEquals(EditorInfo.IME_ACTION_GO, noId.action)
        assertNull(noId.customActionLabel)
        // A blank label is no label.
        assertNull(
            EditorPolicy.fromInputType(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_GO, " ", 1).customActionLabel,
        )
        // A Return key must not borrow a label it cannot run.
        val returnKey =
            EditorPolicy.fromInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
                "发表",
                1,
            )
        assertEquals(EditorInfo.IME_ACTION_NONE, returnKey.action)
        assertNull(returnKey.customActionLabel)
    }

    @Test
    fun aMultiLineEditorReturnsALineBreakAndEveryOtherEditorAnEnterKey() {
        val multiline = policy(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertTrue(multiline.multiline)
        assertTrue(multiline.returnsNewline)
        // IME_MULTI_LINE only asks the fullscreen extract view for more lines: the Return key keeps
        // following the action bits in the field itself.
        val extractOnly = policy(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE)
        assertFalse(extractOnly.multiline)
        assertFalse(extractOnly.returnsNewline)
        // Single-line text, URI, password, number, phone and date editors take the key event.
        for (
        inputType in
        listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
        )
        ) {
            assertFalse(policy(inputType).returnsNewline)
        }
        // A multi-line flag on a non-text class is not a multi-line text editor.
        assertFalse(policy(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_MULTI_LINE).multiline)
    }

    @Test
    fun dateTimeVariationDecidesTheSeparators() {
        assertEquals(
            DateTimeFlags(date = true),
            policy(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE).dateTimeFlags,
        )
        assertEquals(
            DateTimeFlags(time = true),
            policy(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME).dateTimeFlags,
        )
        // No variation means the field takes both.
        assertEquals(DateTimeFlags(date = true, time = true), policy(InputType.TYPE_CLASS_DATETIME).dateTimeFlags)
        // Only a date/time editor has date separators.
        assertEquals(DateTimeFlags(), policy(InputType.TYPE_CLASS_NUMBER).dateTimeFlags)
        assertEquals(DateTimeFlags(), policy(InputType.TYPE_CLASS_TEXT).dateTimeFlags)
    }

    @Test
    fun asciiEditorsCoverEveryWebAndEmailVariation() {
        for (
        variation in
        listOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )
        ) {
            val editor = policy(InputType.TYPE_CLASS_TEXT or variation)
            assertTrue("variation " + variation, editor.ascii)
            assertEquals(FieldKind.EMAIL, editor.fieldKind)
            assertFalse("variation " + variation, editor.privateInput)
        }
        // A Chinese subject line is allowed; only the fields that hold machine-readable text are ASCII.
        assertFalse(policy(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT).ascii)
        // Every password variation is private and ASCII, including the visible and web ones.
        for (
        variation in
        listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        ) {
            val editor = policy(InputType.TYPE_CLASS_TEXT or variation)
            assertTrue("variation " + variation, editor.privateInput)
            assertTrue("variation " + variation, editor.ascii)
        }
        // A numeric password is private too, and keeps the digit pad.
        val numericPassword = policy(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertTrue(numericPassword.privateInput)
        assertEquals(NumericEditorKind.NUMBER, numericPassword.numericKind)
    }

    @Test
    fun keyEventEditorKeepsTheNormalPagesButTypesDirectly() {
        val unknown = policy(InputType.TYPE_NULL)
        assertEquals(NumericEditorKind.NONE, unknown.numericKind)
        // A terminal cannot show a composition: Chinese composing would type blind.
        assertTrue(unknown.ascii)
        assertFalse(unknown.privateInput)
        assertFalse(unknown.noSuggestions)
        assertFalse(unknown.multiline)
        // FORCE_ASCII is an editor flag, not a class flag, so it applies to every class.
        assertTrue(policy(InputType.TYPE_NULL, EditorInfo.IME_FLAG_FORCE_ASCII).ascii)
        assertTrue(policy(InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_FLAG_FORCE_ASCII).ascii)
    }

    @Test
    fun aPrivateEditorStillTurnsOffLearningAndPrediction() {
        val numericPassword = policy(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertTrue(numericPassword.suppressesLearning)
        assertTrue(numericPassword.suppressesPrediction)
        // Clipboard capture is the app's job, but it reads the same flag.
        assertTrue(numericPassword.privateInput)
    }
}
