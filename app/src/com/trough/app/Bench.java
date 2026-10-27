package com.trough.app;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.OutcomeReceiver;
import android.os.PowerMonitor;
import android.os.PowerMonitorReadings;
import android.os.health.SystemHealthManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * The first device test (DESIGN.md "Measurement without adb"):
 *  1. exec a static arm64 binary shipped as lib*.so -- proves the exec path the userland needs;
 *  2. that binary measures each core's clock from CNTVCT and checks it against cpufreq;
 *  3. power monitors (API 35+) give energy per rail across the run, with no adb.
 * Result JSON goes to Download/trough/ (the VM sees it at /mnt/shared/Download/trough/).
 */
final class Bench {
    private Bench() {}

    static String run(Context ctx) {
        JSONObject r = new JSONObject();
        try {
            r.put("device", Build.MODEL);
            r.put("soc", Build.SOC_MODEL);
            r.put("sdk", Build.VERSION.SDK_INT);
            r.put("at", System.currentTimeMillis());

            List<PowerMonitor> monitors = monitors(ctx);
            PowerMonitorReadings before = readings(ctx, monitors);

            String bin = ctx.getApplicationInfo().nativeLibraryDir + "/libtroughbench.so";
            long t0 = System.nanoTime();
            Process p = new ProcessBuilder(bin).redirectErrorStream(true).start();
            String stdout = drain(p.getInputStream());
            int exit = p.waitFor();
            long ms = (System.nanoTime() - t0) / 1_000_000;

            PowerMonitorReadings after = readings(ctx, monitors);
            r.put("exec", new JSONObject().put("path", bin).put("exit", exit).put("ms", ms));
            try { r.put("bench", new JSONObject(stdout)); } catch (Exception e) { r.put("bench_raw", stdout); }

            JSONArray energy = new JSONArray();
            for (PowerMonitor m : monitors) {
                JSONObject e = new JSONObject().put("name", m.getName())
                    .put("type", m.getType() == PowerMonitor.POWER_MONITOR_TYPE_MEASUREMENT ? "rail" : "consumer");
                if (before != null && after != null) {
                    long a = before.getConsumedEnergy(m), b = after.getConsumedEnergy(m);
                    if (a != PowerMonitorReadings.ENERGY_UNAVAILABLE && b != PowerMonitorReadings.ENERGY_UNAVAILABLE)
                        e.put("delta_uJ", b - a);
                }
                energy.put(e);
            }
            r.put("power_monitors", energy);
            if (monitors.isEmpty()) r.put("power_monitors_note", "none returned (API < 35 or not exposed to apps)");
        } catch (Throwable t) {
            try { r.put("error", t.toString()); } catch (Exception ignored) {}
        }
        String text;
        try { text = r.toString(2); } catch (Exception e) { text = r.toString(); }
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "trough");
            dir.mkdirs();
            File f = new File(dir, "bench-" + System.currentTimeMillis() + ".json");
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(text.getBytes("UTF-8")); }
            text = "saved " + f + "\n\n" + text;
        } catch (Exception e) {
            text = "not saved (" + e + ")\n\n" + text;
        }
        return text;
    }

    private static final Executor DIRECT = new Executor() {
        @Override public void execute(Runnable r) { r.run(); }
    };

    private static List<PowerMonitor> monitors(Context ctx) throws InterruptedException {
        if (Build.VERSION.SDK_INT < 35) return Collections.emptyList();
        SystemHealthManager shm = ctx.getSystemService(SystemHealthManager.class);
        final List<PowerMonitor> got = new ArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        shm.getSupportedPowerMonitors(DIRECT, new java.util.function.Consumer<List<PowerMonitor>>() {
            @Override public void accept(List<PowerMonitor> list) { got.addAll(list); done.countDown(); }
        });
        done.await(3, TimeUnit.SECONDS);
        return got;
    }

    private static PowerMonitorReadings readings(Context ctx, List<PowerMonitor> monitors) throws InterruptedException {
        if (monitors.isEmpty()) return null;
        SystemHealthManager shm = ctx.getSystemService(SystemHealthManager.class);
        final PowerMonitorReadings[] got = new PowerMonitorReadings[1];
        final CountDownLatch done = new CountDownLatch(1);
        shm.getPowerMonitorReadings(monitors, DIRECT, new OutcomeReceiver<PowerMonitorReadings, RuntimeException>() {
            @Override public void onResult(PowerMonitorReadings value) { got[0] = value; done.countDown(); }
            @Override public void onError(RuntimeException error) { done.countDown(); }
        });
        done.await(3, TimeUnit.SECONDS);
        return got[0];
    }

    private static String drain(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
        return buf.toString("UTF-8");
    }
}
