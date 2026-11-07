package com.trough.hot;
/** Shared readiness/retirement policy for bounded live-view checks. */
final class HalfSwordCheckGate {
 static final int WAIT=0,READY=1,RETIRED=2,TIMEOUT=3,DEFER=4;
 static int decide(boolean parent,boolean attached,boolean visible,boolean surface,long frames,int generation,int expected,long elapsed){
  if(!parent||(expected>=0&&generation!=expected))return RETIRED;
  if(!visible)return DEFER;
  if(attached&&surface&&frames>0)return READY;
  return elapsed>=20000?TIMEOUT:WAIT;
 }
}
