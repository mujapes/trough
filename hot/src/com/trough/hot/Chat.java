package com.trough.hot;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trough's own UI over an Agent (any backend: claude, codex, ...): native views, no HTML, no WebView. Forge's dock is the spec:
 * a feed of the conversation (live text, compact tool lines, inline permission cards), a status
 * strip, and a prompt dock whose button is Send when idle and Stop while claude works.
 */
final class Chat implements Agent.Listener {
    private static final int BG = 0xFF0B0F0D, FG = 0xFFE8EFE6, DIM = 0xFF8FA394, USER = 0xFF9FD3A8,
        TOOL = 0xFF7FA7C9, WARN = 0xFFE0B26A, PANEL = 0xFF151B18;

    private final Activity a;
    private final File home;
    private final Runnable reload;   // the boot's: rebuild Trough from files/src and swap it in
    private final LinearLayout feed;
    private final ScrollView scroll;
    private final TextView status;
    private final EditText input;
    private final Button send;
    private Agent session;
    private ClaudeAgent.Login login;   // a /login in progress: the next message is its code
    private int generation;          // events from a replaced agent are dropped
    private Map<String, String> agents = new java.util.LinkedHashMap<>();   // name -> executable
    private String agent = "claude";
    private File cwd;
    private Map<String, String> env;
    private TextView live;          // the assistant text being streamed
    private boolean busy;
    private String model = "", sessionId;
    private double cost;

