package app.songyan.ime.tools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.Locale;

/**
 * A third-party app stand-in for ACTION_PROCESS_TEXT: it returns the text it was given in capitals.
 * It runs in its own process, which loads only this test APK, so it is plain Java with no libraries.
 */
public class FakeProcessTextActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CharSequence extra = getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        String text = extra == null ? "" : extra.toString();
        Intent answer = new Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text.toUpperCase(Locale.ROOT));
        setResult(RESULT_OK, answer);
        finish();
    }
}
