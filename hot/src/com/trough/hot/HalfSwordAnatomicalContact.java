package com.trough.hot;
/** Approximate contact selection, not recovered original hit ordering.
 * Both queries must be valid to rearm. On overlap choose deeper signed gap;
 * torso wins ties. A single caller-owned episode gate covers both regions.
 */
final class HalfSwordAnatomicalContact {
 static int select(boolean torsoValid,boolean torsoTouch,double torsoGap,boolean headValid,boolean headTouch,double headGap){
  if(!torsoValid||!headValid||torsoTouch&&!Double.isFinite(torsoGap)||headTouch&&!Double.isFinite(headGap))return -1;
  return headTouch&&(!torsoTouch||headGap<torsoGap)?1:0;
 }
}
