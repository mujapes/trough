package com.trough.hot;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Surface;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Map;
/** One-shot non-launching check. No screen capture, input injection or account access. */
final class NativeAppHostProbe {
 static void pending(Activity activity) {
  File home=new File(activity.getFilesDir(),"home/.trough");
  File request=new File(home,"app-host-probe");
  if(!request.isFile()||!request.delete())return;
  ArrayList<Object> checks=new ArrayList<Object>();
  DisplayManager dm=(DisplayManager)activity.getSystemService(Context.DISPLAY_SERVICE);
  ActivityManager am=(ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);
  for(int flags:new int[]{DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY|DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC}) {
   SurfaceTexture texture=null;Surface surface=null;VirtualDisplay display=null;
   Map<String,Object> result=Json.map("flags",flags,"created",false,"launched",false);
   try {
    texture=new SurfaceTexture(false);texture.setDefaultBufferSize(320,240);surface=new Surface(texture);
    display=dm.createVirtualDisplay("Trough app-host capability",320,240,160,surface,flags);
    if(display==null)throw new IllegalStateException("No virtual display returned");
    result.put("created",true);result.put("display_id",display.getDisplay().getDisplayId());
    ArrayList<Object> packages=new ArrayList<Object>();
    for(String name:new String[]{"com.snapchat.android","com.android.settings"}) {
     Map<String,Object> app=Json.map("package",name);packages.add(app);
     Intent launch=activity.getPackageManager().getLaunchIntentForPackage(name);
     app.put("launch_intent_found",launch!=null);
     if(launch!=null){app.put("component",launch.getComponent().flattenToShortString());app.put("allowed_on_display",am.isActivityStartAllowedOnDisplay(activity,display.getDisplay().getDisplayId(),launch));}
    }
    result.put("packages",packages);
   }catch(Exception error){result.put("error_type",error.getClass().getSimpleName());result.put("error",String.valueOf(error.getMessage()));}
   finally {
    if(display!=null)display.release();if(surface!=null)surface.release();if(texture!=null)texture.release();
   }
   checks.add(result);
  }
  try {
   File tmp=new File(home,"app-host-probe-result.tmp");
   try(FileOutputStream out=new FileOutputStream(tmp)){out.write(Json.write(Json.map("time_ms",System.currentTimeMillis(),"pid",android.os.Process.myPid(),"checks",checks,"activities_launched",0,"screen_capture",false,"input_injection",false,"limits","Preflight only: allowed_on_display is not proof of launch, rendering, login or touch control")).getBytes("UTF-8"));}
   if(!tmp.renameTo(new File(home,"app-host-probe-result.json")))throw new java.io.IOException("Could not publish probe result");
  }catch(Exception error){android.util.Log.w("Trough","App host capability result",error);}
 }
}
