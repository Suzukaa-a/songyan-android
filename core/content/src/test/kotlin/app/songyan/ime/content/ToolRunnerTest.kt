package app.songyan.ime.content

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ported from QuickContentKitTests/ToolRunnerTests, with the platform substitutions this module
 * requires: OpenCC and the bounded regular-expression engine live in the app target, so both are
 * injected here. These tests prove the runner's contract around that injection, every transform
 * step, the input and output bounds and the typed step errors; the real conversion output and the
 * real matcher deadline are proved by the app-side device acceptance.
 *
 * Width expectations are taken from Foundation's `fullwidthToHalfwidth` transform, which the iOS
 * runner calls; they must not be replaced with blanket NFKC expectations.
 */
class ToolRunnerTest {
    private fun definition(vararg steps: ToolStep): ToolDefinition =
        ToolDefinition(id = "test", name = mapOf("en" to "Test"), steps = steps.toList())

    private fun run(
        input: String,
        vararg steps: ToolStep,
        limits: ToolRunnerLimits = ToolRunnerLimits(),
        openCCConverter: ToolOpenCCConverter? = null,
        regexReplacement: ToolRegexReplacementProvider? = null,
    ): ToolRunnerResult =
        runBlocking {
            ToolRunner(
                limits = limits,
                openCCConverter = openCCConverter,
                regexReplacement = regexReplacement,
            ).run(definition(*steps), input = input)
        }

    private fun assertRunFails(
        expected: ToolRunnerError,
        input: String,
        vararg steps: ToolStep,
        limits: ToolRunnerLimits = ToolRunnerLimits(),
        openCCConverter: ToolOpenCCConverter? = null,
        regexReplacement: ToolRegexReplacementProvider? = null,
    ) {
        val error =
            assertThrows(ToolRunnerError::class.java) {
                run(
                    input,
                    *steps,
                    limits = limits,
                    openCCConverter = openCCConverter,
                    regexReplacement = regexReplacement,
                )
            }
        assertEquals(expected, error)
    }

    // MARK: - Width

    @Test
    fun widthToHalfConvertsFullwidthLatinAndDigits() {
        // The shared acceptance fixture is ＡＢＣ１ -> ABC1; digits 2 and 3 prove the whole range.
        assertEquals("ABC1", run("ＡＢＣ１", ToolStep.Width(ToolStep.WidthMode.TO_HALF)).text)
        val result = run("ＡＢＣ１２３", ToolStep.Width(ToolStep.WidthMode.TO_HALF))
        assertEquals("ABC123", result.text)
        assertFalse(result.inputWasTruncated)
    }

    @Test
    fun widthToFullConvertsAscii() {
        val result = run("ABC123", ToolStep.Width(ToolStep.WidthMode.TO_FULL))
        assertEquals("ＡＢＣ１２３", result.text)
        assertFalse(result.inputWasTruncated)
    }

    @Test
    fun widthToHalfDecomposesFullwidthKatakanaIntoHalfwidthAndDakuten() {
        assertEquals(
            "ｶﾞｯｺｳ",
            run("ガッコウ", ToolStep.Width(ToolStep.WidthMode.TO_HALF)).text,
        )
    }

    @Test
    fun widthToFullComposesHalfwidthKatakanaWithDakuten() {
        assertEquals(
            "ガッコウ",
            run("ｶﾞｯｺｳ", ToolStep.Width(ToolStep.WidthMode.TO_FULL)).text,
        )
        assertEquals("ガ", run("ｶﾞ", ToolStep.Width(ToolStep.WidthMode.TO_FULL)).text)
        assertEquals("パ", run("ﾊﾟ", ToolStep.Width(ToolStep.WidthMode.TO_FULL)).text)
    }

    @Test
    fun widthToFullKeepsAnUncomposableDakutenAsACombiningMark() {
        assertEquals(
            "カ\u309A",
            run("ｶﾟ", ToolStep.Width(ToolStep.WidthMode.TO_FULL)).text,
        )
    }

