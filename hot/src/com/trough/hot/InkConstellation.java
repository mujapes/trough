package com.trough.hot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import java.util.LinkedHashMap;
import java.util.Map;

/** A bounded native drawing instrument. Word values are exact; only their motion is playful. */
final class InkConstellation {
    private static final int NODES=8,DUST=40,GLYPHS=24;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path orbit=new Path();
    private final Typeface serif=Typeface.create("serif",Typeface.ITALIC);
    private final Typeface figures=Typeface.create("sans-serif-light",Typeface.NORMAL);
    private final float density;
    private final float[] x=new float[NODES],y=new float[NODES],vx=new float[NODES],vy=new float[NODES];
    private final float[] px=new float[DUST],py=new float[DUST],pvx=new float[DUST],pvy=new float[DUST],life=new float[DUST];
    private final String[] words=new String[NODES],glyphs=new String[GLYPHS];
    private int count,glyphCount,cursor,seed=0x1932fa41,total,draws,touches;
    private boolean enabled;
    private String source="",number="",caption="HOLD TO WANDER";
    // The one shared learned adapter supplies targets. Physics interprets them;
    // touching or drawing here never fabricates a training label.
    private float calm=.5f,focus=.5f,depth=.5f,energy=.5f,warmth=.5f,contrast=.5f,detail=.5f,textScale=.5f;
    private float targetCalm=.5f,targetFocus=.5f,targetDepth=.5f,targetEnergy=.5f,targetWarmth=.5f;
    private float targetContrast=.5f,targetDetail=.5f,targetTextScale=.5f;
    private long weightsVersion;
    private int controlChanges;
    private int teal=0xff83e9d5,gold=0xffe4bc78;
    private float left,top,width,height,phase,attractX=.5f,attractY=.5f,attraction;
    private long lastFrame,lastDrawNanos,maxDrawNanos;

    InkConstellation(Context context) {
        density=context.getResources().getDisplayMetrics().density;
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setTextAlign(Paint.Align.CENTER);
        for(int i=0;i<NODES;i++) {
            float a=(float)(i*Math.PI*2/NODES);
            x[i]=.5f+(float)Math.cos(a)*.27f;y[i]=.5f+(float)Math.sin(a)*.24f;
        }
    }

    /** Material changes allocate labels here, never inside draw(). Matches are checked again. */
    void material(String currentWord,int ordinal,String[] matches,Map<String,Object> adaptive) {
        String next=currentWord==null?"":currentWord;
        if(next.length()>256) next=next.substring(0,256);
        LetterValues.Result sourceValue=LetterValues.analyse(next);
        if(adaptive!=null) controls(read(adaptive,"calm",targetCalm),read(adaptive,"focus",targetFocus),
                read(adaptive,"depth",targetDepth),read(adaptive,"energy",targetEnergy),
                read(adaptive,"warmth",targetWarmth),read(adaptive,"contrast",targetContrast),
                read(adaptive,"detail",targetDetail),read(adaptive,"text_scale",targetTextScale),weightsVersion);
        boolean changed=!source.equals(next)||total!=ordinal;
        source=next;total=ordinal;number=ordinal>0?Integer.toString(ordinal):"";
        count=0;
        if(!next.isEmpty()) words[count++]=shortLabel(next);
        if(matches!=null && ordinal>0) for(int i=0;i<matches.length && count<NODES;i++) {
            String candidate=matches[i];
            if(candidate==null||candidate.length()>64||candidate.equalsIgnoreCase(next)) continue;
            LetterValues.Result exact=LetterValues.analyse(candidate);
            if(sourceValue==null || exact==null || exact.total!=ordinal || !exact.scheme.equals(sourceValue.scheme)) continue;
            boolean duplicate=false;
            for(int j=0;j<count;j++) if(words[j].equalsIgnoreCase(candidate)) { duplicate=true;break; }
            if(!duplicate) words[count++]=shortLabel(candidate);
        }
        for(int i=count;i<NODES;i++) words[i]=null;
        glyphCount=0;
        for(int i=0;i<source.length() && glyphCount<GLYPHS;) {
            int cp=source.codePointAt(i),end=i+Character.charCount(cp);
            if(Character.isLetter(cp)) glyphs[glyphCount++]=source.substring(i,end);
            i=end;
        }
        for(int i=glyphCount;i<GLYPHS;i++) glyphs[i]=null;
        caption=count>1?"SAME VALUE · DIFFERENT WORDS":ordinal>0?"YOUR WORD IN LETTER VALUES":"YOUR WORD IN MOTION";
        if(changed) { phase+=.12f;burst(.5f,.5f,12); }
    }

