package com.trough.hot;
import android.app.Service;
import android.content.*;
import android.os.*;
import java.io.*;
/** One-shot process-boundary test; no game classes or native libraries loaded. */
final class GameProcessProbe {
 private static FileObserver requests;
 private static boolean busy;
 static void stopWatching(){if(requests!=null)requests.stopWatching();requests=null;}
 static void watch(final Context context){
  stopWatching();final Context app=context.getApplicationContext();final Handler handler=new Handler(Looper.getMainLooper());
  File directory=new File(app.getFilesDir(),"home/.trough/games/stumble");directory.mkdirs();
  requests=new FileObserver(directory.getPath(),FileObserver.CLOSE_WRITE|FileObserver.MOVED_TO){public void onEvent(int event,String path){if("process-probe-request".equals(path))handler.post(new Runnable(){public void run(){start(app);}});}};
  requests.startWatching();start(app);
 }
 static final String ACTION="com.trough.hot.GAME_PROCESS_PROBE";
 static IBinder bind(Service service,Intent intent){
  if(intent==null||!ACTION.equals(intent.getAction()))return null;
  return new Binder(){protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
   if(code!=1)return super.onTransact(code,data,reply,flags);
   reply.writeNoException();reply.writeInt(android.os.Process.myPid());return true;
  }};
 }
 static void start(final Context context){
  if(busy)return;
  final File directory=new File(context.getFilesDir(),"home/.trough/games/stumble");
  File request=new File(directory,"process-probe-request");if(!request.isFile()||!request.delete())return;
  busy=true;
  final boolean engineProbe=new File(directory,"worker-engine-probe-request").delete();
  final Handler handler=new Handler(Looper.getMainLooper());
  final GameSlotLease lease;try{lease=GameSlotLease.begin(context.getFilesDir(),new File(directory,"slot-probe-candidate"));}catch(Exception e){busy=false;android.util.Log.e("Trough","Slot lease",e);return;}
  class Connection implements ServiceConnection {
   boolean bound,finished; volatile boolean deathObserved; volatile int workerPid=-1,workerUid=-1; volatile boolean surfaceCreated;volatile String surfaceError="";volatile boolean nativeDescriptorRead; volatile int nativeValue=-1; volatile String nativeLoadError=""; volatile int readableNativeValue=-1; volatile String readableNativeError=""; volatile int memoryNativeValue=-1; volatile String memoryNativeError="";
   void finish(String error,int child){if(finished)return;finished=true;busy=false;try{if(lease!=null)lease.restore();}catch(Exception e){error+="; restore: "+e;}
    try{if(bound)context.unbindService(this);}catch(Exception ignored){}
    try{File tmp=new File(directory,"process-probe-result.tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(Json.write(Json.map("surface_created",surfaceCreated,"surface_error",surfaceError,"slot_worker",lease!=null,"host_uid",android.os.Process.myUid(),"host_pid",android.os.Process.myPid(),"child_pid",child,"separate_process",child>0&&child!=android.os.Process.myPid(),"runtime_requested",engineProbe,"gameplay_verified",false,"child_uid",workerUid,"worker_death_observed",deathObserved,"native_descriptor_read",nativeDescriptorRead,"native_value",nativeValue,"native_load_error",nativeLoadError,"readable_native_value",readableNativeValue,"readable_native_error",readableNativeError,"memory_native_value",memoryNativeValue,"memory_native_error",memoryNativeError,"error",error)).getBytes("UTF-8"));}tmp.renameTo(new File(directory,"process-probe-result.json"));}catch(Exception e){android.util.Log.w("Trough","Process probe",e);}
   }
   public void onServiceConnected(ComponentName name,final IBinder binder){try{if(lease!=null)lease.restore();}catch(Exception e){finish("Restore failed: "+e,-1);return;}new Thread(new Runnable(){public void run(){int child=-1;String error="";IBinder worker=null;
    Parcel data=Parcel.obtain(),reply=Parcel.obtain();
    try{
     try(ParcelFileDescriptor dex=ParcelFileDescriptor.open(new File(directory,"worker.dex"),ParcelFileDescriptor.MODE_READ_ONLY)){
      data.writeFileDescriptor(dex.getFileDescriptor());data.writeString("com.trough.worker.GameWorker");
      if(!binder.transact(1,data,reply,0))throw new IOException("Worker load rejected");reply.readException();
     }
     worker=reply.readStrongBinder();if(worker==null)throw new IOException("No worker binder");
     data.recycle();reply.recycle();data=Parcel.obtain();reply=Parcel.obtain();
     worker.transact(1,data,reply,0);reply.readException();child=reply.readInt();workerPid=child;workerUid=reply.readInt();
     if(child==android.os.Process.myPid())throw new IOException("Worker unexpectedly in host process");
     data.recycle();reply.recycle();data=Parcel.obtain();reply=Parcel.obtain();
     try(ParcelFileDescriptor library=ParcelFileDescriptor.open(new File(directory,"lib/libunity.so"),ParcelFileDescriptor.MODE_READ_ONLY)){
      data.writeFileDescriptor(library.getFileDescriptor());worker.transact(3,data,reply,0);reply.readException();nativeDescriptorRead=reply.readInt()==0x7f454c46;
     }
     if(!nativeDescriptorRead)throw new IOException("Native descriptor bytes did not match ELF header");
     data.recycle();reply.recycle();data=Parcel.obtain();reply=Parcel.obtain();
     try(ParcelFileDescriptor library=ParcelFileDescriptor.open(new File(directory,"libtroughworkercheck.so"),ParcelFileDescriptor.MODE_READ_ONLY)){
      data.writeFileDescriptor(library.getFileDescriptor());try(ParcelFileDescriptor dex=ParcelFileDescriptor.open(new File(directory,"worker.dex"),ParcelFileDescriptor.MODE_READ_ONLY)){data.writeFileDescriptor(dex.getFileDescriptor());worker.transact(5,data,reply,0);reply.readException();nativeValue=reply.readInt();nativeLoadError=reply.readString();}
     }
     data.recycle();reply.recycle();data=Parcel.obtain();reply=Parcel.obtain();
     try(ParcelFileDescriptor library=ParcelFileDescriptor.open(new File(directory,"libtroughworkercheck-readable.so"),ParcelFileDescriptor.MODE_READ_ONLY);ParcelFileDescriptor dex=ParcelFileDescriptor.open(new File(directory,"worker.dex"),ParcelFileDescriptor.MODE_READ_ONLY)){
      data.writeFileDescriptor(library.getFileDescriptor());data.writeFileDescriptor(dex.getFileDescriptor());worker.transact(5,data,reply,0);reply.readException();readableNativeValue=reply.readInt();readableNativeError=reply.readString();
     }
     data.recycle();reply.recycle();data=Parcel.obtain();reply=Parcel.obtain();
     try(ParcelFileDescriptor library=ParcelFileDescriptor.open(new File(directory,"libtroughworkercheck.so"),ParcelFileDescriptor.MODE_READ_ONLY);ParcelFileDescriptor dex=ParcelFileDescriptor.open(new File(directory,"worker.dex"),ParcelFileDescriptor.MODE_READ_ONLY)){
      data.writeFileDescriptor(library.getFileDescriptor());data.writeFileDescriptor(dex.getFileDescriptor());worker.transact(6,data,reply,0);reply.readException();memoryNativeValue=reply.readInt();memoryNativeError=reply.readString();
     }
     if(lease!=null){
      data.recycle();reply.recycle();data=Parcel.obtain();reply=Parcel.obtain();
      try{data.writeStrongBinder(null);data.writeInt(320);data.writeInt(180);worker.transact(4,data,reply,0);reply.readException();android.view.SurfaceControlViewHost.SurfacePackage pack=android.view.SurfaceControlViewHost.SurfacePackage.CREATOR.createFromParcel(reply);surfaceCreated=pack!=null;if(pack!=null)pack.release();}catch(Exception e){surfaceError=e.toString();}
     }
     if(engineProbe){
      if(lease==null||!surfaceCreated)throw new IOException("Engine probe needs same-app worker and remote surface");
      data.recycle();reply.recycle();data=Parcel.obtain();reply=Parcel.obtain();worker.transact(7,data,reply,0);reply.readException();
      Thread.sleep(30000);
     }
     final java.util.concurrent.CountDownLatch death=new java.util.concurrent.CountDownLatch(1);
     worker.linkToDeath(new IBinder.DeathRecipient(){public void binderDied(){deathObserved=true;death.countDown();}},0);
     data.recycle();reply.recycle();data=Parcel.obtain();reply=Parcel.obtain();
     worker.transact(2,data,reply,0);reply.readException();
     if(!death.await(3,java.util.concurrent.TimeUnit.SECONDS))error="Worker exit not observed";
    }catch(Exception e){error=e.toString();}finally{data.recycle();reply.recycle();if(engineProbe&&worker!=null){Parcel stop=Parcel.obtain(),stopped=Parcel.obtain();try{worker.transact(2,stop,stopped,0);}catch(Exception ignored){}finally{stop.recycle();stopped.recycle();}}}
    final int result=child;final String failure=error;handler.post(new Runnable(){public void run(){finish(failure,result);}});
   }},"Trough isolated host ping").start();}
   public void onServiceDisconnected(ComponentName name){/* Binder death is recorded by the worker test. */}
   public void onNullBinding(ComponentName name){finish("Null binding",-1);}
  }
  final Connection connection=new Connection();
  try{Intent intent=new Intent(lease==null?ACTION:"com.trough.hot.SLOT_WORKER");intent.setClassName(context.getPackageName(),lease==null?"com.trough.app.Components$Sandbox1":"com.trough.app.Slot$S15");connection.bound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);if(!connection.bound)connection.finish("Binding rejected",-1);else handler.postDelayed(new Runnable(){public void run(){connection.finish("Timeout",-1);}},engineProbe?45000:10000);}catch(Exception e){connection.finish(e.toString(),-1);}
 }
}
