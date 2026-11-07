package com.trough.hot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Device-only inference and detached renderer checks; never touches a live conversation. */
final class SemanticSteeringOracle {
    private static final String SHA = "f50b77582b795776d57c8fb624a66aa1c19241d1e7d5a81f1ad9834ee1416ede";
    private static final String[][] DIRECTIONS = {
        {"Restless tense chaotic turbulent agitation", "Calm serene peaceful quiet relaxation"},
        {"Restful still slow gentle subdued motion", "Energetic fast dynamic vibrant exciting motion"},
        {"Soft diffuse wandering playful freeform exploration", "Focused precise orderly clear deliberate concentration"},
        {"Cold cool blue cyan icy crisp atmosphere", "Warm amber golden orange cozy sunlit atmosphere"},
        {"Flat simple minimal clean surface with little depth", "Deep immersive spacious luminous dimensional layers"}
    };
    private SemanticSteeringOracle() {}

    /** Run on a background caller; only the detached View drawing hops to Android's main thread. */
    static Map<String,Object> run(final Context context, File file) {
        Map<String,Object> report = new LinkedHashMap<String,Object>();
        report.put("ok", false); report.put("local", true); report.put("model", SemanticSteering.MODEL_ID);
        report.put("revision", SemanticSteering.REVISION); report.put("model_turn_started", false);
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("background caller required");
            long heapBefore = heap(), nativeBefore = Debug.getNativeHeapAllocatedSize();
            long pssBefore = Debug.getPss();
            String sha = hash(file);
            boolean artifact = SHA.equals(sha) && file.length() == 7867466;
            report.put("sha256", sha); report.put("bytes", file.length()); report.put("artifact_pass", artifact);
            if (!artifact) return report;
            long loadStart = System.nanoTime();
            final SemanticSteering model = SemanticSteering.load(file);
            report.put("load_ns", System.nanoTime() - loadStart);
            long heapLoaded = heap();
            report.put("dimensions", model.dimensions()); report.put("vocabulary", model.vocabularySize());
            for (int i = 0; i < 8; i++) model.steer(DIRECTIONS[i % DIRECTIONS.length][i % 2]);
            long[] times = new long[64];
            int tokens = 0; boolean finite = true;
            for (int i = 0; i < times.length; i++) {
                StringBuilder text = new StringBuilder();
                int repeats = 1 << (i % 5);
                for (int n = 0; n < repeats; n++) text.append(DIRECTIONS[i % 5][i % 2]).append(". ");
                long start = System.nanoTime();
                SemanticSteering.Result result = model.steer(text.toString());
                times[i] = System.nanoTime() - start; tokens += result.tokenCount;
                for (float value : result.embedding) finite &= !Float.isNaN(value) && !Float.isInfinite(value);
                for (float value : result.values.values())
                    finite &= !Float.isNaN(value) && !Float.isInfinite(value) && value >= 0 && value <= 1;
            }
            Arrays.sort(times);
            report.put("cases", times.length); report.put("tokens", tokens); report.put("finite_pass", finite);
            report.put("inference_p50_ns", times[31]); report.put("inference_p95_ns", times[60]);
            report.put("inference_min_ns", times[0]); report.put("inference_max_ns", times[63]);
            Map<String,Object> directions = new LinkedHashMap<String,Object>();
            boolean directionPass = true;
            for (int axis = 0; axis < DIRECTIONS.length; axis++) {
                String name = SemanticSteering.AXES[axis];
                float low = model.steer(DIRECTIONS[axis][0]).values.get(name);
                float high = model.steer(DIRECTIONS[axis][1]).values.get(name);
                boolean pass = high > low + .1f;
                directionPass &= pass;
                directions.put(name, Json.map("low", low, "high", high, "pass", pass));
            }
            report.put("semantic_directions", directions); report.put("semantic_directions_pass", directionPass);
            final Map<String,Object> cold = model.steer(DIRECTIONS[3][0]).state();
            final Map<String,Object> warm = model.steer(DIRECTIONS[3][1]).state();
            // Timing is diagnostic only and must not become a hidden rendering input.
            cold.remove("inference_ns"); warm.remove("inference_ns");
            FutureTask<Map<String,Object>> draw = new FutureTask<Map<String,Object>>(new Callable<Map<String,Object>>() {
                @Override public Map<String,Object> call() { return draw(context, cold, warm); }
            });
            new Handler(Looper.getMainLooper()).post(draw);
            Map<String,Object> rendered;
            try { rendered = draw.get(5, TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException timeout) { draw.cancel(false); throw timeout; }
            report.put("render", rendered);
            report.put("memory", Json.map("measurement", "whole_process_before_after_no_forced_gc",
                "java_heap_before_bytes", heapBefore, "java_heap_after_load_bytes", heapLoaded,
                "java_heap_after_cases_bytes", heap(), "java_heap_load_delta_bytes", heapLoaded - heapBefore,
                "native_heap_before_bytes", nativeBefore, "native_heap_after_bytes", Debug.getNativeHeapAllocatedSize(),
                "pss_before_kib", pssBefore, "pss_after_kib", Debug.getPss(),
                "scope", "approximate; concurrent app allocations and GC affect deltas"));
            report.put("ok", artifact && finite && directionPass && Boolean.TRUE.equals(rendered.get("ok")));
        } catch (Throwable error) {
            report.put("error_type", error.getClass().getName());
            if (error.getCause() != null) report.put("cause_type", error.getCause().getClass().getName());
        }
        return report;
    }

