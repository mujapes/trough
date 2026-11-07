package com.trough.hot;
/** Optional planar practice adapter for original AI_BP two-pass aim arithmetic.
 * Cached control yaw is separate from physical body yaw. Caller supplies desired
 * head-to-target yaw and pelvis reference yaw (including any authored offset).
 * Yaw-only errors, fixed dt samples, native interpolation and aligned/invalid-input
 * handling are approximations; this does not execute Unreal rotator APIs or AI.
 */
final class HalfSwordOpponentAim {
 double yaw,accumulatedYaw,lastSpeed,dot=1; long samples;
 void reset(double initialYaw){yaw=finite(initialYaw)?wrap(initialYaw):0;accumulatedYaw=0;lastSpeed=0;dot=1;samples=0;}
 boolean step(double dt,double targetYaw,double bodyReferenceYaw,double consciousness,double distance,boolean targetFallen){
  // Native safety boundary: reject time jumps rather than injecting an aim snap.
  if(!finite(dt)||dt<=0||dt>.1||!finite(targetYaw)||!finite(bodyReferenceYaw)||!finite(consciousness)||!finite(distance)||distance<0){lastSpeed=0;dot=-1;return false;}
  targetYaw=wrap(targetYaw);bodyReferenceYaw=wrap(bodyReferenceYaw);
  double start=yaw,error=wrap(targetYaw-start),controlError=Math.abs(error);
  samples++;
  // Explicit safe adapter: no division when already aligned; preserve cached yaw.
  // Unreal Divide_DoubleDouble zero behavior has not been recovered here.
  if(controlError<=1e-9){accumulatedYaw=(float)(accumulatedYaw*(1-clamp(dt*10,0,1)));lastSpeed=0;dot=1;return true;}
  double pelvisError=Math.abs(wrap(targetYaw-bodyReferenceYaw));
  double previewSpeed=(double)(float)speed(dt,consciousness,distance,pelvisError,controlError,accumulatedYaw,targetFallen);
  double preview=wrap(start+error*clamp(((double)(float)dt)*previewSpeed,0,1));
  accumulatedYaw=(double)(float)((Math.abs(wrap(preview-start))+accumulatedYaw)*(1-clamp(dt*10,0,1)));
  lastSpeed=(double)(float)speed(dt,consciousness,distance,pelvisError,controlError,accumulatedYaw,targetFallen);
  // Both passes start from the same cached yaw, not from the preview result.
  yaw=wrap(start+error*clamp(((double)(float)dt)*lastSpeed,0,1));
  double remaining=wrap(targetYaw-yaw);
  dot=Math.abs(remaining)==90?0:Math.cos(Math.toRadians(remaining));
  return dot>=0;
 }
 static double speed(double dt,double consciousness,double distance,double pelvisError,double controlError,double z,boolean fallen){
  if(controlError<=1e-9)return 0;
  double frame=map(dt,.066,.01667,1,5);
  double health=clamp(map(consciousness,0,100,frame*.1,frame),.01,10);
  double angle=map(pelvisError,0,90,30,60);
  double proximity=map(distance,50,0,1,.00001);
  double penalty=fallen?map(z,0,10,1,.1):map(z,10,30,1,.1);
  return ((health/controlError)*angle)*proximity*penalty;
 }
 static double wrap(double angle){double n=angle%360;if(n>=180)n-=360;if(n< -180)n+=360;return n;}
 private static double map(double value,double a,double b,double c,double d){return c+(d-c)*clamp((value-a)/(b-a),0,1);}
 private static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}
 private static boolean finite(double v){return !Double.isNaN(v)&&!Double.isInfinite(v);}
}
