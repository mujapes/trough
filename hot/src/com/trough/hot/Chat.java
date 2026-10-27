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
 * Trough's own UI over Session: native views, no HTML, no WebView. Forge's dock is the spec:
 * a feed of the conversation (live text, compact tool lines, inline permission cards), a status
 * strip, and a prompt dock whose button is Send when idle and Stop while claude works.
 */
final class Chat implements Session.Listener {
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
    private Session session;
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

    void start(String claude, File cwd, Map<String, String> env) {
        List<String> cmd = new ArrayList<>(Arrays.asList(claude, "--model", "opus"));
        File saved = new File(home, ".trough-session");
        try {
            if (saved.exists()) {
                String id = new String(Payloads.readSmall(saved), "UTF-8").trim();
                if (!id.isEmpty()) { cmd.add("--resume"); cmd.add(id); }
            }
        } catch (Exception ignored) {}
        try {
            session = new Session(cmd, cwd, env, this);
            note("session starting" + (cmd.contains("--resume") ? " (resuming)" : ""));
        } catch (Exception e) {
            note("could not start claude: " + e);
        }
    }

    void close() { if (session != null) session.close(); }

    private void submit() {
        String text = input.getText().toString().trim();
        if (text.equals("/reload")) {
            input.setText("");
            note("rebuilding Trough from files/src");
            reload.run();
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

    // --- Session.Listener: called on the reader thread, so everything hops to the UI thread ---

    @Override public void onInit(final String id, final String m) {
        ui(new Runnable() { @Override public void run() {
            sessionId = id;
            model = m == null ? "" : m;
            try { Payloads.writeSmall(new File(home, ".trough-session"), id); } catch (Exception ignored) {}
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

    @Override public void onPermission(final String rid, final String tool, final Map<String, Object> in) {
        ui(new Runnable() { @Override public void run() {
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
                    if (ok) session.allow(rid, in); else session.deny(rid, "The user denied this in Trough.");
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
        status.setText((model.isEmpty() ? "claude" : model) + "  ·  " + (busy ? "working" : "idle")
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
