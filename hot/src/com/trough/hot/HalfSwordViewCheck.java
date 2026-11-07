package com.trough.hot;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import java.io.*;
import java.nio.*;
import java.util.UUID;
import org.json.*;

/** Explicit private-file requested, bounded check of the actual expanded UI. */
final class HalfSwordViewCheck implements Runnable {
    final Activity activity;
    final HalfSwordView inline;
    final FrameLayout root;
    final ScrollView scroll;
    final int originalScroll, originalVisibility, originalCount;
    final ByteBuffer originalIds, originalTargets, originalComponents;
    final ByteBuffer originalPhysicsParents, originalPhysicsMap, originalPhysicsActors, originalPhysicsAlphas, originalPhysicsOldLocal, originalPhysicsComponentFrame;
    final int originalActorCount;
    final boolean originalExpanded, originalExpandedFlag;
    final Object originalShapesTag;
    final Object originalCompactTag;
    View testPanel;
    FrameLayout.LayoutParams originalPanelLayout;
    android.graphics.drawable.Drawable originalPanelBackground;
    boolean testPreviewCompact, chatAccessible, workingStatusVisible;
    String chatStatusText="";
    int chatStatusBottom;
    int panelLeft,panelTop,panelRight,panelBottom,rootWidth,rootHeight;
    final View.OnLayoutChangeListener compactLayoutListener=new View.OnLayoutChangeListener(){public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int oright,int ob){resizeTestPanel();}};
    final Handler handler = new Handler(Looper.getMainLooper());
    final JSONObject result = new JSONObject();
    final JSONArray events = new JSONArray();
    final String id;
    final File ticket;
    final boolean components, physics, rigid, composed, pipeline, character, ground;
    ByteBuffer referenceComponents, deformedComponents;
    ByteBuffer[][] physicsFrames, rigidFrames;
    ByteBuffer[] composedFrame;
    String composedBaselineHash, composedHash;
    boolean composedChecked;
    String rigidRestHash, rigidCurrentHash, rigidPredictedHash;String characterRestHash,characterFirstHash,characterSecondHash,characterPositiveHash,characterNegativeHash;
    float rigidComparisonYaw; boolean rigidComparisonShapes;
    String physicsRestHash, physicsReferenceHash, physicsDeformedHash;
    final long start = SystemClock.uptimeMillis();
    final boolean originalShapes;
    final float originalYaw;
    final Object originalPreference;
    final int originalMode;
    HalfSwordView view;
    int stage;
    long mark, stableAt, stableDraw, presentedMark;
    String referenceHash, targetHash;
    String componentRestHash, componentReferenceHash, componentDeformedHash;
    boolean done;

    static void maybeRun(Activity a, HalfSwordView inline, ScrollView scroll, FrameLayout root) {
        HalfSwordRunCheck.maybeRun(inline);
        File request = new File(a.getFilesDir(), "home/.trough/half-sword-view-check-request.json");
        if (!request.isFile()) return;
        try {
            if (request.length() < 2 || request.length() > 4096) return;
            byte[] bytes = new byte[(int)request.length()];
            FileInputStream in = new FileInputStream(request);
            try { int offset=0,n;while(offset<bytes.length&&(n=in.read(bytes,offset,bytes.length-offset))>0)offset+=n;if(offset!=bytes.length)return; }
            finally { in.close(); }
            JSONObject options = new JSONObject(new String(bytes,"UTF-8"));
            String id = options.getString("id");
            boolean components = options.optBoolean("components", false);
            boolean physics = options.optBoolean("physics", false);
            boolean composed = options.optBoolean("composed", false);
            boolean character = options.optBoolean("character", false);
            boolean ground = options.optBoolean("ground", false);
            HalfSwordGroundCheck.request(ground,character);
            boolean pipeline = options.optBoolean("pipeline", false)||character;
            if(pipeline&&composed)throw new Exception("Pipeline and composed fixtures are separate");
            boolean rigid = options.optBoolean("rigid", false)||composed||pipeline;
            UUID.fromString(id);
            File taken = new File(request.getParentFile(), "half-sword-view-check-running-"+id+".json");
            if (!request.renameTo(taken)) return;
            new Handler(Looper.getMainLooper()).post(new HalfSwordViewCheckStarter(a,inline,scroll,root,id,taken,components,physics,rigid,composed,pipeline,character,ground));
        } catch (Exception ignored) { }
    }
    // Construct exactly one runnable after the request has been consumed.
    static final class HalfSwordViewCheckStarter implements Runnable {
        final Activity a; final ScrollView scroll; final HalfSwordView v; final FrameLayout r; final String id; final File taken; final boolean components, physics, rigid, composed, pipeline, character, ground;
        HalfSwordViewCheckStarter(Activity a,HalfSwordView v,ScrollView scroll,FrameLayout r,String id,File taken,boolean components,boolean physics,boolean rigid,boolean composed,boolean pipeline,boolean character,boolean ground){this.scroll=scroll;this.a=a;this.v=v;this.r=r;this.id=id;this.taken=taken;this.components=components;this.physics=physics;this.rigid=rigid;this.composed=composed;this.pipeline=pipeline;this.character=character;this.ground=ground;}
        public void run(){new HalfSwordViewCheck(a,v,scroll,r,id,taken,components,physics,rigid,composed,pipeline,character,ground).run();}
    }
    HalfSwordViewCheck(Activity a,HalfSwordView i,ScrollView sc,FrameLayout r,String key,File taken,boolean checkComponents,boolean checkPhysics,boolean checkRigid,boolean checkComposed,boolean checkPipeline,boolean checkCharacter,boolean checkGround) {
        components=checkComponents;physics=checkPhysics;rigid=checkRigid;composed=checkComposed;pipeline=checkPipeline;character=checkCharacter;ground=checkGround;
        activity=a;inline=i;scroll=sc;root=r;originalCompactTag=r.getTag(HalfSwordView.TEST_COMPACT);id=key;ticket=taken;originalScroll=sc.getScrollY();originalVisibility=i.getVisibility();originalExpanded=expanded(r)!=null;originalExpandedFlag=new File(a.getFilesDir(),"home/.trough/half-sword-expanded").isFile();originalShapesTag=i.owner.getTag(HalfSwordView.SHAPES);HalfSwordView prior=expanded(r);if(prior==null)prior=i;originalShapes=prior.shapes;originalYaw=prior.yaw;originalPreference=i.owner.getTag(HalfSwordView.POSE_MODE);
        synchronized(i.poses){originalMode=i.poses.mode;originalCount=i.poses.count;originalIds=ByteBuffer.allocateDirect(originalCount*4).order(ByteOrder.nativeOrder());originalTargets=ByteBuffer.allocateDirect(originalCount*56).order(ByteOrder.nativeOrder());for(int k=0;k<originalCount*4;k++)originalIds.put(k,i.poses.ids.get(k));for(int k=0;k<originalCount*56;k++)originalTargets.put(k,i.poses.targets.get(k));originalComponents=originalMode==HalfSwordPose.COMPONENTS?ByteBuffer.allocateDirect(HalfSwordPose.COMPONENT_BYTES).order(ByteOrder.nativeOrder()):null;if(originalComponents!=null)for(int k=0;k<HalfSwordPose.COMPONENT_BYTES;k++)originalComponents.put(k,i.poses.componentTransforms.get(k));
            originalActorCount=i.poses.actorCount;
            boolean rigid=originalMode==HalfSwordPose.RIGID_CURRENT||originalMode==HalfSwordPose.RIGID_PREDICTED;
            boolean physics=originalMode==HalfSwordPose.PHYSICS||rigid;
            originalPhysicsParents=physics?copyPoseBytes(i.poses.physicsParents,HalfSwordPose.PHYSICS_INDEX_BYTES):null;
            originalPhysicsMap=physics?copyPoseBytes(i.poses.physicsMap,HalfSwordPose.PHYSICS_INDEX_BYTES):null;
            originalPhysicsActors=rigid?copyPoseBytes(i.poses.rigidActors,HalfSwordPose.RIGID_ACTOR_BYTES):physics?copyPoseBytes(i.poses.physicsActors,HalfSwordPose.PHYSICS_ACTOR_BYTES):null;
            originalPhysicsAlphas=physics?copyPoseBytes(i.poses.physicsAlphas,HalfSwordPose.PHYSICS_ALPHA_BYTES):null;
            originalPhysicsOldLocal=physics?copyPoseBytes(i.poses.physicsOldLocal,HalfSwordPose.COMPONENT_BYTES):null;
            originalPhysicsComponentFrame=physics?copyPoseBytes(i.poses.physicsComponentFrame,HalfSwordPose.PHYSICS_FRAME_BYTES):null;}

    }
    // Called while holding the mailbox monitor; preserve each raw queued byte.
    static ByteBuffer copyPoseBytes(ByteBuffer source,int size) {
        ByteBuffer out=ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        for(int k=0;k<size;k++)out.put(k,source.get(k));
        return out;
    }
    static Button button(View v,String text) {
        if(v instanceof Button && (text.equals(((Button)v).getText().toString())||(((Button)v).getContentDescription()!=null&&text.contentEquals(((Button)v).getContentDescription()))))return (Button)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Button b=button(g.getChildAt(i),text);if(b!=null)return b;}}
        return null;
    }
    static HalfSwordView expanded(View v) {
        if(v instanceof HalfSwordView && ((HalfSwordView)v).expanded)return (HalfSwordView)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){HalfSwordView found=expanded(g.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    void resizeTestPanel(){
        if(testPanel==null||testPanel.getParent()!=root)return;
        HalfSwordView.layoutPanel(root,testPanel);
    }
    void compactPanel(){
        HalfSwordView current=expanded(root);if(current==null)return;
        View panel=(View)current.getParent();
        if(testPanel==null){testPanel=panel;if(originalExpanded){originalPanelLayout=new FrameLayout.LayoutParams((FrameLayout.LayoutParams)panel.getLayoutParams());originalPanelBackground=panel.getBackground();}panel.setBackgroundColor(0xee080d12);root.addOnLayoutChangeListener(compactLayoutListener);}
        resizeTestPanel();
    }
    void measureChatAccess(){
        need(testPanel!=null&&testPanel.getParent()==root,"Test preview panel detached");
        panelLeft=testPanel.getLeft();panelTop=testPanel.getTop();panelRight=testPanel.getRight();panelBottom=testPanel.getBottom();rootWidth=root.getWidth();rootHeight=root.getHeight();
        chatStatusBottom=HalfSwordView.chatStatusBottom(root);
        Object header=root.getTag(HalfSwordView.CHAT_STATUS);
        if(header instanceof android.widget.TextView){android.widget.TextView text=(android.widget.TextView)header;chatStatusText=text.getText().toString();android.graphics.Rect visible=new android.graphics.Rect();workingStatusVisible=text.isShown()&&text.getGlobalVisibleRect(visible)&&visible.height()>=text.getHeight()&&chatStatusBottom>0&&panelTop>=chatStatusBottom&&!chatStatusText.isEmpty();}
        testPreviewCompact=panelTop>=chatStatusBottom&&testPanel.getHeight()>0&&testPanel.getHeight()<=rootHeight*.5f;
        chatAccessible=testPreviewCompact&&rootHeight-panelBottom>=rootHeight*.5f;
        need(chatAccessible,"Test preview covers chat area");
        need(workingStatusVisible,"Live work header covered by preview");
    }
    void need(boolean value,String error){if(!value)throw new IllegalStateException(error);}
    void record(String name)throws Exception{JSONObject e=new JSONObject();e.put("stage",name).put("elapsed_ms",SystemClock.uptimeMillis()-start);if(view!=null)e.put("draws",view.drawCount).put("presented",view.presentedCount).put("pose_mode",view.poseMode).put("animated",view.animated).put("yaw",view.yaw);events.put(e);}
    String pixels()throws Exception {
        Bitmap b=view.getBitmap(96,96);need(b!=null,"Texture bitmap unavailable");
        try{int[] p=new int[96*96];b.getPixels(p,0,96,0,0,96,96);long hash=0xcbf29ce484222325L;for(int x:p){hash^=x;hash*=0x100000001b3L;}return Long.toHexString(hash);}finally{b.recycle();}
    }
    void clickPose(){need(view.poseButton!=null&&view.poseButton.performClick(),"Pose button unavailable");}
    void clickShapes(){need(view.shapeButton!=null&&view.shapeButton.performClick(),"Shapes button unavailable");}
    void drag(){long t=SystemClock.uptimeMillis();float y=Math.max(1,view.getHeight()/2f);for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),MotionEvent.ACTION_MOVE,MotionEvent.ACTION_CANCEL}){MotionEvent event=HalfSwordSceneCheck.orbitEvent(t,action,action==MotionEvent.ACTION_MOVE?90:30,y);try{view.dispatchTouchEvent(event);}finally{event.recycle();}}}

    boolean submit(int bone){ByteBuffer ids=ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()),poses=ByteBuffer.allocateDirect(56).order(ByteOrder.nativeOrder());ids.putInt(0,bone);poses.putDouble(24,1);poses.putDouble(32,30);need(inline.getVisibility()!=View.VISIBLE,"Inline producer is not hidden");return inline.submitBoneTargets(ids,poses,1);}
    void loadComponents() throws Exception {
        File fixture = new File(activity.getFilesDir(), "home/.trough/half-sword-view-components.bin");
        int bytes = HalfSwordPose.COMPONENT_BYTES;
        need(fixture.isFile() && fixture.length() == 2L * bytes, "Component fixture must contain exactly two full frames");
        byte[] data = new byte[2 * bytes];
        FileInputStream in = new FileInputStream(fixture);
        try {
            int offset = 0, n;
            while (offset < data.length && (n = in.read(data, offset, data.length - offset)) > 0) offset += n;
            need(offset == data.length && in.read() == -1, "Component fixture changed or was truncated");
        } finally { in.close(); }
        referenceComponents = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        deformedComponents = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        for (int k = 0; k < bytes; k++) {
            referenceComponents.put(k, data[k]);
            deformedComponents.put(k, data[bytes + k]);
        }
    }
    boolean submitComponents(ByteBuffer frame) {
        need(inline.getVisibility() != View.VISIBLE, "Inline component producer is not hidden");
        return inline.submitComponentTransforms(frame, HalfSwordPose.COMPONENT_COUNT);
    }
    void beginPhysics() throws Exception {
        File fixture=new File(activity.getFilesDir(),"home/.trough/half-sword-view-physics.bin");
        need(fixture.isFile()&&fixture.length()==18888,"Physics fixture must contain exactly two 9444-byte frames");
        byte[] data=new byte[18888];FileInputStream in=new FileInputStream(fixture);
        try{int at=0,n;while(at<data.length&&(n=in.read(data,at,data.length-at))>0)at+=n;need(at==data.length&&in.read()==-1,"Physics fixture changed or truncated");}finally{in.close();}
        int[] sizes={268,268,2112,268,6432,96};physicsFrames=new ByteBuffer[2][6];int at=0;
        for(int frame=0;frame<2;frame++)for(int field=0;field<6;field++){ByteBuffer b=ByteBuffer.allocateDirect(sizes[field]).order(ByteOrder.nativeOrder());for(int k=0;k<sizes[field];k++)b.put(k,data[at++]);physicsFrames[frame][field]=b;}
        mark=view.drawCount;presentedMark=view.presentedCount;stableAt=0;clickPose();stage=18;
    }
    boolean submitPhysics(int frame) {
        need(inline.getVisibility()!=View.VISIBLE,"Inline physics producer is not hidden");
        ByteBuffer[] f=physicsFrames[frame];return inline.submitPhysicsFrame(f[0],f[1],f[2],22,f[3],f[4],f[5]);
    }
    void beginRigid() throws Exception {
        File fixture=new File(activity.getFilesDir(),pipeline?"home/.trough/half-sword-view-pipeline.bin":"home/.trough/half-sword-view-rigid.bin");
        need(fixture.isFile()&&fixture.length()==27344,"Rigid fixture must contain two padded 13672-byte packets");
        byte[] data=new byte[27344];FileInputStream in=new FileInputStream(fixture);
        try{int at=0,n;while(at<data.length&&(n=in.read(data,at,data.length-at))>0)at+=n;need(at==data.length&&in.read()==-1,"Rigid fixture changed or truncated");}finally{in.close();}
        if(pipeline){java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");StringBuilder hex=new StringBuilder();for(byte value:digest.digest(data))hex.append(String.format(java.util.Locale.US,"%02x",value&255));result.put("pipeline_fixture_sha256",hex.toString());}
        int[] sizes={268,268,6336,268,6432,96},offsets={0,268,536,6872,7144,13576};rigidFrames=new ByteBuffer[2][6];
        for(int frame=0;frame<2;frame++){
            for(int k=7140;k<7144;k++)need(data[frame*13672+k]==0,"Rigid packet padding must be zero");
            for(int field=0;field<6;field++){ByteBuffer b=ByteBuffer.allocateDirect(sizes[field]).order(ByteOrder.nativeOrder());for(int k=0;k<sizes[field];k++)b.put(k,data[frame*13672+offsets[field]+k]);rigidFrames[frame][field]=b;}
        }
        // Compare skinned mesh pixels without collision inspection lines.
        if(view.shapes)clickShapes();
        need(!view.shapes,"Rigid comparison requires mesh-only rendering");
        mark=view.drawCount;presentedMark=view.presentedCount;stableAt=0;clickPose();stage=26;
    }
    boolean submitRigid(int frame) {
        need(inline.getVisibility()!=View.VISIBLE,"Inline rigid producer is not hidden");
        ByteBuffer[] f=rigidFrames[frame];return inline.submitRigidFrame(frame==0?HalfSwordPose.RIGID_CURRENT:HalfSwordPose.RIGID_PREDICTED,f[0],f[1],f[2],22,f[3],f[4],f[5]);
    }
    void rejectRigid(int frame,int field,int offset) {
        view.status.setText("Awaiting rigid validation");ByteBuffer b=rigidFrames[frame][field];double old=b.getDouble(offset);b.putDouble(offset,Double.NaN);
        try{need(submitRigid(frame),"Malformed numeric rigid frame was not queued");}finally{b.putDouble(offset,old);}
        stableAt=SystemClock.uptimeMillis();
    }
    void checkRigidRejected(int mode,String hash) throws Exception {
        need(view.status.getText().toString().contains("Pose rejected"),"Missing fresh rigid rejection status");need(view.poseMode==mode&&!view.animated,"Rejected rigid frame changed mode");need(hash.equals(pixels()),"Rejected rigid frame changed pixels");
    }
    void beginComposed() throws Exception {
        File fixture=new File(activity.getFilesDir(),"home/.trough/half-sword-view-composed.bin");
        need(fixture.isFile()&&fixture.length()==13672,"Composed fixture must contain one padded 13672-byte packet");
        byte[] data=new byte[13672];FileInputStream in=new FileInputStream(fixture);
        try{int at=0,n;while(at<data.length&&(n=in.read(data,at,data.length-at))>0)at+=n;need(at==data.length&&in.read()==-1,"Composed fixture changed or truncated");}finally{in.close();}
        for(int k=7140;k<7144;k++)need(data[k]==0,"Composed packet padding must be zero");
        int[] sizes={268,268,6336,268,6432,96},offsets={0,268,536,6872,7144,13576};composedFrame=new ByteBuffer[6];
        for(int field=0;field<6;field++){ByteBuffer b=ByteBuffer.allocateDirect(sizes[field]).order(ByteOrder.nativeOrder());for(int k=0;k<sizes[field];k++)b.put(k,data[offsets[field]+k]);composedFrame[field]=b;}
        // Re-establish the supplied CURRENT baseline after the prior drag/Breathe checks.
        if(view.shapes)clickShapes();view.yaw=rigidComparisonYaw;view.camera[0]=rigidComparisonYaw;view.wake();
        need(!view.shapes,"Composed comparison requires mesh-only rendering");
        mark=view.drawCount;presentedMark=view.presentedCount;stableAt=0;
        need(submitRigid(0),"Composed CURRENT baseline request rejected");stage=35;
    }
    boolean submitComposed(){
        need(inline.getVisibility()!=View.VISIBLE,"Inline composed producer is not hidden");
        ByteBuffer[] f=composedFrame;return inline.submitRigidFrame(HalfSwordPose.RIGID_CURRENT,f[0],f[1],f[2],22,f[3],f[4],f[5]);
    }
    void beginCharacter()throws Exception{
        if(view.shapes)clickShapes();view.yaw=rigidComparisonYaw;view.camera[0]=rigidComparisonYaw;view.wake();
        Button reset=button(view.controlsRoot(),"Reset physics");need(reset!=null&&reset.performClick(),"Physics reset unavailable");mark=view.drawCount;presentedMark=view.presentedCount;stableAt=0;stage=39;
    }
    void clickCharacter()throws Exception{Button step=button(view.controlsRoot(),"Physics step");need(step!=null&&step.performClick(),"Physics step unavailable");}
    public void run() {
        if(done)return;
        try {
            need(!activity.isFinishing()&&!activity.isDestroyed()&&inline.isAttachedToWindow(),"Activity/view detached during check");
            need(SystemClock.uptimeMillis()-start<((physics||rigid)?30000:20000),"Bounded UI check timeout at stage "+stage);
            switch(stage){
            case 0:
                root.setTag(HalfSwordView.TEST_COMPACT,Boolean.TRUE);
                if(!originalExpanded){Button expand=button((View)inline.getParent(),"Expand");need(expand!=null&&expand.performClick(),"Expand button unavailable");}compactPanel();stage=1;break;
            case 1:
                view=expanded(root);if(view==null||!view.isAvailable()||!view.isShown()||view.drawCount==0)break;
                compactPanel();if(testPanel==null||testPanel.getTop()<HalfSwordView.chatStatusBottom(root)||testPanel.getHeight()>root.getHeight()*.5f||testPanel.getHeight()<=0)break;measureChatAccess();record("expanded_visible_rendering");
                if(view.poseMode!=HalfSwordPose.BREATHING){clickPose();stage=2;}else{clickPose();stage=3;}break;
            case 2:if(view.poseMode==HalfSwordPose.BREATHING&&view.animated){clickPose();stage=3;}break;
            case 3:
                if(view.poseMode!=HalfSwordPose.REST||view.animated)break;
                if(stableDraw!=view.drawCount||stableAt==0){stableDraw=view.drawCount;stableAt=SystemClock.uptimeMillis();break;}
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                record("rest_static_400ms");mark=view.drawCount;presentedMark=view.presentedCount;clickShapes();stage=4;break;
            case 4:if(view.drawCount>mark&&view.presentedCount>presentedMark){record("static_shapes_redraw");mark=view.drawCount;presentedMark=view.presentedCount;float yaw=view.yaw;drag();need(view.yaw!=yaw,"Drag did not update yaw");stage=5;}break;
            case 5:if(view.drawCount>mark&&view.presentedCount>presentedMark){record("static_drag_redraw");referenceHash=pixels();mark=view.drawCount;presentedMark=view.presentedCount;need(submit(0),"Target mailbox rejected valid request");stage=6;}break;
            case 6:if(view.poseMode==HalfSwordPose.TARGETS&&!view.animated&&view.drawCount>mark&&view.presentedCount>presentedMark){targetHash=pixels();need(!targetHash.equals(referenceHash),"Target pose pixels unchanged");record("hidden_inline_target_pixels_changed");need(submit(999),"Malformed numeric target was not queued");stableAt=SystemClock.uptimeMillis();stage=7;}break;
            case 7:
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                need(view.status.getText().toString().contains("Pose rejected"),"Missing explicit native rejection status");need(view.poseMode==HalfSwordPose.TARGETS&&!view.animated,"Rejected target changed mode");need(targetHash.equals(pixels()),"Rejected target changed pixels");record("invalid_target_retained_pixels");mark=view.drawCount;clickShapes();stage=8;break;
            case 8:if(view.drawCount>mark){record("responsive_after_rejection");mark=view.drawCount;clickPose();stage=9;}break;
            case 9:
                if(view.poseMode==HalfSwordPose.BREATHING&&view.animated&&view.drawCount>mark+2){
                    record("breathing_draw_growth");
                    if(!components){if(physics){beginPhysics();break;}if(rigid){beginRigid();break;}finish(null);return;}
                    loadComponents();mark=view.drawCount;presentedMark=view.presentedCount;stableAt=0;clickPose();stage=10;
                }break;
            case 10:
                if(view.poseMode!=HalfSwordPose.REST||view.animated||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                if(stableDraw!=view.drawCount||stableAt==0){stableDraw=view.drawCount;stableAt=SystemClock.uptimeMillis();break;}
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                componentRestHash=pixels();record("component_baseline_rest_static_400ms");
                mark=view.drawCount;presentedMark=view.presentedCount;
                need(submitComponents(referenceComponents),"Component mailbox rejected reference frame");stableAt=0;stage=11;break;
            case 11:
                if(view.poseMode==HalfSwordPose.COMPONENTS&&!view.animated&&view.drawCount>mark&&view.presentedCount>presentedMark){
                    if(stableAt==0||stableDraw!=view.drawCount){stableDraw=view.drawCount;stableAt=SystemClock.uptimeMillis();break;}
                    if(SystemClock.uptimeMillis()-stableAt<400)break;
                    componentReferenceHash=pixels();record("hidden_inline_component_reference_presented");
                    // REST and the supplied reference can differ by floating-point normalization.
                    // Record that relationship; deformation is compared against this component baseline.
                    mark=view.drawCount;presentedMark=view.presentedCount;
                    need(submitComponents(deformedComponents),"Component mailbox rejected deformation frame");stage=12;
                }break;
            case 12:
                if(view.poseMode==HalfSwordPose.COMPONENTS&&!view.animated&&view.drawCount>mark&&view.presentedCount>presentedMark){
                    record("hidden_inline_component_deformation_arrived");
                    stableDraw=view.drawCount;stableAt=SystemClock.uptimeMillis();stage=13;
                }break;
            case 13:
                need(view.poseMode==HalfSwordPose.COMPONENTS&&!view.animated,"Component hold changed mode");
                if(view.drawCount!=stableDraw){stableDraw=view.drawCount;stableAt=SystemClock.uptimeMillis();break;}
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                componentDeformedHash=pixels();need(!componentDeformedHash.equals(componentReferenceHash),"Component deformation pixels unchanged");
                record("hidden_inline_component_deformation_pixels_changed");record("component_static_400ms");
                int lateScale = HalfSwordPose.COMPONENT_BYTES - 16;
                double originalScale = deformedComponents.getDouble(lateScale);
                deformedComponents.putDouble(lateScale,Double.NaN);
                try{need(submitComponents(deformedComponents),"Malformed numeric component frame was not queued");}
                finally{deformedComponents.putDouble(lateScale,originalScale);}
                stableAt=SystemClock.uptimeMillis();stage=14;break;
            case 14:
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                need(view.status.getText().toString().contains("Pose rejected"),"Missing explicit component rejection status");
                need(view.poseMode==HalfSwordPose.COMPONENTS&&!view.animated,"Rejected component frame changed mode");
                need(componentDeformedHash.equals(pixels()),"Rejected component frame changed pixels");
                record("invalid_last_component_retained_pixels");mark=view.drawCount;presentedMark=view.presentedCount;clickShapes();stage=15;break;
            case 15:
                if(view.drawCount>mark&&view.presentedCount>presentedMark){
                    record("component_shapes_redraw_after_rejection");mark=view.drawCount;presentedMark=view.presentedCount;
                    float previousYaw=view.yaw;drag();need(view.yaw!=previousYaw,"Component drag did not update yaw");stage=16;
                }break;
            case 16:
                if(view.drawCount>mark&&view.presentedCount>presentedMark){record("component_drag_redraw_after_rejection");mark=view.drawCount;clickPose();stage=17;}break;
            case 17:
                if(view.poseMode==HalfSwordPose.BREATHING&&view.animated&&view.drawCount>mark+2){record("breathing_after_components_draw_growth");if(physics){beginPhysics();break;}if(rigid){beginRigid();break;}finish(null);return;}break;
            case 18:
                if(view.poseMode!=HalfSwordPose.REST||view.animated||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                if(stableAt==0||stableDraw!=view.drawCount){stableDraw=view.drawCount;stableAt=SystemClock.uptimeMillis();break;}
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                physicsRestHash=pixels();record("physics_baseline_rest_static_400ms");mark=view.drawCount;presentedMark=view.presentedCount;
                need(submitPhysics(0),"Physics mailbox rejected reference frame");stableAt=0;stage=19;break;
            case 19:
                if(view.poseMode!=HalfSwordPose.PHYSICS||view.animated||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                if(stableAt==0||stableDraw!=view.drawCount){stableDraw=view.drawCount;stableAt=SystemClock.uptimeMillis();break;}
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                physicsReferenceHash=pixels();record("hidden_inline_physics_reference_static_400ms");mark=view.drawCount;presentedMark=view.presentedCount;
                need(submitPhysics(1),"Physics mailbox rejected deformation frame");stage=20;break;
            case 20:
                if(view.poseMode==HalfSwordPose.PHYSICS&&!view.animated&&view.drawCount>mark&&view.presentedCount>presentedMark){record("hidden_inline_physics_deformation_arrived");stableDraw=view.drawCount;stableAt=SystemClock.uptimeMillis();stage=21;}break;
            case 21:
                need(view.poseMode==HalfSwordPose.PHYSICS&&!view.animated,"Physics hold changed mode");
                if(stableDraw!=view.drawCount){stableDraw=view.drawCount;stableAt=SystemClock.uptimeMillis();break;}
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                physicsDeformedHash=pixels();need(!physicsDeformedHash.equals(physicsReferenceHash),"Physics deformation pixels unchanged");record("hidden_inline_physics_deformation_pixels_changed");record("physics_static_400ms");
                view.status.setText("Awaiting physics validation");
                ByteBuffer old=physicsFrames[1][4];double scale=old.getDouble(6416);old.putDouble(6416,Double.NaN);
                try{need(submitPhysics(1),"Malformed numeric physics frame was not queued");}finally{old.putDouble(6416,scale);}
                stableAt=SystemClock.uptimeMillis();stage=22;break;
            case 22:
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                need(view.status.getText().toString().contains("Pose rejected"),"Missing explicit physics rejection status");need(view.poseMode==HalfSwordPose.PHYSICS&&!view.animated,"Rejected physics changed mode");need(physicsDeformedHash.equals(pixels()),"Rejected physics changed pixels");record("invalid_last_physics_local_retained_pixels");mark=view.drawCount;presentedMark=view.presentedCount;clickShapes();stage=23;break;
            case 23:
                if(view.drawCount>mark&&view.presentedCount>presentedMark){record("physics_shapes_redraw_after_rejection");mark=view.drawCount;presentedMark=view.presentedCount;float priorYaw=view.yaw;drag();need(view.yaw!=priorYaw,"Physics drag did not update yaw");stage=24;}break;
            case 24:
                if(view.drawCount>mark&&view.presentedCount>presentedMark){record("physics_drag_redraw_after_rejection");mark=view.drawCount;clickPose();stage=25;}break;
            case 25:
                if(view.poseMode==HalfSwordPose.BREATHING&&view.animated&&view.drawCount>mark+2){record("breathing_after_physics_draw_growth");if(rigid){beginRigid();break;}finish(null);return;}break;
            case 26:
                if(view.poseMode!=HalfSwordPose.REST||view.animated||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                if(stableAt==0||stableDraw!=view.drawCount){stableDraw=view.drawCount;stableAt=SystemClock.uptimeMillis();break;}
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                need(!view.shapes,"Rigid baseline unexpectedly shows shapes");rigidComparisonYaw=view.yaw;rigidComparisonShapes=view.shapes;rigidRestHash=pixels();record("rigid_baseline_rest_static_400ms");mark=view.drawCount;presentedMark=view.presentedCount;need(submitRigid(0),"Rigid CURRENT request rejected");stableAt=0;stage=27;break;
            case 27:
                if(view.poseMode!=HalfSwordPose.RIGID_CURRENT||view.animated||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                if(stableAt==0||stableDraw!=view.drawCount){stableDraw=view.drawCount;stableAt=SystemClock.uptimeMillis();break;}
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                need(view.yaw==rigidComparisonYaw&&view.shapes==rigidComparisonShapes,"Rigid comparison camera/shapes changed");rigidCurrentHash=pixels();record("hidden_inline_rigid_current_static_400ms");rejectRigid(0,2,21*288);stage=28;break;
            case 28:
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                checkRigidRejected(HalfSwordPose.RIGID_CURRENT,rigidCurrentHash);record("invalid_last_current_actor_retained_pixels");mark=view.drawCount;presentedMark=view.presentedCount;need(submitRigid(1),"Rigid PREDICTED request rejected");stableAt=0;stage=29;break;
            case 29:
                if(view.poseMode!=HalfSwordPose.RIGID_PREDICTED||view.animated||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                if(stableAt==0||stableDraw!=view.drawCount){stableDraw=view.drawCount;stableAt=SystemClock.uptimeMillis();break;}
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                need(view.yaw==rigidComparisonYaw&&view.shapes==rigidComparisonShapes,"Rigid comparison camera/shapes changed");rigidPredictedHash=pixels();record("hidden_inline_rigid_predicted_static_400ms");need(!rigidPredictedHash.equals(rigidCurrentHash),"CURRENT/PREDICTED pixels unchanged");record("rigid_current_predicted_pixels_differ");rejectRigid(1,2,21*288+216);stage=30;break;
            case 30:
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                checkRigidRejected(HalfSwordPose.RIGID_PREDICTED,rigidPredictedHash);record("invalid_last_predicted_actor_retained_pixels");rejectRigid(1,4,6416);stage=31;break;
            case 31:
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                checkRigidRejected(HalfSwordPose.RIGID_PREDICTED,rigidPredictedHash);record("invalid_last_rigid_old_local_retained_pixels");mark=view.drawCount;presentedMark=view.presentedCount;clickShapes();stage=32;break;
            case 32:
                if(view.drawCount>mark&&view.presentedCount>presentedMark){record("rigid_shapes_redraw_after_rejection");mark=view.drawCount;presentedMark=view.presentedCount;float priorYaw=view.yaw;drag();need(view.yaw!=priorYaw,"Rigid drag did not change yaw");stage=33;}break;
            case 33:
                if(view.drawCount>mark&&view.presentedCount>presentedMark){record("rigid_drag_redraw_after_rejection");mark=view.drawCount;clickPose();stage=34;}break;
            case 34:
                if(view.poseMode==HalfSwordPose.BREATHING&&view.animated&&view.drawCount>mark+2){record("breathing_after_rigid_draw_growth");if(composed){beginComposed();break;}if(character){beginCharacter();break;}finish(null);return;}break;
            case 35:
                if(view.poseMode!=HalfSwordPose.RIGID_CURRENT||view.animated||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                if(stableAt==0||stableDraw!=view.drawCount){stableDraw=view.drawCount;stableAt=SystemClock.uptimeMillis();break;}
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                need(!view.shapes&&view.yaw==rigidComparisonYaw,"Composed baseline camera/shapes changed");
                composedBaselineHash=pixels();record("composed_current_baseline_static_400ms");mark=view.drawCount;presentedMark=view.presentedCount;stableAt=0;
                need(submitComposed(),"Composed CURRENT request rejected");stage=36;break;
            case 36:
                if(view.poseMode!=HalfSwordPose.RIGID_CURRENT||view.animated||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                if(stableAt==0||stableDraw!=view.drawCount){stableDraw=view.drawCount;stableAt=SystemClock.uptimeMillis();break;}
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                need(!view.shapes&&view.yaw==rigidComparisonYaw,"Composed comparison camera/shapes changed");
                composedHash=pixels();record("hidden_inline_composed_current_static_400ms");need(!composedHash.equals(composedBaselineHash),"Composed CURRENT pixels unchanged");record("composed_current_pixels_differ");
                view.status.setText("Awaiting composed validation");ByteBuffer actor=composedFrame[2];double saved=actor.getDouble(21*288);actor.putDouble(21*288,Double.NaN);
                try{need(submitComposed(),"Malformed composed actor was not queued");}finally{actor.putDouble(21*288,saved);}
                stableAt=SystemClock.uptimeMillis();stage=37;break;
            case 37:
                if(SystemClock.uptimeMillis()-stableAt<400)break;
                checkRigidRejected(HalfSwordPose.RIGID_CURRENT,composedHash);record("invalid_last_composed_actor_retained_pixels");composedChecked=true;mark=view.drawCount;clickPose();stage=38;break;
            case 38:
                if(view.poseMode==HalfSwordPose.BREATHING&&view.animated&&view.drawCount>mark+2){record("breathing_after_composed_draw_growth");finish(null);return;}break;
            case 39:
                if(view.characterSteps!=0||view.poseMode!=HalfSwordPose.REST||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                HalfSwordGroundCheck.mode(ground,view.characterGround,view.characterSteps);characterRestHash=pixels();record("character_reset_rest");mark=view.drawCount;presentedMark=view.presentedCount;clickCharacter();stage=40;break;
            case 40:
                need(!view.characterFailed,"First character frame failed");if(view.characterSteps!=1||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                HalfSwordGroundCheck.mode(ground,view.characterGround,view.characterSteps);characterFirstHash=pixels();need(!characterFirstHash.equals(characterRestHash),"First physics frame has no visible change");record("character_first_frame_visible");mark=view.drawCount;presentedMark=view.presentedCount;clickCharacter();stage=41;break;
            case 41:
                need(!view.characterFailed,"Second character frame failed");if(view.characterSteps!=2||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                HalfSwordGroundCheck.mode(ground,view.characterGround,view.characterSteps);characterSecondHash=pixels();need(!characterSecondHash.equals(characterFirstHash),"Second physics frame has no visible change");record("character_second_frame_visible");record("character_two_manual_frames_visible");
                Button reset=button(view.controlsRoot(),"Reset physics");need(reset!=null&&reset.performClick(),"Final physics reset unavailable");mark=view.drawCount;presentedMark=view.presentedCount;stage=42;break;
            case 42:
                if(view.characterSteps!=0||view.poseMode!=HalfSwordPose.REST||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                HalfSwordGroundCheck.mode(ground,view.characterGround,view.characterSteps);need(!view.characterFailed,"Reset retained failure state");need(pixels().equals(characterRestHash),"Reset did not restore reference pixels");record("character_reset_restores_pixels");Button push=button(view.controlsRoot(),"Push +X");need(push!=null&&push.performClick(),"Positive push unavailable");mark=view.drawCount;presentedMark=view.presentedCount;stage=43;break;
            case 43:
                need(!view.characterFailed,"Positive force failed");if(view.characterSteps!=1||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                HalfSwordGroundCheck.mode(ground,view.characterGround,view.characterSteps);characterPositiveHash=pixels();need(!characterPositiveHash.equals(characterFirstHash),"Positive force equals unforced frame");record("character_positive_force_visible");Button positiveReset=button(view.controlsRoot(),"Reset physics");need(positiveReset!=null&&positiveReset.performClick(),"Positive reset unavailable");mark=view.drawCount;presentedMark=view.presentedCount;stage=44;break;
            case 44:
                if(view.characterSteps!=0||view.poseMode!=HalfSwordPose.REST||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                HalfSwordGroundCheck.mode(ground,view.characterGround,view.characterSteps);Button negative=button(view.controlsRoot(),"Push −X");need(negative!=null&&negative.performClick(),"Negative push unavailable");mark=view.drawCount;presentedMark=view.presentedCount;stage=45;break;
            case 45:
                need(!view.characterFailed,"Negative force failed");if(view.characterSteps!=1||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                HalfSwordGroundCheck.mode(ground,view.characterGround,view.characterSteps);characterNegativeHash=pixels();need(!characterNegativeHash.equals(characterPositiveHash)&&!characterNegativeHash.equals(characterFirstHash),"Negative force has no distinct visible response");record("character_negative_force_visible");Button negativeReset=button(view.controlsRoot(),"Reset physics");need(negativeReset!=null&&negativeReset.performClick(),"Negative reset unavailable");mark=view.drawCount;presentedMark=view.presentedCount;stage=46;break;
            case 46:
                if(view.characterSteps!=0||view.poseMode!=HalfSwordPose.REST||view.drawCount<=mark||view.presentedCount<=presentedMark)break;
                HalfSwordGroundCheck.mode(ground,view.characterGround,view.characterSteps);need(pixels().equals(characterRestHash),"Force reset changed reference pixels");record("character_force_reset_restores_pixels");finish(null);return;
            default:throw new IllegalStateException("Unknown stage");
            }
            handler.postDelayed(this,50);
        } catch(Throwable error){finish(error.toString());}
    }
    void finish(String error) {
        if(done)return;done=true;handler.removeCallbacks(this);
        root.removeOnLayoutChangeListener(compactLayoutListener);
        root.setTag(HalfSwordView.TEST_COMPACT,originalCompactTag);
        if(originalExpanded&&testPanel!=null&&testPanel.getParent()==root&&originalPanelLayout!=null){testPanel.setLayoutParams(originalPanelLayout);testPanel.setBackground(originalPanelBackground);}
        try {
            HalfSwordView current=view!=null?view:inline;
            current.yaw=originalYaw;current.camera[0]=originalYaw;inline.yaw=originalYaw;
            if(current.shapes!=originalShapes&&current.shapeButton!=null)current.shapeButton.performClick();
            current.shapes=originalShapes;inline.shapes=originalShapes;inline.owner.setTag(HalfSwordView.SHAPES,originalShapesTag);
            if(originalMode==HalfSwordPose.TARGETS)inline.poses.publish(originalIds,originalTargets,originalCount);else if(originalMode==HalfSwordPose.COMPONENTS)inline.poses.publishComponents(originalComponents,originalCount);else if(originalMode==HalfSwordPose.PHYSICS)inline.poses.publishPhysics(originalPhysicsParents,originalPhysicsMap,originalPhysicsActors,originalActorCount,originalPhysicsAlphas,originalPhysicsOldLocal,originalPhysicsComponentFrame);else if(originalMode==HalfSwordPose.RIGID_CURRENT||originalMode==HalfSwordPose.RIGID_PREDICTED)inline.poses.publishRigid(originalMode,originalPhysicsParents,originalPhysicsMap,originalPhysicsActors,originalActorCount,originalPhysicsAlphas,originalPhysicsOldLocal,originalPhysicsComponentFrame);else inline.poses.request(originalMode);inline.owner.setTag(HalfSwordView.POSE_MODE,originalPreference);current.wake();inline.wake();
            if(!originalExpanded&&expanded(root)!=null){Button back=button(root,"Chat");if(back!=null)back.performClick();}
            inline.setVisibility(originalVisibility);scroll.scrollTo(scroll.getScrollX(),originalScroll);if(inline.poseButton!=null)inline.poseButton.setText(originalMode==HalfSwordPose.BREATHING?"Rest":"Breathe");if(inline.shapeButton!=null)inline.shapeButton.setText(originalShapes?"Shapes ✓":"Shapes");if(current.poseButton!=null)current.poseButton.setText(originalMode==HalfSwordPose.BREATHING?"Rest":"Breathe");if(current.shapeButton!=null)current.shapeButton.setText(originalShapes?"Shapes ✓":"Shapes");File flag=new File(activity.getFilesDir(),"home/.trough/half-sword-expanded");if(originalExpandedFlag)flag.createNewFile();else flag.delete();
        }catch(Throwable cleanup){error=(error==null?"":error+"; ")+"cleanup: "+cleanup;}
        try {
            result.put("id",id).put("passed",error==null).put("error",error==null?"":error).put("stages",events).put("reference_pixel_hash",referenceHash==null?"":referenceHash).put("target_pixel_hash",targetHash==null?"":targetHash).put("elapsed_ms",SystemClock.uptimeMillis()-start).put("scope","Actual expanded TextureView/button/touch/GLES path on UI thread; bounded explicit request. No gameplay/full-frame performance claim.");
            result.put("test_preview_compact",testPreviewCompact).put("chat_accessible",chatAccessible).put("chat_access_scope","Geometric uncovered lower chat/composer region; no human interaction assertion").put("test_preview_bounds",new JSONArray(new int[]{panelLeft,panelTop,panelRight,panelBottom})).put("test_root_bounds",new JSONArray(new int[]{0,0,rootWidth,rootHeight}));
            result.put("working_status_visible",workingStatusVisible).put("chat_status_text",chatStatusText).put("chat_status_bottom",chatStatusBottom);
            result.put("components_requested",components).put("physics_requested",physics).put("rigid_requested",rigid).put("pipeline_requested",pipeline).put("character_requested",character).put("ground_requested",ground).put("character_first_pixel_hash",characterFirstHash).put("character_second_pixel_hash",characterSecondHash).put("character_positive_pixel_hash",characterPositiveHash).put("character_negative_pixel_hash",characterNegativeHash);
            result.put("composed_requested",composed).put("composed_checked",composedChecked);
            if(composed)result.put("composed_comparison_shapes",false).put("composed_comparison_yaw",rigidComparisonYaw).put("composed_baseline_pixel_hash",composedBaselineHash==null?"":composedBaselineHash).put("composed_pixel_hash",composedHash==null?"":composedHash);
            if(rigid)result.put("rigid_comparison_shapes",rigidComparisonShapes).put("rigid_comparison_yaw",rigidComparisonYaw).put("rigid_rest_pixel_hash",rigidRestHash==null?"":rigidRestHash).put("rigid_current_pixel_hash",rigidCurrentHash==null?"":rigidCurrentHash).put("rigid_predicted_pixel_hash",rigidPredictedHash==null?"":rigidPredictedHash);
            if(physics)result.put("physics_rest_pixel_hash",physicsRestHash==null?"":physicsRestHash).put("physics_reference_pixel_hash",physicsReferenceHash==null?"":physicsReferenceHash).put("physics_deformed_pixel_hash",physicsDeformedHash==null?"":physicsDeformedHash);
            if(components)result.put("component_rest_pixel_hash",componentRestHash==null?"":componentRestHash).put("component_reference_pixel_hash",componentReferenceHash==null?"":componentReferenceHash).put("component_deformed_pixel_hash",componentDeformedHash==null?"":componentDeformedHash).put("component_reference_matches_rest",componentRestHash!=null&&componentRestHash.equals(componentReferenceHash));
            File out=new File(activity.getFilesDir(),"home/.trough/half-sword-view-check-result.json"),tmp=new File(out.getParentFile(),out.getName()+".tmp");FileOutputStream stream=new FileOutputStream(tmp);try{stream.write(result.toString().getBytes("UTF-8"));stream.getFD().sync();}finally{stream.close();}if(!tmp.renameTo(out))throw new IOException("Atomic result rename failed");
        }catch(Exception ignored){}finally{ticket.delete();}
    }
}