    @Test
    fun widthLeavesWhatBlanketNfkcWouldChangeAlone() {
        val exclusion = "①㍿㈱Ⅳ"
        assertEquals(exclusion, run(exclusion, ToolStep.Width(ToolStep.WidthMode.TO_HALF)).text)
        assertEquals(exclusion, run(exclusion, ToolStep.Width(ToolStep.WidthMode.TO_FULL)).text)
    }

    @Test
    fun widthToHalfMapsTheIdeographicSpaceToAscii() {
        assertEquals("A B", run("A\u3000B", ToolStep.Width(ToolStep.WidthMode.TO_HALF)).text)
    }

    @Test
    fun widthToFullMapsTheAsciiSpaceToIdeographic() {
        assertEquals("Ａ\u3000Ｂ", run("A B", ToolStep.Width(ToolStep.WidthMode.TO_FULL)).text)
    }

    // MARK: - Template

    @Test
    fun templateWrapsTheWholeText() {
        val result = run("软件", ToolStep.Template("「{text}」"))
        assertEquals("「软件」", result.text)
        assertFalse(result.inputWasTruncated)
    }

    @Test
    fun templateInsertsTheTextLiterally() {
        assertEquals("<$1\\{text}>", run("$1\\{text}", ToolStep.Template("<{text}>")).text)
    }

    // MARK: - Ordering and the other transforms

    @Test
    fun stepsRunInDeclarationOrder() {
        val forward =
            run("a", ToolStep.Replace("a", "b"), ToolStep.Replace("b", "c"), regexReplacement = LiteralReplacement)
        assertEquals("c", forward.text)

        val reversed =
            run("a", ToolStep.Replace("b", "c"), ToolStep.Replace("a", "b"), regexReplacement = LiteralReplacement)
        assertEquals("b", reversed.text)
    }

    @Test
    fun everyTransformStepTypeRunsInOneDefinition() {
        val steps: Array<ToolStep> =
            arrayOf(
                ToolStep.Width(ToolStep.WidthMode.TO_HALF),
                ToolStep.Map(mapOf("A" to "B")),
                ToolStep.CaseTransform(ToolStep.CaseMode.LOWER),
                ToolStep.Replace("bb", "x"),
                ToolStep.Template("「{text}」"),
            )
        // ＡＢＣ -> ABC -> BBC -> bbc -> xc -> 「xc」
        val result = run("ＡＢＣ", *steps, regexReplacement = LiteralReplacement)
        assertEquals("「xc」", result.text)
    }

    @Test
    fun caseModesUseEnglishCasing() {
        assertEquals("HELLO WORLD", run("Hello wORLD", ToolStep.CaseTransform(ToolStep.CaseMode.UPPER)).text)
        assertEquals("hello world", run("Hello wORLD", ToolStep.CaseTransform(ToolStep.CaseMode.LOWER)).text)
    }

    @Test
    fun capitalizeUppercasesOnlyTheFirstGraphemeOfEachWord() {
        val result = run("  hello wORLD\ttwo", ToolStep.CaseTransform(ToolStep.CaseMode.CAPITALIZE))
        assertEquals("  Hello WORLD\tTwo", result.text)
    }

    // MARK: - Map and grapheme clusters

    @Test
    fun mapMatchesWholeGraphemeClusters() {
        val family = "👨‍👩‍👧‍👦"
        val table = mapOf("é" to "E", "🇯🇵" to "JP", "👍" to "THUMB", family to "FAMILY")
        // The first grapheme is a decomposed é; both platforms compare keys canonically.
        val result =
            run("e\u0301🇯🇵👍👨‍👩‍👧‍👦x", ToolStep.Map(table))
        assertEquals("EJPTHUMBFAMILYx", result.text)
    }

    @Test
    fun mapLeavesUnmappedCharactersUntouched() {
        assertEquals("Ab😀c", run("ab😀c", ToolStep.Map(mapOf("a" to "A"))).text)
    }

    // MARK: - Input bound

    @Test
    fun inputIsTruncatedOnAGraphemeBoundaryAndFlagged() {
        val limits = ToolRunnerLimits(maximumInputUtf16Length = 2, maximumOutputUtf16Length = 64)
        val result = run("😀a", ToolStep.Template("<{text}>"), limits = limits)
        assertEquals("<😀>", result.text)
        assertTrue(result.inputWasTruncated)
    }

