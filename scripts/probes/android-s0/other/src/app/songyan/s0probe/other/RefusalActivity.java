package app.songyan.s0probe.other;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Tries to start the probe's control entry from an app that holds no permissions, and reports what
 * the platform did. The probe's entry requires android.permission.DUMP, a signature permission
 * this app cannot hold, so the start must be refused; selftest.sh reads the log line instead of
 * inspecting any source or manifest.
 */
public final class RefusalActivity extends Activity {
    private static final String TAG = "S0Other";
    private static final String TARGET = "app.songyan.s0probe";
    private static final String TARGET_ENTRY = "app.songyan.s0probe.ControlActivity";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String result;
        try {
            Intent intent = new Intent();
            intent.setClassName(TARGET, TARGET_ENTRY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            result = "STARTED";
        } catch (Throwable error) {
            String message = String.valueOf(error.getMessage());
            if (error instanceof SecurityException
                    && message.toLowerCase().contains("permission")) {
                result = "DENIED " + message;
            } else {
                result = "UNEXPECTED " + error.getClass().getName() + ": " + message;
            }
        }
        Log.i(TAG, "REFUSAL " + result);
        finish();
    }
}
