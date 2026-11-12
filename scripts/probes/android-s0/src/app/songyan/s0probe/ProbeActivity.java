package app.songyan.s0probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Hosts the probes that do not need an editor: the isolated-process round trip and the recording
 * plans. Every probe writes one JSON report and leaves; the host script reads the report with
 * run-as. Arming a scenario for the input method service is ControlActivity's job.
 */
public final class ProbeActivity extends Activity {
    private static final byte[] PAYLOAD = new byte[1024];

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        // Reflects the persisted scenario; a process restart therefore shows up as CONTROL-STALE
        // and clears it instead of leaving it armed for a later app.
        Control.load(this);
        String mode = intent.getStringExtra("mode");
        String run = intent.getStringExtra("run");
        if ("facts".equals(mode)) {
            JSONObject report = new JSONObject();
            J.put(report, "scenario", "facts");
            J.put(report, "run", run);
            J.put(report, "device", Report.device());
            J.put(report, "micPermission", Recorder.permission(this));
            Report.write(this, "facts-" + name(run), report);
            finish();
            return;
        }
        if ("isolated".equals(mode)) {
            runOnWorker(() -> {
                try {
                    runIsolated(run);
                } catch (Throwable error) {
                    Log.e(Report.TAG, "ISOLATED-FAILED", error);
                    JSONObject report = new JSONObject();
                    J.put(report, "scenario", "isolated");
                    J.put(report, "run", run);
                    J.put(report, "error", J.error(error));
                    Report.write(this, "isolated-" + name(run), report);
                }
                finishOnMain();
            });
            return;
        }
        if ("record".equals(mode)) {
            startRecord(intent, run);
            return;
        }
        Log.w(Report.TAG, "UNKNOWN-MODE " + mode);
        finish();
    }

    private void startRecord(Intent intent, String run) {
        int seconds = intent.getIntExtra("seconds", 6);
        String plan = intent.getStringExtra("plan");
        boolean foregroundService = intent.getBooleanExtra("fgs", false);
        if (foregroundService) {
            Intent service = new Intent(this, MicService.class);
            service.putExtra("seconds", seconds);
            service.putExtra("run", run);
            service.putExtra("plan", plan);
            service.putExtra("delayMs", intent.getIntExtra("delayMs", 0));
            try {
                startForegroundService(service);
            } catch (Throwable error) {
                JSONObject report = new JSONObject();
                J.put(report, "scenario", "record");
                J.put(report, "where", "foreground-service");
                J.put(report, "plan", plan);
                J.put(report, "run", run);
                J.put(report, "device", Report.device());
                J.put(report, "startError", J.error(error));
                Report.write(this, "record-fgs-" + name(run), report);
            }
            finish();
            return;
        }
        final int delayMs = intent.getIntExtra("delayMs", 0);
        runOnWorker(() -> {
            JSONObject report = new JSONObject();
            J.put(report, "scenario", "record");
            J.put(report, "where", "activity");
            J.put(report, "plan", plan);
            J.put(report, "run", run);
            J.put(report, "delayMs", delayMs);
            J.put(report, "device", Report.device());
            if (delayMs > 0) sleep(delayMs);
            Report.record(this, "record-activity-" + name(run), report, seconds);
            finishOnMain();
        });
    }

    private void runIsolated(String run) {
        JSONObject report = new JSONObject();
        J.put(report, "scenario", "isolated");
        J.put(report, "run", run);
        J.put(report, "device", Report.device());
        J.put(report, "payloadBytes", PAYLOAD.length);
        writeCanary(report);

        Binding cold = bindAndProbe();
        J.put(report, "cold", cold.json);
        if (cold.binder != null) {
            J.put(report, "warm", warmRoundTrips(cold.binder, 300));
            J.put(report, "capabilities", capabilities(cold.binder));
            kill(cold.binder);
            await(cold.disconnected, 5000);
            unbind(cold);
            sleep(1500);
        }
        J.put(report, "hostPid", Process.myPid());
        J.put(report, "hostUid", Process.myUid());
        Binding second = bindAndProbe();
        J.put(report, "secondCold", second.json);
        int coldPid = cold.json.optInt("pid", -1);
        J.put(report, "coldPid", coldPid);
        if (second.binder != null) {
            int secondPid = second.json.optInt("pid", -1);
            long secondAgeMs = second.json.optLong("isolatedProcessAgeMs", -1);
            J.put(report, "secondColdPid", secondPid);
            // A second start only counts as cold when it is a different process that just started.
            J.put(report, "pidChanged", secondPid > 0 && secondPid != coldPid);
            J.put(report, "secondColdAgeMs", secondAgeMs);
            unbind(second);
        }
        Report.write(this, "isolated-" + name(run), report);
        Log.i(Report.TAG, "ISOLATED-DONE run=" + run);
    }

    /**
     * The host creates the private file the isolated process will be asked to read, so a missing
     * file cannot be mistaken for the permission boundary.
     */
    private void writeCanary(JSONObject report) {
        java.io.File canary = new java.io.File(getFilesDir(), "probe-caps.txt");
        boolean created = false;
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(canary);
            try {
                out.write("s0-probe-canary".getBytes("UTF-8"));
                out.getFD().sync();
            } finally {
                out.close();
            }
            created = canary.isFile() && canary.length() > 0;
        } catch (Throwable error) {
            J.put(report, "canaryError", J.error(error));
        }
        J.put(report, "canaryCreated", created);
        J.put(report, "canaryPath", canary.getAbsolutePath());
        J.put(report, "canaryLength", canary.isFile() ? canary.length() : -1);
    }

    private static final class Binding {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final JSONObject json = new JSONObject();
        ServiceConnection connection;
        IBinder binder;
    }

    private Binding bindAndProbe() {
        final Binding binding = new Binding();
        binding.connection =
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        binding.binder = service;
                        binding.connected.countDown();
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        binding.disconnected.countDown();
                    }
                };
        long started = System.nanoTime();
        boolean requested = false;
        try {
            requested = bindService(new Intent(this, IsolatedEcho.class), binding.connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable error) {
            J.put(binding.json, "bindError", J.error(error));
        }
        J.put(binding.json, "bindRequested", requested);
        if (!requested) {
            J.put(binding.json, "bindToFirstReplyMs", -1.0);
            return binding;
        }
        if (!await(binding.connected, 20000)) {
            J.put(binding.json, "connectError", "onServiceConnected timeout");
            J.put(binding.json, "bindToFirstReplyMs", -1.0);
            return binding;
        }
        JSONObject first = echo(binding.binder, 1);
        J.put(binding.json, "bindToFirstReplyMs", (System.nanoTime() - started) / 1000000.0);
        J.put(binding.json, "firstReply", first);
        if (first != null) {
            J.put(binding.json, "pid", first.optInt("pid", -1));
            J.put(binding.json, "uid", first.optInt("uid", -1));
            J.put(binding.json, "isolatedProcessAgeMs", first.optLong("processAgeMs", -1));
            J.put(binding.json, "cmdline", first.optString("cmdline", ""));
        }
        return binding;
    }

    private JSONObject echo(IBinder binder, int value) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IsolatedEcho.DESCRIPTOR);
            data.writeInt(value);
            data.writeByteArray(PAYLOAD);
            if (!binder.transact(IsolatedEcho.CODE_ECHO, data, reply, 0)) {
                Log.w(Report.TAG, "ISOLATED-CALL-FALSE");
                return null;
            }
            reply.readException();
            JSONObject out = new JSONObject();
            J.put(out, "value", reply.readInt());
            J.put(out, "pid", reply.readInt());
            J.put(out, "uid", reply.readInt());
            J.put(out, "processAgeMs", reply.readLong());
            J.put(out, "cmdline", reply.readString());
            J.put(out, "payloadBytes", reply.readInt());
            return out;
        } catch (Throwable error) {
            Log.w(Report.TAG, "ISOLATED-CALL-FAILED " + J.error(error));
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static JSONObject warmRoundTrips(IBinder binder, int count) {
        JSONObject result = new JSONObject();
        List<Double> micros = new ArrayList<>();
        int replies = 0;
        int errors = 0;
        int pid = -1;
        for (int i = 0; i < count; i++) {
            long started = System.nanoTime();
            JSONObject reply = callEcho(binder, i);
            double micro = (System.nanoTime() - started) / 1000.0;
            if (reply == null) {
                errors++;
                continue;
            }
            replies++;
            pid = reply.optInt("pid", pid);
            micros.add(micro);
        }
        J.put(result, "calls", count);
        J.put(result, "replies", replies);
        J.put(result, "errors", errors);
        J.put(result, "pid", pid);
        if (!micros.isEmpty()) {
            List<Double> firstTen = new ArrayList<>(micros.subList(0, Math.min(10, micros.size())));
            Collections.sort(micros);
            J.put(result, "minUs", micros.get(0));
            J.put(result, "p50Us", percentile(micros, 50));
            J.put(result, "p95Us", percentile(micros, 95));
            J.put(result, "p99Us", percentile(micros, 99));
            J.put(result, "maxUs", micros.get(micros.size() - 1));
            double sum = 0;
            for (double micro : micros) sum += micro;
            J.put(result, "meanUs", sum / micros.size());
            J.put(result, "firstTenUs", firstTen);
        }
        return result;
    }

    /** The same echo call without the surrounding report fields; used for the warm loop. */
    private static JSONObject callEcho(IBinder binder, int value) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IsolatedEcho.DESCRIPTOR);
            data.writeInt(value);
            data.writeByteArray(PAYLOAD);
            if (!binder.transact(IsolatedEcho.CODE_ECHO, data, reply, 0)) return null;
            reply.readException();
            JSONObject out = new JSONObject();
            J.put(out, "value", reply.readInt());
            J.put(out, "pid", reply.readInt());
            J.put(out, "uid", reply.readInt());
            J.put(out, "processAgeMs", reply.readLong());
            J.put(out, "cmdline", reply.readString());
            J.put(out, "payloadBytes", reply.readInt());
            return out;
        } catch (Throwable error) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static double percentile(List<Double> sorted, int percent) {
        int index = (int) Math.round((percent / 100.0) * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static JSONObject capabilities(IBinder binder) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IsolatedEcho.DESCRIPTOR);
            if (!binder.transact(IsolatedEcho.CODE_CAPABILITIES, data, reply, 0)) {
                JSONObject missing = new JSONObject();
                J.put(missing, "error", "capability call returned false");
                return missing;
            }
            reply.readException();
            return new JSONObject(reply.readString());
        } catch (Throwable error) {
            JSONObject failure = new JSONObject();
            J.put(failure, "error", J.error(error));
            return failure;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void kill(IBinder binder) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IsolatedEcho.DESCRIPTOR);
            binder.transact(IsolatedEcho.CODE_KILL, data, reply, 0);
        } catch (Throwable error) {
            Log.i(Report.TAG, "ISOLATED-KILL-SENT " + J.error(error));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void unbind(Binding binding) {
        try {
            unbindService(binding.connection);
        } catch (Throwable error) {
            Log.w(Report.TAG, "UNBIND-FAILED " + J.error(error));
        }
    }

    private static boolean await(CountDownLatch latch, long timeoutMs) {
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static String name(String run) {
        return run == null || run.isEmpty() ? "adhoc" : run;
    }

    private void runOnWorker(Runnable work) {
        Thread worker = new Thread(work, "s0-probe");
        worker.start();
    }

    private void finishOnMain() {
        runOnUiThread(this::finish);
    }
}