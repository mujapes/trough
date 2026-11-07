package com.trough.hot;
/** Effect-free diagnostic driven ONLY by entered training contact attempts.
 * Contact scheduling is inherited from that source, not recovered original scheduling.
 * World delta is supplied from prototype physics steps. Tone/death paths remain withheld.
 */
final class HalfSwordCombatShadow {
 static final class Baseline {
  private final HalfSwordRecoveredInjury.State injury;
  final double health;
  Baseline(HalfSwordRecoveredInjury.State input){
   if(input==null)throw new IllegalArgumentException("Missing observed baseline");
   HalfSwordRecoveredInjury.State c=new HalfSwordRecoveredInjury.State();
   c.health=input.health;c.tonus=input.tonus;c.overallRate=input.overallRate;c.limbRate=input.limbRate;c.dress=input.dress;c.fallen=input.fallen;
   HalfSwordRecoveredInjury.finite(c.health);HalfSwordRecoveredInjury.finite(c.tonus);HalfSwordRecoveredInjury.finite(c.overallRate);HalfSwordRecoveredInjury.finite(c.limbRate);
   for(int i=0;i<8;i++){HalfSwordRecoveredInjury.finite(input.region[i]);c.region[i]=input.region[i];}injury=c;health=c.health;
  }
 }
 static Baseline captureBaseline(HalfSwordRecoveredInjury.State input){try{return new Baseline(input);}catch(IllegalArgumentException invalid){return null;}}
 boolean requiresBaseline(int e,int step){return actor==null||e!=epoch||step!=lastStep+1;}
 long conditionalPhaseSteps,conditionalShockedSteps,conditionalTransitions;
 int conditionalFirstTransitionEpoch=-1,conditionalFirstTransitionStep=-1,conditionalLastTransitionEpoch=-1,conditionalLastTransitionStep=-1;
 double conditionalPeakPain;
 private HalfSwordPainTick.State conditionalPain;
 HalfSwordPainTick.State conditionalPainSnapshot(){return conditionalPain;}
 // Exact completed-step contract; caller chooses previous step before contact evaluation.
 HalfSwordInjuryMotorScale.Observation injuryMotorAt(int expectedEpoch,int expectedStep){
  if(conditionalPainAt(expectedEpoch,expectedStep)==null)return null;
  return new HalfSwordInjuryMotorScale.Observation(expectedEpoch,expectedStep,actor.snapshot().health,true);
 }
 HalfSwordPainTick.State conditionalPainAt(int expectedEpoch,int expectedCompletedStep){
  return expectedEpoch>=0&&expectedCompletedStep>=0&&epoch==expectedEpoch&&lastStep==expectedCompletedStep&&actor!=null?conditionalPain:null;
 }
 private HalfSwordCombatActor actor;private int epoch=-1,lastStep=-1,sequence;
 double baselineHealth=Double.NaN;int baselineEpoch=-1,baselineStep=-1;
 long samples,episodes,admitted,rejected,resets,totalNanos,maxNanos;String reason="reset";
 void reset(){conditionalPhaseSteps=conditionalShockedSteps=conditionalTransitions=0;conditionalFirstTransitionEpoch=conditionalFirstTransitionStep=conditionalLastTransitionEpoch=conditionalLastTransitionStep=-1;conditionalPeakPain=0;baselineHealth=Double.NaN;baselineEpoch=baselineStep=-1;actor=null;conditionalPain=null;epoch=lastStep=-1;sequence=0;samples=episodes=admitted=rejected=resets=totalNanos=maxNanos=0;reason="reset";}
 HalfSwordCombatLifecycle.Snapshot snapshot(){return actor==null?null:actor.snapshot();}
 void sample(int e,int step,boolean queryValid,boolean sourceEpisode,double[] velocity,HalfSwordRecoveredInjury.Hit boundary,double stepSeconds){sample(e,step,queryValid,sourceEpisode,velocity,boundary,null,stepSeconds);}
 void sample(int e,int step,boolean queryValid,boolean sourceEpisode,double[] velocity,HalfSwordRecoveredInjury.Hit boundary,Baseline baseline,double stepSeconds){
  long start=System.nanoTime();try{observe(e,step,queryValid,sourceEpisode,velocity,boundary,baseline,stepSeconds);}catch(RuntimeException bad){actor=null;conditionalPain=null;sequence=0;rejected++;reason="shadow-error-isolated";}finally{long dt=System.nanoTime()-start;totalNanos+=dt;if(dt>maxNanos)maxNanos=dt;}
 }
 private void observe(int e,int step,boolean queryValid,boolean event,double[] velocity,HalfSwordRecoveredInjury.Hit b,Baseline baseline,double dt){
  if(e<0||step<0||!Double.isFinite(dt)||dt<=0||Float.isInfinite((float)dt)){rejected++;reason="invalid-clock";return;}
  if(e==epoch&&step<=lastStep){rejected++;reason="stale-step";return;}
  boolean consecutive=e==epoch&&step==lastStep+1;
  if(!consecutive||!queryValid){actor=null;conditionalPain=null;sequence=0;resets++;}
  epoch=e;lastStep=step;samples++;
  if(!queryValid){reason="query-gap";return;}
  if(actor!=null){actor.tick(e,sequence++,false,false,dt);
   // Conditional profile: intact, present bones; decision precedes this frame hit.
   boolean priorShock=conditionalPain.shock;conditionalPeakPain=Math.max(conditionalPeakPain,conditionalPain.pain);
   conditionalPain=HalfSwordPainTick.evaluate(conditionalPain,new HalfSwordPainTick.Inputs(actor.injurySnapshot().region,new boolean[4],new boolean[4],false,false,false),dt);
   conditionalPhaseSteps++;if(conditionalPain.shock)conditionalShockedSteps++;
   if(priorShock!=conditionalPain.shock){if(conditionalTransitions==0){conditionalFirstTransitionEpoch=e;conditionalFirstTransitionStep=step;}conditionalTransitions++;conditionalLastTransitionEpoch=e;conditionalLastTransitionStep=step;}
   conditionalPeakPain=Math.max(conditionalPeakPain,conditionalPain.pain);
  } // History only; unknown tone/posture not synthesized.
  if(!event){reason=actor==null?"waiting-for-source-episode":"clock-only";return;}
  episodes++;
  if(velocity==null||velocity.length!=3||b==null){rejected++;reason="missing-hit";return;}
  HalfSwordRecoveredInjury.Hit h=new HalfSwordRecoveredInjury.Hit();h.vx=velocity[0];h.vy=velocity[1];h.vz=velocity[2];h.ix=b.ix;h.iy=b.iy;h.iz=b.iz;h.cut=b.cut;h.rigidity=b.rigidity;h.stab=b.stab;h.defCut=b.defCut;h.defStab=b.defStab;h.defBlunt=b.defBlunt;h.kick=b.kick;h.damageRate=b.damageRate;h.bone=b.bone;h.ignoreInvincibility=b.ignoreInvincibility;h.shockwave=b.shockwave;h.rootMesh=b.rootMesh;h.entryAllowed=b.entryAllowed;
  try{
   double priorPain=actor==null?0:actor.snapshot().pain;
   if(actor==null){if(baseline==null)throw new IllegalArgumentException("Missing observed pre-hit baseline");HalfSwordRecoveredInjury.State s=baseline.injury;HalfSwordCombatActor candidate=new HalfSwordCombatActor();candidate.reset(e,s,0,0,.5,100,false);boolean ok=candidate.hit(e,sequence++,h,false,0,true);actor=candidate;conditionalPain=new HalfSwordPainTick.State(0,0,false,0,new double[8]);baselineHealth=baseline.health;baselineEpoch=e;baselineStep=step;if(ok)admitted++;reason=ok?"shadow-hit":"shadow-denied";}
   else{boolean ok=actor.hit(e,sequence++,h,false,0,true);if(ok)admitted++;reason=ok?"shadow-hit":"shadow-denied";}
   if(conditionalPain!=null){double added=actor.snapshot().pain-priorPain;conditionalPain=new HalfSwordPainTick.State(conditionalPain.pain+added,conditionalPain.threshold,conditionalPain.shock,conditionalPain.bodyPart,conditionalPain.regions());conditionalPeakPain=Math.max(conditionalPeakPain,conditionalPain.pain);}
  }catch(IllegalArgumentException bad){rejected++;reason="unsupported-or-invalid-hit";}
 }
}
