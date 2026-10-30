package com.trough.hot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps Trough running (the VM's failure mode was being closed; Trough must not inherit it).
 * Settings in home/.trough/power.json, all on by default:
 *   screen  FLAG_KEEP_SCREEN_ON while Trough's window is visible
 *   cpu     a partial wake lock while a bundle runs, so turns finish with the screen off
 *   back    Back sends Trough behind other apps instead of finishing the Activity (which stops
 *           the bundle and every agent session with it)
 * Also asks once for the battery-optimisation exemption (Doze would cut network and timers).
 * One owner per bundle: start() in Chat's constructor, stop() in Chat.close() before the
 * successor starts, so wake locks and the window callback never stack across reloads.
 * Swiping Trough out of Recents keeps it running: the host hands the next Activity to attach().
 */
final class Power {
    private static final String[] KEYS = {"screen", "cpu", "back"};
    private static Activity a;
    private static File home;
    private static PowerManager.WakeLock cpu;

    static synchronized void start(Activity activity, File h) {
        a = activity;
        home = h;
        apply();
        Map<String, Object> s = read();
        if (!Boolean.TRUE.equals(s.get("asked_battery")) && !batteryExempt()) {
            s.put("asked_battery", true);
            write(s);
            requestBattery();
        }
    }

    /** The window is new: flags and the Back callback go on it; the wake lock is kept. */
    static synchronized void attach(Activity activity) {
        if (a == null) return;
        a = activity;
        apply();
    }

    static synchronized void stop() {
        if (a == null) return;
        if (cpu != null && cpu.isHeld()) cpu.release();
        cpu = null;
        a.getWindow().setCallback(a);
        a = null;
    }

    /** "/awake [screen|cpu|back on|off | battery | child]"; returns the text to show. */
    static synchronized String command(String args) {
        String[] w = args.trim().isEmpty() ? new String[0] : args.trim().split("\\s+");
        if (w.length == 2 && Arrays.asList(KEYS).contains(w[0]) && (w[1].equals("on") || w[1].equals("off"))) {
            Map<String, Object> s = read();
            s.put(w[0], w[1].equals("on"));
            write(s);
            apply();
        } else if (w.length == 1 && w[0].equals("battery")) {
            if (!requestBattery()) return "could not open the battery-optimisation request\n\n" + status();
        } else if (w.length == 1 && w[0].equals("child")) {
            try { a.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (Exception e) { return "could not open Developer options: " + e + "\n\n" + status(); }
            return "Developer options opened: turn on \"Disable child process restrictions\" (stops Android killing claude/codex as phantom processes)";
        } else if (w.length != 0) return "usage: /awake [screen|cpu|back on|off] | /awake battery | /awake child\n\n" + status();
        return status();
    }

    static String status() {
        Map<String, Object> s = read();
        String phantom = phantomMonitor();
        return "screen on while visible: " + on(s, "screen") + "\n"
            + "cpu awake with screen off: " + on(s, "cpu") + (cpu != null && cpu.isHeld() ? " (held)" : "") + "\n"
            + "back keeps Trough running: " + on(s, "back") + "\n"
            + "battery optimisation exempt: " + (batteryExempt() ? "yes" : "NO (/awake battery)") + "\n"
            + "child process restrictions: " + ("false".equals(phantom) ? "disabled" : "ACTIVE (/awake child)") + "\n"
            + "Swiping from Recents keeps Trough running with an updated APK; the notification's Quit ends it.";
    }

    private static String on(Map<String, Object> s, String k) { return enabled(s, k) ? "on" : "off"; }
    private static boolean enabled(Map<String, Object> s, String k) { return !Boolean.FALSE.equals(s.get(k)); }

    private static void apply() {
        Map<String, Object> s = read();
        Window win = a.getWindow();
        if (enabled(s, "screen")) win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (enabled(s, "cpu")) {
            if (cpu == null) {
                cpu = ((PowerManager) a.getSystemService(Activity.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trough:run");
                cpu.setReferenceCounted(false);
            }
            if (!cpu.isHeld()) cpu.acquire();
        } else if (cpu != null && cpu.isHeld()) cpu.release();
        win.setCallback(enabled(s, "back") ? new BackToBackground(a) : a);
    }

    private static boolean batteryExempt() {
        return ((PowerManager) a.getSystemService(Activity.POWER_SERVICE)).isIgnoringBatteryOptimizations(a.getPackageName());
    }

    private static boolean requestBattery() {
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.getPackageName())));
            return true;
        } catch (Exception e) { return false; }
    }

    /** The developer option "Disable child process restrictions" (Android 14+) writes this flag. */
    private static String phantomMonitor() {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class)
                .invoke(null, "persist.sys.fflag.override.settings_enable_monitor_phantom_procs", "");
        } catch (Throwable e) { return ""; }
    }

    private static File file() { return new File(home, ".trough/power.json"); }

    private static Map<String, Object> read() {
        try {
            Map<String, Object> m = Json.obj(Json.parse(new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8)));
            if (m != null) return m;
        } catch (Exception ignored) {}
        return new LinkedHashMap<String, Object>();
    }

    private static void write(Map<String, Object> s) {
        StringBuilder b = new StringBuilder("{");
        for (Map.Entry<String, Object> e : s.entrySet())
            b.append(b.length() > 1 ? ", " : "").append('"').append(e.getKey()).append("\": ").append(e.getValue());
        try {
            file().getParentFile().mkdirs();
            File tmp = new File(file().getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(b.append("}\n").toString().getBytes(StandardCharsets.UTF_8)); }
            tmp.renameTo(file());
        } catch (Exception ignored) {}
    }

    /** The Activity's own window callback, except that Back moves the task behind other apps. */
    private static final class BackToBackground implements Window.Callback {
        private final Activity a;
        BackToBackground(Activity a) { this.a = a; }
        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() != KeyEvent.KEYCODE_BACK) return a.dispatchKeyEvent(e);
            // Views first (a focused view may use Back); never the Activity, whose Back finishes it.
            if (!a.getWindow().superDispatchKeyEvent(e) && e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) a.moveTaskToBack(true);
            return true;
        }
        @Override public boolean dispatchKeyShortcutEvent(KeyEvent e) { return a.dispatchKeyShortcutEvent(e); }
        @Override public boolean dispatchTouchEvent(MotionEvent e) { return a.dispatchTouchEvent(e); }
        @Override public boolean dispatchTrackballEvent(MotionEvent e) { return a.dispatchTrackballEvent(e); }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent e) { return a.dispatchGenericMotionEvent(e); }
        @Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent e) { return a.dispatchPopulateAccessibilityEvent(e); }
        @Override public View onCreatePanelView(int f) { return a.onCreatePanelView(f); }
        @Override public boolean onCreatePanelMenu(int f, Menu m) { return a.onCreatePanelMenu(f, m); }
        @Override public boolean onPreparePanel(int f, View v, Menu m) { return a.onPreparePanel(f, v, m); }
        @Override public boolean onMenuOpened(int f, Menu m) { return a.onMenuOpened(f, m); }
        @Override public boolean onMenuItemSelected(int f, MenuItem i) { return a.onMenuItemSelected(f, i); }
        @Override public void onWindowAttributesChanged(WindowManager.LayoutParams p) { a.onWindowAttributesChanged(p); }
        @Override public void onContentChanged() { a.onContentChanged(); }
        @Override public void onWindowFocusChanged(boolean f) { a.onWindowFocusChanged(f); }
        @Override public void onAttachedToWindow() { a.onAttachedToWindow(); }
        @Override public void onDetachedFromWindow() { a.onDetachedFromWindow(); }
        @Override public void onPanelClosed(int f, Menu m) { a.onPanelClosed(f, m); }
        @Override public boolean onSearchRequested() { return a.onSearchRequested(); }
        @Override public boolean onSearchRequested(SearchEvent e) { return a.onSearchRequested(e); }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback c) { return a.onWindowStartingActionMode(c); }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback c, int t) { return a.onWindowStartingActionMode(c, t); }
        @Override public void onActionModeStarted(ActionMode m) { a.onActionModeStarted(m); }
        @Override public void onActionModeFinished(ActionMode m) { a.onActionModeFinished(m); }
        @Override public void onProvideKeyboardShortcuts(java.util.List<android.view.KeyboardShortcutGroup> d, Menu m, int id) { a.onProvideKeyboardShortcuts(d, m, id); }
        @Override public void onPointerCaptureChanged(boolean c) { a.onPointerCaptureChanged(c); }
    }
}
