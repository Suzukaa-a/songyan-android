package app.songyan.ime.ime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import java.util.concurrent.Executor

/**
 * Collects the views of one autofill response, which arrive one by one and out of order, and hands
 * them over in suggestion order once all [size] have answered. Pure, so the ordering and the
 * failure case (a suggestion that cannot be shown) are unit-tested.
 */
class InlineBatch<T : Any>(
    private val size: Int,
) {
    private val slots = arrayOfNulls<Any>(size)
    private var answered = 0

    /** Records the answer for [index] (null when it could not be inflated); returns the list when complete. */
    @Suppress("UNCHECKED_CAST")
    fun put(
        index: Int,
        value: T?,
    ): List<T>? {
        if (index !in 0 until size) return null
        slots[index] = value
        answered++
        return if (answered == size) slots.filterNotNull().map { it as T } else null
    }
}

/**
 * Tells a late answer from a current one: every new response or field change moves the generation
 * on, so views inflated for an older response are thrown away instead of shown over the new field.
 */
class InlineGeneration {
    private var value = 0

    fun next(): Int = ++value

    fun current(): Int = value

    fun invalidate() {
        value++
    }

    fun isCurrent(token: Int): Boolean = token == value
}

/**
 * Inline autofill (Android 11+): password managers and the one-time-code suggestions of the
 * system's autofill service are drawn in the candidate bar's idle position, styled like it.
 * See https://developer.android.com/guide/topics/text/ime-autofill. The suggestions come from the
 * platform; this keyboard reads no messages and holds no SMS permission.
 */
@RequiresApi(Build.VERSION_CODES.R)
object InlineAutofill {
    /** More suggestions than fit the bar would only scroll off; five is what a phone shows. */
    const val MAX_SUGGESTIONS = 5
    private const val MIN_WIDTH_DP = 80
    private const val TEXT_SIZE_SP = 15f
    private const val PADDING_DP = 12

    /**
     * The size limits and style of the chips the system may draw, built from the bar's own colours.
     * [heightDp] is the shared content box of the bar row ([BarContentBox]), not the row's height.
     */
    fun buildRequest(
        context: Context,
        palette: KeyboardPalette,
        maxWidthPx: Int,
        heightDp: Float = BarContentBox.of(palette.tokens.candidateBarHeightDp, null).heightDp,
    ): InlineSuggestionsRequest {
        val density = context.resources.displayMetrics.density
        val height = (heightDp * density).toInt()
        val min = Size((MIN_WIDTH_DP * density).toInt().coerceAtMost(maxWidthPx), height)
        val max = Size(maxWidthPx.coerceAtLeast(min.width), height)
        val spec = InlinePresentationSpec.Builder(min, max).setStyle(style(palette, density)).build()
        return InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(MAX_SUGGESTIONS).build()
    }

    /**
     * Chips read like candidates: the bar's text colour on no fill of their own, so the band does
     * not turn into a row of tinted blocks.
     * The builder setters are public API (see the InlineSuggestionUi guide) but declared on a
     * restricted base builder, hence the lint suppression.
     */
    @SuppressLint("RestrictedApi")
    fun style(
        palette: KeyboardPalette,
        density: Float,
    ): Bundle {
        val pad = (PADDING_DP * density).toInt()
        val chip =
            ViewStyle
                .Builder()
                .setBackgroundColor(android.graphics.Color.TRANSPARENT)
                .setPadding(pad, 0, pad, 0)
                .build()
        val title =
            TextViewStyle
                .Builder()
                .setTextColor(palette.text)
                .setTextSize(TEXT_SIZE_SP)
                .build()
        val subtitle =
            TextViewStyle
                .Builder()
                .setTextColor(palette.muted)
                .setTextSize(TEXT_SIZE_SP - 3f)
                .build()
        val styles =
            InlineSuggestionUi
                .newStyleBuilder()
                .setChipStyle(chip)
                .setSingleIconChipStyle(chip)
                .setTitleStyle(title)
                .setSubtitleStyle(subtitle)
                .build()
        return UiVersions.newStylesBuilder().addStyle(styles).build()
    }

    /**
     * Inflates the suggestions of [response] and gives the views to [strip] once all of them are
     * ready. True when the response was taken (the system then expects the suggestions to be shown).
     */
    fun show(
        context: Context,
        strip: InlineSuggestionStrip,
        response: InlineSuggestionsResponse,
        executor: Executor,
    ): Boolean {
        val suggestions: List<InlineSuggestion> = response.inlineSuggestions.take(MAX_SUGGESTIONS)
        val token = strip.generation.next()
        if (suggestions.isEmpty()) {
            strip.clear()
            return false
        }
        val batch = InlineBatch<android.view.View>(suggestions.size)
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        suggestions.forEachIndexed { index, suggestion ->
            suggestion.inflate(context, size, executor) { view ->
                val done = batch.put(index, view)
                if (done != null && strip.generation.isCurrent(token)) strip.show(done)
            }
        }
        return true
    }
}
