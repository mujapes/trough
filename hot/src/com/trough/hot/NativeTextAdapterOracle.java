package com.trough.hot;
import android.os.Looper;
import android.content.Context;
import android.view.View;
import java.io.FileOutputStream;
import java.util.concurrent.Callable;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
/** Isolated worker diagnostic; no NativeCore registration or transcript mutation. */
final class NativeTextAdapterOracle {
    private static final int ROOT_TAG=0x7f0d7479;
    private static final String CORE_PATH="/data/user/0/t.t/files/self-native/40e714ec6431774f/lib/libtrough-android.so";
    private static final String CORE_HASH="ee3b68e16b6f6c89e602214a76dbdd7356e4fc3b44af9c8ab098cb44aefe1a4b";
    private static final String ORACLE_PATH="/data/user/0/t.t/files/work/trough-lab/validation/native-text-adapter/libtrough-text-adapter-oracle-v1.so";
    private static final String ORACLE_HASH="f9acfc6448190ef9ce7a0eecc082b2e97e4dd4d58a1f0a7e0e125568b294a7a3";
    @SuppressWarnings("unchecked")
    static Callable<String> factory(Context context,View root) {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Capture root owner on main thread");
        Object carried=root.getTag(ROOT_TAG);
        if(carried instanceof Callable)return (Callable<String>)carried;
        if(carried!=null)throw new IllegalStateException("Native text oracle root tag occupied");
        final File cache=new File(context.getApplicationContext().getCodeCacheDir(),"trough-text-oracle");
        Callable<String> owner=new Callable<String>() {
            private File library;
            public synchronized String call() throws Exception {
                if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Native text oracle requires worker");
                if(library==null) {
                    File source=new File(ORACLE_PATH);verify(source,ORACLE_HASH);
                    if(!cache.isDirectory() && !cache.mkdirs())throw new java.io.IOException("Native text cache unavailable");
                    File copy=File.createTempFile("text-"+ORACLE_HASH.substring(0,12)+"-",".so",cache);
                    boolean ready=false;
                    try {
                        FileInputStream in=new FileInputStream(source);
                        try {FileOutputStream out=new FileOutputStream(copy);try {
                            byte[] buffer=new byte[16384];int count;while((count=in.read(buffer))>=0)if(count>0)out.write(buffer,0,count);out.getFD().sync();
                        }finally{out.close();}}finally{in.close();}
                        if(!copy.setReadOnly())throw new java.io.IOException("Cannot seal native text library");
                        verify(copy,ORACLE_HASH);library=copy;ready=true;
                    }finally{if(!ready)copy.delete();}
                }
                return run(new File(CORE_PATH),CORE_HASH,library,ORACLE_HASH);
            }
        };
        root.setTag(ROOT_TAG,owner);return owner;
    }
    private static String completed;
    private static String identity;
    private static boolean attempted;
    private static native String runNative(String corePath);
    private static synchronized String run(File core,String coreSha256,File oracle,String oracleSha256) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Native text oracle needs worker");
        String key=core.getCanonicalPath()+":"+coreSha256+":"+oracle.getCanonicalPath()+":"+oracleSha256;
        if(completed!=null && key.equals(identity))return completed;
        if(attempted)throw new IllegalStateException("One oracle revision per classloader; retain this owner across reload");
        verify(core,coreSha256);verify(oracle,oracleSha256);
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        attempted=true;identity=key;
        System.load(oracle.getCanonicalPath());
        completed=runNative(core.getCanonicalPath());
        if(completed==null)throw new IllegalStateException("Native oracle returned no report");
        return completed;
    }
    private static void verify(File file,String expected) throws Exception {
        if(expected==null || !expected.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Expected SHA256 required");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        FileInputStream in=new FileInputStream(file);
        try {byte[] block=new byte[16384];int count;while((count=in.read(block))>=0)if(count>0)digest.update(block,0,count);}finally{in.close();}
        StringBuilder actual=new StringBuilder(64);for(byte value:digest.digest())actual.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        if(!expected.equals(actual.toString()))throw new SecurityException("Native oracle checksum mismatch: "+file.getName());
    }
}
