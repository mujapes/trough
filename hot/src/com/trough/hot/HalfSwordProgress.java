package com.trough.hot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.Button;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small, evidence-backed project snapshot. Does not own chat, media or game focus. */
final class HalfSwordProgress extends LinearLayout {
    private static final int SNAPSHOT=0x7f0d74b1, EXPANDED=0x7f0d74b2, MAX_BYTES=2097152;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final File source,diagnostic,workSource,dashboardRequest,cloudSource,historySource;
    private final TextView heading,current,outputLabel;
    private final ProgressBar estimate;
    private final ScrollView details;
    private final LinearLayout rows,outputRows;
    private final ScrollView outputScroll;
    private JSONObject snapshot;
    private String sha="",status="waiting",error="",workSha="",workError="";
    interface OutputRenderer {TextView render(String kind,String text);}
    private OutputRenderer outputRenderer;
    void setOutputRenderer(OutputRenderer renderer){outputRenderer=renderer;workSha="";}
    private long workUpdated;
    private int cloudRenderedCount;
    private boolean expanded,inFlight,closed,outputFollow=true;
    private volatile int generation;
    private long renderedAt;
    private final Runnable tick=new Runnable(){public void run(){
        if(!visible()||inFlight)return;
        boolean requested=dashboardRequest.isFile()&&dashboardRequest.delete();
        boolean restore=new File(dashboardRequest.getParentFile(),"half-sword-dashboard-visible").isFile()&&root.findViewWithTag("half-sword-compute-dashboard")==null;
        if(requested||restore)HalfSwordComputeDashboard.show(getContext(),root,dashboardRequest.getParentFile().getParentFile());
        inFlight=true;
        final int mine=generation;
        new Thread(new Runnable(){public void run(){
            JSONObject loaded=null;String hash="",failure="";
            try {byte[] bytes=readBounded(source);loaded=parse(bytes);hash=hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
            catch(Exception e){failure=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());}
            JSONObject workLoaded=null;String workDigest="",workFailure="";
            try{byte[] bytes=readBounded(workSource);workLoaded=mergeCloud(parseWork(bytes));workDigest=hex(MessageDigest.getInstance("SHA-256").digest(workLoaded.toString().getBytes("UTF-8")));}catch(Exception e){workFailure=e.getClass().getSimpleName();}
            final JSONObject workData=workLoaded;final String workHash=workDigest,workProblem=workFailure;
            final JSONObject data=loaded;final String digest=hash,problem=failure;
            main.post(new Runnable(){public void run(){
                if(mine!=generation||closed)return;
                inFlight=false;
                if(!visible())return;
                boolean changed=data!=null&&!digest.equals(sha);
                String previous=status;error=problem;status=data==null?(snapshot==null?"unavailable":"stale"):"current";
                if(data!=null){snapshot=data;sha=digest;root.setTag(SNAPSHOT,new String[]{data.toString(),digest});}
                workError=workProblem;
                if(workData!=null&&!workHash.equals(workSha)){renderWork(workData);workSha=workHash;}
                if(changed||!status.equals(previous))render();
                writeDiagnostic();
                main.removeCallbacks(tick);main.postDelayed(tick,5000);
            }});
        }},"half-sword-progress-read").start();
    }};

