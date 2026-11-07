package com.trough.hot;

import android.app.Activity;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Bundle entry point (contract: docs/HOST.md, app/.../HotLoader.java). Two halves:
 *   boot   -- the boot, always the signed bundle's own copy: installs the root and payloads,
 *             seeds files/src, builds it with Trough's own toolchain (Self) and hands off.
 *             Runs with or without a window (boot); start is the v1 host's way in.
 *   run    -- Trough itself, normally the copy Trough just built: its own UI (Chat) over an
 *             agent it runs directly (Agent: claude, codex, ...). No daemon, no server, no WebView.
 * If the self build fails, the boot runs the bundle's own copy of run, so Trough always comes up.
 *
 * Replacement is seamless (docs/HOT_RELOAD.md "The Forge lesson"): a new run copy, or a new
 * bundle (replace), takes the running one's state as plain data (handoffState: the feed, draft,
 * selection, scroll, agent), builds its screen beside the old one and swaps them in one UI
 * task, so no frame is blank or reset. Source writes request a build without waiting for a turn.
 */
public final class Main {
    public static android.os.IBinder slotBind(android.app.Service service, android.content.Intent intent) { android.os.IBinder container=NativeContainerProbe.bind(service,intent); return container!=null?container:GameProcessProbe.bind(service,intent); }
    static Map<String,Object> resumeTransferredCodex(String id) throws Exception {
        if(chat==null) return Json.map("ok",false,"error","chat unavailable");
        return chat.resumeTransferredCodex(id);
    }
    public static void buildProgress(final Map<String,Object> progress) {
        MAIN.post(new Runnable() { public void run() {
            if(chat!=null) { chat.showWork(progress); return; }
            try {
                if(predecessor!=null) { predecessor.getMethod("buildProgress",Map.class).invoke(null,progress); return; }
                if(runningDispatch!=null) runningDispatch.invoke(null,bootContext,"build.progress",new Object[]{progress});
            } catch(Exception ignored) {}
        }});
    }
    static void jumpToBottom(){if(chat!=null)chat.jumpToBottom();}
    static Map<String,Object> surfaceState() { return chat==null?Json.map():chat.surfaceState(); }
    static Map<String,Object> verifyCodexTurn() {
        return chat==null ? Json.map("ok",false,"error","chat unavailable") : chat.verifyCodexTurn();
    }
    static Map<String,Object> codexVerificationState() {
        return chat==null ? Json.map("ok",false,"error","chat unavailable") : chat.codexVerificationState();
    }
    static Map<String,Object> configureCodex(String model, String effort) throws Exception {
        return chat==null ? Json.map("ok",false,"error","chat unavailable") : chat.configureCodex(model,effort);
    }
    static Map<String,Object> codexState() {
        return chat==null ? Json.map("initialized",false) : chat.codexState();
    }
    static Map<String,Object> workControl(String command) {
        return chat==null ? Json.map("ok",false,"error","chat unavailable") : chat.controlWork(command);
    }
    static Map<String,Object> stopCodex() {
        if(chat==null)return Json.map("ok",false,"error","chat unavailable");
        chat.interruptResponse();return Json.map("ok",true,"session",chat.codexState());
    }
    static Map<String,Object> recoverCodex() {
        return chat==null ? Json.map("ok",false,"error","chat unavailable") : chat.recoverCodex();
    }
    static Map<String,Object> inputOracle() { return chat==null?Json.map("ok",false,"error","chat unavailable"):chat.inputOracle(); }
    /** The live selected conversation, shared by the chat and Cosmos input surfaces. UI thread only. */
    public static Map<String,Object> cosmicChatState() {
        return chat == null ? Json.map("ready", false, "busy", false, "selected_agent", "", "model", "",
            "assistant_text", "", "error", "Chat unavailable", "status", "Chat unavailable",
            "waiting_permission", false, "auth_required", false) : chat.cosmicState();
    }
    public static Map<String,Object> sendCosmicMessage(String text) {
        return chat == null ? Json.map("ok", false, "accepted", false, "error", "Chat unavailable")
            : chat.sendFromCosmos(text);
    }
    private static Chat chat;
    private static Development development;
    private static SourceWatch sourceWatch;
    private static UiWatchdog uiWatchdog;
    static void recordTransportStall(Map<String,Object> health,Map<String,Object> detail){if(uiWatchdog!=null)uiWatchdog.transportStall(health,detail);}

    private static FrameLayout reloadRoot;
    static long liveWorkUs(){return uiWatchdog==null?0:uiWatchdog.liveWorkUs();}
    static long liveGpuUs(){return uiWatchdog==null?-1:uiWatchdog.liveGpuUs();}
    static long liveJankUs(){return uiWatchdog==null?0:uiWatchdog.liveJankUs();}
    static long liveJankAge(){return uiWatchdog==null?-1:uiWatchdog.liveJankAge();}
    static long liveFrameUs(){return uiWatchdog==null?0:uiWatchdog.liveFrameUs();}
    static long liveFrameCount(){return uiWatchdog==null?0:uiWatchdog.liveFrameCount();}
    static Map<String,Object> liveReloadState() {
        Map<String,Object> state=sourceWatch == null ? Json.map("watching", false) : sourceWatch.state();
        if(reloadRoot!=null) {
            Map<String,Object> resident=ReloadDispatcher.state(reloadRoot);
            boolean pending=Boolean.TRUE.equals(state.get("pending")) || Boolean.TRUE.equals(resident.get("pending")) || Boolean.TRUE.equals(resident.get("native_pending"));
            boolean building=Boolean.TRUE.equals(state.get("building")) || Boolean.TRUE.equals(resident.get("building")) || Boolean.TRUE.equals(resident.get("native_building"));
            state.putAll(resident);state.put("pending",pending);state.put("building",building);
        }
        return state;
    }

