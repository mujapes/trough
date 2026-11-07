package com.trough.hot;
import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.zip.*;
/** Read-only asset/path checks. Never loads or initializes Unity. */
final class GameImportProbe {
 static void start(final Context context){
  final File directory=new File(context.getFilesDir(),"home/.trough/games/stumble");File request=new File(directory,"probe-request");if(!request.isFile()||!request.delete())return;
  new Thread(new Runnable(){public void run(){Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("runtime_started",false);result.put("pid",android.os.Process.myPid());
   try{GameAssetContext guest=new GameAssetContext(context,directory,context.getClassLoader());
    result.put("host_code_path",context.getPackageCodePath());result.put("guest_code_path",guest.getPackageCodePath());result.put("guest_resource_path",guest.getPackageResourcePath());result.put("split_paths",Arrays.asList(guest.getApplicationInfo().splitSourceDirs));
    try(ZipFile wrong=new ZipFile(context.getPackageCodePath())){result.put("host_apk_has_game_guid",wrong.getEntry("assets/bin/Data/unity_app_guid")!=null);}
    try(ZipFile correct=new ZipFile(guest.getPackageCodePath())){ZipEntry entry=correct.getEntry("assets/bin/Data/unity_app_guid");result.put("guest_apk_guid_bytes",entry==null?-1:entry.getSize());}
    try(InputStream input=guest.getAssets().open("bin/Data/unity_app_guid")){int bytes=0;while(input.read()!=-1)bytes++;result.put("asset_manager_guid_bytes",bytes);}
    result.put("game_description_id",guest.getResources().getIdentifier("game_view_content_description","string",guest.getPackageName()));
    result.put("imported_bundle_count",guest.getAssets().list("aa/Android").length);
    result.put("native_library_present",new File(guest.getApplicationInfo().nativeLibraryDir,"libunity.so").isFile());
    result.put("paths_within_import",new File(guest.getPackageCodePath()).getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()));
   }catch(Throwable error){StringWriter trace=new StringWriter();error.printStackTrace(new PrintWriter(trace));result.put("error",trace.toString());}
   try{File temp=new File(directory,"asset-probe-result.tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(Json.write(result).getBytes("UTF-8"));}temp.renameTo(new File(directory,"asset-probe-result.json"));}catch(Exception error){android.util.Log.w("Trough","Asset probe",error);}
  }},"Trough game asset path check").start();
 }
}
