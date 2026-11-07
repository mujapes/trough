package com.trough.hot;
import java.io.IOException;import java.nio.*;
/** Explicit prototype rest/gravity policy. Never mutates the diagnostic corpus. */
final class HalfSwordInitialState {
 static final double GRAVITY_CM_S2=-980.,MAX_LINEAR_CM_S=500.,MAX_ANGULAR_RAD_S=10.;
 static int[] offsets(byte[] packet)throws IOException{HalfSwordStepPolicy.validate(packet);ByteBuffer b=ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);int[] offsets=new int[21];int at=0;for(int i=0;i<21;i++){int n=b.getInt(at);at+=4;offsets[i]=at;at+=n;}return offsets;}
 static byte[] prepare(byte[] packet)throws IOException{int[] off=offsets(packet);ByteBuffer src=ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
  for(int i=0;i<22;i++){int a=off[10]+i*288;double norm=0;for(int k=0;k<3;k++)if(!Double.isFinite(src.getDouble(a+k*8))||!Double.isFinite(src.getDouble(a+64+k*8)))throw new IOException("Initial actor pose");for(int k=0;k<4;k++){float q=src.getFloat(a+24+k*4);if(!Float.isFinite(q))throw new IOException("Initial actor rotation");norm+=(double)q*q;}if(Math.abs(norm-1)>.001||src.getInt(a+280)!=2||src.getInt(a+284)!=4)throw new IOException("Initial actor state");double inv=src.getDouble(off[11]+i*8);if(!Double.isFinite(inv)||inv<=0)throw new IOException("Initial actor mass");}
  byte[] result=packet.clone();ByteBuffer b=ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
  for(int i=0;i<22;i++){int a=off[10]+i*288;for(int k=40;k<64;k++)result[a+k]=0;for(int k=120;k<216;k++)result[a+k]=0;b.putDouble(a+136,GRAVITY_CM_S2);for(int k=256;k<280;k++)result[a+k]=0;for(int k=0;k<24;k++)result[a+216+k]=result[a+k];for(int k=0;k<16;k++)result[a+240+k]=result[a+24+k];int h=off[19]+i*56;for(int k=0;k<24;k++)result[h+k]=result[a+k];for(int k=0;k<4;k++)b.putDouble(h+24+k*8,b.getFloat(a+24+k*4));result[off[20]+i]=0;}
  int c=off[18];double[] cfg={HalfSwordStepPolicy.SECONDS,.1,.2,MAX_LINEAR_CM_S*MAX_LINEAR_CM_S,MAX_ANGULAR_RAD_S*MAX_ANGULAR_RAD_S,MAX_ANGULAR_RAD_S*MAX_ANGULAR_RAD_S,MAX_LINEAR_CM_S*MAX_LINEAR_CM_S};for(int k=0;k<7;k++)b.putDouble(c+k*8,cfg[k]);for(int k=0;k<4;k++)b.putFloat(c+56+k*4,-1);return result;
 }
}
