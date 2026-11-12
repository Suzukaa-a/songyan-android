package app.songyan.s0probe;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes one JSON report per measurement under filesDir/reports. The host pulls the file with
 * run-as, so a long payload never depends on logcat's per-line limit.
 *
 * A recording also writes a ".partial" report after every second and removes it once the complete
 * report is on disk. On a physical device the user locks the screen in the middle of a run, and a
 * probe frozen or killed by that leaves the partial report as its evidence.
 */
final class Report {
    static final String TAG = "S0Probe";

    private Report() {}

    static JSONObject device() {
        JSONObject report = new JSONObject();
        J.put(report, "sdk", Build.VERSION.SDK_INT);
        J.put(report, "release", Build.VERSION.RELEASE);
        J.put(report, "model", Build.MODEL);
        J.put(report, "device", Build.DEVICE);
        J.put(report, "hardware", Build.HARDWARE);
        J.put(report, "abis", String.join(",", Build.SUPPORTED_ABIS));
        return report;
    }

    /** Records audio into the report and persists it after every second, then once complete. */
    static JSONObject record(Context context, String name, JSONObject report, int seconds) {
        JSONObject recording =
                Recorder.run(
                        context,
                        seconds,
                        partial -> {
                            J.put(report, "recording", partial);
                            write(context, name + ".partial", report);
                        });
        J.put(report, "recording", recording);
        J.put(report, "complete", recording.optBoolean("complete", false));
        write(context, name, report);
        remove(context, name + ".partial");
        return recording;
    }

    static void write(Context context, String name, JSONObject report) {
        File dir = new File(context.getFilesDir(), "reports");
        File target = new File(dir, name + ".json");
        File temp = new File(dir, name + ".json.tmp");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
            byte[] bytes = report.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (!temp.renameTo(target)) {
                if (!target.delete() || !temp.renameTo(target)) throw new IllegalStateException("rename failed");
            }
            Log.i(TAG, "REPORT name=" + target.getName() + " bytes=" + bytes.length);
        } catch (Exception error) {
            Log.e(TAG, "REPORT-FAILED name=" + name, error);
        }
    }

    /** Removes one of the probe's own report files; nothing outside files/reports is touched. */
    static void remove(Context context, String name) {
        File target = new File(new File(context.getFilesDir(), "reports"), name + ".json");
        if (target.isFile() && !target.delete()) {
            Log.w(TAG, "REPORT-KEEP name=" + target.getName());
        }
    }
}