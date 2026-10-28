package com.trough.hot;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;

public final class ScenarioProtocolTest {
    static int checks,runs; static long now=1000000;
    static KeyPair key; static File dir; static int next;
    static void check(boolean value,String label) { if(!value) throw new AssertionError(label); checks++; System.out.println("ok "+label); }
    static ScenarioProtocol protocol() {
        return new ScenarioProtocol(dir,key.getPublic(),"bundle-a","runtime-a",() -> now,new ScenarioProtocol.Handler() {
            public boolean supports(String s) { return "app.observe".equals(s) || "core.test".equals(s) || "core.fail".equals(s); }
            public Map<String,Object> execute(String s) throws Exception {
                runs++; if("core.fail".equals(s)) throw new IOException("controlled"); return Json.map("ok",true,"value",runs);
            }
        });
    }
    static Map<String,Object> request(String scenario) {
        return Json.map("version",1,"id",String.format("%032x",++next),"scenario",scenario,
            "bundle","bundle-a","runtime","runtime-a","issued_ms",now,"expires_ms",now+60000);
    }
    static byte[] sign(byte[] body) throws Exception {
        Signature s=Signature.getInstance("SHA256withECDSA"); s.initSign(key.getPrivate()); s.update(body); return s.sign();
    }
    static Map<String,Object> submit(ScenarioProtocol p,Map<String,Object> q) throws Exception {
        byte[] body=ScenarioProtocol.encode(q); return Json.obj(Json.parse(new String(p.process(body,sign(body)),"UTF-8")));
    }
    public static void main(String[] args) throws Exception {
        dir=new File(args[0],"journal"); KeyPairGenerator gen=KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1")); key=gen.generateKeyPair();
        ScenarioProtocol p=protocol(); Map<String,Object> q=request("core.test");
        Map<String,Object> result=submit(p,q); check("done".equals(result.get("status")) && runs==1,"signed named scenario executes");
        check(submit(p,q).equals(result) && runs==1,"same request delivers same receipt without re-execution");
        check(submit(protocol(),q).equals(result) && runs==1,"deduplication survives executor restart");
        q.put("scenario","app.observe"); check("rejected".equals(submit(p,q).get("status")) && runs==1,"same identity cannot substitute request");
        q=request("core.test"); q.put("runtime","old"); check("rejected".equals(submit(p,q).get("status")),"stale runtime cannot receive actions");
        q=request("core.test"); q.put("bundle","*"); check("rejected".equals(submit(p,q).get("status")),"wildcards forbidden for execution");
        q=request("app.observe"); q.put("bundle","*"); q.put("runtime","*"); check("done".equals(submit(p,q).get("status")),"observation discovers exact running identity");
        q=request("core.test"); q.put("expires_ms",now-1); check("rejected".equals(submit(p,q).get("status")),"expired request cannot execute");
        q=request("core.test"); q.put("command","arbitrary"); check("rejected".equals(submit(p,q).get("status")),"unknown fields rejected");
        q=request("arbitrary.exec"); check("rejected".equals(submit(p,q).get("status")),"unregistered scenario rejected");
        q=request("core.fail"); result=submit(p,q); int count=runs;
        check("failed".equals(result.get("status")) && submit(p,q).equals(result) && runs==count,"failed outcomes retained, not retried blindly");
        q=request("core.test"); byte[] body=ScenarioProtocol.encode(q),signature=sign(body); body[0]='[';
        boolean bad=false; try { p.process(body,signature); } catch(SecurityException e) { bad=true; }
        check(bad && runs==count,"tampered request rejected before parsing/dispatch");
        bad=false; try { p.process(new byte[ScenarioProtocol.REQUEST_LIMIT+1],signature); } catch(SecurityException e) { bad=true; }
        check(bad,"request payload bounded");
        q=request("core.test"); body=ScenarioProtocol.encode(q);
        Map<String,Object> accepted=Json.map("id",q.get("id"),"request_sha256",ScenarioProtocol.sha(body),
            "expires_ms",q.get("expires_ms"),"status","accepted");
        ScenarioProtocol.write(new File(dir,q.get("id")+".json"),ScenarioProtocol.encode(accepted));
        result=submit(protocol(),q); check("interrupted".equals(result.get("status")) && runs==count,"crash after acceptance is reported, never blindly replayed");
        now+=70000; check("rejected".equals(submit(p,q).get("status")),"expired accepted request remains ineligible");
        q=request("core.test"); check("done".equals(submit(p,q).get("status")),"expired ledger entries reclaimed for new work");
        for(int i=0;i<ScenarioProtocol.JOURNAL_LIMIT;i++) submit(p,request("core.test"));
        count=runs; result=submit(p,request("core.test"));
        check("rejected".equals(result.get("status")) && runs==count,"live replay ledger has hard admission limit");
        Map<String,String> env=new HashMap<>(); env.put("PATH","/usr/bin:/bin");
        File supervisor=new File(args[0],"trough-run");
        result=ScenarioProcess.run(supervisor,new File(args[0]),env,2,"/bin/sh","-c","printf scenario-ok");
        check(Boolean.TRUE.equals(result.get("ok")) && "scenario-ok".equals(result.get("output")),"subprocess outcome checks actual exit and output");
        result=ScenarioProcess.run(supervisor,new File(args[0]),env,1,"/bin/sh","-c","sleep 10 & wait");
        check(Boolean.TRUE.equals(result.get("timed_out")) && Boolean.FALSE.equals(result.get("ok")),"hung scenario times out and is not success");
        check(Boolean.TRUE.equals(result.get("cleanup_complete")) && Boolean.TRUE.equals(result.get("reader_complete")),"timeout reaps descendant retaining output pipe");
        result=ScenarioProcess.run(supervisor,new File(args[0]),env,2,"/bin/sh","-c","exit 124");
        check(Boolean.FALSE.equals(result.get("timed_out")) && Boolean.FALSE.equals(result.get("ok")),"child exit 124 is distinguished from supervisor timeout");
        result=ScenarioProcess.run(new File("/bin/true"),new File(args[0]),env,2,"/bin/true");
        check(Boolean.FALSE.equals(result.get("ok")) && Boolean.FALSE.equals(result.get("cleanup_complete")),"missing supervisor report cannot claim success");
        result=ScenarioProcess.run(supervisor,new File(args[0]),env,2,"/bin/sh","-c","head -c 70000 /dev/zero");
        check(Boolean.TRUE.equals(result.get("truncated")) && Boolean.FALSE.equals(result.get("ok")) &&
            ((String)result.get("output")).length()==65536,"excess output is drained with bounded storage and fails evidence");
        File pidfile=new File(args[0],"interrupted-child"); final Throwable[] error={null};
        Thread worker=new Thread(() -> {
            try { ScenarioProcess.run(supervisor,new File(args[0]),env,20,"/bin/sh","-c",
                "sleep 30 & echo $! > \"$1\"; wait","fixture",pidfile.getAbsolutePath());
                error[0]=new AssertionError("interruption was swallowed");
            } catch(InterruptedException expected) {} catch(Throwable e) { error[0]=e; }
        });
        worker.start(); long deadline=System.nanoTime()+5000000000L;
        while((!pidfile.isFile() || pidfile.length()==0) && System.nanoTime()<deadline) Thread.sleep(10);
        check(pidfile.isFile() && pidfile.length()>0,"interruption fixture descendant started");
        long pid=Long.parseLong(new String(Files.readAllBytes(pidfile.toPath()),"UTF-8").trim());
        worker.interrupt(); worker.join(5000);
        check(!worker.isAlive() && error[0]==null && !ProcessHandle.of(pid).isPresent(),"Java interruption waits for owned descendant cleanup");
        System.out.println(checks+" scenario protocol checks passed");
    }
}
