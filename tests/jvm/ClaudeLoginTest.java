import com.trough.hot.Agent;
import com.trough.hot.ClaudeAgent;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** ClaudeAgent.Login plumbing against a real claude, in a throwaway HOME: the sign-in URL
 *  reaches Trough, and a bogus code is reported as a failure (never as success). No account is
 *  touched: the bogus code cannot complete a sign-in. Usage: java ClaudeLoginTest <claude> <home> */
public class ClaudeLoginTest {
    public static void main(String[] a) throws Exception {
        final String[] url = new String[1];
        final boolean[] ok = new boolean[1];
        final CountDownLatch got = new CountDownLatch(1), done = new CountDownLatch(1);
        Agent.Listener l = new Agent.Listener() {
            public void onInit(String i, String m) {} public void onTextDelta(String t) {} public void onAssistantText(String t) {}
            public void onToolUse(String i, String n, Map<String, Object> in) {} public void onToolResult(String i, String t, boolean e) {}
            public void onPermission(String r, String t, Map<String, Object> in) {} public void onResult(boolean k, double c, long ms, String t) {}
            public void onExit(int c, String e) {}
            public void onAuth(String u, String code) { url[0] = u; got.countDown(); }
        };
        Map<String, String> env = new HashMap<>();
        env.put("HOME", a[1]);
        env.put("PATH", "/usr/bin:/bin");
        ClaudeAgent.Login login = new ClaudeAgent.Login(a[0], new File(a[1]), env, l, new ClaudeAgent.Login.Done() {
            public void onDone(boolean k, String detail) { ok[0] = k; System.out.println("done: " + detail.replace('\n', ' ')); done.countDown(); }
        });
        int fails = 0;
        boolean u = got.await(30, TimeUnit.SECONDS) && url[0] != null && url[0].startsWith("https://") && url[0].contains("oauth/authorize");
        System.out.println((u ? "ok    " : "FAIL  ") + "sign-in URL reaches Trough");
        if (!u) fails++;
        login.code("not-a-real-code");
        boolean d = done.await(60, TimeUnit.SECONDS);
        System.out.println((d && !ok[0] ? "ok    " : "FAIL  ") + "bogus code reported as failure");
        if (!(d && !ok[0])) fails++;
        boolean noCreds = !new File(a[1], ".claude/.credentials.json").exists();
        System.out.println((noCreds ? "ok    " : "FAIL  ") + "no credentials written");
        if (!noCreds) fails++;
        System.out.println("== " + fails + " failed");
        System.exit(fails);
    }
}
