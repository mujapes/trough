package com.trough.hot;
/** Prototype horizontal post spring, metres/seconds; simulation-thread owned.
 * A scored contact gives a bounded visual kick, not a recovered mass/energy model. */
final class HalfSwordTargetSpring {
 double x,z,vx,vz;
 void reset(){x=z=vx=vz=0;}
 boolean kick(double nx,double nz){
  double length=Math.hypot(nx,nz);
  if(!Double.isFinite(length)||length<1e-9)return false;
  vx=-.6*nx/length;vz=-.6*nz/length;return true;
 }
 boolean step(){
  double oldX=x,oldZ=z,dt=1./60.;
  vx+=(-36*x-10*vx)*dt;vz+=(-36*z-10*vz)*dt;
  x+=vx*dt;z+=vz*dt;
  double radius=Math.hypot(x,z);
  if(radius>.12){x*=.12/radius;z*=.12/radius;vx=vz=0;}
  if(Math.hypot(x,z)<1e-5&&Math.hypot(vx,vz)<1e-4)reset();
  return x!=oldX||z!=oldZ;
 }
}
