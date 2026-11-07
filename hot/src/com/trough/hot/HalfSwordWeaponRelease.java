package com.trough.hot;

/** Recovered weapon timeline decisions. Force/state are observations supplied by caller.
 * No solver, inferred force units, struct layout, or action implementation lives here.
 * Source: Willie_BP Timeline_0/1 update iCode434773/434783, TechDemo18478397.
 */
public strictfp final class HalfSwordWeaponRelease {
 public static final int NONE=0,RELEASE_GRAB=1,BREAK_CONSTRAINT=2,SETUP_RIGHT=3,SETUP_LEFT=4;
 public static final class Input {
  public boolean left,grabbed,blockDrop,gripSlide,softNotAttached;
  public boolean leftOffhand,leftSoftNotAttached,leftSoftNotRotated,rightSoftNotAttached,twoHanded;
  public boolean weaponConstraintValid,grabConstraintValid;
  public int gripType;
  public double releaseRate,armTonus,bodyTonus,armHealth,consciousness,constraintRate,forceMagnitude,dt;
 }
 public static final class Result {
  public final double releaseRate,threshold;
  public final int action;
  /** Only meaningful for left BREAK_CONSTRAINT; original boolean call argument. */
  public final boolean breakLeftTransfer;
  public final boolean constraintObserved;
  Result(double rate,double threshold,int action,boolean transfer,boolean observed){this.releaseRate=rate;this.threshold=threshold;this.action=action;this.breakLeftTransfer=transfer;this.constraintObserved=observed;}
 }
 static double clamp(double v,double a,double b){return v<a?a:v<b?v:b;}
 static void finite(double v){if(Double.isNaN(v)||Double.isInfinite(v))throw new IllegalArgumentException("Finite observed inputs required");}
 public static Result update(Input s){
  if(s==null)throw new IllegalArgumentException("Missing weapon state");
  finite(s.releaseRate);finite(s.armTonus);finite(s.bodyTonus);finite(s.armHealth);finite(s.consciousness);finite(s.constraintRate);finite(s.forceMagnitude);finite(s.dt);
  if(s.dt<0||s.forceMagnitude<0||s.gripType<0||s.gripType>255)throw new IllegalArgumentException("Invalid observed domain");
  boolean weapon=s.gripType!=0||(s.left&&s.leftOffhand);
  if(!(weapon?s.weaponConstraintValid:s.grabConstraintValid))return new Result(s.releaseRate,0,NONE,false,false);
  double factor=s.grabbed?clamp(s.constraintRate,.25,1):1;
  double strength=(s.armTonus*s.bodyTonus*5)*(s.consciousness*.01*factor);
  strength=clamp(strength,s.grabbed?0:s.armHealth,strength);
  double threshold=strength+((s.gripType==6||s.gripType==7)?500:0);
  if(!(s.forceMagnitude>threshold&&!s.blockDrop))return new Result(clamp(s.releaseRate-s.dt,0,1),threshold,NONE,false,true);
  double rate=clamp(s.releaseRate+s.dt*(10*clamp((strength-500)/(0.-500),0,1)),0,1);
  int action=NONE;boolean transfer=false;
  if(rate>=1){
   if(s.gripType!=0){
    if(!s.left){
     if(s.softNotAttached&&!s.gripSlide)action=s.leftOffhand&&s.leftSoftNotAttached&&s.leftSoftNotRotated?BREAK_CONSTRAINT:SETUP_RIGHT;
    }else if(s.leftSoftNotAttached&&s.leftSoftNotRotated&&!s.gripSlide){
     if(s.twoHanded){if(s.rightSoftNotAttached){action=BREAK_CONSTRAINT;transfer=true;}else action=SETUP_RIGHT;}
     else action=SETUP_LEFT;
    }
   }else if(s.grabConstraintValid)action=RELEASE_GRAB;
   else if(s.left&&s.leftOffhand&&s.weaponConstraintValid)action=BREAK_CONSTRAINT;
  }
  return new Result(rate,threshold,action,transfer,true);
 }
 private HalfSwordWeaponRelease(){}
}
