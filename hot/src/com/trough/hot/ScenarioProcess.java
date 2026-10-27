package com.trough.hot;

import java.io.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded subprocess evidence. Interrupt/timeout always destroys the direct scenario process.
 * Registry scenarios do not spawn grandchildren; this is not a general process-tree supervisor.
 */
final class ScenarioProcess {
    static Map<String,Object> run(File cwd, Map<String,String> env, long seconds, String... command) throws Exception {
        ProcessBuilder pb=new ProcessBuilder(command).directory(cwd).redirectErrorStream(true)
            .redirectInput(new File("/dev/null"));
        pb.environment().clear(); pb.environment().putAll(env);
        final Process process=pb.start(); final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        final boolean[] truncated={false};
        Thread reader=new Thread(new Runnable() { public void run() {
            try(InputStream in=process.getInputStream()) {
                byte[] b=new byte[4096];
                for(int n;(n=in.read(b))!=-1;) synchronized(bytes) {
                    int keep=Math.min(n,65536-bytes.size()); bytes.write(b,0,keep);
                    if(keep<n) truncated[0]=true;
                }
            } catch(IOException ignored) {}
        }},"trough-scenario-output");
        reader.setDaemon(true); reader.start();
        try {
            boolean done=process.waitFor(seconds,TimeUnit.SECONDS);
            if(!done) process.destroyForcibly();
            reader.join(1000);
            synchronized(bytes) {
                return Json.map("ok",done && process.exitValue()==0 && !truncated[0] && !reader.isAlive(),
                    "exit",done?process.exitValue():-1,"timed_out",!done,"truncated",truncated[0],
                    "reader_complete",!reader.isAlive(),"output",bytes.toString("UTF-8"));
            }
        } finally {
            if(process.isAlive()) process.destroyForcibly();
            try { process.getInputStream().close(); } catch(IOException ignored) {}
        }
    }
}
