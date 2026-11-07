package com.trough.hot;
/** Caller holds the view signal lock. One pending force cannot leak into later controls. */
final class HalfSwordPendingForce {
 private boolean active;private double x,forward;
 void set(boolean forcing,double right,double away){active=forcing;x=forcing?right:0.;forward=forcing?away:0.;}
 void clear(){active=false;x=forward=0.;}
 boolean take(double[] output){if(output==null||output.length<2)throw new IllegalArgumentException("Force pair required");boolean forcing=active;output[0]=x;output[1]=forward;clear();return forcing;}
}
