package app.songyan.ime.ime

/**
 * The last Delete key events, kept in memory for the diagnostic report so a press that seemed to do
 * nothing can be explained after the fact: what the touch did, which path the press took and what
 * the editor was asked to do.
 *
 * Never records text: an event is a fixed code, two flags, the time and the editor's package name.
 * Recording writes into preallocated arrays (the package is the reference the editor info already
 * holds), so the key path allocates nothing. The lock is uncontended except while a report is read.
 */
object BackspaceTrace {
    /** What happened, one code per event; the names are what the report prints. */
    enum class Event {
        // Touch layer.
        TOUCH_DOWN_DELETE,
        TOUCH_HOLD_START,
        TOUCH_REPEAT,
        TOUCH_HOLD_STOPPED_COMPOSITION_GONE,
        TOUCH_LEFT_KEY,
        TOUCH_CANCELLED,
        TOUCH_ENDED_BY_SECOND_FINGER,
        ACCESSIBILITY_DELETE,
        CANDIDATE_BAR_DELETE,

        // Dispatch.
        DROPPED_KEYBOARD_RETIRED,
        HELD_WHILE_PREPARING,
        DROPPED_HELD_QUEUE_FULL,
        ENGINE_DELETE,
        ENGINE_NO_CHANGE,
        RAIL_CHOICE_UNDONE,
        EDITOR_DELETE,

        // Editor.
        EDITOR_NO_CONNECTION,
        EDITOR_KEY_EVENT_FOR_KEY_EVENT_EDITOR,
        EDITOR_SELECTION_REMOVED,
        EDITOR_DELETED_BEFORE_CURSOR,
        EDITOR_KEY_EVENT_NO_TEXT_BEFORE,
    }

    const val CAPACITY = 128

    private val events = Array(CAPACITY) { Event.TOUCH_DOWN_DELETE }
    private val times = LongArray(CAPACITY)
    private val composingFlags = BooleanArray(CAPACITY)
    private val caretFlags = BooleanArray(CAPACITY)
    private val packages = arrayOfNulls<String>(CAPACITY)
    private var next = 0
    private var count = 0

    /** The editor the next events belong to; set when an input starts. */
    @Volatile
    var editorPackage: String? = null

    /**
     * Records one event. [composing] is whether a composition was in progress; [cachedCaret] is true
     * when the editor path trusted the known caret instead of asking the editor for a selection.
     */
    fun record(
        event: Event,
        composing: Boolean = false,
        cachedCaret: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        val app = editorPackage
        synchronized(this) {
            val slot = next
            events[slot] = event
            times[slot] = now
            composingFlags[slot] = composing
            caretFlags[slot] = cachedCaret
            packages[slot] = app
            next = (slot + 1) % CAPACITY
            if (count < CAPACITY) count++
        }
    }

    /** One recorded event, for the report only. */
    data class Entry(
        val timeEpochMs: Long,
        val event: Event,
        val composing: Boolean,
        val cachedCaret: Boolean,
        val editorPackage: String?,
    )

    /** Oldest first. */
    fun snapshot(): List<Entry> =
        synchronized(this) {
            val start = (next - count + CAPACITY) % CAPACITY
            List(count) { i ->
                val slot = (start + i) % CAPACITY
                Entry(times[slot], events[slot], composingFlags[slot], caretFlags[slot], packages[slot])
            }
        }

    internal fun clear() =
        synchronized(this) {
            next = 0
            count = 0
            packages.fill(null)
        }
}
