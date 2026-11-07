package com.trough.hot;
/** Standalone authored history/reset profile. No contact, health, UI or world ownership.
 * Sequence numbers order explicit admission/request/latent-tick commands, not physics frames.
 */
final class HalfSwordDamageHistory {
 static final float DURATION=.2f;
 static final class Snapshot {
  final double last,sustained;final String bone;final boolean lastPending,sustainedPending;
  final float lastRemaining,sustainedRemaining;
  Snapshot(double l,double s,String b,boolean lp,float lr,boolean sp,float sr){last=l;sustained=s;bone=b;lastPending=lp;lastRemaining=lr;sustainedPending=sp;sustainedRemaining=sr;}
 }
 private Snapshot state;private int epoch=-1,sequence=-1;
 void reset(int e,double last,double sustained,String bone){finite(last);finite(sustained);if(e<0||bone==null)throw new IllegalArgumentException("Missing history owner");state=new Snapshot(last,sustained,bone,false,0,false,0);epoch=e;sequence=-1;}
 Snapshot snapshot(){if(state==null)throw new IllegalStateException("No history owner");return state;}
 boolean admit(int e,int seq,double scaled,String bone,boolean inside){
  guard(e,seq);finite(scaled);if(bone==null)throw new IllegalArgumentException("Missing bone");
  boolean accepted=!bone.equals(state.bone)||scaled>state.last;
  if(accepted){double sustained=state.sustained+(inside?0:scaled);finite(sustained);state=new Snapshot(scaled,sustained,bone,state.lastPending,state.lastRemaining,true,DURATION);}
  sequence=seq;return accepted;
 }
 void requestLastReset(int e,int seq){guard(e,seq);Snapshot s=state;state=new Snapshot(s.last,s.sustained,s.bone,true,s.lastPending?s.lastRemaining:DURATION,s.sustainedPending,s.sustainedRemaining);sequence=seq;}
 void tick(int e,int seq,double delta){
  guard(e,seq);finite(delta);float dt=(float)delta;if(delta<0||Float.isInfinite(dt))throw new IllegalArgumentException("Invalid latent delta");
  Snapshot s=state;float lr=s.lastPending?s.lastRemaining-dt:0,sr=s.sustainedPending?s.sustainedRemaining-dt:0;
  boolean lp=s.lastPending&&lr>0,sp=s.sustainedPending&&sr>0;
  state=new Snapshot(s.lastPending&&!lp?0:s.last,s.sustainedPending&&!sp?0:s.sustained,s.bone,lp,lp?lr:0,sp,sp?sr:0);sequence=seq;
 }
 private void guard(int e,int seq){if(state==null||e!=epoch||seq<0||seq<=sequence)throw new IllegalArgumentException("Stale history command");}
 private static void finite(double v){if(!Double.isFinite(v))throw new IllegalArgumentException("Nonfinite history value");}
}
