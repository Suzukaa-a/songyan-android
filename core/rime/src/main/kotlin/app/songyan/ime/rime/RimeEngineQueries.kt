package app.songyan.ime.rime

// Read-only engine queries whose bodies need only the session handle. They run on the engine
// thread (the caller wraps them in RimeDispatcher.onEngine).

internal fun readCandidatesOnEngine(
    session: Long,
    fromIndex: Int,
    count: Int,
    includeReadings: Boolean,
): List<RimeReadCandidate>? {
    val flat = RimeNative.nativeReadCandidates(session, fromIndex, count) ?: return null
    val metadata =
        RimeNative.nativeContextMetadataJson(session)?.let { RimePageSelectMetadata.fromJson(it) }
    return flat.toList().chunked(2).mapIndexed { offset, pair ->
        val absolute = fromIndex + offset
        val reading =
            if (includeReadings) {
                RimeNative.nativeCandidateSyllablesJson(session, absolute)?.let {
                    RimeCandidateSyllables.fromJson(it)
                }
            } else {
                null
            }
        val slot = if (metadata != null && metadata.pageSize > 0) absolute % metadata.pageSize else -1
        RimeReadCandidate(
            absoluteIndex = absolute,
            text = pair.getOrNull(0).orEmpty(),
            comment = pair.getOrNull(1).orEmpty(),
            readingPaths = reading?.paths,
            readingsComplete = reading?.isComplete ?: false,
            selectKey = if (slot >= 0) metadata?.selectKey(slot) else null,
            selectLabel = if (slot >= 0) metadata?.selectLabel(slot) else null,
        )
    }
}

internal fun readSchemaValues(
    schemaId: String,
    keys: List<String>,
): Map<String, String>? {
    // The bridge omits pairs for keys that do not exist, so the array must be
    // decoded by the returned key; positional decoding would hand a present
    // key's value to an earlier missing key.
    val flat = RimeNative.nativeSchemaValues(schemaId, keys.toTypedArray()) ?: return null
    return RimeNative.decodeSchemaValues(flat)
}

internal fun readSchemaStringList(
    schemaId: String,
    key: String,
): List<String>? = RimeNative.nativeSchemaStringList(schemaId, key)?.filterNotNull()

/** Ambiguity-spelling calls with argument validation done before the native call. */
internal object RimeAmbiguityBridge {
    fun report(
        session: Long,
        maxItems: Int,
    ): RimeAmbiguitySpellings? {
        val normalized =
            when {
                maxItems <= 0 -> RimeAmbiguitySpellings.DEFAULT_LIMIT
                maxItems > RimeAmbiguitySpellings.HARD_LIMIT -> RimeAmbiguitySpellings.HARD_LIMIT
                else -> maxItems
            }
        return RimeNative
            .nativeAmbiguitySpellingsJson(session, normalized)
            ?.let { RimeAmbiguitySpellings.fromJson(it) }
    }

    fun apply(
        session: Long,
        expectedInput: String,
        segmentStart: Int,
        segmentEnd: Int,
        spelling: String,
    ): RimeAmbiguityApplyResult {
        if (segmentStart < 0 ||
            segmentEnd <= segmentStart ||
            segmentEnd > expectedInput.toByteArray(Charsets.UTF_8).size
        ) {
            return RimeAmbiguityApplyResult.INVALID
        }
        return RimeAmbiguityApplyResult.fromCode(
            RimeNative.nativeApplyAmbiguitySpelling(
                session,
                expectedInput,
                segmentStart,
                segmentEnd,
                spelling,
            ),
        )
    }

    fun restore(
        session: Long,
        expectedInput: String,
        segmentStart: Int,
        segmentEnd: Int,
        expectedSpelling: String,
        reducedCode: String,
    ): RimeAmbiguityApplyResult {
        if (segmentStart < 0 ||
            segmentEnd <= segmentStart ||
            segmentEnd > expectedInput.toByteArray(Charsets.UTF_8).size ||
            expectedSpelling.isEmpty() ||
            reducedCode.isEmpty()
        ) {
            return RimeAmbiguityApplyResult.INVALID
        }
        return RimeAmbiguityApplyResult.fromCode(
            RimeNative.nativeRestoreAmbiguitySpelling(
                session,
                expectedInput,
                segmentStart,
                segmentEnd,
                expectedSpelling,
                reducedCode,
            ),
        )
    }
}
