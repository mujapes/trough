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
final class NativePlatformTextOracle {
    private static final int ROOT_TAG=0x7f0d747c;
    private static final String CORE_PATH="/data/user/0/t.t/files/self-native/6da49afca2e7bb71/lib/libtrough-android.so";
    private static final String CORE_HASH="214fa2bab054ac38b3dd57883a1ca4126b859bb7dd8acf24c2a33a81a037827e";
    private static final String ORACLE_PATH="/data/user/0/t.t/files/work/trough-lab/validation/native-platform-text/libtrough-platform-text-oracle-v1.so";
    private static final String ORACLE_HASH="1a583f6732daeb044228e52482bdcd609ba6106e98e9c7df0afe7d87a1a9e505";
    @SuppressWarnings("unchecked")
    static Callable<String> factory(Context context,View root) {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Capture root owner on main thread");
        Object carried=root.getTag(ROOT_TAG);
        if(carried!=null && !(carried instanceof java.util.Map))throw new IllegalStateException("Native text oracle root registry occupied");
        java.util.Map<String,Object> owners;
        if(carried==null){owners=new java.util.LinkedHashMap<String,Object>();root.setTag(ROOT_TAG,owners);}
        else owners=(java.util.Map<String,Object>)carried;
        final String revision=CORE_HASH+":"+ORACLE_HASH+":mixed-rtl-spaces-r6";
        Object prior=owners.get(revision);
        if(prior instanceof Callable)return (Callable<String>)prior;
        if(prior!=null)throw new IllegalStateException("Native text oracle revision occupied");
        final File cache=new File(context.getApplicationContext().getCodeCacheDir(),"trough-platform-text-oracle");
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
        owners.put(revision,owner);return owner;
    }
    private static String completed;
    private static String identity;
    private static boolean attempted;
    private static native Object[] layoutNative(String corePath,char[] text,String locale,float size,int width);
    private static synchronized String run(File core,String coreSha256,File oracle,String oracleSha256) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Native text oracle needs worker");
        String key=core.getCanonicalPath()+":"+coreSha256+":"+oracle.getCanonicalPath()+":"+oracleSha256;
        if(completed!=null && key.equals(identity))return completed;
        if(attempted)throw new IllegalStateException("One oracle revision per classloader; retain this owner across reload");
        verify(core,coreSha256);verify(oracle,oracleSha256);
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        attempted=true;identity=key;
        System.load(oracle.getCanonicalPath());
        completed=new Comparison().run(core.getCanonicalPath());
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
    /** Independent Android reference; no reference values enter native layout. */
    private static final class Comparison {
        private static final float PIXEL_TOLERANCE=0.5f;
        private int cases,checks,failures,graphemeCarets,skippedInteriorCarets,hits;
        private float worstDelta,caseWorst;
        private final java.util.List<Object> caseResults=new java.util.ArrayList<Object>();
        private java.util.List<String> caseDetails;
        private final java.util.List<String> details=new java.util.ArrayList<String>();
        private void check(boolean ok,String detail) {
            checks++;if(!ok){failures++;if(details.size()<48)details.add(detail);if(caseDetails!=null && caseDetails.size()<4)caseDetails.add(detail);}
        }
        private void near(float actual,float expected,String label) {
            float delta=Math.abs(actual-expected);
            if(Float.isFinite(delta)){worstDelta=Math.max(worstDelta,delta);caseWorst=Math.max(caseWorst,delta);}
            check(Float.isFinite(actual) && delta<=PIXEL_TOLERANCE,label+" native="+actual+" platform="+expected);
        }
        String run(String corePath) throws Exception {
            String[] texts={"", "MOON GLOW 57", "one two three four five six seven", "  leading and trailing  ",
                "tab\tstop next", "e\u0301 cafe\u0301 fi ffi", "\u05e9\u05dc\u05d5\u05dd 123 test", "\u0633\u0644\u0627\u0645 test 123", "emoji \ud83d\ude00 end", "a ", "ab   ", "     ", "x y  ", "  z  ", "abc\t ", "\u05e9\u05dc\u05d5\u05dd  ", "\u0633\u0644\u0627\u0645  ", "\tx", "a\t\tb", "ab\tc", "abcd\tz", "\t\tX", "          \tx", "\t", "\t\t", "ab \t\t ", "          \t", "abc\t\t", " \t ", "שלום ", "سلام   ", "שלום שלום שלום   ", "سلام سلام سلام   ", "שלום 123 test   ", "سلام test 123   "};
            String[] locales={"en","en","en","en","en","en","he","ar","en","en","en","en","en","en","en","he","ar","en","en","en","en","en","en"};
            for(int i=0;i<texts.length;i++)for(float size:new float[]{18,24})for(int width:new int[]{96,240}) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                String label="fixture="+i+" size="+size+" width="+width;cases++;
                int beforeFailures=failures,beforeChecks=checks;caseWorst=0;caseDetails=new java.util.ArrayList<String>();
                try {compare(corePath,texts[i],i<locales.length?locales[i]:i>=29?(i%2==1?"he":"ar"):"en",size,width,label);}
                catch(RuntimeException error){check(false,label+" exception="+error.toString());}
                caseResults.add(Json.map("fixture",i,"cohort",i<9?"original":i<17?"additional-whitespace":i<23?"additional-visible-tabs":i<29?"additional-trailing-tabs":"additional-rtl-spaces","size",size,"width",width,"checks",checks-beforeChecks,"failures",failures-beforeFailures,"worst_pixel_delta",caseWorst,"examples",caseDetails));
            }
            return Json.write(Json.map("ok",failures==0,"cases",cases,"checks",checks,"failures",failures,"details",details,
                "case_results",caseResults,"core_sha256",CORE_HASH,"reference","Android StaticLayout","pixel_tolerance",PIXEL_TOLERANCE,"worst_pixel_delta",worstDelta,
                "grapheme_carets",graphemeCarets,"skipped_interior_utf16_carets",skippedInteriorCarets,"hit_queries",hits,
                "native_vs_native",false,"ui_replaced",false,"rich_style_parity",false,
                "unsupported","spans/bold/italic/monospace/mixed sizes, LF paragraph joining, raster pixels, accessibility, selection UI"));
        }
        private void compare(String corePath,String text,String locale,float size,int width,String label) {
            android.text.TextPaint paint=new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(size);paint.setTextLocale(java.util.Locale.forLanguageTag(locale));
            android.text.StaticLayout reference=android.text.StaticLayout.Builder.obtain(text,0,text.length(),paint,width)
                .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
                .setTextDirection(android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(false).setUseLineSpacingFromFallbacks(true).build();
            Object[] raw=layoutNative(corePath,text.toCharArray(),locale,size,width);
            check(raw!=null && raw.length==4,label+" native result");if(raw==null || raw.length!=4)return;
            float[] wire=(float[])raw[0],carets=(float[])raw[2],queries=(float[])raw[3];
            check(wire.length>=4 && carets.length==(text.length()+1)*2 && queries.length%3==0,label+" wire bounds");
            if(wire.length<4 || carets.length!=(text.length()+1)*2 || queries.length%3!=0)return;
            int lines=(int)wire[0],runs=(int)wire[1],glyphs=(int)wire[2];
            check(lines>=1 && wire.length==4+lines*10+runs*6+glyphs*4,label+" geometry shape");
            if(lines<1 || wire.length!=4+lines*10+runs*6+glyphs*4)return;
            check(lines==reference.getLineCount(),label+" line count native="+lines+" platform="+reference.getLineCount());
            near(wire[3],reference.getHeight(),label+" height");
            for(int line=0;line<Math.min(lines,reference.getLineCount());line++) {
                int at=4+line*10;
                check(wire[at]==reference.getLineStart(line),label+" line="+line+" start");
                check(wire[at+1]==reference.getLineEnd(line),label+" line="+line+" end");
                near(wire[at+3],reference.getLineTop(line),label+" line="+line+" top");
                near(wire[at+4],reference.getLineBaseline(line),label+" line="+line+" baseline");
                near(wire[at+5],reference.getLineBottom(line),label+" line="+line+" bottom");
            }
            android.icu.text.BreakIterator boundaries=android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
            boundaries.setText(text);
            for(int at=0;at<=text.length();at++) {
                if(!boundaries.isBoundary(at)){skippedInteriorCarets++;continue;}
                graphemeCarets++;near(carets[at*2],reference.getPrimaryHorizontal(at),label+" caret="+at);
            }
            // Require exact hit offsets; ties are reported, never silently waived.
            if(lines==reference.getLineCount())for(int at=0;at<queries.length;at+=3) {
                int line=(int)queries[at];float x=queries[at+1];int nativeOffset=(int)queries[at+2];
                check(line>=0 && line<lines,label+" hit line bounds");if(line<0 || line>=lines)continue;
                hits++;int expected=reference.getOffsetForHorizontal(line,x);
                check(nativeOffset==expected,label+" hit line="+line+" x="+x+" native="+nativeOffset+" platform="+expected);
            }
        }
    }

}
