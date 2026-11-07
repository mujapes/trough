package com.trough.hot;
/** Contact episode scheduling is approximate; injury arithmetic is recovered.
 * Caller supplies observed UE cm/s velocity and explicit material/entry/root boundaries.
 * No solver impulses, bleeding, death, severing, or inferred material defaults.
 */
final class HalfSwordRecoveredDamage {
 final HalfSwordRecoveredInjury.State state=new HalfSwordRecoveredInjury.State();
 private int epoch=-1,step=-1,clearFrames,cooldown;
 private boolean armed;int hits,rejected;String reason="reset";
 final int[] hitsByPart=new int[8];String appliedBone;HalfSwordRecoveredInjury.Result last,lastApplied;int appliedStep=-1;double appliedVx,appliedVy,appliedVz;
 HalfSwordRecoveredDamage(){reset();}
 void reset(){state.health=100;state.tonus=100;state.limbRate=state.overallRate=1;state.dress=state.fallen=false;for(int i=0;i<8;i++)state.region[i]=100;epoch=step=-1;clearFrames=cooldown=hits=rejected=0;armed=false;last=lastApplied=null;appliedStep=-1;appliedBone=null;for(int i=0;i<8;i++)hitsByPart[i]=0;reason="reset";}
 boolean step(int currentEpoch,int currentStep,boolean queryValid,boolean touching,boolean velocityValid,double[] velocity,HalfSwordRecoveredInjury.Hit boundary){
  if(currentEpoch<0||currentStep<0){armed=false;clearFrames=0;reason="invalid-step";return false;}
  if(currentEpoch==epoch&&currentStep==step){reason="duplicate-step";return false;}
  boolean consecutive=currentEpoch==epoch&&currentStep==step+1;
  epoch=currentEpoch;step=currentStep;
  if(!consecutive){armed=false;clearFrames=0;cooldown=0;reason="warming-or-discontinuous";return false;}
  if(cooldown>0)cooldown--;
  if(!queryValid){armed=false;clearFrames=0;reason="query-invalid";return false;}
  if(!touching){if(clearFrames<6)clearFrames++;if(clearFrames>=6&&cooldown==0)armed=true;reason="clear";return false;}
  clearFrames=0;boolean entry=armed;armed=false;
  if(!entry){reason="contact-consumed";return false;}
  if(!velocityValid||velocity==null||velocity.length!=3||boundary==null){reason="missing-velocity-or-boundary";return false;}
  HalfSwordRecoveredInjury.Hit h=new HalfSwordRecoveredInjury.Hit();h.vx=velocity[0];h.vy=velocity[1];h.vz=velocity[2];h.ix=boundary.ix;h.iy=boundary.iy;h.iz=boundary.iz;h.cut=boundary.cut;h.rigidity=boundary.rigidity;h.stab=boundary.stab;h.defCut=boundary.defCut;h.defStab=boundary.defStab;h.defBlunt=boundary.defBlunt;h.kick=boundary.kick;h.damageRate=boundary.damageRate;h.bone=boundary.bone;h.ignoreInvincibility=boundary.ignoreInvincibility;h.shockwave=boundary.shockwave;h.rootMesh=boundary.rootMesh;h.entryAllowed=boundary.entryAllowed;
  double before=state.health;double[] regions=state.region.clone();
  try{HalfSwordRecoveredInjury.Result result=HalfSwordRecoveredInjury.apply(state,h);last=result;boolean changed=before!=state.health;for(int i=0;i<8;i++)changed|=regions[i]!=state.region[i];if(changed){lastApplied=result;appliedBone=h.bone;hitsByPart[result.part]++;appliedStep=currentStep;appliedVx=h.vx;appliedVy=h.vy;appliedVz=h.vz;hits++;cooldown=30;reason="injury-applied";}else reason="entry-denied-or-ineligible";return changed;}
  catch(IllegalArgumentException bad){rejected++;reason="injury-input-rejected";return false;}
 }
}
