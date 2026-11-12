package app.songyan.s0probe;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Runs only in an android:isolatedProcess, which the platform gives no permissions. Each call
 * echoes a payload back with the process facts, and the capability probe records what an isolated
 * process is actually denied, so the plan's "no permissions" claim is measured, not assumed.
 */
public final class IsolatedEcho extends Service {
    static final String DESCRIPTOR = "app.songyan.s0probe.IsolatedEcho";
    static final int CODE_ECHO = 1;
    static final int CODE_CAPABILITIES = 2;
    static final int CODE_KILL = 3;

    private final Binder binder =
            new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    try {
                        switch (code) {
                            case CODE_ECHO:
                                return echo(data, reply);
                            case CODE_CAPABILITIES:
                                data.enforceInterface(DESCRIPTOR);
                                reply.writeNoException();
                                reply.writeString(capabilities(IsolatedEcho.this));
                                return true;
                            case CODE_KILL:
                                data.enforceInterface(DESCRIPTOR);
                                reply.writeNoException();
                                Log.i(Report.TAG, "ISOLATED-KILL pid=" + Process.myPid());
                                Process.killProcess(Process.myPid());
                                return true;
                            default:
                                return super.onTransact(code, data, reply, flags);
                        }
                    } catch (Throwable error) {
                        Log.e(Report.TAG, "ISOLATED-TRANSACT-FAILED code=" + code, error);
                        return false;
                    }
                }
            };

    private static boolean echo(Parcel data, Parcel reply) {
        data.enforceInterface(DESCRIPTOR);
        int value = data.readInt();
        byte[] payload = data.createByteArray();
        reply.writeNoException();
        reply.writeInt(value + 1);
        reply.writeInt(Process.myPid());
        reply.writeInt(Process.myUid());
        reply.writeLong(SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime());
        reply.writeString(cmdline());
        reply.writeInt(payload == null ? -1 : payload.length);
        return true;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(
                Report.TAG,
                "ISOLATED-BIND pid=" + Process.myPid() + " uid=" + Process.myUid() + " cmdline=" + cmdline());
        return binder;
    }

    private static String cmdline() {
        try {
            return new String(java.nio.file.Files.readAllBytes(new File("/proc/self/cmdline").toPath()), "UTF-8")
                    .replace('\0', ' ')
                    .trim();
        } catch (Throwable error) {
            return "unavailable: " + J.error(error);
        }
    }

    private static String check(Context context, String permission) {
        try {
            return context.checkSelfPermission(permission)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ? "granted"
                    : "denied";
        } catch (Throwable error) {
            return J.error(error);
        }
    }

    /**
     * A denial is evidence only when the error itself says the permission was refused. A missing
     * path, an unreachable network or any other failure is reported as unknown instead.
     */
    private static String denialVerdict(Throwable error) {
        if (error == null) return "unknown";
        String message = J.error(error);
        if (error instanceof SecurityException
                || error instanceof java.nio.file.AccessDeniedException
                || message.contains("EACCES")
                || message.contains("EPERM")
                || message.contains("Permission denied")) {
            return "denied";
        }
        return "unknown";
    }

    private static String capabilities(Context context) {
        JSONObject result = new JSONObject();
        J.put(result, "pid", Process.myPid());
        J.put(result, "uid", Process.myUid());
        J.put(result, "cmdline", cmdline());
        J.put(result, "processAgeMs", SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime());
        try {
            J.put(result, "filesDir", String.valueOf(context.getFilesDir()));
        } catch (Throwable error) {
            J.put(result, "filesDir", J.error(error));
        }
        try {
            // The host writes this file before binding and reports canaryCreated/canaryLength.
            // A failure to read it here is evidence only when the failure says the permission was
            // refused; a missing path says nothing about the boundary, so it is recorded as
            // unknown instead of being claimed as denial.
            File canary = new File(context.getFilesDir(), "probe-caps.txt");
            J.put(result, "canaryExists", canary.isFile());
            J.put(result, "canaryLength", canary.isFile() ? canary.length() : -1);
            J.put(result, "appPrivateReadable", canary.canRead());
            String content = null;
            String readVerdict = "unknown";
            try {
                content = new String(java.nio.file.Files.readAllBytes(canary.toPath()), "UTF-8");
            } catch (Throwable error) {
                J.put(result, "appPrivateReadError", J.error(error));
                readVerdict = denialVerdict(error);
            }
            J.put(result, "appPrivateContent", content);
            J.put(result, "appPrivateReadVerdict", content != null ? "readable" : readVerdict);
            try {
                File writeTarget = new File(context.getFilesDir(), "probe-caps-write.txt");
                boolean created = writeTarget.createNewFile();
                J.put(result, "appPrivateWrite", String.valueOf(created));
                J.put(result, "appPrivateWriteVerdict", created ? "writable" : "unknown");
            } catch (Throwable error) {
                J.put(result, "appPrivateWrite", J.error(error));
                J.put(result, "appPrivateWriteVerdict", denialVerdict(error));
            }
        } catch (Throwable error) {
            J.put(result, "appPrivateReadable", J.error(error));
        }
        J.put(result, "checkInternet", String.valueOf(check(context, "android.permission.INTERNET")));
        J.put(result, "checkRecordAudio", String.valueOf(check(context, "android.permission.RECORD_AUDIO")));
        try {
            Socket socket = new Socket();
            socket.close();
            J.put(result, "socketCreated", "ok");
        } catch (Throwable error) {
            J.put(result, "socketCreated", J.error(error));
        }
        try {
            J.put(result, "dnsLookup", String.valueOf(java.net.InetAddress.getByName("localhost")));
        } catch (Throwable error) {
            J.put(result, "dnsLookup", J.error(error));
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("10.0.2.2", 9), 300);
            J.put(result, "socketConnect", "ok");
            J.put(result, "socketConnectVerdict", "connected");
        } catch (Throwable error) {
            J.put(result, "socketConnect", J.error(error));
            J.put(result, "socketConnectVerdict", denialVerdict(error));
        }
        AudioRecord record = null;
        try {
            record =
                    new AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            Recorder.SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            3200);
            J.put(result, "audioRecordState", record.getState());
            record.startRecording();
            J.put(result, "audioRecordStart", "ok");
        } catch (Throwable error) {
            J.put(result, "audioRecordStart", J.error(error));
        } finally {
            if (record != null) {
                try {
                    record.release();
                } catch (Throwable ignored) {
                    // Nothing to release.
                }
            }
        }
        return result.toString();
    }
}