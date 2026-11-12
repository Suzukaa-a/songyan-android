package app.songyan.ime.tools

/** What a tool needs from the host. Scripts, built-in modules and add-ons declare the same. */
enum class ToolCapability {
    TEXT,
    AUDIO,
}

/**
 * A runnable tool as the keyboard sees it. A tool lists the capabilities it requires; the host
 * answers with a [CapabilityTable]. The model compares the two and never treats a tool's own
 * declaration as proof that the host can run it.
 */
data class ToolDescriptor(
    val id: String,
    val title: String,
    val requiredCapabilities: Set<ToolCapability>,
)

/**
 * One keyboard session's run identities. The host creates one object per session and passes it to
 * every [ToolRun.begin]; run ids are the session's opaque id plus a sequence this object hands out,
 * so a callback from a task started in an earlier session can never carry an id this session issued.
 */
class ToolSession(
    val id: String,
) {
    private var sequence = 0L

    internal fun nextRunId(): String {
        sequence += 1
        return id + "#" + sequence
    }
}

/** The host's capability table; a missing requirement makes the tool unavailable, never assumed. */
class CapabilityTable(
    availableCapabilities: Set<ToolCapability>,
) {
    val availableCapabilities: Set<ToolCapability> = availableCapabilities.toSet()

    fun missing(required: Set<ToolCapability>): Set<ToolCapability> = required - availableCapabilities
}
