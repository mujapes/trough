package com.trough.hot;
import org.json.*;
/** Live comparison only. Fixed unarmored torso/material inputs are declared assumptions. */
final class HalfSwordInjuryComparison {
 private final HalfSwordRecoveredDamage[] damage={new HalfSwordRecoveredDamage(),new HalfSwordRecoveredDamage()};
 private final HalfSwordCombatShadow[] shadow={new HalfSwordCombatShadow(),new HalfSwordCombatShadow()};
 private final HalfSwordRecoveredInjury.Hit[] boundary={hit(),hit()};
 private static final class Attempt {
  final int epoch,step,part,baselineEpoch,baselineStep;final String bone,shadowReason;
  final double raw,scaled,sourceHealth,vx,vy,vz,baselineHealth;final boolean hpEligible,shadowAdmitted;
  final HalfSwordCombatLifecycle.Snapshot shadowState;
  Attempt(int e,int st,String b,double[] v,HalfSwordRecoveredInjury.Result r,double hp,HalfSwordCombatShadow shadow,long admittedBefore){epoch=e;step=st;bone=b;part=r.part;raw=r.raw;scaled=r.scaled;hpEligible=r.hpEligible;sourceHealth=hp;vx=v[0];vy=v[1];vz=v[2];shadowState=shadow.snapshot();shadowAdmitted=shadow.admitted>admittedBefore;shadowReason=shadow.reason;baselineEpoch=shadow.baselineEpoch;baselineStep=shadow.baselineStep;baselineHealth=shadow.baselineHealth;}
 }
 private final Attempt[] lastAttempt=new Attempt[2];
 private long samples,validMotion,touchSamples,updated;private JSONObject previous,lastInjuryEpisode;
 private static HalfSwordRecoveredInjury.Hit hit(){HalfSwordRecoveredInjury.Hit h=new HalfSwordRecoveredInjury.Hit();h.cut=.5;h.rigidity=10;h.damageRate=1;h.rootMesh=h.entryAllowed=true;h.bone=HalfSwordBodyTarget.BONE;return h;}
 synchronized HalfSwordInjuryMotorScale.Observation injuryMotorAt(int side,int epoch,int completedStep){
  return side<0||side>=shadow.length?null:shadow[side].injuryMotorAt(epoch,completedStep);
 }
 synchronized HalfSwordPainTick.State conditionalPainAt(int side,int expectedEpoch,int expectedCompletedStep){
  if(side<0||side>=shadow.length)return null;
  return shadow[side].conditionalPainAt(expectedEpoch,expectedCompletedStep);
 }
 synchronized void sample(int side,int epoch,int step,boolean queryValid,boolean touching,boolean velocityValid,double[] velocity){
  sampleBone(side,epoch,step,queryValid,touching,velocityValid,velocity,HalfSwordBodyTarget.BONE);
 }
 synchronized void sampleAnatomical(int side,int epoch,int step,boolean torsoValid,boolean torsoTouch,double torsoGap,boolean torsoMotion,double[] torsoVelocity,HalfSwordHeadContactObservation head){
  int selected=HalfSwordAnatomicalContact.select(torsoValid,torsoTouch,torsoGap,head.queryValid[side],head.touchingNow[side],head.gap[side]);
  sampleBone(side,epoch,step,selected>=0,torsoTouch||head.touchingNow[side],selected==1?head.motionValid[side]:torsoMotion,selected==1?head.velocities[side]:torsoVelocity,selected==1?"head":HalfSwordBodyTarget.BONE);
 }
 private void sampleBone(int side,int epoch,int step,boolean queryValid,boolean touching,boolean velocityValid,double[] velocity,String bone){
  boundary[side].bone=bone;HalfSwordCombatShadow.Baseline preHit=queryValid&&touching&&velocityValid&&shadow[side].requiresBaseline(epoch,step)?HalfSwordCombatShadow.captureBaseline(damage[side].state):null;HalfSwordRecoveredInjury.Result previousResult=damage[side].last;damage[side].step(epoch,step,queryValid,touching,velocityValid,velocity,boundary[side]);boolean sourceEpisode=damage[side].last!=previousResult&&damage[side].last!=null&&damage[side].last.entered;long admittedBefore=shadow[side].admitted;shadow[side].sample(epoch,step,queryValid,sourceEpisode,velocity,boundary[side],preHit,HalfSwordStepPolicy.SECONDS);if(sourceEpisode)lastAttempt[side]=new Attempt(epoch,step,bone,velocity,damage[side].last,damage[side].state.health,shadow[side],admittedBefore);samples++;if(velocityValid)validMotion++;if(touching&&velocityValid)touchSamples++;updated=System.currentTimeMillis();
 }
 synchronized void reset(){try{if(samples>0){previous=current();if(damage[0].hits>0||damage[1].hits>0)lastInjuryEpisode=current();}}catch(JSONException ignored){}for(HalfSwordRecoveredDamage d:damage)d.reset();for(HalfSwordCombatShadow s:shadow)s.reset();lastAttempt[0]=lastAttempt[1]=null;samples=validMotion=touchSamples=0;updated=System.currentTimeMillis();}
 private JSONObject side(int index)throws JSONException{HalfSwordRecoveredDamage d=damage[index];JSONObject o=new JSONObject().put("health",d.state.health).put("region_health",new JSONArray(d.state.region)).put("hits",d.hits).put("hits_by_part",new JSONArray(d.hitsByPart)).put("rejected",d.rejected).put("reason",d.reason);if(d.lastApplied!=null)o.put("last_applied",new JSONObject().put("bone",d.appliedBone).put("part",d.lastApplied.part).put("step",d.appliedStep).put("velocity_ue_cm_s",new JSONArray(new double[]{d.appliedVx,d.appliedVy,d.appliedVz})).put("raw",d.lastApplied.raw).put("scaled",d.lastApplied.scaled).put("hp_eligible",d.lastApplied.hpEligible).put("health_after",d.lastApplied.healthAfter).put("region_after",d.lastApplied.regionAfter));if(d.last!=null)o.put("raw",d.last.raw).put("scaled",d.last.scaled).put("hp_eligible",d.last.hpEligible).put("part",d.last.part);return o;}
 private JSONObject attemptObservation(Attempt a)throws JSONException{
  JSONObject o=new JSONObject().put("epoch",a.epoch).put("step",a.step).put("bone",a.bone).put("part",a.part).put("velocity_ue_cm_s",new JSONArray(new double[]{a.vx,a.vy,a.vz})).put("source_raw",a.raw).put("source_scaled",a.scaled).put("source_hp_eligible",a.hpEligible).put("source_health",a.sourceHealth).put("shadow_admitted",a.shadowAdmitted).put("shadow_reason",a.shadowReason);
  if(a.shadowState!=null)o.put("shadow_health",a.shadowState.health).put("shadow_pain",a.shadowState.pain).put("health_difference",a.shadowState.health-a.sourceHealth).put("baseline_epoch",a.baselineEpoch).put("baseline_step",a.baselineStep).put("baseline_health",a.baselineHealth);return o;
 }
 private JSONObject shadowSide(int index)throws JSONException{
  HalfSwordCombatShadow s=shadow[index];HalfSwordCombatLifecycle.Snapshot state=s.snapshot();
  JSONObject o=new JSONObject().put("samples",s.samples).put("source_episodes",s.episodes).put("admitted",s.admitted).put("rejected",s.rejected).put("resets",s.resets).put("reason",s.reason).put("total_ns",s.totalNanos).put("max_ns",s.maxNanos);
  if(lastAttempt[index]!=null)o.put("last_attempt",attemptObservation(lastAttempt[index]));
  if(state!=null)o.put("baseline_health",s.baselineHealth).put("baseline_epoch",s.baselineEpoch).put("baseline_step",s.baselineStep).put("shadow_health",state.health).put("shadow_pain",state.pain);
  HalfSwordPainTick.State conditional=s.conditionalPainSnapshot();if(conditional!=null)o.put("conditional_pain",new JSONObject().put("pain_after_delivered_hit",conditional.pain).put("shock_before_current_hit",conditional.shock).put("regional_pain",new JSONArray(conditional.regions())).put("body_part",conditional.bodyPart).put("profile","assumed_unfractured_unsevered_present_bones_zero_initial_pain").put("timing","decision_then_regions_then_aggregation_before_current_hit; hit_delta_added_once_afterward").put("phase_steps",s.conditionalPhaseSteps).put("shocked_steps",s.conditionalShockedSteps).put("transition_count",s.conditionalTransitions).put("first_transition_epoch",s.conditionalFirstTransitionEpoch).put("first_transition_step",s.conditionalFirstTransitionStep).put("last_transition_epoch",s.conditionalLastTransitionEpoch).put("last_transition_step",s.conditionalLastTransitionStep).put("peak_pain",s.conditionalPeakPain).put("trace_scope","since_explicit_reset; query_gaps_restart_assumed_baseline_without_synthetic_transition").put("authority","diagnostic_only_not_AI_readiness"));return o;
 }
 private JSONObject shadowObservation()throws JSONException{return new JSONObject().put("mode","effect_free_entered_contact_attempt_subset").put("opponent",shadowSide(0)).put("player",shadowSide(1)).put("world_delta_seconds",HalfSwordStepPolicy.SECONDS).put("limits","Copied observed training injury state immediately before shadow activation; pain baseline0 is unobserved, tone/death paths withheld; Entered contact attempts, including HP-ineligible attempts; contact-clear/cooldown inherited. Prototype physics step substitutes world delta; tone/death paths withheld. Diagnostics never replace health/HUD/forces or dispatch effects.");}
 private JSONObject current()throws JSONException{return new JSONObject().put("time_ms",updated).put("samples",samples).put("valid_motion",validMotion).put("touch_motion_samples",touchSamples).put("opponent",side(0)).put("player",side(1)).put("combat_shadow",shadowObservation());}
 synchronized boolean applyToHud(HalfSwordImpactDamage opponent,HalfSwordImpactDamage player){boolean changed=false;HalfSwordImpactDamage[] targets={opponent,player};for(int i=0;i<2;i++){HalfSwordImpactDamage target=targets[i];int next=(int)Math.ceil(damage[i].state.health);changed|=next!=target.health||damage[i].hits!=target.hits;target.lastDamage=Math.max(0,target.health-next);target.health=next;target.hits=damage[i].hits;}return changed;}
 synchronized JSONObject observation(boolean applied)throws JSONException{return new JSONObject().put("mode",applied?"recovered_injury_training_health_no_knockout":"comparison_only_gameplay_health_unchanged").put("current",current()).put("last_injury_episode",lastInjuryEpisode==null?JSONObject.NULL:lastInjuryEpisode).put("previous_episode",previous==null?JSONObject.NULL:previous).put("assumptions","Unarmored authored head/spine_03 capsules in training, torso-only comparison when disabled; deeper signed gap selects touching region, torso wins ties, both queries required to rearm; cut=.5, rigidity=10, damageRate=1, rootMesh/entry=true, defenses/stab/kick=0; fixed contact-clear6/cooldown30 scheduling. These materials and entry decisions are approximations.").put("velocity_source","Guarded same-material-point relative capsule motion, UE cm/s; not original hit sampling").put("limits","Recovered injury arithmetic only; original invincibility/scheduling, death/bleed/severing/round outcome remain unresolved.");}
}
