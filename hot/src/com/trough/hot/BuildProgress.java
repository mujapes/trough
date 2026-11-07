package com.trough.hot;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native live work surface: actual output, file diffs and check results come before the phase
 * timeline. update() accepts a bounded status snapshot from any thread. It never manufactures
 * measurements, test results or file changes. Only an explicit progress value displays a bar.
 * The elapsed clock runs only while work is active and this view is attached and visible.
 */
final class BuildProgress extends LinearLayout implements AutoCloseable {
    private static final int FG=0xffe8efe6, DIM=0xff91a79a, BG=0xff151d19, INSET=0xff0c120f,
        LINE=0xff2b3a31, BLUE=0xff9bc6ef, GREEN=0xffa3d5aa, RED=0xffffa29b, AMBER=0xffe8bf7d;
    private static final String[] PHASES={"root","payloads","seed","self_build","self_native","enter","done"};
    private static final String[] LABELS={"Runtime","Codex and tools","Build sources","Java build","Native build","Reload","Ready"};
    private final Handler main=new Handler(Looper.getMainLooper());
    private final TextView title, elapsed, current, disclosure;
    private final LinearLayout detail, tabs, content, failure;
    private final ProgressBar meter;
    private final BuildArtwork artwork;
    private final ScrollView viewport;
    private final TextView[] tab=new TextView[4];
    private Map<String,Object> latest, shown=new LinkedHashMap<>();
    private volatile boolean disposed;
    private boolean queued, expanded=false, active, ticking;
    private long elapsedBase, elapsedAnchor;
    private int selected;
    private String bodyKey="";
    private final Runnable tick=new Runnable() { public void run() {
        ticking=false;
        if(canTick()) { paintClock(); scheduleClock(); }
    }};
    private final Runnable apply=new Runnable() { public void run() {
        Map<String,Object> status;
        synchronized(BuildProgress.this) { queued=false; status=latest; latest=null; }
        if(!disposed && status!=null) render(status);
    }};

    static BuildProgress create(Context context) { return new BuildProgress(context); }

