package com.trough.hot;
import android.app.Activity;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.res.*;
import android.content.res.loader.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import dalvik.system.DexClassLoader;
/** Experimental imported Unity surface; no external Activity launch. */
final class GameSurface {
 static final int TAG=0x7f0d7492;
 static void start(final Context context,final Activity activity,final ViewGroup root){
  if(activity==null)return;
  File reset=new File(context.getFilesDir(),"home/.trough/games/stumble/reset-failed-surface");if(reset.isFile()&&reset.delete())root.getRootView().setTag(TAG,null);
  Object retained=root.getRootView().getTag(TAG);
  if(retained instanceof View[]){for(View view:(View[])retained){if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);root.addView(view);}return;}
  final File directory=new File(context.getFilesDir(),"home/.trough/games/stumble");File request=new File(directory,"start-request");if(!request.isFile()||!request.delete())return;
  new Thread(new Runnable(){public void run(){try{
   String dex=new File(directory,"base.apk").getPath()+File.pathSeparator+new File(directory,"split_InstallTime.apk").getPath()+File.pathSeparator+new File(directory,"split_config.arm64_v8a.apk").getPath();
   final DexClassLoader loader=new DexClassLoader(dex,context.getCodeCacheDir().getPath(),new File(directory,"lib").getPath(),context.getClassLoader());
   final Context guest=new GameAssetContext(activity,directory,loader);
   new Handler(Looper.getMainLooper()).post(new Runnable(){public void run(){
    final FrameLayout surface=new FrameLayout(activity);surface.setBackgroundColor(0xff101820);surface.setLayoutParams(new ViewGroup.LayoutParams(-1,Math.max(300,activity.getResources().getDisplayMetrics().widthPixels*9/16)));
    final TextView status=new TextView(activity);status.setText("Starting Stumble Guys…");status.setTextColor(0xffe2eee8);status.setPadding(16,80,16,16);surface.addView(status);
    root.addView(surface);root.getRootView().setTag(TAG,new View[]{surface});
    final Object[] player={root.getRootView().getTag(TAG+1)};
    if(player[0]==null)player[0]=recover();
    record(directory,"constructing",null);
    Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
    try{
     Class<?> callback=Class.forName("com.unity3d.player.IUnityPlayerLifecycleEvents",false,loader);
     Object events=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{callback},new InvocationHandler(){public Object invoke(Object proxy,Method method,Object[] args){if(method.getName().equals("toString"))return "Trough game lifecycle";if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);if(method.getName().equals("equals"))return proxy==args[0];record(directory,method.getName(),null);return null;}});
     Class<?> type=Class.forName("com.unity3d.player.UnityPlayerForActivityOrService",true,loader);
     if(player[0]==null)player[0]=type.getConstructor(Context.class,callback).newInstance(guest,events);
     else type=player[0].getClass();
     root.getRootView().setTag(TAG+1,player[0]);
     View view=(View)type.getMethod("getFrameLayout").invoke(player[0]);if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);surface.addView(view,0,new FrameLayout.LayoutParams(-1,-1));status.setVisibility(View.GONE);
     invoke(player[0],"onStart");invoke(player[0],"onResume");type.getMethod("windowFocusChanged",boolean.class).invoke(player[0],true);view.requestFocus();
     record(directory,"native_view_attached",null);
    }catch(Throwable error){Throwable cause=error instanceof InvocationTargetException?((InvocationTargetException)error).getTargetException():error;status.setText("Stumble Guys startup is being repaired.");StringWriter trace=new StringWriter();cause.printStackTrace(new PrintWriter(trace));record(directory,"startup_failed",trace.toString());root.getRootView().setTag(TAG,null);}
    finally{Thread.setDefaultUncaughtExceptionHandler(previous);}
   }});
  }catch(Throwable error){record(directory,"preparation_failed",error.toString());}}},"Trough game surface preparation").start();
 }
 static Object recover(){
  for(Thread thread:Thread.getAllStackTraces().keySet())if(thread.getClass().getName().equals("com.unity3d.player.Q")){
   for(Field field:thread.getClass().getDeclaredFields())try{field.setAccessible(true);Object value=field.get(thread);if(value!=null&&value.getClass().getName().equals("com.unity3d.player.UnityPlayerForActivityOrService")&&value.getClass().getMethod("getFrameLayout").invoke(value)!=null)return value;}catch(Exception ignored){}
  }return null;
 }
 static void invoke(Object target,String name){if(target==null)return;try{target.getClass().getMethod(name).invoke(target);}catch(Exception error){android.util.Log.w("Trough","Game lifecycle "+name,error);}}
 static synchronized void record(File directory,String phase,String error){try{Map<String,Object> result=Json.map("phase",phase,"error",error,"pid",android.os.Process.myPid(),"observed_ms",System.currentTimeMillis(),"external_activity_launched",false);File temp=new File(directory,"surface-result.tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(Json.write(result).getBytes("UTF-8"));}temp.renameTo(new File(directory,"surface-result.json"));}catch(Exception ignored){}}
}
