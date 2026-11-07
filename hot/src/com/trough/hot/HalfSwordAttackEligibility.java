package com.trough.hot;
/** Original AI_BP Attack eligibility block only. Inputs supplied by scene adapter.
 * Does not select target, distance, weapon, timing, or implement the attack body.
 */
final class HalfSwordAttackEligibility {
 static boolean allows(boolean giveUp,boolean fallen,double consciousness,boolean painShock,double rootPower,double getUpRate){
  return !giveUp&&!fallen&&consciousness>=50&&!painShock&&rootPower>.25&&getUpRate<=.5;
 }
}
