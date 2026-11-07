package com.trough.hot;
/** Small fixed-step combat simulation. World coordinates use a 1000x650 arena. */
final class ArenaPhysics {
 float x=350,y=350,vx,vy,angle,omega,ex=760,ey=320,evx,evy,clock,cool,flash;
 final float[] value=new float[3]; int tactic,round; float reward;
 int hits,health=5; boolean held; float tx=x,ty=y;
 void step(float dt){
  if(health<=0)return;clock+=dt;
  int nextRound=(int)(clock/3);if(nextRound>round){value[tactic]+=.25f*(reward-value[tactic]);reward=0;round=nextRound;if(round<3||round%4==0)tactic=round%3;else{tactic=0;for(int i=1;i<3;i++)if(value[i]>value[tactic])tactic=i;}}
  cool=Math.max(0,cool-dt);flash=Math.max(0,flash-dt);
  if(held){float dx=tx-x,dy=ty-y;float dist=(float)Math.sqrt(dx*dx+dy*dy);if(dist>45){vx+=dx/Math.max(1,dist)*520*dt;vy+=dy/Math.max(1,dist)*520*dt;}
   float want=(float)Math.atan2(dy,dx),err=want-angle;while(err>Math.PI)err-=2*Math.PI;while(err<-Math.PI)err+=2*Math.PI;omega+=err*30*dt;
  }
  float damp=(float)Math.exp(-4*dt);vx*=damp;vy*=damp;omega*=(float)Math.exp(-3*dt);omega=Math.max(-12,Math.min(12,omega));angle+=omega*dt;
  x=Math.max(35,Math.min(965,x+vx*dt));y=Math.max(90,Math.min(600,y+vy*dt));
  float dx=x-ex,dy=y-ey,dist=(float)Math.sqrt(dx*dx+dy*dy);float phase=clock%3;float speed=phase>2.3f?190:phase>1.8f?0:42;
  float sideMove=phase<1.8f?(tactic==1?65:tactic==2?-65:0):0;
  evx+=((dx*speed-dy*sideMove)/Math.max(1,dist)-evx)*Math.min(1,dt*4);evy+=((dy*speed+dx*sideMove)/Math.max(1,dist)-evy)*Math.min(1,dt*4);
  ex=Math.max(30,Math.min(970,ex+evx*dt));ey=Math.max(90,Math.min(600,ey+evy*dt));
  float sx=(float)Math.cos(angle),sy=(float)Math.sin(angle);float along=(ex-x)*sx+(ey-y)*sy;float side=Math.abs((ex-x)*sy-(ey-y)*sx);
  if(cool<=0&&along>20&&along<140&&side<34&&Math.abs(omega)>1.1f){hits++;reward-=1;cool=.45f;flash=.25f;ex=Math.max(40,Math.min(960,ex+sx*95));ey=Math.max(100,Math.min(590,ey+sy*95));evx=sx*260;evy=sy*260;}
  else if(cool<=0&&dist<49){health--;reward+=1;cool=1;flash=.4f;evx=-dx*8;evy=-dy*8;vx=dx*6;vy=dy*6;}
 }
 float[] save(){return new float[]{x,y,vx,vy,angle,omega,ex,ey,evx,evy,clock,cool,flash,hits,health,value[0],value[1],value[2],tactic,round,reward};}
 void restore(float[]s){if(s==null||(s.length!=15&&s.length!=21))return;for(float f:s)if(Float.isNaN(f)||Float.isInfinite(f))return;x=s[0];y=s[1];vx=s[2];vy=s[3];angle=s[4];omega=s[5];ex=s[6];ey=s[7];evx=s[8];evy=s[9];clock=s[10];cool=s[11];flash=s[12];hits=(int)s[13];health=(int)s[14];tx=x;ty=y;if(s.length==21){for(int i=0;i<3;i++)value[i]=s[15+i];tactic=Math.max(0,Math.min(2,(int)s[18]));round=(int)s[19];reward=s[20];}}
}
