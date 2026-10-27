import com.trough.hot.Json;
import com.trough.hot.Session;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Session against a real claude on any JVM. Tools are DENIED, never run.
 *  Usage: java SessionTest <claude> <cwd> */
public class SessionTest {
    static int fails = 0;
    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "ok    " : "FAIL  ") + name + (ok ? "" : "  -- " + detail));
        if (!ok) fails++;
    }

    static class Rec implements Session.Listener {
        final StringBuilder deltas = new StringBuilder(), text = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);
        String model, permTool, toolResult;
        boolean ok, toolError;
        Session s;
        public void onInit(String id, String m) { model = m; }
        public void onTextDelta(String t) { deltas.append(t); }
        public void onAssistantText(String t) { text.append(t); }
        public void onToolUse(String id, String name, Map<String, Object> in) {}
        public void onToolResult(String id, String t, boolean err) { toolResult = t; toolError = err; }
        public void onPermission(String rid, String tool, Map<String, Object> in) { permTool = tool; s.deny(rid, "denied by Trough's test"); }
        public void onResult(boolean ok, double cost, long ms, String t) { this.ok = ok; done.countDown(); }
        public void onExit(int code, String err) { done.countDown(); }
    }

    static Rec turn(String claude, File cwd, String prompt) throws Exception {
        Rec r = new Rec();
        Map<String, String> env = new HashMap<>();
        // --setting-sources project: ignore this machine's user settings, whose allow rules
        // pre-approved Bash on the first run (10-27), so no permission request was ever sent.
        r.s = new Session(new ArrayList<>(Arrays.asList(claude, "--model", "haiku", "--setting-sources", "project")), cwd, env, r);
        r.s.send(prompt);
        r.done.await(120, TimeUnit.SECONDS);
        r.s.close();
        return r;
    }

    public static void main(String[] a) throws Exception {
        Object j = Json.parse("{\"a\":[1,2.5,\"x\\n\\u00e9\",true,null],\"b\":{}}");
        check("json round trip", Json.write(j).equals("{\"a\":[1,2.5,\"x\\né\",true,null],\"b\":{}}"), Json.write(j));
        File cwd = new File(a[1]);
        Rec t = turn(a[0], cwd, "Reply with exactly: trough-native");
        check("text turn: result ok", t.ok, "no successful result");
        check("text turn: final text", t.text.toString().trim().equals("trough-native"), t.text.toString());
        check("text turn: live deltas == final text", t.deltas.toString().equals(t.text.toString()), t.deltas + " vs " + t.text);
        // A write always needs permission (read-only commands like `echo` are auto-allowed by
        // claude itself, so they never reach Trough -- seen 10-27). Denied, so nothing is written.
        File target = new File(cwd, "trough-permission-test.txt");
        target.delete();
        Rec p = turn(a[0], cwd, "Use the Write tool to create the file " + target.getPath() + " containing: hello");
        check("permission: request reached Trough", "Write".equals(p.permTool), "tool=" + p.permTool);
        check("permission: denial reported back as tool error", p.toolError, "toolResult=" + p.toolResult);
        check("permission: denied write did not happen", !target.exists(), target + " exists");
        System.out.println("== " + fails + " failed");
        System.exit(fails);
    }
}
