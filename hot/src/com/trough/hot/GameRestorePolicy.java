package com.trough.hot;
/** Startup recovery must never turn an already running roster into a pause. */
final class GameRestorePolicy {
 enum Receipt { NONE, ACCEPTED, DISPATCH_TIMEOUT, FATAL }
 enum Action { WAIT, DISPATCH, COMPLETE, STOP }
 static Action decide(boolean enabled,boolean alive,boolean cancelled,long elapsed,int attempts,long sinceAttempt,Receipt receipt){
  if(!enabled||!alive||cancelled)return Action.STOP;
  if(receipt==Receipt.ACCEPTED)return Action.COMPLETE;
  if(receipt==Receipt.FATAL||elapsed>=120000)return Action.STOP;
  if(attempts==0)return elapsed>=1000?Action.DISPATCH:Action.WAIT;
  if(receipt==Receipt.DISPATCH_TIMEOUT&&sinceAttempt>=15000)return attempts<3?Action.DISPATCH:Action.STOP;
  return Action.WAIT;
 }
}
