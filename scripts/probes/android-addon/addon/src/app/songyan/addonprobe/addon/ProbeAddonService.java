package app.songyan.addonprobe.addon;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The addon side of the probe. Two transactions matter:
 *
 *   1  record audio for a moment and report whether AudioRecord could start and produce samples;
 *      the host's binding flags and the addon's own permission are what the probe varies.
 *   2  create a SurfaceControlViewHost from the host's SurfaceView host token, hand the
 *      SurfacePackage back, and record every touch the panel receives.
 *
 * A JSON report is written next to the app for every case; the script pulls it and summarize.py
 * validates it.
 */
public class ProbeAddonService extends Service {
    private static final String TAG = "AddonProbeAddon";
    private static final int TRANSACT_RECORD_AUDIO = 1;
    private static final int TRANSACT_OPEN_PANEL = 2;
    private static final int TRANSACT_CLOSE_PANEL = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object reportLock = new Object();
    private final Map<String, Object> caseFields = new LinkedHashMap<>();
    private final List<Map<String, Object>> touches = new ArrayList<>();

    private String activeCase = "";
    private SurfaceControlViewHost panelHost;
    private PanelView panelView;
    private Binder binder;

    @Override
    public void onCreate() {
        super.onCreate();
        binder =
                new Binder() {
                    @Override
                    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                            throws android.os.RemoteException {
                        switch (code) {
                            case TRANSACT_RECORD_AUDIO:
                                {
                                    Bundle args = data.readBundle(getClass().getClassLoader());
                                    Bundle result = recordAudio(args == null ? new Bundle() : args);
                                    reply.writeNoException();
                                    reply.writeBundle(result);
                                    return true;
                                }
                            case TRANSACT_OPEN_PANEL:
                                {
                                    Bundle args = data.readBundle(getClass().getClassLoader());
                                    Bundle result = openPanel(args == null ? new Bundle() : args);
                                    reply.writeNoException();
                                    reply.writeBundle(result);
                                    return true;
                                }
                            case TRANSACT_CLOSE_PANEL:
                                {
                                    closePanel();
                                    reply.writeNoException();
                                    return true;
                                }
                            default:
                                return super.onTransact(code, data, reply, flags);
                        }
                    }
                };
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private Bundle recordAudio(Bundle args) {
        String caseName = args.getString("case", "unknown");
        int durationMs = args.getInt("durationMs", 1200);
        activeCase = caseName;
        synchronized (reportLock) {
            caseFields.clear();
            caseFields.put("permissionGranted", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
            android.app.ActivityManager.RunningAppProcessInfo processInfo =
                    new android.app.ActivityManager.RunningAppProcessInfo();
            android.app.ActivityManager.getMyMemoryState(processInfo);
            caseFields.put("importance", processInfo.importance);
            caseFields.put("importanceReasonCode", processInfo.importanceReasonCode);
            caseFields.put("appOpMode", appOpMode());
            caseFields.put("started", false);
            AudioRecord recorder = null;
            try {
                int minBuffer = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int bufferSize = Math.max(minBuffer, 4_096);
                caseFields.put("minBufferSize", minBuffer);
                caseFields.put("bufferSize", bufferSize);
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                caseFields.put("audioRecordState", recorder.getState());
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioRecord is not initialized");
                }
                recorder.startRecording();
                caseFields.put("started", true);
                short[] buffer = new short[bufferSize / 2];
                long deadline = System.currentTimeMillis() + durationMs;
                int samples = 0;
                int maxAmplitude = 0;
                int readErrors = 0;
                while (System.currentTimeMillis() < deadline) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        samples += read;
                        for (int i = 0; i < read; i++) {
                            int value = Math.abs(buffer[i]);
                            if (value > maxAmplitude) maxAmplitude = value;
                        }
                    } else {
                        readErrors++;
                        if (readErrors > 20) break;
                    }
                }
                caseFields.put("samplesRead", samples);
                caseFields.put("maxAmplitude", maxAmplitude);
                caseFields.put("readErrors", readErrors);
            } catch (Throwable failure) {
                caseFields.put("errorClass", failure.getClass().getName());
                caseFields.put("errorMessage", String.valueOf(failure.getMessage()));
            } finally {
                if (recorder != null) {
                    try {
                        if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            recorder.stop();
                        }
                    } catch (Throwable ignored) {
                        // Stopping a recorder that never started fails; the report already says so.
                    }
                    try {
                        recorder.release();
                    } catch (Throwable ignored) {
                        // Nothing else can be done in a probe.
                    }
                }
            }
            writeReport();
            Bundle reply = new Bundle();
            reply.putBoolean("started", Boolean.TRUE.equals(caseFields.get("started")));
            reply.putString("errorClass", String.valueOf(caseFields.get("errorClass")));
            reply.putString("errorMessage", String.valueOf(caseFields.get("errorMessage")));
            reply.putInt("samplesRead", intOf(caseFields.get("samplesRead")));
            reply.putInt("maxAmplitude", intOf(caseFields.get("maxAmplitude")));
            reply.putBoolean("permissionGranted", Boolean.TRUE.equals(caseFields.get("permissionGranted")));
            return reply;
        }
    }

    private int appOpMode() {
        try {
            AppOpsManager appOps = getSystemService(AppOpsManager.class);
            if (appOps == null) return -1;
            return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, android.os.Process.myUid(), getPackageName());
        } catch (Throwable failure) {
            return -1;
        }
    }

    private Bundle openPanel(Bundle args) {
        String caseName = args.getString("case", "unknown");
        activeCase = caseName;
        IBinder token = args.getBinder("hostToken");
        int displayId = args.getInt("displayId", 0);
        int width = Math.max(1, args.getInt("widthPx", 1));
        int height = Math.max(1, args.getInt("heightPx", 1));
        synchronized (reportLock) {
            caseFields.clear();
            caseFields.put("hostTokenPresent", token != null);
            caseFields.put("requestedWidthPx", width);
            caseFields.put("requestedHeightPx", height);
        }
        if (token == null) {
            caseFields.put("error", "no host token");
            writeReport();
            return new Bundle();
        }
        CountDownLatch done = new CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];
        final SurfaceControlViewHost[] hostHolder = new SurfaceControlViewHost[1];
        main.post(
                () -> {
                    try {
                        DisplayManager displayManager = getSystemService(DisplayManager.class);
                        Display display = displayManager == null ? null : displayManager.getDisplay(displayId);
                        if (display == null) {
                            throw new IllegalStateException("no display " + displayId);
                        }
                        panelHost = new SurfaceControlViewHost(ProbeAddonService.this, display, token);
                        panelView = new PanelView();
                        panelHost.setView(panelView, width, height);
                        hostHolder[0] = panelHost;
                        panelView.post(
                                () -> {
                                    synchronized (reportLock) {
                                        if (panelView != null) {
                                            caseFields.put("viewWidthPx", panelView.getWidth());
                                            caseFields.put("viewHeightPx", panelView.getHeight());
                                        }
                                        writeReport();
                                    }
                                });
                    } catch (Throwable throwable) {
                        failure[0] = throwable;
                    } finally {
                        done.countDown();
                    }
                });
        try {
            done.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Bundle reply = new Bundle();
        if (failure[0] != null || hostHolder[0] == null) {
            synchronized (reportLock) {
                caseFields.put("panelCreated", false);
                caseFields.put("error", String.valueOf(failure[0]));
                writeReport();
            }
            return reply;
        }
        SurfaceControlViewHost.SurfacePackage surfacePackage = hostHolder[0].getSurfacePackage();
        synchronized (reportLock) {
            caseFields.put("panelCreated", surfacePackage != null);
            caseFields.put("viewWidthPx", panelView == null ? -1 : panelView.getWidth());
            caseFields.put("viewHeightPx", panelView == null ? -1 : panelView.getHeight());
            writeReport();
        }
        reply.putParcelable("surfacePackage", surfacePackage);
        reply.putBoolean("panelCreated", surfacePackage != null);
        reply.putInt("viewWidthPx", panelView == null ? -1 : panelView.getWidth());
        reply.putInt("viewHeightPx", panelView == null ? -1 : panelView.getHeight());
        return reply;
    }

    private void closePanel() {
        main.post(
                () -> {
                    if (panelHost != null) {
                        try {
                            panelHost.release();
                        } catch (Throwable ignored) {
                            // The host may already be gone.
                        }
                        panelHost = null;
                    }
                    panelView = null;
                });
    }

    private static int intOf(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private void recordTouch(MotionEvent event) {
        synchronized (reportLock) {
            Map<String, Object> touch = new LinkedHashMap<>();
            touch.put("action", event.getActionMasked());
            touch.put("x", event.getX());
            touch.put("y", event.getY());
            touch.put("eventTimeMillis", event.getEventTime());
            touches.add(touch);
            caseFields.put("touchCount", touches.size());
            caseFields.put("lastTouchX", event.getX());
            caseFields.put("lastTouchY", event.getY());
            caseFields.put("touches", new ArrayList<>(touches));
            writeReport();
        }
    }

    private void writeReport() {
        synchronized (reportLock) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("probe", "addon");
            report.put("case", activeCase);
            report.put("generatedAtMillis", System.currentTimeMillis());
            report.put("sdkInt", android.os.Build.VERSION.SDK_INT);
            report.put("packageName", getPackageName());
            report.put("uid", android.os.Process.myUid());
            report.put("pid", android.os.Process.myPid());
            report.putAll(caseFields);
            File directory = getExternalFilesDir(null);
            if (directory == null) {
                Log.w(TAG, "no external files directory");
                return;
            }
            File file = new File(directory, "report-addon-" + activeCase + ".json");
            File temporary = new File(directory, "report-addon-" + activeCase + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(Json.write(report).getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                Log.w(TAG, "cannot write the addon report", failure);
                return;
            }
            if (!temporary.renameTo(file)) {
                Log.w(TAG, "cannot publish the addon report");
            }
        }
    }

    /** The addon's panel: a dark view that reports every touch it receives. */
    private final class PanelView extends View {
        private final Paint paint = new Paint();

        PanelView() {
            super(ProbeAddonService.this);
            setBackgroundColor(0xFF1B1B1F);
            paint.setColor(Color.WHITE);
            paint.setTextSize(48);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawText("addon panel", 32, 96, paint);
            canvas.drawText(getWidth() + "x" + getHeight(), 32, 160, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            recordTouch(event);
            return true;
        }
    }
}
