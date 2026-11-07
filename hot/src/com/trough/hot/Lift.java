package com.trough.hot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lift's rewrite pass over decoded smali (docs/LIFT.md): framework calls a guest makes are redirected
 * to Trough shims with the same signature, and the shims are added to the guest. This makes a lifted
 * app see Trough's view of the device (its own storage, ...) without runtime hooks.
 */
final class Lift {
    private Lift() {}

    /** Framework static method -> shim class, same name and descriptor. */
    static final Map<String, String> REDIRECTS = new LinkedHashMap<>();
    static {
        REDIRECTS.put("Landroid/os/Environment;->getExternalStoragePublicDirectory(Ljava/lang/String;)Ljava/io/File;",
            "Lcom/trough/guest/Shim;");
    }

    static final String SHIM =
        ".class public final Lcom/trough/guest/Shim;\n.super Ljava/lang/Object;\n\n"
        + "# Trough guest shim (Lift.java): set root to give the guest its own shared storage.\n"
        + ".field public static root:Ljava/lang/String;\n\n"
        + ".method public static getExternalStoragePublicDirectory(Ljava/lang/String;)Ljava/io/File;\n"
        + "    .registers 3\n"
        + "    sget-object v0, Lcom/trough/guest/Shim;->root:Ljava/lang/String;\n"
        + "    if-nez v0, :guest\n"
        + "    invoke-static {p0}, Landroid/os/Environment;->getExternalStoragePublicDirectory(Ljava/lang/String;)Ljava/io/File;\n"
        + "    move-result-object v0\n"
        + "    return-object v0\n"
        + "    :guest\n"
        + "    new-instance v1, Ljava/io/File;\n"
        + "    invoke-direct {v1, v0, p0}, Ljava/io/File;-><init>(Ljava/lang/String;Ljava/lang/String;)V\n"
        + "    return-object v1\n"
        + ".end method\n";

    /** baksmali -> rewrite -> smali on a bare dex, with the root's smali 2.5.2 jars. Returns rewritten sites, or -1. */
    static int liftDex(android.content.Context ctx, File in, File out, File work, java.util.Map<String, Object> log) throws Exception {
        File d = new File(Root.os(), "d");
        StringBuilder cp = new StringBuilder();
        File[] jars = new File(d, "usr/share/java").listFiles();
        if (jars != null) for (File j : jars) if (j.getName().endsWith(".jar")) cp.append(cp.length() > 0 ? ":" : "").append(j.getPath());
        File smali = new File(work, "smali");
        org.json.JSONObject a = SelfTest.exec(work, ctx, null, 600, d + "/usr/bin/java", "-cp", cp.toString(),
            "org.jf.baksmali.Main", "d", in.getPath(), "-o", smali.getPath());
        log.put("baksmali_exit", a.getInt("exit"));
        if (a.getInt("exit") != 0) return -1;
        int n = rewrite(smali);
        org.json.JSONObject b = SelfTest.exec(work, ctx, null, 600, d + "/usr/bin/java", "-cp", cp.toString(),
            "org.jf.smali.Main", "a", smali.getPath(), "-o", out.getPath());
        log.put("smali_exit", b.getInt("exit"));
        return b.getInt("exit") == 0 ? n : -1;
    }

    /** Trough's own bundle-signing key (app-private), made on first use: guests trust its public half. */
    static java.security.KeyPair key(android.content.Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), "lift");
        File priv = new File(dir, "key.pkcs8"), pub = new File(dir, "key.der");
        java.security.KeyFactory f = java.security.KeyFactory.getInstance("EC");
        if (priv.exists() && pub.exists())
            return new java.security.KeyPair(f.generatePublic(new java.security.spec.X509EncodedKeySpec(bytes(pub))),
                f.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(bytes(priv))));
        dir.mkdirs();
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.KeyPair k = g.generateKeyPair();
        write(priv, k.getPrivate().getEncoded());
        write(pub, k.getPublic().getEncoded());
        return k;
    }

    /** The bundle with its classes.dex replaced by LIFTED, signed with Trough's key into outZip/outSig. */
    static void repack(File bundle, File lifted, File outZip, File outSig, java.security.KeyPair k) throws Exception {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(bundle);
             java.util.zip.ZipOutputStream o = new java.util.zip.ZipOutputStream(new FileOutputStream(outZip))) {
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                java.util.zip.ZipEntry en = e.nextElement();
                o.putNextEntry(new java.util.zip.ZipEntry(en.getName()));
                if (en.getName().equals("classes.dex")) o.write(bytes(lifted));
                else try (java.io.InputStream in = z.getInputStream(en)) { byte[] b = new byte[1 << 16]; for (int r; (r = in.read(b)) > 0; ) o.write(b, 0, r); }
                o.closeEntry();
            }
        }
        java.security.Signature s = java.security.Signature.getInstance("SHA256withECDSA");
        s.initSign(k.getPrivate());
        s.update(bytes(outZip));
        write(outSig, s.sign());
    }

    static byte[] bytes(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            for (int off = 0, r; off < b.length && (r = in.read(b, off, b.length - off)) > 0; ) off += r;
            return b;
        }
    }

    private static void write(File f, byte[] b) throws Exception { try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); } }

    /** Rewrites every .smali under smaliDir and adds the shim. Returns the number of call sites changed. */
    static int rewrite(File smaliDir) throws Exception {
        int n = rewriteTree(smaliDir);
        File shim = new File(smaliDir, "com/trough/guest/Shim.smali");
        shim.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(shim)) { o.write(SHIM.getBytes("UTF-8")); }
        return n;
    }

    private static int rewriteTree(File f) throws Exception {
        if (f.isDirectory()) {
            int n = 0;
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) n += rewriteTree(c);
            return n;
        }
        if (!f.getName().endsWith(".smali")) return 0;
        if (f.getPath().endsWith("/com/trough/guest/Shim.smali")) return 0;
        byte[] b;
        try (FileInputStream in = new FileInputStream(f)) { b = new byte[(int) f.length()]; int off = 0; for (int r; off < b.length && (r = in.read(b, off, b.length - off)) > 0; ) off += r; }
        // Rewrite instructions only. Lift itself contains method descriptors and the shim's
        // source as string constants: changing those would make a lifted Lift rewrite its own
        // shim into a recursive call on the next self build.
        String s = new String(b, "UTF-8");
        StringBuilder rewritten = new StringBuilder();
        int n = 0;
        for (String line : s.split("\n", -1)) {
            String instruction = line.trim();
            if (instruction.startsWith("invoke-static ") || instruction.startsWith("invoke-static/range ")) {
                for (Map.Entry<String, String> e : REDIRECTS.entrySet()) {
                    String from = e.getKey(), to = e.getValue() + from.substring(from.indexOf("->"));
                    if (line.contains(from)) { line = line.replace(from, to); n++; }
                }
            }
            if (rewritten.length() > 0) rewritten.append('\n');
            rewritten.append(line);
        }
        String t = rewritten.toString();
        if (n > 0) try (FileOutputStream o = new FileOutputStream(f)) { o.write(t.getBytes("UTF-8")); }
        return n;
    }
}
