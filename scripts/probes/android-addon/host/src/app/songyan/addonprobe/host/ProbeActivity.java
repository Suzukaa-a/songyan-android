package app.songyan.addonprobe.host;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The host window of the probe: a plain activity with a focused editor so the probe input method
 * becomes visible, and a small loop that writes the editor's current text to a report file. That
 * text is the evidence that the keyboard still commits after the addon process is killed.
 */
public class ProbeActivity extends Activity {
    private static final String TAG = "AddonProbeHost";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText editor;

    private final Runnable editorReport =
            new Runnable() {
                @Override
                public void run() {
                    writeEditorReport();
                    handler.postDelayed(this, 400);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(48, 96, 48, 48);

        TextView title = new TextView(this);
        title.setText("Addon probe host");
        title.setTextSize(22);
        content.addView(title);

        editor = new EditText(this);
        editor.setHint("editor");
        content.addView(editor);

        setContentView(content);
        applyCase(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyCase(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(editorReport);
        handler.post(editorReport);
        focusEditor();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(editorReport);
        super.onPause();
    }

    private void applyCase(Intent intent) {
        String caseName = intent == null ? null : intent.getStringExtra("case");
        if (caseName == null || caseName.isEmpty()) {
            return;
        }
        writeFile(new File(getFilesDir(), "case.txt"), caseName);
        editor.setText("");
        focusEditor();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        // The probe input method may become current only after the editor is focused (the runner
        // switches it around the activity start), so keep asking for the keyboard for a while.
        for (int delay = 300; delay <= 15_000; delay += 500) {
            handler.postDelayed(
                    () -> {
                        if (imm != null && editor != null && editor.hasFocus()) {
                            imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
                        }
                    },
                    delay);
        }
    }

    private void focusEditor() {
        if (editor == null) {
            return;
        }
        editor.requestFocus();
    }

    private void writeEditorReport() {
        if (editor == null) {
            return;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("probe", "activity");
        report.put("text", editor.getText().toString());
        report.put("hasFocus", editor.hasFocus());
        report.put("selection", editor.getSelectionStart());
        writeFile(new File(getExternalFilesDir(null), "editor.json"), Json.write(report));
    }

    private static void writeFile(File file, String text) {
        File directory = file.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // A missing report fails the probe run; there is nothing to retry here.
        }
    }
}
