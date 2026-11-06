package app.songyan.ime.content

import java.text.Normalizer
import java.util.regex.Pattern
import kotlin.coroutines.cancellation.CancellationException

/**
 * Bounds and the deadline one run applies.
 *
 * The input bound is larger than the interface's 1000-character selection limit on purpose: the
 * run must be able to hand the required pathological regular-expression fixture to the isolated
 * matcher, so the deadline is measured on real work instead of on a prefix the runner cut short.
 */
data class ToolRunnerLimits(
    val maximumInputUtf16Length: Int = 8_192,
    val maximumOutputUtf16Length: Int = 16_384,
    val regexBudgetMs: Int = 100,
    val maximumRegexPatternUtf16Length: Int = ToolDefinitionLimits.MAXIMUM_PATTERN_UTF16_CODE_UNITS,
) {
    init {
        require(maximumInputUtf16Length > 0) { "the input bound must be positive" }
        require(maximumOutputUtf16Length > 0) { "the output bound must be positive" }
        require(regexBudgetMs > 0) { "a regular-expression step needs a positive deadline" }
        require(maximumRegexPatternUtf16Length > 0) { "the pattern bound must be positive" }
    }
}

/** The transformed text plus the one fact the interface needs before writing it back. */
data class ToolRunnerResult(
    val text: String,
    /**
     * True when only the leading grapheme clusters of the input were processed. A caller must not
     * replace the whole selection in that case, because the untransformed tail would be lost; it
     * should insert at the cursor or leave the selection untouched instead.
     */
    val inputWasTruncated: Boolean,
)

/**
 * Why a run stopped. The step number is 1-based, matching [ToolDefinitionError], so the interface
 * can point at the same step that validation would name.
 *
 * [Exception.message] is developer-facing only and must never be shown to a user; the interface maps each
 * type onto a localized string from resources.
 */
sealed class ToolRunnerError(
    message: String,
) : Exception(message) {
    /** The definition did not pass [ToolDefinition.validate]; no step ran. */
    data class InvalidDefinition(
        val error: ToolDefinitionError,
    ) : ToolRunnerError("the definition was rejected: " + error.reason)

    /** An OpenCC step was reached but no converter was injected. */
    data class ConverterUnavailable(
        val step: Int,
        val config: OpenCCToolConfig,
    ) : ToolRunnerError("step " + step + " needs the " + config.wireName + " OpenCC converter")

    /** The injected converter threw; its own error stays with the converter for diagnostics. */
    data class ConversionFailed(
        val step: Int,
        val config: OpenCCToolConfig,
    ) : ToolRunnerError("step " + step + " failed the " + config.wireName + " OpenCC conversion")

    /** A replace step was reached but no bounded regular-expression provider was injected. */
    data class RegexUnavailable(
        val step: Int,
    ) : ToolRunnerError("step " + step + " needs the bounded regular-expression provider")

    /** A replace step failed or exceeded its budget; the run never returns partial output. */
    data class RegexFailed(
        val step: Int,
        val reason: ToolRegexReplacementError,
    ) : ToolRunnerError("step " + step + " failed its regular expression: " + reason.message)

    /** A step produced more text than [ToolRunnerLimits.maximumOutputUtf16Length]. */
    data class OutputTooLong(
        val step: Int,
        val limit: Int,
    ) : ToolRunnerError("step " + step + " produced more than " + limit + " UTF-16 code units")

    /** A step threw a failure the runner cannot classify. Kept separate so it never guesses. */
    data class UnexpectedFailure(
        val step: Int,
    ) : ToolRunnerError("step " + step + " failed in a way the runner cannot classify")
}

/**
 * The app-side OpenCC conversion the runner injects.
 *
 * This module neither imports RimeBridge nor ships conversion data, so the bundled OpenCC
 * implementation stays in the app target, which owns the engine thread. A definition that needs a
 * conversion run without a converter fails closed with [ToolRunnerError.ConverterUnavailable].
 */
fun interface ToolOpenCCConverter {
    suspend fun convert(
        text: String,
        config: OpenCCToolConfig,
    ): String
}

/**
 * Caps one regular-expression replacement must honour before it starts a match.
 *
 * The provider is responsible for keeping [budgetMs]; this module never runs a platform matcher on
 * its own, because Kotlin's matcher backtracks inside native code and cannot be stopped.
 */
