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

    static JSONObject namespaces(Context ctx, File bundle) throws Exception {
        JSONObject r = exec(ctx.getCacheDir(), ctx, new File(bundle, "bin/nsprobe").getPath());
        android.system.StructUtsname uname = android.system.Os.uname();
        r.put("uname", new JSONObject().put("release", uname.release).put("version", uname.version)
            .put("machine", uname.machine));
        JSONObject files = new JSONObject();
        for (String path : new String[]{"/proc/sys/user/max_user_namespaces",
                "/proc/sys/user/max_mnt_namespaces", "/proc/sys/kernel/unprivileged_userns_clone",
                "/proc/self/status", "/sys/fs/selinux/enforce", "/proc/version"}) {
            try (InputStream in = new java.io.FileInputStream(path)) {
                byte[] b = new byte[16384];
                int n = in.read(b);
                files.put(path, n < 0 ? "" : new String(b, 0, n, "UTF-8"));
            } catch (Exception e) { files.put(path, e.toString()); }
        }
        JSONObject links = new JSONObject();
        for (String name : new String[]{"user", "mnt", "pid", "net"}) {
            try { links.put(name, android.system.Os.readlink("/proc/self/ns/" + name)); }
            catch (Exception e) { links.put(name, e.toString()); }
        }
        r.put("namespace_links", links);
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.util.zip.GZIPInputStream(new java.io.FileInputStream("/proc/config.gz")), "UTF-8"))) {
            StringBuilder config = new StringBuilder();
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.matches(".*CONFIG_(USER_NS|NAMESPACES|PID_NS|NET_NS|UTS_NS|IPC_NS|SECCOMP|SECCOMP_FILTER|SECURITY_SELINUX)(=.*| is not set)"))
                    config.append(line).append('\n');
            }
            files.put("/proc/config.gz", config.toString());
        } catch (Exception e) { files.put("/proc/config.gz", e.toString()); }
        r.put("kernel", files);
        return r;
    }

    /**
     * The reload path end to end, without touching the live files/src: copy it, add a class whose
     * answer is a fresh nonce, build with Self.build (cold, so this is the real compile time on
     * the phone), load the result beside the bundle and call it.
     */
    static JSONObject selfEdit(Context ctx, File bundle, JSONObject payloads) throws Exception {
        File w = new File(ctx.getCacheDir(), "selfedit"), src = new File(w, "src"), out = new File(w, "out");
        Main.deleteTree(w);
        copy(Self.src(ctx), src);
        String nonce = Long.toHexString(System.nanoTime());
        Payloads.writeSmall(new File(src, "com/trough/hot/EditProbe.java"),
            "package com.trough.hot;\npublic final class EditProbe { public static String id() { return \"" + nonce + "\"; } }\n");
        JSONObject r = new JSONObject();
        Self.build(ctx, src, bundle, payloads, out, r);
        File[] built = out.listFiles();
        Object got = Self.load(ctx, built[0], bundle, "com.trough.hot.EditProbe").getMethod("id").invoke(null);
        r.put("want", nonce).put("got", got).put("ok", nonce.equals(got));
        Main.deleteTree(w);
        return r;
    }

    private static void copy(File from, File to) throws Exception {
        if (from.isDirectory()) {
            to.mkdirs();
            File[] kids = from.listFiles();
            if (kids != null) for (File k : kids) copy(k, new File(to, k.getName()));
        } else Payloads.writeSmall(to, new String(Payloads.readSmall(from), "UTF-8"));
    }

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
