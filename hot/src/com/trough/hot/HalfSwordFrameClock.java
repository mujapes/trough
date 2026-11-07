package com.trough.hot;
/** Render-thread-only deadline clock. Late work never queues catch-up physics. */
final class HalfSwordFrameClock {
 static final long PERIOD_NS=16666667L;
 private long deadline;private boolean armed;
 void reset(){armed=false;}
 boolean due(long now){return !armed||now-deadline>=0;}
 void stepped(long now){if(!armed||now-deadline>=PERIOD_NS)deadline=now+PERIOD_NS;else deadline+=PERIOD_NS;armed=true;}
 long remaining(long now){return !armed?0:Math.max(0,deadline-now);}
}
