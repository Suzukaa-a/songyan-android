package app.songyan.ime.tools;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The app that is typed into: a plain editor with two fields that have real view ids, the way an
 * ordinary app builds one. It runs in its own process, which loads only this test APK, so it is
 * plain Java with no libraries.
 */
public class ProcessTextHostActivity extends Activity {
    public static final int FIELD_FIRST = 101;
    public static final int FIELD_SECOND = 202;
    public static final String FIRST_TEXT = "hello world";
    public static final String SECOND_TEXT = "second field";
    public static final String SELECTION = "hello";
    public static final String SELECT_HELLO = "select hello";
    public static final String EXTRA_IDENTIFY = "identify";
    public static final String EXTRA_UNNAMED = "unnamed";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_NO_LEARNING = "noLearning";

    private EditText first;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean identify = getIntent().getBooleanExtra(EXTRA_IDENTIFY, false);
        boolean unnamed = getIntent().getBooleanExtra(EXTRA_UNNAMED, false);
        boolean password = getIntent().getBooleanExtra(EXTRA_PASSWORD, false);
        boolean noLearning = getIntent().getBooleanExtra(EXTRA_NO_LEARNING, false);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        first = new FieldEditText(this, identify);
        if (!unnamed) first.setId(FIELD_FIRST);
        first.setText(FIRST_TEXT);
        first.setSelection(FIRST_TEXT.length());
        if (password) {
            first.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        if (noLearning) {
            first.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        }
        // A password field hides its text from accessibility, so the test app shows its own copy of it.
        TextView mirror = new TextView(this);
        mirror.setText(FIRST_TEXT);
        first.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        mirror.setText(s.toString());
                    }
                });
        Button select = new Button(this);
        select.setText(SELECT_HELLO);
        select.setFocusable(false);
        select.setOnClickListener(v -> selectHello());
        EditText second = new FieldEditText(this, identify);
        second.setId(FIELD_SECOND);
        second.setText(SECOND_TEXT);
        second.setSelection(SECOND_TEXT.length());
        layout.addView(first, new LinearLayout.LayoutParams(-1, -2));
        layout.addView(mirror, new LinearLayout.LayoutParams(-1, -2));
        layout.addView(select, new LinearLayout.LayoutParams(-1, -2));
        layout.addView(second, new LinearLayout.LayoutParams(-1, -2));
        setContentView(layout);
        first.requestFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        showKeyboard(first);
    }

    /** A native action of this app: selects the first word and hands the keyboard back to the field. */
    private void selectHello() {
        first.setSelection(0, SELECTION.length());
        first.requestFocus();
        showKeyboard(first);
    }

    private void showKeyboard(EditText field) {
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) manager.showSoftInput(field, 0);
    }

    /**
     * An app may name its editor itself; [identify] turns that on, which is how an editor that has
     * several fields can be told apart. Without it, the field is an ordinary one.
     */
    private static final class FieldEditText extends EditText {
        private final boolean identify;

        FieldEditText(Context context, boolean identify) {
            super(context);
            this.identify = identify;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            InputConnection connection = super.onCreateInputConnection(outAttrs);
            if (identify) outAttrs.fieldId = getId();
            return connection;
        }
    }
}
