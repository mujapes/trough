package com.trough.hot;
/** Headless candidate. Original selected tick arithmetic; supplied path/world boundaries.
 * No scene binding, health loss, sound, forces or automatic death effects.
 */
final class HalfSwordCombatLifecycle {
 static final class Snapshot {
  final double health,pain,tonus,crawl,consciousness;
  final boolean snapped,fallen,initialWake,dead;
  Snapshot(double h,double p,double t,double c,double consciousness,boolean n,boolean f,boolean w,boolean d){
   finite(h);finite(p);finite(t);finite(c);finite(consciousness);
   health=h;pain=p;tonus=t;crawl=c;this.consciousness=consciousness;snapped=n;fallen=f;initialWake=w;dead=d;
  }
 }
 static final class Transition {
  final Snapshot state;final boolean deathRequested;
  Transition(Snapshot s,boolean request){state=s;deathRequested=request;}
 }
 private Snapshot state;private int epoch=-1,lastStep=-1;private boolean pendingDeath;
 void reset(int newEpoch,Snapshot initial){
  if(newEpoch<0||initial==null)throw new IllegalArgumentException("Missing actor epoch/state");
  state=copy(initial,initial.pain,initial.tonus,initial.dead);epoch=newEpoch;lastStep=-1;pendingDeath=false;
 }
 Snapshot snapshot(){if(state==null)throw new IllegalStateException("Actor not initialized");return state;}
 Transition step(int suppliedEpoch,int step,boolean painPathReached,boolean tonePathReached,double worldDelta){
  return stepFromSnapshot(suppliedEpoch,step,state,painPathReached,tonePathReached,worldDelta);
 }
 /** Caller supplies the authoritative injury/lifecycle snapshot for this exact step.
  * Return state must be committed by the owner; this candidate never mutates live injury.
  */
 Transition stepFromSnapshot(int suppliedEpoch,int step,Snapshot input,boolean painPathReached,boolean tonePathReached,double worldDelta){
  if(state==null||suppliedEpoch!=epoch||step<0||step<=lastStep)throw new IllegalArgumentException("Stale actor/step");
  if(input==null||state.dead&&!input.dead)throw new IllegalArgumentException("Missing snapshot or death rollback");
  Transition next=evaluate(input,painPathReached,tonePathReached,worldDelta);
  // Atomic handoff: failed validation cannot consume a step or replace the snapshot.
  state=next.state;lastStep=step;pendingDeath=next.deathRequested;return next;
 }
 void acknowledgeDeath(int suppliedEpoch){
  if(state==null||suppliedEpoch!=epoch||!pendingDeath)throw new IllegalArgumentException("No matching death request");
  state=copy(state,state.pain,state.tonus,true);pendingDeath=false;
 }
 static Transition evaluate(Snapshot s,boolean painPathReached,boolean tonePathReached,double dt){
  if(s==null)throw new IllegalArgumentException("Missing snapshot");finite(dt);if(dt<0)throw new IllegalArgumentException("Negative world delta");
  double pain=painPathReached&&s.snapped?0:s.pain,tonus=s.tonus;boolean request=false;
  if(tonePathReached&&!s.dead){
   double healthMapped=100*clamp(s.health/25,0,1);
   double crawlMapped=s.health+(healthMapped-s.health)*clamp(s.crawl,0,1);
   double upper=clamp(crawlMapped,0,100);
   double painBound=clamp(upper,pain*10,upper); // ordered clamp: minimum can exceed maximum
   double target=clamp(s.consciousness,0,painBound);
   tonus=interp(tonus,target,dt,s.initialWake?.1:1.5);
   finite(tonus);request=s.health<=0&&(tonus<=0||s.fallen);
  }
  return new Transition(copy(s,pain,tonus,s.dead),request);
 }
 private static Snapshot copy(Snapshot s,double pain,double tone,boolean dead){return new Snapshot(s.health,pain,tone,s.crawl,s.consciousness,s.snapped,s.fallen,s.initialWake,dead);}
 private static double clamp(double v,double a,double b){return v<a?a:v<b?v:b;}
 private static double interp(double current,double target,double dt,double speed){double dist=target-current;if(speed<=0||dist*dist<1e-8)return target;return current+dist*clamp(dt*speed,0,1);}
 private static void finite(double v){if(!Double.isFinite(v))throw new IllegalArgumentException("Nonfinite lifecycle value");}
}
