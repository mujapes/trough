package com.trough.hot;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.security.*;
import java.util.*;

/** Android-free scenario protocol. The local and remote executor use the same handler.
 * One bounded request, explicit build binding, durable acceptance before execution, dedupe,
 * restart ambiguity and signed evidence. No arbitrary code/shell/UI strings in the protocol.
 */
final class ScenarioProtocol {
    static final int REQUEST_LIMIT=8192, RESULT_LIMIT=131072, JOURNAL_LIMIT=128;
    interface Clock { long millis(); }
    interface Handler {
        boolean supports(String scenario);
        Map<String,Object> execute(String scenario) throws Exception;
    }
    private final File journal;
    private final PublicKey requestKey;
    private final String bundle,runtime;
    private final Clock clock;
    private final Handler handler;
    ScenarioProtocol(File journal, PublicKey key, String bundle, String runtime, Clock clock, Handler handler) {
        this.journal=journal; requestKey=key; this.bundle=bundle; this.runtime=runtime;
        this.clock=clock; this.handler=handler;
    }
    synchronized byte[] process(byte[] request, byte[] signature) throws Exception {
        if(request.length>REQUEST_LIMIT || signature.length>512) throw new SecurityException("request bounds");
        Signature verify=Signature.getInstance("SHA256withECDSA"); verify.initVerify(requestKey); verify.update(request);
        if(!verify.verify(signature)) throw new SecurityException("request signature");
        Map<String,Object> q=Json.obj(Json.parse(new String(request,"UTF-8")));
        if(q==null) throw new IllegalArgumentException("request object");
        String id=Json.str(q,"id"), scenario=Json.str(q,"scenario");
        if(id==null || !id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("request id");
        String hash=sha(request);
        Map<String,Object> result=Json.map("version",1,"id",id,"scenario",scenario,
            "request_sha256",hash,"bundle",bundle,"runtime",runtime,"status","rejected");
        long now=clock.millis(),issued=number(q,"issued_ms"),expires=number(q,"expires_ms");
        result.put("expires_ms",expires);
        Set<String> fields=new HashSet<>(Arrays.asList("version","id","scenario","bundle","runtime","issued_ms","expires_ms"));
        if(!fields.equals(q.keySet()) || number(q,"version")!=1 || issued<0 || expires<issued ||
            expires-issued>300000 || issued>now+10000 || expires<=now) return reject(result,"schema or expiry");
        if(!handler.supports(scenario)) return reject(result,"unknown scenario");
        boolean inspect="app.observe".equals(scenario);
        if(!(bundle.equals(Json.str(q,"bundle")) || inspect && "*".equals(Json.str(q,"bundle"))) ||
           !(runtime.equals(Json.str(q,"runtime")) || inspect && "*".equals(Json.str(q,"runtime"))))
            return reject(result,"build mismatch");
        if(!journal.isDirectory() && !journal.mkdirs()) throw new IOException("journal unavailable");
        File record=new File(journal,id+".json");
        if(record.isFile()) {
            Map<String,Object> previous=Json.obj(Json.parse(new String(read(record,RESULT_LIMIT),"UTF-8")));
            if(!hash.equals(Json.str(previous,"request_sha256"))) return reject(result,"request id reused");
            if("accepted".equals(Json.str(previous,"status"))) {
                previous.put("status","interrupted"); previous.put("error","prior execution has no terminal receipt; not replayed");
                writeJournal(record,encode(previous));
            }
            return encode(previous);
        }
        File[] files=journal.listFiles(); int retained=0;
        if(files!=null) for(File f:files) {
            if(!f.getName().matches("[a-f0-9]{32}\\.json")) continue;
            Map<String,Object> old=Json.obj(Json.parse(new String(read(f,RESULT_LIMIT),"UTF-8")));
            if(number(old,"expires_ms")<=now && f.delete()) continue;
            retained++;
        }
        if(retained>=JOURNAL_LIMIT) return reject(result,"journal capacity");
        result.put("status","accepted"); writeJournal(record,encode(result));
        long started=System.nanoTime();
        try {
            Map<String,Object> outcome=handler.execute(scenario);
            result.put("outcome",outcome);
            result.put("status",Boolean.TRUE.equals(outcome.get("ok"))?"done":"failed");
        } catch(Exception e) {
            result.put("status",e instanceof InterruptedException?"interrupted":"failed");
            result.put("error",e.getClass().getSimpleName());
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        result.put("elapsed_ns",System.nanoTime()-started);
        byte[] bytes=encode(result);
        if(bytes.length>RESULT_LIMIT) {
            result.remove("outcome"); result.put("status","failed"); result.put("error","result capacity"); bytes=encode(result);
        }
        writeJournal(record,bytes); return bytes;
    }
    private static long number(Map<String,Object> m,String key) {
        Object v=m==null?null:m.get(key); return v instanceof Long || v instanceof Integer?((Number)v).longValue():-1;
    }
    private static byte[] reject(Map<String,Object> r,String why) throws Exception { r.put("error",why); return encode(r); }
    static byte[] encode(Map<String,Object> m) throws Exception { return Json.write(m).getBytes("UTF-8"); }
    static String sha(byte[] bytes) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder s=new StringBuilder();
        for(byte b:digest) s.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return s.toString();
    }
    static byte[] read(File f,int limit) throws Exception {
        if(f.length()>limit) throw new IOException("file capacity");
        try(InputStream in=new FileInputStream(f); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[4096]; for(int n;(n=in.read(b))!=-1;) {
                if(out.size()+n>limit) throw new IOException("file grew beyond capacity"); out.write(b,0,n);
            }
            return out.toByteArray();
        }
    }
    static void write(File f,byte[] data) throws Exception {
        File parent=f.getParentFile(); if(!parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir");
        File tmp=new File(f.getPath()+".new");
        try(FileOutputStream out=new FileOutputStream(tmp)) { out.write(data); out.getFD().sync(); }
        if(!tmp.renameTo(f)) throw new IOException("atomic rename");
        // Private journal requires durable directory entry before effects; shared transport
        // may use a filesystem that cannot fsync directories, so this is separate below.
    }
    private void writeJournal(File f,byte[] data) throws Exception {
        write(f,data);
        try(FileChannel directory=FileChannel.open(f.getParentFile().toPath(),StandardOpenOption.READ)) {
            directory.force(true);
        }
    }
}