    Chat(Activity activity, FrameLayout root, File home, Runnable reload) {
        a = activity;
        this.home = home;
        this.reload = reload;
        a.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setFitsSystemWindows(true);

        status = text(DIM, 12);
        status.setPadding(dp(14), dp(8), dp(14), dp(6));
        status.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickAgent(v); }
        });
        col.addView(status);

        scroll = new ScrollView(a);
        feed = new LinearLayout(a);
        feed.setOrientation(LinearLayout.VERTICAL);
        feed.setPadding(dp(14), dp(4), dp(14), dp(12));
        scroll.addView(feed);
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout dock = new LinearLayout(a);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.BOTTOM);
        dock.setPadding(dp(8), dp(6), dp(8), dp(8));
        input = new EditText(a);
        input.setTextColor(FG);
        input.setHintTextColor(DIM);
        input.setHint("Message Claude");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setMaxLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setBackground(round(PANEL, 18));
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        dock.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        send = new Button(a);
        send.setAllCaps(false);
        send.setTextColor(BG);
        send.setBackground(round(USER, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        dock.addView(send, lp);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (busy) stopTurn(); else submit(); }
        });
        col.addView(dock);
        root.addView(col);
        paint();
    }

    /**
     * agents: every backend this bundle carries, name -> executable. The one last picked (tap the
     * status strip) is remembered in home/.trough-agent; each keeps its own conversation to resume.
     */
    void start(Map<String, String> agents, File cwd, Map<String, String> env) {
        this.agents = agents;
        this.cwd = cwd;
        this.env = env;
        try {
            File f = new File(home, ".trough-agent");
            String saved = f.exists() ? new String(Payloads.readSmall(f), "UTF-8").trim() : "";
            if (agents.containsKey(saved)) agent = saved;
        } catch (Exception ignored) {}
        if (!agents.containsKey(agent) && !agents.isEmpty()) agent = agents.keySet().iterator().next();
        ui(new Runnable() { @Override public void run() { connect(); } });
    }

    private File sessionFile() { return new File(home, "claude".equals(agent) ? ".trough-session" : ".trough-session-" + agent); }

    private void connect() {
        if (session != null) session.close();
        session = null;
        busy = false;
        model = "";
        final int gen = ++generation;
        String exe = agents.get(agent), resume = "";
        try { if (sessionFile().exists()) resume = new String(Payloads.readSmall(sessionFile()), "UTF-8").trim(); } catch (Exception ignored) {}
        try {
            Agent.Listener l = bound(gen);
            if (Models.MARK.equals(exe)) {
                String key = Models.readKey(home, agent);
                if (key == null) {
                    note(agent + " needs an API key: send  /key <your key>  (kept in Trough's private storage, never sent to a model, never shown)");
                    paint();
                    return;
                }
                Map<String, Object> cfg = Models.config(home, agent);
                session = new TroughAgent(Provider.of(cfg, key), agent + " (" + Json.str(cfg, "model") + ")", cwd, env,
                    new File(home, ".trough/conversations"), resume, l);
            } else if ("codex".equals(agent)) {
                session = new CodexAgent(exe, cwd, env, resume, null, "danger-full-access", l);
            } else {
                List<String> cmd = new ArrayList<>(Arrays.asList(exe, "--model", "opus"));
                if (!resume.isEmpty()) { cmd.add("--resume"); cmd.add(resume); }
                session = new ClaudeAgent(cmd, cwd, env, l);
            }
            note(agent + " starting" + (resume.isEmpty() ? "" : " (resuming)"));
            if ("claude".equals(agent) && new File(home, ".claude/.credentials.json").exists()
                    && !new File(home, ".claude/.trough-own-login").exists())
                note("claude here uses a copy of the VM's login; its refresh token rotates, so the two can log each other out. Send /login to give this phone its own.");
        } catch (Exception e) {
            note("could not start " + agent + ": " + e);
        }
        paint();
    }

    private void pickAgent(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(a, anchor);
        for (String name : agents.keySet()) menu.getMenu().add(name.equals(agent) ? name + "  ✓" : name);
        menu.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                String name = item.getTitle().toString().replace("  ✓", "");
                if (name.equals(agent) || busy) return true;
                agent = name;
                try { Payloads.writeSmall(new File(home, ".trough-agent"), name); } catch (Exception ignored) {}
                connect();
                return true;
            }
        });
        menu.show();
    }

    /** Claude's own sign-in for this phone (ClaudeAgent.Login): card with the page, then the code. */
    private void startLogin() {
        if (login != null) login.cancel();
        try {
            login = new ClaudeAgent.Login(agents.get("claude"), cwd, env, this, new ClaudeAgent.Login.Done() {
                @Override public void onDone(final boolean ok, final String detail) {
                    ui(new Runnable() { @Override public void run() {
                        login = null;
                        if (ok) try { Payloads.writeSmall(new File(home, ".claude/.trough-own-login"), "1"); } catch (Exception ignored) {}
                        note(ok ? "claude: signed in on this phone" : "claude sign-in did not complete: " + detail);
                        report("login", ok ? "ok" : "failed");
                        if (ok) connect();
                    } });
                }
            });
            note("claude sign-in started: open the page, sign in, then paste the code shown there as a message");
        } catch (Exception e) {
            note("could not start claude sign-in: " + e);
        }
    }

    /** This Chat as the listener of agent generation gen only. */
    private Agent.Listener bound(final int gen) {
        final Chat c = this;
        return new Agent.Listener() {
            boolean live() { return gen == generation; }
            @Override public void onInit(String id, String m) { if (live()) c.onInit(id, m); }
            @Override public void onTextDelta(String t) { if (live()) c.onTextDelta(t); }
            @Override public void onAssistantText(String t) { if (live()) c.onAssistantText(t); }
            @Override public void onToolUse(String id, String n, Map<String, Object> in) { if (live()) c.onToolUse(id, n, in); }
            @Override public void onToolResult(String id, String t, boolean e) { if (live()) c.onToolResult(id, t, e); }
            @Override public void onPermission(String r, String t, Map<String, Object> in) { if (live()) c.onPermission(r, t, in); }
            @Override public void onResult(boolean ok, double cost, long ms, String t) { if (live()) c.onResult(ok, cost, ms, t); }
            @Override public void onExit(int code, String err) { if (live()) c.onExit(code, err); }
            @Override public void onAuth(String url, String code) { if (live()) c.onAuth(url, code); }
        };
    }

    void close() { if (session != null) session.close(); }

    private void submit() {
        String text = input.getText().toString().trim();
        if (text.equals("/reload")) {
            input.setText("");
            note("rebuilding Trough from files/src (swaps in only if it changed)");
            reload.run();
            return;
        }
        if (login != null && !text.isEmpty() && !text.startsWith("/")) {
            input.setText("");   // the sign-in code goes to claude's login only, never the feed or a model
            login.code(text);
            note("code sent, finishing sign-in");
            return;
        }
        if (text.equals("/login")) {
            input.setText("");
            if (!"claude".equals(agent)) { note("/login is for claude; " + agent + " signs in its own way"); return; }
            startLogin();
            return;
        }
        if (text.startsWith("/key ")) {
            input.setText("");   // the secret never reaches the feed or a model
            if (!Models.MARK.equals(agents.get(agent))) { note("/key is for models Trough runs itself (models.json); " + agent + " signs in its own way"); return; }
            try { Models.writeKey(home, agent, text.substring(5).trim()); note("key saved for " + agent); connect(); }
            catch (Exception e) { note("could not save key: " + e); }
            return;
        }
        if (text.isEmpty() || session == null) return;
        input.setText("");
        TextView u = text(USER, 16);
        u.setText(text);
        u.setPadding(0, dp(14), 0, dp(4));
        add(u);
        live = null;
        busy = true;
        paint();
        session.send(text);
    }

    private void stopTurn() {
        if (session != null) session.interrupt();
        note("interrupted");
    }

    // --- Agent.Listener: called on the reader thread, so everything hops to the UI thread ---

    @Override public void onInit(final String id, final String m) {
        ui(new Runnable() { @Override public void run() {
            sessionId = id;
            model = m == null ? "" : m;
            try { Payloads.writeSmall(sessionFile(), id); } catch (Exception ignored) {}
            report("init", "model " + model);
            paint();
        } });
    }

    @Override public void onTextDelta(final String t) {
        ui(new Runnable() { @Override public void run() {
            if (live == null) {
                live = text(FG, 16);
                live.setPadding(0, dp(8), 0, dp(4));
                live.setTextIsSelectable(true);
                add(live);
            }
            live.append(t);
            follow();
        } });
    }

    @Override public void onAssistantText(final String t) {
        ui(new Runnable() { @Override public void run() {
            if (live == null) {  // no deltas (e.g. a resumed history): show the whole block
                TextView v = text(FG, 16);
                v.setText(t);
                v.setPadding(0, dp(8), 0, dp(4));
                v.setTextIsSelectable(true);
                add(v);
            }
            live = null;  // the next text block starts a new view
        } });
    }

    @Override public void onToolUse(final String id, final String name, final Map<String, Object> in) {
        ui(new Runnable() { @Override public void run() {
            TextView v = text(TOOL, 13);
            v.setTypeface(Typeface.MONOSPACE);
            v.setText("▸ " + name + "  " + summary(name, in));
            v.setPadding(0, dp(6), 0, 0);
            add(v);
            live = null;
        } });
    }

    @Override public void onToolResult(String toolUseId, final String t, final boolean err) {
        ui(new Runnable() { @Override public void run() {
            String first = t == null ? "" : t.trim();
            int nl = first.indexOf('\n');
            int lines = first.isEmpty() ? 0 : first.split("\n").length;
            if (nl >= 0) first = first.substring(0, nl);
            if (first.length() > 160) first = first.substring(0, 160) + "…";
            TextView v = text(err ? WARN : DIM, 12);
            v.setTypeface(Typeface.MONOSPACE);
            v.setText("  " + first + (lines > 1 ? "  (+" + (lines - 1) + " lines)" : ""));
            add(v);
        } });
    }

    /** A backend's own sign-in, in the system browser (never a WebView). */
    @Override public void onAuth(final String url, final String code) {
        ui(new Runnable() { @Override public void run() {
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(round(PANEL, 12));
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            TextView what = text(WARN, 14);
            what.setText("Sign in to " + agent + (code == null ? "" : ", then enter this code:"));
            card.addView(what);
            if (code != null) {
                TextView c = text(FG, 22);
                c.setTypeface(Typeface.MONOSPACE);
                c.setTextIsSelectable(true);
                c.setText(code);
                card.addView(c);
            }
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.END);
            Button open = button("Open sign-in page", USER, BG);
            row.addView(open);
            card.addView(row);
            open.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (code != null) {
                        android.content.ClipboardManager cb = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("code", code));
                    }
                    a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                }
            });
            add(card);
            report("auth", agent + " sign-in requested");
        } });
    }

    @Override public void onPermission(final String rid, final String tool, final Map<String, Object> in) {
        ui(new Runnable() { @Override public void run() {
            final Agent asker = session;   // answer the agent that asked, even after a switch
            final LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(round(PANEL, 12));
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            final TextView what = text(WARN, 14);
            what.setText("Allow " + tool + "?");
            card.addView(what);
            TextView detail = text(FG, 13);
            detail.setTypeface(Typeface.MONOSPACE);
            detail.setText(summary(tool, in));
            card.addView(detail);
            final LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.END);
            final Button deny = button("Deny", PANEL, FG), allow = button("Allow", USER, BG);
            row.addView(deny);
            row.addView(allow);
            card.addView(row);
            View.OnClickListener answer = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean ok = v == allow;
                    if (asker == null) return;
                    if (ok) asker.allow(rid, in); else asker.deny(rid, "The user denied this in Trough.");
                    what.setText((ok ? "Allowed " : "Denied ") + tool);
                    card.removeView(row);
                }
            };
            deny.setOnClickListener(answer);
            allow.setOnClickListener(answer);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            feed.addView(card, lp);
            follow();
        } });
    }

    @Override public void onResult(final boolean ok, final double c, final long ms, String t) {
        ui(new Runnable() { @Override public void run() {
            busy = false;
            cost += c;
            live = null;
            if (!ok) note("turn ended with an error");
            report("result", (ok ? "ok" : "error") + String.format(" $%.4f %d ms", c, ms));
            paint();
            reload.run();   // the agent may have edited Trough's source: rebuild, swap if changed
        } });
    }

    @Override public void onExit(final int code, final String err) {
        ui(new Runnable() { @Override public void run() {
            busy = false;
            session = null;
            String tail = err == null ? "" : err.trim();
            if (tail.length() > 600) tail = tail.substring(tail.length() - 600);
            note("claude exited (" + code + ")" + (tail.isEmpty() ? "" : "\n" + tail));
            report("exit " + code, tail);
            paint();
        } });
    }

    // --- helpers ---

    /** Session state (never conversation content) for the VM: Download/trough/chat-state.txt. */
    private static void report(String event, String detail) {
        try {
            File f = new File(new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "trough"), "chat-state.txt");
            String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                + " " + event + (detail == null || detail.isEmpty() ? "" : " | " + detail.replace('\n', ' ')) + "\n";
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f, true)) { o.write(line.getBytes("UTF-8")); }
        } catch (Exception ignored) {}
    }

    private static String summary(String tool, Map<String, Object> in) {
        if (in == null) return "";
        for (String k : new String[]{"command", "file_path", "path", "pattern", "url", "description"}) {
            Object v = in.get(k);
            if (v instanceof String) return clip((String) v);
        }
        return clip(Json.write(in));
    }

    private static String clip(String s) { return s.length() > 300 ? s.substring(0, 300) + "…" : s; }

    private void note(String s) {
        TextView v = text(DIM, 12);
        v.setText(s);
        v.setPadding(0, dp(6), 0, 0);
        add(v);
    }

    private void paint() {
        status.setText(agent + (model.isEmpty() ? "" : " " + model) + "  ·  " + (busy ? "working" : "idle")
            + (cost > 0 ? String.format("  ·  $%.3f", cost) : ""));
        send.setText(busy ? "Stop" : "Send");
    }

    private void add(View v) { feed.addView(v); follow(); }

    private void follow() {
        scroll.post(new Runnable() { @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
    }

    private void ui(Runnable r) { a.runOnUiThread(r); }

    private TextView text(int color, int sp) {
        TextView t = new TextView(a);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private Button button(String label, int bg, int fg) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackground(round(bg, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }
}
