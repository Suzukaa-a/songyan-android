package app.songyan.s0probe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * Shows one editor and raises the probe input method, which then measures what the editor
 * reports. The plain EditText and the real WebView stand in for the two editor families the plan
 * asks about; a real app (Chrome, WeChat) is measured by hand with manual.sh. The password,
 * TYPE_NULL and no-personalized-learning editors exist for the behaviour test only.
 */
public final class HostActivity extends Activity {
    private View editor;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        // The host script may have handed the probe a scenario already (a recording, or a manual
        // step the user started): the field is then just the editor that runs it.
        Control.load(this);
        if (Control.finished) {
            Control.apply(
                    this,
                    intent.getStringExtra("run"),
                    intent.getIntExtra("marker", Control.markerLength),
                    intent.getBooleanExtra("measure", false),
                    intent.getBooleanExtra("record", false),
                    getPackageName(),
                    3,
                    0);
        } else {
            Log.i(Report.TAG, "HOST-PENDING run=" + Control.runId + " target=" + Control.targetEditor);
        }
        final long hideAfterMs = intent.getLongExtra("hideAfterMs", 0);
        String kind = intent.getStringExtra("editor");
        if (kind == null) kind = "edittext";
        FrameLayout root = new FrameLayout(this);
        setContentView(root);
        if ("webview".equals(kind)) {
            final WebView web = new WebView(this);
            web.getSettings().setJavaScriptEnabled(true);
            web.setFocusable(true);
            web.setFocusableInTouchMode(true);
            web.setWebViewClient(
                    new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            android.util.Log.i(Report.TAG, "WEBVIEW-PAGE-FINISHED " + url);
                            // A cold WebView renderer is not ready to hold IME focus on the first
                            // frame, so keep asking while it finishes starting.
                            for (int attempt = 0; attempt < 12; attempt++) {
                                view.postDelayed(
                                        () -> {
                                            view.evaluateJavascript("document.getElementById('t').focus()", null);
                                            view.requestFocus();
                                            InputMethodManager manager = getSystemService(InputMethodManager.class);
                                            if (manager != null) {
                                                manager.showSoftInput(view, InputMethodManager.SHOW_FORCED);
                                            }
                                        },
                                        500 + attempt * 800L);
                            }
                        }
                    });
            web.loadUrl("file:///android_asset/probe.html");
            editor = web;
            root.addView(
                    web,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            EditText edit = new EditText(this);
            edit.setSingleLine(false);
            edit.setMinLines(3);
            edit.setHint("S0 probe editor");
            edit.setTextSize(16f);
            if ("password".equals(kind)) {
                edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            } else if ("null".equals(kind)) {
                edit.setInputType(InputType.TYPE_NULL);
            } else if ("nolearn".equals(kind)) {
                edit.setImeOptions(edit.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            }
            editor = edit;
            root.addView(
                    edit,
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 900));
            requestKeyboard(edit);
        }
        if (hideAfterMs > 0) root.postDelayed(this::hideKeyboard, hideAfterMs);
        final long started = android.os.SystemClock.elapsedRealtime();
        root.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        boolean finished = Control.doneAtMs > 0;
                        boolean timedOut = android.os.SystemClock.elapsedRealtime() - started > 45000;
                        if (finished || timedOut) {
                            finish();
                        } else {
                            root.postDelayed(this, 200);
                        }
                    }
                },
                200);
    }

    /**
     * Keeps asking while the probe runs: the platform sometimes cancels the first show request
     * (ImeTracker reports onFailed) and closes the input connection mid-measurement, so one
     * request is not enough on a loaded device.
     */
    private void requestKeyboard(final View view) {
        view.requestFocus();
        view.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        if (Control.doneAtMs > 0) return;
                        InputMethodManager manager = getSystemService(InputMethodManager.class);
                        if (manager != null) manager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
                        view.postDelayed(this, 600);
                    }
                },
                250);
    }

    private void hideKeyboard() {
        getWindow().getDecorView().getWindowInsetsController().hide(WindowInsets.Type.ime());
    }
}