    @Test
    fun inputWithinTheBoundIsNotFlagged() {
        val limits = ToolRunnerLimits(maximumInputUtf16Length = 3, maximumOutputUtf16Length = 64)
        val result = run("😀a", ToolStep.Template("<{text}>"), limits = limits)
        assertEquals("<😀a>", result.text)
        assertFalse(result.inputWasTruncated)
    }

    @Test
    fun theInputCutNeverSplitsAGraphemeCluster() {
        val limits = ToolRunnerLimits(maximumInputUtf16Length = 2, maximumOutputUtf16Length = 64)
        val result = run("a👨‍👩‍👧‍👦", ToolStep.Template("<{text}>"), limits = limits)
        assertEquals("<a>", result.text)
        assertTrue(result.inputWasTruncated)
    }

    @Test
    fun theDefaultInputBoundKeepsGraphemeClustersWhole() {
        val input = "a".repeat(8_191) + "😀"
        val result = run(input, ToolStep.Template("{text}"))
        assertEquals("a".repeat(8_191), result.text)
        assertTrue(result.inputWasTruncated)
    }

    @Test
    fun theDefaultInputBoundAcceptsExactlyItsLimit() {
        val input = "a".repeat(8_192)
        val result = run(input, ToolStep.Template("{text}"))
        assertEquals(input, result.text)
        assertFalse(result.inputWasTruncated)
    }

    @Test
    fun defaultInputAndOutputBoundsMatchTheSharedFormat() {
        val limits = ToolRunnerLimits()
        assertEquals(8_192, limits.maximumInputUtf16Length)
        assertEquals(16_384, limits.maximumOutputUtf16Length)
        assertEquals(100, limits.regexBudgetMs)
        assertEquals(256, limits.maximumRegexPatternUtf16Length)
    }

    @Test
    fun defaultInputLimitAllowsTheRequiredPathologicalRegexInput() {
        val input = "a".repeat(5_000) + "b"
        val result = run(input, ToolStep.Template("{text}"))
        assertEquals(input, result.text)
        assertFalse(result.inputWasTruncated)
    }

    // MARK: - Output bound

    @Test
    fun outputBoundRejectsAnOversizedStepResult() {
        val limits = ToolRunnerLimits(maximumInputUtf16Length = 64, maximumOutputUtf16Length = 6)
        assertRunFails(
            ToolRunnerError.OutputTooLong(step = 1, limit = 6),
            "abcde",
            ToolStep.Template("<{text}>"),
            limits = limits,
        )
    }

    @Test
    fun outputBoundIsCheckedAfterEveryStep() {
        val limits = ToolRunnerLimits(maximumInputUtf16Length = 64, maximumOutputUtf16Length = 10)
        val step = ToolStep.Template("<{text}>")
        assertRunFails(
            ToolRunnerError.OutputTooLong(step = 4, limit = 10),
            "abc",
            step,
            step,
            step,
            step,
            limits = limits,
        )
    }

    @Test
    fun theDefaultOutputBoundRejectsAGrownStep() {
        val converter = ToolOpenCCConverter { _, _ -> "x".repeat(16_385) }
        assertRunFails(
            ToolRunnerError.OutputTooLong(step = 1, limit = 16_384),
            "软件",
            ToolStep.OpenCC(OpenCCToolConfig.S2TWP),
            openCCConverter = converter,
        )
    }

    @Test
    fun outputBoundAlsoAppliesToAnInjectedOpenCCResult() {
        val limits = ToolRunnerLimits(maximumInputUtf16Length = 64, maximumOutputUtf16Length = 10)
        val converter = ToolOpenCCConverter { _, _ -> "x".repeat(20) }
        assertRunFails(
            ToolRunnerError.OutputTooLong(step = 1, limit = 10),
            "软件",
            ToolStep.OpenCC(OpenCCToolConfig.S2TWP),
            limits = limits,
            openCCConverter = converter,
        )
    }

    // MARK: - OpenCC injection

