package com.trough.hot;
import java.nio.ByteBuffer;
/** Skin-attached opponent blade:200N/m penalty,20N cap onto player torso.
 * Opponent hand receives the opposite force; no independent weapon impulse. */
final class HalfSwordBladeContactForce {
 static boolean player(ByteBuffer hit,double[] out){
  if(hit==null||hit.limit()<64||out==null||out.length<3)return false;
  double phi=hit.getDouble(8),x=hit.getDouble(40),y=hit.getDouble(48),z=hit.getDouble(56),n=Math.sqrt(x*x+y*y+z*z);
  if(!Double.isFinite(phi)||!Double.isFinite(n))return false;
  if(hit.getInt(4)==0){out[0]=out[1]=out[2]=0;return true;}
  if(hit.getInt(0)<1||phi>0||n<1e-12)return false;
  double scale=-Math.min(20.,-phi*200.)/n;out[0]=x*scale;out[1]=y*scale;out[2]=z*scale;return true;
 }
 static double norm(double[] f){return Math.sqrt(f[0]*f[0]+f[1]*f[1]+f[2]*f[2]);}
 static double remaining(double[] pair,double[] blade){return Math.max(0.,10000.-100.*norm(pair)-100.*norm(blade));}
}
