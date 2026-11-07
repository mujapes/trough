package com.trough.hot;
import android.app.Activity;
import android.app.Service;
import android.content.*;
import android.os.*;
import dalvik.system.InMemoryDexClassLoader;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
/** FD-only, non-initializing class import in a dedicated existing service process. */
final class NativeContainerProbe {
 static final String ACTION="com.trough.hot.CONTAINER_CLASS_PROBE";
 static final String TOKEN="Trough.container.class.1";
 static IBinder bind(Service service,Intent intent){
  if(intent==null||!ACTION.equals(intent.getAction()))return null;
  return new Binder(){protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
   if(code!=1)return super.onTransact(code,data,reply,flags);
   data.enforceInterface(TOKEN);
   Map<String,Object> result=Json.map("pid",android.os.Process.myPid(),"uid",android.os.Process.myUid(),"initialized",false,"native_library_loaded",false);
   try(ParcelFileDescriptor fd=data.readFileDescriptor();InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[16384];
    for(int n;(n=in.read(block))>0;){if(bytes.size()+n>4*1024*1024)throw new IOException("Dex exceeds4MiB bound");bytes.write(block,0,n);}
    InMemoryDexClassLoader loader=new InMemoryDexClassLoader(ByteBuffer.wrap(bytes.toByteArray()),NativeContainerProbe.class.getClassLoader());
    List<Object> classes=new ArrayList<Object>();
    for(String name:new String[]{"top.niunaijun.blackbox.proxy.ProxyManifest","top.niunaijun.blackbox.utils.compat.BuildCompat","top.niunaijun.blackbox.BlackBoxCore","top.niunaijun.blackbox.app.BActivityThread"}){
     Map<String,Object> item=Json.map("name",name);classes.add(item);
     try{Class<?> type=Class.forName(name,false,loader);item.put("loaded",true);item.put("loader_owned",type.getClassLoader()==loader);}
     catch(LinkageError error){item.put("loaded",false);item.put("error",error.toString());}
     catch(ClassNotFoundException error){item.put("loaded",false);item.put("error",error.toString());}
    }
    result.put("dex_bytes",bytes.size());result.put("classes",classes);
   }catch(Exception error){result.put("error",error.toString());}
   reply.writeNoException();reply.writeString(Json.write(result));return true;
  }};
 }
 static void pending(final Activity activity){
  final File home=new File(activity.getFilesDir(),"home/.trough");File request=new File(home,"container-class-probe");
  if(!request.isFile()||!request.delete())return;
  final Handler handler=new Handler(Looper.getMainLooper());
  class Connection implements ServiceConnection {
   boolean bound,done;
   void finish(final Map<String,Object> result){handler.post(new Runnable(){public void run(){
    if(done)return;done=true;if(bound)try{activity.unbindService(Connection.this);}catch(Exception ignored){}
    result.put("host_pid",android.os.Process.myPid());result.put("time_ms",System.currentTimeMillis());result.put("scope","Class loading only; no static initialization, app launch, hooks or account access");
    try{File tmp=new File(home,"container-class-probe-result.tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(Json.write(result).getBytes("UTF-8"));}if(!tmp.renameTo(new File(home,"container-class-probe-result.json")))throw new IOException("Publish failed");}catch(Exception error){android.util.Log.w("Trough","Container probe result",error);}
   }});}
   public void onServiceConnected(ComponentName name,final IBinder binder){if(done)return;new Thread(new Runnable(){public void run(){
    Parcel data=Parcel.obtain(),reply=Parcel.obtain();Map<String,Object> result;
    try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(new File(activity.getFilesDir(),"work/trough-lab/android-os-mirror/container-reuse/classes.dex"),ParcelFileDescriptor.MODE_READ_ONLY)){
     data.writeInterfaceToken(TOKEN);data.writeFileDescriptor(fd.getFileDescriptor());
     if(!binder.transact(1,data,reply,0))throw new IOException("Binder rejected class probe");reply.readException();result=Json.obj(Json.parse(reply.readString()));
     if(((Number)result.get("pid")).intValue()==android.os.Process.myPid())throw new IOException("Expected dedicated process");
    }catch(Exception error){result=Json.map("error",error.toString());}finally{data.recycle();reply.recycle();}
    finish(result);
   }},"Trough container import check").start();}
   public void onServiceDisconnected(ComponentName name){finish(Json.map("error","Service disconnected"));}
   public void onNullBinding(ComponentName name){finish(Json.map("error","Service returned null binder"));}
  }
  final Connection connection=new Connection();
  try{Intent intent=new Intent(ACTION);intent.setClassName(activity.getPackageName(),"com.trough.app.Slot$S14");connection.bound=activity.bindService(intent,connection,Context.BIND_AUTO_CREATE);if(!connection.bound)connection.finish(Json.map("error","Binding rejected"));else handler.postDelayed(new Runnable(){public void run(){connection.finish(Json.map("error","Probe deadline exceeded"));}},10000);}
  catch(Exception error){connection.finish(Json.map("error",error.toString()));}
 }
}
