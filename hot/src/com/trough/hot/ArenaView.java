package com.trough.hot;
import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.File;
final class ArenaView extends View {
 static final int STATE=0x7f0d7480;final View owner;final Paint p=new Paint(3);final Rect visible=new Rect();ArenaPhysics sim=new ArenaPhysics();long last;float accumulator;int pointer=-1;boolean attached;
 ArenaView(Context c,View o){super(c);owner=o;Object old=o.getTag(STATE);if(old instanceof float[])sim.restore((float[])old);setContentDescription("Sword arena. Drag to move and aim. Sweep sideways to strike. Release to pause.");}
 static File flag(Context c){return new File(c.getFilesDir(),"home/.trough/arena-active");}
 static void show(final Activity a,final android.view.ViewGroup feed,final ScrollView scroll){
  final LinearLayout box=new LinearLayout(a);box.setOrientation(1);box.setTag("trough-arena");
  for(int i=0;i<feed.getChildCount();i++)if("trough-arena".equals(feed.getChildAt(i).getTag())){final View existing=feed.getChildAt(i);scroll.post(new Runnable(){public void run(){scroll.smoothScrollTo(0,existing.getTop());}});return;}
  LinearLayout bar=new LinearLayout(a);TextView label=new TextView(a);label.setText("SWORD STUDY · playable prototype");label.setTextSize(12);label.setTextColor(0xffb6e4d2);bar.addView(label,new LinearLayout.LayoutParams(0,-2,1));
  Button close=new Button(a);close.setText("Close");bar.addView(close);box.addView(bar);
  final ArenaView arena=new ArenaView(a,a.getWindow().getDecorView());int height=(int)(a.getResources().getDisplayMetrics().heightPixels*.43f);box.addView(arena,new LinearLayout.LayoutParams(-1,height));
  close.setOnClickListener(new OnClickListener(){public void onClick(View v){flag(a).delete();feed.removeView(box);}});
  feed.addView(box,new android.view.ViewGroup.LayoutParams(-1,-2));scroll.post(new Runnable(){public void run(){scroll.smoothScrollTo(0,box.getTop());}});
 }
 protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;last=0;post(frame);}
 protected void onDetachedFromWindow(){attached=false;removeCallbacks(frame);sim.held=false;owner.setTag(STATE,sim.save());super.onDetachedFromWindow();}
 final Runnable frame=new Runnable(){public void run(){if(!attached)return;long now=android.os.SystemClock.uptimeMillis();if(getWindowVisibility()==VISIBLE&&getLocalVisibleRect(visible)){float dt=last==0?0:Math.min(.05f,(now-last)/1000f);if(sim.held)accumulator+=dt;else accumulator=0;while(accumulator>=1f/120){sim.step(1f/120);accumulator-=1f/120;}invalidate();}else{sim.held=false;pointer=-1;accumulator=0;}last=now;postDelayed(this,33);}};
 void line(Canvas c,float x,float y,float xx,float yy,int color,float width){p.setColor(color);p.setStrokeWidth(width);c.drawLine(x,y,xx,yy,p);}
 void circle(Canvas c,float x,float y,float r,int color){p.setColor(color);c.drawCircle(x,y,r,p);}
 protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w);setMeasuredDimension(width,(int)(width*.65f));}
 protected void onDraw(Canvas c){super.onDraw(c);c.save();c.scale(getWidth()/1000f,getHeight()/650f);c.drawColor(0xee09191d);
  for(int i=0;i<1000;i+=80)line(c,i,80,i,610,0x183e9c91,1);for(int i=90;i<650;i+=80)line(c,0,i,1000,i,0x183e9c91,1);
  p.setTextSize(26);p.setColor(0xffc4e4d9);c.drawText("HITS "+sim.hits+"   RESOLVE "+sim.health+"   LEARNING · "+sim.round,25,40,p);
  String help=sim.health<=0?"Touch to begin again":sim.hits==0?"Drag to move · sweep to strike · release to pause":sim.hits<4?"Amber means a charge is coming. Keep moving.":"Let the blade carry momentum. Find your rhythm.";
  p.setTextSize(23);p.setColor(0xff9ebbb6);c.drawText(help,25,637,p);
  if(sim.held){circle(c,sim.tx,sim.ty,7,0xff709f96);line(c,sim.x,sim.y,sim.tx,sim.ty,0x4075d7bc,2);}
  circle(c,sim.ex+5,sim.ey+12,30,0x60000000);circle(c,sim.ex,sim.ey,27,sim.flash>0?0xffefd6a5:sim.clock%3>1.8?0xffedab68:0xffae7c91);
  circle(c,sim.x+5,sim.y+12,27,0x60000000);circle(c,sim.x,sim.y,24,0xff8ed7c3);
  float sx=(float)Math.cos(sim.angle),sy=(float)Math.sin(sim.angle);
  line(c,sim.x+sx*25,sim.y+sy*25,sim.x+sx*133,sim.y+sy*133,0x3047e9dd,15);
  line(c,sim.x+sx*25,sim.y+sy*25,sim.x+sx*133,sim.y+sy*133,0xffe0f7ee,5);
  line(c,sim.x+sx*32-sy*14,sim.y+sy*32+sx*14,sim.x+sx*32+sy*14,sim.y+sy*32-sx*14,0xffd2b584,6);c.restore();
 }
 public boolean onTouchEvent(MotionEvent e){int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){if(sim.health<=0)sim=new ArenaPhysics();pointer=e.getPointerId(0);getParent().requestDisallowInterceptTouchEvent(true);sim.held=true;}
  int i=e.findPointerIndex(pointer);if(i>=0){sim.tx=Math.max(0,Math.min(1000,e.getX(i)*1000/getWidth()));sim.ty=Math.max(90,Math.min(600,e.getY(i)*650/getHeight()));}
  if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL||(action==MotionEvent.ACTION_POINTER_UP&&e.getPointerId(e.getActionIndex())==pointer)){sim.held=false;pointer=-1;getParent().requestDisallowInterceptTouchEvent(false);owner.setTag(STATE,sim.save());if(action==MotionEvent.ACTION_UP)performClick();}return true;
 }
 public boolean performClick(){super.performClick();return true;}
}
