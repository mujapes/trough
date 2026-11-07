package com.trough.hot;
import android.view.MotionEvent;
import java.util.Map;
/** Bounded touch evidence, with no invented emotional or linguistic interpretation. */
final class TouchExpression {
 private final float[][] samples=new float[64][6];private int cursor,count,taps;private long began,lastAt,previousTap,gap;private float startX,startY,travel;
 void offer(MotionEvent e,int width,int height){
  int action=e.getActionMasked();long at=e.getEventTime();
  if(action==MotionEvent.ACTION_DOWN){began=at;startX=e.getX();startY=e.getY();travel=0;gap=previousTap==0?0:at-previousTap;}
  travel=Math.max(travel,(float)Math.hypot(e.getX()-startX,e.getY()-startY));
  if(action==MotionEvent.ACTION_MOVE && at-lastAt<12)return;
  float[] row=samples[cursor++%samples.length];row[0]=Math.max(0,at-began);row[1]=e.getX()/Math.max(1,width);row[2]=e.getY()/Math.max(1,height);row[3]=Math.max(0,Math.min(1,e.getPressure()));row[4]=action;row[5]=e.getPointerCount();count=Math.min(count+1,samples.length);lastAt=at;
  if(action==MotionEvent.ACTION_UP){taps++;previousTap=at;}
 }
 Map<String,Object> state(){float[][] copy=new float[count][6];for(int i=0;i<count;i++)copy[i]=samples[Math.floorMod(cursor-count+i,samples.length)].clone();return Json.map("source","native_motion_events","events",count,"completed_gestures",taps,"last_event_uptime_ms",lastAt,"duration_ms",Math.max(0,lastAt-began),"gap_ms",gap,"travel_px",travel,"samples",copy,"meaning","Uninterpreted touch; no emotion or word decoding");}
}
