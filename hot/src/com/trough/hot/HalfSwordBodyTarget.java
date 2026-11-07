package com.trough.hot;
import java.nio.*;
/** Authored spine_03 capsule (actor2), UE cm pose -> renderer metre probe. */
final class HalfSwordBodyTarget {
 static final String BONE="spine_03";
 static boolean write(ByteBuffer actors,ByteBuffer target,ByteBuffer out){return write(actors,target,out,false);}
 static boolean writeHead(ByteBuffer actors,ByteBuffer target,ByteBuffer out){return write(actors,target,out,true);}
 private static boolean write(ByteBuffer actors,ByteBuffer target,ByteBuffer out,boolean head){
  if(actors==null||target==null||out==null||actors==out||target==out||actors.order()!=ByteOrder.nativeOrder()||target.order()!=ByteOrder.nativeOrder()||actors.limit()<6336||target.limit()<112||out.limit()<176||!out.isDirect()||out.isReadOnly())return false;
  ByteBuffer a=actors,t=target;int base=(head?11:2)*288;
  double px=a.getDouble(base),py=a.getDouble(base+8),pz=a.getDouble(base+16),x=a.getFloat(base+24),y=a.getFloat(base+28),z=a.getFloat(base+32),w=a.getFloat(base+36),norm=x*x+y*y+z*z+w*w;
  if(!Double.isFinite(px)||!Double.isFinite(py)||!Double.isFinite(pz)||!Double.isFinite(norm)||Math.abs(norm-1)>.001)return false;
  double inv=1/Math.sqrt(norm);x*=inv;y*=inv;z*=inv;w*=inv;
  double r00=1-2*(y*y+z*z),r01=2*(x*y-z*w),r02=2*(x*z+y*w),r10=2*(x*y+z*w),r11=1-2*(x*x+z*z),r12=2*(y*z-x*w),r20=2*(x*z-y*w),r21=2*(y*z+x*w),r22=1-2*(x*x+y*y);
  double sx=-3.3193712234497057,sy=1.2045932403900055,sz=3.233004559610011,length=6.638742446899412;
  double ex=sx+length*.9999999999999998,ey=sy-length*1.21679688240334e-8,ez=sz+length*1.2167963873908917e-8;
  if(head){sx=.02176115876760558;sy=6.302870079581085;sz=3.9292941377683706;length=7.923442840576171;ex=sx+length*(-.0010433946078179804);ey=sy+length*(-.305100220674575);ez=sz+length*.9523196767220649;}
  double ax=(px+r00*sx+r01*sy+r02*sz)*.01,ay=(pz+r20*sx+r21*sy+r22*sz)*.01,az=(py+r10*sx+r11*sy+r12*sz)*.01,bx=(px+r00*ex+r01*ey+r02*ez)*.01,by=(pz+r20*ex+r21*ey+r22*ez)*.01,bz=(py+r10*ex+r11*ey+r12*ez)*.01;
  if(!Double.isFinite(ax)||!Double.isFinite(ay)||!Double.isFinite(az)||!Double.isFinite(bx)||!Double.isFinite(by)||!Double.isFinite(bz))return false;
  for(int i=0;i<3;i++){double lo=t.getDouble(56+i*8),hi=t.getDouble(80+i*8);if(!Double.isFinite(lo)||!Double.isFinite(hi)||lo>=hi)return false;}double cull=t.getDouble(104);if(!Double.isFinite(cull)||cull<0)return false;
  out.order(ByteOrder.nativeOrder());for(int i=0;i<16;i++)out.putFloat(i*4,i%5==0?1f:0f);out.putDouble(64,ax).putDouble(72,ay).putDouble(80,az).putDouble(88,bx).putDouble(96,by).putDouble(104,bz).putDouble(112,head?.07756832122802734:.10186450958251953);for(int i=0;i<6;i++)out.putDouble(120+i*8,t.getDouble(56+i*8));out.putDouble(168,cull);return true;
 }
}