    // Boot state (signed-bundle copy only): what is running now, so it can be stopped or replaced.
    private static Method runningStop, runningAttached, runningHandoff, runningIntent, runningDispatch;
    // Set when HotLoader replaces this bundle. Boot and reload threads that finish afterwards
    // must not enter: a superseded bundle starting late would run beside its successor.
    private static boolean superseded, stoppingRun;
    // A string literal is interned once per VM, so this is the same object in every bundle's and
    // every self build's class loader: a lock that spans them.
    private static final Object BOOT_LOCK = "com.trough.hot.boot";
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static android.content.Context bootContext;
    private static Activity bootActivity;     // the current window, or null
    private static FrameLayout bootRoot;
    private static File bootDir;
    private static JSONObject bootPayloads;
    private static Class<?> predecessor;      // the bundle we replace, until we take over from it
    private static final java.util.ArrayList<android.content.Intent> heldIntents = new java.util.ArrayList<>();

    /** v1 host: an Activity exists. */
    public static void start(final Activity activity, final FrameLayout root, final File dir) {
        bootActivity = activity;
        boot(activity.getApplicationContext(), root, dir);
    }

    /** v2 host: process start, with or without a window. */
    public static void boot(final android.content.Context app, final FrameLayout root, final File dir) {
        bootContext = app;
        bootRoot = root;
        bootDir = dir;
        if (root.getChildCount() == 0) {
            TextView wait = new TextView(root.getContext());
            wait.setText("Trough is starting");
            wait.setPadding(48, 96, 48, 48);
            root.addView(wait);
        }
        bootThread(app, dir);
    }

    /** v2 host, a bundle already running: prepare beside it, then take over (takeover()). */
    public static void replace(final android.content.Context app, final FrameLayout root, final File dir, Class<?> previous, Activity window) {
        predecessor = previous;
        bootActivity = window;
        bootContext = app;
        bootRoot = root;
        bootDir = dir;
        LegacyChatRecovery.request(app,dir,previous);
        bootThread(app, dir);
    }

    private static void bootThread(final android.content.Context app, final File dir) {
        new Thread(new Runnable() {
            @Override public void run() {
                final JSONObject boot = new JSONObject();
                Class<?> self = null;
                // A newer bundle can arrive while an older boot still installs; never two at once.
                synchronized (BOOT_LOCK) { try {
                    SourceCheckpoint.capture(app);
                    step(boot, "root");
                    Root.install(app, dir);      // texec, patched glibc, resolv.conf
                    boot.put("hostexec", HostExec.ensure(app));
                    step(boot, "payloads");
                    // The root zip only bootstraps: once dpkg owns d/, it is never fetched again.
                    bootPayloads = Payloads.ensure(app, dir, rootSkip(app));
                    if (bootPayloads.has("root")) {
                        long t = System.nanoTime();
                        boot.put("root_install", DebRoot.install(app, bootPayloads.getString("root")));
                        boot.put("root_install_ms", (System.nanoTime() - t) / 1_000_000);
                    } else if (DebRoot.present(app)) boot.put("root_install", "kept: dpkg owns d/");
                    if (DebRoot.present(app)) boot.put("root_files", DebRoot.converge(app, dir));
                    step(boot, "seed");
                    boot.put("seed", Self.seed(app, dir));
                    step(boot, "self_build");
                    JSONObject b = new JSONObject();
                    boot.put("self_build", b);
                    File built = Self.build(app, Self.src(app), dir, bootPayloads, Self.builds(app), b);
                    self = Self.load(app, built, dir, Main.class.getName());
                    runningKey = built.getName();
                    if (DebRoot.present(app)) {
                        step(boot, "self_native");
                        JSONObject n = new JSONObject();
                        boot.put("self_native", n);
                        try { native_(app, dir, n); } catch (Throwable e) { n.put("error", String.valueOf(e)); }
                    }
                    boot.put("running", "self " + built.getName().substring(0, 12));
                } catch (Throwable e) {
                    put(boot, "self_error", String.valueOf(e));
                    put(boot, "running", "bundle");
                } }
                step(boot, "enter");
                final Class<?> entry = self != null ? self : Main.class;
                MAIN.post(new Runnable() { @Override public void run() {
                    if (superseded) return;
                    if (predecessor != null) takeover(entry, boot);
                    else { swap(entry, boot, null); flushIntents(); }
                } });
            }
        }, "trough-boot").start();
    }

    /** Takes over once the old UI reaches its next safe handoff boundary. */
    private static void takeover(final Class<?> entry, final JSONObject boot) {
        if (superseded) return;
        Object state;
        try {
            Method h = method(predecessor, "handoff");
            state = h == null ? new java.util.HashMap<String, Object>() : h.invoke(null);
        } catch (Throwable t) { state = new java.util.HashMap<String, Object>(); }
        if (state == null) {   // a gesture or durable publication still owns the current views
            MAIN.postDelayed(new Runnable() { @Override public void run() { takeover(entry, boot); } }, 16);
            return;
        }
        java.util.List<android.view.View> old = children(bootRoot);
        try { predecessor.getMethod("stop").invoke(null); } catch (Throwable ignored) {}
        predecessor = null;
        swapIn(entry, boot, state, old);
        flushIntents();
    }

