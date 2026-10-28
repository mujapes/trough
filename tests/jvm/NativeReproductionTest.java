package com.trough.hot;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class NativeReproductionTest {
    static int checks;
    static void check(boolean value,String label) {
        if(!value) throw new AssertionError(label);
        checks++; System.out.println("ok "+label);
    }
    static void write(File file,String value) throws Exception { ScenarioProtocol.write(file,value.getBytes("UTF-8")); }
    static void rejects(File a,File b,String label) throws Exception {
        boolean rejected=false;
        try { NativeReproduction.compare(a,b); } catch(IOException expected) { rejected=true; }
        check(rejected,label);
    }
    public static void main(String[] args) throws Exception {
        File root=new File(args[0]),a=new File(root,"expected"),b=new File(root,"actual");
        write(new File(a,"outputs.list"),"bin/sample\n"); write(new File(b,"outputs.list"),"bin/sample\n");
        byte[] data=new byte[40001]; new Random(42).nextBytes(data);
        ScenarioProtocol.write(new File(a,"bin/sample"),data); ScenarioProtocol.write(new File(b,"bin/sample"),data);
        check(Boolean.TRUE.equals(NativeReproduction.compare(a,b).get("ok")),"streamed multi-chunk outputs compare exactly");
        data[20000]^=1; ScenarioProtocol.write(new File(b,"bin/sample"),data);
        check(Boolean.FALSE.equals(NativeReproduction.compare(a,b).get("ok")),"same-size corruption fails reproduction");
        ScenarioProtocol.write(new File(b,"bin/sample"),Arrays.copyOf(data,data.length-1));
        check(Boolean.FALSE.equals(NativeReproduction.compare(a,b).get("ok")),"shortened output fails reproduction");
        Files.delete(new File(b,"bin/sample").toPath()); rejects(a,b,"missing output fails reproduction");
        Files.createSymbolicLink(new File(b,"bin/sample").toPath(),new File(a,"bin/sample").toPath());
        rejects(a,b,"symlink cannot substitute for built output"); Files.delete(new File(b,"bin/sample").toPath());
        write(new File(b,"bin/sample"),"x");
        try(RandomAccessFile f=new RandomAccessFile(new File(b,"bin/sample"),"rw")) { f.setLength(32L*1024*1024+1); }
        rejects(a,b,"oversized output rejected before reading into memory");
        write(new File(b,"outputs.list"),"bin/other\n"); rejects(a,b,"candidate cannot weaken expected inventory");
        for(String invalid:new String[]{"","../outside","bin/sample\nbin/sample\n","bin/../escape"}) {
            write(new File(a,"outputs.list"),invalid); rejects(a,b,"invalid or duplicate inventory rejected: "+invalid.replace('\n',' '));
        }

        File bundle=new File(root,"bundle"),cache=new File(root,"cache"); cache.mkdir();
        write(new File(bundle,"outputs.list"),"bin/sample\n"); write(new File(bundle,"bin/sample"),"reproduced");
        Files.copy(new File(root,"trough-run").toPath(),new File(bundle,"bin/trough-run").toPath());
        new File(bundle,"bin/trough-run").setExecutable(true);
        File source=new File(bundle,"src/native"),script=new File(source,"build.sh");
        write(new File(source,"outputs.list"),"bin/sample\n"); write(new File(source,"expected.bin"),"reproduced");
        String build="set -eu\nmkdir -p \"$2/bin\"\ncp \"$1/expected.bin\" \"$2/bin/sample\"\ncp \"$1/outputs.list\" \"$2/outputs.list\"\n";
        write(script,build);
        Map<String,String> env=new HashMap<>(); env.put("PATH","/usr/bin:/bin");
        Map<String,Object> result=NativeReproduction.run(bundle,cache,env,"/bin/sh","/bin/true");
        check(Boolean.TRUE.equals(result.get("ok")) && cache.list().length==0,"supervised build and fix pass exact comparison and remove scratch");
        write(script,build+"exit 7\n");
        result=NativeReproduction.run(bundle,cache,env,"/bin/sh","/bin/true");
        check(Boolean.FALSE.equals(result.get("ok")) && !result.containsKey("fix") && cache.list().length==0,
            "failed build cannot pass even with identical outputs; fix is not run");
        write(script,build);
        result=NativeReproduction.run(bundle,cache,env,"/bin/sh","/bin/false");
        check(Boolean.FALSE.equals(result.get("ok")) && !result.containsKey("comparison") && cache.list().length==0,
            "failed interpreter fixing cannot pass identical outputs");
        File ready=new File(source,"started");
        write(script,"sleep 30 & echo $! > \"$1/started\"\nwait\n");
        final Throwable[] error={null};
        Thread worker=new Thread(() -> {
            try { NativeReproduction.run(bundle,cache,env,"/bin/sh","/bin/true"); error[0]=new AssertionError("interruption lost"); }
            catch(InterruptedException expected) {} catch(Throwable e) { error[0]=e; }
        });
        worker.start(); long end=System.nanoTime()+5000000000L;
        while((!ready.isFile() || ready.length()==0) && System.nanoTime()<end) Thread.sleep(10);
        check(ready.isFile() && ready.length()>0,"native build cancellation fixture started");
        long pid=Long.parseLong(new String(Files.readAllBytes(ready.toPath()),"UTF-8").trim());
        worker.interrupt(); worker.join(5000);
        check(!worker.isAlive() && error[0]==null && !ProcessHandle.of(pid).isPresent() && cache.list().length==0,
            "build interruption reaps compiler descendants before removing scratch");
        System.out.println(checks+" native reproduction checks passed");
    }
}
