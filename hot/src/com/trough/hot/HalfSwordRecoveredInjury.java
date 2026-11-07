package com.trough.hot;

/** Candidate composition of recovered original blocks, not original entry scheduling.
 * Material/contact numeric units and entry/root-mesh decisions are supplied boundaries.
 * Does not apply bleeding, head-crush, death effects or unknown invincibility guards.
 */
final class HalfSwordRecoveredInjury {
 static final double[] PART_SCALE={3,1,1,1,1,.5,1,1};
 static final double[] HP_RATE={15,7.5,.1,.1,5,2.5,.1,.1};
 static final double[] HP_FLOOR={1,2.5,50,50,5,10,50,50};
 static final String[][] BONES={{"head"},{"neck_01","neck_02"},{"upperarm_r","lowerarm_r","hand_r"},{"upperarm_l","lowerarm_l","hand_l"},{"spine_04","spine_05","clavicle_r","clavicle_l"},{"pelvis","spine_01","spine_02","spine_03"},{"thigh_r","calf_r","foot_r"},{"thigh_l","calf_l","foot_l"}};
 static final class State {
  double health,tonus,limbRate,overallRate;final double[] region=new double[8];boolean dress,fallen;
 }
 static final class Hit {
  double vx,vy,vz,ix,iy,iz,cut,rigidity,stab,defCut,defStab,defBlunt,kick,damageRate;
  String bone;boolean ignoreInvincibility,shockwave,rootMesh,entryAllowed;
 }
 static final class Result {
  boolean entered,hpEligible,deathGatePassed;int part;
  double raw,scaled,cut,regionAfter,healthAfter;
 }
 static double clamp(double v,double a,double b){return v<a?a:v<b?v:b;}
 static double map(double v,double a,double b,double c,double d){double range=b-a;double alpha=Math.abs(range)<=1e-8?(v>=b?1:0):(v-a)/range;return c+(d-c)*clamp(alpha,0,1);}
 static void finite(double v){if(!Double.isFinite(v))throw new IllegalArgumentException("Nonfinite injury value");}
 static int part(String bone){if(bone==null)throw new IllegalArgumentException("Missing bone");for(int i=0;i<8;i++)for(String name:BONES[i])if(name.equals(bone))return i;return 4;}
 static Result apply(State s,Hit h){
  if(s==null||h==null)throw new IllegalArgumentException("Missing injury input");
  finite(h.damageRate);Result out=new Result();if(!h.entryAllowed)return out;
  for(double v:new double[]{h.vx,h.vy,h.vz,h.ix,h.iy,h.iz,h.cut,h.rigidity,h.stab,h.defCut,h.defStab,h.defBlunt,h.kick,s.health,s.tonus,s.limbRate,s.overallRate})finite(v);
  for(double v:s.region)finite(v);
  int p=part(h.bone);double speed=Math.sqrt(h.vx*h.vx+h.vy*h.vy+h.vz*h.vz),cut=h.cut+.1;
  double force=(cut*speed)*h.rigidity;finite(force);if(force==0)throw new IllegalArgumentException("Unsupported zero impact denominator");
  double defense=(h.defCut*(1-h.stab)+h.defStab*h.stab)*1000;
  double ratio=clamp(force-defense,0,100000)/force,cutOut=ratio*cut;
  double base=h.rigidity*speed,cutDamage=base*ratio,remaining=base-cutDamage;
  double residual=remaining-h.defBlunt*100,quarter=remaining*.25,negative=-clamp(residual,-1e12,0);
  double raw=(clamp(residual,0,1e10)+cutDamage)+map(quarter,0,negative,0,quarter);
  double scaled=(raw*h.damageRate)*1.125;
  // Verified complex-damage caller supplies Inside=false and Stab?=false.
  boolean eligible=scaled>1000&&(!h.shockwave||raw>=5000);
  double floor=HP_FLOOR[p]*((s.fallen&&!h.shockwave||raw>=10000)?.1:1);
  boolean protectedOverride=!s.dress||(h.ignoreInvincibility&&(p==0||p==1||p==4));
  double loss=scaled*HP_RATE[p];loss*=.00125;loss*=h.rootMesh?1:0;loss*=eligible?1:0;loss*=s.overallRate;loss*=protectedOverride?1:0;
  double proposed=s.health-loss,health=proposed>=floor?proposed:Math.min(s.health,floor);
  double limbLoss=scaled*PART_SCALE[p];limbLoss*=eligible?1:0;limbLoss*=s.limbRate;limbLoss*=.01;limbLoss*=s.dress?0:1;
  if(p==1)limbLoss*=map(cutOut,0,1000,1,25);
  double region=clamp(s.region[p]-limbLoss,0,100);
  for(double v:new double[]{raw,scaled,cutOut,health,region,(h.ix*h.rigidity)*h.kick,(h.iy*h.rigidity)*h.kick,(h.iz*h.rigidity)*h.kick,(h.vx*h.rigidity)*h.kick,(h.vy*h.rigidity)*h.kick,(h.vz*h.rigidity)*h.kick})finite(v);
  out.entered=true;out.part=p;out.raw=raw;out.scaled=scaled;out.cut=cutOut;out.regionAfter=region;out.healthAfter=health;out.hpEligible=eligible;out.deathGatePassed=health<=0&&(s.tonus<=0||s.fallen);
  s.health=health;s.region[p]=region;return out;
 }
}
