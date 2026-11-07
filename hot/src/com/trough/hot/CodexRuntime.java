package com.trough.hot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

/** Installs one signed Codex release as an immutable, complete vendor tree. No credentials live here. */
final class CodexRuntime {
    private static final Object LOCK = "com.trough.hot.codex-runtime";

    static File install(File payload, Map<String,Object> manifest) throws Exception {
        Map<String,Object> runtime = Json.obj(manifest.get("codex_runtime"));
        if(runtime==null) {
            Map<String,Object> wanted=Json.obj(manifest.get("payloads"));
            String sha=wanted==null?null:Json.str(wanted,"codex");
            return sha==null?null:new File(payload,sha);
        }
        if(!"aarch64-unknown-linux-musl".equals(Json.str(runtime,"target"))) throw new IOException("Codex runtime target");
        Map<String,Object> files=Json.obj(runtime.get("files"));
        Map<String,Object> wanted=Json.obj(manifest.get("payloads"));
        if(files==null || files.size()<2 || files.size()>128 || wanted==null) throw new IOException("Codex runtime inventory");
        if(!files.containsKey("bin/codex") || !files.containsKey("bin/codex-code-mode-host")
            || !files.get("bin/codex").equals(wanted.get("codex"))) throw new IOException("Codex runtime missing matching main/helper");
        ArrayList<String> names=new ArrayList<String>(files.keySet()); Collections.sort(names);
        StringBuilder identity=new StringBuilder(Json.str(runtime,"version")).append('\n');
        for(String name:names) {
            String sha=Json.str(files,name);
            if(!validPath(name) || sha==null || !sha.matches("[a-f0-9]{64}") || !wanted.containsValue(sha))
                throw new IOException("Codex runtime member: "+name);
            identity.append(name).append(' ').append(sha).append('\n');
        }
        String key=hex(MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes("UTF-8")));
        File parent=new File(payload,"codex-runtimes"), dest=new File(parent,key);
        synchronized(LOCK) {
            if(dest.isDirectory()) {
                try { verify(dest,files,names); return new File(dest,"bin/codex"); }
                catch(IOException changed) { /* Reconstruct below from signed payloads. */ }
                catch(SecurityException changed) { /* Reconstruct below from signed payloads. */ }
            }
            if(!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Codex runtime directory");
            File stage=new File(parent,"."+key+"-"+java.util.UUID.randomUUID()+".prepare");
            if(!stage.mkdir()) throw new IOException("Codex runtime preparation");
            try {
                for(String name:names) {
                    File from=new File(payload,Json.str(files,name)), to=new File(stage,name);
                    if(!to.getParentFile().isDirectory() && !to.getParentFile().mkdirs()) throw new IOException("Codex member parent");
                    MessageDigest hash=MessageDigest.getInstance("SHA-256");
                    try(InputStream in=new FileInputStream(from); FileOutputStream out=new FileOutputStream(to)) {
                        byte[] block=new byte[1<<20];
                        for(int n;(n=in.read(block))!=-1;) { hash.update(block,0,n); out.write(block,0,n); }
                        out.getFD().sync();
                    }
                    if(!Json.str(files,name).equals(hex(hash.digest()))) throw new SecurityException("Codex member digest: "+name);
                    if(!to.setReadable(true,true) || !to.setWritable(false,false)) throw new IOException("Codex member permissions");
                    if(executable(name) && !to.setExecutable(true,true)) throw new IOException("Codex executable permissions");
                }
                File retired=null;
                if(dest.exists()) {
                    retired=new File(parent,"."+key+"-"+java.util.UUID.randomUUID()+".retired");
                    if(!dest.renameTo(retired)) throw new IOException("Codex runtime retirement");
                }
                if(!stage.renameTo(dest)) {
                    if(retired!=null) retired.renameTo(dest);
                    throw new IOException("Codex runtime activation");
                }
            } finally { if(stage.exists()) remove(stage); }
            return new File(dest,"bin/codex");
        }
    }

    private static boolean validPath(String name) {
        return name.matches("(?:bin/|codex-path/|codex-resources/)[A-Za-z0-9_./+-]+|codex-package\\.json")
            && !name.contains("..") && !name.endsWith("/");
    }
    private static boolean executable(String name) {
        return name.startsWith("bin/") || name.startsWith("codex-path/") || name.contains("/bin/") || name.equals("codex-resources/bwrap");
    }
    private static void verify(File root, Map<String,Object> files, ArrayList<String> names) throws Exception {
        for(String name:names) {
            File file=new File(root,name);
            if(!file.getCanonicalPath().equals(root.getCanonicalPath()+"/"+name)
                || java.nio.file.Files.isSymbolicLink(file.toPath()) || !file.isFile())
                throw new IOException("Codex runtime missing/executable: "+name);
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(file)) { byte[] block=new byte[1<<20]; for(int n;(n=in.read(block))!=-1;) hash.update(block,0,n); }
            if(!Json.str(files,name).equals(hex(hash.digest()))) throw new SecurityException("Codex runtime changed: "+name);
            if(executable(name) && !file.setExecutable(true,true)) throw new IOException("Codex runtime permissions: "+name);
        }
    }
    private static void remove(File file) {
        if(!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.isDirectory()) { File[] children=file.listFiles(); if(children!=null) for(File child:children) remove(child); }
        file.setWritable(true,true); file.delete();
    }
    private static String hex(byte[] bytes) { StringBuilder s=new StringBuilder(); for(byte b:bytes) s.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return s.toString(); }
}
