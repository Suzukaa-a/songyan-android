package app.songyan.s0probe;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 16 kHz mono PCM16 capture that reports what actually arrived, one second at a time. The
 * platform's own silence signal is recorded too: on an emulator the microphone is already quiet,
 * so sample amplitude alone cannot tell "muted because the app went to the background" from
 * "the input is silent".
 *
 * Every second also records whether the screen was interactive and the keyguard locked, because
 * on a physical device the user locks the screen by hand: the moment the stream stopped has to be
 * distinguishable from the moment the device locked. The progress callback lets the caller persist
 * the partial report after each second, so a probe frozen or killed while the screen is off still
 * leaves evidence of how far the recording got.
 */
final class Recorder {
    /** Receives the in-progress result after every complete second. */
    interface Progress {
        void onSecond(JSONObject partial);
    }

    static final int SAMPLE_RATE = 16000;
    private static final int FRAME_MS = 100;
    private static final int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000;

    private Recorder() {}

    static String permission(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                ? "granted"
                : "denied";
    }

    static boolean screenInteractive(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isInteractive();
    }

    static boolean keyguardLocked(Context context) {
        KeyguardManager keyguard =
                (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    /**
     * Whether the lock screen needs a credential. A keyguard that is showing on an insecure
     * device (swipe only) locks nothing, so the lock-screen answer needs both signals.
     */
    static boolean deviceSecure(Context context) {
        KeyguardManager keyguard =
                (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    static JSONObject run(Context context, int seconds) {
        return run(context, seconds, null);
    }

    static JSONObject run(Context context, int seconds, Progress progress) {
        JSONObject result = new JSONObject();
        J.put(result, "permission", permission(context));
        J.put(result, "source", "MIC");
        J.put(result, "sampleRate", SAMPLE_RATE);
        J.put(result, "seconds", seconds);
        J.put(result, "screenInteractiveAtStart", screenInteractive(context));
        J.put(result, "keyguardLockedAtStart", keyguardLocked(context));
        J.put(result, "deviceSecure", deviceSecure(context));
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        final JSONArray transitions = J.array();
        AudioRecord record = null;
        AudioManager.AudioRecordingCallback callback = null;
        short[] buffer = new short[FRAME_SAMPLES];
        try {
            int minBytes = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            J.put(result, "minBufferBytes", minBytes);
            long openStart = SystemClock.elapsedRealtime();
            record = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBytes, SAMPLE_RATE));
            J.put(result, "openMs", SystemClock.elapsedRealtime() - openStart);
            J.put(result, "state", record.getState());
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord not initialized");
            }
            final AudioRecord started = record;
            J.put(result, "audioSessionId", started.getAudioSessionId());
            if (manager != null) {
                J.put(result, "microphoneMute", manager.isMicrophoneMute());
                callback =
                        new AudioManager.AudioRecordingCallback() {
                            @Override
                            public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configurations) {
                                JSONObject change = new JSONObject();
                                J.put(change, "atMs", SystemClock.elapsedRealtime());
                                J.put(change, "silenced", silenced(manager, started));
                                transitions.put(change);
                            }
                        };
                manager.registerAudioRecordingCallback(callback, new Handler(Looper.getMainLooper()));
            }
            long startStart = SystemClock.elapsedRealtime();
            record.startRecording();
            J.put(result, "startRecordingMs", SystemClock.elapsedRealtime() - startStart);
            J.put(result, "recordingState", record.getRecordingState());
            JSONArray perSecond = J.array();
            long totalFrames = 0;
            int readErrors = 0;
            for (int second = 0; second < seconds; second++) {
                long startedAt = SystemClock.elapsedRealtime();
                long frames = 0;
                int peak = 0;
                long nonzero = 0;
                while (SystemClock.elapsedRealtime() - startedAt < 1000) {
                    int read = record.read(buffer, 0, buffer.length);
                    if (read <= 0) {
                        readErrors++;
                        Thread.sleep(10);
                        continue;
                    }
                    frames += read;
                    for (int i = 0; i < read; i++) {
                        int value = Math.abs((int) buffer[i]);
                        if (value > peak) peak = value;
                        if (value != 0) nonzero++;
                    }
                }
                JSONObject stats = new JSONObject();
                J.put(stats, "second", second);
                J.put(stats, "frames", frames);
                J.put(stats, "peak", peak);
                J.put(stats, "nonzero", nonzero);
                J.put(stats, "recordingState", record.getRecordingState());
                J.put(stats, "silenced", silenced(manager, record));
                J.put(stats, "atMs", SystemClock.elapsedRealtime());
                J.put(stats, "screenInteractive", screenInteractive(context));
                J.put(stats, "keyguardLocked", keyguardLocked(context));
                perSecond.put(stats);
                totalFrames += frames;
                J.put(result, "perSecond", perSecond);
                J.put(result, "totalFrames", totalFrames);
                J.put(result, "readErrors", readErrors);
                if (progress != null) progress.onSecond(result);
            }
            J.put(result, "complete", true);
        } catch (Throwable error) {
            J.put(result, "error", J.error(error));
        } finally {
            J.put(result, "silenceTransitions", transitions);
            if (record != null) {
                try {
                    record.stop();
                } catch (Throwable ignored) {
                    // A recorder that never started throws here; the error above already says so.
                }
                try {
                    record.release();
                } catch (Throwable ignored) {
                    // Same.
                }
            }
            if (callback != null && manager != null) {
                try {
                    manager.unregisterAudioRecordingCallback(callback);
                } catch (Throwable ignored) {
                    // Nothing to undo.
                }
            }
        }
        return result;
    }

    /** The platform's answer to "is this recording being silenced right now", per session. */
    private static boolean silenced(AudioManager manager, AudioRecord record) {
        if (manager == null || record == null) return false;
        try {
            int session = record.getAudioSessionId();
            for (AudioRecordingConfiguration configuration : manager.getActiveRecordingConfigurations()) {
                if (configuration.getClientAudioSessionId() == session) {
                    return configuration.isClientSilenced();
                }
            }
        } catch (Throwable ignored) {
            // A configuration list that cannot be read says nothing about the stream.
        }
        return false;
    }
}