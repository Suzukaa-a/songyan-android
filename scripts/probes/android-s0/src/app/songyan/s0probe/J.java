package app.songyan.s0probe;

import org.json.JSONArray;
import org.json.JSONObject;

/** JSON helpers that keep the measurement code free of checked-exception noise. */
final class J {
    private J() {}

    static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (Exception ignored) {
            // A key that cannot be encoded is dropped; the surrounding measurement still stands.
        }
    }

    static JSONArray array() {
        return new JSONArray();
    }

    static String error(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getName() + (message == null ? "" : ": " + message);
    }
}
