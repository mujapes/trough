package com.trough.hot;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

/** Explicit private diagnostic. No heap contents enter runtime JSON or provider requests. */
final class HeapSnapshot {
    static void captureIfRequested(final File directory) {
        final File request=new File(directory,"heap-snapshot-request");
        final File processing=new File(directory,"heap-snapshot-processing");
        if(!request.isFile() || processing.exists())return;
        if(!request.renameTo(processing))return;
        Thread worker=new Thread(new Runnable(){public void run(){
            File temporary=new File(directory,"heap-private.hprof.new");
            String id="",error="";boolean ok=false,owned=false;long started=System.currentTimeMillis();
            try(FileOutputStream lockFile=new FileOutputStream(new File(directory,"heap-snapshot.lock"),true);
                    FileChannel channel=lockFile.getChannel();FileLock lock=channel.tryLock()) {
                if(lock==null)throw new java.io.IOException("Heap capture already running");
                owned=true;
                if(processing.length()>80)throw new java.io.IOException("Invalid heap request");
                id=new String(Payloads.readSmall(processing),"UTF-8").trim();
                if(!id.matches("[A-Za-z0-9_-]{1,64}"))throw new java.io.IOException("Invalid heap request");
                android.os.Debug.dumpHprofData(temporary.getAbsolutePath());
                if(!temporary.isFile() || temporary.length()==0)throw new java.io.IOException("Empty heap capture");
                try(FileOutputStream sync=new FileOutputStream(temporary,true)){sync.getFD().sync();}
                if(!temporary.renameTo(new File(directory,"heap-private.hprof")))throw new java.io.IOException("Heap publication failed");
                ok=true;
            }catch(Exception failure){error=failure.getClass().getSimpleName();}
            catch(OutOfMemoryError exhausted){error="OutOfMemoryError";}
            finally {
                if(owned)temporary.delete();
                try {Payloads.writeSmall(new File(directory,"heap-snapshot-result.json"),Json.write(Json.map(
                    "id",id,"ok",ok,"error",error,"started_ms",started,"completed_ms",System.currentTimeMillis(),
                    "bytes",ok?new File(directory,"heap-private.hprof").length():0)));}catch(Exception ignored){}
                processing.delete();
            }
        }},"trough-private-heap");
        worker.start();
    }
}
