package com.trough.hot;
import java.nio.*;
/** Read-only authored neck capsule packet. Actor9=neck_01, actor10=neck_02.
 * UE cm actor pose -> renderer XYZ metres. No socket-origin or injury binding.
 */
final class HalfSwordNeckTarget {
 static boolean write(ByteBuffer actors,ByteBuffer target,ByteBuffer out,boolean upper){
  if(actors==null||target==null||out==null||actors==out||target==out||actors.order()!=ByteOrder.nativeOrder()||target.order()!=ByteOrder.nativeOrder()||actors.limit()<6336||target.limit()<112||out.limit()<176||!out.isDirect()||out.isReadOnly())return false;
  ByteBuffer a=actors,t=target;int base=(upper?10:9)*288;
  double px=a.getDouble(base),py=a.getDouble(base+8),pz=a.getDouble(base+16),x=a.getFloat(base+24),y=a.getFloat(base+28),z=a.getFloat(base+32),w=a.getFloat(base+36),norm=x*x+y*y+z*z+w*w;
  if(!Double.isFinite(px)||!Double.isFinite(py)||!Double.isFinite(pz)||!Double.isFinite(norm)||Math.abs(norm-1)>.001)return false;
  double inv=1/Math.sqrt(norm);x*=inv;y*=inv;z*=inv;w*=inv;
  double r00=1-2*(y*y+z*z),r01=2*(x*y-z*w),r02=2*(x*z+y*w),r10=2*(x*y+z*w),r11=1-2*(x*x+z*z),r12=2*(y*z-x*w),r20=2*(x*z-y*w),r21=2*(y*z+x*w),r22=1-2*(x*x+y*y);
  double sx=upper?.385383039340071:1.3762072515022876,sy=upper?1.0510062092890724:-.2348565522979677,sz=upper?2.2004307841980646:1.538626183303517;
  double length=upper?1.9656839370727546:2.75;
  double ex=sx+length*(upper?-.025897285784381885:-.9992984400743911),ey=sy+length*(upper?-.15992200610148738:-.0374516710560235),ez=sz+length*(upper?.9867898877438285:1.2142896627603312e-8);
  double ax=(px+r00*sx+r01*sy+r02*sz)*.01,ay=(pz+r20*sx+r21*sy+r22*sz)*.01,az=(py+r10*sx+r11*sy+r12*sz)*.01,bx=(px+r00*ex+r01*ey+r02*ez)*.01,by=(pz+r20*ex+r21*ey+r22*ez)*.01,bz=(py+r10*ex+r11*ey+r12*ez)*.01;
  if(!Double.isFinite(ax)||!Double.isFinite(ay)||!Double.isFinite(az)||!Double.isFinite(bx)||!Double.isFinite(by)||!Double.isFinite(bz))return false;
  for(int i=0;i<3;i++){double lo=t.getDouble(56+i*8),hi=t.getDouble(80+i*8);if(!Double.isFinite(lo)||!Double.isFinite(hi)||lo>=hi)return false;}double cull=t.getDouble(104);if(!Double.isFinite(cull)||cull<0)return false;
  out.order(ByteOrder.nativeOrder());for(int i=0;i<16;i++)out.putFloat(i*4,i%5==0?1f:0f);out.putDouble(64,ax).putDouble(72,ay).putDouble(80,az).putDouble(88,bx).putDouble(96,by).putDouble(104,bz).putDouble(112,(upper?5.8263115882873535:5.41257905960083)*.01);for(int i=0;i<6;i++)out.putDouble(120+i*8,t.getDouble(56+i*8));out.putDouble(168,cull);return true;
 }
}
