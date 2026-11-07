package com.trough.hot;
/** Selected source pain phases only. Caller supplies all state/flags/world delta.
 * Decision samples incoming Pain, then updates regions, then aggregates outgoing Pain.
 * No AI, hit sampling, health mutation or engine effects.
 */
final class HalfSwordPainTick {
 static final class State {
  final double pain,threshold;final boolean shock;final int bodyPart;private final double[] regional;
  State(double p,double t,boolean s,int part,double[] regions){finite(p);finite(t);if(part<0||part>7)throw new IllegalArgumentException("Pain part");regional=numbers(regions,8);pain=p;threshold=t;shock=s;bodyPart=part;}
  double[] regions(){return regional.clone();}
 }
 static final class Inputs {
  private final double[] health;private final boolean[] broken,missingMass;
  final boolean snapped,neckDismembered,headless;
  Inputs(double[] hp,boolean[] b,boolean[] m,boolean n,boolean d,boolean h){health=numbers(hp,8);broken=flags(b);missingMass=flags(m);snapped=n;neckDismembered=d;headless=h;}
 }
 static State evaluate(State s,Inputs in,double delta){
  if(s==null||in==null)throw new IllegalArgumentException("Missing pain phase input");finite(delta);if(delta<0)throw new IllegalArgumentException("Negative world delta");
  boolean shock=s.pain>=7.5;double threshold=interp(s.threshold,0,delta,1);double[] region=s.regional.clone();
  for(int i=0;i<8;i++){
   double target=5*clamp((in.health[i]-100)/-100,0,1);int limb=i==2?0:i==3?1:i==6?2:i==7?3:-1;
   if(i==1&&(in.snapped||in.neckDismembered)||limb>=0&&(in.broken[limb]||in.missingMass[limb]))target=7.5;
   region[i]=interp(region[i],target,delta,1);
  }
  double pain=s.pain;int part=s.bodyPart;
  if(in.snapped)pain=0;
  else if(in.headless)pain=interp(pain,0,delta,5);
  else{pain=region[0];part=0;for(int i=1;i<8;i++)if(region[i]>pain){pain=region[i];part=i;}if(in.health[1]<=25)part=1;}
  return new State(pain,threshold,shock,part,region);
 }
 private static double interp(double cur,double target,double dt,double speed){double d=target-cur;return d*d<1e-8?target:cur+d*clamp(dt*speed,0,1);}
 private static double clamp(double v,double lo,double hi){return v<lo?lo:v<hi?v:hi;}
 private static double[] numbers(double[] a,int size){if(a==null||a.length!=size)throw new IllegalArgumentException("Missing regional values");double[] out=a.clone();for(double x:out)finite(x);return out;}
 private static boolean[] flags(boolean[] a){if(a==null||a.length!=4)throw new IllegalArgumentException("Missing limb flags");return a.clone();}
 private static void finite(double x){if(!Double.isFinite(x))throw new IllegalArgumentException("Nonfinite pain state");}
}
