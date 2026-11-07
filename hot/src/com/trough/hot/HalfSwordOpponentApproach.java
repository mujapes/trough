package com.trough.hot;
/** Optional blade-gap approach. Render X/Z map to UE horizontal X/Y.
 * Normal points from player body toward opponent blade, so closing reverses it.
 * Target clearance3cm (prototype, based on measured4.1cm swing miss); desired speed cap20cm/s; current physical velocity controller still applies. */
final class HalfSwordOpponentApproach {
 static boolean command(double gap,double nx,double nz,boolean swinging,double[] out){
  if(out==null||out.length<2||!Double.isFinite(gap)||!Double.isFinite(nx)||!Double.isFinite(nz))return false;
  out[0]=out[1]=0.;double length=Math.hypot(nx,nz);if(!Double.isFinite(length))return false;
  if(swinging||gap<=.03||length<.1)return true;
  double speed=Math.min(20.,(gap-.03)*200.);out[0]=-nx/length*speed/.008;out[1]=-nz/length*speed/.008;return true;
 }
}
