package app.songyan.s0probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

/**
 * A microphone-type foreground service: the plan asks whether recording outside the visible input
 * method needs one. The service reports whether the platform let it start foreground and what the
 * audio stream did, including while the screen is off.
 */
public final class MicService extends Service {
    private static final int NOTIFICATION_ID = 0x5AB0;
    private static final String CHANNEL = "s0-probe";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final int seconds = intent == null ? 6 : intent.getIntExtra("seconds", 6);
        final String run = intent == null ? "adhoc" : intent.getStringExtra("run");
        final String plan = intent == null ? "" : intent.getStringExtra("plan");
        final JSONObject report = new JSONObject();
        J.put(report, "scenario", "record");
        J.put(report, "where", "foreground-service");
        J.put(report, "plan", plan);
        J.put(report, "run", run);
        J.put(report, "device", Report.device());
        String startError = null;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(
                        new NotificationChannel(CHANNEL, "S0 Probe", NotificationManager.IMPORTANCE_LOW));
            }
            Notification notification =
                    new Notification.Builder(this, CHANNEL)
                            .setSmallIcon(android.R.drawable.stat_sys_download)
                            .setContentTitle("S0 Probe")
                            .setContentText("microphone foreground service")
                            .setOngoing(true)
                            .build();
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (Throwable error) {
            startError = J.error(error);
            Log.w(Report.TAG, "FGS-START-FAILED " + startError);
        }
        J.put(report, "foreground", startError == null);
        if (startError != null) {
            J.put(report, "startError", startError);
            Report.write(this, "record-fgs-" + name(run), report);
            stopSelf();
            return START_NOT_STICKY;
        }
        final int delayMs = intent == null ? 0 : intent.getIntExtra("delayMs", 0);
        J.put(report, "delayMs", delayMs);
        Thread worker =
                new Thread(
                        () -> {
                            if (delayMs > 0) {
                                try {
                                    Thread.sleep(delayMs);
                                } catch (InterruptedException error) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            Report.record(this, "record-fgs-" + name(run), report, seconds);
                            Log.i(Report.TAG, "FGS-DONE run=" + run);
                            stopSelf();
                        },
                        "s0-fgs");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String name(String run) {
        return run == null || run.isEmpty() ? "adhoc" : run;
    }
}