package app.songyan.ime.tools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;

import java.util.ArrayList;
import java.util.Collections;

/**
 * A speech app stand-in that answers with what the request asked for, so a test can see exactly the
 * extras the keyboard sent. It runs in its own process, which loads only this test APK.
 */
public class EchoSpeechActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent request = getIntent();
        Bundle extras = request.getExtras();
        String echo =
                "model=" + request.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL)
                        + ";lang=" + request.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
                        + ";extras=" + (extras == null ? 0 : extras.size());
        ArrayList<String> results = new ArrayList<>(Collections.singletonList(echo));
        setResult(RESULT_OK, new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results));
        finish();
    }
}
