package com.trough.hot;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
/** Fixed-step shadow observation only. Never applies forces or changes health. */
final class HalfSwordContactHistory {
 private final double[] previous=new double[12],current=new double[12],staged=new double[12];
 private final double[] point=new double[3],normal=new double[3];
 private long epoch,lastStep;
 private boolean present,consecutive;
 private double bladeRadius,bodyRadius;
 void reset(){present=false;consecutive=false;}
 boolean capture(long generation,long step,ByteBuffer segments){
  if(generation<0||step<0||segments==null||segments.capacity()!=112||segments.order()!=ByteOrder.nativeOrder()){reset();return false;}
  for(int i=0;i<12;i++){staged[i]=segments.getDouble(i*8);if(!Double.isFinite(staged[i])){reset();return false;}}
  double br=segments.getDouble(96),tr=segments.getDouble(104);
  if(!Double.isFinite(br)||!Double.isFinite(tr)||br<0||tr<0){reset();return false;}
  consecutive=present&&generation==epoch&&lastStep<Long.MAX_VALUE&&step==lastStep+1&&br==bladeRadius&&tr==bodyRadius;
  if(consecutive)System.arraycopy(current,0,previous,0,12);
  System.arraycopy(staged,0,current,0,12);epoch=generation;lastStep=step;bladeRadius=br;bodyRadius=tr;present=true;return consecutive;
 }
 boolean velocity(ByteBuffer contact,double seconds,double maxSpeed,double[] output){
  if(!consecutive||contact==null||contact.capacity()!=64||contact.order()!=ByteOrder.nativeOrder()||contact.getInt(0)<=0)return false;
  // Reject a teleport at either endpoint, even when this contact's material point barely moved.
  if(!Double.isFinite(seconds)||seconds<=0||!Double.isFinite(maxSpeed)||maxSpeed<=0)return false;
  for(int endpoint=0;endpoint<4;endpoint++){
   double distance2=0;for(int k=0;k<3;k++){double delta=current[endpoint*3+k]-previous[endpoint*3+k];distance2+=delta*delta;}
   double limit=maxSpeed*seconds;if(!Double.isFinite(distance2)||distance2>limit*limit)return false;
  }
  for(int k=0;k<3;k++){point[k]=contact.getDouble(16+k*8);normal[k]=contact.getDouble(40+k*8);}
  return HalfSwordContactMotion.sample(previous,current,point,normal,contact.getDouble(8),bladeRadius,bodyRadius,seconds,maxSpeed,output);
 }
}