    /** Same bundle, new run copy (reload) or first entry: hand off from what runs, then swap. */
    private static void swap(final Class<?> entry, final JSONObject boot, final Runnable done) {
        if (superseded) { if (done != null) done.run(); return; }
        Object state = null;
        if (runningStop != null) {
            try { state = runningHandoff == null ? new java.util.HashMap<String, Object>() : runningHandoff.invoke(null); }
            catch (Throwable t) { state = new java.util.HashMap<String, Object>(); }
            if (state == null) {   // keep the current UI until its short handoff boundary
                MAIN.postDelayed(new Runnable() { @Override public void run() { swap(entry, boot, done); } }, 16);
                return;
            }
        }
        java.util.List<android.view.View> old = children(bootRoot);
        stopRunning();
        swapIn(entry, boot, state, old);
        if (done != null) done.run();
    }

    /** One UI task: the new screen is added before the old one is removed, so no frame is empty. */
    private static void swapIn(Class<?> entry, JSONObject boot, Object state, java.util.List<android.view.View> old) {
        enter(entry, boot, state);
        for (android.view.View v : old) bootRoot.removeView(v);
    }

    private static java.util.List<android.view.View> children(FrameLayout root) {
        java.util.List<android.view.View> l = new java.util.ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) l.add(root.getChildAt(i));
        return l;
    }

    private static Method method(Class<?> c, String name, Class<?>... types) {
        try { return c.getMethod(name, types); } catch (Throwable t) { return null; }
    }

    /** HotLoader: live state, or null until a gesture/publication reaches a safe boundary. */
    public static Object handoff() throws Exception {
        if (predecessor != null) { Method h = method(predecessor, "handoff"); return h == null ? new java.util.HashMap<String, Object>() : h.invoke(null); }
        if (runningHandoff != null) return runningHandoff.invoke(null);
        return new java.util.HashMap<String, Object>();
    }

    /** HotLoader: the window went away; the bundle keeps running. */
    public static void detach() throws Exception {
        bootActivity = null;
        if (predecessor != null) { Method d = method(predecessor, "detach"); if (d != null) d.invoke(null); }
        if (runningAttached != null) runningAttached.invoke(null, (Object) null);
    }

    /** HotLoader: an Intent for Trough (shares, links, files, ...). */
    public static void intent(android.content.Intent i) throws Exception {
        if (predecessor != null) { Method m = method(predecessor, "intent", android.content.Intent.class); if (m != null) m.invoke(null, i); return; }
        if (runningIntent != null) { runningIntent.invoke(null, i); return; }
        synchronized (heldIntents) { if (heldIntents.size() < 16) heldIntents.add(i); }
    }

    private static void flushIntents() {
        java.util.List<android.content.Intent> held;
        synchronized (heldIntents) { held = new java.util.ArrayList<>(heldIntents); heldIntents.clear(); }
        for (android.content.Intent i : held) try { intent(i); } catch (Throwable ignored) {}
    }

    /** HotLoader: a component or Activity/Application callback (docs/HOST.md). */
    public static Object dispatch(Object host, String event, Object[] args) throws Exception {
        if (predecessor != null) { Method m = method(predecessor, "dispatch", Object.class, String.class, Object[].class); return m == null ? null : m.invoke(null, host, event, args); }
        Method m = runningDispatch;
        return m == null ? null : m.invoke(null, host, event, args);
    }

    /**
     * Boot side of reload: called by saved source edits, turn end, and /reload. Rebuilds
     * files/src; swaps only if the build differs from what runs. On failure the old build keeps
     * running and the build log lands in work/.trough/build.log, where the agent can read it.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean reloading = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean reloadPending = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile String runningKey = "";

    private static void reloadFinished() {
        reloading.set(false);
        if (!superseded && reloadPending.get()) MAIN.post(RELOAD);
    }

    private static final Runnable RELOAD = new Runnable() {
        @Override public void run() {
            if (superseded || bootPayloads == null) return;
            reloadPending.set(true);
            if (!reloading.compareAndSet(false, true)) return;
            reloadPending.set(false);
            new Thread(new Runnable() {
                @Override public void run() {
                    backgroundBuildPriority();
                    final JSONObject boot = new JSONObject();
                    final JSONObject b = new JSONObject();
                    final android.content.Context app = bootContext;
                    File log = new File(work(app), ".trough/build.log");
                    try {
                        boot.put("self_build", b).put("reload", true);
                        step(boot, "reload_build");
                        final File built;
                        final Class<?> entry;
                        synchronized (BOOT_LOCK) {   // never beside a bundle boot building the same tree
                            built = Self.build(app, Self.src(app), bootDir, bootPayloads, Self.builds(app), b);
                            log.delete();
                            if (DebRoot.present(app)) {   // native edits rebuild and install too
                                JSONObject n = new JSONObject();
                                boot.put("self_native", n);
                                try { native_(app, bootDir, n); } catch (Throwable ne) { n.put("error", String.valueOf(ne)); }
                            }
                            if (built.getName().equals(runningKey)) { reloadFinished(); status(boot); return; }
                            Self.commitEdits(app, Self.src(app), "agent edits (built " + built.getName().substring(0, 12) + ")");
                            entry = Self.load(app, built, bootDir, Main.class.getName());
                        }
                        boot.put("running", "self " + built.getName().substring(0, 12));
                        MAIN.post(new Runnable() { @Override public void run() {
                            if (superseded) { reloadFinished(); return; }
                            swap(entry, boot, new Runnable() { @Override public void run() {
                                runningKey = built.getName();
                                reloadFinished();
                            } });
                        } });
                    } catch (final Throwable e) {
                        put(boot, "reload_error", String.valueOf(e));
                        status(boot);
                        try {
                            log.getParentFile().mkdirs();
                            Payloads.writeSmall(log, e + "\n" + b.optString("log") + "\n");
                        } catch (Exception ignored) {}
                        reloadFinished();
                        buildProgress(Json.map("title","Trough · self build","stage","build failed","state","failed",
                            "message","Continuing with the running build; details in work/.trough/build.log"));
                    }
                }
            }, "trough-reload").start();
        }
    };

    private static void backgroundBuildPriority() {
        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
        catch (RuntimeException ignored) {}
    }

    /** Watches owned source directories, never polls or hashes the native dependency tree.
     * Each run owns its observers. The stable bundle's reload Runnable remains the authority,
     * including when that older copy predates this watcher. */
    private static final class SourceWatch {
        private static final int EVENTS = android.os.FileObserver.CLOSE_WRITE | android.os.FileObserver.MOVED_TO
            | android.os.FileObserver.CREATE | android.os.FileObserver.DELETE | android.os.FileObserver.MOVED_FROM
            | android.os.FileObserver.DELETE_SELF | android.os.FileObserver.MOVE_SELF;
        private final File src;
        private final Runnable reload;
        private final java.util.concurrent.atomic.AtomicBoolean building;
        private final java.util.LinkedHashMap<String,android.os.FileObserver> observers = new java.util.LinkedHashMap<>();
        final ReloadRequests requests = new ReloadRequests();
        private volatile boolean active;
        private boolean requesting;
        private long lastRequestMs, dispatchedBuilds;
        final Runnable trigger = new Runnable() { @Override public void run() { request(); } };
        private final Runnable drain = new Runnable() { @Override public void run() {
            if (!active || !requests.pending()) return;
            if (requesting || (building != null && building.get())) { MAIN.postDelayed(this, 250); return; }
            if (requests.claim(false)) {
                dispatchedBuilds++;
                requesting = true;
                Thread requestThread = new Thread(new Runnable() { public void run() {
                    backgroundBuildPriority();
                    try { if (active) reload.run(); }
                    catch (Throwable error) { android.util.Log.e("trough", "live reload request failed", error); }
                    finally { MAIN.post(new Runnable() { public void run() {
                        requesting = false;
                        if (active && requests.pending()) {
                            MAIN.removeCallbacks(drain);
                            MAIN.postDelayed(drain, 250);
                        }
                    }}); }
                }}, "trough-reload-request");
                requestThread.setPriority(Thread.MIN_PRIORITY);
                requestThread.start();
            }
        } };

        SourceWatch(File src, Runnable reload) {
            this.src = src;
            this.reload = reload;
            java.util.concurrent.atomic.AtomicBoolean flag = null;
            // A run is commonly entered by the older signed bundle's Main. Its callback already
            // exposes this flag internally; observing it avoids losing edits while it is busy.
            try {
                Class<?> owner = reload.getClass().getEnclosingClass();
                java.lang.reflect.Field field = owner == null ? reload.getClass().getDeclaredField("building") : owner.getDeclaredField("reloading");
                field.setAccessible(true);
                Object value = field.get(owner == null ? reload : null);
                if (value instanceof java.util.concurrent.atomic.AtomicBoolean)
                    flag = (java.util.concurrent.atomic.AtomicBoolean) value;
            } catch (Throwable e) { android.util.Log.w("trough", "source watcher cannot observe reload busy state", e); }
            building = flag;
        }

        void start() {
            active = true;
            watch(src, "", 0);
        }

        void request() {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                MAIN.post(trigger);
                return;
            }
            if (!active) return;
            requests.request();
            lastRequestMs = System.currentTimeMillis();
            MAIN.removeCallbacks(drain);
            MAIN.postDelayed(drain, 350);
        }

        private void watch(final File directory, final String relative, int depth) {
            if (!active || depth > 16 || observers.size() >= 128 || observers.containsKey(relative)
                || !ReloadRequests.sourceDirectory(relative) || !directory.isDirectory() || isLink(directory)) return;
            android.os.FileObserver observer = new android.os.FileObserver(directory.getAbsolutePath(), EVENTS) {
                @Override public void onEvent(final int event, final String path) {
                    MAIN.post(new Runnable() { @Override public void run() {
                        changed(relative, event, path);
                    } });
                }
            };
            observers.put(relative, observer); // retain strongly: FileObserver otherwise stops at GC
            observer.startWatching();
            File[] children = directory.listFiles();
            if (children != null) for (File child : children) {
                String next = relative.length() == 0 ? child.getName() : relative + "/" + child.getName();
                if (ReloadRequests.sourceDirectory(next) && child.isDirectory()) watch(child, next, depth + 1);
            }
        }

        private void changed(String relative, int event, String path) {
            if (!active) return;
            String next = path == null ? relative : relative.length() == 0 ? path : relative + "/" + path;
            boolean directory = (event & 0x40000000) != 0 || path == null;
            if (directory) {
                if (!ReloadRequests.sourceDirectory(next)) return;
                if ((event & (android.os.FileObserver.DELETE | android.os.FileObserver.MOVED_FROM
                    | android.os.FileObserver.DELETE_SELF | android.os.FileObserver.MOVE_SELF)) != 0) {
                    java.util.Iterator<Map.Entry<String,android.os.FileObserver>> entries = observers.entrySet().iterator();
                    while (entries.hasNext()) {
                        Map.Entry<String,android.os.FileObserver> entry = entries.next();
                        if (entry.getKey().equals(next) || entry.getKey().startsWith(next + "/")) {
                            entry.getValue().stopWatching();
                            entries.remove();
                        }
                    }
                    request();
                }
                if ((event & (android.os.FileObserver.CREATE | android.os.FileObserver.MOVED_TO)) != 0) {
                    watch(new File(src, next), next, next.split("/").length);
                    request();
                }
            } else if ((event & (android.os.FileObserver.CLOSE_WRITE | android.os.FileObserver.MOVED_TO
                | android.os.FileObserver.DELETE | android.os.FileObserver.MOVED_FROM)) != 0
                && ReloadRequests.sourceFile(next)) request();
        }

        void close() {
            active = false;
            requests.close();
            MAIN.removeCallbacks(drain);
            for (android.os.FileObserver observer : observers.values()) observer.stopWatching();
            observers.clear();
        }

        Map<String,Object> state() {
            return Json.map("watching",active,"directories",observers.size(),"pending",requests.pending(),
                "building",building != null && building.get(),"busy_observable",building != null,
                "requested_ms",lastRequestMs,"dispatched_builds",dispatchedBuilds,"coalesce_ms",350);
        }
    }

    /** Native code from files/src/native: built, smoke-tested, installed only if it differs. */
    private static void native_(android.content.Context activity, File dir, JSONObject n) throws Exception {
        if (Root.guest()) { n.put("installed", "guest: shares the host's native OS layer"); return; }
        File nb = Self.buildNative(activity, Self.src(activity), n);
        if (nb == null) { n.put("installed", "no native sources"); return; }
        if (Self.sameAsBundle(nb, dir)) { n.put("installed", "bundle's (identical)"); return; }
        Root.installNative(activity, nb);
        n.put("installed", "self-built " + nb.getName());
    }

    /** Runs entry's run copy: runWith (context, optional window, handoff state) or the older run(Activity, ...). */
    private static void enter(Class<?> entry, JSONObject boot, Object state) {
        try {
            Method with = method(entry, "runWith", android.content.Context.class, Activity.class, FrameLayout.class, File.class, JSONObject.class, Runnable.class, Object.class);
            if (with != null) with.invoke(null, bootContext, bootActivity, bootRoot, bootDir, boot, RELOAD, state);
            else if (bootActivity != null) entry.getMethod("run", Activity.class, FrameLayout.class, File.class, JSONObject.class, Runnable.class)
                .invoke(null, bootActivity, bootRoot, bootDir, boot, RELOAD);
            else throw new IllegalStateException("this build needs a window (no runWith)");
            runningStop = entry.getMethod("stop");
            runningAttached = method(entry, "attached", Activity.class);
            runningHandoff = method(entry, "handoffState");
            runningIntent = method(entry, "intentRun", android.content.Intent.class);
            runningDispatch = method(entry, "dispatchRun", Object.class, String.class, Object[].class);
        } catch (Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            put(boot, "enter_error", String.valueOf(cause));
            if (entry != Main.class) enter(Main.class, boot, state);
            else status(boot);
        }
    }

    /**
     * Called by HotLoader on the boot copy when a new Activity took the root view (the old one was
     * swiped from Recents or recreated) while this bundle kept running. Throws when what runs
     * cannot move, so the host stops it and starts a bundle on the new Activity instead.
     */
    public static void attach(Activity activity) throws Exception {
        bootActivity = activity;
        if (predecessor != null) { predecessor.getMethod("attach", Activity.class).invoke(null, activity); return; }
        if (runningStop == null) return;   // still booting: enter() uses bootActivity
        if (runningAttached == null) throw new UnsupportedOperationException("running build has no attached()");
        runningAttached.invoke(null, activity);
    }

    /** The running copy's half of attach (null: detached, no window). */
    public static void attached(Activity activity) {
        if (uiWatchdog != null) uiWatchdog.attach(activity);
        if (chat != null) chat.attach(activity);
        if (development != null) development.attach(activity);
    }

    /** Live transport and UI state; null only while a gesture/publication owns the old UI. */
    public static Object handoffState() {
        Object state = chat == null ? new java.util.HashMap<String, Object>() : chat.handoff();
        if (state instanceof Map && sourceWatch != null && sourceWatch.requests.pending())
            ((Map<String,Object>) state).put("live_source_pending", Boolean.TRUE);
        return state;
    }

    /** The running copy's Intents: shared text lands in the draft; everything else is noted. */
    public static void intentRun(android.content.Intent i) {
        if (chat != null) chat.intent(i);
    }

    /** The running copy's component events; screen observation is explicitly requested over development transport. */
    public static Object dispatchRun(Object host, String event, Object[] args) {
        if(chat!=null && chat.mediaResult(host,event,args)) return Boolean.TRUE;
        if("build.progress".equals(event) && args!=null && args.length==1 && args[0] instanceof Map) {
            if(chat!=null) chat.showWork((Map<String,Object>)args[0]);
            return Boolean.TRUE;
        }
        ScreenInspection.component(host,event);
        return null;
    }

    /** Slot providers (app Slot.java, in their own processes): docs/LIFT.md runs lifted code here. */
    public static void slotActivity(Activity a, android.os.Bundle saved) throws Exception {
        GuestRuntime.attach(a);
    }

    public static Object slotProvider(android.content.ContentProvider p, String event, Object[] args) {
        if ("call".equals(event) && args.length == 3 && "lift.guest.capture".equals(args[0])) {
            try { return GuestRuntime.capture(); } catch (Exception e) { return null; }
        }
        if ("call".equals(event) && args.length == 3 && "lift.guest.hide".equals(args[0])) {
            try { GuestRuntime.hide(); } catch (Exception e) { android.util.Log.e("trough", "guest hide", e); }
            return new android.os.Bundle();
        }
        if ("call".equals(event) && args.length == 3 && "lift.guest.stop".equals(args[0])) {
            try { GuestRuntime.onMain(new java.util.concurrent.Callable<Object>() { public Object call() throws Exception {
                GuestRuntime.stop(); return null;
            } }); } catch (Exception e) { android.util.Log.e("trough", "guest stop", e); }
            return new android.os.Bundle();
        }
        if ("call".equals(event) && args.length == 3 && "lift.guest.state".equals(args[0]))
            return GuestRuntime.state();
        if ("call".equals(event) && args.length == 3 && "lift.verify".equals(args[0]))
            return LiftOracle.slotVerify(p.getContext(), (android.os.Bundle) args[2]);
        if ("call".equals(event) && args.length == 3 && "lift.ping".equals(args[0])) {
            android.os.Bundle b = new android.os.Bundle();
            b.putString("pid", String.valueOf(android.os.Process.myPid()));
            return b;
        }
        if ("query".equals(event) && args.length == 5 && args[0] instanceof android.net.Uri
            && "/lift.verify".equals(((android.net.Uri) args[0]).getPath()) && args[3] instanceof String[])
            return LiftOracle.slotQuery(p.getContext(), (String[]) args[3]);
        return null;
    }

    private static void stopRunning() {
        Method m = runningStop;
        runningStop = null;   // first: when run is the boot's own copy, its stop lands back here
        runningAttached = null;
        runningHandoff = null;
        runningIntent = null;
        runningDispatch = null;
        stoppingRun = true;
        if (m != null) try { m.invoke(null); } catch (Throwable ignored) {}
        stoppingRun = false;
    }

    /**
     * Trough's own view tree as a PNG in its private diagnostic directory.
     */
    static void screenshot(final android.view.View v, final String tag) {
        try {
            int w = v.getWidth(), h = v.getHeight();
            if (w <= 0 || h <= 0) return;
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            v.draw(new android.graphics.Canvas(bm));
            File f = new File(reportDirectory(v.getContext()), "shot-" + System.currentTimeMillis() + "-" + tag + ".png");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o); }
            bm.recycle();
        } catch (Throwable ignored) {}
    }

    /** Trough itself, entered by an older boot copy (window only). */
    public static void run(final Activity activity, final FrameLayout root, final File dir, final JSONObject boot, final Runnable reload) {
        runWith(activity.getApplicationContext(), activity, root, dir, boot, reload, null);
    }

    /** Trough itself. Entered by the boot, on the UI thread; window may be null; state is the
     *  predecessor's handoffState (null: a fresh start). */
    public static void runWith(final android.content.Context activity, final Activity window, final FrameLayout root, final File dir,
                               final JSONObject boot, final Runnable reload, Object state) {
        final File home = new File(activity.getFilesDir(), "home");
        home.mkdirs();
        GameImportProbe.start(activity); // Asset-only check; never starts Unity.
        GameProcessProbe.watch(activity);
        uiWatchdog = new UiWatchdog(home);
        uiWatchdog.attach(window);
        reloadRoot=root;
        sourceWatch = new SourceWatch(Self.src(activity), ReloadDispatcher.attach(activity,root,reload));
        sourceWatch.start();
        if (state instanceof Map && Boolean.TRUE.equals(((Map<?,?>) state).get("live_source_pending"))) sourceWatch.request();
        chat = new Chat(activity, window, root, home, sourceWatch.trigger, state instanceof Map ? (Map<?, ?>) state : null,VisualSteering.modelFile(activity,dir));
        // Imported game activation disabled pending crash investigation.
        chat.probeImportedGameSurface();
        final Chat c = chat;
        JSONObject build = boot.optJSONObject("self_build");
        String identity = build == null || !boot.optString("running").startsWith("self ") ? "bundle" : build.optString("identity", "bundle");
        development = new Development(activity, window, root, dir, identity);
        if (!boot.optBoolean("reload")) root.postDelayed(new Runnable() { @Override public void run() { screenshot(root, "boot"); } }, 4000);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject payloads = Payloads.ensure(activity, dir, rootSkip(activity));
                    Creds.sync(activity, home);
                    File work = work(activity);
                    agentNotes(activity, work);
                    Map<String, String> env = Root.env(activity, activity.getCacheDir());
                    env.put("CODEX_HOME", new File(home, ".codex").getPath());
                    new File(home, ".codex").mkdirs();
                    Map<String, String> agents = new java.util.LinkedHashMap<>();
                    for (String name : new String[]{"claude", "codex"}) if (payloads.has(name)) agents.put(name, payloads.getString(name));
                    for (String name : Models.names(home)) if (!agents.containsKey(name)) agents.put(name, Models.MARK);
                    c.start(agents, work, env);
                    if (boot.optBoolean("reload")) { status(boot); return; }
                    // Self-hosting proofs, reported to the VM: the toolchain builds C and Java here,
                    // and an edited copy of Trough's own source builds and loads.
                    if (DebRoot.present(activity)) {
                        step(boot, "root_packages");
                        try {
                            JSONObject pk = DebRoot.packages(activity, dir);
                            boot.put("root_packages", pk);
                            // Newly installed packages are build inputs (native/packages.list):
                            // rebuild now rather than run without them until the next bundle.
                            if (pk.optBoolean("ok")) reload.run();
                        } catch (Throwable e) { boot.put("root_packages_error", String.valueOf(e)); }
                        // Expensive/networked regression suites are explicit development work,
                        // not competition for foreground interaction after every code change.
                        boolean diagnostics = new JSONObject(new String(Payloads.readSmall(
                            new File(dir, "manifest.json")), "UTF-8")).optBoolean("diagnostics", false);
                        boot.put("diagnostics", diagnostics);
                        if (!diagnostics) { step(boot, "done"); return; }
                        step(boot, "selftest");
                        try { boot.put("selftest", SelfTest.run(activity)); } catch (Throwable e) { boot.put("selftest_error", String.valueOf(e)); }
                        step(boot, "native_repro");
                        try { boot.put("native_repro", SelfTest.nativeRepro(activity, dir)); } catch (Throwable e) { boot.put("native_repro_error", String.valueOf(e)); }
                        step(boot, "native_edit");
                        try { boot.put("native_edit", SelfTest.nativeEdit(activity, dir)); } catch (Throwable e) { boot.put("native_edit_error", String.valueOf(e)); }
                        step(boot, "self_edit");
                        try { boot.put("self_edit", SelfTest.selfEdit(activity, dir, payloads)); } catch (Throwable e) { boot.put("self_edit_error", String.valueOf(e)); }
                        step(boot, "probe");
                        try { boot.put("probe", SelfTest.probe(activity, dir)); } catch (Throwable e) { boot.put("probe_error", String.valueOf(e)); }
                        step(boot, "providers");
                        try { boot.put("providers", SelfTest.providers()); } catch (Throwable e) { boot.put("providers_error", String.valueOf(e)); }
                        step(boot, "apt");
                        try { boot.put("apt", SelfTest.apt(activity)); } catch (Throwable e) { boot.put("apt_error", String.valueOf(e)); }
                        step(boot, "seed_merge");
                        try { boot.put("seed_merge", SelfTest.seedMerge(activity)); } catch (Throwable e) { boot.put("seed_merge_error", String.valueOf(e)); }
                        step(boot, "namespaces");
                        try { boot.put("namespaces", SelfTest.namespaces(activity, dir)); } catch (Throwable e) { boot.put("namespaces_error", String.valueOf(e)); }
                        if (payloads.has("codex")) {
                            step(boot, "codex");
                            try { boot.put("codex", SelfTest.codex(activity, payloads)); } catch (Throwable e) { boot.put("codex_error", String.valueOf(e)); }
                        }
                        step(boot, "done");
                        return;
                    }
                    status(boot);
                } catch (final Throwable e) {
                    MAIN.post(new Runnable() { @Override public void run() { c.onExit(-1, String.valueOf(e)); } });
                }
            }
        }, "trough-start").start();
    }

    private static java.util.Set<String> rootSkip(android.content.Context ctx) {
        java.util.Set<String> s = new java.util.HashSet<>();
        if (DebRoot.present(ctx)) s.add("root");
        return s;
    }

    static File work(android.content.Context ctx) {
        File w = new File(ctx.getFilesDir(), "work");
        w.mkdirs();
        return w;
    }

    private static final String NOTES_MARK = "<!-- written by Trough; delete this line to keep your own -->";

    /**
     * What the agent running inside Trough needs to know to change Trough. Agent-neutral
     * (AGENTS.md; CLAUDE.md points at it), since Trough runs arbitrary models, not just Claude.
     * Rewritten each start unless someone removed the mark line.
     */
    private static void agentNotes(android.content.Context ctx, File work) throws Exception {
        String src = Self.src(ctx).getPath();
        File home=new File(ctx.getFilesDir(),"home");
        String notes = NOTES_MARK + "\n# You are running inside Trough\n\n"
            + "Trough is the Android app you are running in. Its own source is at " + src + " (Java,\n"
            + "package com.trough.hot; entry Main.run). Edit it there to change Trough itself.\n\n"
            + "- Closed source writes automatically request a coalesced javac + d8 rebuild, including\n"
            + "  during turns. Turn end also checks. The running session is handed to the new UI.\n"
            + "- If the build fails, the old build keeps running and the error is in .trough/build.log\n"
            + "  (relative to this directory). Fix the source; the next saved edit requests another build.\n"
            + "- It is a git repo: branch main is yours (Trough commits your edits when it reloads),\n"
            + "  branch bundle is what arrived in signed bundles; bundles are merged into main, and a\n"
            + "  conflicting edit is kept on an agent-* branch. git and the toolchain are on PATH.\n"
            + "- Java 8 source against android.jar, no lambdas (no LambdaMetafactory there).\n"
            + "- Native views only: no WebView, no HTML, no local servers.\n\n"
            + "## Your userland\n"
            + "Debian trixie arm64, running natively from /data/data/t.t/d (no VM, no root). bash, git,\n"
            + "gcc 14, JDK 21 and the Debian essentials are on PATH. `apt-get install <pkg>` works: real\n"
            + "Debian packages, signatures verified, installed into the root (you are not root and need\n"
            + "no sudo). Normal Linux paths work: /usr, /etc, /tmp, /var, /home map into the root\n"
            + "(Trough's glibc and loader translate them; docs/FILESYSTEM.md). Android's own trees\n"
            + "(/data, /system, /proc, /sys, /dev, /storage) are the real ones.\n\n"
            + "## Live user messages\n"
            + "While working, check " + new File(home,".local/bin/trough-inbox").getAbsolutePath() + " list at natural tool checkpoints.\n"
            + "This is a live local queue; newer user instructions may change priorities.\n"
            + "If you pick up an instruction during this turn, run trough-inbox take MESSAGE_ID with a brief note.\n"
            + "Taking records pickup, not completion. Leave deferred instructions queued.\n"
            + "Saved messages and delivery states: " + new File(home,".trough/inbox.json").getAbsolutePath() + ".\n"
            + "Running UI diagnostics: " + new File(home,".trough/ui-runtime.json").getAbsolutePath() + ".\n"
            + "On crashes inspect " + new File(home,".local/bin/trough-diagnostics").getAbsolutePath() + " summary and crash before asking the user to explain.\n"
            + "Compressed rolling history and preserved crash snapshots: " + new File(home,".trough/diagnostics").getAbsolutePath() + ". Compare timestamps and PIDs; stale snapshots do not prove liveness.\n";
        for (String[] f : new String[][]{{"AGENTS.md", notes}, {"CLAUDE.md", NOTES_MARK + "\n@AGENTS.md\n"}}) {
            File file = new File(work, f[0]);
            if (file.exists() && !new String(Payloads.readSmall(file), "UTF-8").startsWith(NOTES_MARK)) continue;
            Payloads.writeSmall(file, f[1]);
        }
    }

    static File reportDirectory(android.content.Context context) {
        File files=context==null?new File(Root.os(),"files"):context.getFilesDir();
        File reports=new File(files,"home/.trough/reports");reports.mkdirs();return reports;
    }

    static File statusFile() {
        return new File(reportDirectory(bootContext), "status-" + System.currentTimeMillis() + ".json");
    }

    /** Writes r to its own report file (r.report, set on first write), replacing what was there. */
    static void status(JSONObject r) {
        try {
            if (!r.has("report")) r.put("report", statusFile().getPath());
            File f = new File(r.getString("report")), tmp = new File(f.getPath() + ".tmp");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) { o.write(r.toString(2).getBytes("UTF-8")); }
            tmp.renameTo(f);
        } catch (Exception ignored) {}
    }

    /** Marks the step about to run and rewrites the report: a hang still shows how far it got. */
    private static void step(JSONObject r, String next) {
        try { r.put("step", next).put("step_at_ms", System.currentTimeMillis()); } catch (Exception ignored) {}
        status(r);
        buildProgress(Json.map("title","Trough · self build","stage",next,"state","done".equals(next)?"done":"running",
            "message",next.replace('_',' '),"log",r.toString()));
    }

    private static void put(JSONObject o, String k, String v) { try { o.put(k, v); } catch (Exception ignored) {} }

    static void deleteTree(File f) {
        File[] kids = f.isDirectory() && !isLink(f) ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static boolean isLink(File f) {
        try { return android.system.Os.lstat(f.getPath()).st_mode / 4096 == 10; } catch (Exception e) { return false; }
    }

    /** Called by HotLoader on the boot copy (forwards to what runs), and by the boot on run's copy. */
    public static void stop() {
        GameProcessProbe.stopWatching();
        if (uiWatchdog != null) uiWatchdog.close();
        uiWatchdog = null;
        if (sourceWatch != null) sourceWatch.close();
        sourceWatch = null;
        if (!stoppingRun) superseded = true;   // HotLoader, not our own reload, is stopping us
        if (predecessor != null) {   // replaced again before taking over: stop what actually runs
            Class<?> p = predecessor;
            predecessor = null;
            try { p.getMethod("stop").invoke(null); } catch (Throwable ignored) {}
            return;
        }
        if (runningStop != null) { stopRunning(); Drafts.retire(); return; }
        if (development != null) development.close();
        development = null;
        Drafts.retire();   // before the successor starts: drafts saved, store released
        if (chat != null) chat.close();
        chat = null;
    }
}
