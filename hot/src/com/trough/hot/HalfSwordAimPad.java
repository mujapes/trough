package com.trough.hot;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
/** Native prototype shoulder orientation input; no original control-policy claim. */
final class HalfSwordAimPad extends View {
 boolean swipeMode,rightHand=true;final HalfSwordSwipeAim swipe=new HalfSwordSwipeAim();
 final HalfSwordView viewer;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);int pointer=-1;final HalfSwordMoveKeys keys=new HalfSwordMoveKeys();
 HalfSwordAimPad(Context c,HalfSwordView v){super(c);viewer=v;setFocusable(true);setContentDescription("Sword aim. Drag for right arm pitch and yaw; arrow keys adjust aim.");}
 protected void onDraw(Canvas canvas){super.onDraw(canvas);float w=getWidth(),h=getHeight();paint.setColor(0xff10221e);canvas.drawRect(0,0,w,h,paint);paint.setColor(0xff3b675b);paint.setStrokeWidth(1);canvas.drawLine(w*.5f,0,w*.5f,h,paint);canvas.drawLine(0,h*.5f,w,h*.5f,paint);int pitch,yaw;synchronized(viewer.signal){pitch=rightHand?viewer.characterRightPitch:viewer.characterLeftPitch;yaw=rightHand?viewer.characterRightYaw:viewer.characterLeftYaw;}float x=w*(yaw+45)/90f,y=h*(45-pitch)/90f;paint.setColor(0xff8ae0bb);canvas.drawCircle(x,y,Math.max(4,getResources().getDisplayMetrics().density*3),paint);paint.setTextSize(getResources().getDisplayMetrics().scaledDensity*11);canvas.drawText(rightHand?(swipeMode?"R swipe":"R aim"):viewer.leftYawSupported()?(swipeMode?"L swipe":"L aim"):"L lift",8,paint.getTextSize()+6,paint);}
 void clearInput(){keys.clear();pointer=-1;viewer.captureInput(HalfSwordTouchCapture.AIM,false);}
 protected void onDetachedFromWindow(){clearInput();super.onDetachedFromWindow();}
 void describe(){setContentDescription(rightHand?(swipeMode?"Right hand swipe. Drag from anywhere; lift to keep aim.":"Right hand aim. Drag for pitch and yaw."):viewer.leftYawSupported()?"Left hand aim. Drag to guide pitch and yaw; sword stays in right hand.":"Left hand lift. Drag vertically; horizontal control is not available yet.");}
 void setSwipeMode(boolean enabled){viewer.sceneTouch.clear();clearInput();swipeMode=enabled;describe();invalidate();}
 void selectHand(boolean right){viewer.sceneTouch.clear();clearInput();rightHand=right;describe();invalidate();}
 void sendAim(int pitch,int yaw){if(rightHand)viewer.requestAim(pitch,yaw);else viewer.requestLeftAim(pitch,yaw);}
 void beginAim(float x,float y){if(swipeMode){synchronized(viewer.signal){swipe.begin(x,y,rightHand?viewer.characterRightPitch:viewer.characterLeftPitch,rightHand?viewer.characterRightYaw:viewer.characterLeftYaw);}}else aim(x,y);}
 void moveAim(float x,float y){if(swipeMode){swipe.move(x,y,getWidth(),getHeight());sendAim(Math.round(swipe.pitch),Math.round(swipe.yaw));}else aim(x,y);}
 void aim(float x,float y){int yaw=Math.round(Math.max(-45,Math.min(45,(x/Math.max(1,getWidth())-.5f)*90))),pitch=Math.round(Math.max(-45,Math.min(45,(.5f-y/Math.max(1,getHeight()))*90)));sendAim(pitch,yaw);}
 public boolean onTouchEvent(MotionEvent event){int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN){viewer.sceneTouch.clear();keys.clear();pointer=event.getPointerId(0);viewer.captureInput(HalfSwordTouchCapture.AIM,true);beginAim(event.getX(),event.getY());return true;}int index=event.findPointerIndex(pointer);if(action==MotionEvent.ACTION_MOVE){if(index>=0)moveAim(event.getX(index),event.getY(index));else clearInput();return true;}if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL||(action==MotionEvent.ACTION_POINTER_UP&&event.getPointerId(event.getActionIndex())==pointer)){boolean clicked=pointer>=0&&action==MotionEvent.ACTION_UP;clearInput();if(clicked)performClick();return true;}return true;}
 public boolean performClick(){super.performClick();return true;}
 public boolean onKeyDown(int key,KeyEvent event){int bit=key==KeyEvent.KEYCODE_DPAD_CENTER?16:HalfSwordBodyPushPad.keyBit(key);if(bit==0)return super.onKeyDown(key,event);if(!keys.accepts(bit,event.getRepeatCount(),pointer>=0||viewer.sceneTouch.first>=0))return true;keys.press(bit);int pitch,yaw;synchronized(viewer.signal){pitch=rightHand?viewer.characterRightPitch:viewer.characterLeftPitch;yaw=rightHand?viewer.characterRightYaw:viewer.characterLeftYaw;}if(key==KeyEvent.KEYCODE_DPAD_LEFT)yaw-=5;else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)yaw+=5;else if(key==KeyEvent.KEYCODE_DPAD_UP)pitch+=5;else if(key==KeyEvent.KEYCODE_DPAD_DOWN)pitch-=5;else if(key==KeyEvent.KEYCODE_DPAD_CENTER){pitch=0;yaw=0;}else return super.onKeyDown(key,event);sendAim(Math.max(-45,Math.min(45,pitch)),Math.max(-45,Math.min(45,yaw)));return true;}
 public boolean onKeyUp(int key,KeyEvent event){int bit=key==KeyEvent.KEYCODE_DPAD_CENTER?16:HalfSwordBodyPushPad.keyBit(key);if(bit==0)return super.onKeyUp(key,event);keys.release(bit);return true;}
 protected void onFocusChanged(boolean gain,int direction,android.graphics.Rect previous){super.onFocusChanged(gain,direction,previous);if(!gain&&keys!=null)keys.clear();}
}
