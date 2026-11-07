package com.trough.hot;
import java.io.IOException;import java.nio.*;
/** Explicit prototype packet/native-weapon time contract; reject mismatched timing. */
final class HalfSwordStepPolicy {
 static final double SECONDS=1.0/60.0;
 static double validate(byte[] packet)throws IOException{
  if(packet==null||packet.length>400000)throw new IOException("Physics packet size");
  int[] sizes={7744,49728,2992,44688,17808,336,168,168,32256,208,6336,176,264,528,8064,8736,336,18480,72,1232,22};ByteBuffer b=ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);int at=0;double seconds=Double.NaN;
  for(int i=0;i<sizes.length;i++){if(at>packet.length-4||b.getInt(at)!=sizes[i]||sizes[i]>packet.length-at-4)throw new IOException("Physics packet layout");at+=4;if(i==18)seconds=b.getDouble(at);at+=sizes[i];}
  if(at!=packet.length||seconds!=SECONDS)throw new IOException("Physics/weapon time step mismatch");return seconds;
 }
}
