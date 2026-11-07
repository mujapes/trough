package com.trough.hot;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** The trusted self-guest's process/window owner (docs/LIFT.md). Slot.entry loads a fresh bundle
 * class loader on each callback, so retained objects belong to the process Vault, not this class's
 * statics. This is identity separation for trusted code, not containment of untrusted APKs. */
final class GuestRuntime {
    private static final String KEY = "lift.self-guest";
    private GuestRuntime() {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> vault() throws Exception {
        return (Map<String, Object>) Class.forName("com.trough.app.Vault").getField("objects").get(null);
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }

    private static Object invoke(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(o, args);
    }

    static <T> T onMain(Callable<T> work) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return work.call();
        FutureTask<T> task = new FutureTask<>(work);
        new Handler(Looper.getMainLooper()).post(task);
        try { return task.get(30, TimeUnit.SECONDS); }
        finally { task.cancel(false); }
    }

    /** Activate through the guest loader's own signature check and boot, on its UI owner. */
    static boolean activate(final Context host, final Object loader) throws Exception {
        return onMain(new Callable<Boolean>() { public Boolean call() throws Exception {
            stop();
            Field process = loader.getClass().getDeclaredField("process"); process.setAccessible(true);
            process.set(null, loader);
            boolean accepted = (Boolean) invoke(loader, "check", new Class<?>[0]);
            if (!accepted) return false;
            final Application app = (Application) host.getApplicationContext();
            Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
                public void onActivityCreated(Activity a, Bundle b) {}
                public void onActivityStarted(Activity a) { visibility(a, true); }
                public void onActivityResumed(Activity a) { visibility(a, true); }
                public void onActivityPaused(Activity a) {}
                public void onActivityStopped(Activity a) { visibility(a, false); }
                public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                public void onActivityDestroyed(Activity a) {
                    if (!slot(a)) return;
                    try { invoke(loader, "detachActivity", new Class<?>[]{Activity.class}, a); }
                    catch (Exception e) { android.util.Log.e("trough", "guest detach", e); }
                }
                private void visibility(Activity a, boolean visible) {
                    if (!slot(a)) return;
                    try { invoke(loader, "visible", new Class<?>[]{boolean.class}, visible); }
                    catch (Exception e) { android.util.Log.e("trough", "guest visibility", e); }
                }
            };
            vault().put(KEY, new Object[]{loader, app, lifecycle});
            app.registerActivityLifecycleCallbacks(lifecycle);
            ((Handler) field(loader, "main")).post((Runnable) field(loader, "poll"));
            return true;
        } });
    }

    private static boolean slot(Activity a) { return "com.trough.app.Slot$A0".equals(a.getClass().getName()); }

    static void attach(Activity a) throws Exception {
        Object[] state = (Object[]) vault().get(KEY);
        if (state == null || !slot(a)) { a.finish(); return; }
        Object loader = state[0];
        FrameLayout root = (FrameLayout) invoke(loader, "root", new Class<?>[0]);
        if (root.getParent() != null) ((android.view.ViewGroup) root.getParent()).removeView(root);
        a.setContentView(root);
        invoke(loader, "attachActivity", new Class<?>[]{Activity.class}, a);
    }

    static Bundle state() {
        try { return onMain(new Callable<Bundle>() { public Bundle call() throws Exception {
            Bundle out = new Bundle();
            Object[] state = (Object[]) vault().get(KEY);
            out.putBoolean("running", state != null);
            if (state != null) {
                Object loader = state[0];
                out.putString("bundle", String.valueOf(field(loader, "currentSha")));
                out.putString("status", String.valueOf(field(loader, "status")));
                FrameLayout root = (FrameLayout) field(loader, "root");
                out.putInt("children", root.getChildCount());
                out.putBoolean("attached", field(loader, "activity") != null);
                out.putBoolean("shown", root.isShown());
                out.putInt("width", root.getWidth()); out.putInt("height", root.getHeight());
            }
            return out;
        } }); } catch (Exception e) { Bundle out = new Bundle(); out.putString("error", String.valueOf(e)); return out; }
    }

    static Bundle capture() throws Exception {
        return onMain(new Callable<Bundle>() { public Bundle call() throws Exception {
            Bundle out = state();
            Object[] state = (Object[]) vault().get(KEY);
            if (state == null) return out;
            Object loader = state[0];
            FrameLayout root = (FrameLayout) field(loader, "root");
            int w = root.getWidth(), h = root.getHeight();
            if (!root.isAttachedToWindow() || w < 1 || h < 1) return out;
            android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            try {
                root.draw(new android.graphics.Canvas(image));
                java.io.File inbox = (java.io.File) invoke(loader, "sharedDir", new Class<?>[0]);
                java.io.File file = new java.io.File(inbox.getParentFile(), "guest-window.png");
                try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream);
                }
                out.putString("screenshot", file.getPath());
            } finally { image.recycle(); }
            return out;
        } });
    }

    static void hide() throws Exception {
        onMain(new Callable<Object>() { public Object call() throws Exception {
            Object[] state = (Object[]) vault().get(KEY);
            if (state != null) {
                Activity a = (Activity) field(state[0], "activity");
                if (a != null) a.finish();
            }
            return null;
        } });
    }

    static void stop() throws Exception {
        Object[] state = (Object[]) vault().remove(KEY);
        if (state == null) return;
        Object loader = state[0];
        ((Application) state[1]).unregisterActivityLifecycleCallbacks((Application.ActivityLifecycleCallbacks) state[2]);
        ((Handler) field(loader, "main")).removeCallbacksAndMessages(null);
        invoke(loader, "stopCurrent", new Class<?>[0]);
    }
}
