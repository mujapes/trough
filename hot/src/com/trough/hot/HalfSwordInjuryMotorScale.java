package com.trough.hot;
/** Explicit prototype health-to-arm-stiffness mapping, NOT recovered tonus logic.
 * Stateless: absent/reset/query-gap observation falls back to the existing scale1.
 * Does not write buffers, change pose/damping/forces, or authorize scene integration.
 */
final class HalfSwordInjuryMotorScale {
 static final class Observation {
  final int epoch,completedStep;final double health;final boolean queryValid;
  Observation(int e,int s,double h,boolean valid){epoch=e;completedStep=s;health=h;queryValid=valid;}
 }
 static final class Decision {
  final double stiffnessScale;final boolean applied;final String reason;
  Decision(double s,boolean a,String r){stiffnessScale=s;applied=a;reason=r;}
 }
 static Decision evaluate(boolean enabled,double minimumScale,int expectedEpoch,int expectedCompletedStep,Observation observed){
  if(!Double.isFinite(minimumScale)||minimumScale<0||minimumScale>1)throw new IllegalArgumentException("Prototype stiffness floor");
  if(!enabled)return fallback("disabled");
  if(expectedEpoch<0||expectedCompletedStep<0)return fallback("invalid-clock");
  if(observed==null)return fallback("unavailable");
  if(!observed.queryValid)return fallback("query-gap");
  if(observed.epoch!=expectedEpoch||observed.completedStep!=expectedCompletedStep)return fallback("stale-or-foreign");
  if(!Double.isFinite(observed.health)||observed.health<0||observed.health>100)return fallback("invalid-health");
  return new Decision(minimumScale+(1-minimumScale)*(observed.health/100),true,"prototype-health-scale");
 }
 private static Decision fallback(String reason){return new Decision(1,false,reason);}
}
