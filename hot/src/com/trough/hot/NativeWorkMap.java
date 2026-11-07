package com.trough.hot;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.io.File;
import java.util.*;
/** Compact activity map from observed source timestamps, build state and command outcomes. */
final class NativeWorkMap extends View {
 final Paint ink=new Paint(3);final String[] labels={"Agent","Interface","Build","Command result"};
 final int[] colors={0xff8ed9ed,0xffc4b0f4,0xffffd28f,0xffa5e3c3};
 final boolean[] active=new boolean[4];final long[] edits=new long[2];boolean buildFailed;boolean busy,buildWasRunning;long agentActivityAge=-1;String detail="Ready";String check="";long buildFinished;
 NativeWorkMap(Context c){super(c);setContentDescription("Agent activity, interface edits, rebuild and last command result. Tap for details.");}
 void update(boolean running,Map<String,Object> reload,List<Object> checks,long activityAgeMs){
  busy=running;agentActivityAge=activityAgeMs;long now=System.currentTimeMillis();StringBuilder info=new StringBuilder();info.append(running?"WORKING":"IDLE").append(" · last agent activity ").append(ageText(activityAgeMs)).append("\n");
  String[][] paths={{"ForgeKeyboard.java","KeyboardDock.java","AutoSendVisibility.java"},{"NativeHomePanel.java","Chat.java","NativeTraceRenderer.java","TraceCategory.java","NativeWorkMap.java"}};
  for(int i=0;i<2;i++){long latest=0;String name="";for(String f:paths[i]){File file=new File(getContext().getFilesDir(),"src/com/trough/hot/"+f);if(file.lastModified()>latest){latest=file.lastModified();name=f;}}edits[i]=latest;active[i]=latest>0&&now>=latest&&now-latest<120000;if(active[i])info.append(labels[i]).append(": ").append(name).append(" edited ").append((now-latest)/1000).append("s ago\n");}
  buildFailed="failed".equals(reload.get("native_state"))||reload.get("dispatcher_error") instanceof String&&!((String)reload.get("dispatcher_error")).isEmpty();active[2]=Boolean.TRUE.equals(reload.get("building"))||Boolean.TRUE.equals(reload.get("pending"));if(buildWasRunning&&!active[2]&&!buildFailed)buildFinished=now;buildWasRunning=active[2];
  info.append(buildFailed?"Build: failed; running interface retained\n":active[2]?"Build: updating the interface\n":buildFinished>0?"Build: finished\n":"Build: no build running\n");
  active[3]=false;check="";if(!checks.isEmpty()){Object last=checks.get(checks.size()-1);if(last instanceof Map){Object state=((Map<?,?>)last).get("state");check=String.valueOf(state);active[3]="failed".equals(state);info.append("Last command: ").append(state).append('\n');}}
  info.append(running?"Agent turn active.":"No agent turn active.");active[0]=running;detail=info.toString();invalidate();
 }
 static String ageText(long ms){if(ms<0)return "not yet observed";long seconds=ms/1000;if(seconds<60)return seconds+"s ago";if(seconds<3600)return seconds/60+"m "+seconds%60+"s ago";return seconds/3600+"h "+seconds/60%60+"m ago";}
 @Override protected void onDraw(Canvas c){super.onDraw(c);float d=getResources().getDisplayMetrics().density,w=getWidth()/4f,y=getHeight()/2f;long now=android.os.SystemClock.uptimeMillis();
  for(int i=0;i<4;i++){float x=w*(i+.5f);boolean done=i==2&&buildFinished>0&&!active[2]&&!buildFailed||i==3&&"passed".equals(check);int color=(i==3&&active[3]||i==2&&buildFailed)?0xffff9587:colors[i];ink.setStyle(Paint.Style.FILL);ink.setColor(color);ink.setAlpha(active[i]?40:done?25:10);c.drawCircle(x,y,10*d,ink);ink.setAlpha(active[i]||done?255:90);ink.setStrokeWidth(1.5f*d);ink.setStrokeCap(Paint.Cap.ROUND);ink.setStyle(Paint.Style.STROKE);
   if(i==0){for(int n=0;n<3;n++){float dx=x+(n-1)*4*d,dy=(busy?(3+2*(float)Math.sin(now/250.0+n)):(n==1?2:1))*d;c.drawLine(dx,y-dy,dx,y+dy,ink);}}
   else if(i==1){for(int row=0;row<2;row++)for(int col=0;col<2;col++){float l=x+(col==0?-6:1)*d,t=y+(row==0?-6:1)*d;c.drawRoundRect(l,t,l+5*d,t+5*d,d,d,ink);}}
   else if(i==2){if(buildFailed){c.drawLine(x-4*d,y-4*d,x+4*d,y+4*d,ink);c.drawLine(x+4*d,y-4*d,x-4*d,y+4*d,ink);}else if(active[2])c.drawArc(x-6*d,y-6*d,x+6*d,y+6*d,(now%1800)*.2f,250,false,ink);else c.drawCircle(x,y,6*d,ink);if(done){c.drawLine(x-3*d,y,x-d,y+2*d,ink);c.drawLine(x-d,y+2*d,x+3*d,y-3*d,ink);}}
   else if(active[3]){c.drawLine(x-4*d,y-4*d,x+4*d,y+4*d,ink);c.drawLine(x+4*d,y-4*d,x-4*d,y+4*d,ink);}else if(done){c.drawLine(x-5*d,y,x-1*d,y+4*d,ink);c.drawLine(x-1*d,y+4*d,x+5*d,y-4*d,ink);}else{c.drawCircle(x,y,6*d,ink);c.drawLine(x-2*d,y,x+2*d,y,ink);}
   if(i==1&&active[i]){float freshness=Math.max(0,Math.min(1,(120000f-(System.currentTimeMillis()-edits[i]))/120000f));ink.setColor(color);ink.setAlpha(80+(int)(175*freshness));ink.setStrokeWidth(1.5f*d);c.drawLine(x-5*d,y+10*d,x-5*d+10*d*freshness,y+10*d,ink);}
  }ink.setStrokeCap(Paint.Cap.BUTT);setContentDescription(detail+". Tap for details.");if((busy||active[2])&&isAttachedToWindow())postInvalidateDelayed(120);
 }
}
