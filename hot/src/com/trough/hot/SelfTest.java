package com.trough.hot;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Map;

/**
 * On the phone: Trough's own toolchain builds and runs C and Java (DESIGN.md "Self-hosting").
 * Same checks as tests/toolchain.sh in the VM, against the real seccomp filter.
 */
final class SelfTest {
    private SelfTest() {}

    static JSONObject run(Context ctx) throws Exception {
        JSONObject r = new JSONObject();
        File d = new File(ctx.getDataDir(), "d"), bin = new File(d, "usr/bin");
        File w = new File(ctx.getCacheDir(), "selftest");
        Main.deleteTree(w);
        w.mkdirs();
        Payloads.writeSmall(new File(w, "t.c"),
            "#include <pthread.h>\n#include <stdio.h>\n#include <signal.h>\n"
            + "static void *f(void *a){sigset_t s;sigfillset(&s);pthread_sigmask(SIG_BLOCK,&s,0);return a;}\n"
            + "int main(void){pthread_t t[4];for(int i=0;i<4;i++)pthread_create(&t[i],0,f,0);"
            + "for(int i=0;i<4;i++)pthread_join(t[i],0);puts(\"c ok\");return 0;}\n");
        Payloads.writeSmall(new File(w, "Hello.java"),
            "class Hello{public static void main(String[] a){System.out.println(\"java ok\");}}\n");
        r.put("gcc", exec(w, ctx, new File(bin, "gcc").getPath(), "-O2", "t.c", "-o", "t", "-lpthread"));
        r.put("c_run", exec(w, ctx, new File(w, "t").getPath()));
        r.put("javac", exec(w, ctx, new File(bin, "javac").getPath(), "Hello.java"));
        r.put("java_run", exec(w, ctx, new File(bin, "java").getPath(), "Hello"));
        return r;
    }

    private static JSONObject exec(File cwd, Context ctx, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true)
            .redirectInput(new File("/dev/null"));
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("HOME", new File(ctx.getFilesDir(), "home").getPath());
        env.put("TMPDIR", cwd.getPath());
        env.put("PATH", new File(ctx.getDataDir(), "d/usr/bin").getPath() + ":/system/bin");
        long t0 = System.nanoTime();
        Process p = pb.start();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
        }
        int exit = p.waitFor();
        String out = buf.toString("UTF-8");
        if (out.length() > 1500) out = out.substring(out.length() - 1500);
        return new JSONObject().put("exit", exit).put("ms", (System.nanoTime() - t0) / 1_000_000).put("out", out);
    }
}
