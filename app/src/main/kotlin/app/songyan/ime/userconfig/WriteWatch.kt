package app.songyan.ime.userconfig

/**
 * What to do about one open writer, from the size of its temporary file and how long it has been
 * open. A writer holds the file itself, so this is checked on a timer rather than as it writes.
 */
class WriteWatch(
    private val limitBytes: Long,
    private val maxOpenMs: Long,
    private val openedAt: Long,
) {
    enum class Verdict { FINE, TOO_BIG, TOO_LONG }

    /** [TOO_BIG] and [TOO_LONG] are final: the file is emptied and the write is discarded. */
    fun check(
        size: Long,
        now: Long,
    ): Verdict =
        when {
            now - openedAt >= maxOpenMs -> Verdict.TOO_LONG
            size > limitBytes -> Verdict.TOO_BIG
            else -> Verdict.FINE
        }
}
