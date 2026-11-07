package com.trough.hot;
import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.provider.Settings;
import java.io.*;
import java.util.*;
/** Explicit one-shot app observation requests; launch uses the app UID, not shell identity. */
final class NativeAppReference {
 static void pending(Activity activity){File dir=new File(activity.getFilesDir(),"home/.trough"),request=new File(dir,"reference-open-youtube");if(!request.isFile()||!request.delete())return;
  Map<String,Object> result=Json.map("time_ms",System.currentTimeMillis(),"package","com.google.android.apps.youtube.music","launch_requested",false);
  try{Intent launch=activity.getPackageManager().getLaunchIntentForPackage("com.google.android.apps.youtube.music");if(launch==null)throw new IOException("YouTube Music launch unavailable");activity.startActivity(launch);result.put("launch_requested",true);result.put("enabled_accessibility_services",Settings.Secure.getString(activity.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));PackageInfo own=activity.getPackageManager().getPackageInfo(activity.getPackageName(),PackageManager.GET_SERVICES);ArrayList<String> services=new ArrayList<String>();if(own.services!=null)for(ServiceInfo service:own.services)if("android.permission.BIND_ACCESSIBILITY_SERVICE".equals(service.permission))services.add(service.name);result.put("declared_accessibility_services",services);result.put("limit","Activity launch requested; external screen observation and touch control are not yet verified.");}catch(Exception error){result.put("error",error.toString());}
  try(FileOutputStream out=new FileOutputStream(new File(dir,"reference-app-result.json"))){out.write(Json.write(result).getBytes("UTF-8"));}catch(Exception ignored){}
 }
}
