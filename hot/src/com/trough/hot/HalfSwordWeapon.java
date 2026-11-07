package com.trough.hot;
import java.io.*;
import java.nio.*;
import java.security.*;
/** Immutable acquired geometry; kinematic weapon_r attachment, no weapon physics. */
final class HalfSwordWeapon {
 static final int VERTICES=1923,INDICES=8046;
 static byte[] read(File root,String name,int length,String hash)throws IOException{
  File file=new File(root,name);if(file.length()!=length)throw new IOException("Weapon stream size");byte[] bytes=new byte[length];try(FileInputStream in=new FileInputStream(file)){int offset=0,n;while(offset<length&&(n=in.read(bytes,offset,length-offset))>0)offset+=n;if(offset!=length||in.read()!=-1)throw new IOException("Weapon stream changed");}
  try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format(java.util.Locale.US,"%02x",value&255));if(!hash.equals(hex.toString()))throw new IOException("Weapon stream digest");}catch(NoSuchAlgorithmException e){throw new IOException(e);}return bytes;
 }
 static ByteBuffer[] merge(ByteBuffer bodyVertices,ByteBuffer bodyIndices,File root)throws IOException{
  if(bodyVertices==null||bodyIndices==null||bodyVertices.capacity()%56!=0||bodyIndices.capacity()%6!=0)throw new IOException("Body mesh layout");int base=bodyVertices.capacity()/56;if(base<1||base+VERTICES+72>65535||bodyIndices.capacity()/2+INDICES+108>1000000)throw new IOException("Combined mesh bounds");
  ByteBuffer bv=bodyVertices.duplicate(),bi=bodyIndices.duplicate().order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<bi.capacity();i+=2)if((bi.getShort(i)&65535)>=base)throw new IOException("Body mesh index");
  byte[] v=read(root,"vertices.bin",VERTICES*56,"2363085efb1af55450e1103b2eab7fdeb9b81e956e7cebd9f44097eac5c0465f"),i=read(root,"indices.bin",INDICES*2,"aea8eb4a469523a2765d165250078cd0579329d2c6e20736e40994808deec862");ByteBuffer vi=ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN),ii=ByteBuffer.wrap(i).order(ByteOrder.LITTLE_ENDIAN);
  for(int k=0;k<VERTICES;k++){int at=k*56;for(int f=0;f<8;f++){float value=vi.getFloat(at+f*4);if(Float.isNaN(value)||Float.isInfinite(value))throw new IOException("Weapon vertex domain");}for(int j=0;j<4;j++)if(vi.getShort(at+32+j*2)!=(j==0?38:0)||vi.getFloat(at+40+j*4)!=(j==0?1f:0f))throw new IOException("Weapon skin binding");}
  ByteBuffer vertices=ByteBuffer.allocateDirect(bodyVertices.capacity()+v.length+72*56).order(ByteOrder.nativeOrder()),indices=ByteBuffer.allocateDirect(bodyIndices.capacity()+i.length+108*2).order(ByteOrder.nativeOrder());bv.clear();vertices.put(bv);vertices.put(v);bi.clear();indices.put(bi);for(int k=0;k<INDICES;k++){int value=ii.getShort(k*2)&65535;if(value>=VERTICES)throw new IOException("Weapon mesh index");indices.putShort((short)(base+value));}appendTarget(vertices,indices,base+VERTICES);appendBox(vertices,indices,base+VERTICES+24,BODY_TARGET_MIN,BODY_TARGET_MAX);HalfSwordGroundPolicy.appendFloor(vertices,indices,base+VERTICES+48);return new ByteBuffer[]{vertices,indices};
 }
 static final double[] TARGET_MIN={-.98,1.2,.5},TARGET_MAX={-.82,1.8,.7};
 static final double[] BODY_TARGET_MIN={.30,1.0,-.15},BODY_TARGET_MAX={.35,1.4,.15};
 static ByteBuffer bodyTargetConfig(){ByteBuffer b=targetConfig();for(int i=0;i<3;i++){b.putDouble(56+i*8,BODY_TARGET_MIN[i]);b.putDouble(80+i*8,BODY_TARGET_MAX[i]);}return b;}
 // Existing authored ground spans UE +/-50cm XY and -50..0cm Z.
 static ByteBuffer floorConfig(){return HalfSwordGroundPolicy.floorConfig();}
 static boolean targetOffset(ByteBuffer config,double x,double y,double z){if(config==null||config.limit()<112||config.isReadOnly()||config.order()!=ByteOrder.nativeOrder()||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||x*x+y*y+z*z>.25*.25)return false;double[] offset={x,y,z};for(int i=0;i<3;i++){config.putDouble(56+i*8,TARGET_MIN[i]+offset[i]);config.putDouble(80+i*8,TARGET_MAX[i]+offset[i]);}return true;}
 static ByteBuffer targetConfig(){ByteBuffer b=ByteBuffer.allocateDirect(112).order(ByteOrder.nativeOrder());double[] values={-.7542296905,1.4530003569999999,-.004632868900999773,-1.0036104539352961,1.6293859646087088,.7875541369611823,.025,-.98,1.2,.5,-.82,1.8,.7,.02};for(double v:values)b.putDouble(v);return b;}
 static void appendTarget(ByteBuffer vertices,ByteBuffer indices,int base){appendBox(vertices,indices,base,TARGET_MIN,TARGET_MAX);}
 static void appendBox(ByteBuffer vertices,ByteBuffer indices,int base,double[] minimum,double[] maximum){int[] order={0,1,2,0,2,3};for(int axis=0;axis<3;axis++)for(int side=0;side<2;side++){int face=axis*2+side;for(int corner=0;corner<4;corner++){int[] bits={0,0,0};bits[axis]=side;bits[(axis+1)%3]=(corner==1||corner==2)?1:0;bits[(axis+2)%3]=corner>=2?1:0;for(int k=0;k<3;k++)vertices.putFloat((float)(bits[k]==0?minimum[k]:maximum[k]));for(int k=0;k<3;k++)vertices.putFloat(k==axis?(side==0?-1f:1f):0f);vertices.putFloat(0).putFloat(0);for(int k=0;k<4;k++)vertices.putShort((short)0);vertices.putFloat(1).putFloat(0).putFloat(0).putFloat(0);}for(int v:order)indices.putShort((short)(base+face*4+v));}}
}
