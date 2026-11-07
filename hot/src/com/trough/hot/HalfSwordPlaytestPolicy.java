package com.trough.hot;
/** Bounded platform push/release acceptance, not a gait or general game validator. */
final class HalfSwordPlaytestPolicy {
 static String rejection(boolean finite,double sx,double sy,double hx,double hy,double hz,double x,double y,double z,double vx,double vy){
  double[] values={sx,sy,hx,hy,hz,x,y,z,vx,vy};for(double v:values)if(!Double.isFinite(v))return "Nonfinite playtest observation";
  if(!finite)return "Nonfinite playtest pose";
  if(hz<=40.||z<=40.)return "Pose lost platform support";
  if(Math.abs(hx)>=480.||Math.abs(hy)>=480.||Math.abs(x)>=480.||Math.abs(y)>=480.)return "Pose left platform interior";
  if(Math.hypot(hx-sx,hy-sy)<=10.)return "Movement response too small";
  if(Math.hypot(x-sx,y-sy)>=400.)return "Movement escaped bounded playtest area";
  if(Math.hypot(vx,vy)>30.)return "Movement still coasting after release";
  return "";
 }
}
