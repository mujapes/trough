package com.trough.hot;
import java.nio.*;
/** Caller-confined, bounded latest observations only; never applies health/forces.
 * Prototype actor9 bone origin is the neck_01 distance reference, including upper hits.
 * Contact surface point is NOT proven equivalent to original Hit.Location/socket overrides.
 */
final class HalfSwordNeckObservation {
 static final String PROFILE="prototype-actor-bone-origin-surface-contact";
 final boolean[] valid=new boolean[4],touching=new boolean[4];
 final double[] gap=new double[4],distanceCm=new double[4];
 final double[][] pointCm=new double[4][3],neckOriginCm=new double[4][3];
 final long[] epoch=new long[4],step=new long[4],timeNanos=new long[4],queries=new long[4];
 final String[] reason=new String[4];
 private final ByteBuffer[] body=new ByteBuffer[4],result=new ByteBuffer[4];
 HalfSwordNeckObservation(){for(int i=0;i<4;i++){body[i]=buffer(176);result[i]=buffer(64);}reset();}
 static ByteBuffer buffer(int n){return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());}
 void reset(){for(int i=0;i<4;i++){epoch[i]=step[i]=-1;queries[i]=timeNanos[i]=0;invalidate(i,"reset");}}
 private void invalidate(int i,String why){valid[i]=touching[i]=false;gap[i]=distanceCm[i]=Double.NaN;for(int k=0;k<3;k++)pointCm[i][k]=neckOriginCm[i][k]=Double.NaN;reason[i]=why;}
 void sample(long handle,ByteBuffer actors,ByteBuffer target,ByteBuffer query,int side,long generation,long currentStep){
  if(side<0||side>1)throw new IllegalArgumentException("Neck observation side");
  for(int part=0;part<2;part++){
   int i=side*2+part;invalidate(i,"unavailable");
   if(generation<0||currentStep<0){reason[i]="invalid-step";continue;}
   if(generation<epoch[i]||(generation==epoch[i]&&currentStep<=step[i])){reason[i]="stale-or-duplicate";continue;}
   epoch[i]=generation;step[i]=currentStep;
   if(handle==0||!HalfSwordNeckTarget.write(actors,target,body[i],part==1)){reason[i]="pose-unavailable";continue;}
   double x=actors.getDouble(9*288),y=actors.getDouble(9*288+8),z=actors.getDouble(9*288+16);
   if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)){reason[i]="origin-invalid";continue;}
   try{
    int rc=side==0?HalfSwordBridge.weaponBodyContact(handle,query,body[i],result[i]):HalfSwordBridge.secondaryWeaponBodyContact(handle,query,body[i],result[i]);queries[i]++;
    if(rc!=0){reason[i]="query-rejected";continue;}
    int count=result[i].getInt(0),hit=result[i].getInt(4);double phi=result[i].getDouble(8);
    if(count<0||count>1||hit<0||hit>1||!Double.isFinite(phi)||(count==0&&hit!=0)){reason[i]="packet-invalid";continue;}
    if(count==0){valid[i]=true;gap[i]=phi;timeNanos[i]=System.nanoTime();reason[i]="clear-no-candidate";continue;}
    double px=result[i].getDouble(16)*100,py=result[i].getDouble(32)*100,pz=result[i].getDouble(24)*100;
    double nx=result[i].getDouble(40),ny=result[i].getDouble(48),nz=result[i].getDouble(56),norm=nx*nx+ny*ny+nz*nz,distance=Math.sqrt((px-x)*(px-x)+(py-y)*(py-y)+(pz-z)*(pz-z));
    if(!Double.isFinite(distance)||!Double.isFinite(norm)||Math.abs(norm-1)>.001||(hit==1&&phi>0)||(hit==0&&phi<=0)){reason[i]="contact-invalid";continue;}
    pointCm[i][0]=px;pointCm[i][1]=py;pointCm[i][2]=pz;neckOriginCm[i][0]=x;neckOriginCm[i][1]=y;neckOriginCm[i][2]=z;distanceCm[i]=distance;gap[i]=phi;touching[i]=hit!=0;valid[i]=true;timeNanos[i]=System.nanoTime();reason[i]="observed";
   }catch(RuntimeException unavailable){reason[i]="query-exception";}catch(LinkageError unavailable){reason[i]="query-api-unavailable";}
  }
 }
}
