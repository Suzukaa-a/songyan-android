package app.songyan.ime.audio

import java.io.File

/**
 * The host's policy for one voice take.
 *
 * The PCM format is fixed by what the recogniser accepts (16 kHz mono 16-bit), the same audio the
 * iOS keyboard hands over; the cap is the host's own choice. Everything a take needs to know about
 * its limits lives here, not in the recorder, so a later input path can change the cap without
 * touching the capture code.
 */
data class VoiceRecordingPolicy(
    /**
     * Provisional: the P10 plan gives no figure and the recogniser's input limit is not known yet, so
     * one take stays inside the minute most speech services accept. Nothing else depends on this
     * value; revisit it when the recogniser is chosen.
     */
    val maxDurationMs: Long = PROVISIONAL_MAX_DURATION_MS,
) {
    init {
        require(maxDurationMs > 0) { "a take needs a positive cap, was $maxDurationMs ms" }
    }

    /** The WAV layout every take is recorded and written in. */
    val format: WavFormat = WavFormat(SAMPLE_RATE_HZ, CHANNELS, BITS_PER_SAMPLE)

    /** How many PCM bytes the cap takes at this format. */
    val maxDataBytes: Long = format.bytesFor(maxDurationMs)

    companion object {
        /** 16 kHz is what speech recognition takes, and what the iOS keyboard records. */
        const val SAMPLE_RATE_HZ = 16_000

        const val CHANNELS = 1

        const val BITS_PER_SAMPLE = 16

        const val PROVISIONAL_MAX_DURATION_MS = 60_000L

        val DEFAULT = VoiceRecordingPolicy()
    }
}

/** The PCM layout of a take; only formats the recorder actually opens are buildable. */
data class WavFormat(
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int,
) {
    init {
        require(sampleRateHz > 0 && channels > 0 && bitsPerSample % 8 == 0) {
            "not a PCM format: $sampleRateHz Hz, $channels channels, $bitsPerSample bits"
        }
    }

    /** Bytes per sample frame, the unit the WAV header and the take length count in. */
    val blockAlign: Int = channels * bitsPerSample / 8

    /** Bytes one second of this format takes. */
    val byteRate: Int = sampleRateHz * blockAlign

    /** How many bytes [durationMs] of this format take, rounded down to a whole frame. */
    fun bytesFor(durationMs: Long): Long = byteRate * durationMs / 1_000
}

/** Why a take did not start, or ended before the caller stopped it. */
enum class VoiceRecordingError {
    /** RECORD_AUDIO is not granted to the app. */
    NO_PERMISSION,

    /** The device reports no microphone at all. */
    NO_MICROPHONE,

    /** The microphone exists but the system would not give it to this take, usually because another
     * capture holds it. */
    DEVICE_BUSY,

    /** Reading or writing the take failed after it started; its file is discarded. */
    RECORDING_FAILED,
}

/** What [VoiceRecorder.start] did. */
sealed interface VoiceRecordingStart {
    data object Started : VoiceRecordingStart

    data class Rejected(
        val error: VoiceRecordingError,
    ) : VoiceRecordingStart
}

/** What [VoiceRecorder.stop] reports: the finished take, or why there is none. */
sealed interface VoiceRecordingOutcome {
    data class Recorded(
        val clip: VoiceClip,
    ) : VoiceRecordingOutcome

    data class Failed(
        val error: VoiceRecordingError,
    ) : VoiceRecordingOutcome

    /** Nothing was recording. */
    data object NotRecording : VoiceRecordingOutcome
}

/** Who ended a take. */
enum class VoiceTakeEnd {
    /** The caller asked [VoiceRecorder.stop]. */
    STOPPED_BY_CALLER,

    /** The host's [VoiceRecordingPolicy.maxDurationMs] cap ended the take by itself. */
    REACHED_LIMIT,
}

/**
 * One finished take, written in the app cache.
 *
 * The file belongs to the caller: delete it once the recogniser has read it, or when the user
 * abandons that step. [frames] is zero only when the caller stopped before the first chunk arrived.
 */
data class VoiceClip(
    val file: File,
    val format: WavFormat,
    val frames: Long,
    val durationMs: Long,
    val end: VoiceTakeEnd,
) {
    /** PCM bytes after the WAV header; [file] is that plus [WavFile.HEADER_BYTES]. */
    val dataBytes: Long get() = frames * format.blockAlign
}
