import com.trough.hot.Agent;
import com.trough.hot.TroughAgent;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** TroughAgent against tests/fake_provider.py, both wire formats. Usage:
 *  java TroughAgentTest <base-url> <work-dir> <request-log> */
public class TroughAgentTest {
    static int fails = 0;
    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "ok    " : "FAIL  ") + name + (ok ? "" : "  -- " + detail));
        if (!ok) fails++;
    }

    static class Rec implements Agent.Listener {
        final StringBuilder deltas = new StringBuilder(), text = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);
        String id, permTool, toolResult, error;
        boolean ok, toolError, allow;
        Agent s;
        public void onInit(String i, String m) { id = i; }
        public void onTextDelta(String t) { deltas.append(t); }
        public void onAssistantText(String t) { text.append(t); }
        public void onToolUse(String i, String name, Map<String, Object> in) {}
        public void onToolResult(String i, String t, boolean err) { toolResult = t; toolError = err; }
        public void onPermission(String rid, String tool, Map<String, Object> in) { permTool = tool; if (allow) s.allow(rid, in); else s.deny(rid, "test"); }
        public void onResult(boolean ok, double cost, long ms, String t) { this.ok = ok; error = t; done.countDown(); }
        public void onExit(int code, String err) { done.countDown(); }
    }

    static Object provider(String api, String base) throws Exception {
        Class<?> p = Class.forName("com.trough.hot.Provider");
        Method of = p.getDeclaredMethod("of", Map.class, String.class);
        of.setAccessible(true);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("api", api);
        cfg.put("base", "anthropic".equals(api) ? base : base + "/v1");
        cfg.put("model", "fake");
        return of.invoke(null, cfg, "sk-fake");
    }

    static Rec turn(String api, String base, File cwd, File store, String resume, boolean allow, String prompt) throws Exception {
        Rec r = new Rec();
        r.allow = allow;
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/usr/bin:/bin");
        env.put("SHELL", "/bin/bash");
        java.lang.reflect.Constructor<TroughAgent> c = TroughAgent.class.getConstructor(Class.forName("com.trough.hot.Provider"),
            String.class, File.class, Map.class, File.class, String.class, Agent.Listener.class);
        c.setAccessible(true);
        r.s = c.newInstance(provider(api, base), "fake " + api, cwd, env, store, resume, r);
        r.s.send(prompt);
        r.done.await(30, TimeUnit.SECONDS);
        r.s.close();
        return r;
    }

    public static void main(String[] a) throws Exception {
        String base = a[0];
        File work = new File(a[1]), log = new File(a[2]);
        for (String api : new String[]{"anthropic", "openai"}) {
            File cwd = new File(work, api), store = new File(work, api + "-store");
            cwd.mkdirs();
            Rec t = turn(api, base, cwd, store, null, false, "hello");
            check(api + ": text turn ok", t.ok, "error=" + t.error);
            check(api + ": final text", t.text.toString().equals("trough-native"), t.text.toString());
            check(api + ": deltas == final text", t.deltas.toString().equals(t.text.toString()), t.deltas.toString());
            Rec again = turn(api, base, cwd, store, t.id, false, "hello again");
            String last = Files.readAllLines(log.toPath()).get(Files.readAllLines(log.toPath()).size() - 1);
            check(api + ": resume sends the earlier conversation", t.id.equals(again.id) && last.contains("\"hello\"") && last.contains("hello again"), last.length() > 300 ? last.substring(0, 300) : last);
            File secret = new File(cwd, "note.txt");
            Files.write(secret.toPath(), "content-7f3a".getBytes("UTF-8"));
            Rec read = turn(api, base, cwd, store, null, true, "READ note.txt");
            check(api + ": tool call reaches Trough for approval", "read_file".equals(read.permTool), "tool=" + read.permTool);
            check(api + ": approved read runs, result goes back to the model", read.ok && read.text.toString().contains("content-7f3a"), "error=" + read.error + " text=" + read.text);
            File target = new File(cwd, "should-not-exist");
            Rec run = turn(api, base, cwd, store, null, false, "RUN touch " + target.getPath());
            check(api + ": denied bash did not run", "bash".equals(run.permTool) && !target.exists(), "tool=" + run.permTool + " exists=" + target.exists());
            check(api + ": denial reported to the model as an error", run.ok && run.toolError && run.text.toString().contains("denied"), "text=" + run.text);
        }
        System.out.println("== " + fails + " failed");
        System.exit(fails);
    }
}
