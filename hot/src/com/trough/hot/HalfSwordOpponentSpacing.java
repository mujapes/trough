package com.trough.hot;
/** Deterministic spacing prototype, not a trained fighting controller.
 * UE centimetres;110..140cm dead band,40cm/s desired speed ceiling. */
final class HalfSwordOpponentSpacing {
 static boolean command(double dx,double dy,double[] out){
  if(out==null||out.length<2||!Double.isFinite(dx)||!Double.isFinite(dy))return false;
  double distance=Math.hypot(dx,dy);if(!Double.isFinite(distance))return false;
  if(distance<1e-9||(distance>=110&&distance<=140)){out[0]=out[1]=0;return true;}
  double speed=Math.max(-40,Math.min(40,(distance-125)*2));
  out[0]=dx/distance*speed/.008;out[1]=dy/distance*speed/.008;return true;
 }
}
