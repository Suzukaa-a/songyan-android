package app.songyan.ime.tools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** A text app that answers with RESULT_OK and no text: an empty answer is a real answer. */
public class EmptyProcessTextActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_OK, new Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, ""));
        finish();
    }
}
