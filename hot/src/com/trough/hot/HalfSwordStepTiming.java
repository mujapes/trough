package com.trough.hot;
/** Render-thread recorder; immutable published samples, bounded storage, no per-step allocation. */
final class HalfSwordStepTiming {
 static final String[] NAMES={"motor","player_step_observation","player_pose","opponent_step_pose","contacts","geometry_spring","feedback_drop"};
 static final class Sample {
  final int episode,step;final long wall,cpu;final long[] phaseWall,phaseCpu;final boolean running,swing;
  Sample(int e,int s,long w,long c,long[] pw,long[] pc,boolean r,boolean g){episode=e;step=s;wall=w;cpu=c;phaseWall=pw.clone();phaseCpu=pc.clone();running=r;swing=g;}
 }
 volatile Sample worst,lastSlow;private int episode,step,index;private long beganWall,beganCpu,lastWall,lastCpu;private boolean running,swing;
 private final long[] walls=new long[7],cpus=new long[7];
 void reset(){episode++;worst=null;} // Keep lastSlow with its original episode across Reset.
 void begin(int s,long wall,long cpu,boolean r,boolean g){step=s;beganWall=lastWall=wall;beganCpu=lastCpu=cpu;index=0;running=r;swing=g;}
 void mark(long wall,long cpu){if(index>=7)throw new IllegalStateException("Too many timing phases");walls[index]=Math.max(0,wall-lastWall);cpus[index]=Math.max(0,cpu-lastCpu);index++;lastWall=wall;lastCpu=cpu;}
 void finish(long wall,long cpu){mark(wall,cpu);if(index!=7)throw new IllegalStateException("Incomplete timing phases");long elapsed=Math.max(0,wall-beganWall),used=Math.max(0,cpu-beganCpu);Sample previous=worst;if(previous==null||elapsed>previous.wall||elapsed>=50000000L){Sample sample=new Sample(episode,step,elapsed,used,walls,cpus,running,swing);if(previous==null||elapsed>previous.wall)worst=sample;if(elapsed>=50000000L)lastSlow=sample;}}
}