    HalfSwordProgress(Context c,FrameLayout root,File home){this(c,root,home,null);}
    HalfSwordProgress(Context c,FrameLayout root,File home,final Runnable openSimulator){
        super(c);this.root=root;
        dashboardRequest=new File(home,".trough/half-sword-dashboard-open");
        historySource=new File(home,".trough/half-sword-visible-history.json");
        cloudSource=new File(home,".trough/half-sword-cloud-feed.json");
        source=new File(home,"../work/trough-lab/half-sword/progress.json");
        workSource=new File(home,"../work/trough-lab/half-sword/work-output.json");
        diagnostic=new File(home,".trough/half-sword-progress-runtime.json");
        setOrientation(VERTICAL);setBackgroundColor(0xff15211d);setPadding(dp(10),dp(1),dp(10),dp(1));
        setTag("half-sword-progress");setFocusable(false);
        heading=text(0xffcae4d4,12);heading.setMaxLines(1);heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        current=text(0xffa9bfb2,11);current.setMaxLines(2);current.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout currentRow=new LinearLayout(c);currentRow.setOrientation(HORIZONTAL);
        currentRow.addView(current,new LayoutParams(0,-2,1));
        if(openSimulator!=null){Button open=new Button(c);open.setText("Simulator");open.setTextSize(11);open.setMinHeight(0);open.setMinimumHeight(0);open.setPadding(dp(8),0,dp(8),0);open.setContentDescription("Open the Half Sword simulator without changing the chat draft");open.setOnClickListener(new OnClickListener(){public void onClick(View view){if(!closed)openSimulator.run();}});currentRow.addView(open,new LayoutParams(-2,dp(32)));}
        LinearLayout dashboardRow=new LinearLayout(c);dashboardRow.setOrientation(HORIZONTAL);dashboardRow.setGravity(android.view.Gravity.CENTER_VERTICAL);dashboardRow.addView(heading,new LayoutParams(0,-2,1));
        Button dashboard=new Button(c);dashboard.setText("GPU dashboard");dashboard.setTextSize(10);dashboard.setMinHeight(0);dashboard.setMinimumHeight(0);dashboard.setPadding(dp(7),0,dp(7),0);dashboard.setOnClickListener(new OnClickListener(){public void onClick(View view){HalfSwordComputeDashboard.show(getContext(),HalfSwordProgress.this.root,home);}});dashboardRow.addView(dashboard,new LayoutParams(-2,dp(28)));addView(dashboardRow,new LayoutParams(-1,-2));addView(currentRow,new LayoutParams(-1,-2));
        estimate=new ProgressBar(c,null,android.R.attr.progressBarStyleHorizontal);estimate.setMax(100);estimate.setVisibility(GONE);addView(estimate,new LayoutParams(-1,dp(5)));
        rows=new LinearLayout(c);rows.setOrientation(VERTICAL);
        details=new ScrollView(c);details.setFillViewport(false);details.addView(rows,new ScrollView.LayoutParams(-1,-2));
        addView(details,new LayoutParams(-1,dp(172)));
        outputLabel=text(0xffcae4d4,11);outputLabel.setText("Half Sword · work updates");addView(outputLabel,new LayoutParams(-1,-2));
        outputRows=new LinearLayout(c);outputRows.setOrientation(VERTICAL);outputRows.setPadding(dp(14),dp(4),dp(14),dp(12));
        outputScroll=new ScrollView(c);outputScroll.setContentDescription("Half Sword work updates. Scroll independently from the conversation.");outputScroll.addView(outputRows,new ScrollView.LayoutParams(-1,-2));outputScroll.setOnTouchListener(new OnTouchListener(){public boolean onTouch(View v,android.view.MotionEvent e){int action=e.getActionMasked();if(action==android.view.MotionEvent.ACTION_DOWN)outputFollow=false;if(action==android.view.MotionEvent.ACTION_UP||action==android.view.MotionEvent.ACTION_CANCEL)outputFollow=!outputScroll.canScrollVertically(1);return false;}});outputScroll.setVisibility(GONE);addView(outputScroll,new LayoutParams(-1,dp(88)));
        // Restore compact: an expanded tracker can consume the chat viewport after reload.
        expanded=false;root.setTag(EXPANDED,Boolean.FALSE);
        Object saved=root.getTag(SNAPSHOT);
        if(saved instanceof String[]){String[] prior=(String[])saved;try{snapshot=parse(prior[0].getBytes("UTF-8"));sha=prior[1];status="stale";}catch(Exception ignored){}}
        OnClickListener toggle=new OnClickListener(){public void onClick(View v){expanded=!expanded;HalfSwordProgress.this.root.setTag(EXPANDED,Boolean.valueOf(expanded));render();post(new Runnable(){public void run(){writeDiagnostic();}});}};
        heading.setOnClickListener(toggle);current.setOnClickListener(toggle);
        addOnLayoutChangeListener(new OnLayoutChangeListener(){public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob){
            for(int i=0;i<HalfSwordProgress.this.root.getChildCount();i++){View panel=HalfSwordProgress.this.root.getChildAt(i);if("half-sword-expanded".equals(panel.getTag()))HalfSwordView.layoutPanel(HalfSwordProgress.this.root,panel);}
            writeDiagnostic();
        }});
        render();

    }

    ScrollView detachOutputViewport(){outputLabel.setVisibility(GONE);if(outputScroll.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)outputScroll.getParent()).removeView(outputScroll);return outputScroll;}
    void clearOutput(){outputFollow=true;outputRows.removeAllViews();outputScroll.setVisibility(GONE);}
    void addOutput(View row){if(row.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)row.getParent()).removeView(row);outputRows.addView(row);while(outputRows.getChildCount()>12)outputRows.removeViewAt(0);outputScroll.setVisibility(VISIBLE);followOutput();}
    void followOutput(){if(outputFollow)outputScroll.post(new Runnable(){public void run(){if(outputFollow)outputScroll.fullScroll(FOCUS_DOWN);}});}
    TextView outputAt(int tag,int index){for(int i=0;i<outputRows.getChildCount();i++){View v=outputRows.getChildAt(i);if(v instanceof TextView&&Integer.valueOf(index).equals(v.getTag(tag)))return (TextView)v;}return null;}
    private static JSONObject parseWork(byte[] bytes)throws Exception{
        JSONObject value=new JSONObject(new String(bytes,"UTF-8"));
        if(value.getInt("schema_version")!=1||!"half-sword".equals(value.getString("project"))||!("halfsword_live_worker".equals(value.getString("producer"))||"halfsword_direct_worker".equals(value.getString("producer"))))throw new IllegalArgumentException("Project identity");
        JSONArray entries=value.getJSONArray("entries");if(entries.length()>12)throw new IllegalArgumentException("Work entry count");
        for(int i=0;i<entries.length();i++){JSONObject row=entries.getJSONObject(i);require(row,"id",80);require(row,"text",12000);String kind=row.optString("kind","assistant");if(!("user".equals(kind)||"assistant".equals(kind)||"tool".equals(kind)||"result".equals(kind)||"error".equals(kind)))throw new IllegalArgumentException("Work kind");if(row.getLong("time_ms")<=0)throw new IllegalArgumentException("Work timestamp");}
        return value;
    }
    private JSONObject mergeCloud(JSONObject local)throws Exception{
        java.util.LinkedHashMap<String,JSONObject> byId=new java.util.LinkedHashMap<String,JSONObject>();
        if(historySource.isFile()){try{JSONArray history=new JSONObject(new String(readBounded(historySource),"UTF-8")).getJSONArray("entries");for(int i=0;i<history.length()&&i<172;i++){JSONObject row=history.getJSONObject(i);byId.put(row.getString("id"),row);}}catch(Exception ignored){}}
        JSONArray base=local.getJSONArray("entries");for(int i=0;i<base.length();i++){JSONObject row=base.getJSONObject(i);byId.put(row.getString("id"),row);}
        if(cloudSource.isFile()){
            JSONObject cloud=new JSONObject(new String(readBounded(cloudSource),"UTF-8"));JSONArray incoming="cloud-sol".equals(cloud.optString("source"))?cloud.optJSONArray("entries"):null;
            for(int i=0;incoming!=null&&i<Math.min(40,incoming.length());i++){JSONObject row=incoming.getJSONObject(i);String id=row.optString("id"),text=row.optString("text");if(!id.startsWith("cloud-sol-")||id.length()>180||text.length()==0||text.length()>6000)continue;byId.put(id,new JSONObject().put("id",id).put("kind","assistant").put("text","[Cloud Sol]\n"+text).put("time_ms",row.optLong("time_ms",1)));}
        }
        java.util.ArrayList<JSONObject> all=new java.util.ArrayList<JSONObject>(byId.values());
        java.util.Collections.sort(all,new java.util.Comparator<JSONObject>(){public int compare(JSONObject a,JSONObject b){int order=Long.compare(a.optLong("time_ms"),b.optLong("time_ms"));return order!=0?order:a.optString("id").compareTo(b.optString("id"));}});
        int conversation=0,diagnostics=0;java.util.ArrayList<JSONObject> kept=new java.util.ArrayList<JSONObject>();for(int i=all.size()-1;i>=0;i--){JSONObject row=all.get(i);String kind=row.optString("kind");if("user".equals(kind)||"assistant".equals(kind)){if(conversation++>=160)continue;}else if(diagnostics++>=12)continue;kept.add(0,row);}
        JSONArray merged=new JSONArray();for(JSONObject row:kept)merged.put(row);local.put("entries",merged);
        String saved=local.toString();if(saved.getBytes("UTF-8").length<MAX_BYTES){File temp=new File(historySource.getPath()+".tmp");Payloads.writeSmall(temp,saved);if(!temp.renameTo(historySource))temp.delete();}
        return local;
    }
    private void renderWork(JSONObject value){
        try{cloudRenderedCount=0;JSONArray entries=value.getJSONArray("entries");outputRows.removeAllViews();for(int i=0;i<entries.length();i++){JSONObject row=entries.getJSONObject(i);if(row.optString("id").startsWith("cloud-sol-"))cloudRenderedCount++;TextView v;if(outputRenderer!=null)v=outputRenderer.render(row.optString("kind","assistant"),row.getString("text"));else{v=text(0xffcae4d4,16);v.setText(RichText.render(row.getString("text")));v.setTextIsSelectable(true);v.setPadding(dp(8),dp(8),dp(8),dp(6));}outputRows.addView(v);workUpdated=row.getLong("time_ms");}outputScroll.setVisibility(entries.length()>0?VISIBLE:GONE);followOutput();}catch(Exception e){workError=e.getClass().getSimpleName();}
    }
    private static JSONObject parse(byte[] bytes)throws Exception{
        JSONObject value=new JSONObject(new String(bytes,"UTF-8"));
        if(value.getInt("schema_version")!=1)throw new IllegalArgumentException("Snapshot schema");
        require(value,"updated_at",80);require(value,"title",80);require(value,"summary",1000);require(value,"current",1200);require(value,"next",1200);
        JSONArray milestones=value.getJSONArray("milestones");if(milestones.length()<1||milestones.length()>24)throw new IllegalArgumentException("Milestone count");
        if(value.has("estimate_percent")){Object percent=value.get("estimate_percent");if(!(percent instanceof Integer)||((Integer)percent).intValue()<0||((Integer)percent).intValue()>100)throw new IllegalArgumentException("Estimate range");require(value,"estimate_label",200);require(value,"estimate_confidence",100);require(value,"estimate_basis",1800);require(value,"playtest_scope",1200);}
        for(int i=0;i<milestones.length();i++){JSONObject row=milestones.getJSONObject(i);require(row,"id",100);require(row,"label",180);require(row,"detail",1800);String s=require(row,"state",20);if(!"verified".equals(s)&&!"partial".equals(s)&&!"active".equals(s)&&!"planned".equals(s))throw new IllegalArgumentException("Milestone state");}
        return value;
    }
    private static String require(JSONObject value,String key,int limit)throws Exception{Object raw=value.get(key);if(!(raw instanceof String))throw new IllegalArgumentException(key);String text=(String)raw;if(text.trim().length()==0||text.length()>limit)throw new IllegalArgumentException(key);return text;}
    private static byte[] readBounded(File file)throws Exception{
        if(!file.isFile()||file.length()>MAX_BYTES)throw new IllegalArgumentException("Missing or oversized progress snapshot");
        try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[2048];for(int n;(n=in.read(buffer))!=-1;){if(out.size()+n>MAX_BYTES)throw new IllegalArgumentException("Progress snapshot limit");out.write(buffer,0,n);}return out.toByteArray();
        }
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes){int n=b&255;if(n<16)out.append('0');out.append(Integer.toHexString(n));}return out.toString();}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(int color,int size){TextView v=new TextView(getContext());v.setTextColor(color);v.setTextSize(size);v.setFocusable(false);return v;}
    private void render(){
        renderedAt=System.currentTimeMillis();current.setVisibility(expanded?VISIBLE:GONE);details.setVisibility(expanded?VISIBLE:GONE);rows.removeAllViews();
        if(snapshot==null){estimate.setVisibility(GONE);heading.setText("Half Sword · progress unavailable "+(expanded?"▴":"▾"));current.setText("Waiting for a verified project snapshot");return;}
        String stale="current".equals(status)?"":" · stale snapshot";
        boolean hasEstimate=snapshot.has("estimate_percent");
        String summary=snapshot.optString("summary");
        if(hasEstimate&&!summary.contains("%"))summary+=" · ≈"+snapshot.optInt("estimate_percent")+"% to "+snapshot.optString("estimate_label");
        heading.setText(expanded?snapshot.optString("title")+stale+" ▴  "+summary:snapshot.optString("title")+" ▾"+(hasEstimate?" · ≈"+snapshot.optInt("estimate_percent")+"% to first playtest":"")+stale);
        estimate.setVisibility(hasEstimate&&expanded?VISIBLE:GONE);if(hasEstimate){estimate.setProgress(snapshot.optInt("estimate_percent"));estimate.setContentDescription("Approximately "+snapshot.optInt("estimate_percent")+" percent to "+snapshot.optString("estimate_label")+". Engineering estimate, "+snapshot.optString("estimate_confidence")+" confidence.");}
        current.setText("Now: "+snapshot.optString("current"));
        heading.setContentDescription("Half Sword progress. "+(expanded?"Collapse":"Expand")+" milestone details. "+snapshot.optString("summary")+stale);
        addDetail("Next: "+snapshot.optString("next"),0xffc9decf);
        addDetail("Last verified snapshot: "+snapshot.optString("updated_at"),0xff91ab9c);
        if(hasEstimate){addDetail("Estimate scope: "+snapshot.optString("playtest_scope")+"\nConfidence: "+snapshot.optString("estimate_confidence")+"\n"+snapshot.optString("estimate_basis"),0xffd4bb95);}
        JSONArray milestones=snapshot.optJSONArray("milestones");
        for(int i=0;milestones!=null&&i<milestones.length();i++){JSONObject row=milestones.optJSONObject(i);String s=row.optString("state");int color="verified".equals(s)?0xffa4d9b0:"active".equals(s)?0xff9ac9f0:"partial".equals(s)?0xffe2c088:0xff9baaa4;String weight=row.has("weight")?" · weight "+row.optInt("weight")+"/100":"";addDetail(s.toUpperCase(java.util.Locale.ROOT)+" · "+row.optString("label")+weight+"\n"+row.optString("detail"),color);}
        JSONArray later=snapshot.optJSONArray("later");for(int i=0;later!=null&&i<Math.min(16,later.length());i++){JSONObject row=later.optJSONObject(i);if(row!=null)addDetail("LATER · "+row.optString("label"),0xff9baaa4);}
        String limits=snapshot.optString("limits");if(!limits.isEmpty())addDetail(limits,0xffd4bb95);
    }
    private void addDetail(String text,int color){TextView v=text(color,12);v.setText(text);v.setPadding(0,dp(5),0,dp(5));rows.addView(v,new LayoutParams(-1,-2));}
    private boolean visible(){return !closed&&isAttachedToWindow()&&getWindowVisibility()==VISIBLE&&isShown();}
    private void schedule(){if(main==null)return;main.removeCallbacks(tick);if(visible()&&!inFlight)main.post(tick);}
    protected void onAttachedToWindow(){super.onAttachedToWindow();generation++;inFlight=false;schedule();}
    protected void onDetachedFromWindow(){generation++;inFlight=false;main.removeCallbacks(tick);super.onDetachedFromWindow();}
    protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);schedule();}
    protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);schedule();}
    void close(){closed=true;generation++;main.removeCallbacks(tick);}
    private void writeDiagnostic(){
        if(closed||!isAttachedToWindow())return;
        try{
            int[] at=new int[2];getLocationInWindow(at);
            Object work=root.getTag(HalfSwordView.CHAT_STATUS);android.graphics.Rect workBounds=new android.graphics.Rect();
            boolean workVisible=work instanceof TextView&&((TextView)work).isShown()&&((TextView)work).getGlobalVisibleRect(workBounds)&&workBounds.height()>=((TextView)work).getHeight();
            boolean panelClears=true;int panelTop=-1;int[] panelAt=new int[2];
            for(int i=0;i<root.getChildCount();i++){View panel=root.getChildAt(i);if("half-sword-expanded".equals(panel.getTag())){panel.getLocationInWindow(panelAt);panelTop=panelAt[1];panelClears=panelTop>=at[1]+getHeight();}}
            final String json=new JSONObject().put("protocol",1).put("work_stream_renderer",outputRenderer==null?"fallback":"chat").put("work_stream_first_selectable",outputRows.getChildCount()>0&&outputRows.getChildAt(0) instanceof TextView&&((TextView)outputRows.getChildAt(0)).isTextSelectable()).put("work_stream_first_text_px",outputRows.getChildCount()>0&&outputRows.getChildAt(0) instanceof TextView?((TextView)outputRows.getChildAt(0)).getTextSize():0).put("work_stream_source","half-sword/work-output.json").put("work_stream_error",workError).put("work_stream_updated_ms",workUpdated).put("work_stream_rows",outputRows.getChildCount()).put("cloud_rendered_rows",cloudRenderedCount).put("work_stream_visible",outputScroll.isShown()&&outputScroll.getVisibility()==VISIBLE).put("work_stream_following",outputFollow).put("time_ms",System.currentTimeMillis()).put("rendered_at_ms",renderedAt).put("status",status).put("error",error).put("visible",isShown()&&getWindowVisibility()==VISIBLE).put("expanded",expanded).put("estimate_percent",snapshot==null||!snapshot.has("estimate_percent")?JSONObject.NULL:snapshot.optInt("estimate_percent")).put("estimate_label",snapshot==null?"":snapshot.optString("estimate_label")).put("working_status_visible",workVisible).put("expanded_panel_top",panelTop).put("panel_clears_tracker",panelClears).put("data_sha256",sha).put("updated_at",snapshot==null?"":snapshot.optString("updated_at")).put("current",snapshot==null?"":snapshot.optString("current")).put("milestone_count",snapshot==null?0:snapshot.optJSONArray("milestones").length()).put("row_bounds",new JSONArray(new int[]{at[0],at[1],at[0]+getWidth(),at[1]+getHeight()})).toString();
            final int mine=generation;
            new Thread(new Runnable(){public void run(){File temporary=new File(diagnostic.getPath()+".new-"+System.nanoTime());try{if(mine!=generation||closed)return;try(FileOutputStream out=new FileOutputStream(temporary)){out.write(json.getBytes("UTF-8"));}if(mine==generation&&!closed)temporary.renameTo(diagnostic);}catch(Exception ignored){}finally{temporary.delete();}}},"half-sword-progress-status").start();
        }catch(Exception ignored){}
    }
}
