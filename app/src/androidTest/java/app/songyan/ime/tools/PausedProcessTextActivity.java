package app.songyan.ime.tools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

/** A text app that answers only after a while, so a request can still be open while the user leaves. */
public class PausedProcessTextActivity extends Activity {
    private static final long ANSWER_DELAY_MS = 5_000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CharSequence extra = getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        String text = extra == null ? "" : extra.toString();
        new Handler(Looper.getMainLooper())
                .postDelayed(
                        () -> {
                            setResult(
                                    RESULT_OK,
                                    new Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text.toUpperCase(Locale.ROOT)));
                            finish();
                        },
                        ANSWER_DELAY_MS);
    }
}