    /** Allocation-free latest targets, including lessons received while the ink is open. */
    void controls(float nextCalm,float nextFocus,float nextDepth,float nextEnergy,float nextWarmth,
                  float nextContrast,float nextDetail,float nextTextScale,long version) {
        nextCalm=unit(nextCalm,targetCalm);nextFocus=unit(nextFocus,targetFocus);
        nextDepth=unit(nextDepth,targetDepth);nextEnergy=unit(nextEnergy,targetEnergy);
        nextWarmth=unit(nextWarmth,targetWarmth);nextContrast=unit(nextContrast,targetContrast);
        nextDetail=unit(nextDetail,targetDetail);nextTextScale=unit(nextTextScale,targetTextScale);
        if(nextCalm!=targetCalm || nextFocus!=targetFocus || nextDepth!=targetDepth
                || nextEnergy!=targetEnergy || nextWarmth!=targetWarmth || nextContrast!=targetContrast
                || nextDetail!=targetDetail || nextTextScale!=targetTextScale) {
            if(controlChanges<Integer.MAX_VALUE) controlChanges++;
        }
        targetCalm=nextCalm;targetFocus=nextFocus;targetDepth=nextDepth;targetEnergy=nextEnergy;
        targetWarmth=nextWarmth;targetContrast=nextContrast;targetDetail=nextDetail;targetTextScale=nextTextScale;
        weightsVersion=Math.max(0,version);
        if(!enabled) easeControls(1f);
    }

    private void easeControls(float amount) {
        calm+=(targetCalm-calm)*amount;focus+=(targetFocus-focus)*amount;
        depth+=(targetDepth-depth)*amount;energy+=(targetEnergy-energy)*amount;
        warmth+=(targetWarmth-warmth)*amount;contrast+=(targetContrast-contrast)*amount;
        detail+=(targetDetail-detail)*amount;textScale+=(targetTextScale-textScale)*amount;
        teal=blend(0xff68dcd5,0xffb4eed0,warmth*.5f);
        gold=blend(0xffb4d6e2,0xfff1bf7e,warmth);
    }

    void active(boolean value) {
        if(enabled==value) return;
        enabled=value;lastFrame=0;
        if(value) burst(.5f,.5f,16);
        else attraction=0;
    }
    boolean active() { return enabled; }

    /** Coordinates are in the same canvas as draw. A down begins a fresh dust stroke. */
    void touch(float touchX,float touchY,boolean down) {
        if(!enabled||width<=0||height<=0) return;
        attractX=clamp((touchX-left)/width,.06f,.94f);
        attractY=clamp((touchY-top)/height,.08f,.92f);
        attraction=1;
        if(down) { touches++;burst(attractX,attractY,16); }
        else burst(attractX,attractY,2);
    }

