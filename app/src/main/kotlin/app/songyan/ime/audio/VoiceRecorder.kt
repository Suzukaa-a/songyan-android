package app.songyan.ime.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "VoiceRecorder"

private const val TAKE_WRITE_BUFFER_BYTES = 16 * 1024

/** A chunk normally returns in tens of milliseconds; this only keeps a wedged device from hanging. */
private const val AWAIT_TIMEOUT_MS = 3_000L

/**
 * Records one 16 kHz mono 16-bit PCM take at a time in the input method process and writes it as a
 * WAV file.
 *
 * The input method process is what makes this possible without a foreground service: while the
 * keyboard window is visible the process counts as foreground, which is the platform fact P10
 * relies on. The caller stops the take when the keyboard hides, and nothing here outlives the
 * process.
 *
 * [start] opens the microphone on the calling thread (a fast platform call, safe on the main
 * thread) and captures on its own thread; [stop] joins that thread and patches the WAV header. A
 * take ends by itself at [VoiceRecordingPolicy.maxDurationMs], and such a finished take is still
 * collected with [stop]. Only one take runs at a time; keep one recorder and reuse it.
 */
class VoiceRecorder(
    private val context: Context,
    private val policy: VoiceRecordingPolicy = VoiceRecordingPolicy.DEFAULT,
) {
    private val lock = Any()
    private var take: Take? = null
    private var takeCount = 0

    /** Whether audio is being captured right now. */
    val isRecording: Boolean
        get() = synchronized(lock) { take?.isCapturing == true }

    /** How long the current or just finished take ran, in milliseconds; 0 when there is none. */
    fun elapsedMs(): Long = synchronized(lock) { take?.elapsedMs() ?: 0L }

    /**
     * Opens the microphone and starts capturing.
     *
     * Every result except [VoiceRecordingStart.Started] leaves nothing running. The caller shows the
     * matching message; for [VoiceRecordingError.NO_PERMISSION] it asks for the microphone through
     * [MicrophonePermission.requestIntent] first and starts the take again afterwards.
     */
    fun start(): VoiceRecordingStart {
        synchronized(lock) {
            check(take?.isCapturing != true) { "a voice take is already running" }
            // A take that ended at the cap and was never collected would keep its file forever.
            discardUncollected()
            if (!MicrophonePermission.isGranted(context)) {
                return VoiceRecordingStart.Rejected(VoiceRecordingError.NO_PERMISSION)
            }
            if (!hasMicrophone()) {
                return VoiceRecordingStart.Rejected(VoiceRecordingError.NO_MICROPHONE)
            }
            val microphone = openMicrophone() ?: return VoiceRecordingStart.Rejected(VoiceRecordingError.DEVICE_BUSY)
            takeCount++
            val started = Take(microphone.record, microphone.chunkBytes, newTakeFile())
            take = started
            started.start()
            return VoiceRecordingStart.Started
        }
    }

    /**
     * Ends the running take, or collects one that already ended at the cap.
     *
     * Blocks until the capture thread is done; it returns as soon as the blocking audio read is
     * released. [VoiceRecordingOutcome.NotRecording] means there was nothing to collect, so a
     * repeated stop is harmless.
     */
    fun stop(): VoiceRecordingOutcome {
        val running = synchronized(lock) { take } ?: return VoiceRecordingOutcome.NotRecording
        running.requestStop()
        val outcome = running.await()
        synchronized(lock) { if (take === running) take = null }
        return outcome
    }

    /** Ends the running take, if any, and deletes its file; the outcome is dropped. */
    fun cancel() {
        val running = synchronized(lock) { take.also { take = null } } ?: return
        running.requestStop()
        running.await()
        running.discard()
    }

    private fun discardUncollected() {
        val finished = take ?: return
        take = null
        finished.requestStop()
        finished.await()
        finished.discard()
    }

    /** The system's input device list; capture is refused before a device is even opened. */
    private fun hasMicrophone(): Boolean =
        runCatching {
            context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)
        }.getOrNull()?.isNotEmpty() == true

    /**
     * Opens the microphone, or returns null when the system refuses it. The refusal is not
     * distinguishable from here (another capture holding the microphone looks the same), so the
     * caller reports it as [VoiceRecordingError.DEVICE_BUSY].
     *
     * The caller checks RECORD_AUDIO through [MicrophonePermission] before this runs; lint cannot
     * see through that helper, and a revocation in between arrives as a SecurityException below.
     */
    @SuppressLint("MissingPermission")
    private fun openMicrophone(): Microphone? {
        val format = policy.format
        val channelMask =
            when (format.channels) {
                1 -> AudioFormat.CHANNEL_IN_MONO
                2 -> AudioFormat.CHANNEL_IN_STEREO
                else -> return null
            }
        val minBufferBytes =
            AudioRecord.getMinBufferSize(format.sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufferBytes <= 0) {
            Log.w(TAG, "the microphone cannot take ${format.sampleRateHz} Hz: $minBufferBytes")
            return null
        }
        // About a tenth of a second per read: long enough to keep the recorder off the CPU, short
        // enough that the cap ends the take within one chunk of the limit.
        val chunkBytes = maxOf(minBufferBytes, format.byteRate / 10)
        val record =
            try {
                AudioRecord
                    .Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(format.sampleRateHz)
                            .setChannelMask(channelMask)
                            .build(),
                    ).setBufferSizeInBytes(chunkBytes * 2)
                    .build()
            } catch (error: Exception) {
                Log.w(TAG, "cannot open the microphone", error)
                return null
            }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        try {
            record.startRecording()
        } catch (error: Exception) {
            Log.w(TAG, "cannot start the microphone", error)
            record.release()
            return null
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return null
        }
        return Microphone(record, chunkBytes)
    }

    /** A take the caller has not collected yet; one per microphone. */
    private fun newTakeFile(): File =
        VoiceTakeFiles.takeFile(
            File(context.cacheDir, VoiceTakeFiles.DIRECTORY),
            "${System.currentTimeMillis()}-$takeCount",
        )

    private class Microphone(
        val record: AudioRecord,
        val chunkBytes: Int,
    )

    /** One thread per take: reads PCM chunks, ends at the cap, and finalizes the WAV header. */
    private inner class Take(
        private val record: AudioRecord,
        private val chunkBytes: Int,
        private val file: File,
    ) : Thread("songyan-voice-take") {
        @Volatile
        private var stopRequested = false

        @Volatile
        private var capturing = true

        @Volatile
        private var outcome: VoiceRecordingOutcome? = null

        private var ranForMs = 0L
        private val startedAt = SystemClock.elapsedRealtime()

        val isCapturing: Boolean get() = capturing

        fun elapsedMs(): Long = if (capturing) SystemClock.elapsedRealtime() - startedAt else ranForMs

        /**
         * Asks the capture to end. Stopping the record is what releases the thread's blocking read;
         * the flag alone would only be seen after the next chunk.
         */
        fun requestStop() {
            stopRequested = true
            runCatching { record.stop() }
        }

        /** Waits for the capture thread and reports what it produced. */
        fun await(): VoiceRecordingOutcome {
            join(AWAIT_TIMEOUT_MS)
            return outcome ?: VoiceRecordingOutcome.Failed(VoiceRecordingError.RECORDING_FAILED)
        }

        fun discard() {
            file.delete()
        }

        override fun run() {
            var dataBytes = 0L
            var end = VoiceTakeEnd.STOPPED_BY_CALLER
            try {
                file.parentFile?.mkdirs()
                // A killed process cannot delete its take; a new take clears those leftovers first.
                file.parentFile?.let { VoiceTakeFiles.prune(it) }
                val output = FileOutputStream(file).buffered(TAKE_WRITE_BUFFER_BYTES)
                try {
                    output.write(WavFile.header(policy.format))
                    val buffer = ByteArray(chunkBytes)
                    val maxDataBytes = policy.maxDataBytes
                    while (!stopRequested) {
                        val remaining = maxDataBytes - dataBytes
                        if (remaining <= 0) {
                            end = VoiceTakeEnd.REACHED_LIMIT
                            break
                        }
                        val requested = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = record.read(buffer, 0, requested, AudioRecord.READ_BLOCKING)
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            dataBytes += read
                        } else if (read != 0 && !stopRequested) {
                            // A record stopped by requestStop also returns an error; only one seen
                            // while the take is still meant to run is real.
                            throw IOException("reading the microphone failed: $read")
                        }
                    }
                } finally {
                    output.close()
                }
                WavFile.patchDataBytes(file, dataBytes)
                val frames = dataBytes / policy.format.blockAlign
                ranForMs = frames * 1_000 / policy.format.sampleRateHz
                outcome =
                    VoiceRecordingOutcome.Recorded(
                        VoiceClip(
                            file = file,
                            format = policy.format,
                            frames = frames,
                            durationMs = ranForMs,
                            end = end,
                        ),
                    )
            } catch (error: Throwable) {
                // The permission can be revoked mid-take; everything else is a device or file fault.
                file.delete()
                outcome =
                    if (error is SecurityException) {
                        VoiceRecordingOutcome.Failed(VoiceRecordingError.NO_PERMISSION)
                    } else {
                        VoiceRecordingOutcome.Failed(VoiceRecordingError.RECORDING_FAILED)
                    }
                Log.w(TAG, "voice take failed", error)
            } finally {
                capturing = false
                runCatching { record.stop() }
                record.release()
            }
        }
    }
}
