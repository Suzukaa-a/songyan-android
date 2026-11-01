package app.songyan.ime.settings.state

import android.net.Uri
import app.songyan.ime.packages.EngineFeatureReport.Availability

/**
 * The engine features of one input method as the keyboard's current build runs them, with the
 * user's stored choices where the build cannot show them (an imported file not yet in use).
 */
data class EngineFeaturesSnapshot(
    val schemaId: String,
    val typoCorrection: Availability = Availability.UNSUPPORTED,
    val typoCorrectionOn: Boolean = false,
    val fuzzyPinyin: Availability = Availability.UNSUPPORTED,
    /** Pair ids ([app.songyan.ime.packages.FuzzyPinyin.Pair.id]) the build mixes. */
    val fuzzyPairs: Set<String> = emptySet(),
    val prediction: Availability = Availability.UNSUPPORTED,
    val predictionOn: Boolean = false,
    /** The imported prediction database, or null. */
    val predictionDatabase: StoredFeatureFile? = null,
    val lua: Availability = Availability.UNSUPPORTED,
    val luaComponents: List<LuaComponentInfo> = emptyList(),
    val grammar: Availability = Availability.UNSUPPORTED,
    val grammarModels: List<StoredFeatureFile> = emptyList(),
    val grammarChoice: GrammarSelection = GrammarSelection.SchemeDefault,
)

data class StoredFeatureFile(
    val name: String,
    val displayName: String,
    val size: Long,
)

data class LuaComponentInfo(
    val prescription: String,
    val displayName: String,
    /** `processors`, `segmentors`, `translators` or `filters`. */
    val list: String,
    val enabled: Boolean,
)

/** Which grammar model an input method uses. */
sealed interface GrammarSelection {
    /** Whatever the scheme itself configures. */
    data object SchemeDefault : GrammarSelection

    data object Off : GrammarSelection

    data class Model(
        val name: String,
    ) : GrammarSelection
}

/** Result of one change; [message] is already localized by the app module. */
sealed interface FeatureChangeResult {
    data object Success : FeatureChangeResult

    data class Failure(
        val message: String?,
        /** Whether trying the same change again can succeed (a network failure, not a bad file). */
        val retryable: Boolean = false,
    ) : FeatureChangeResult
}

/**
 * Bridge the settings UI uses to read and change engine features. Every change returns after the
 * deploy process published a build with it; a failed change is undone.
 */
interface EngineFeaturesGateway {
    val available: Boolean

    /** The features of [schemaId] in the active build, or null when there is no readable build. */
    suspend fun load(schemaId: String): EngineFeaturesSnapshot?

    suspend fun setTypoCorrection(enabled: Boolean): FeatureChangeResult

    suspend fun setFuzzyPinyin(
        schemaId: String,
        pairs: Set<String>,
    ): FeatureChangeResult

    suspend fun setPrediction(enabled: Boolean): FeatureChangeResult

    suspend fun importPredictionDatabase(uri: Uri): FeatureChangeResult

    suspend fun setLuaComponent(
        schemaId: String,
        prescription: String,
        enabled: Boolean,
    ): FeatureChangeResult

    suspend fun importGrammarModel(uri: Uri): FeatureChangeResult

    /** Downloads a prediction database from an https link, then imports it like a picked file. */
    suspend fun importPredictionDatabaseFromUrl(
        url: String,
        progress: (TransferProgress) -> Unit = {},
    ): FeatureChangeResult

    /** Downloads a grammar model from an https link, then imports it like a picked file. */
    suspend fun importGrammarModelFromUrl(
        url: String,
        progress: (TransferProgress) -> Unit = {},
    ): FeatureChangeResult

    /** Asks the running download to stop; the change then ends with a cancelled failure. */
    fun cancelRunning() = Unit

    suspend fun setGrammar(
        schemaId: String,
        selection: GrammarSelection,
    ): FeatureChangeResult
}

class UnavailableEngineFeaturesGateway : EngineFeaturesGateway {
    override val available: Boolean = false

    override suspend fun load(schemaId: String): EngineFeaturesSnapshot? = null

    override suspend fun setTypoCorrection(enabled: Boolean) = FeatureChangeResult.Failure(null)

    override suspend fun setFuzzyPinyin(
        schemaId: String,
        pairs: Set<String>,
    ) = FeatureChangeResult.Failure(null)

    override suspend fun setPrediction(enabled: Boolean) = FeatureChangeResult.Failure(null)

    override suspend fun importPredictionDatabase(uri: Uri) = FeatureChangeResult.Failure(null)

    override suspend fun setLuaComponent(
        schemaId: String,
        prescription: String,
        enabled: Boolean,
    ) = FeatureChangeResult.Failure(null)

    override suspend fun importGrammarModel(uri: Uri) = FeatureChangeResult.Failure(null)

    override suspend fun importPredictionDatabaseFromUrl(
        url: String,
        progress: (TransferProgress) -> Unit,
    ) = FeatureChangeResult.Failure(null)

    override suspend fun importGrammarModelFromUrl(
        url: String,
        progress: (TransferProgress) -> Unit,
    ) = FeatureChangeResult.Failure(null)

    override suspend fun setGrammar(
        schemaId: String,
        selection: GrammarSelection,
    ) = FeatureChangeResult.Failure(null)
}

/** Process-wide holder; the app module installs its implementation once during Application creation. */
object EngineFeaturesHolder {
    private val fallback = UnavailableEngineFeaturesGateway()

    @Volatile
    private var installed: EngineFeaturesGateway? = null

    val current: EngineFeaturesGateway
        get() = installed ?: fallback

    fun install(gateway: EngineFeaturesGateway) {
        installed = gateway
    }
}
