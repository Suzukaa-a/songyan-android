package app.songyan.ime.ime.panels

import app.songyan.ime.content.QuickInsertDateFormat

/**
 * The quick-insert actions the keyboard settings panel triggers.
 *
 * The app process owns [app.songyan.ime.content.QuickInsertCore] - it persists the chosen format
 * and records every commit - so the keyboard only sees these three calls and never loads the
 * preferences itself. Returning false means nothing was inserted (nothing to repeat, or the
 * editor refused), which keeps the panel honest without it knowing why.
 */
interface QuickInsertHost {
    fun repeatLastInput(): Boolean

    fun insertDateFormat(format: QuickInsertDateFormat): Boolean

    /** The rows for the next panel open. */
    fun quickInsertState(): QuickInsertState
}