    @Test
    fun openCCStepUsesTheInjectedConverter() {
        var seenText: String? = null
        var seenConfig: OpenCCToolConfig? = null
        val converter =
            ToolOpenCCConverter { text, config ->
                seenText = text
                seenConfig = config
                "CONVERTED"
            }
        val result = run("source", ToolStep.OpenCC(OpenCCToolConfig.S2TWP), openCCConverter = converter)
        assertEquals("CONVERTED", result.text)
        assertEquals("source", seenText)
        assertEquals(OpenCCToolConfig.S2TWP, seenConfig)
    }

    @Test
    fun openCCStepWithoutAConverterFailsClosed() {
        assertRunFails(
            ToolRunnerError.ConverterUnavailable(step = 1, config = OpenCCToolConfig.S2TWP),
            "软件",
            ToolStep.OpenCC(OpenCCToolConfig.S2TWP),
        )
    }

    @Test
    fun missingConverterKeepsTheStepThatNeededIt() {
        assertRunFails(
            ToolRunnerError.ConverterUnavailable(step = 2, config = OpenCCToolConfig.S2T),
            "ＡＢＣ",
            ToolStep.Width(ToolStep.WidthMode.TO_HALF),
            ToolStep.OpenCC(OpenCCToolConfig.S2T),
        )
    }

    @Test
    fun converterFailureIsATypedErrorWithStepAndConfig() {
        val converter = ToolOpenCCConverter { _, _ -> throw IllegalStateException("conversion failed") }
        assertRunFails(
            ToolRunnerError.ConversionFailed(step = 1, config = OpenCCToolConfig.T2JP),
            "軟體",
            ToolStep.OpenCC(OpenCCToolConfig.T2JP),
            openCCConverter = converter,
        )
    }

    @Test
    fun aCancelledConversionIsNotReportedAsAPlatformFailure() {
        val converter = ToolOpenCCConverter { _, _ -> throw CancellationException("cancelled") }
        val thrown =
            runCatching {
                run("软件", ToolStep.OpenCC(OpenCCToolConfig.S2TWP), openCCConverter = converter)
            }.exceptionOrNull()
        assertTrue("expected CancellationException, got $thrown", thrown is CancellationException)
    }

    // MARK: - Regular expressions

    @Test
    fun regexReplacementUsesTheInjectedProviderAndItsStepArguments() {
        var seenInput: String? = null
        var seenPattern: String? = null
        var seenReplacement: String? = null
        val provider =
            ToolRegexReplacementProvider { input, pattern, replacement, _ ->
                seenInput = input
                seenPattern = pattern
                seenReplacement = replacement
                "REPLACED"
            }
        val result =
            run(
                "a1b22",
                ToolStep.Replace("(\\d+)", "[$1]"),
                regexReplacement = provider,
            )
        assertEquals("REPLACED", result.text)
        assertEquals("a1b22", seenInput)
        assertEquals("(\\d+)", seenPattern)
        assertEquals("[$1]", seenReplacement)
    }

    @Test
    fun regexStepPassesTheBudgetAndCapsToTheProvider() {
        var seenLimits: ToolRegexReplacementLimits? = null
        val recording =
            ToolRegexReplacementProvider { input, _, _, limits ->
                seenLimits = limits
                input
            }
        val limits =
            ToolRunnerLimits(
                maximumInputUtf16Length = 32,
                maximumOutputUtf16Length = 100,
                regexBudgetMs = 250,
                maximumRegexPatternUtf16Length = 64,
            )
        val result = run("text", ToolStep.Replace("x", "y"), limits = limits, regexReplacement = recording)
        assertEquals("text", result.text)
        assertEquals(
            ToolRegexReplacementLimits(
                maximumInputUtf16Length = 100,
                maximumPatternUtf16Length = 64,
                budgetMs = 250,
            ),
            seenLimits,
        )
    }

    @Test
    fun regexStepWithoutAProviderFailsClosed() {
        assertRunFails(
            ToolRunnerError.RegexUnavailable(step = 1),
            "a",
            ToolStep.Replace("a", "b"),
        )
    }