    private BuildProgress(Context context) {
        super(context);
        setOrientation(VERTICAL); setPadding(dp(12),dp(10),dp(12),dp(10));
        setBackground(box(BG,12,LINE));
        LinearLayout heading=new LinearLayout(context); heading.setOrientation(HORIZONTAL); heading.setGravity(Gravity.CENTER_VERTICAL);
        title=text(GREEN,13,false); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); title.setText("Work in Trough");
        heading.addView(title,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        elapsed=text(DIM,11,true); heading.addView(elapsed);
        disclosure=text(DIM,15,false); disclosure.setPadding(dp(10),0,0,0); disclosure.setText("⌄"); heading.addView(disclosure);
        addView(heading);
        current=text(FG,12,false); current.setMaxLines(2); current.setPadding(0,dp(5),0,dp(2));
        current.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); addView(current);
        View.OnClickListener collapse=new View.OnClickListener() { public void onClick(View view) {
            expanded=!expanded; detail.setVisibility(expanded?VISIBLE:GONE); disclosure.setText(expanded?"⌃":"⌄");
            view.setContentDescription(expanded?"Collapse work details":"Expand work details");
        }};
        heading.setOnClickListener(collapse); heading.setFocusable(true); heading.setContentDescription("Collapse work details");
        artwork=new BuildArtwork(context);
        LayoutParams artworkParams=new LayoutParams(LayoutParams.MATCH_PARENT,dp(176)); artworkParams.topMargin=dp(8);
        addView(artwork,artworkParams);
        artwork.setOnClickListener(collapse); artwork.setContentDescription("Artwork of the current work. Tap to expand source details.");
        meter=new ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal);
        meter.setMax(1000); meter.setProgressTintList(ColorStateList.valueOf(GREEN));
        meter.setProgressBackgroundTintList(ColorStateList.valueOf(LINE)); meter.setVisibility(GONE);
        LayoutParams meterParams=new LayoutParams(LayoutParams.MATCH_PARENT,dp(3)); meterParams.topMargin=dp(7); addView(meter,meterParams);
        detail=new LinearLayout(context); detail.setOrientation(VERTICAL); detail.setVisibility(GONE); addView(detail);
        failure=new LinearLayout(context); failure.setOrientation(VERTICAL); detail.addView(failure);
        tabs=new LinearLayout(context); tabs.setOrientation(HORIZONTAL); tabs.setPadding(0,dp(8),0,dp(5)); detail.addView(tabs);
        for(int i=0;i<tab.length;i++) {
            final int index=i;
            tab[i]=text(DIM,11,false); tab[i].setGravity(Gravity.CENTER); tab[i].setPadding(dp(3),dp(7),dp(3),dp(7));
            tab[i].setOnClickListener(new View.OnClickListener() { public void onClick(View view) {
                selected=index; bodyKey=""; paintBody();
            }});
            tabs.addView(tab[i],new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        }
        viewport=new ScrollView(context) {
            @Override protected void onMeasure(int width,int height) {
                int cap=dp(190);
                int size=MeasureSpec.getSize(height), mode=MeasureSpec.getMode(height);
                if(mode!=MeasureSpec.UNSPECIFIED) cap=Math.min(cap,size);
                super.onMeasure(width,MeasureSpec.makeMeasureSpec(cap,MeasureSpec.AT_MOST));
            }
        };
        viewport.setFillViewport(false); viewport.setNestedScrollingEnabled(true);
        content=new LinearLayout(context); content.setOrientation(VERTICAL); content.setPadding(dp(8),dp(7),dp(8),dp(7));
        content.setBackground(box(INSET,8,0)); viewport.addView(content); detail.addView(viewport);
        setVisibility(GONE);
    }

    /**
     * title/stage/state/message; optional progress 0..1, elapsed_ms or started_ms;
     * log/code strings, logs:[{text,kind}], files:[{path,state,summary,diff}],
     * checks:[{name,state,detail}], steps:[{id,label,state,detail,elapsed_ms}], error,next_action.
     * States: running, waiting, complete/done/passed, failed/error, pending.
     */
    void update(Map<String,Object> status) {
        if(status==null || disposed) return;
        Map<String,Object> copy=bounded(status);
        synchronized(this) {
            latest=copy;
            if(!queued) { queued=true; main.post(apply); }
        }
    }

    private void render(Map<String,Object> status) {
        String state=state(str(status,"state",32)), stage=str(status,"stage",80);
        boolean nextActive="running".equals(state) || "waiting".equals(state);
        if(status.get("elapsed_ms") instanceof Number) {
            elapsedBase=Math.max(0,number(status,"elapsed_ms",0)); elapsedAnchor=SystemClock.elapsedRealtime();
        } else if(status.get("started_ms") instanceof Number) {
            elapsedBase=Math.max(0,System.currentTimeMillis()-number(status,"started_ms",System.currentTimeMillis()));
            elapsedAnchor=SystemClock.elapsedRealtime();
        } else if(!active && nextActive) { elapsedBase=0; elapsedAnchor=SystemClock.elapsedRealtime(); }
        else if(active && !nextActive) { elapsedBase=elapsedNow(); elapsedAnchor=SystemClock.elapsedRealtime(); }
        shown=status; active=nextActive;
        artwork.update(status);
        title.setText(nonempty(str(status,"title",100),"Work in Trough")); title.setTextColor(color(state));
        String message=nonempty(str(status,"message",500),nonempty(str(status,"detail",500),phaseLabel(stage)));
        current.setText(message); current.setVisibility(message.isEmpty()?GONE:VISIBLE);
        Object progress=status.get("progress");
        boolean measured=progress instanceof Number && ((Number)progress).doubleValue()>=0 && ((Number)progress).doubleValue()<=1;
        meter.setVisibility(measured?VISIBLE:GONE);
        if(measured) meter.setProgress((int)(((Number)progress).doubleValue()*1000));
        failure.removeAllViews();
        String error=str(status,"error",700), next=str(status,"next_action",450);
        if(!error.isEmpty()) {
            failure.setPadding(dp(8),dp(7),dp(8),dp(7)); failure.setBackground(box(0xff331d1b,8,0xff694039));
            TextView problem=text(RED,12,false); problem.setText(error); problem.setTextIsSelectable(true); failure.addView(problem);
            if(!next.isEmpty()) { TextView action=text(FG,12,false); action.setPadding(0,dp(5),0,0); action.setText("Next: "+next); failure.addView(action); }
        } else { failure.setPadding(0,0,0,0); failure.setBackgroundColor(0x00000000); }
        paintBody(); paintClock(); setVisibility(VISIBLE); scheduleClock();
    }

    private void paintBody() {
        int files=list(shown,"files").size(), checks=list(shown,"checks").size();
        String[] labels={"Output", "Changes"+(files>0?" · "+files:""), "Checks"+(checks>0?" · "+checks:""), "Steps"};
        for(int i=0;i<tab.length;i++) {
            tab[i].setText(labels[i]); tab[i].setTextColor(i==selected?FG:DIM);
            tab[i].setTypeface(Typeface.DEFAULT,i==selected?Typeface.BOLD:Typeface.NORMAL);
            tab[i].setBackground(i==selected?box(LINE,6,0):null);
        }
        String key=selected+":"+Json.write(shown);
        // Clock-only updates and repeated snapshots preserve selection and scroll position.
        if(key.equals(bodyKey)) return;
        bodyKey=key;
        boolean follow=viewport.getScrollY()+viewport.getHeight()>=content.getHeight()-dp(20);
        final int previous=viewport.getScrollY(); content.removeAllViews();
        if(selected==0) output(); else if(selected==1) files(); else if(selected==2) checks(); else steps();
        if(selected==0 && follow) viewport.post(new Runnable() { public void run() { if(!disposed) viewport.fullScroll(View.FOCUS_DOWN); }});
        else viewport.post(new Runnable() { public void run() { if(!disposed) viewport.scrollTo(0,previous); }});
    }

    private void output() {
        boolean any=false;
        String code=str(shown,"code",4000), log=str(shown,"log",6000);
        List<Object> logs=list(shown,"logs");
        if(!code.isEmpty()) { section("CODE",BLUE); snippet(code,FG,32); any=true; }
        if(!log.isEmpty()) { section("LIVE OUTPUT",DIM); snippet(tail(log,6000,45),FG,45); any=true; }
        for(Object row:logs) {
            Map<String,Object> item=Json.obj(row); if(item==null) continue;
            String text=str(item,"text",1500), kind=str(item,"kind",32);
            if(!text.isEmpty()) { snippet(text,"error".equals(kind)?RED:"command".equals(kind)?BLUE:FG,12); any=true; }
        }
        if(!any) {
            String detail=nonempty(str(shown,"detail",2000),str(shown,"message",1000));
            if(!detail.isEmpty()) snippet(detail,FG,15);
            else empty("Waiting for output from the current step.");
        }
    }

    private void files() {
        List<Object> rows=list(shown,"files");
        if(rows.isEmpty()) { empty("File changes will appear here when reported."); return; }
        for(Object value:rows) {
            Map<String,Object> item=Json.obj(value); if(item==null) continue;
            cardTitle(nonempty(str(item,"path",300),"File"),str(item,"state",32));
            String summary=str(item,"summary",500); if(!summary.isEmpty()) paragraph(summary,DIM,12);
            String diff=str(item,"diff",2400);
            if(!diff.isEmpty()) {
                String[] lines=tail(diff,2400,32).split("\n",-1);
                for(String line:lines) snippet(line,line.startsWith("+")?GREEN:line.startsWith("-")?RED:line.startsWith("@@")?BLUE:DIM,2);
            }
            gap();
        }
    }

    private void checks() {
        List<Object> rows=list(shown,"checks");
        if(rows.isEmpty()) { empty("Verification results will appear here as checks finish."); return; }
        for(Object value:rows) {
            Map<String,Object> item=Json.obj(value); if(item==null) continue;
            String status=str(item,"state",32);
            if(status.isEmpty() && item.get("ok") instanceof Boolean) status=Boolean.TRUE.equals(item.get("ok"))?"passed":"failed";
            cardTitle(nonempty(str(item,"name",250),"Check"),status);
            String detail=str(item,"detail",1500); if(!detail.isEmpty()) snippet(detail,DIM,14);
            gap();
        }
    }

    private void steps() {
        List<Object> rows=list(shown,"steps");
        if(rows.isEmpty()) {
            String phase=str(shown,"stage",80); int current=-1;
            for(int i=0;i<PHASES.length;i++) if(PHASES[i].equals(phase)) current=i;
            if(current<0) { empty("No phase timeline has been reported."); return; }
            // A phase identifies only the current stage. Earlier phases are not labelled passed.
            for(int i=0;i<PHASES.length;i++) cardTitle(LABELS[i],i==current?state(str(shown,"state",32)):"pending");
        } else for(Object value:rows) {
            Map<String,Object> item=Json.obj(value); if(item==null) continue;
            cardTitle(nonempty(str(item,"label",200),nonempty(str(item,"id",80),"Step")),str(item,"state",32));
            String detail=str(item,"detail",650); if(!detail.isEmpty()) paragraph(detail,DIM,11);
            gap();
        }
    }

    private void cardTitle(String label,String status) {
        LinearLayout row=new LinearLayout(getContext()); row.setOrientation(HORIZONTAL); row.setGravity(Gravity.TOP);
        TextView name=text(FG,12,false); name.setText(label); name.setTypeface(Typeface.DEFAULT,Typeface.BOLD); name.setMaxLines(2);
        row.addView(name,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        if(!status.isEmpty()) {
            TextView badge=text(color(state(status)),10,false); badge.setPadding(dp(7),dp(2),0,0); badge.setText(displayState(status)); row.addView(badge);
        }
        content.addView(row);
    }
    private void section(String label,int color) { TextView view=text(color,10,false); view.setLetterSpacing(.08f); view.setText(label); view.setPadding(0,dp(2),0,dp(4)); content.addView(view); }
    private void snippet(String value,int color,int lines) {
        TextView view=text(color,11,true); view.setText(value); view.setTextIsSelectable(true); view.setMaxLines(lines);
        view.setLineSpacing(dp(2),1); view.setHorizontallyScrolling(false); content.addView(view);
    }
    private void paragraph(String value,int color,int size) { TextView view=text(color,size,false); view.setText(value); view.setPadding(0,dp(3),0,0); view.setTextIsSelectable(true); content.addView(view); }
    private void empty(String message) { paragraph(message,DIM,12); }
    private void gap() { View gap=new View(getContext()); content.addView(gap,new LayoutParams(1,dp(10))); }
    private TextView text(int color,int sp,boolean mono) {
        TextView view=new TextView(getContext()); view.setTextColor(color); view.setTextSize(TypedValue.COMPLEX_UNIT_SP,sp);
        view.setIncludeFontPadding(false); if(mono) view.setTypeface(Typeface.MONOSPACE); return view;
    }
    private GradientDrawable box(int color,int radius,int stroke) {
        GradientDrawable background=new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(radius));
        if(stroke!=0) background.setStroke(dp(1),stroke); return background;
    }
    private int dp(int value) { return (int)(value*getResources().getDisplayMetrics().density+.5f); }
    private static String state(String value) {
        if(value.isEmpty()) return "running";
        if("done".equals(value)||"passed".equals(value)||"completed".equals(value)||"success".equals(value)) return "complete";
        if("error".equals(value)||"failure".equals(value)) return "failed";
        if("working".equals(value)||"in_progress".equals(value)) return "running";
        return value;
    }
    private static int color(String state) { return "failed".equals(state)?RED:"complete".equals(state)?GREEN:"waiting".equals(state)?AMBER:"running".equals(state)?BLUE:DIM; }
    private static String displayState(String value) { String normalized=state(value); return "complete".equals(normalized)?"Passed":"running".equals(normalized)?"Running":"waiting".equals(normalized)?"Waiting":"failed".equals(normalized)?"Failed":"pending".equals(normalized)?"Pending":value; }
    private static String phaseLabel(String stage) { for(int i=0;i<PHASES.length;i++) if(PHASES[i].equals(stage)) return LABELS[i]; return stage; }
    private static String nonempty(String value,String fallback) { return value.isEmpty()?fallback:value; }
    private static String str(Map<String,Object> source,String key,int limit) { String value=Json.str(source,key); return value==null?"":value.length()>limit?value.substring(0,limit)+"…":value; }
    private static long number(Map<String,Object> source,String key,long fallback) { Object n=source.get(key); return n instanceof Number?((Number)n).longValue():fallback; }
    private static List<Object> list(Map<String,Object> source,String key) { List<Object> values=Json.arr(source.get(key)); return values==null?new ArrayList<Object>():values; }
    private static String tail(String text,int chars,int lines) {
        boolean clipped=text.length()>chars; String result=clipped?text.substring(text.length()-chars):text;
        int count=0;
        for(int at=result.length()-1;at>=0;at--) if(result.charAt(at)=='\n' && ++count>lines) { result=result.substring(at+1); clipped=true; break; }
        return clipped?"… earlier output omitted\n"+result:result;
    }
    private static Map<String,Object> bounded(Map<String,Object> source) {
        Map<String,Object> copy=new LinkedHashMap<>();
        for(String key:new String[]{"title","stage","state","message","detail","error","next_action","code","log"}) {
            String value=Json.str(source,key); if(value!=null) copy.put(key,"log".equals(key)?tail(value,6000,45):str(source,key,"code".equals(key)?4000:2000));
        }
        for(String key:new String[]{"elapsed_ms","started_ms","progress"}) if(source.get(key) instanceof Number) copy.put(key,source.get(key));
        for(String key:new String[]{"logs","files","checks","steps"}) {
            List<Object> original=list(source,key), rows=new ArrayList<>();
            int limit="logs".equals(key)?12:8;
            for(int i=Math.max(0,original.size()-limit);i<original.size();i++) {
                Map<String,Object> item=Json.obj(original.get(i)); if(item==null) continue;
                Map<String,Object> row=new LinkedHashMap<>();
                for(String field:new String[]{"id","label","name","path","state","summary","detail","diff","text","kind"}) {
                    if(item.get(field) instanceof String) row.put(field,str(item,field,"diff".equals(field)?2400:1500));
                }
                if(item.get("ok") instanceof Boolean) row.put("ok",item.get("ok"));
                if(item.get("elapsed_ms") instanceof Number) row.put("elapsed_ms",item.get("elapsed_ms"));
                rows.add(row);
            }
            if(!rows.isEmpty()) copy.put(key,rows);
        }
        return copy;
    }

    private long elapsedNow() { return elapsedBase+(active?Math.max(0,SystemClock.elapsedRealtime()-elapsedAnchor):0); }
    private void paintClock() {
        long seconds=elapsedNow()/1000;
        elapsed.setText((active?"Live · ":"")+(seconds/60)+":"+(seconds%60<10?"0":"")+(seconds%60));
    }
    private boolean canTick() { return !disposed && active && isAttachedToWindow() && getWindowVisibility()==VISIBLE && isShown(); }
    private void scheduleClock() {
        main.removeCallbacks(tick); ticking=false;
        if(canTick()) { ticking=true; main.postDelayed(tick,1000); }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if(main!=null) scheduleClock(); }
    @Override protected void onDetachedFromWindow() { if(main!=null) { main.removeCallbacks(tick); ticking=false; } super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View changed,int visibility) { super.onVisibilityChanged(changed,visibility); if(main!=null) scheduleClock(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); if(main!=null) scheduleClock(); }
    Map<String,Object> state() { return Json.map("active",active,"expanded",expanded,"tab",selected,"files",list(shown,"files").size(),"checks",list(shown,"checks").size(),"clock_scheduled",ticking,"disposed",disposed,"artwork",artwork.state()); }
    void dispose() { close(); }
    @Override public void close() { disposed=true; main.removeCallbacks(tick); main.removeCallbacks(apply); artwork.close(); ticking=false; synchronized(this) { latest=null; queued=false; } }
}
