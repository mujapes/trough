package com.trough.hot;
/** Prototype horizontal velocity target for refreshed support; UE centimetres/seconds. */
final class HalfSwordMovementVelocity {
 static boolean command(double right,double forward,double vx,double vy,double[] out){
  if(out==null||out.length<2||!Double.isFinite(right)||!Double.isFinite(forward)||!Double.isFinite(vx)||!Double.isFinite(vy))return false;
  double input=Math.hypot(right,forward),scale=input>10000.?10000./input:1.;
  double ax=(right*scale*.008-vx)*5.,ay=(forward*scale*.008-vy)*5.;double length=Math.hypot(ax,ay);if(length>300.){ax*=300./length;ay*=300./length;}
  out[0]=ax;out[1]=ay;return true;
 }
}
