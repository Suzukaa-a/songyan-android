package app.songyan.s0probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/**
 * Cross-component flags. Every probe component runs in the probe process, so a static field is
 * the shortest and most reliable way to hand a scenario to the input method service, whose
 * lifecycle the host cannot call into directly.
 *
 * A scenario is persisted because on a physical device the control activity is started and
 * finishes, and the user then opens their own app by hand. Persisted scenarios obey three rules,
 * so nothing is ever collected that the user did not just ask for:
 *
 *  - it names the editor package it is for; the input method ignores every other app;
 *  - it belongs to the process that was handed it: a restart means the step is over, and the
 *    scenario is cleared instead of firing later on its own;
 *  - it is claimed by the first field it runs on and is cleared immediately, so one command
 *    measures at most one editor and a later field finds nothing.
 */
final class Control {
    private static final String FILE = "control";

    static volatile String runId = "adhoc";
    static volatile int markerLength = 4000;
    static volatile boolean measure = false;
    static volatile boolean record = false;
    /** The editor package this scenario is for; the input method collects nothing without one. */
    static volatile String targetEditor = "";
    static volatile int recordSeconds = 3;
    static volatile int recordDelayMs = 0;
    /** Set once the scenario ran or was claimed; a finished scenario never runs again. */
    static volatile boolean finished = true;
    /** Non-zero once this run has produced its report, so the host can leave. */
    static volatile long doneAtMs = 0;

    private Control() {}

    static synchronized void load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        int pid = prefs.getInt("pid", -1);
        if (pid <= 0) {
            finished = true;
            measure = false;
            record = false;
            doneAtMs = 0;
            return;
        }
        if (pid != Process.myPid()) {
            Log.i(Report.TAG, "CONTROL-STALE handed-to=" + pid + " now=" + Process.myPid());
            clear(context);
            return;
        }
        runId = prefs.getString("run", "adhoc");
        markerLength = prefs.getInt("marker", 4000);
        measure = prefs.getBoolean("measure", false);
        record = prefs.getBoolean("record", false);
        targetEditor = prefs.getString("editor", "");
        recordSeconds = prefs.getInt("seconds", 3);
        recordDelayMs = prefs.getInt("delayMs", 0);
        finished = prefs.getBoolean("finished", true);
        doneAtMs = finished ? 1 : 0;
    }

    static synchronized void apply(
            Context context,
            String run,
            int marker,
            boolean measureContext,
            boolean recordAudio,
            String editor,
            int seconds,
            int delayMs) {
        runId = run == null || run.isEmpty() ? "adhoc" : run;
        markerLength = marker <= 0 ? 4000 : marker;
        measure = measureContext;
        record = recordAudio;
        targetEditor = editor == null ? "" : editor;
        recordSeconds = seconds <= 0 ? 3 : seconds;
        recordDelayMs = Math.max(0, delayMs);
        finished = false;
        doneAtMs = 0;
        SharedPreferences.Editor prefs =
                context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit();
        prefs.putString("run", runId);
        prefs.putInt("marker", markerLength);
        prefs.putBoolean("measure", measure);
        prefs.putBoolean("record", record);
        prefs.putString("editor", targetEditor);
        prefs.putInt("seconds", recordSeconds);
        prefs.putInt("delayMs", recordDelayMs);
        prefs.putInt("pid", Process.myPid());
        prefs.putBoolean("finished", false);
        prefs.commit();
    }

    /** Forgets the scenario; the input method then collects nothing until a step starts again. */
    static synchronized void clear(Context context) {
        runId = "adhoc";
        measure = false;
        record = false;
        targetEditor = "";
        recordSeconds = 3;
        recordDelayMs = 0;
        finished = true;
        doneAtMs = 0;
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt("pid", -1)
                .putBoolean("measure", false)
                .putBoolean("record", false)
                .putString("editor", "")
                .putBoolean("finished", true)
                .commit();
    }

    /**
     * Claims the scenario for the editor that is about to be measured and clears it, so one
     * command measures one editor and a repeated field tap collects nothing. Returns false when
     * there is nothing left to run.
     */
    static synchronized boolean claim(Context context) {
        if (finished || (!measure && !record)) return false;
        finished = true;
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt("pid", -1)
                .putBoolean("measure", false)
                .putBoolean("record", false)
                .putBoolean("finished", true)
                .commit();
        return true;
    }

    /** Records that this run already produced its report. */
    static synchronized void markDone(Context context) {
        doneAtMs = SystemClock.elapsedRealtime();
        finished = true;
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("finished", true)
                .putInt("pid", -1)
                .putBoolean("measure", false)
                .putBoolean("record", false)
                .commit();
    }
}