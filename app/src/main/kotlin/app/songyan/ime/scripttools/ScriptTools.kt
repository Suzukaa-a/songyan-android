package app.songyan.ime.scripttools

import app.songyan.ime.ime.tools.ToolCatalog
import app.songyan.ime.ime.tools.ToolExecutor
import app.songyan.ime.script.ScriptEntry
import app.songyan.ime.script.ScriptRegistry
import app.songyan.ime.tools.ToolCapability
import app.songyan.ime.tools.ToolDescriptor
import app.songyan.ime.tools.ToolLimits
import kotlinx.coroutines.CancellationException

/**
 * Host policy for one tool run. These limits belong to the keyboard's tool state machine, not to
 * the script C contract, so they are not shared values: they match the iOS ToolKit provisional
 * numbers so both platforms behave alike, and are tuned here after measurement.
 */
object ToolPipelineLimits {
    val PRODUCTION =
        ToolLimits(
            maxInputUnits = 20_000,
            timeoutMillis = 4_000,
            undoWindowMillis = 30_000,
            maxResults = 3,
        )
}

/**
 * Script entries as the keyboard's text tool catalog. It only reads the metadata the host has
 * cached: no script bytes, no proxy process, no engine. A tool id is namespaced so entries from
 * another addon kind cannot collide with a script.
 *
 * Voice scripts are left out on purpose: they run only from the microphone, whose recording the
 * text tool panel cannot supply (a run started here would always fail with a missing input
 * capability). They stay visible in Settings -> Voice input as voice sources.
 */
class ScriptToolCatalog(
    private val entries: () -> List<ScriptEntry>,
) : ToolCatalog {
    override fun tools(): List<ToolDescriptor> =
        entries()
            .filter { it.enabled && it.availableOnThisHost && !it.confirmationPending && !it.needsAudio }
            .map { entry ->
                ToolDescriptor(
                    id = "script:" + entry.id,
                    title = entry.name,
                    requiredCapabilities = setOf(ToolCapability.TEXT),
                )
            }
}

/** Raised when the script host reports a refusal; the message reaches the failure preview. */
class ScriptToolRunException(
    val code: String,
    message: String,
    val line: Int,
) : IllegalStateException(if (line > 0) message + " (line " + line + ")" else message)

/**
 * Runs one script through the installed [ScriptRegistry]. The proxy and the isolated engine start
 * only here, when the user picked a tool; cancelling the panel's task also cancels the script run.
 */
class ScriptToolExecutor(
    private val registry: () -> ScriptRegistry,
) : ToolExecutor {
    override suspend fun run(
        tool: ToolDescriptor,
        input: String,
    ): List<String> {
        val id = tool.id.removePrefix(SCRIPT_PREFIX)
        val current = registry()
        try {
            return when (val outcome = current.run(id, input)) {
                is app.songyan.ime.script.ScriptRunOutcome.Success -> outcome.outputs
                is app.songyan.ime.script.ScriptRunOutcome.Failed ->
                    throw ScriptToolRunException(outcome.code, outcome.message, outcome.line)
                app.songyan.ime.script.ScriptRunOutcome.Cancelled -> throw CancellationException("script cancelled")
            }
        } catch (cancelled: CancellationException) {
            runCatching { current.cancel(id) }
            throw cancelled
        }
    }

    private companion object {
        const val SCRIPT_PREFIX = "script:"
    }
}
