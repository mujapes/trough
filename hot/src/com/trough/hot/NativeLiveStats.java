package com.trough.hot;
import android.content.Context;
import android.graphics.*;
import android.net.TrafficStats;
import android.os.SystemClock;
import android.view.View;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
/** Visible-only 4Hz UID counters; actual Window frames, no forced 60Hz animation. */
final class NativeLiveStats extends View {
 final Paint ink=new Paint(3);float refreshHz=60;final boolean solid;volatile boolean closed,probeBusy;ScheduledExecutorService sampler;
 volatile long agentEventAt=-1;
 void agentAge(long age){agentEventAt=age<0?-1:SystemClock.elapsedRealtime()-age;}
 long agentAge(){return agentEventAt<0?-1:Math.max(0,SystemClock.elapsedRealtime()-agentEventAt);}
 volatile double down,up,fps,averageDown,averageUp;final long[] networkTimes=new long[21],networkRx=new long[21],networkTx=new long[21];int networkSamples;volatile long rxDelta,txDelta,latency=-1,probeAt;volatile boolean supported;
 long previousRx=-1,previousTx=-1,previousAt;final long[] frameTimes=new long[9],frameCounts=new long[9];int samples;
 NativeLiveStats(Context context,boolean opaque){super(context);solid=opaque;try{if(context instanceof android.app.Activity)refreshHz=((android.app.Activity)context).getWindowManager().getDefaultDisplay().getRefreshRate();}catch(Exception ignored){}setAlpha(opaque?1f:.55f);setContentDescription("Live UI frame rate and Trough upload/download usage");}
 @Override protected void onAttachedToWindow(){super.onAttachedToWindow();closed=false;sampler=Executors.newSingleThreadScheduledExecutor();sampler.scheduleAtFixedRate(new Runnable(){public void run(){sample();}},0,250,TimeUnit.MILLISECONDS);}
 void sample(){if(closed)return;long now=SystemClock.elapsedRealtime(),rx=TrafficStats.getUidRxBytes(android.os.Process.myUid()),tx=TrafficStats.getUidTxBytes(android.os.Process.myUid());supported=rx>=0&&tx>=0;if(previousAt>0&&supported&&previousRx>=0&&previousTx>=0){double elapsed=Math.max(1,now-previousAt);rxDelta=Math.max(0,rx-previousRx);txDelta=Math.max(0,tx-previousTx);down=rxDelta*1000.0/elapsed;up=txDelta*1000.0/elapsed;}previousAt=now;previousRx=rx;previousTx=tx;if(supported){int ni=networkSamples%21;networkTimes[ni]=now;networkRx[ni]=rx;networkTx[ni]=tx;int oldest=networkSamples<20?0:(networkSamples+1)%21;long elapsed=now-networkTimes[oldest];if(elapsed>0){averageDown=Math.max(0,rx-networkRx[oldest])*1000.0/elapsed;averageUp=Math.max(0,tx-networkTx[oldest])*1000.0/elapsed;}networkSamples++;}
  int index=samples%9;frameTimes[index]=now;frameCounts[index]=Main.liveFrameCount();int start=samples<8?0:(samples+1)%9;long dt=now-frameTimes[start];if(dt>0)fps=Math.max(0,(frameCounts[index]-frameCounts[start])*1000.0/dt);samples++;
  if(now-probeAt>30000&&!probeBusy){probeBusy=true;probeAt=now;new Thread(new Runnable(){public void run(){long begin=SystemClock.elapsedRealtime();HttpURLConnection c=null;try{c=(HttpURLConnection)new URL("https://www.google.com/generate_204").openConnection();c.setRequestMethod("HEAD");c.setConnectTimeout(1500);c.setReadTimeout(1500);c.setUseCaches(false);int code=c.getResponseCode();latency=code>=200&&code<400?SystemClock.elapsedRealtime()-begin:-1;}catch(Exception ignored){latency=-1;}finally{if(c!=null)c.disconnect();probeBusy=false;if(!closed)postInvalidate();}}},"trough-network-response").start();}postInvalidate();
 }
 String line(){return String.format(Locale.US,"%.0f FPS   ↓%.3f  ↑%.3f MB/s   %s",fps,down/1000000,up/1000000,latency>=0?"HTTP "+latency+"ms":"HTTP —");}
 String compactDetail(){long work=Main.liveWorkUs();return "Last action · "+NativeWorkMap.ageText(agentAge())+" ago\n\n"+String.format(Locale.US,"PERFORMANCE\nCapacity (est.) %8.0f FPS\nWindow frames   %8.1f FPS\nDisplay         %8.0f Hz\nFrame work      %8.2f ms\nRecent spike    %8.1f ms\n\nNETWORK · THIS APP\nDownload · 5s   %8.3f MB/s\nUpload · 5s     %8.3f MB/s\nDownload · now  %8.3f MB/s\nUpload · now    %8.3f MB/s\nHTTP response   %8d ms",work>0?1000000.0/work:0,fps,refreshHz,work/1000.0,Main.liveJankUs()/1000.0,averageDown/1000000,averageUp/1000000,down/1000000,up/1000000,latency);}
 String detail(){long work=Main.liveWorkUs();return String.format(Locale.US,"5s average: ↓%.3f ↑%.3f MB/s\n",averageDown/1000000,averageUp/1000000)+(work>0?String.format(Locale.US,"Estimated uncapped capacity: %.0f FPS from %.2fms mean frame work.\n",1000000.0/work,work/1000.0):"Render capacity not yet measured.\n")+"Estimate uses CPU work and available GPU duration, excludes refresh waiting; not an uncapped benchmark.\n"+String.format(Locale.US,"Display %.0f Hz · latest frame %.2f ms\nLast over-budget frame: %.1f ms · %s\n",refreshHz,Main.liveFrameUs()/1000.0,Main.liveJankUs()/1000.0,Main.liveJankAge()<0?"none observed":NativeWorkMap.ageText(Main.liveJankAge()))+String.format(Locale.US,"Window frames completed: %.1f/s (idle screens draw less).\nTrough UID traffic: ↓%.3f ↑%.3f MB/s\nLast 250ms sample: ↓%d ↑%d bytes\nHTTP response: %s · google.com, checked every 30s. Not server ping or packet size.",fps,down/1000000,up/1000000,rxDelta,txDelta,latency>=0?latency+"ms":"unavailable");}
 Map<String,Object> snapshot(){return Json.map("last_agent_activity_age_ms",agentAge(),"ui_fps",fps,"estimated_uncapped_fps",Main.liveWorkUs()>0?1000000.0/Main.liveWorkUs():0,"mean_frame_work_us",Main.liveWorkUs(),"gpu_duration_us",Main.liveGpuUs(),"display_refresh_hz",refreshHz,"last_frame_us",Main.liveFrameUs(),"last_jank_us",Main.liveJankUs(),"last_jank_age_ms",Main.liveJankAge(),"uid_rx_bytes_s",down,"uid_rx_5s_average_bytes_s",averageDown,"uid_tx_5s_average_bytes_s",averageUp,"uid_tx_bytes_s",up,"sample_rx_bytes",rxDelta,"sample_tx_bytes",txDelta,"http_response_ms",latency,"uid_counters_supported",supported,"sample_interval_ms",250,"scope","Trough UID, includes child processes; HTTP probe is not game ping");}
 void slot(Canvas canvas,String value,float left,float width,float baseline,int color){int save=canvas.save();canvas.clipRect(left,0,left+width,getHeight());ink.setColor(color);canvas.drawText(value,left+2*getResources().getDisplayMetrics().density,baseline,ink);canvas.restoreToCount(save);}
 @Override protected void onDraw(Canvas c){super.onDraw(c);float d=getResources().getDisplayMetrics().density,w=getWidth(),first=11*d,second=23*d;ink.setTextSize(9.5f*d);ink.setTypeface(Typeface.MONOSPACE);long age=Main.liveJankAge();boolean spike=age>=0&&age<3000;double ms=(spike?Main.liveJankUs():Main.liveFrameUs())/1000.0;
  long work=Main.liveWorkUs();slot(c,work>0?String.format(Locale.US,"~%4.0f FPS",1000000.0/work):"   — FPS",0,w*.31f,first,0xffb4dcda);
  slot(c,String.format(Locale.US,"%6.1fms",ms),w*.31f,w*.35f,first,spike?0xffffb68d:0xffb4dcda);
  slot(c,latency>=0?String.format(Locale.US,"H%4dms",latency):"H   —",w*.66f,w*.34f,first,0xffb4dcda);
  slot(c,supported?String.format(Locale.US,"↓%7.3f MB/s",averageDown/1000000):"↓ —",0,w*.5f,second,0xffa4d7ec);
  slot(c,supported?String.format(Locale.US,"↑%7.3f MB/s",averageUp/1000000):"↑ —",w*.5f,w*.5f,second,0xffd1b9ed);
  slot(c,"Action "+NativeWorkMap.ageText(agentAge())+" ago",0,w,36*d,0xffb4dcda);
 }
 @Override protected void onDetachedFromWindow(){closed=true;if(sampler!=null)sampler.shutdownNow();super.onDetachedFromWindow();}
}
