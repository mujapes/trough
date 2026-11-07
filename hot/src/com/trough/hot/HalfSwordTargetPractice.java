package com.trough.hot;
/** Contact practice scoring, not damage. Simulation frames own rearm and flash. */
final class HalfSwordTargetPractice {
 int hits,clearFrames,flashFrames,cooldownFrames;boolean armed;
 void reset(){hits=clearFrames=flashFrames=cooldownFrames=0;armed=false;}
 boolean step(boolean touching){if(flashFrames>0)flashFrames--;if(cooldownFrames>0)cooldownFrames--;if(!touching){if(clearFrames<6)clearFrames++;if(clearFrames>=6&&cooldownFrames==0)armed=true;return false;}clearFrames=0;if(!armed)return false;armed=false;if(hits<Integer.MAX_VALUE)hits++;flashFrames=18;cooldownFrames=60;return true;}
}
