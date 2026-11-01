package app.songyan.ime.layout

/**
 * Footer row geometry for an authored page, in dp, shared by the runtime and the preview.
 *
 * A materialized default footer is the same key set the built-in control row draws. Recognizing that
 * structure (actions/targets and the frozen default widths — never an id or label) keeps the
 * built-in control-row budget, so copying a page does not immediately squeeze the bottom row tuned
 * for it. Any changed width or structure falls back to the real authored relative weights; a custom
 * title, secondary label or long-press list does not change the geometry.
 *
 * Pass only the visible keys: the host removes globe keys when the system has no keyboard switch and
 * passes a policy with hasGlobe = false, so the filtered default still matches the same budget. This
 * type never adds, removes or repairs a key.
 */
data class KeyboardPageFooterGeometry(
    /** One width per input key, in dp. */
    val keyWidths: List<Double>,
    val spacing: Double,
    /** True when the keys are exactly the live default footer and the control-row budget applies. */
    val usesControlRowBudget: Boolean,
) {
    companion object {
        fun resolve(
            width: Double,
            keys: List<LayoutKey>,
            policy: KeyboardControlPolicy,
        ): KeyboardPageFooterGeometry {
            val metrics = policy.metrics(width)
            val resolvedWidth = if (width.isFinite()) maxOf(0.0, width) else 0.0
            if (keys.isEmpty()) {
                return KeyboardPageFooterGeometry(emptyList(), metrics.spacing, false)
            }
            val slots = policy.slots
            if (keys.size == slots.size && isDefaultFooter(keys, policy)) {
                return KeyboardPageFooterGeometry(
                    keyWidths = slots.map { metrics[it] },
                    spacing = metrics.spacing,
                    usesControlRowBudget = true,
                )
            }
            val available = maxOf(0.0, resolvedWidth - metrics.spacing * (keys.size - 1))
            // Normalise through the shared projection: legal but huge finite weights must not
            // overflow the sum or the per-key multiplication into NaN frames.
            val projection = LayoutRowWidthProjection.of(keys.map { it.widthWeight })
            val keyWidths =
                if (projection.keyFractions.any { it > 0.0 }) {
                    projection.keyFractions.map { available * it }
                } else {
                    List(keys.size) { available / keys.size }
                }
            return KeyboardPageFooterGeometry(keyWidths, metrics.spacing, false)
        }

        /**
         * The frozen default footer shape: globe first, then the control row's slots in order, with
         * space at width 4 and every other default key at its native budget width.
         */
        private fun isDefaultFooter(
            keys: List<LayoutKey>,
            policy: KeyboardControlPolicy,
        ): Boolean {
            val slots = policy.slots
            if (keys.size != slots.size) return false
            for ((key, slot) in keys.zip(slots)) {
                // The digit pad's space bar is one digit column, not the control row's wide one.
                val expectedWidth = if (slot == KeyboardControlPolicy.Slot.SPACE && !policy.isDigitPad) 4.0 else null
                if (key.width != expectedWidth) return false
                if (!matches(key.action, policy.keyAt(slot), policy)) return false
            }
            return true
        }

        private fun pageTargetOf(policy: KeyboardControlPolicy): LayoutPageTarget =
            when (policy.pageTarget) {
                KeyboardControlPolicy.Page.LETTERS -> LayoutPageTarget.LETTERS
                KeyboardControlPolicy.Page.NUMERIC -> LayoutPageTarget.NUMERIC
                KeyboardControlPolicy.Page.SYMBOLS -> LayoutPageTarget.SYMBOLS
            }

        private fun matches(
            action: LayoutAction,
            expected: KeyboardControlPolicy.Slot,
            policy: KeyboardControlPolicy,
        ): Boolean =
            when (expected) {
                KeyboardControlPolicy.Slot.PAGE -> action.pageSwitchTarget == pageTargetOf(policy)
                // The row pages' left switch is a return, not a language toggle.
                KeyboardControlPolicy.Slot.RETURN_TO_LETTERS ->
                    action.pageSwitchTarget == LayoutPageTarget.LETTERS
                KeyboardControlPolicy.Slot.LANGUAGE -> {
                    if (action.type != LayoutActionKind.LANGUAGE_SWITCH) {
                        false
                    } else if (policy.page == KeyboardControlPolicy.Page.SYMBOLS &&
                        policy.usesCategorySymbolPage
                    ) {
                        // The categorized default return keeps the native language/tools menu, so it
                        // may be an explicit letters language key as well as the page-following form.
                        action.languageSwitchBehavior == null ||
                            action.languageSwitchBehavior == LayoutLanguageSwitchBehavior.LETTERS
                    } else {
                        action.languageSwitchBehavior == null
                    }
                }
                KeyboardControlPolicy.Slot.GLOBE -> action.type == LayoutActionKind.GLOBE
                KeyboardControlPolicy.Slot.SPACE -> action.type == LayoutActionKind.SPACE
                KeyboardControlPolicy.Slot.ENTER -> action.type == LayoutActionKind.ENTER
                KeyboardControlPolicy.Slot.SYMBOL_PAGE ->
                    action.pageSwitchTarget == LayoutPageTarget.SYMBOLS
                KeyboardControlPolicy.Slot.SYMBOL_LOCK ->
                    action.type == LayoutActionKind.SYMBOL_LOCK
                // The digit pad's fixed 0 key, or the comma of the letter and row pages.
                KeyboardControlPolicy.Slot.UTILITY ->
                    if (action.type != LayoutActionKind.LITERAL) {
                        false
                    } else if (policy.utility is KeyboardControlPolicy.Utility.Digit) {
                        action.value == "0"
                    } else {
                        true
                    }
            }
    }
}
