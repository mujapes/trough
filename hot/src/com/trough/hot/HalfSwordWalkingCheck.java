package com.trough.hot;
import android.os.*;import android.widget.Button;import java.io.File;import org.json.JSONObject;
/** Bounded actual native-control test; deferred until the scene is visible. */
final class HalfSwordWalkingCheck implements Runnable {
 final HalfSwordView view;final Handler ui=new Handler(Looper.getMainLooper());final long began=SystemClock.uptimeMillis();int stage;double startX,startY,leftProgress,rightProgress;boolean leftComplete,rightComplete,relaxRecovery;double minimumPelvis=Double.POSITIVE_INFINITY;int samples;long walkRequestedAt;String lastState="";final org.json.JSONArray stateChanges=new org.json.JSONArray();
 HalfSwordWalkingCheck(HalfSwordView v){view=v;}
 void click(String label)throws Exception{Button button=HalfSwordViewCheck.button(view.controlsRoot(),label);if(button==null||!button.isEnabled())throw new Exception("Missing walking control: "+label);button.performClick();if(label.startsWith("Walk four steps")||label.equals("Walking test"))walkRequestedAt=SystemClock.uptimeMillis();}
 void start(){ui.post(this);}
 void finish(boolean passed,String error){view.requestWalkingCancel();try{JSONObject result=new JSONObject().put("version",7).put("relax_recovery",relaxRecovery).put("primary_controls",true).put("state_changes",stateChanges).put("left_complete",leftComplete).put("right_complete",rightComplete).put("left_progress_cm",leftProgress).put("right_progress_cm",rightProgress).put("passed",passed).put("error",error).put("stage",stage).put("samples",samples).put("walking_frame",view.walkingSession.frame).put("minimum_sampled_pelvis_cm",Double.isFinite(minimumPelvis)?minimumPelvis:JSONObject.NULL).put("observation",view.characterObservation()).put("module",HalfSwordBridge.loadedModuleSha256).put("time_ms",System.currentTimeMillis()).put("scope","Actual scene controls and sampled physics; rendered motion and whole-game performance require separate review");Payloads.writeSmall(new File(view.getContext().getFilesDir(),"home/.trough/half-sword-walking-check-result.json"),result.toString());}catch(Exception ignored){}}
 public void run(){try{
  if(!view.isAttachedToWindow())throw new Exception("Walking scene became detached");
  if(SystemClock.uptimeMillis()-began>60000)throw new Exception("Walking scene check timed out");
  if(!view.renderVisible){ui.postDelayed(this,100);return;}
  if(view.characterFailed)throw new Exception("Character physics failed");
  if(stage==0&&HalfSwordBridge.loadedModuleSha256.length()==0){ui.postDelayed(this,100);return;}
  String state=view.walkingSession.state+"/active="+view.walkingSession.active+"/ready="+view.walkingSession.ready;
  if(!state.equals(lastState)){lastState=state;if(stateChanges.length()<16)stateChanges.put(new JSONObject().put("elapsed_ms",SystemClock.uptimeMillis()-began).put("state",state).put("walking_frame",view.walkingSession.frame).put("physics_steps",view.characterSteps));}
  if((stage==2||stage==4)&&!view.walkingSession.active&&view.walkingSession.frame!=714&&SystemClock.uptimeMillis()-walkRequestedAt>1500)throw new Exception("Walking stopped before completion: "+state+" frame="+view.walkingSession.frame);

  if(stage==0){if(!HalfSwordWalkSession.supported())throw new Exception("Walking candidate is not resident");click("Walking test");stage=1;}
  else if(stage==1){if(view.walkingSession.ready&&!view.walkingSettling&&!view.characterRunning&&view.characterSteps>=180){JSONObject o=view.characterObservation();if(!o.optBoolean("finite"))throw new Exception("Nonfinite settled pose");startX=o.getJSONArray("pelvis_cm").getDouble(0);startY=o.getJSONArray("pelvis_cm").getDouble(1);click("Walking test");stage=2;}}
  else if(stage==2){JSONObject o=view.characterObservation();if(!o.optBoolean("finite"))throw new Exception("Nonfinite walking pose");minimumPelvis=Math.min(minimumPelvis,o.getJSONArray("pelvis_cm").getDouble(2));samples++;if(minimumPelvis<70)throw new Exception("Walking pelvis fell below 70cm");if(!view.walkingSession.active&&view.walkingSession.frame==714&&!view.characterRunning){double x=o.getJSONArray("pelvis_cm").getDouble(0)-startX,y=o.getJSONArray("pelvis_cm").getDouble(1)-startY;leftProgress=Math.hypot(x,y);if(leftProgress<2)throw new Exception("No visible-scale root progress");leftComplete=true;click("Walking test setup · experimental");stage=3;}}
  else if(stage==3){if(view.walkingSession.ready&&!view.walkingSettling&&!view.characterRunning&&view.characterSteps>=180){JSONObject o=view.characterObservation();startX=o.getJSONArray("pelvis_cm").getDouble(0);startY=o.getJSONArray("pelvis_cm").getDouble(1);click("Walk four steps · right first");stage=4;}}
  else if(stage==4){JSONObject o=view.characterObservation();if(!o.optBoolean("finite"))throw new Exception("Nonfinite right-first pose");minimumPelvis=Math.min(minimumPelvis,o.getJSONArray("pelvis_cm").getDouble(2));samples++;if(minimumPelvis<70)throw new Exception("Right-first pelvis fell below 70cm");if(!view.walkingSession.active&&view.walkingSession.frame==714&&!view.characterRunning){rightProgress=Math.hypot(o.getJSONArray("pelvis_cm").getDouble(0)-startX,o.getJSONArray("pelvis_cm").getDouble(1)-startY);if(rightProgress<2)throw new Exception("No right-first root progress");rightComplete=true;click("Walk four steps · left first");stage=5;}}
  else if(stage==5){if(view.walkingSession.active&&view.walkingSession.frame>=20){click("Walking test");stage=6;}}
  else if(stage==6){if(!view.walkingSession.active&&!view.characterRunning){view.requestRelaxRagdoll();stage=7;}}
  else if(stage==7){if(view.ragdollRelaxApplied){if(!"Walk setup".equals(view.walkingActionLabel()))throw new Exception("Relax must offer walking setup");click("Walking test");stage=8;}}
  else if(stage==8){if(view.walkingSession.ready&&!view.walkingSettling&&!view.characterRunning&&view.characterSteps>=180){if(view.ragdollRelaxApplied||view.ragdollRelaxRequested||!"Walk 4".equals(view.walkingActionLabel()))throw new Exception("Walking recovery did not restore ready state");relaxRecovery=true;view.requestPhysicsReset();stage=9;}}
  else if(stage==9){if(!view.resetPending()&&view.characterSteps==0&&!view.walkingSession.ready&&!view.walkingSession.active){finish(true,"");return;}}
  ui.postDelayed(this,50);
 }catch(Exception failure){finish(false,failure.toString());}}
}
