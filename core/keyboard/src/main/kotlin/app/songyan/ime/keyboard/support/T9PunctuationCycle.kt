package app.songyan.ime.keyboard.support

import java.text.BreakIterator

/**
 * Short multi-taps replace only the punctuation inserted by the previous tap.
 *
 * A 9-key board's first key ("，。？") inserts punctuation when idle; tapping it again within
 * [INTERVAL] replaces that symbol instead of appending. Every replacement is pinned to the context
 * that was actually observed, so a write that never reached the host is never replaced.
 */
class T9PunctuationCycle {
    /** One punctuation write: [text] replaces [replacing] (null on a fresh start). */
    data class Edit(
        /** Index of [text] within the symbol list. */
        val index: Int,
        /** The symbol to insert. */
        val text: String,
        /** The previously inserted symbol this edit replaces, or null. */
        val replacing: String? = null,
        /** The document context (bounded, from the end) this edit was computed against. */
        val expectedContext: String? = null,
    )

    private data class Pending(
        val symbols: List<String>,
        val index: Int,
        val context: String,
        val timestamp: Double,
        val revision: Int,
    )

    private var pending: Pending? = null

    /**
     * The edit for tapping the punctuation key now. A repeat within [INTERVAL] with an unchanged
     * document, symbol list and revision cycles to the next symbol; everything else starts fresh.
     */
    fun next(
        symbols: List<String>,
        context: String?,
        now: Double,
        revision: Int,
    ): Edit? {
        if (symbols.isEmpty() || symbols.any { it.isEmpty() }) return null
        val previous = pending
        if (previous != null &&
            context != null &&
            previous.symbols == symbols &&
            previous.revision == revision &&
            now >= previous.timestamp &&
            now - previous.timestamp < INTERVAL &&
            context.takeLastOrWhole(CONTEXT_LIMIT) == previous.context &&
            context.endsWith(symbols[previous.index])
        ) {
            val index = (previous.index + 1) % symbols.size
            return Edit(
                index = index,
                text = symbols[index],
                replacing = symbols[previous.index],
                expectedContext = previous.context,
            )
        }
        return Edit(
            index = 0,
            text = symbols[0],
            replacing = null,
            expectedContext = context?.takeLastOrWhole(CONTEXT_LIMIT),
        )
    }

    /**
     * Arms the next tap after [edit] was written and the host context [context] was read.
     *
     * A document proxy often still shows the text from before the write; arming on the *predicted*
     * text means the next tap's fresh context must equal it, so a write that never landed is never
     * replaced. A context that changed into something else, or a bounded context that cannot tell
     * the write apart, never arms.
     */
    fun record(
        edit: Edit,
        symbols: List<String>,
        context: String?,
        now: Double,
        revision: Int,
    ) {
        reset()
        if (edit.index !in symbols.indices || symbols[edit.index] != edit.text) return
        val expected = edit.expectedContext ?: return
        val observed = context?.takeLastOrWhole(CONTEXT_LIMIT) ?: return
        val prefix = edit.replacing?.let { expected.dropLast(it.length) } ?: expected
        val predicted = (prefix + edit.text).takeLastOrWhole(CONTEXT_LIMIT)
        if (predicted == expected) return
        if (observed != predicted && observed != expected) return
        pending =
            Pending(
                symbols = symbols,
                index = edit.index,
                context = predicted,
                timestamp = now,
                revision = revision,
            )
    }

    /** Forgets the armed state (a clear, a mode switch, another key). */
    fun reset() {
        pending = null
    }

    /** The last [limit] user-perceived characters: a cut never falls inside an emoji sequence. */
    private fun String.takeLastOrWhole(limit: Int): String {
        if (length <= limit) return this
        val boundary = BreakIterator.getCharacterInstance()
        boundary.setText(this)
        var start = boundary.last()
        repeat(limit) {
            val previous = boundary.previous()
            if (previous == BreakIterator.DONE) return this
            start = previous
        }
        return substring(start)
    }

    companion object {
        /** Maximum interval between taps that still counts as a cycle. */
        const val INTERVAL: Double = 0.8

        /** Maximum document context kept for the replacement check. */
        const val CONTEXT_LIMIT: Int = 128
    }
}
