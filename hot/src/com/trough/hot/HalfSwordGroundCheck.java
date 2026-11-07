package com.trough.hot;
// A presented character check must prove the requested solver mode, including resets.
final class HalfSwordGroundCheck {
 static void request(boolean ground,boolean character){if(ground&&!character)throw new IllegalArgumentException("Ground check requires character lifecycle");}
 static void mode(boolean expected,boolean actual,int steps){
  if(steps<0||steps>2)throw new IllegalStateException("Character diagnostic frame count");
  if(actual!=(steps==0?false:expected))throw new IllegalStateException("Character ground mode mismatch");
 }
}
