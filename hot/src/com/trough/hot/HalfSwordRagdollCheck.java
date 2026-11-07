package com.trough.hot;
import android.os.*;import android.graphics.Rect;import android.widget.Button;import java.io.*;import org.json.*;
/** Explicit bounded relax/fall/reset interaction; reset is not an animated get-up. */
final class HalfSwordRagdollCheck implements Runnable {
 final HalfSwordView view;final HalfSwordAimCheck input;final boolean split;final Handler ui=new Handler(Looper.getMainLooper());final long began=SystemClock.uptimeMillis();int stage,steps;long motor;JSONObject standing,fallen,recovered;boolean controlsVisible,noReset,armSuppressed;
 HalfSwordRagdollCheck(HalfSwordView v,boolean small){view=v;split=small;input=new HalfSwordAimCheck(v);}
 void start(){ui.postDelayed(this,150);}void need(boolean yes,String why)throws Exception{if(!yes)throw new Exception(why);}
 public void run(){try{need(SystemClock.uptimeMillis()-began<22000,"Ragdoll timeout");need(view.isAttachedToWindow()&&view.renderVisible&&!view.characterFailed,"Scene unavailable");
 switch(stage){
 case 0:if(split){Button b=HalfSwordViewCheck.button(view.controlsRoot(),"Split view");if(b!=null)need(b.performClick(),"Split rejected");}for(String name:new String[]{"Physics step","Compliant hold · prototype","Release sword","Relax ragdoll"}){Button b=HalfSwordViewCheck.button(view.controlsRoot(),name);Rect r=new Rect();need(b!=null&&b.isShown()&&b.getGlobalVisibleRect(r)&&r.width()>=HalfSwordView.dp(view.getContext(),48)&&r.height()>=HalfSwordView.dp(view.getContext(),36),"Control clipped: "+name);}controlsVisible=true;input.click("Reset physics");stage=1;break;
 case 1:if(view.resetPending())break;need(view.requestCharacterStep(),"Initial step rejected");stage=2;break;
 case 2:if(view.characterSteps<1||view.characterBusy)break;if(!view.weaponHoldEnabled)input.click("Compliant hold · prototype");input.click("Run / pause");steps=view.characterSteps;stage=3;break;
 case 3:if(view.characterSteps-steps<60)break;standing=input.observation();input.click("Run / pause");input.pausedSteps=-1;stage=4;break;
 case 4:if(!input.pausedScene(false))break;steps=view.characterSteps;motor=view.characterMotorFrames;input.click("Relax ragdoll");stage=5;break;
 case 5:if(view.characterBusy||!view.ragdollRelaxApplied)break;need(view.characterSteps==steps+1&&!view.characterRunning&&!view.resetPending(),"Relax reset state or ran automatically");noReset=true;view.requestAim(30,30);stage=6;break;
 case 6:if(view.characterBusy||view.characterSteps<steps+2)break;need(view.characterMotorFrames==motor,"Relaxed aim reactivated motor");armSuppressed=true;input.click("Run / pause");steps=view.characterSteps;stage=7;break;
 case 7:if(view.characterSteps-steps<60)break;fallen=view.characterObservation();need(fallen.getBoolean("finite")&&fallen.getJSONArray("pelvis_cm").getDouble(2)<standing.getJSONArray("pelvis_cm").getDouble(2)-20,"Relax did not fall");input.click("Run / pause");input.pausedSteps=-1;stage=8;break;
 case 8:if(!input.pausedScene(true))break;File base=new File(view.getContext().getFilesDir(),"home/.trough");try(FileInputStream in=new FileInputStream(new File(base,"half-sword-aim-held.png"));FileOutputStream out=new FileOutputStream(new File(base,"half-sword-ragdoll-fallen.png"))){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}input.click("Reset physics");stage=9;break;
 case 9:if(view.resetPending())break;need(!view.ragdollRelaxRequested&&!view.ragdollRelaxApplied,"Reset retained relax state");input.click("Run / pause");steps=view.characterSteps;stage=10;break;
 case 10:if(view.characterSteps-steps<60)break;recovered=input.observation();need(view.characterAppliedHoldStrength==8&&view.weaponHeld&&!view.weaponReleased,"Reset did not restore supported held sword");input.aim(30,30);motor=view.characterMotorFrames;steps=view.characterSteps;stage=11;break;
 case 11:if(view.characterSteps-steps<60)break;need(view.characterMotorFrames>motor&&view.physicalAimApplied,"Recovered arm unavailable");input.observation();input.click("Run / pause");input.pausedSteps=-1;stage=12;break;
 case 12:if(!input.pausedScene(false))break;finish(null);return;
 }ui.postDelayed(this,100);
 }catch(Throwable error){view.characterRunning=false;view.cancelAllTouchInputs();finish(error.toString());}}
 void finish(String error){try{JSONObject out=new JSONObject();out.put("passed",error==null).put("error",error==null?"":error).put("stage",stage).put("split_requested",split).put("controls_visible",controlsVisible).put("relax_without_reset",noReset).put("relaxed_arm_suppressed",armSuppressed).put("standing",standing).put("fallen",fallen).put("recovered",recovered).put("module",HalfSwordBridge.loadedModuleSha256).put("time_ms",System.currentTimeMillis());Payloads.writeSmall(new File(view.getContext().getFilesDir(),"home/.trough/half-sword-ragdoll-check-result.json"),out.toString());}catch(Exception e){android.util.Log.e("Trough","Ragdoll receipt failed",e);}}
}
