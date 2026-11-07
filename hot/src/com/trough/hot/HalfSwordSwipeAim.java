package com.trough.hot;
/** Relative shoulder target; no physics force or original control-policy claim. */
final class HalfSwordSwipeAim {
 float x,y,pitch,yaw;
 void begin(float px,float py,int p,int a){x=px;y=py;pitch=p;yaw=a;}
 void move(float px,float py,int width,int height){
  if(!Float.isFinite(px)||!Float.isFinite(py))return;
  yaw=clamp(yaw+(px-x)*90f/Math.max(1,width));
  pitch=clamp(pitch-(py-y)*90f/Math.max(1,height));x=px;y=py;
 }
 static float clamp(float v){return Math.max(-45f,Math.min(45f,v));}
}
