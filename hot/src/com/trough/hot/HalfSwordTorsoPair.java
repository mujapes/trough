package com.trough.hot;
import java.nio.ByteBuffer;
/** Prototype discrete torso spring:1000N/m,80N pair cap, no pose projection.
 * Actor2 composition reserves pair/blade magnitudes within a100N total. */
final class HalfSwordTorsoPair {
 // Approaching desired speed is bounded by remaining gap over the existing
 // velocity controller's0.2s response time. Only horizontal inward intent changes.
 static boolean movement(double right,double forward,double nx,double nz,double gap,double[] out){
  if(out==null||out.length<2||!Double.isFinite(right)||!Double.isFinite(forward)||!Double.isFinite(nx)||!Double.isFinite(nz)||!Double.isFinite(gap))return false;
  double length=Math.hypot(right,forward),scale=length>10000?10000/length:1.;double x=right*scale*.008,y=forward*scale*.008,n=Math.hypot(nx,nz);
  if(n>.1){nx/=n;nz/=n;double inward=x*nx+y*nz,minimum=-Math.max(0,gap-.05)*500.;if(inward<minimum){x+=(minimum-inward)*nx;y+=(minimum-inward)*nz;}}
  out[0]=x/.008;out[1]=y/.008;return true;
 }
 static boolean force(ByteBuffer hit,double[] out){
  if(hit==null||hit.limit()<64||out==null||out.length<3)return false;
  double phi=hit.getDouble(8),x=hit.getDouble(40),y=hit.getDouble(48),z=hit.getDouble(56),norm=Math.sqrt(x*x+y*y+z*z);
  if(!Double.isFinite(phi)||!Double.isFinite(norm))return false;
  if(hit.getInt(4)==0){out[0]=out[1]=out[2]=0;return true;}
  if(hit.getInt(0)<1||phi>0||norm<1e-12)return false;
  double scale=Math.min(80.,-phi*1000.)/norm;out[0]=x*scale;out[1]=y*scale;out[2]=z*scale;return true;
 }
}
