package app.songyan.addonprobe.host;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The probe input method. It is the calling side the plan needs to measure:
 *
 *   a1  bind with BIND_INCLUDE_CAPABILITIES, addon has RECORD_AUDIO  -> can the addon record?
 *   a2  bind without the flag, addon has RECORD_AUDIO               -> control run
 *   a3  bind with BIND_INCLUDE_CAPABILITIES, addon's permission revoked by the script
 *   b   embed the addon's SurfaceControlViewHost panel in the input method window, measure size,
 *       insets and touches, then let the script kill the addon and commit text afterwards
 *
 * Reports are JSON files in the host's external files directory, one per case; the script pulls
 * them and summarize.py validates them. Missing data is never treated as success.
 */
public class ProbeIme extends InputMethodService {
    private static final String TAG = "AddonProbeHost";
    private static final String ADDON_PKG = "app.songyan.addonprobe.addon";
    private static final String ADDON_SERVICE = ADDON_PKG + ".ProbeAddonService";
    private static final String ADDON_ACTION = "app.songyan.addonprobe.SERVICE";
    private static final int TRANSACT_RECORD_AUDIO = 1;
    private static final int TRANSACT_OPEN_PANEL = 2;
    private static final int TRANSACT_CLOSE_PANEL = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object reportLock = new Object();
    private final Map<String, Object> caseFields = new LinkedHashMap<>();

    private FrameLayout inputRoot;
    private SurfaceView panelSurface;
    private Button commitButton;
    private String activeCase = "";
    private IBinder addonBinder;
    private ServiceConnection addonConnection;
    private IBinder.DeathRecipient addonDeath;
    private boolean panelAttached;
    private int commitClicks;

    @Override
    public View onCreateInputView() {
        FrameLayout root = new FrameLayout(this);
        inputRoot = root;

        panelSurface = new SurfaceView(this);
        // The embedded hierarchy only receives input when the SurfaceView is above the host
        // surface; without this it renders but the keyboard window keeps the touches.
        panelSurface.setZOrderOnTop(true);
        root.addView(
                panelSurface,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, dp(360), Gravity.TOP));

