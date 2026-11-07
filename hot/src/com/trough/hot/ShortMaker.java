package com.trough.hot;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaMetadataRetriever;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded offline short production. Call only on a worker; never the main looper. */
final class ShortMaker {
    static final int W=352,H=624,FPS=12,FRAMES=192;
    private static final int TEAL=0xff68ead5,AMBER=0xffffbe70,WHITE=0xffe7f1f2;
    private static final long BUDGET_MS=120000;
    private ShortMaker() {}
    static Map<String,Object> make(File output) throws Exception { return make(output,null); }
    static float control(Map<String,Object> values,String name,float fallback) {
        Object value=values==null?null:values.get(name);
        float f=value instanceof Number?((Number)value).floatValue():fallback;
        return Float.isNaN(f)||Float.isInfinite(f)?fallback:Math.max(0,Math.min(1,f));
    }
    static Map<String,Object> make(File output,Map<String,Object> values) throws Exception {
        final float motion=control(values,"motion",.4f),warmth=control(values,"warmth",.5f),focus=control(values,"focus",.5f);
        if(Looper.myLooper()==Looper.getMainLooper()) throw new IllegalStateException("ShortMaker requires a worker");
        long start=SystemClock.elapsedRealtime();
        File parent=output.getAbsoluteFile().getParentFile();
        if(!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create output directory");
        File partial=File.createTempFile("ordinal-short-",".mp4",parent);
        Bitmap bitmap=null;MediaCodec codec=null;MediaMuxer muxer=null;
        boolean muxStarted=false,codecStarted=false,published=false;int samples=0;
        String encoder="";
        try {
            bitmap=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bitmap);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);Path path=new Path();
            int[] pixels=new int[W*H];
            MediaFormat format=MediaFormat.createVideoFormat("video/avc",W,H);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE,750000);format.setInteger(MediaFormat.KEY_FRAME_RATE,FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2);
            codec=MediaCodec.createEncoderByType("video/avc");encoder=codec.getName();
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();codecStarted=true;
            muxer=new MediaMuxer(partial.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int frame=0,track=-1;boolean inputEnd=false,outputEnd=false;
            while(!outputEnd) {
                if(Thread.currentThread().isInterrupted()) throw new InterruptedException("Short generation interrupted");
                if(SystemClock.elapsedRealtime()-start>BUDGET_MS) throw new IOException("Short generation exceeded 120 seconds");
                if(!inputEnd) {
                    int index=codec.dequeueInputBuffer(10000);
                    if(index>=0) {
                        long pts=frame*1000000L/FPS;
                        if(frame==FRAMES) {codec.queueInputBuffer(index,0,0,pts,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnd=true;}
                        else {
                            render(canvas,paint,path,frame/(float)FPS,motion,warmth,focus);
                            bitmap.getPixels(pixels,0,W,0,0,W,H);
                            Image image=codec.getInputImage(index);
                            if(image==null) throw new IOException("AVC encoder did not expose flexible YUV input");
                            fillYuv(image,pixels);
                            codec.queueInputBuffer(index,0,W*H*3/2,pts,0);frame++;
                        }
                    }
                }
                int index=codec.dequeueOutputBuffer(info,10000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if(muxStarted) throw new IOException("Encoder changed format twice");
                    track=muxer.addTrack(codec.getOutputFormat());muxer.start();muxStarted=true;
                } else if(index>=0) {
                    try {
                        if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0) info.size=0;
                        if(info.size>0) {
                            if(!muxStarted) throw new IOException("Encoded sample before format");
                            ByteBuffer buffer=codec.getOutputBuffer(index);
                            if(buffer==null) throw new IOException("Missing encoder output");
                            buffer.position(info.offset);buffer.limit(info.offset+info.size);
                            muxer.writeSampleData(track,buffer,info);samples++;
                        }
                        outputEnd=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    } finally {codec.releaseOutputBuffer(index,false);}
                }
            }
            if(samples!=FRAMES) throw new IOException("Expected "+FRAMES+" video samples, got "+samples);
            muxer.stop();muxStarted=false;muxer.release();muxer=null;
            codec.stop();codecStarted=false;codec.release();codec=null;
            if(partial.length()<1024) throw new IOException("Empty generated video");
            // Verify an actual decoded frame before publishing the completed file.
            MediaMetadataRetriever decoder=new MediaMetadataRetriever();Bitmap decoded=null;
            try {
                decoder.setDataSource(partial.getAbsolutePath());
                decoded=decoder.getFrameAtTime(12000000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if(decoded==null || decoded.getWidth()!=W || decoded.getHeight()!=H) throw new IOException("Generated video did not decode at expected dimensions");
                File preview=new File(parent,"gematria-57-preview.png");
                try(java.io.FileOutputStream stream=new java.io.FileOutputStream(preview)) { decoded.compress(Bitmap.CompressFormat.PNG,100,stream); }
            } finally {if(decoded!=null) decoded.recycle();decoder.release();}
            // Same-directory rename publishes only a fully finalized, decoded MP4.
            if(!partial.renameTo(output)) throw new IOException("Cannot publish completed short");
            published=true;
            Map<String,Object> result=new LinkedHashMap<String,Object>();
            result.put("ok",true);result.put("path",output.getAbsolutePath());result.put("bytes",output.length());
            result.put("width",W);result.put("height",H);result.put("fps",FPS);result.put("frames",samples);
            result.put("duration_ms",16000);result.put("generation_ms",SystemClock.elapsedRealtime()-start);
            result.put("decoded_frame_verified",true);result.put("encoder",encoder);result.put("audio",false);result.put("scheme","English ordinal A=1..Z=26");
            result.put("visual_controls",Json.map("motion",motion,"warmth",warmth,"focus",focus));result.put("control_source",values==null?"defaults":"snapshot of learned preferences through Dogshit graph");result.put("moon",57);result.put("glow",57);return result;
        } finally {
            if(codec!=null) {if(codecStarted) try {codec.stop();} catch(Exception ignored) {} try {codec.release();} catch(Exception ignored) {}}
            if(muxer!=null) {if(muxStarted) try {muxer.stop();} catch(Exception ignored) {} try {muxer.release();} catch(Exception ignored) {}}
            if(bitmap!=null) bitmap.recycle();
            if(!published) partial.delete();
        }
    }
    private static int clamp(int x) {return x<0?0:x>255?255:x;}
    static int y(int r,int g,int b) {return clamp(((66*r+129*g+25*b+128)>>8)+16);}
    static int u(int r,int g,int b) {return clamp(((-38*r-74*g+112*b+128)>>8)+128);}
    static int v(int r,int g,int b) {return clamp(((112*r-94*g-18*b+128)>>8)+128);}
    private static void fillYuv(Image image,int[] pixels) throws IOException {
        Image.Plane[] planes=image.getPlanes();
        if(planes.length!=3 || image.getWidth()!=W || image.getHeight()!=H) throw new IOException("Unexpected YUV image layout");
        ByteBuffer yy=planes[0].getBuffer(),uu=planes[1].getBuffer(),vv=planes[2].getBuffer();
        int ybase=yy.position(),ubase=uu.position(),vbase=vv.position();
        int ys=planes[0].getRowStride(),us=planes[1].getRowStride(),vs=planes[2].getRowStride();
        int yp=planes[0].getPixelStride(),up=planes[1].getPixelStride(),vp=planes[2].getPixelStride();
        for(int row=0;row<H;row+=2) for(int col=0;col<W;col+=2) {
            int rs=0,gs=0,bs=0;
            for(int dy=0;dy<2;dy++) for(int dx=0;dx<2;dx++) {
                int rgb=pixels[(row+dy)*W+col+dx],r=(rgb>>16)&255,g=(rgb>>8)&255,b=rgb&255;
                yy.put(ybase+(row+dy)*ys+(col+dx)*yp,(byte)y(r,g,b));rs+=r;gs+=g;bs+=b;
            }
            uu.put(ubase+(row/2)*us+(col/2)*up,(byte)u(rs/4,gs/4,bs/4));
            vv.put(vbase+(row/2)*vs+(col/2)*vp,(byte)v(rs/4,gs/4,bs/4));
        }
    }
    private static void text(Canvas c,Paint p,String s,float size,float baseline,int color) {
        p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(size);
        c.drawText(s,W/2f,baseline,p);
    }
    static void render(Canvas c,Paint p,Path path,float t) { render(c,p,path,t,.4f,.5f,.5f); }
    static void render(Canvas c,Paint p,Path path,float t,float motion,float warmth,float focus) {
        float phase=t*(.2f+motion*2f);
        c.drawColor(0xff08151c);p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        // Deterministic flowing ribbons, only one reusable path and paint per frame.
        p.setStyle(Paint.Style.STROKE);
        for(int ribbon=0;ribbon<12;ribbon++) {
            p.setColor((ribbon/12f<warmth?AMBER:TEAL)&0xffffff | 0x26000000);p.setStrokeWidth(1.2f+ribbon*.14f);path.reset();
            for(int k=0;k<=36;k++) {
                float yy=80+k*14;
                float xx=W/2f+(float)Math.sin(k*.14+phase*.55+ribbon*.4)*(45+(1-focus)*65+ribbon*5)+(float)Math.cos(phase*.8+k*.23)*19;
                if(k==0) path.moveTo(xx,yy);else path.lineTo(xx,yy);
            }
            c.drawPath(path,p);
        }
        p.setStyle(Paint.Style.FILL);
        for(int i=0;i<48;i++) {
            double angle=i*2.399963+phase*(.35+i%3*.12);
            float radius=58+(i%12)*9;
            float x=W/2f+(float)Math.cos(angle)*radius;
            float y=H/2f+(float)Math.sin(angle)*radius*1.65f;
            p.setColor(((i/48f<warmth?AMBER:TEAL)&0xffffff)|0x70000000);
            c.drawCircle(x,y,1.2f+(i%4)*.55f,p);
        }
        p.setStyle(Paint.Style.FILL);
        text(c,p,"T R O U G H  /  0 0 1",12,42,0xff89a5ae);
        text(c,p,"ENGLISH ORDINAL",13,82,WHITE);
        text(c,p,"A = 1   ·   Z = 26",14,105,0xff8ca9b0);
        float pulse=1+(float)Math.sin(phase*1.6)*.035f;
        p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        if(t<5.5f) {
            text(c,p,"MOON",54*pulse,242,TEAL);
            text(c,p,"13 + 15 + 15 + 14",21,290,WHITE);
            text(c,p,"57",100,410,TEAL);
        } else if(t<11) {
            text(c,p,"GLOW",54*pulse,242,AMBER);
            text(c,p,"7 + 12 + 15 + 23",21,290,WHITE);
            text(c,p,"57",100,410,AMBER);
        } else {
            text(c,p,"MOON   /   GLOW",30,231,WHITE);
            text(c,p,"57",100*pulse,361,TEAL);
            text(c,p,"SAME TOTAL",25,423,AMBER);
            text(c,p,"DIFFERENT WORDS",23,456,WHITE);
        }
        p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        text(c,p,t<11?"A pattern to play with.":"A number is a connection.",17,530,WHITE);
        text(c,p,t<11?"Watch what shares the number.":"It does not make meanings identical.",15,555,0xff9fb6bf);
        p.setColor(0xff20363e);c.drawRect(28,594,W-28,597,p);
        p.setColor(t<5.5f?TEAL:AMBER);c.drawRect(28,594,28+(W-56)*Math.min(1,t/16f),597,p);
    }
}
