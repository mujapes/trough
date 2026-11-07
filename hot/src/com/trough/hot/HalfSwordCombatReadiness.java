package com.trough.hot;
/** Prototype policy adapter, not recovered source logic. Default retains flash hit-stun.
 * Conditional pain is an explicitly assumed diagnostic profile, never authoritative state.
 * Stateless: caller must discard stale/query-gap/reset pain snapshots before evaluation.
 */
final class HalfSwordCombatReadiness {
 static final int FLASH_ONLY=0,FLASH_AND_CONDITIONAL_SHOCK=1;
 static final class Decision {
  final boolean ready,hitStun,conditionalAvailable,conditionalShock,conditionalApplied;
  Decision(boolean r,boolean h,boolean a,boolean s,boolean p){ready=r;hitStun=h;conditionalAvailable=a;conditionalShock=s;conditionalApplied=p;}
 }
 static Decision evaluate(int mode,boolean giveUp,boolean fallen,double consciousness,int flashFrames,double rootPower,double getUpRate,HalfSwordPainTick.State conditional){
  if(mode!=FLASH_ONLY&&mode!=FLASH_AND_CONDITIONAL_SHOCK)throw new IllegalArgumentException("Unknown readiness policy");
  if(flashFrames<0||!Double.isFinite(consciousness)||!Double.isFinite(rootPower)||!Double.isFinite(getUpRate))throw new IllegalArgumentException("Invalid readiness inputs");
  boolean hit=flashFrames>0,available=conditional!=null,shock=available&&conditional.shock;
  boolean applied=mode==FLASH_AND_CONDITIONAL_SHOCK&&available;
  boolean ready=HalfSwordAttackEligibility.allows(giveUp,fallen,consciousness,hit||(applied&&shock),rootPower,getUpRate);
  return new Decision(ready,hit,available,shock,applied);
 }
}