    private static Map<String,Object> draw(Context context, Map<String,Object> cold, Map<String,Object> warm) {
        BuildArtwork first = new BuildArtwork(context), second = new BuildArtwork(context);
        Bitmap a = null, baseline = null, b = null;
        try {
            int width = 384, height = 256;
            Map<String,Object> material = Json.map("word", "MOON", "response", "", "state", "idle", "steering", cold);
            first.update(material); second.update(material);
            int exactWidth = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
            int exactHeight = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
            first.measure(exactWidth, exactHeight); first.layout(0, 0, width, height);
            second.measure(exactWidth, exactHeight); second.layout(0, 0, width, height);
            // Freeze time, phase, geometry, text and grain. Only the learned policy
            // differs in the warm comparison, preventing hash/time-driven false passes.
            Map<String,Object> fixed = new LinkedHashMap<String,Object>(first.handoff());
            fixed.put("seed", 0x19ad72f); fixed.put("changed_at", SystemClock.elapsedRealtime() - 10000);
            fixed.put("touched_at", 0L); fixed.put("active", false);
            fixed.put("phase_from", 2.8f); fixed.put("phase_target", 2.8f);
            fixed.put("wave_from", new float[6]); fixed.put("wave_target", new float[6]);
            first.restore(fixed); second.restore(fixed);
            a = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            baseline = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            first.draw(new Canvas(a)); second.draw(new Canvas(baseline));
            int[] pixels = new int[width * height], reference = new int[width * height], warmPixels = new int[width * height];
            a.getPixels(pixels, 0, width, 0, 0, width, height);
            baseline.getPixels(reference, 0, width, 0, 0, width, height);
            Map<String,Object> changed = new LinkedHashMap<String,Object>(fixed);
            changed.put("steering", warm); second.restore(changed);
            b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); second.draw(new Canvas(b));
            b.getPixels(warmPixels, 0, width, 0, 0, width, height);
            int baselineChanged = 0, policyChanged = 0;
            for (int i = 0; i < pixels.length; i++) {
                if (pixels[i] != reference[i]) baselineChanged++;
                if (pixels[i] != warmPixels[i]) policyChanged++;
            }
            first.close(); second.close();
            boolean stopped = Boolean.FALSE.equals(first.state().get("scheduled")) && Boolean.FALSE.equals(second.state().get("scheduled"));
            return Json.map("ok", baselineChanged == 0 && policyChanged > 20 && stopped,
                "width", width, "height", height, "same_word_and_state", true,
                "same_policy_changed_pixels", baselineChanged, "learned_policy_changed_pixels", policyChanged,
                "time_and_geometry_frozen", true, "disposed_callbacks_stopped", stopped);
        } catch (Throwable error) { return Json.map("ok", false, "error_type", error.getClass().getName()); }
        finally { first.close(); second.close(); if (a != null) a.recycle(); if (baseline != null) baseline.recycle(); if (b != null) b.recycle(); }
    }

    private static long heap() { Runtime runtime = Runtime.getRuntime(); return runtime.totalMemory() - runtime.freeMemory(); }
    private static String hash(File file) throws Exception {
        if (file == null || !file.isFile() || file.length() > 16000000) throw new IllegalStateException("model file");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            for (int count; (count = input.read(buffer)) >= 0; ) if (count > 0) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }
}
