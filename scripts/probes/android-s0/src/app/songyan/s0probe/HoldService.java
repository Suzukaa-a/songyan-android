package app.songyan.s0probe;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the probe process resident between the control activity and the text field the user taps.
 * A scenario only belongs to the process that was handed it (another pid clears it), so the
 * process must still be there when the input method starts; this started service is that hold.
 * It stops as soon as the scenario is claimed, and the system reclaims it if the user never taps.
 */
public final class HoldService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Control.load(this);
        if (Control.finished) {
            Log.i(Report.TAG, "HOLD-STOP nothing pending");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