data class ToolRegexReplacementLimits(
    val maximumInputUtf16Length: Int,
    val maximumPatternUtf16Length: Int,
    val budgetMs: Int,
) {
    init {
        require(maximumInputUtf16Length > 0) { "the replacement input bound must be positive" }
        require(maximumPatternUtf16Length > 0) { "the replacement pattern bound must be positive" }
        require(budgetMs > 0) { "a replacement needs a positive deadline" }
    }
}

/** Why an injected bounded replacement did not produce text. */
sealed class ToolRegexReplacementError(
    message: String,
) : Exception(message) {
    /** The text is longer than the provider accepts. */
    data class InputTooLong(
        val limit: Int,
    ) : ToolRegexReplacementError("the input exceeds " + limit + " UTF-16 code units")

    /** The pattern is longer than the provider accepts. */
    data class PatternTooLong(
        val limit: Int,
    ) : ToolRegexReplacementError("the pattern exceeds " + limit + " UTF-16 code units")

    /** The pattern does not compile under the platform's regular-expression rules. */
    data object InvalidPattern : ToolRegexReplacementError("the pattern does not compile")

    /** The deadline passed and the isolated matcher's death was observed. */
    data class TimedOut(
        val budgetMs: Int,
    ) : ToolRegexReplacementError("the replacement exceeded " + budgetMs + " ms")

    /** The isolated matcher died or could not be started, so no result exists. */
    data object WorkerFailed : ToolRegexReplacementError("the isolated matcher failed")
}

/**
 * Runs one bounded replacement off the caller's thread.
 *
 * The app target injects its private `:tools` process implementation; this module only holds the
 * contract. A provider that throws anything else makes the runner report
 * [ToolRunnerError.UnexpectedFailure] instead of guessing a more specific reason.
 */
fun interface ToolRegexReplacementProvider {
    suspend fun replace(
        input: String,
        pattern: String,
        replacement: String,
        limits: ToolRegexReplacementLimits,
    ): String
}

/**
 * Executes a tool definition's steps in declaration order on one bounded string.
 *
 * The runner is pure logic: it touches no files, no engine and no network. Conversion and regular
 * expressions are injected because both need platform machinery this module must not own. A
 * missing provider fails closed before that step changes anything; a failure never yields partial
 * output.
 */
