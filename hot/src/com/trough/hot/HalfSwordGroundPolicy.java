package com.trough.hot;
import java.nio.*;import java.io.IOException;
/** Explicit prototype10m platform, shared UEcm collision and renderer-metre bounds. */
final class HalfSwordGroundPolicy {
 static final double HALF_EXTENT_M=5.,DEPTH_M=.5;
 static double minimum(int axis){return axis==1?-DEPTH_M:-HALF_EXTENT_M;}
 static double maximum(int axis){return axis==1?0:HALF_EXTENT_M;}
 static ByteBuffer floorConfig(){ByteBuffer b=HalfSwordWeapon.targetConfig();for(int k=0;k<3;k++){b.putDouble(56+k*8,minimum(k));b.putDouble(80+k*8,maximum(k));}return b;}
 static void appendFloor(ByteBuffer vertices,ByteBuffer indices,int base){HalfSwordWeapon.appendBox(vertices,indices,base,new double[]{minimum(0),minimum(1),minimum(2)},new double[]{maximum(0),maximum(1),maximum(2)});}
 static byte[] prepareTemplates(byte[] source)throws IOException{if(source==null||source.length!=12696)throw new IOException("Ground template size");ByteBuffer in=ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<23;i++){int a=i*552,kind=in.getInt(a+4);if(in.getInt(a)<0||in.getInt(a)>=22||kind<1||kind>3)throw new IOException("Ground template kind");int lo=kind==2?408:448,hi=kind==2?456:472;for(int k=0;k<3;k++){double x=in.getDouble(a+8+lo+k*8),y=in.getDouble(a+8+hi+k*8);if(!Double.isFinite(x)||!Double.isFinite(y)||x>y)throw new IOException("Ground bounds");}for(int k=0;k<4;k++)if(in.getDouble(a+8+96+k*8)!=(k==3?1:0))throw new IOException("Ground rotation");for(int k=0;k<3;k++)if(in.getDouble(a+8+128+k*8)!=0)throw new IOException("Ground translation");}
  byte[] result=source.clone();ByteBuffer out=ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<23;i++){int a=i*552,kind=in.getInt(a+4),lo=kind==2?408:448,hi=kind==2?456:472;double[] min={-HALF_EXTENT_M*100,-HALF_EXTENT_M*100,-DEPTH_M*100},max={HALF_EXTENT_M*100,HALF_EXTENT_M*100,0};for(int k=0;k<3;k++){out.putDouble(a+8+lo+k*8,min[k]);out.putDouble(a+8+hi+k*8,max[k]);}}return result;
 }
}
