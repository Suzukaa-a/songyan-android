package app.songyan.ime.tools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * A third-party app stand-in for ACTION_RECOGNIZE_SPEECH: it "recognizes" a fixed phrase and
 * returns two alternatives, best first. It runs in its own process, which loads only this test APK,
 * so it is plain Java with no libraries.
 */
public class FakeSpeechActivity extends Activity {
    public static final String TRANSCRIPT = "你好世界";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ArrayList<String> results = new ArrayList<>(Arrays.asList(TRANSCRIPT, "备选结果"));
        setResult(RESULT_OK, new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results));
        finish();
    }
}