        commitButton = new Button(this);
        commitButton.setText("commit K");
        commitButton.setOnClickListener(v -> commitK());
        root.addView(
                commitButton,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM));
        return root;
    }

    private void commitK() {
        commitClicks++;
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            connection.commitText("K", 1);
        }
        caseFields.put("commitClicks", commitClicks);
        caseFields.put("commitHadConnection", connection != null);
        writeReport();
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        String caseName = readCase();
        if (caseName == null || caseName.isEmpty() || caseName.equals(activeCase)) {
            return;
        }
        unbindAddon();
        activeCase = caseName;
        synchronized (reportLock) {
            caseFields.clear();
        }
        panelAttached = false;
        commitClicks = 0;
        addonBinder = null;
        writeReport();
        handler.postDelayed(() -> runCase(caseName), 1200);
    }

    private void runCase(String caseName) {
        if (caseName.startsWith("a")) {
            runAudioCase(caseName);
        } else if (caseName.startsWith("b")) {
            runPanelCase(caseName);
        } else {
            caseFields.put("error", "unknown case " + caseName);
            writeReport();
        }
    }

    private void runAudioCase(String caseName) {
        boolean includeCapabilities = caseName.equals("a1") || caseName.equals("a3");
        bindAddon(
                includeCapabilities,
                () -> {
                    Bundle args = new Bundle();
                    args.putInt("durationMs", 1200);
                    args.putString("case", caseName);
                    Bundle reply = transact(TRANSACT_RECORD_AUDIO, args);
                    if (reply != null) {
                        copyReply("record", reply);
                    } else {
                        caseFields.put("record.replyMissing", true);
                    }
                    writeReport();
                });
    }

    private void runPanelCase(String caseName) {
        bindAddon(
                true,
                () -> {
                    if (panelSurface == null) {
                        caseFields.put("error", "no input view");
                        writeReport();
                        return;
                    }
                    IBinder token = panelSurface.getHostToken();
                    caseFields.put("hostTokenPresent", token != null);
                    int width = panelSurface.getWidth();
                    int height = panelSurface.getHeight();
                    caseFields.put("panelRequestedWidthPx", width);
                    caseFields.put("panelRequestedHeightPx", height);
                    if (token == null || width <= 0 || height <= 0) {
                        caseFields.put("error", "host token or size not ready");
                        writeReport();
                        return;
                    }
                    Bundle args = new Bundle();
                    args.putBinder("hostToken", token);
                    args.putInt("displayId", getDisplay() == null ? 0 : getDisplay().getDisplayId());
                    args.putInt("widthPx", width);
                    args.putInt("heightPx", height);
                    args.putBoolean("darkMode", isNightMode());
                    args.putInt("keyboardColor", 0xFF202124);
                    args.putString("case", caseName);
                    Bundle reply = transact(TRANSACT_OPEN_PANEL, args);
                    if (reply == null) {
                        caseFields.put("panelReplyMissing", true);
                        writeReport();
                        return;
                    }
                    copyReply("addon", reply);
                    SurfaceControlViewHost.SurfacePackage surfacePackage =
                            reply.getParcelable("surfacePackage");
                    if (surfacePackage == null) {
                        caseFields.put("panelSurfaceMissing", true);
                        writeReport();
                        return;
                    }
                    handler.post(() -> attachPanel(surfacePackage));
                });
    }

    private void attachPanel(SurfaceControlViewHost.SurfacePackage surfacePackage) {
        try {
            panelSurface.setChildSurfacePackage(surfacePackage);
            panelAttached = true;
            int[] location = new int[2];
            panelSurface.getLocationOnScreen(location);
            caseFields.put("panelReady", true);
            caseFields.put("panelSurfaceLeft", location[0]);
            caseFields.put("panelSurfaceTop", location[1]);
            caseFields.put("panelSurfaceWidthPx", panelSurface.getWidth());
            caseFields.put("panelSurfaceHeightPx", panelSurface.getHeight());
            int[] commit = new int[2];
            commitButton.getLocationOnScreen(commit);
            caseFields.put("commitLeft", commit[0]);
            caseFields.put("commitTop", commit[1]);
            caseFields.put("commitWidthPx", commitButton.getWidth());
            caseFields.put("commitHeightPx", commitButton.getHeight());
        } catch (Exception failure) {
            caseFields.put("panelAttachError", failure.toString());
        }
        writeReport();
    }

    private void bindAddon(boolean includeCapabilities, Runnable onConnected) {
        Intent intent = new Intent(ADDON_ACTION);
        intent.setComponent(new ComponentName(ADDON_PKG, ADDON_SERVICE));
        int flags = Context.BIND_AUTO_CREATE;
        if (includeCapabilities) {
            flags |= Context.BIND_INCLUDE_CAPABILITIES;
        }
        caseFields.put("includeCapabilities", includeCapabilities);
        caseFields.put("bindingFlags", flags);
        addonConnection =
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        addonBinder = service;
                        caseFields.put("binderReceived", true);
                        addonDeath = () -> onAddonDied(service);
                        try {
                            service.linkToDeath(addonDeath, 0);
                        } catch (Exception failure) {
                            caseFields.put("linkToDeathError", failure.toString());
                        }
                        handler.post(onConnected);
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        caseFields.put("serviceDisconnected", true);
                        writeReport();
                    }

                    @Override
                    public void onBindingDied(ComponentName name) {
                        caseFields.put("bindingDied", true);
                        writeReport();
                    }
                };
        boolean bound;
        try {
            bound = bindService(intent, addonConnection, flags);
        } catch (Exception failure) {
            bound = false;
            caseFields.put("bindError", failure.toString());
        }
        caseFields.put("bindAccepted", bound);
        writeReport();
        if (!bound) {
            return;
        }
        handler.postDelayed(
                () -> {
                    if (addonBinder == null) {
                        caseFields.put("binderTimeout", true);
                        writeReport();
                    }
                },
                6_000);
    }

    private void onAddonDied(IBinder service) {
        caseFields.put("addonDeathObserved", true);
        caseFields.put("deathAtElapsedRealtime", SystemClock.elapsedRealtime());
        handler.post(
                () -> {
                    if (panelSurface != null) {
                        try {
                            panelSurface.clearChildSurfacePackage();
                            caseFields.put("surfaceClearedAfterDeath", true);
                        } catch (Exception failure) {
                            caseFields.put("surfaceClearError", failure.toString());
                        }
                    }
                    caseFields.put("panelAttachedBeforeDeath", panelAttached);
                    caseFields.put("keyboardStillShown", isInputViewShown());
                    writeReport();
                });
    }

    private Bundle transact(int code, Bundle args) {
        IBinder binder = addonBinder;
        if (binder == null) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeBundle(args);
            if (!binder.transact(code, data, reply, 0)) {
                caseFields.put("transactRefused", code);
                return null;
            }
            reply.setDataPosition(0);
            // The addon writes the standard "no exception" header before its bundle.
            reply.readException();
            return reply.readBundle(getClass().getClassLoader());
        } catch (Exception failure) {
            caseFields.put("transactError", failure.toString());
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void copyReply(String prefix, Bundle reply) {
        for (String key : reply.keySet()) {
            Object value = reply.get(key);
            if (value instanceof Bundle) {
                int depth = 1;
                for (String inner : ((Bundle) value).keySet()) {
                    caseFields.put(prefix + "." + key + "." + inner, ((Bundle) value).get(inner));
                    depth++;
                }
            } else if (!(value instanceof IBinder)) {
                caseFields.put(prefix + "." + key, value);
            }
        }
    }

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private String readCase() {
        File file = new File(getFilesDir(), "case.txt");
        if (!file.isFile()) {
            return null;
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = in.read(bytes);
            return read <= 0 ? null : new String(bytes, 0, read, StandardCharsets.UTF_8).trim();
        } catch (Exception failure) {
            Log.w(TAG, "cannot read the case file", failure);
            return null;
        }
    }

    private void writeReport() {
        synchronized (reportLock) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("probe", "host");
            report.put("case", activeCase);
            report.put("generatedAtMillis", System.currentTimeMillis());
            report.put("sdkInt", android.os.Build.VERSION.SDK_INT);
            report.put("device", android.os.Build.MODEL);
            report.put("inputViewShown", isInputViewShown());
            report.put("inputViewWidthPx", inputRoot == null ? -1 : inputRoot.getWidth());
            report.put("inputViewHeightPx", inputRoot == null ? -1 : inputRoot.getHeight());
            report.put("panelSurfaceZOrderOnTop", true);
            report.put("windowInsets", windowInsetsFacts());
            report.put("addonInstalled", addonInstalled());
            report.put("addonUid", addonUid());
            report.putAll(caseFields);
            if (inputRoot != null) {
                WindowInsets insets = inputRoot.getRootWindowInsets();
                report.put("insetsAtReportAvailable", insets != null);
            }
            File directory = getExternalFilesDir(null);
            File file = new File(directory, "report-host-" + activeCase + ".json");
            File temporary = new File(directory, "report-host-" + activeCase + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(Json.write(report).getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                Log.w(TAG, "cannot write the host report", failure);
                return;
            }
            if (!temporary.renameTo(file)) {
                Log.w(TAG, "cannot publish the host report");
            }
        }
    }

    private Map<String, Object> windowInsetsFacts() {
        Map<String, Object> facts = new LinkedHashMap<>();
        if (inputRoot == null || inputRoot.getRootWindowInsets() == null) {
            facts.put("available", false);
            return facts;
        }
        WindowInsets insets = inputRoot.getRootWindowInsets();
        facts.put("available", true);
        facts.put("navigationBars", rect(insets.getInsets(WindowInsets.Type.navigationBars())));
        facts.put("systemBars", rect(insets.getInsets(WindowInsets.Type.systemBars())));
        facts.put("ime", rect(insets.getInsets(WindowInsets.Type.ime())));
        facts.put("displayCutout", rect(insets.getInsets(WindowInsets.Type.displayCutout())));
        return facts;
    }

    private static Map<String, Object> rect(android.graphics.Insets insets) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("left", insets.left);
        map.put("top", insets.top);
        map.put("right", insets.right);
        map.put("bottom", insets.bottom);
        return map;
    }

    private boolean addonInstalled() {
        try {
            getPackageManager().getPackageInfo(ADDON_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    private int addonUid() {
        try {
            return getPackageManager().getApplicationInfo(ADDON_PKG, 0).uid;
        } catch (PackageManager.NameNotFoundException missing) {
            return -1;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void unbindAddon() {
        IBinder binder = addonBinder;
        if (binder != null && addonDeath != null) {
            try {
                binder.unlinkToDeath(addonDeath, 0);
            } catch (Exception ignored) {
                // The addon may already be gone.
            }
        }
        if (addonConnection != null) {
            try {
                unbindService(addonConnection);
            } catch (Exception ignored) {
                // Already unbound.
            }
        }
        addonBinder = null;
        addonConnection = null;
        addonDeath = null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (addonBinder != null) {
            transact(TRANSACT_CLOSE_PANEL, new Bundle());
        }
        unbindAddon();
        super.onDestroy();
    }
}
