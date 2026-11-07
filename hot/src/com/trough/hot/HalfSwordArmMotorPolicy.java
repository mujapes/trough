package com.trough.hot;
import java.nio.*;
/** Shared explicit prototype motor inputs; mass/gains are not shipping defaults. */
final class HalfSwordArmMotorPolicy {
 static void writable(ByteBuffer b,int size){if(b==null||!b.isDirect()||b.isReadOnly()||b.capacity()<size||b.limit()<size||b.order()!=ByteOrder.nativeOrder())throw new IllegalArgumentException("Motor buffer");}
 static void initialize(ByteBuffer state,ByteBuffer bone){writable(state,144);writable(bone,64);for(int k=0;k<144;k++)state.put(k,(byte)0);for(int k=0;k<64;k++)bone.put(k,(byte)0);double[] values={1,25,1,1,1,1,1,1,1,0,0,1,100,0,10000,0,0};for(int k=0;k<17;k++)state.putDouble(k*8,values[k]);bone.putDouble(0,1.);}
 static void command(ByteBuffer bone,int pitch,int yaw,boolean right){writable(bone,64);if(pitch< -45||pitch>45||yaw< -45||yaw>45||(!right&&yaw!=0))throw new IllegalArgumentException("Motor command");bone.putDouble(8,pitch);bone.putDouble(16,yaw);bone.putInt(56,right?3:4);}
}
