package app.songyan.ime.script

/** Host policy for the audio input of a run (a header that declares `@input audio`). The shared
 *  contract carries no audio size limit - only the recorder knows how much it kept - so the bound
 *  and the codes for the host's own refusals live here.
 *
 *  Audio is offered only through [ScriptRegistry.runAudio], where the caller hands over the
 *  recorded WAV and the run therefore passes the engine's audio capability bit. A text run of an
 *  audio script still fails with the contract's `capabilityUnavailable`. */
internal object ScriptAudioInput {
    /** Sixteen kilohertz, mono, sixteen-bit PCM for one minute: 16_000 * 2 * 60 bytes. This is the
     *  recorder's utterance budget, not an engine limit. Provisional: the S4 device measurements
     *  replace it, together with the iOS side of the plan. */
    const val PROVISIONAL_MAX_PCM_BYTES = 16_000 * 2 * 60

    /** The RIFF header the recorder writes before the PCM; the run hands over the whole WAV file. */
    const val WAV_HEADER_BYTES = 44

    /** The recorded WAV a run accepts: the PCM budget plus the header that precedes it. */
    const val PROVISIONAL_MAX_BYTES = PROVISIONAL_MAX_PCM_BYTES + WAV_HEADER_BYTES

    /** The shared contract's code for "this host cannot provide the input the script needs". */
    const val CAPABILITY_UNAVAILABLE = "capabilityUnavailable"

    /** Android-only code: audio was handed to a script that does not declare `@input audio`. */
    const val INPUT_MISMATCH = "inputMismatch"

    /** Android-only code: the audio is over [PROVISIONAL_MAX_BYTES]. */
    const val AUDIO_TOO_LARGE = "audioTooLarge"

    /** Refuses a [byteCount]-byte WAV file on host policy alone, or returns null when it fits. */
    fun sizeRefusal(byteCount: Long): ScriptRunOutcome.Failed? =
        when {
            byteCount <= 0L ->
                ScriptRunOutcome.Failed(
                    CAPABILITY_UNAVAILABLE,
                    "the host supplied no audio input",
                    0,
                )
            byteCount > PROVISIONAL_MAX_BYTES ->
                ScriptRunOutcome.Failed(
                    AUDIO_TOO_LARGE,
                    "the audio input is $byteCount bytes, over the $PROVISIONAL_MAX_BYTES byte limit",
                    0,
                )
            else -> null
        }

    /** Refuses a run for a stored header and [byteCount] bytes of recorded WAV, or returns null.
     *  The header decides first: audio for a text script is a caller mistake, not a missing input. */
    fun refusal(
        needsAudio: Boolean,
        byteCount: Long,
    ): ScriptRunOutcome.Failed? =
        if (!needsAudio) {
            ScriptRunOutcome.Failed(INPUT_MISMATCH, "the script does not declare @input audio", 0)
        } else {
            sizeRefusal(byteCount)
        }
}
