package com.trough.hot;
import org.json.JSONObject;
/** Observe existing prototype force; never writes solver state or applies actions. */
final class HalfSwordWeaponReleaseShadow {
 private final HalfSwordWeaponRelease.Input input=new HalfSwordWeaponRelease.Input();
 private int epoch=-1,frame=-1;private long samples,rejected,previousSamples;private double previousPeak;private double rawForce,peakForce,threshold;private int action;private boolean active;
 synchronized void sample(int currentEpoch,int currentFrame,boolean held,boolean released,double x,double y,double z,double dt){
  if(currentEpoch!=epoch||currentFrame<frame){if(samples>0){previousSamples=samples;previousPeak=peakForce;}input.releaseRate=0;peakForce=0;samples=0;frame=-1;epoch=currentEpoch;}
  if(currentFrame==frame)return;frame=currentFrame;
  if(!held||released){input.releaseRate=0;action=0;active=false;return;}
  double magnitude=Math.hypot(Math.hypot(x,y),z);
  if(Double.isNaN(magnitude)||Double.isInfinite(magnitude)||Double.isNaN(dt)||Double.isInfinite(dt)||dt<=0||dt>.05){rejected++;active=false;input.releaseRate=0;action=0;return;}
  rawForce=magnitude;peakForce=Math.max(peakForce,magnitude);
  // Prototype reaction path already multiplies by100. This mapping is an assumption,
  // not recovery of GetConstraintForce units. The original inputs below are absent.
  input.left=false;input.gripType=1;input.weaponConstraintValid=true;input.grabConstraintValid=false;
  input.armTonus=50;input.bodyTonus=1;input.armHealth=50;input.consciousness=100;input.constraintRate=1;
  input.softNotAttached=true;input.forceMagnitude=magnitude*100;input.dt=dt;
  if(Double.isInfinite(input.forceMagnitude)){rejected++;active=false;input.releaseRate=0;action=0;return;}
  HalfSwordWeaponRelease.Result result=HalfSwordWeaponRelease.update(input);input.releaseRate=result.releaseRate;threshold=result.threshold;action=result.action;samples++;active=true;
 }
 synchronized void restartDecision(){input.releaseRate=0;action=0;frame=-1;active=false;}
 synchronized boolean requestsSetupDrop(){return active&&input.releaseRate>=1&&action==HalfSwordWeaponRelease.SETUP_RIGHT;}
 synchronized JSONObject observation()throws org.json.JSONException{
  return new JSONObject().put("mode","shadow_only_no_actions_applied").put("epoch",epoch).put("frame",frame).put("previous_episode_samples",previousSamples).put("previous_episode_peak_force",previousPeak).put("samples",samples).put("rejected",rejected).put("active",active).put("prototype_hand_force",rawForce).put("prototype_peak_force",peakForce).put("assumed_force_scale",100).put("threshold_assumed_units",threshold).put("release_rate",input.releaseRate).put("requested_action_code",action).put("force_source","Existing hs_weapon_grip_output.hand_force; not original GetConstraintForce").put("assumptions","Right grip1; armTonus50/bodyTonus1/health50/consciousness100/softNotAttached=true; force*100 follows existing prototype reaction scaling, not verified engine units").put("missing_original_inputs","Original constraint force, live grip type, arm/body tonus, consciousness, arm health and attachment flags");
 }
}
