package app.songyan.ime.tools;

import android.app.Activity;
import android.os.Bundle;

/** A text app that closes without an answer: nothing may be written and nothing copied. */
public class CancelledProcessTextActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        finish();
    }
}
