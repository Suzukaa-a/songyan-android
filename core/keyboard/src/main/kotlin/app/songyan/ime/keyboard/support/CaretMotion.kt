package app.songyan.ime.keyboard.support

import java.text.BreakIterator

/**
 * Where the caret goes for the edit panel's arrow keys, computed on the text around it.
 *
 * The keyboard cannot see how an editor wraps a long line, so up and down move between the lines
 * the text itself breaks (newlines). Left and right move one user-perceived character, so a family
 * or flag emoji is never split. Every function returns an offset in [text]; the caller maps it back
 * to the editor.
 */
object CaretMotion {
    fun left(
        text: String,
        index: Int,
    ): Int {
        val at = index.coerceIn(0, text.length)
        if (at == 0) return 0
        return boundaries(text).preceding(at).let { if (it == BreakIterator.DONE) 0 else it }
    }

    fun right(
        text: String,
        index: Int,
    ): Int {
        val at = index.coerceIn(0, text.length)
        if (at == text.length) return text.length
        return boundaries(text).following(at).let { if (it == BreakIterator.DONE) text.length else it }
    }

    fun lineStart(
        text: String,
        index: Int,
    ): Int = text.lastIndexOf('\n', (index.coerceIn(0, text.length)) - 1) + 1

    fun lineEnd(
        text: String,
        index: Int,
    ): Int = text.indexOf('\n', index.coerceIn(0, text.length)).let { if (it < 0) text.length else it }

    /** The same column one line up (clamped to that line's length); stays put on the first line. */
    fun up(
        text: String,
        index: Int,
    ): Int {
        val at = index.coerceIn(0, text.length)
        val start = lineStart(text, at)
        if (start == 0) return at
        val previousStart = lineStart(text, start - 1)
        val target = minOf(previousStart + (at - start), start - 1)
        return snap(text, target)
    }

    /** The same column one line down (clamped to that line's length); stays put on the last line. */
    fun down(
        text: String,
        index: Int,
    ): Int {
        val at = index.coerceIn(0, text.length)
        val end = lineEnd(text, at)
        if (end >= text.length) return at
        val nextStart = end + 1
        val nextEnd = lineEnd(text, nextStart)
        val target = minOf(nextStart + (at - lineStart(text, at)), nextEnd)
        return snap(text, target)
    }

    /** An offset inside a character moves back to the character's start. */
    private fun snap(
        text: String,
        index: Int,
    ): Int {
        val at = index.coerceIn(0, text.length)
        val iterator = boundaries(text)
        return if (iterator.isBoundary(at)) at else iterator.preceding(at)
    }

    private fun boundaries(text: String): BreakIterator = BreakIterator.getCharacterInstance().also { it.setText(text) }
}
