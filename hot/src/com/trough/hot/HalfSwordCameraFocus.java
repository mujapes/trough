package com.trough.hot;
/** Horizontal duel midpoint; camera yaw and physical inputs remain independent. */
final class HalfSwordCameraFocus {
 static float metres(double playerCm,double opponentCm){
  if(!Double.isFinite(opponentCm))return (float)(playerCm*.01);
  return (float)(playerCm*.005+opponentCm*.005);
 }
}
