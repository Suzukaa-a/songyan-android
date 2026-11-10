package app.songyan.ime.packages

/**
 * Reads the `require` calls of a Lua source lexically. Comments and string literals are skipped
 * whole, so text inside them never names a dependency; a real call still names one when whitespace,
 * comments or parentheses sit between its tokens. Only a string-literal argument counts: a computed
 * argument cannot be resolved to a file, and a name outside the module path characters is left out
 * so the set of accepted names stays what the installer has always accepted.
 *
 * The scan walks the source characters rather than its UTF-8 bytes: Lua's own syntax is ASCII, and a
 * multibyte character is never equal to a quote, backslash, bracket or line break, so text inside
 * strings and comments is skipped just as safely.
 */
internal object LuaRequireScanner {
    /** Module names required by string literals, in source order. */
    fun modules(source: String): List<String> {
        val cursor = Cursor(source)
        val modules = ArrayList<String>()
        while (true) {
            val name = cursor.nextName() ?: break
            if (name != "require") continue
            cursor.requiredModule()?.let { modules.add(it) }
        }
        return modules
    }

    /**
     * A forward-only reader over one Lua source. Lua's lexer is small enough to follow directly:
     * names, short and long strings, short and long comments, and punctuation are all it has to tell
     * apart.
     */
    private class Cursor(
        private val source: String,
    ) {
        private var index = 0

        /** The next Lua name, skipping comments, strings and punctuation. */
        fun nextName(): String? {
            while (index < source.length) {
                if (isCommentStart(index)) {
                    skipComment()
                    continue
                }
                if (isQuote(source[index])) {
                    skipShortString()
                    continue
                }
                if (source[index] == OPEN_BRACKET && longBracketLevel(index) != null) {
                    skipLongBracket()
                    continue
                }
                if (isNameStart(source[index])) {
                    val start = index
                    index += 1
                    while (index < source.length && isNamePart(source[index])) index += 1
                    return source.substring(start, index)
                }
                index += 1
            }
            return null
        }

        /** The module a `require` call names, when its argument is a plain string literal. */
        fun requiredModule(): String? {
            skipTrivia()
            if (index < source.length && source[index] == OPEN_PAREN) {
                index += 1
                skipTrivia()
            }
            val literal = readStringLiteral() ?: return null
            index = literal.end
            if (literal.text.isEmpty() || !literal.text.all { isModuleChar(it) }) return null
            return literal.text
        }

        private fun skipTrivia() {
            while (index < source.length) {
                if (isWhitespace(source[index])) {
                    index += 1
                    continue
                }
                if (isCommentStart(index)) {
                    skipComment()
                    continue
                }
                return
            }
        }

        private fun skipComment() {
            index += 2
            if (index < source.length && source[index] == OPEN_BRACKET && longBracketLevel(index) != null) {
                skipLongBracket()
                return
            }
            while (index < source.length && source[index] != LINE_FEED && source[index] != CARRIAGE_RETURN) {
                index += 1
            }
        }

        private fun skipShortString() {
            val quote = source[index]
            index += 1
            while (index < source.length) {
                val char = source[index]
                if (char == BACKSLASH) {
                    index += 2
                    continue
                }
                if (char == quote) {
                    index += 1
                    return
                }
                if (char == LINE_FEED || char == CARRIAGE_RETURN) return
                index += 1
            }
        }

        private fun skipLongBracket() {
            val bracket = readLongBracket(index)
            index = if (bracket == null) index + 1 else bracket.end
        }

        /** The literal's text and the position after it, when this is a plain string literal. */
        private fun readStringLiteral(): LuaLiteral? {
            if (index >= source.length) return null
            if (source[index] == OPEN_BRACKET) return readLongBracket(index)
            if (!isQuote(source[index])) return null
            val quote = source[index]
            var cursor = index + 1
            val text = StringBuilder()
            while (cursor < source.length) {
                val char = source[cursor]
                if (char == quote) return LuaLiteral(text.toString(), cursor + 1)
                // An escape or a raw line break means the argument is not a plain module name; leave
                // the call unreported rather than guessing what it loads.
                if (char == BACKSLASH || char == LINE_FEED || char == CARRIAGE_RETURN) return null
                text.append(char)
                cursor += 1
            }
            return null
        }

        /** The content of a long string `[=*[` ... `]=*]` and the position after it. */
        private fun readLongBracket(position: Int): LuaLiteral? {
            val level = longBracketLevel(position) ?: return null
            val contentStart = position + level + 2
            var cursor = contentStart
            while (cursor < source.length) {
                if (source[cursor] == CLOSE_BRACKET) {
                    val close = longBracketEnd(cursor, level)
                    if (close != null) return LuaLiteral(source.substring(contentStart, cursor), close)
                }
                cursor += 1
            }
            return LuaLiteral(source.substring(contentStart), source.length)
        }

        /** The level of a long bracket opening `[=*[` at [position], or null when it is an index. */
        private fun longBracketLevel(position: Int): Int? {
            if (position >= source.length || source[position] != OPEN_BRACKET) return null
            var cursor = position + 1
            var level = 0
            while (cursor < source.length && source[cursor] == EQUALS_SIGN) {
                level += 1
                cursor += 1
            }
            if (cursor >= source.length || source[cursor] != OPEN_BRACKET) return null
            return level
        }

        /** The position after the closing `]=*]` of the long bracket opening before it. */
        private fun longBracketEnd(
            position: Int,
            level: Int,
        ): Int? {
            var cursor = position + 1
            var equals = 0
            while (cursor < source.length && source[cursor] == EQUALS_SIGN) {
                equals += 1
                cursor += 1
            }
            if (equals == level && cursor < source.length && source[cursor] == CLOSE_BRACKET) return cursor + 1
            return null
        }

        private fun isCommentStart(position: Int): Boolean {
            if (position + 1 >= source.length) return false
            return source[position] == HYPHEN && source[position + 1] == HYPHEN
        }

        private fun isQuote(char: Char): Boolean = char == DOUBLE_QUOTE || char == SINGLE_QUOTE

        private fun isNameStart(char: Char): Boolean =
            char == UNDERSCORE || (char >= 'A' && char <= 'Z') || (char >= 'a' && char <= 'z')

        private fun isNamePart(char: Char): Boolean = isNameStart(char) || (char >= '0' && char <= '9')

        /** The characters a module path may use, matching what the installer has always accepted. */
        private fun isModuleChar(char: Char): Boolean = isNamePart(char) || char == '.' || char == '/' || char == '-'

        private fun isWhitespace(char: Char): Boolean =
            char == ' ' || char == '\t' || char == '\n' || char == '\u000B' || char == '\u000C' || char == '\r'

        private companion object {
            const val HYPHEN = '-'
            const val OPEN_PAREN = '('
            const val OPEN_BRACKET = '['
            const val CLOSE_BRACKET = ']'
            const val EQUALS_SIGN = '='
            const val UNDERSCORE = '_'
            const val DOUBLE_QUOTE = '"'
            const val SINGLE_QUOTE = '\''
            const val BACKSLASH = '\\'
            const val LINE_FEED = '\n'
            const val CARRIAGE_RETURN = '\r'
        }
    }
}

/** A plain string literal's text and the position after it in the source. */
private class LuaLiteral(
    val text: String,
    val end: Int,
)
