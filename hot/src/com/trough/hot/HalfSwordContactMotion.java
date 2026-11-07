package com.trough.hot;
/** Approximation for the current capsule scene, not recovered original hit sampling.
 * Endpoints are renderer metres. Evaluates the SAME current material coordinates
 * in previous/current poses; avoids differentiating migrating closest points.
 * Caller owns consecutive-step/reset/teleport guards. No output mutation on failure.
 */
final class HalfSwordContactMotion {
 static boolean sample(double[] previous,double[] current,double[] point,double[] normal,double phi,double bladeRadius,double bodyRadius,double seconds,double maxMetresPerSecond,double[] ueVelocity){
  if(previous==null||current==null||point==null||normal==null||ueVelocity==null||previous.length!=12||current.length!=12||point.length!=3||normal.length!=3||ueVelocity.length!=3||ueVelocity==previous||ueVelocity==current||ueVelocity==point||ueVelocity==normal)return false;
  if(!Double.isFinite(seconds)||seconds<=0||!Double.isFinite(maxMetresPerSecond)||maxMetresPerSecond<=0||!Double.isFinite(phi)||phi==Double.MAX_VALUE||!Double.isFinite(bladeRadius)||bladeRadius<0||!Double.isFinite(bodyRadius)||bodyRadius<0)return false;
  double norm=0;for(int k=0;k<3;k++){if(!Double.isFinite(point[k])||!Double.isFinite(normal[k]))return false;norm+=normal[k]*normal[k];}
  if(!Double.isFinite(norm)||Math.abs(norm-1)>.001)return false;
  for(int i=0;i<12;i++)if(!Double.isFinite(previous[i])||!Double.isFinite(current[i]))return false;
  // Current query's point lies on body surface: q=point-normal*bodyRadius.
  // Reconstruct blade centreline point using signed gap and both radii.
  double sb=0,tb=0,bl=0,tl=0;
  for(int k=0;k<3;k++){
   double q=point[k]-normal[k]*bodyRadius,p=q+normal[k]*(phi+bladeRadius+bodyRadius),b=current[3+k]-current[k],t=current[9+k]-current[6+k];
   sb+=(p-current[k])*b;tb+=(q-current[6+k])*t;bl+=b*b;tl+=t*t;
  }
  double s=bl>1e-24?Math.max(0,Math.min(1,sb/bl)):0,t=tl>1e-24?Math.max(0,Math.min(1,tb/tl)):0;
  double[] velocity=new double[3];double speed2=0;
  for(int k=0;k<3;k++){
   double blade=current[k]+s*(current[3+k]-current[k]),oldBlade=previous[k]+s*(previous[3+k]-previous[k]);
   double body=current[6+k]+t*(current[9+k]-current[6+k]),oldBody=previous[6+k]+t*(previous[9+k]-previous[6+k]);
   velocity[k]=((blade-oldBlade)-(body-oldBody))/seconds;if(!Double.isFinite(velocity[k]))return false;speed2+=velocity[k]*velocity[k];
  }
  if(!Double.isFinite(speed2)||speed2>maxMetresPerSecond*maxMetresPerSecond)return false;
  // Existing scene mapping: renderer XYZ metres -> UE XZY centimetres.
  ueVelocity[0]=velocity[0]*100;ueVelocity[1]=velocity[2]*100;ueVelocity[2]=velocity[1]*100;return true;
 }
}
