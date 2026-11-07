package com.trough.hot;
/** Optional practice with original cooldown fragment; supplied scene state and RNG are approximate, not full fighting AI. */
final class HalfSwordOpponentDrill {
 int remaining=60;long policyStarts,readinessBlocked;double lastSeed,lastCooldownSeconds;private long randomState=0x4853574f52444cL;
 void reset(){remaining=60;}
 boolean stepRecovered(boolean enabled,boolean swinging,double distanceCm,boolean ready){
  if(!enabled){reset();return false;}
  if(!ready){readinessBlocked++;return false;}
  if(!Double.isFinite(distanceCm)||distanceCm<40||distanceCm>160){remaining=Math.max(remaining,60);return false;}
  if(remaining>0)remaining--;if(remaining>0||swinging)return false;
  // Original cooldown fragment is Random Float Seed *3 seconds. Seed sampling
  // here is a deterministic prototype boundary [.2,1), not recovered AI RNG.
  randomState=(randomState*25214903917L+11)&((1L<<48)-1);lastSeed=.2+.8*(randomState/(double)(1L<<48));lastCooldownSeconds=(double)(float)(lastSeed*3);
  remaining=(int)Math.ceil(lastCooldownSeconds/HalfSwordStepPolicy.SECONDS);policyStarts++;return true;
 }
 boolean step(boolean enabled,boolean swinging,double distanceCm){
  if(!enabled){reset();return false;}
  if(!Double.isFinite(distanceCm)||distanceCm<90||distanceCm>160){remaining=Math.max(remaining,60);return false;}
  if(remaining>0)remaining--;
  if(remaining>0||swinging)return false;
  remaining=180;return true;
 }
}
