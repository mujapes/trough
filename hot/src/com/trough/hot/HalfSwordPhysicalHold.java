package com.trough.hot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
/** Explicit prototype gain choices; input ownership and authored zero linear gains retained. */
final class HalfSwordPhysicalHold {
 static ByteBuffer profiles(byte[] source,int strength)throws IOException {
  if(source==null||source.length!=792||(strength!=1&&strength!=2&&strength!=4&&strength!=8))throw new IOException("Invalid pose hold preset");
  ByteBuffer out=ByteBuffer.allocateDirect(792).order(ByteOrder.nativeOrder());out.put(source);
  for(int body=0;body<22;body++)for(int gain=0;gain<6;gain++){
   int offset=body*36+12+gain*4;float value=out.getFloat(offset),scaled=value*strength;
   if(!Float.isFinite(value)||!Float.isFinite(scaled))throw new IOException("Invalid pose hold gain");out.putFloat(offset,scaled);
  }
  out.position(0);return out;
 }
}
