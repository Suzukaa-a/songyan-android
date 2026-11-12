package app.songyan.s0probe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Hands the probe a scenario. This is the only component that writes control flags, and it is not
 * exported: the host script starts it through run-as, so no other app can arm the probe.
 */
public final class ControlActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        boolean measure = intent.getBooleanExtra("measure", false);
        boolean record = intent.getBooleanExtra("record", false);
        if (measure || record) {
            Control.apply(
                    this,
                    intent.getStringExtra("run"),
                    intent.getIntExtra("marker", Control.markerLength),
                    measure,
                    record,
                    intent.getStringExtra("editor"),
                    intent.getIntExtra("seconds", 3),
                    intent.getIntExtra("delayMs", 0));
            // Keeps the process resident while the user switches to the app being measured.
            startService(new Intent(this, HoldService.class));
        } else {
            Control.clear(this);
            stopService(new Intent(this, HoldService.class));
        }
        Log.i(
                Report.TAG,
                "CONTROL run=" + Control.runId + " marker=" + Control.markerLength
                        + " measure=" + Control.measure + " record=" + Control.record
                        + " editor=" + Control.targetEditor);
        finish();
    }
}
