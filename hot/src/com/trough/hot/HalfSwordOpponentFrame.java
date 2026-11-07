package com.trough.hot;
import java.nio.*;
/** Opponent instance-local UE frame: optional180degree turn around verticalZ.
 * Copies only published current poses; simulation/history remain instance-local.
 * Renderer vectors use XZY, so horizontal worldX/Z reverse on inverse mapping.
 */
final class HalfSwordOpponentFrame {
 final double x,y,sign;final boolean turned;
 HalfSwordOpponentFrame(double px,double py,boolean face){if(!Double.isFinite(px)||!Double.isFinite(py)||Math.abs(px)>200||Math.abs(py)>200)throw new IllegalArgumentException("Opponent spawn outside floor");x=px;y=py;turned=face;sign=face?-1:1;}
 double worldX(double local){return sign*local+x;}
 double worldY(double local){return sign*local+y;}
 double localHorizontal(double world){return sign*world;}
 void copyActors(ByteBuffer source,ByteBuffer output){
  if(source==null||output==null||source==output||source.limit()<6336||output.limit()<6336||output.isReadOnly()||source.order()!=ByteOrder.nativeOrder()||output.order()!=ByteOrder.nativeOrder())throw new IllegalArgumentException("Opponent actor buffers");
  for(int actor=0;actor<22;actor++){int at=actor*288;for(int k=0;k<3;k++)if(!Double.isFinite(source.getDouble(at+k*8)))throw new IllegalArgumentException("Opponent pose position");for(int k=0;k<4;k++)if(!Float.isFinite(source.getFloat(at+24+k*4)))throw new IllegalArgumentException("Opponent pose rotation");}
  for(int k=0;k<6336;k++)output.put(k,source.get(k));
  for(int actor=0;actor<22;actor++){int at=actor*288;output.putDouble(at,worldX(source.getDouble(at)));output.putDouble(at+8,worldY(source.getDouble(at+8)));if(turned){float qx=source.getFloat(at+24),qy=source.getFloat(at+28),qz=source.getFloat(at+32),qw=source.getFloat(at+36);output.putFloat(at+24,-qy).putFloat(at+28,qx).putFloat(at+32,qw).putFloat(at+36,-qz);}}
 }
}
