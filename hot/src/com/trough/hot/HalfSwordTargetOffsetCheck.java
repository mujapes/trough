package com.trough.hot;
import android.os.*;import java.io.*;import org.json.*;
/** Render-thread target transform + same-offset query, no simulation advance. */
final class HalfSwordTargetOffsetCheck implements Runnable {
 final HalfSwordView view;final HalfSwordAimCheck input;final Handler ui=new Handler(Looper.getMainLooper());final long began=SystemClock.uptimeMillis();int stage,steps;long request,captureAfter;boolean guards,query;int changedPixels;boolean restoredPixels;String error;
 HalfSwordTargetOffsetCheck(HalfSwordView v){view=v;input=new HalfSwordAimCheck(v);}void start(){ui.postDelayed(this,150);}void need(boolean ok,String why)throws Exception{if(!ok)throw new Exception(why);}
 boolean matchingOffset()throws Exception{File base=new File(view.getContext().getFilesDir(),"home/.trough");JSONObject meta=new JSONObject(new String(Payloads.readSmall(new File(base,view.expanded?"half-sword-expanded-scene.json":"half-sword-preview-scene.json")),"UTF-8"));JSONObject runtime=meta.getJSONObject("runtime");if(meta.getLong("captured_ms")<captureAfter){view.captureScene();return false;}return "rest".equals(runtime.optString("pose_mode"))&&runtime.optLong("target_offset_applied",-1)==view.targetOffsetApplied&&Math.abs(runtime.optDouble("target_offset_x",Double.NaN)-view.targetAppliedX)<1e-6;}
 void copy(String name)throws Exception{File base=new File(view.getContext().getFilesDir(),"home/.trough");try(FileInputStream in=new FileInputStream(new File(base,"half-sword-aim-held.png"));FileOutputStream out=new FileOutputStream(new File(base,name))){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}}
 void verifyPixels()throws Exception{
  File base=new File(view.getContext().getFilesDir(),"home/.trough");
  android.graphics.Bitmap before=null,moved=null,restored=null;
  try{
   before=android.graphics.BitmapFactory.decodeFile(new File(base,"half-sword-target-offset-before.png").getPath());
   moved=android.graphics.BitmapFactory.decodeFile(new File(base,"half-sword-target-offset-moved.png").getPath());
   restored=android.graphics.BitmapFactory.decodeFile(new File(base,"half-sword-target-offset-restored.png").getPath());
   need(before!=null&&moved!=null&&restored!=null,"Target capture decode failed");
   int w=before.getWidth(),h=before.getHeight();
   need(moved.getWidth()==w&&restored.getWidth()==w&&moved.getHeight()==h&&restored.getHeight()==h,"Target capture dimensions changed");
   int[] a=new int[w*h],b=new int[w*h],c=new int[w*h];
   before.getPixels(a,0,w,0,0,w,h);moved.getPixels(b,0,w,0,0,w,h);restored.getPixels(c,0,w,0,0,w,h);
   changedPixels=0;restoredPixels=true;
   for(int i=0;i<a.length;i++){if(a[i]!=b[i])changedPixels++;if(a[i]!=c[i])restoredPixels=false;}
   need(changedPixels>0,"Target movement missing from GPU capture");
   need(restoredPixels,"Zero target offset did not restore GPU image");
  }finally{if(before!=null)before.recycle();if(moved!=null)moved.recycle();if(restored!=null)restored.recycle();}
 }
 public void run(){try{need(SystemClock.uptimeMillis()-began<18000,"Target offset timeout");need(view.isAttachedToWindow()&&view.renderVisible,"Scene unavailable");switch(stage){
 case 0:if(view.characterRunning)input.click("Run / pause");input.click("Training view");input.click("Reset physics");stage=1;break;
 case 1:if(view.resetPending())break;steps=view.characterSteps;captureAfter=System.currentTimeMillis()+400;input.pausedSteps=-1;stage=2;break;
 case 2:if(!input.pausedScene(true)||!matchingOffset())break;copy("half-sword-target-offset-before.png");synchronized(view.signal){view.targetOffsetGuardCheck=true;}need(view.requestTargetOffset(.1f,0,0),"Valid request rejected");request=view.targetOffsetRequested;captureAfter=System.currentTimeMillis()+400;input.pausedSteps=-1;stage=3;break;
 case 3:if(view.targetOffsetApplied!=request)break;need(Math.abs(view.targetAppliedX-.1)<1e-6&&view.targetOffsetGuardsPassed,"Render target offset missing");guards=true;need(Math.abs(view.targetConfig.getDouble(56)-(HalfSwordWeapon.TARGET_MIN[0]+.1))<1e-6&&Math.abs(view.targetConfig.getDouble(80)-(HalfSwordWeapon.TARGET_MAX[0]+.1))<1e-6,"Query did not move with target");query=true;need(!view.requestTargetOffset(Float.NaN,0,0)&&!view.requestTargetOffset(.3f,0,0)&&view.targetOffsetRequested==request,"Invalid request changed state");if(!input.pausedScene(true)||!matchingOffset())break;copy("half-sword-target-offset-moved.png");need(view.requestTargetOffset(0,0,0),"Zero request rejected");request=view.targetOffsetRequested;captureAfter=System.currentTimeMillis()+400;input.pausedSteps=-1;stage=4;break;
 case 4:if(view.targetOffsetApplied!=request)break;need(view.targetAppliedX==0&&view.targetConfig.getDouble(56)==HalfSwordWeapon.TARGET_MIN[0],"Zero failed to restore target");if(!input.pausedScene(true)||!matchingOffset())break;copy("half-sword-target-offset-restored.png");verifyPixels();need(view.characterSteps==steps&&!view.characterRunning,"Target offset advanced physics");finish(null);return;
 }ui.postDelayed(this,100);}catch(Throwable e){view.characterRunning=false;view.cancelAllTouchInputs();finish(e.toString());}}
 void finish(String error){try{JSONObject out=new JSONObject();out.put("passed",error==null).put("error",error==null?"":error).put("stage",stage).put("native_and_request_guards",guards).put("query_matches_render_offset",query).put("physics_unchanged",view.characterSteps==steps).put("changed_pixels",changedPixels).put("restored_pixels_exact",restoredPixels).put("restored_x",view.targetAppliedX).put("module",HalfSwordBridge.loadedModuleSha256).put("time_ms",System.currentTimeMillis());Payloads.writeSmall(new File(view.getContext().getFilesDir(),"home/.trough/half-sword-target-offset-check-result.json"),out.toString());}catch(Exception e){android.util.Log.e("Trough","Target offset receipt",e);}}
}
