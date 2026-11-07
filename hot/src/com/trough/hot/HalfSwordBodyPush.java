package com.trough.hot;
/** Camera-plane input to bounded UE horizontal force; no locomotion policy. */
final class HalfSwordBodyPush {
 static boolean command(double x,double forward,double yaw,double[] output){
  if(output==null||output.length<2||!Double.isFinite(x)||!Double.isFinite(forward)||!Double.isFinite(yaw))return false;
  double maximum=Math.max(Math.abs(x),Math.abs(forward));if(maximum>1.){x/=maximum;forward/=maximum;}
  double length=Math.hypot(x,forward),scale=length<=.15?0.:Math.min(1.,(length-.15)/.85)*9999./length;
  double right=x*scale,away=forward*scale,c=Math.cos(yaw),s=Math.sin(yaw);output[0]=right*c-away*s;output[1]=right*s+away*c;return true;
 }
}
