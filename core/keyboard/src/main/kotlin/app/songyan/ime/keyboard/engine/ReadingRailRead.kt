package app.songyan.ime.keyboard.engine

/**
 * The reads the reading rail makes for one composition state. A count of zero (or [spellings]
 * false) leaves that part out.
 */
data class ReadingRailRequest(
    val spellings: Boolean,
    val readFrom: Int,
    val readCount: Int,
    val pageFrom: Int,
    val pageCount: Int,
)

/**
 * The answers to a [ReadingRailRequest], each part failing on its own as its separate read would;
 * null for a part that was not asked for.
 */
class ReadingRailRead(
    val spellings: Result<AmbiguitySpellingsReport?>?,
    val readings: Result<List<ReadingCandidate>?>?,
    val page: Result<List<ReadingCandidate>?>?,
)
