package com.trough.hot;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.*;
/** One-pointer body-force input; capture never transfers silently to another finger. */
final class HalfSwordBodyPushPad extends View {
 final HalfSwordView owner;final Paint paint=new Paint(3);int pointer=-1;float x,y;final HalfSwordMoveKeys keys=new HalfSwordMoveKeys();
 HalfSwordBodyPushPad(Context c,HalfSwordView v){super(c);owner=v;setFocusable(true);setContentDescription("Move with a sliding push. Press Run, then hold and drag; release to brake. Tap while paused for one push. Arrow keys supported.");}
 void clearInput(){keys.clear();pointer=-1;x=y=0;capture(false);postInvalidate();}
 void capture(boolean active){owner.captureInput(HalfSwordTouchCapture.BODY,active);}
 void update(float px,float py,boolean begin){float radius=Math.max(1,Math.min(getWidth(),getHeight())*.4f);x=(px-getWidth()*.5f)/radius;y=(getHeight()*.5f-py)/radius;float length=(float)Math.hypot(x,y);if(length>1){x/=length;y/=length;}owner.bodyPush(x,y,begin);invalidate();}
 protected void onDetachedFromWindow(){owner.cancelBodyPush();super.onDetachedFromWindow();}
 protected void onDraw(Canvas canvas){super.onDraw(canvas);float cx=getWidth()*.5f,cy=getHeight()*.5f,r=Math.min(getWidth(),getHeight())*.4f;paint.setColor(0xff25372f);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(cx,cy,r,paint);paint.setColor(0xff81bba0);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);canvas.drawLine(cx-r,cy,cx+r,cy,paint);canvas.drawLine(cx,cy-r,cx,cy+r,paint);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(cx+x*r,cy-y*r,6*getResources().getDisplayMetrics().density,paint);paint.setColor(0xff8ae0bb);paint.setTextSize(getResources().getDisplayMetrics().scaledDensity*11);canvas.drawText("Move",8,paint.getTextSize()+6,paint);}
 public boolean onTouchEvent(android.view.MotionEvent e){int action=e.getActionMasked();if(action==android.view.MotionEvent.ACTION_DOWN){keys.clear();pointer=e.getPointerId(0);capture(true);update(e.getX(),e.getY(),true);return true;}if(action==android.view.MotionEvent.ACTION_MOVE){int at=e.findPointerIndex(pointer);if(at<0){capture(false);owner.cancelBodyPush();}else update(e.getX(at),e.getY(at),false);return true;}if(action==android.view.MotionEvent.ACTION_UP||action==android.view.MotionEvent.ACTION_CANCEL||(action==android.view.MotionEvent.ACTION_POINTER_UP&&e.getPointerId(e.getActionIndex())==pointer)){capture(false);owner.cancelBodyPush();return true;}return true;}
 static int keyBit(int key){return key==KeyEvent.KEYCODE_DPAD_LEFT?1:key==KeyEvent.KEYCODE_DPAD_RIGHT?2:key==KeyEvent.KEYCODE_DPAD_UP?4:key==KeyEvent.KEYCODE_DPAD_DOWN?8:0;}
 void applyKeys(boolean begin){x=keys.x();y=keys.y();float length=(float)Math.hypot(x,y);if(length>1){x/=length;y/=length;}owner.bodyPush(x,y,begin);invalidate();}
 public boolean onKeyDown(int key,KeyEvent event){int bit=keyBit(key);if(bit==0)return super.onKeyDown(key,event);if(!keys.accepts(bit,event.getRepeatCount(),pointer>=0))return true;boolean fresh=keys.press(bit);applyKeys(fresh&&event.getRepeatCount()==0);return true;}
 public boolean onKeyUp(int key,KeyEvent event){int bit=keyBit(key);if(bit==0)return super.onKeyUp(key,event);if((keys.held&bit)==0)return true;keys.release(bit);if(keys.held==0)owner.cancelBodyPush();else applyKeys(false);return true;}
 protected void onFocusChanged(boolean gain,int direction,android.graphics.Rect previous){super.onFocusChanged(gain,direction,previous);if(!gain&&keys!=null&&keys.held!=0)owner.cancelBodyPush();}
}
