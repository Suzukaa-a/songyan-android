package app.songyan.s0probe;

import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A real input method bound through BIND_INPUT_METHOD: the place the plan asks about for recording
 * and for how much text an editor hands an input method. It measures one editor per show and
 * reports it; the audio run is the same process answering whether a visible input method may record.
 *
 * On a physical device the user starts a scenario by tapping a text field by hand, so the view
 * states what is happening: a short delay before recording lets the user get ready, and the label
 * says when the microphone is live. A scenario is claimed by the first field it matches and is
 * cleared then, so a later field or another app collects nothing.
 */
public final class ProbeIme extends InputMethodService {
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView label;

    @Override
    public View onCreateInputView() {
        Control.load(this);
        label = new TextView(this);
        label.setText("S0 probe IME");
        label.setTextSize(12f);
        label.setPadding(24, 24, 24, 24);
        return label;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // The control activity may have finished and the process been restarted since it ran.
        Control.load(this);
        if (Control.finished) {
            Log.i(Report.TAG, "IME-SKIP nothing pending");
            return;
        }
        final String run = Control.runId;
        final boolean measure = Control.measure;
        final boolean record = Control.record;
        final EditorInfo editorInfo = info;
        final String target = Control.targetEditor;
        if (!measure && !record) return;
        Log.i(Report.TAG, "IME-SHOW editor="
                + (editorInfo == null ? "" : editorInfo.packageName)
                + " type=" + (editorInfo == null ? -1 : editorInfo.inputType));
        // A scenario always names the app it is for. Without one nothing is collected: the
        // automated host script names the probe's own editor, the manual flow names the app the
        // user opened, and anything else is a stray field tap.
        if (target.isEmpty()) {
            Log.i(Report.TAG, "IME-SKIP no target");
            return;
        }
        if (editorInfo == null || !target.equals(editorInfo.packageName)) {
            Log.i(Report.TAG, "IME-SKIP editor=" + (editorInfo == null ? "" : editorInfo.packageName)
                    + " target=" + target);
            return;
        }
        String ineligible = ineligible(editorInfo);
        if (ineligible != null) {
            // The product must not learn or keep text from these fields; the probe must not read
            // or record them either, and the scenario stays pending for a field that is allowed.
            Log.i(Report.TAG, "IME-SKIP ineligible=" + ineligible + " editor=" + editorInfo.packageName);
            return;
        }
        if (!busy.compareAndSet(false, true)) return;
        // One command measures one editor: claim and clear before the work starts.
        if (!Control.claim(this)) {
            busy.set(false);
            return;
        }
        stopService(new Intent(this, HoldService.class));
        final int seconds = Control.recordSeconds;
        final int delayMs = Control.recordDelayMs;
        Thread worker =
                new Thread(
                        () -> {
                            long started = SystemClock.elapsedRealtime();
                            try {
                                if (measure) {
                                    Report.write(this, "context-" + run, ContextProbe.measure(this, run, editorInfo));
                                }
                                if (record) {
                                    recordReport(run, editorInfo, seconds, delayMs);
                                }
                                Log.i(
                                        Report.TAG,
                                        "IME-DONE run=" + run + " totalMs="
                                                + (SystemClock.elapsedRealtime() - started));
                            } catch (Throwable error) {
                                Log.e(Report.TAG, "IME-PROBE-FAILED run=" + run, error);
                            } finally {
                                Control.markDone(this);
                                busy.set(false);
                            }
                        },
                        "s0-ime");
        worker.start();
    }

    /** The editors the product treats as off limits, so the probe measures nothing there. */
    private static String ineligible(EditorInfo info) {
        int type = info.inputType & InputType.TYPE_MASK_CLASS;
        if (type == InputType.TYPE_NULL) return "TYPE_NULL";
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        if (type == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            return "password";
        }
        if (type == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            return "password";
        }
        if ((info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) {
            return "no-personalized-learning";
        }
        return null;
    }

    private void recordReport(String run, EditorInfo editorInfo, int seconds, int delayMs) {
        JSONObject report = new JSONObject();
        J.put(report, "scenario", "record");
        J.put(report, "where", "ime");
        J.put(report, "run", run);
        J.put(report, "device", Report.device());
        J.put(report, "editor", editorInfo == null ? "" : editorInfo.packageName);
        J.put(report, "inputType", editorInfo == null ? -1 : editorInfo.inputType);
        J.put(report, "delayMs", delayMs);
        if (delayMs > 0) {
            show("S0 probe: recording starts in " + (delayMs / 1000) + " s");
            sleep(delayMs);
        }
        show("S0 probe: RECORDING " + seconds + " s");
        Report.record(this, "record-ime-" + name(run), report, seconds);
        show("S0 probe: done");
    }

    private void show(String text) {
        handler.post(
                () -> {
                    if (label != null) label.setText(text);
                });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static String name(String run) {
        return run == null || run.isEmpty() ? "adhoc" : run;
    }
}