class ToolRunner(
    val limits: ToolRunnerLimits = ToolRunnerLimits(),
    private val openCCConverter: ToolOpenCCConverter? = null,
    private val regexReplacement: ToolRegexReplacementProvider? = null,
) {
    /**
     * Validates [definition], then runs its steps in order.
     *
     * Validation runs again here so an in-memory model cannot bypass the file format's rules.
     * [input] is the selected text, or the paragraph before the cursor, already bounded by the
     * caller's own selection rules.
     */
    suspend fun run(
        definition: ToolDefinition,
        input: String,
    ): ToolRunnerResult {
        try {
            definition.validate()
        } catch (error: ToolDefinitionError) {
            throw ToolRunnerError.InvalidDefinition(error)
        }

        val truncated = truncate(input, limits.maximumInputUtf16Length)
        var text = truncated.text
        for ((offset, step) in definition.steps.withIndex()) {
            val stepNumber = offset + 1
            text = apply(step, stepNumber, text)
            if (text.length > limits.maximumOutputUtf16Length) {
                throw ToolRunnerError.OutputTooLong(stepNumber, limits.maximumOutputUtf16Length)
            }
        }
        return ToolRunnerResult(text = text, inputWasTruncated = truncated.wasTruncated)
    }

    private suspend fun apply(
        step: ToolStep,
        stepNumber: Int,
        text: String,
    ): String =
        when (step) {
            is ToolStep.OpenCC -> convert(text, step.config, stepNumber)
            is ToolStep.Replace -> replace(text, step, stepNumber)
            is ToolStep.Map -> mapGraphemes(text, step.table)
            is ToolStep.CaseTransform -> changeCase(text, step.mode)
            is ToolStep.Width -> ToolWidthTransform.transform(text, step.mode)
            is ToolStep.Template -> step.pattern.replace(ToolDefinition.TEXT_PLACEHOLDER, text)
        }

    private suspend fun convert(
        text: String,
        config: OpenCCToolConfig,
        stepNumber: Int,
    ): String {
        val converter = openCCConverter ?: throw ToolRunnerError.ConverterUnavailable(stepNumber, config)
        return try {
            converter.convert(text, config)
        } catch (cancelled: CancellationException) {
            // A cancelled caller must stay cancelled; it is not a conversion the app failed to run.
            throw cancelled
        } catch (_: Exception) {
            throw ToolRunnerError.ConversionFailed(stepNumber, config)
        }
    }

    private suspend fun replace(
        text: String,
        step: ToolStep.Replace,
        stepNumber: Int,
    ): String {
        val provider = regexReplacement ?: throw ToolRunnerError.RegexUnavailable(stepNumber)
        val replacementLimits =
            ToolRegexReplacementLimits(
                maximumInputUtf16Length = limits.maximumOutputUtf16Length,
                maximumPatternUtf16Length = limits.maximumRegexPatternUtf16Length,
                budgetMs = limits.regexBudgetMs,
            )
        return try {
            provider.replace(text, step.pattern, step.replacement, replacementLimits)
        } catch (cancelled: CancellationException) {
            // A cancelled caller must stay cancelled, not report a matcher timeout it never asked for.
            throw cancelled
        } catch (failure: ToolRegexReplacementError) {
            throw ToolRunnerError.RegexFailed(stepNumber, failure)
        } catch (_: Exception) {
            throw ToolRunnerError.UnexpectedFailure(stepNumber)
        }
    }

    /**
     * Maps whole grapheme clusters, not chars, so an emoji or a combining sequence is one lookup.
     * Keys are compared after NFC normalization because the iOS model compares strings canonically.
     */
    private fun mapGraphemes(
        text: String,
        table: Map<String, String>,
    ): String {
        val normalized = normalizedTable(table)
        val builder = StringBuilder(text.length)
        forEachGrapheme(text) { cluster ->
            val mapped = normalized[Normalizer.normalize(cluster, Normalizer.Form.NFC)]
            builder.append(mapped ?: cluster)
        }
        return builder.toString()
    }

    /**
     * Canonically equivalent keys collapse the same way the iOS dictionary collapses them; the last
     * entry in file order wins, so a decoded table is reproducible.
     */
    private fun normalizedTable(table: Map<String, String>): Map<String, String> {
        val result = HashMap<String, String>(table.size * 2)
        for ((key, value) in table) {
            result[Normalizer.normalize(key, Normalizer.Form.NFC)] = value
        }
        return result
    }

    /** Upper- and lowercase use the locale-independent Unicode mappings the iOS model uses. */
    private fun changeCase(
        text: String,
        mode: ToolStep.CaseMode,
    ): String =
        when (mode) {
            ToolStep.CaseMode.UPPER -> text.uppercase()
            ToolStep.CaseMode.LOWER -> text.lowercase()
            ToolStep.CaseMode.CAPITALIZE -> capitalize(text)
        }

    /**
     * Uppercases the first grapheme cluster of every whitespace-separated word and leaves the rest
     * of the word unchanged, which is the capitalization rule the step model defines.
     */
    private fun capitalize(text: String): String {
        val builder = StringBuilder(text.length)
        var startsWord = true
        forEachGrapheme(text) { cluster ->
            val whitespace = cluster.all { it.isWhitespace() }
            if (startsWord && !whitespace) {
                builder.append(cluster.uppercase())
                startsWord = false
            } else {
                builder.append(cluster)
                if (whitespace) startsWord = true
            }
        }
        return builder.toString()
    }

    private inline fun forEachGrapheme(
        text: String,
        action: (String) -> Unit,
    ) {
        val matcher = GRAPHEME_CLUSTER.matcher(text)
        while (matcher.find()) action(matcher.group())
    }

    /**
     * Cuts the input to the longest prefix that fits the UTF-16 budget without splitting a
     * grapheme cluster, so a truncated preview never ends in half an emoji or a broken accent.
     */
    private fun truncate(
        input: String,
        limit: Int,
    ): TruncatedInput {
        if (input.length <= limit) return TruncatedInput(input, false)
        val matcher = GRAPHEME_CLUSTER.matcher(input)
        var end = 0
        while (matcher.find()) {
            if (matcher.end() > limit) break
            end = matcher.end()
        }
        return TruncatedInput(input.substring(0, end), true)
    }

    private data class TruncatedInput(
        val text: String,
        val wasTruncated: Boolean,
    )

    private companion object {
        /** One grapheme cluster, the unit truncation and the map step must not split. */
        val GRAPHEME_CLUSTER: Pattern = Pattern.compile("\\X")
    }
}
