package com.trough.hot;
import java.nio.*;
/** Preserve the native strict magnitude guard across double rounding.
 * Only a64ULP overshoot is rounding; larger budget violations still reject. */
final class HalfSwordForceRow {
 static void put(ByteBuffer out,int index,int actor,double x,double y,double z,double cap){
  if(out==null||!out.isDirect()||out.isReadOnly()||out.order()!=ByteOrder.nativeOrder()||index<0||index>1||out.limit()<(index+1)*40||actor<0||actor>=22||!Double.isFinite(cap)||cap<0)throw new IllegalArgumentException("Force row");
  double norm=Math.hypot(Math.hypot(x,y),z);if(!Double.isFinite(norm)||norm-cap>64*Math.ulp(cap))throw new IllegalArgumentException("Force exceeds budget");
  double inside=Math.max(0.,cap-32*Math.ulp(cap));if(norm>inside&&norm>0){double scale=inside/norm;x*=scale;y*=scale;z*=scale;}
  int at=index*40;out.putInt(at,actor).putInt(at+4,0).putDouble(at+8,x).putDouble(at+16,y).putDouble(at+24,z).putDouble(at+32,cap);
 }
}
