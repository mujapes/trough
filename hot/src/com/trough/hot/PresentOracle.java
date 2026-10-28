package com.trough.hot;

import android.app.Activity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** android.vulkan: native presentation bring-up (docs/PRESENT.md). A SurfaceView is added over
 * the app, and the bionic library drives Vulkan on its Surface from a worker thread. */
final class PresentOracle {
    static Map<String, Object> run(final Activity activity, final FrameLayout root, File bundle) throws Exception {
        NativeCore.load(bundle);
        final CountDownLatch ready = new CountDownLatch(1);
        final SurfaceView[] view = new SurfaceView[1];
        ui(activity, new Callable<Void>() { public Void call() {
            SurfaceView v = new SurfaceView(activity);
            v.setZOrderOnTop(true);
            v.getHolder().addCallback(new SurfaceHolder.Callback() {
                public void surfaceCreated(SurfaceHolder h) { ready.countDown(); }
                public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
                public void surfaceDestroyed(SurfaceHolder h) {}
            });
            root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, root.getHeight() / 3));
            view[0] = v;
            return null;
        }});
        try {
            if (!ready.await(10, TimeUnit.SECONDS))
                return Json.map("ok", false, "needs_visible_app", true, "error", "surface not created: Trough is not visible");
            String result = NativeCore.presentProbe(view[0].getHolder().getSurface(), 240);
            JSONObject o = new JSONObject(result);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Iterator<String> it = o.keys(); it.hasNext();) { String k = it.next(); out.put(k, o.get(k)); }
            out.put("refresh_hz", activity.getWindowManager().getDefaultDisplay().getRefreshRate());
            return out;
        } finally {
            ui(activity, new Callable<Void>() { public Void call() { if (view[0] != null) root.removeView(view[0]); return null; }});
        }
    }
    private static <T> T ui(Activity a, Callable<T> c) throws Exception {
        FutureTask<T> t = new FutureTask<>(c);
        a.runOnUiThread(t);
        return t.get(20, TimeUnit.SECONDS);
    }
}
