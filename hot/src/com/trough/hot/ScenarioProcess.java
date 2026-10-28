package com.trough.hot;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded output plus a dedicated native owner which reaps the complete subprocess tree. */
final class ScenarioProcess {
    private static boolean yes(Map<String,Object> m,String key) { return Boolean.TRUE.equals(m.get(key)); }
    private static long number(Map<String,Object> m,String key) throws IOException {
        Object v=m.get(key); if(!(v instanceof Long)) throw new IOException("supervisor report number");
        return (Long)v;
    }
    private static void stop(Process process) {
        if(!process.isAlive()) return;
        boolean interrupted=false;
        process.destroy(); // TERM lets the native owner clean descendants before it exits.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(process.isAlive() && System.nanoTime()<deadline) {
            try { process.waitFor(50,TimeUnit.MILLISECONDS); }
            catch(InterruptedException e) { interrupted=true; }
        }
        if(process.isAlive()) process.destroyForcibly();
        if(interrupted) Thread.currentThread().interrupt();
    }
    static Map<String,Object> run(File supervisor,File cwd,Map<String,String> env,long seconds,String... command) throws Exception {
        if(seconds<1 || seconds>600 || command.length==0 || !new File(command[0]).isAbsolute())
            throw new IllegalArgumentException("scenario command/deadline");
        File scratch=new File(cwd,"supervisor-"+UUID.randomUUID().toString());
        if(!scratch.mkdir()) throw new IOException("supervisor directory");
        File report=new File(scratch,"result.json");
        List<String> args=new ArrayList<>();
        args.add(supervisor.getAbsolutePath()); args.add(Long.toString(seconds*1000));
        args.add(report.getAbsolutePath()); args.add("--"); Collections.addAll(args,command);
        Process process=null;
        try {
            ProcessBuilder pb=new ProcessBuilder(args).directory(cwd).redirectErrorStream(true)
                .redirectInput(new File("/dev/null"));
            pb.environment().clear(); pb.environment().putAll(env);
            final Process child=pb.start(); process=child;
            final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            final boolean[] truncated={false},readerError={false};
            Thread reader=new Thread(new Runnable() { public void run() {
                try(InputStream in=child.getInputStream()) {
                    byte[] b=new byte[4096];
                    for(int n;(n=in.read(b))!=-1;) synchronized(bytes) {
                        int keep=Math.min(n,65536-bytes.size()); bytes.write(b,0,keep);
                        if(keep<n) truncated[0]=true;
                    }
                } catch(IOException e) { synchronized(bytes) { readerError[0]=true; } }
            }},"trough-scenario-output");
            reader.setDaemon(true); reader.start();
            boolean done=child.waitFor(seconds+4,TimeUnit.SECONDS);
            if(!done) stop(child);
            reader.join(1000);
            Map<String,Object> nativeResult=null;
            if(done) {
                try {
                    nativeResult=Json.obj(Json.parse(new String(ScenarioProtocol.read(report,1024),"UTF-8")));
                    if(nativeResult==null || number(nativeResult,"version")!=1) throw new IOException("supervisor version");
                    long error=number(nativeResult,"error"),exit=number(nativeResult,"exit"),signal=number(nativeResult,"signal");
                    long cancel=number(nativeResult,"cancelled_signal");
                    number(nativeResult,"exec_errno"); number(nativeResult,"pid"); number(nativeResult,"reaped");
                    number(nativeResult,"elapsed_ms");
                    if(!(nativeResult.get("timed_out") instanceof Boolean) || !(nativeResult.get("cleanup_complete") instanceof Boolean))
                        throw new IOException("supervisor flags");
                    long expected=error!=0 || !yes(nativeResult,"cleanup_complete")?125:
                        yes(nativeResult,"timed_out")?124:cancel!=0?128+cancel:signal!=0?128+signal:exit;
                    if(expected!=child.exitValue()) throw new IOException("supervisor exit mismatch");
                } catch(Exception e) { nativeResult=null; }
            }
            synchronized(bytes) {
                boolean clean=nativeResult!=null && yes(nativeResult,"cleanup_complete") && number(nativeResult,"error")==0;
                boolean ok=done && clean && child.exitValue()==0 && number(nativeResult,"exec_errno")==0 &&
                    !truncated[0] && !reader.isAlive() && !readerError[0];
                return Json.map("ok",ok,"exit",done?child.exitValue():-1,
                    "timed_out",!done || (nativeResult!=null && yes(nativeResult,"timed_out")),
                    "cleanup_complete",clean,"supervision",nativeResult,"truncated",truncated[0],
                    "reader_complete",!reader.isAlive() && !readerError[0],"output",bytes.toString("UTF-8"));
            }
        } finally {
            if(process!=null) {
                stop(process);
                try { process.getInputStream().close(); } catch(IOException ignored) {}
            }
            report.delete(); scratch.delete();
        }
    }
}
