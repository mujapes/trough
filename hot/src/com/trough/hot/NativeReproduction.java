package com.trough.hot;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Rebuild immutable bundle sources in private scratch; never install the candidate. */
final class NativeReproduction {
    private static final long FILE_LIMIT=32L*1024*1024, TOTAL_LIMIT=256L*1024*1024;

    static String[] outputs(File build) throws Exception {
        String text=new String(ScenarioProtocol.read(new File(build,"outputs.list"),16384),"UTF-8").trim();
        String[] paths=text.split("\\s+");
        Set<String> seen=new HashSet<>();
        if(paths.length>128) throw new IOException("native output count");
        for(String path:paths)
            if(!path.matches("(bin|lib|fixtures)/[a-zA-Z0-9_.-]+") || !seen.add(path))
                throw new IOException("native output inventory");
        return paths;
    }
    private static String hex(byte[] bytes) {
        StringBuilder s=new StringBuilder();
        for(byte b:bytes) s.append(String.format(Locale.ROOT,"%02x",b&255));
        return s.toString();
    }
    static Map<String,Object> compare(File expected,File actual) throws Exception {
        String[] paths=outputs(expected);
        if(!Arrays.equals(paths,outputs(actual))) throw new IOException("native output inventory changed");
        Map<String,Object> files=new LinkedHashMap<>(); boolean all=true; long total=0;
        for(String path:paths) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
            File a=new File(expected,path),b=new File(actual,path);
            if(!Files.isRegularFile(a.toPath(),LinkOption.NOFOLLOW_LINKS) ||
               !Files.isRegularFile(b.toPath(),LinkOption.NOFOLLOW_LINKS))
                throw new IOException("missing native output: "+path);
            long size=a.length(),other=b.length();
            if(size>FILE_LIMIT || other>FILE_LIMIT || (total+=size+other)>TOTAL_LIMIT)
                throw new IOException("native output byte budget");
            boolean equal=size==other;
            MessageDigest da=MessageDigest.getInstance("SHA-256"),db=MessageDigest.getInstance("SHA-256");
            try(DataInputStream ia=new DataInputStream(new BufferedInputStream(new FileInputStream(a)));
                DataInputStream ib=new DataInputStream(new BufferedInputStream(new FileInputStream(b)))) {
                byte[] ba=new byte[16384],bb=new byte[16384];
                for(long at=0;at<Math.max(size,other);at+=ba.length) {
                    if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    int na=(int)Math.min(ba.length,Math.max(0,size-at));
                    int nb=(int)Math.min(bb.length,Math.max(0,other-at));
                    ia.readFully(ba,0,na); ib.readFully(bb,0,nb); da.update(ba,0,na); db.update(bb,0,nb);
                    if(na!=nb) equal=false;
                    else for(int i=0;i<na;i++) if(ba[i]!=bb[i]) { equal=false; break; }
                }
                if(ia.read()!=-1 || ib.read()!=-1) throw new IOException("native output grew during comparison");
            }
            all &= equal;
            files.put(path,Json.map("identical",equal,"expected_bytes",size,"actual_bytes",other,
                "expected_sha256",hex(da.digest()),"actual_sha256",hex(db.digest())));
        }
        return Json.map("ok",all,"outputs",files,"compared_bytes",total);
    }
    private static void remove(File path) throws IOException {
        if(Files.isDirectory(path.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            File[] children=path.listFiles();
            if(children==null) throw new IOException("cannot list build scratch");
            for(File child:children) remove(child);
        }
        Files.deleteIfExists(path.toPath());
    }
    static Map<String,Object> run(File bundle,File cache,Map<String,String> env,String shell,String fixer) throws Exception {
        outputs(bundle); // Reject malformed inventory before starting a build.
        File work=Files.createTempDirectory(cache.toPath(),"native-reproduction-").toFile();
        File candidate=new File(work,"candidate"),source=new File(bundle,"src/native");
        File supervisor=new File(bundle,"bin/trough-run");
        Map<String,Object> result=Json.map("ok",false);
        try {
            Map<String,Object> build=ScenarioProcess.run(supervisor,work,env,180,shell,
                new File(source,"build.sh").getAbsolutePath(),source.getAbsolutePath(),candidate.getAbsolutePath());
            result.put("build",build);
            if(!Boolean.TRUE.equals(build.get("ok"))) return result;
            build.remove("output"); // Keep combined evidence bounded; failure retains diagnostics.
            Map<String,Object> fix=ScenarioProcess.run(supervisor,work,env,30,fixer,candidate.getAbsolutePath());
            result.put("fix",fix);
            if(!Boolean.TRUE.equals(fix.get("ok"))) return result;
            fix.remove("output");
            Map<String,Object> comparison=compare(bundle,candidate);
            result.put("comparison",comparison); result.put("ok",comparison.get("ok"));
            return result;
        } finally { remove(work); }
    }
}
