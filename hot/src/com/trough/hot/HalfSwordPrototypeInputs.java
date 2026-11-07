package com.trough.hot;
import java.io.IOException;import java.nio.*;
/** Verify saved config against original packet before preparing owned prototype copies. */
final class HalfSwordPrototypeInputs {
 final ByteBuffer packet,ground,templates;
 private HalfSwordPrototypeInputs(ByteBuffer p,ByteBuffer g,ByteBuffer t){packet=p;ground=g;templates=t;}
 static ByteBuffer direct(byte[] bytes){ByteBuffer b=ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());b.put(bytes);return b.asReadOnlyBuffer();}
 static HalfSwordPrototypeInputs prepare(byte[] config,byte[] original)throws IOException{HalfSwordGroundConfig decoded=HalfSwordGroundConfig.decode(config,original);if(decoded.count!=23)throw new IOException("Prototype ground needs23shapes");byte[] templates=new byte[12696];ByteBuffer copy=decoded.templates.duplicate();copy.clear();copy.get(templates);return new HalfSwordPrototypeInputs(direct(HalfSwordInitialState.prepare(original)),decoded.ground,direct(HalfSwordGroundPolicy.prepareTemplates(templates)));}
}
