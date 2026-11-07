package com.trough.hot;
/** Actual committed pelvis displacement per simulation step, in UE cm/s.
 * Solver-published velocity can omit position corrections and even oppose motion. */
final class HalfSwordRootMotion {
 double lastX,lastY,vx,vy;boolean seeded,ready;
 synchronized void reset(){lastX=lastY=vx=vy=0;seeded=ready=false;}
 synchronized boolean sample(double x,double y,double seconds){
  if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(seconds)||seconds<=0||seconds>.05)return false;
  double nx=seeded?(x-lastX)/seconds:0,ny=seeded?(y-lastY)/seconds:0;
  if(!Double.isFinite(nx)||!Double.isFinite(ny))return false;
  vx=nx;vy=ny;ready=seeded;seeded=true;lastX=x;lastY=y;return true;
 }
 synchronized double speed(){return ready?Math.hypot(vx,vy):Double.NaN;}
}
