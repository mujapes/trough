package com.trough.hot;
/** Explicit90-frame motor-target gesture, not recovered fighting AI. */
final class HalfSwordOpponentSwing {
 int frame,pitch,yaw;boolean active;
 boolean start(){if(active)return false;frame=0;active=true;return true;}
 void cancel(){frame=pitch=yaw=0;active=false;}
 void step(){if(!active){pitch=yaw=0;return;}frame++;double p,y;if(frame<=30){p=frame;y=-frame;}else if(frame<=48){double t=(frame-30)/18.;p=30-60*t;y=-30+60*t;}else{double t=(frame-48)/42.;p=-30*(1-t);y=30*(1-t);}pitch=(int)Math.round(p);yaw=(int)Math.round(y);if(frame>=90){pitch=yaw=0;active=false;}}
}
