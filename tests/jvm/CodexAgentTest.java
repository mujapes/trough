import com.trough.hot.Agent;
import com.trough.hot.CodexAgent;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** CodexAgent against a real codex app-server on any JVM. Every approval is DECLINED and the
 *  sandbox is read-only, so nothing the model asks for runs.
 *  Usage: java CodexAgentTest <codex> <cwd> <codex-home> */
public class CodexAgentTest {
    static int fails = 0;
    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "ok    " : "FAIL  ") + name + (ok ? "" : "  -- " + detail));
        if (!ok) fails++;
    }

    static class Rec implements Agent.Listener {
        final StringBuilder deltas = new StringBuilder(), text = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1), ready = new CountDownLatch(1);
        String model, thread, permTool, toolName, toolResult;
        boolean ok, toolError;
        Agent s;
        public void onInit(String id, String m) { thread = id; model = m; ready.countDown(); }
        public void onTextDelta(String t) { deltas.append(t); }
        public void onAssistantText(String t) { text.setLength(0); text.append(t); }
        public void onToolUse(String id, String name, Map<String, Object> in) { toolName = name; }
        public void onToolResult(String id, String t, boolean err) { toolResult = t; toolError = err; }
        public void onPermission(String rid, String tool, Map<String, Object> in) { permTool = tool; s.deny(rid, "declined by Trough's test"); }
        public void onResult(boolean ok, double cost, long ms, String t) { this.ok = ok; done.countDown(); }
        public void onExit(int code, String err) { System.out.println("exit " + code + " " + err); ready.countDown(); done.countDown(); }
    }

    static Rec turn(String codex, File cwd, String home, String resume, String prompt) throws Exception {
        Rec r = new Rec();
        Map<String, String> env = new HashMap<>();
        env.put("CODEX_HOME", home);
        env.put("HOME", new File(home).getParent());
        env.put("PATH", "/usr/bin:/bin");
        r.s = new CodexAgent(codex, cwd, env, resume, null, "read-only", r);
        r.s.send(prompt);   // queued until the thread exists: exercises the queue
        r.done.await(180, TimeUnit.SECONDS);
        r.s.close();
        return r;
    }

    public static void main(String[] a) throws Exception {
        File cwd = new File(a[1]);
        Rec t = turn(a[0], cwd, a[2], null, "Reply with exactly: trough-native");
        check("text turn: thread started", t.thread != null && t.model != null, "thread=" + t.thread + " model=" + t.model);
        check("text turn: result ok", t.ok, "no successful result");
        check("text turn: final text", t.text.toString().trim().equals("trough-native"), t.text.toString());
        check("text turn: live deltas == final text", t.deltas.toString().equals(t.text.toString()), t.deltas + " vs " + t.text);
        Rec again = turn(a[0], cwd, a[2], t.thread, "What exact text did you reply with last time? Reply with only that text.");
        check("resume: same thread", t.thread != null && t.thread.equals(again.thread), again.thread + " vs " + t.thread);
        check("resume: remembers", again.text.toString().contains("trough-native"), again.text.toString());
        File target = new File(cwd, "trough-permission-test.txt");
        target.delete();
        Rec p = turn(a[0], cwd, a[2], null, "Run this exact shell command: touch " + target.getPath());
        check("permission: request reached Trough", "Bash".equals(p.permTool), "tool=" + p.permTool);
        check("permission: declined command did not run", !target.exists(), target + " exists");
        System.out.println("== " + fails + " failed");
        System.exit(fails);
    }
}
