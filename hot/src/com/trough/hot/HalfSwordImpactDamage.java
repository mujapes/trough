package com.trough.hot;
/** Prototype health from signed blade/torso gap closing speed, not recovered damage.
 * Metres per fixed physics step. First/invalid/teleport samples cannot injure.
 * A touch consumes the armed contact even when too slow, preventing resting grind.
 */
final class HalfSwordImpactDamage {
 int health=100,hits,lastDamage,clearFrames,cooldown;double previousGap;boolean ready,armed;
 void reset(){health=100;hits=lastDamage=clearFrames=cooldown=0;previousGap=0;ready=armed=false;}
 boolean step(boolean valid,boolean touching,double gap){
  if(cooldown>0)cooldown--;
  if(!valid||!Double.isFinite(gap)){ready=armed=false;clearFrames=0;return false;}
  // Successful native no-candidate output uses DBL_MAX outside the2cm cull.
  // It is clear space for rearm, but never a usable speed sample.
  if(!touching&&gap==Double.MAX_VALUE){ready=false;if(clearFrames<6)clearFrames++;if(clearFrames>=6&&cooldown==0)armed=true;return false;}
  double closing=ready?(previousGap-gap)/HalfSwordStepPolicy.SECONDS:0;boolean sampled=ready;previousGap=gap;ready=true;
  if(!touching){if(clearFrames<6)clearFrames++;if(clearFrames>=6&&cooldown==0)armed=true;return false;}
  clearFrames=0;boolean strike=armed;armed=false;
  if(!strike||!sampled||health==0||!Double.isFinite(closing)||closing<=.35||closing>20)return false;
  int damage=Math.min(30,Math.max(1,(int)Math.round((closing-.35)*12.)));
  lastDamage=Math.min(health,damage);health-=lastDamage;if(hits<Integer.MAX_VALUE)hits++;cooldown=30;return true;
 }
}
