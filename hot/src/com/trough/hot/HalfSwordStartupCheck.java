package com.trough.hot;
import android.os.*;import android.graphics.Rect;import android.widget.Button;import java.io.File;import org.json.*;
/** Fresh ordinary Run, without selecting a diagnostic setup or manually stepping. */
final class HalfSwordStartupCheck implements Runnable {
 final boolean split;final HalfSwordView view;final HalfSwordAimCheck input;final Handler ui=new Handler(Looper.getMainLooper());final long began=SystemClock.uptimeMillis();int stage=-1,controlIndex;final String[] primaryNames={"Run / pause","Reset physics","Physics step","Compliant hold · prototype","Release sword"};JSONObject observed;boolean controlsVisible;
 HalfSwordStartupCheck(HalfSwordView v,boolean small){split=small;view=v;input=new HalfSwordAimCheck(v);}
 void start(){ui.postDelayed(this,150);}
 void need(boolean ok,String why)throws Exception{if(!ok)throw new Exception(why);}
 public void run(){try{need(SystemClock.uptimeMillis()-began<15000,"Startup timeout");need(view.isAttachedToWindow()&&view.renderVisible,"Scene unavailable");
  switch(stage){
   case -1:if(split){Button toggle=HalfSwordViewCheck.button(view.controlsRoot(),"Split view");if(toggle!=null)need(toggle.performClick(),"Split toggle rejected");need(HalfSwordViewCheck.button(view.controlsRoot(),"Full screen")!=null,"Split mode unavailable");}stage=0;break;
   case 0:need(view.getHeight()>=HalfSwordView.dp(view.getContext(),80),"Scene squeezed out");need(view.characterSteps==0&&!view.characterRunning,"Startup requires fresh scene");need(view.characterPhysicalHold&&view.characterGroundFriction&&view.characterRefreshedSupport&&view.characterStanceSupport&&view.characterHoldStrength==8,"Supported defaults missing");controlIndex=0;stage=3;break;
   case 3:{Button b=HalfSwordViewCheck.button(view.controlsRoot(),primaryNames[controlIndex]);need(b!=null&&b.isShown(),"Primary control missing: "+primaryNames[controlIndex]);reveal(b);stage=4;break;}
   case 4:{Button b=HalfSwordViewCheck.button(view.controlsRoot(),primaryNames[controlIndex]);Rect rect=new Rect();need(b!=null&&b.getGlobalVisibleRect(rect)&&rect.width()>=HalfSwordView.dp(view.getContext(),36)&&rect.height()>=HalfSwordView.dp(view.getContext(),36),"Primary control unreachable: "+primaryNames[controlIndex]);if(++controlIndex<primaryNames.length)stage=3;else{Button run=HalfSwordViewCheck.button(view.controlsRoot(),"Run / pause");reveal(run);stage=5;}break;}
   case 5:controlsVisible=true;input.click("Run / pause");stage=1;break;
   case 1:if(view.characterSteps<120)break;observed=input.observation();need(view.characterAppliedStanceSupport&&view.characterAppliedHoldStrength==8&&view.characterAppliedGroundFriction,"Default support not applied");input.click("Run / pause");input.pausedSteps=-1;stage=2;break;
   case 2:if(!input.pausedScene(true))break;finish(null);return;
  }ui.postDelayed(this,100);
 }catch(Throwable error){view.characterRunning=false;view.cancelAllTouchInputs();finish(error.toString());}}
 void reveal(Button button){android.view.ViewParent row=button.getParent();if(row instanceof android.view.View&&row.getParent() instanceof android.widget.HorizontalScrollView){android.widget.HorizontalScrollView strip=(android.widget.HorizontalScrollView)row.getParent();strip.requestRectangleOnScreen(new Rect(0,0,strip.getWidth(),strip.getHeight()),true);strip.scrollTo(button.getLeft(),0);}else button.requestRectangleOnScreen(new Rect(0,0,button.getWidth(),button.getHeight()),true);}
 void finish(String error){try{JSONObject out=new JSONObject();out.put("split_requested",split).put("scene_height",view.getHeight()).put("scene_width",view.getWidth());out.put("passed",error==null).put("error",error==null?"":error).put("stage",stage).put("expanded",view.expanded).put("controls_visible",controlsVisible).put("observation",observed).put("steps",view.characterSteps).put("stance_applied",view.characterAppliedStanceSupport).put("hold_strength",view.characterAppliedHoldStrength).put("module",HalfSwordBridge.loadedModuleSha256).put("time_ms",System.currentTimeMillis());Payloads.writeSmall(new File(view.getContext().getFilesDir(),"home/.trough/half-sword-startup-check-result.json"),out.toString());}catch(Exception failed){android.util.Log.e("Trough","Startup receipt failed",failed);}}
}
