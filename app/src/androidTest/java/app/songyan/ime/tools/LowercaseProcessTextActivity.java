package app.songyan.ime.tools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.Locale;

/** A second text app, so a new request is told apart from a late answer of an older one. */
public class LowercaseProcessTextActivity extends Activity {
    public static final String MARK = "lower:";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CharSequence extra = getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        String text = extra == null ? "" : extra.toString();
        setResult(RESULT_OK, new Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, MARK + text.toLowerCase(Locale.ROOT)));
        finish();
    }
}