    void draw(Canvas canvas,float sceneLeft,float sceneTop,float sceneWidth,float sceneHeight,long now) {
        if(!enabled||sceneWidth<dp(80)||sceneHeight<dp(100)) return;
        long started=System.nanoTime();
        left=sceneLeft;top=sceneTop;width=sceneWidth;height=sceneHeight;
        float dt=lastFrame==0?.033f:clamp((now-lastFrame)*.001f,.001f,.05f);
        lastFrame=now;draws++;
        easeControls(Math.min(1f,dt*4f));
        phase+=dt*orbitRate();
        attraction=Math.max(0,attraction-dt*(.28f+calm*.55f));
        float polarity=focus*2f-1f;
        float cx=.5f+(attractX-.5f)*attraction*polarity*.48f;
        float cy=.49f+(attractY-.5f)*attraction*polarity*.40f;
        float radius=Math.min(width*(.22f+depth*.17f),height*(.19f+depth*.15f));
        float rx=radius/width,ry=radius/height;
        float pull=attraction*polarity*(.18f+energy*.22f);
        float swirl=attraction*depth*(.12f+energy*.32f);
        float damping=Math.max(0,1-dt*(2.2f+calm*4.5f));
        float spring=5f+focus*7f;
        for(int i=0;i<count;i++) {
            float branch=1f-(i&1)*depth*1.7f;
            float a=phase*branch+i*6.2831853f/Math.max(1,count);
            float pulse=1+(float)Math.sin(phase*1.7f+i*1.3f)*(.06f+depth*.16f);
            float targetX=cx+(float)Math.cos(a)*rx*pulse;
            float targetY=cy+(float)Math.sin(a)*ry*pulse;
            float dx=targetX-x[i],dy=targetY-y[i];
            float tx=attractX-x[i],ty=attractY-y[i];
            float inverse=(float)(1d/Math.sqrt(tx*tx+ty*ty+.015f));
            tx*=inverse;ty*=inverse;
            vx[i]=clamp((vx[i]+(dx*spring+tx*pull-ty*swirl)*dt)*damping,-.6f,.6f);
            vy[i]=clamp((vy[i]+(dy*spring+ty*pull+tx*swirl)*dt)*damping,-.6f,.6f);
            x[i]=clamp(x[i]+vx[i]*dt,.10f,.90f);
            y[i]=clamp(y[i]+vy[i]*dt,.15f,.84f);
        }
        int saved=canvas.save();canvas.clipRect(left,top,left+width,top+height);
        // Thin links remain underneath readable words; no raster buffers or generated shaders.
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(.7f));paint.setColor(teal);
        for(int i=0;i<count;i++) for(int j=i+1;j<count;j++) {
            float dx=x[i]-x[j],dy=y[i]-y[j];
            float separation=dx*dx+dy*dy;
            paint.setAlpha((int)(18+(1-Math.min(1,separation*2))*32));
            canvas.drawLine(sx(x[i]),sy(y[i]),sx(x[j]),sy(y[j]),paint);
        }
        float centerX=sx(cx),centerY=sy(cy);
        paint.setColor(gold);paint.setAlpha(43);paint.setStrokeWidth(dp(.8f));
        canvas.drawCircle(centerX,centerY,radius*.59f,paint);
        paint.setColor(teal);paint.setAlpha(24);
        canvas.drawOval(centerX-radius,centerY-radius*.74f,centerX+radius,centerY+radius*.74f,paint);
        // A single restrained arc gives the slow orbit a direction.
        orbit.reset();
        for(int k=0;k<=24;k++) {
            float a=phase*1.3f+k*.043f;
            float ox=centerX+(float)Math.cos(a)*radius*.59f,oy=centerY+(float)Math.sin(a)*radius*.59f;
            if(k==0) orbit.moveTo(ox,oy);else orbit.lineTo(ox,oy);
        }
        paint.setColor(gold);paint.setAlpha(160);paint.setStrokeWidth(dp(1.1f));canvas.drawPath(orbit,paint);
        paint.setStyle(Paint.Style.FILL);paint.setTypeface(figures);
        paint.setTextSize(dp(39+textScale*9));paint.setColor(gold);paint.setAlpha(215);
        canvas.drawText(number,centerX,centerY+dp(12),paint);
        paint.setTextSize(dp(8));paint.setColor(teal);paint.setAlpha(155);
        canvas.drawText(caption,centerX,top+height-dp(16),paint);
        paint.setTypeface(serif);paint.setTextSize(dp(16+textScale*3));
        for(int i=0;i<count;i++) {
            float nx=sx(x[i]),ny=sy(y[i]);
            // A low-opacity dark stroke separates labels from the living texture underneath.
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(3));paint.setColor(0xff102322);paint.setAlpha(190);
            canvas.drawText(words[i],nx,ny,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(i==0?gold:teal);paint.setAlpha(225);
            canvas.drawText(words[i],nx,ny,paint);
            paint.setAlpha(110);canvas.drawCircle(nx,ny+dp(7),dp(1.5f),paint);
        }
        paint.setTypeface(figures);paint.setTextSize(dp(11+textScale*3));paint.setColor(gold);
        int visible=(int)Math.min(glyphCount,8+detail*16);
        for(int i=0;i<visible;i++) {
            float a=-phase*.72f+i*6.2831853f/Math.max(1,visible);
            float rr=radius*(1.19f+(float)Math.sin(i+phase)*.06f);
            paint.setAlpha(100+(int)(contrast*70));
            canvas.drawText(glyphs[i],centerX+(float)Math.cos(a)*rr,centerY+(float)Math.sin(a)*rr*.85f+dp(4),paint);
        }
        for(int i=0;i<DUST;i++) if(life[i]>0) {
            life[i]=Math.max(0,life[i]-dt*(.25f+calm*.48f));
            float tx=attractX-px[i],ty=attractY-py[i];
            float inverse=(float)(1d/Math.sqrt(tx*tx+ty*ty+.015f));
            tx*=inverse;ty*=inverse;
            float drag=Math.max(0,1-dt*(.4f+calm*1.8f));
            pvx[i]=clamp((pvx[i]+(tx*pull-ty*swirl)*dt*.65f)*drag,-.6f,.6f);
            pvy[i]=clamp((pvy[i]+(ty*pull+tx*swirl)*dt*.65f)*drag,-.6f,.6f);
            px[i]=clamp(px[i]+pvx[i]*dt,-.1f,1.1f);py[i]=clamp(py[i]+pvy[i]*dt,-.1f,1.1f);
            paint.setColor((i&1)==0?teal:gold);paint.setAlpha((int)(life[i]*125));
            canvas.drawCircle(sx(px[i]),sy(py[i]),dp(.8f+life[i]*1.2f),paint);
        }
        if(attraction>0) {
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(.8f));paint.setColor(teal);paint.setAlpha((int)(attraction*90));
            canvas.drawCircle(sx(attractX),sy(attractY),dp(12+(1-attraction)*20),paint);
        }
        paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);canvas.restoreToCount(saved);
        lastDrawNanos=System.nanoTime()-started;maxDrawNanos=Math.max(maxDrawNanos,lastDrawNanos);
    }

    Map<String,Object> state() {
        Map<String,Object> out=new LinkedHashMap<String,Object>();
        out.put("active",enabled);out.put("word",source);out.put("value",total);
        out.put("nodes",count);out.put("glyphs",glyphCount);out.put("particle_capacity",DUST);
        out.put("draws",draws);out.put("touches",touches);out.put("kind","native-ink-constellation");
        out.put("draw_ns",lastDrawNanos);out.put("max_draw_ns",maxDrawNanos);
        out.put("control_source","shared-online-preference-adapter");out.put("weights_version",weightsVersion);
        out.put("control_changes",controlChanges);out.put("target_controls",controlState(true));
        out.put("effective_controls",controlState(false));out.put("touch_polarity",focus*2f-1f);
        out.put("orbit_rate",orbitRate());out.put("swirl_strength",depth*(.12f+energy*.32f));
        return out;
    }
    private Map<String,Object> controlState(boolean target) {
        Map<String,Object> out=new LinkedHashMap<String,Object>();
        out.put("calm",target?targetCalm:calm);out.put("focus",target?targetFocus:focus);
        out.put("depth",target?targetDepth:depth);out.put("energy",target?targetEnergy:energy);
        out.put("warmth",target?targetWarmth:warmth);out.put("contrast",target?targetContrast:contrast);
        out.put("detail",target?targetDetail:detail);out.put("text_scale",target?targetTextScale:textScale);
        return out;
    }
    private float orbitRate() { return (.10f+energy*.5f)*(1f-calm*.55f); }
    private void burst(float atX,float atY,int amount) {
        for(int i=0;i<amount;i++) {
            int n=cursor++%DUST;float angle=random()*6.2831853f,speed=.025f+random()*.14f;
            px[n]=atX;py[n]=atY;pvx[n]=(float)Math.cos(angle)*speed;pvy[n]=(float)Math.sin(angle)*speed;
            life[n]=.48f+random()*.52f;
        }
    }
    private float random() { seed^=seed<<13;seed^=seed>>>17;seed^=seed<<5;return (seed&0x7fffffff)/2147483647f; }
    private float sx(float normal) { return left+normal*width; }
    private float sy(float normal) { return top+normal*height; }
    private float dp(float value) { return value*density; }
    private static String shortLabel(String value) { return value.length()>18?value.substring(0,17)+"…":value; }
    private static float read(Map<String,Object> map,String axis,float fallback) {
        Object value=map.get(axis);
        if(!(value instanceof Number)) return fallback;
        float result=((Number)value).floatValue();
        return Float.isNaN(result)||Float.isInfinite(result)?fallback:clamp(result,0,1);
    }
    private static float unit(float value,float fallback) {
        return Float.isNaN(value)||Float.isInfinite(value)?fallback:clamp(value,0,1);
    }
    private static float clamp(float value,float low,float high) { return Math.max(low,Math.min(high,value)); }
    private static int blend(int a,int b,float f) {
        int r=(int)(((a>>16)&255)*(1-f)+((b>>16)&255)*f);
        int g=(int)(((a>>8)&255)*(1-f)+((b>>8)&255)*f);
        int bl=(int)((a&255)*(1-f)+(b&255)*f);
        return 0xff000000|(r<<16)|(g<<8)|bl;
    }
}
