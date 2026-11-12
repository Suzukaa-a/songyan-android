package app.songyan.ime.script

/** A stored script, as the list and the confirmation page see it. The source bytes never travel
 * with the metadata: reading them requires [ScriptRegistry.source], so a settings list can be
 * built from the cache without starting the proxy process or touching the engine. */
data class ScriptEntry(
    val id: String,
    val name: String,
    val description: String?,
    val enabled: Boolean,
    /** Position in the panel; 0 first. Reordering rewrites the whole order. */
    val order: Int,
    val apiVersion: Int,
    val connects: List<String>,
    val needsAudio: Boolean,
    val availableOnThisHost: Boolean,
    /** True when the stored declarations changed since the user last confirmed them. */
    val confirmationPending: Boolean,
    val settings: List<ScriptSettingField>,
    val sourceOrigin: ScriptSourceOrigin,
)

/** One @setting or @secret of the header, in declaration order (the settings page shows it). */
data class ScriptSettingField(
    val key: String,
    val kind: ScriptSettingKind,
    val label: String,
    val secret: Boolean,
)

enum class ScriptSettingKind { TEXT, URL, CHOICE }

/** Where the source came from; "check for updates" needs the link, exports need the bytes. */
sealed interface ScriptSourceOrigin {
    data object Pasted : ScriptSourceOrigin

    /** SAF-picked file or one opened from another app. */
    data class File(
        val displayName: String,
    ) : ScriptSourceOrigin

    /** HTTPS link; [url] is what was fetched, [updateUrl] what "check update" re-fetches. */
    data class Link(
        val url: String,
        val updateUrl: String,
    ) : ScriptSourceOrigin

    data object Edited : ScriptSourceOrigin
}

/** What a header parse produced: either the declarations for the confirmation page, or a refusal
 *  the importer surfaces to the user as-is. */
sealed interface ScriptHeader {
    data class Parsed(
        val apiVersion: Int,
        val name: String,
        val description: String?,
        val connects: List<String>,
        val settings: List<ScriptSettingField>,
        val needsAudio: Boolean,
    ) : ScriptHeader

    /** [code] is the machine name from the shared contract (header/apiVersion/...). */
    data class Refused(
        val code: String,
        val line: Int,
        val message: String,
        val truncated: Boolean,
    ) : ScriptHeader
}

/** How a run ended, for the preview and the sandbox page. */
sealed interface ScriptRunOutcome {
    data class Success(
        val outputs: List<String>,
        val consoleLines: List<String>,
        val consoleTruncated: Boolean,
    ) : ScriptRunOutcome

    /** [code] is the machine name from the shared contract; the user copy comes from resources. */
    data class Failed(
        val code: String,
        val message: String,
        val line: Int,
    ) : ScriptRunOutcome

    data object Cancelled : ScriptRunOutcome
}

/** The public registry hosts use. All functions are safe to call from any process; the impl
 *  forwards to the proxy process over Binder and starts it on demand. */
interface ScriptRegistry {
    /** Cached metadata; does not start the proxy process. Callers refresh with [reload]. */
    val entries: List<ScriptEntry>

    /** True while a [reload] is in flight, for pull indicators. */
    val refreshing: Boolean

    /** Re-reads the catalog from storage. Completes before returning. */
    suspend fun reload()

    /** Parses a candidate source (paste bytes, file bytes, edited text) without storing it. */
    suspend fun inspect(source: ByteArray): ScriptHeader

    /** Stores the source and returns the new entry with [ScriptEntry.confirmationPending] = true.
     *  Storing does not confirm: the user must confirm before the script becomes enabled. */
    suspend fun add(
        source: ByteArray,
        origin: ScriptSourceOrigin,
    ): ScriptEntry

    /** Downloads an HTTPS link once, then behaves like [add] with a [ScriptSourceOrigin.Link]. */
    suspend fun addFromLink(url: String): ScriptEntry

    /** Records the user's confirmation of the current declarations and the filled settings. The
     *  first confirmation also enables the script (as the iOS store does); re-confirming an update
     *  keeps the user's enable choice. */
    suspend fun confirm(
        id: String,
        settings: Map<String, String>,
        clearSecrets: Set<String> = emptySet(),
    ): ScriptEntry

    /** Updates settings or secrets; the script stays confirmed unless declarations changed.
     *  A blank secret value keeps the stored secret; [clearSecrets] names the ones to delete. */
    suspend fun updateSettings(
        id: String,
        settings: Map<String, String>,
        clearSecrets: Set<String> = emptySet(),
    ): ScriptEntry

    /** The saved values of the non-secret settings, keyed by declaration key. Secrets never leave
     *  the proxy process: a blank secret field in [confirm] or [updateSettings] keeps what is
     *  stored, and only [clearSecrets] deletes it. */
    suspend fun settings(id: String): Map<String, String>

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ): ScriptEntry

    suspend fun reorder(orderedIds: List<String>)

    /** The stored source bytes, verbatim; used by export/share and the in-app editor. */
    suspend fun source(id: String): ByteArray?

    /** The export payload: the original bytes. Secrets are never included. */
    suspend fun export(id: String): ByteArray?

    /** Re-fetches a link origin; returns the new entry when the declarations changed, requiring a
     *  fresh confirmation, or the unchanged one when they did not. */
    suspend fun checkUpdate(id: String): ScriptEntry

    suspend fun delete(id: String)

    /** Trial run in the sandbox page: the input text, the panel's outputs and console lines. */
    suspend fun runTrial(
        id: String,
        text: String,
    ): ScriptRunOutcome

    /** Runs an enabled script for the keyboard panel; same shape as [runTrial]. */
    suspend fun run(
        id: String,
        text: String,
    ): ScriptRunOutcome

    /** Runs a stored script that declares `@input audio`. [wav] is the recorded 16 kHz mono WAV
     *  the script receives as an ArrayBuffer; the host caps the whole file at 1_920_044 bytes (one
     *  minute of 16 kHz mono PCM plus the 44-byte WAV header, provisional until S4's device numbers
     *  land) and answers
     *  `audioTooLarge` above that, `capabilityUnavailable` when no audio arrived, and
     *  `inputMismatch` for a script whose header does not declare audio. The run still happens
     *  in the isolated process, like [run]. */
    suspend fun runAudio(
        id: String,
        wav: ByteArray,
    ): ScriptRunOutcome

    /** Cancels the in-flight run of [id], if any. */
    suspend fun cancel(id: String)
}
