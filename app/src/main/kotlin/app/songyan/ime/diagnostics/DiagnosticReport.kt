package app.songyan.ime.diagnostics

import app.songyan.ime.deploy.RequestState

/**
 * Everything a diagnostic report may contain. The type is the privacy boundary: it has no field for
 * typed text, learned words, dictionary or scheme names, links or file names, so a report cannot
 * carry them by accident. Problem codes are the fixed names the deploy process records.
 */
data class DiagnosticFacts(
    val appVersion: String,
    val buildType: String,
    val androidRelease: String,
    val sdkInt: Int,
    val device: String,
    val abis: List<String>,
    val engineVersion: String?,
    val dataVersion: String?,
    /** `builtin`, `dictionary` or `scheme`; null when nothing is published. */
    val activeKind: String?,
    val activeUsable: Boolean,
    val activeSchemaCount: Int,
    val builtinSources: Int,
    val dictionarySources: Int,
    val schemeSources: Int,
    val damagedSources: Int,
    val deployState: String,
    val freeStorageMb: Long,
    val recentRequests: List<RequestFact>,
    /** Crash-loop recoveries at engine start, newest first. */
    val engineRecoveries: List<RecoveryFact> = emptyList(),
    /** The latest Delete key events of this keyboard session, oldest first. */
    val deleteKeyEvents: List<DeleteKeyFact> = emptyList(),
)

/**
 * One Delete key event: a fixed event code, whether a composition was in progress, whether the
 * editor path trusted the known caret, and the package of the app being typed into. No text.
 */
data class DeleteKeyFact(
    val event: String,
    val composing: Boolean,
    val cachedCaret: Boolean,
    val editorPackage: String?,
    val msAgo: Long,
)

/** One engine start recovery: the kind of source that kept failing and what was done about it. */
data class RecoveryFact(
    val sourceKind: String,
    val movedLearningData: Boolean,
    val switchedToBuiltin: Boolean,
    val minutesAgo: Long,
)

/** One finished or running deploy request: its state, the problem code and how long ago it ended. */
data class RequestFact(
    val state: RequestState,
    val problem: String?,
    val minutesAgo: Long,
)

object DiagnosticReport {
    /** What the report contains, one line each; the settings dialog shows the same list. */
    fun render(facts: DiagnosticFacts): String =
        buildString {
            appendLine("Songyan diagnostic report")
            appendLine("app: ${facts.appVersion} (${facts.buildType})")
            appendLine("android: ${facts.androidRelease} (API ${facts.sdkInt})")
            appendLine("device: ${facts.device}")
            appendLine("abi: ${facts.abis.joinToString(",")}")
            appendLine("engine: ${facts.engineVersion ?: "unknown"}, data: ${facts.dataVersion ?: "unknown"}")
            appendLine(
                "active: ${facts.activeKind ?: "none"}, usable: ${facts.activeUsable}, " +
                    "input methods: ${facts.activeSchemaCount}",
            )
            appendLine(
                "sources: builtin ${facts.builtinSources}, dictionary ${facts.dictionarySources}, " +
                    "scheme ${facts.schemeSources}, damaged ${facts.damagedSources}",
            )
            appendLine("deploy: ${facts.deployState}")
            appendLine("free storage: ${facts.freeStorageMb} MB")
            appendLine("recent requests: ${if (facts.recentRequests.isEmpty()) "none" else ""}".trimEnd())
            facts.recentRequests.forEach {
                appendLine("  ${it.state} ${it.problem ?: "-"} ${it.minutesAgo} min ago")
            }
            appendLine("engine start recoveries: ${if (facts.engineRecoveries.isEmpty()) "none" else ""}".trimEnd())
            facts.engineRecoveries.forEach {
                appendLine(
                    "  ${it.sourceKind} learning moved: ${it.movedLearningData}, " +
                        "switched to builtin: ${it.switchedToBuiltin}, ${it.minutesAgo} min ago",
                )
            }
            appendLine("delete key events: ${if (facts.deleteKeyEvents.isEmpty()) "none" else ""}".trimEnd())
            facts.deleteKeyEvents.forEach {
                appendLine(
                    "  -${it.msAgo} ms ${it.event}" +
                        (if (it.composing) " composing" else "") +
                        (if (it.cachedCaret) " cached-caret" else "") +
                        " ${it.editorPackage ?: "-"}",
                )
            }
        }
}
