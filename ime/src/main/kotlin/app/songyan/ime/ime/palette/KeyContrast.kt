package app.songyan.ime.ime.palette

import app.songyan.ime.ime.KeyboardDesignTokens

/**
 * Keeps the keycaps readable as keys: a letter key must stand out from the keyboard background and
 * a function key from both, whatever palette (skin or wallpaper) supplies the colours. Material's
 * container tones carry no contrast guarantee and vendors build their own wallpaper palettes, so
 * this is the floor every palette ends up on.
 *
 * Only the fills move, and only as far as needed, so a palette that already separates is returned
 * untouched. The background gives way when the letter key cannot go any lighter or darker (a white
 * key on a light grey background), because the key itself is already at the end of the scale.
 */
internal object KeyContrast {
    /** Letter key against the keyboard background. */
    const val KEY_AGAINST_BACKGROUND = 1.3

    /** Function key against a letter key. */
    const val FUNCTION_AGAINST_KEY = 1.25

    /** Function key against the keyboard background, so it keeps its outline next to a letter key. */
    const val FUNCTION_AGAINST_BACKGROUND = 1.2

    /** The highlighted candidate's capsule against the candidate bar. */
    const val CAPSULE_AGAINST_BAR = 1.25

    fun separate(tokens: KeyboardDesignTokens): KeyboardDesignTokens {
        var background = tokens.background
        var key = tokens.keySurface
        var function = tokens.controlKey
        val lighterThanBackground = Argb.luminance(key) >= Argb.luminance(background)
        if (Argb.contrast(key, background) < KEY_AGAINST_BACKGROUND) {
            key = push(key, background, KEY_AGAINST_BACKGROUND, towardsWhite = lighterThanBackground)
            if (Argb.contrast(key, background) < KEY_AGAINST_BACKGROUND) {
                background = push(background, key, KEY_AGAINST_BACKGROUND, towardsWhite = !lighterThanBackground)
            }
        }
        if (Argb.contrast(function, key) < FUNCTION_AGAINST_KEY) {
            // A function key keeps the side of the letter key it was authored on.
            val lighter = Argb.luminance(function) > Argb.luminance(key)
            function = push(function, key, FUNCTION_AGAINST_KEY, towardsWhite = lighter)
            if (Argb.contrast(function, key) < FUNCTION_AGAINST_KEY) {
                function = push(key, key, FUNCTION_AGAINST_KEY, towardsWhite = !lighter)
            }
        }
        if (Argb.contrast(function, background) < FUNCTION_AGAINST_BACKGROUND) {
            val keyLighter = Argb.luminance(key) > Argb.luminance(background)
            val pastBackground = Argb.luminance(function) > Argb.luminance(background) != keyLighter
            // A light keyboard takes its function keys darker than the background, as iOS does.
            if (pastBackground || Argb.luminance(background) > LIGHT_BACKGROUND) {
                function = push(function, background, FUNCTION_AGAINST_BACKGROUND, towardsWhite = !keyLighter)
            }
            if (Argb.contrast(function, background) < FUNCTION_AGAINST_BACKGROUND) {
                // Between the background and the letter key: the function key moves towards the
                // letter key, the letter key moves on, and the background gives way last.
                function = push(function, background, FUNCTION_AGAINST_BACKGROUND, towardsWhite = keyLighter)
                if (Argb.contrast(function, key) < FUNCTION_AGAINST_KEY) {
                    key = push(key, function, FUNCTION_AGAINST_KEY, towardsWhite = keyLighter)
                }
                if (Argb.contrast(function, key) < FUNCTION_AGAINST_KEY) {
                    function = push(key, key, FUNCTION_AGAINST_KEY, towardsWhite = !keyLighter)
                    background = push(background, function, FUNCTION_AGAINST_BACKGROUND, towardsWhite = !keyLighter)
                }
            }
        }
        // The bar sits on the keyboard background; it follows when that moved.
        val bar = if (tokens.candidateBarBackground == tokens.background) background else tokens.candidateBarBackground
        var capsule = tokens.candidateSelectedBackground
        if (Argb.contrast(capsule, bar) < CAPSULE_AGAINST_BAR) {
            // Material's primary container sits two tones from the surface containers, so the
            // highlighted candidate, the clipboard chip and the "on" tiles read as unfilled. Like the
            // function keys, a light bar takes its capsule darker and a dark bar lighter.
            capsule = push(capsule, bar, CAPSULE_AGAINST_BAR, towardsWhite = Argb.luminance(bar) <= LIGHT_BACKGROUND)
        }
        if (background == tokens.background &&
            key == tokens.keySurface &&
            function == tokens.controlKey &&
            capsule == tokens.candidateSelectedBackground
        ) {
            return tokens
        }
        return tokens.copy(
            background = background,
            keySurface = key,
            controlKey = function,
            candidateBarBackground = bar,
            candidateSelectedBackground = capsule,
            candidateSelectedText = Argb.readableOn(tokens.candidateSelectedText, capsule),
        )
    }

    /**
     * Moves [color] towards white or black until it reaches [minimum] against [other], or until it
     * runs out of range; the least movement that satisfies the ratio.
     */
    private fun push(
        color: Long,
        other: Long,
        minimum: Double,
        towardsWhite: Boolean,
    ): Long {
        val end = if (towardsWhite) Argb.WHITE else Argb.BLACK
        if (Argb.contrast(end, other) < minimum) return end
        var low = 0.0
        var high = 1.0
        repeat(STEPS) {
            val mid = (low + high) / 2
            if (Argb.contrast(Argb.blend(color, end, mid), other) >= minimum) high = mid else low = mid
        }
        return Argb.blend(color, end, high)
    }

    private const val STEPS = 20

    /** Relative luminance above which a background counts as light (about L* 50). */
    private const val LIGHT_BACKGROUND = 0.18
}
