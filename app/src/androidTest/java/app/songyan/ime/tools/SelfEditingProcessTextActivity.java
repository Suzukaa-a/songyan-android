package app.songyan.ime.tools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.Locale;

/**
 * A text app that has an editor of its own: it takes the keyboard for a moment before it answers,
 * which must not be mistaken for the editor the request came from.
 */
public class SelfEditingProcessTextActivity extends Activity {
    private static final long ANSWER_DELAY_MS = 400L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EditText own = new EditText(this);
        own.setText("own editor");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(own, new LinearLayout.LayoutParams(-1, -2));
        setContentView(layout);
        own.requestFocus();
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) manager.showSoftInput(own, 0);

        CharSequence extra = getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        String text = extra == null ? "" : extra.toString();
        String answer = text.toUpperCase(Locale.ROOT);
        new Handler(Looper.getMainLooper())
                .postDelayed(
                        () -> {
                            setResult(RESULT_OK, new Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, answer));
                            finish();
                        },
                        ANSWER_DELAY_MS);
    }
}
