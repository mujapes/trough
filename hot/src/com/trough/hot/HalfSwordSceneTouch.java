package com.trough.hot;
import android.view.MotionEvent;
/** Scene-only pointers: one guides the arm, two orbit. Move pad remains separate. */
final class HalfSwordSceneTouch {
 final HalfSwordView view;final HalfSwordSwipeAim aim=new HalfSwordSwipeAim();
 int first=-1,second=-1;boolean orbit,right;float lastCenter;
 HalfSwordSceneTouch(HalfSwordView v){view=v;}
 void clear(){first=second=-1;orbit=false;view.cameraPointer=-1;view.captureInput(HalfSwordTouchCapture.CAMERA,false);}
 boolean touch(MotionEvent e){int action=e.getActionMasked();
  if(action==MotionEvent.ACTION_DOWN){clear();if(view.roundOutcome!=0)return true;first=e.getPointerId(0);view.cameraPointer=first;view.captureInput(HalfSwordTouchCapture.CAMERA,true);if(view.aimPad!=null){view.aimPad.clearInput();right=view.aimPad.rightHand;}else right=true;synchronized(view.signal){aim.begin(e.getX(),e.getY(),right?view.characterRightPitch:view.characterLeftPitch,right?view.characterRightYaw:view.characterLeftYaw);}return true;}
  if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){clear();return true;}
  if(first<0)return true;
  if(action==MotionEvent.ACTION_POINTER_DOWN&&!orbit){int at=e.getActionIndex(),a=e.findPointerIndex(first);if(a<0){clear();return true;}second=e.getPointerId(at);if(second==first)return true;lastCenter=(e.getX(a)+e.getX(at))*.5f;orbit=true;return true;}
  if(action==MotionEvent.ACTION_POINTER_UP){int removed=e.getActionIndex(),a=-1,b=-1;for(int i=0;i<e.getPointerCount();i++)if(i!=removed){if(a<0)a=i;else if(b<0)b=i;}if(a<0){clear();return true;}first=e.getPointerId(a);view.cameraPointer=first;if(b>=0){second=e.getPointerId(b);orbit=true;lastCenter=(e.getX(a)+e.getX(b))*.5f;}else{second=-1;orbit=false;synchronized(view.signal){aim.begin(e.getX(a),e.getY(a),right?view.characterRightPitch:view.characterLeftPitch,right?view.characterRightYaw:view.characterLeftYaw);}}return true;}
  if(action!=MotionEvent.ACTION_MOVE)return true;int a=e.findPointerIndex(first);if(a<0){clear();return true;}
  if(orbit){int b=e.findPointerIndex(second);if(b<0){clear();return true;}float center=(e.getX(a)+e.getX(b))*.5f;view.yaw+=(center-lastCenter)*.012f;lastCenter=center;view.camera[0]=view.yaw;view.wake();}
  else{aim.move(e.getX(a),e.getY(a),view.getWidth(),view.getHeight());if(right)view.requestAim(Math.round(aim.pitch),Math.round(aim.yaw));else view.requestLeftAim(Math.round(aim.pitch),Math.round(aim.yaw));}
  return true;
 }
}