    @Test
    fun aTimedOutReplacementIsReportedWithItsStepNumber() {
        val timedOut = ToolRegexReplacementProvider { _, _, _, _ -> throw ToolRegexReplacementError.TimedOut(100) }
        assertRunFails(
            ToolRunnerError.RegexFailed(step = 2, reason = ToolRegexReplacementError.TimedOut(100)),
            "ＡＢＣ",
            ToolStep.Width(ToolStep.WidthMode.TO_HALF),
            ToolStep.Replace("(a+)+$", "X"),
            regexReplacement = timedOut,
        )
    }

    @Test
    fun aPatternTheProviderRejectsIsReportedWithItsStepNumber() {
        val invalid = ToolRegexReplacementProvider { _, _, _, _ -> throw ToolRegexReplacementError.InvalidPattern }
        assertRunFails(
            ToolRunnerError.RegexFailed(step = 1, reason = ToolRegexReplacementError.InvalidPattern),
            "a",
            ToolStep.Replace("a", "x"),
            regexReplacement = invalid,
        )
    }

    @Test
    fun aProviderRejectedInputIsReportedWithItsStepNumber() {
        val tooLong =
            ToolRegexReplacementProvider { _, _, _, _ ->
                throw ToolRegexReplacementError.InputTooLong(9)
            }
        assertRunFails(
            ToolRunnerError.RegexFailed(step = 1, reason = ToolRegexReplacementError.InputTooLong(9)),
            "a",
            ToolStep.Replace("a", "b"),
            regexReplacement = tooLong,
        )
    }

    @Test
    fun aFailedWorkerIsReportedAsARegexFailure() {
        val failed = ToolRegexReplacementProvider { _, _, _, _ -> throw ToolRegexReplacementError.WorkerFailed }
        assertRunFails(
            ToolRunnerError.RegexFailed(step = 1, reason = ToolRegexReplacementError.WorkerFailed),
            "a",
            ToolStep.Replace("a", "b"),
            regexReplacement = failed,
        )
    }

    @Test
    fun anUnexpectedProviderFailureIsReportedAsUnexpected() {
        val broken = ToolRegexReplacementProvider { _, _, _, _ -> throw IllegalStateException("broken") }
        assertRunFails(
            ToolRunnerError.UnexpectedFailure(step = 1),
            "a",
            ToolStep.Replace("a", "b"),
            regexReplacement = broken,
        )
    }

    @Test
    fun aCancelledReplacementIsNotReportedAsAPlatformFailure() {
        val cancelled = ToolRegexReplacementProvider { _, _, _, _ -> throw CancellationException("cancelled") }
        val thrown =
            runCatching {
                run("a", ToolStep.Replace("a", "b"), regexReplacement = cancelled)
            }.exceptionOrNull()
        assertTrue("expected CancellationException, got $thrown", thrown is CancellationException)
    }

    // MARK: - Rejected definitions

    @Test
    fun invalidDefinitionIsRejectedBeforeAnyStepRuns() {
        var conversions = 0
        val converter =
            ToolOpenCCConverter { _, _ ->
                conversions++
                "x"
            }
        val error =
            assertThrows(ToolRunnerError::class.java) {
                run(
                    "text",
                    ToolStep.OpenCC(OpenCCToolConfig.S2TWP),
                    ToolStep.Replace("(", "x"),
                    openCCConverter = converter,
                    regexReplacement = LiteralReplacement,
                )
            }
        val invalid = error as ToolRunnerError.InvalidDefinition
        assertEquals(ToolDefinitionError.Reason.InvalidRegex(step = 2), invalid.error.reason)
        assertEquals(0, conversions)
    }

    @Test
    fun runnerLimitsRejectNonPositiveBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolRunnerLimits(maximumInputUtf16Length = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolRunnerLimits(maximumOutputUtf16Length = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolRunnerLimits(regexBudgetMs = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolRunnerLimits(maximumRegexPatternUtf16Length = 0)
        }
    }

    /**
     * A literal stand-in for the injected matcher, for ordering only. It deliberately runs no
     * platform regular expression: production injects the app's isolated worker, whose real
     * matching and deadline are proved by the app-side device acceptance.
     */
    private object LiteralReplacement : ToolRegexReplacementProvider {
        override suspend fun replace(
            input: String,
            pattern: String,
            replacement: String,
            limits: ToolRegexReplacementLimits,
        ): String = input.replace(pattern, replacement)
    }
}
