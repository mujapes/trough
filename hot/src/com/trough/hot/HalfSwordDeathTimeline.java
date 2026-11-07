package com.trough.hot;
/** Narrow forward non-looping reference profile; length5 inherited from reference CDO.
 * Source curve keys0:1/3:0; supplied world delta is separate from component delta.
 * Returned effects are records only. No audio, physics, UI or native action dispatch.
 */
final class HalfSwordDeathTimeline {
 enum Effect { UPDATE, DISABLE_ACTOR_TICK, CHECK_RIGHT_HOLDING, CHECK_LEFT_HOLDING }
 static final class Frame {
  final float position,wake;final double tonus;final boolean playing;private final Effect[] effects;
  Frame(float p,float w,double t,boolean run,Effect[] e){position=p;wake=w;tonus=t;playing=run;effects=e;}
  Effect[] effects(){return effects.clone();}
 }
 private float position;private boolean playing;
 void reset(){position=0;playing=false;}
 void play(){playing=true;}
 Frame advance(double componentDelta,double worldDelta,double currentTonus){
  finite(componentDelta);finite(worldDelta);finite(currentTonus);
  if(componentDelta<0||worldDelta<0||Float.isInfinite((float)componentDelta))throw new IllegalArgumentException("Unsupported timeline delta");
  float target=position;if(playing){target=position+(float)componentDelta;if(Float.isInfinite(target))throw new IllegalArgumentException("Timeline overflow");}
  boolean finish=playing&&target>5;target=finish?5:target;
  float wake=target<=0?1:target>=3?0:(float)(1-target/3.0);
  if(!playing)return new Frame(position,wake,currentTonus,false,new Effect[0]);
  double dist=-currentTonus;double tone=dist*dist<1e-8?0:currentTonus+dist*Math.max(0,Math.min(1,worldDelta));finite(tone);
  position=target;playing=!finish;
  if(finish)return new Frame(position,wake,0,false,new Effect[]{Effect.UPDATE,Effect.DISABLE_ACTOR_TICK,Effect.CHECK_RIGHT_HOLDING,Effect.CHECK_LEFT_HOLDING});
  return new Frame(position,wake,tone,true,new Effect[]{Effect.UPDATE});
 }
 private static void finite(double v){if(!Double.isFinite(v))throw new IllegalArgumentException("Nonfinite timeline value");}
}
