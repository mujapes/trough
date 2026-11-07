package com.trough.hot;
/** Standalone selected head/neck/torso hit profiles. Authoritative state owner, no live engine effects.
 * Inside=false; material/root/entry, lower threshold, socket distance and later route supplied.
 * Fear, reactions, bleeding, full Death and weapon actions remain external.
 */
final class HalfSwordCombatActor {
 private HalfSwordRecoveredInjury.State injury;
 private final HalfSwordDamageHistory history=new HalfSwordDamageHistory();
 private final HalfSwordCombatLifecycle life=new HalfSwordCombatLifecycle();
 private final HalfSwordDeathTimeline deathTimeline=new HalfSwordDeathTimeline();
 private int epoch=-1,sequence=-1,historySequence;private double pain,painNeck,crawl,consciousness;
 private boolean snapped,dead,initialWake;
 void reset(int e,HalfSwordRecoveredInjury.State input,double p,double pn,double cr,double con,boolean wake){
  HalfSwordRecoveredInjury.State next=copy(input);check(p);check(pn);check(cr);check(con);
  HalfSwordCombatLifecycle.Snapshot initial=new HalfSwordCombatLifecycle.Snapshot(next.health,p,next.tonus,cr,con,false,next.fallen,wake,false);
  life.reset(e,initial);deathTimeline.reset();history.reset(e,0,0,"None");injury=next;pain=p;painNeck=pn;crawl=cr;consciousness=con;initialWake=wake;snapped=dead=false;epoch=e;sequence=-1;historySequence=0;
 }
 HalfSwordRecoveredInjury.State injurySnapshot(){return copy(injury);}
 HalfSwordCombatLifecycle.Snapshot snapshot(){return new HalfSwordCombatLifecycle.Snapshot(injury.health,pain,injury.tonus,crawl,consciousness,snapped,injury.fallen,initialWake,dead);}
 HalfSwordDamageHistory.Snapshot historySnapshot(){return history.snapshot();}
 double neckPain(){return painNeck;}
 boolean hit(int e,int seq,HalfSwordRecoveredInjury.Hit h,boolean lowerThreshold,double socketDistanceCm,boolean laterResetReached){
  guard(e,seq);if(dead||h==null||!("neck_01".equals(h.bone)||"neck_02".equals(h.bone)||"head".equals(h.bone)||"spine_03".equals(h.bone)))throw new IllegalArgumentException("Unsupported hit profile");
  check(socketDistanceCm);if(socketDistanceCm<0)throw new IllegalArgumentException("Negative socket distance");
  HalfSwordRecoveredInjury.State proposed=copy(injury);HalfSwordRecoveredInjury.Result result=HalfSwordRecoveredInjury.apply(proposed,h);
  if(!result.entered){sequence=seq;return false;}
  double speed=Math.sqrt(h.vx*h.vx+h.vy*h.vy+h.vz*h.vz),impulse=Math.sqrt(h.ix*h.ix+h.iy*h.iy+h.iz*h.iz);
  boolean impact=speed>1000||impulse>(lowerThreshold?0:1000)||result.scaled>333;
  double add=result.scaled*.001*HalfSwordRecoveredInjury.map(result.cut,0,1000,1,10)*injury.overallRate;
  double nextPain=pain+(h.rootMesh?add:0);check(nextPain);
  boolean snap=result.part==1&&proposed.region[1]<=25&&!snapped&&result.scaled*HalfSwordRecoveredInjury.clamp(1-socketDistanceCm/15,0,1)>proposed.region[1]*100+1000;
  double nextNeckPain=painNeck;if(snap){nextPain+=10;nextNeckPain+=10;check(nextPain);check(nextNeckPain);proposed.health=0;}
  boolean admitted=history.admit(e,historySequence++,result.scaled,h.bone,false);
  sequence=seq;if(!admitted||!impact)return false; // Authored history commits survive later impact denial.
  injury=proposed;pain=nextPain;painNeck=nextNeckPain;snapped|=snap;
  if(laterResetReached)history.requestLastReset(e,historySequence++);return true;
 }
 HalfSwordCombatLifecycle.Transition tick(int e,int seq,boolean painPath,boolean tonePath,double dt){
  guard(e,seq);check(dt);if(dt<0||Float.isInfinite((float)dt))throw new IllegalArgumentException("Invalid supplied world delta");
  HalfSwordCombatLifecycle.Transition next=life.stepFromSnapshot(e,seq,snapshot(),painPath,tonePath,dt);
  history.tick(e,historySequence++,dt);pain=next.state.pain;injury.tonus=next.state.tonus;sequence=seq;return next;
 }
 void acknowledgeDeath(int e){if(e!=epoch)throw new IllegalArgumentException("Wrong actor");life.acknowledgeDeath(e);dead=true;deathTimeline.play();}
 // Caller invokes only after its Death-start boundary has accepted acknowledgement.
 // Component ticks remain independent from character tick availability.
 HalfSwordDeathTimeline.Frame advanceDeathTimeline(int e,int seq,double componentDelta,double worldDelta){
  guard(e,seq);if(!dead)throw new IllegalArgumentException("Death not acknowledged");
  HalfSwordDeathTimeline.Frame frame=deathTimeline.advance(componentDelta,worldDelta,injury.tonus);
  injury.tonus=frame.tonus;sequence=seq;return frame;
 }
 private void guard(int e,int seq){if(injury==null||e!=epoch||seq<0||seq<=sequence)throw new IllegalArgumentException("Stale actor command");}
 private static void check(double v){if(!Double.isFinite(v))throw new IllegalArgumentException("Nonfinite actor value");}
 private static HalfSwordRecoveredInjury.State copy(HalfSwordRecoveredInjury.State s){
  if(s==null)throw new IllegalArgumentException("Missing injury state");
  check(s.health);check(s.tonus);check(s.limbRate);check(s.overallRate);
  HalfSwordRecoveredInjury.State d=new HalfSwordRecoveredInjury.State();d.health=s.health;d.tonus=s.tonus;d.limbRate=s.limbRate;d.overallRate=s.overallRate;d.dress=s.dress;d.fallen=s.fallen;
  for(int i=0;i<8;i++){check(s.region[i]);d.region[i]=s.region[i];}return d;
 }
}
