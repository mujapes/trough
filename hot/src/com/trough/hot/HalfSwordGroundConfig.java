package com.trough.hot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
// Explicit diagnostic policy, bound to the exact creation packet. No defaults.
final class HalfSwordGroundConfig {
 final ByteBuffer ground,templates;final int count;
 private HalfSwordGroundConfig(ByteBuffer g,ByteBuffer t,int n){ground=g;templates=t;count=n;}
 static HalfSwordGroundConfig decode(byte[] config,byte[] packet)throws IOException {
  if(config==null||packet==null||packet.length<1||packet.length>400000||config.length<952||config.length>13096)throw new IOException("Ground configuration size");
  ByteBuffer in=ByteBuffer.wrap(config).order(ByteOrder.LITTLE_ENDIAN);
  if(in.getInt()!=0x31475348||in.getInt()!=1)throw new IOException("Ground configuration version");
  int count=in.getInt();if(count<1||count>23||in.getInt()!=0||config.length!=48+352+count*552)throw new IOException("Ground configuration layout");
  byte[] expected=new byte[32];in.get(expected);
  try{if(!MessageDigest.isEqual(expected,MessageDigest.getInstance("SHA-256").digest(packet)))throw new IOException("Ground packet mismatch");}catch(java.security.NoSuchAlgorithmException failure){throw new IOException("SHA-256 unavailable",failure);}
  ByteBuffer ground=ByteBuffer.allocateDirect(352).order(ByteOrder.nativeOrder()),templates=ByteBuffer.allocateDirect(count*552).order(ByteOrder.nativeOrder());
  ground.put(config,48,352);templates.put(config,400,count*552);
  return new HalfSwordGroundConfig(ground.asReadOnlyBuffer(),templates.asReadOnlyBuffer(),count);
 }
}
