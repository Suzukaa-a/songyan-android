package app.songyan.s0probe;

import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Measures how much text an editor hands back through getTextBeforeCursor. The probe only writes
 * into its own empty editor and only deletes what it can prove it committed: any other editor is
 * measured read-only, so a stray target can never corrupt text that belongs to the user.
 */
final class ContextProbe {
    private static final int CHUNK = 500;

    private ContextProbe() {}

    static JSONObject measure(InputMethodService service, String run, EditorInfo editorInfo) {
        JSONObject report = new JSONObject();
        J.put(report, "scenario", "context");
        J.put(report, "run", run);
        J.put(report, "device", Report.device());
        J.put(report, "editor", editorInfo == null ? "" : editorInfo.packageName);
        J.put(report, "inputType", editorInfo == null ? -1 : editorInfo.inputType);
        J.put(report, "requestedMarkerLength", Control.markerLength);
        boolean ownEditor = editorInfo != null && service.getPackageName().equals(editorInfo.packageName);
        J.put(report, "mode", ownEditor ? "write" : "read-only");
        InputConnection connection = service.getCurrentInputConnection();
        if (connection == null) {
            J.put(report, "error", "no input connection");
            return report;
        }
        int[] sizes = {1, 8, 64, 256, 1000, 4096, 10000, 1000000};
        J.put(report, "initial", queries(connection, sizes));
        if (!ownEditor) {
            J.put(report, "wrote", false);
            J.put(report, "deleted", false);
            J.put(report, "availableLength", length(connection, 1000000));
            J.put(report, "cleanup", "not-attempted");
            return report;
        }
        int before = length(connection, 1000000);
        J.put(report, "availableLengthBefore", before);
        if (before != 0) {
            // Only the probe's own empty field may be written; anything else is not ours to touch.
            J.put(report, "wrote", false);
            J.put(report, "deleted", false);
            J.put(report, "skipped", "own editor is not empty: " + before);
            J.put(report, "cleanup", "not-attempted");
            return report;
        }
        String marker = marker(Control.markerLength);
        long commitStarted = SystemClock.elapsedRealtime();
        int commitErrors = 0;
        for (int offset = 0; offset < marker.length(); offset += CHUNK) {
            try {
                connection.commitText(marker.substring(offset, Math.min(marker.length(), offset + CHUNK)), 1);
            } catch (Throwable error) {
                commitErrors++;
            }
            sleep(15);
        }
        J.put(report, "sentLength", marker.length());
        J.put(report, "wrote", true);
        J.put(report, "commitErrors", commitErrors);
        int available = -1;
        long settledMs = -1;
        for (int attempt = 0; attempt < 60; attempt++) {
            int length = length(connection, 1000000);
            if (length > available) available = length;
            if (length >= marker.length()) {
                settledMs = SystemClock.elapsedRealtime() - commitStarted;
                break;
            }
            sleep(50);
        }
        J.put(report, "settledMs", settledMs);
        J.put(report, "availableLength", available);
        J.put(report, "after", queries(connection, sizes));
        // Deleting is only allowed when the field holds exactly the marker and nothing else.
        boolean complete = false;
        try {
            CharSequence full = connection.getTextBeforeCursor(marker.length() + 1, 0);
            complete = full != null && full.length() == marker.length() && marker.contentEquals(full);
        } catch (Throwable error) {
            J.put(report, "error", J.error(error));
        }
        J.put(report, "completeMatch", complete);
        J.put(report, "measuredLength", available);
        J.put(report, "truncated", available >= 0 && available < marker.length());
        if (!complete) {
            // A truncated commit is a failure to report, never a reason to delete anything.
            J.put(report, "deleted", false);
            J.put(report, "cleanup", "skipped-incomplete");
            J.put(report, "lengthAfterCleanup", length(connection, 1000000));
            J.put(
                    report,
                    "error",
                    "marker was not committed completely (measured " + available + " of "
                            + marker.length() + "); refusing to delete");
            return report;
        }
        try {
            connection.deleteSurroundingTextInCodePoints(marker.length(), 0);
            J.put(report, "deleteRequested", marker.length());
        } catch (Throwable error) {
            J.put(report, "error", J.error(error));
        }
        sleep(150);
        int after = length(connection, 1000000);
        boolean cleaned = after == 0;
        J.put(report, "lengthAfterCleanup", after);
        J.put(report, "deleted", cleaned);
        J.put(report, "cleanup", cleaned ? "deleted" : "delete-incomplete");
        if (!cleaned) J.put(report, "error", "deletion did not leave an empty field");
        return report;
    }

    private static String marker(int length) {
        StringBuilder builder = new StringBuilder(length + 10);
        while (builder.length() < length) builder.append("0123456789");
        return builder.substring(0, length);
    }

    private static int length(InputConnection connection, int n) {
        try {
            CharSequence before = connection.getTextBeforeCursor(n, 0);
            return before == null ? -1 : before.length();
        } catch (Throwable error) {
            return -2;
        }
    }

    private static JSONArray queries(InputConnection connection, int[] sizes) {
        JSONArray array = J.array();
        for (int n : sizes) {
            JSONObject entry = new JSONObject();
            J.put(entry, "n", n);
            try {
                CharSequence before = connection.getTextBeforeCursor(n, 0);
                J.put(entry, "length", before == null ? -1 : before.length());
                J.put(entry, "null", before == null);
            } catch (Throwable error) {
                J.put(entry, "error", J.error(error));
            }
            array.put(entry);
        }
        return array;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
