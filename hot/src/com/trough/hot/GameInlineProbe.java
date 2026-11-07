package com.trough.hot;
import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import java.io.*;
/** Inline remote surface probe and persistent imported-game panel. */
final class GameInlineProbe {
 static java.util.Map<String,Object> state(View view){Object saved=view.getRootView().getTag(0x7f0d7496);java.util.Map<String,Object> result=Json.map("phase","closed");if(saved instanceof java.util.Map)result.putAll((java.util.Map<String,Object>)saved);Object card=view.getRootView().getTag(0x7f0d7498);if(saved==null&&card instanceof View)result.put("phase","retained_uninstrumented");result.put("panel_attached",card instanceof View&&((View)card).isAttachedToWindow());return result;}

 private static View inputView(View view){if("Game touch controls".contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=inputView(group.getChildAt(i));if(found!=null)return found;}}return null;}
 private static void probeRetained(View card,File dir){final View input=inputView(card);if(input==null||!new File(dir,"input-probe-request").delete())return;input.postDelayed(new Runnable(){public void run(){if(!input.isAttachedToWindow())return;long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,1,1,0),cancel=MotionEvent.obtain(now,now+1,MotionEvent.ACTION_CANCEL,1,1,0);try{input.dispatchTouchEvent(down);input.dispatchTouchEvent(cancel);}finally{down.recycle();cancel.recycle();}}},500);}

 private static void stopWorker(final IBinder worker){new Thread(new Runnable(){public void run(){Parcel d=Parcel.obtain(),r=Parcel.obtain();try{worker.transact(2,d,r,0);r.readException();}catch(Exception ignored){}finally{d.recycle();r.recycle();}}},"Trough late game worker cleanup").start();}

 private static void watchLiveEdits(final Activity activity,final View owner){
  Object prior=owner.getTag(0x7f0d7491);if(prior instanceof FileObserver&&Integer.valueOf(5).equals(owner.getTag(0x7f0d7490)))return;if(prior instanceof FileObserver)((FileObserver)prior).stopWatching();owner.setTag(0x7f0d7490,Integer.valueOf(5));
  final File live=new File(activity.getFilesDir(),"home/.trough/games/stumble/live");live.mkdirs();final Handler main=new Handler(Looper.getMainLooper());
  FileObserver observer=new FileObserver(live.getPath(),FileObserver.CLOSE_WRITE|FileObserver.MOVED_TO){public void onEvent(int event,String name){
   if(!"roster-restore.json".equals(name)&&!"roster-result.json".equals(name)&&!"result.json".equals(name)&&!"demo-restored.json".equals(name)&&!"demo-restore-error.json".equals(name))return;
   try{File file=new File(live,name);if(file.length()>65536)return;final org.json.JSONObject row=new org.json.JSONObject(GameSlotLease.read(file));main.post(new Runnable(){public void run(){
    Object saved=owner.getTag(0x7f0d7496),view=owner.getTag(0x7f0d7498);if(!(saved instanceof java.util.Map)||!(view instanceof ViewGroup))return;
    java.util.Map state=(java.util.Map)saved;Object pid=state.get("worker_pid");if(!(pid instanceof Number)||((Number)pid).intValue()!=row.optInt("worker_pid",-1))return;
    long observed=row.optLong("observed_ms",0);Object previous=state.get("live_edit_observed_ms");if(previous instanceof Number&&observed<=((Number)previous).longValue())return;
    String result=row.optString("result",""),error=row.optString("error","");boolean restored="restored".equals(row.optString("phase"));boolean failed=!error.isEmpty()||"restore-error".equals(row.optString("phase"))||(restored&&!"Original Mario vertices restored".equals(result));
    state.put("live_edit_observed_ms",observed);state.put("live_edit_result",result.length()>512?result.substring(0,512):result);state.put("live_edit_error",error);
    ViewGroup card=(ViewGroup)view;if(card.getChildCount()==0||!(card.getChildAt(0) instanceof ViewGroup))return;ViewGroup header=(ViewGroup)card.getChildAt(0);if(header.getChildCount()==0||!(header.getChildAt(0) instanceof TextView))return;
    TextView label=(TextView)header.getChildAt(0);label.setText(failed?"Stumble Guys · live edit failed":restored?"Stumble Guys · original shape restored":result.contains("expanded12percent")?"Stumble Guys · live morph · restoring…":"Stumble Guys · live edit result");label.setContentDescription(error.isEmpty()?result:error);if(result.startsWith("Character cycle"))label.setText(result);if(result.startsWith("Whole model swapped:"))label.setText(result.substring("Whole model swapped:".length()).trim());for(int i=1;i<header.getChildCount();i++){View button=header.getChildAt(i);if(button instanceof Button&&"Morph".contentEquals(((Button)button).getText())){((Button)button).setText("Swap");button.setContentDescription("Switch complete Mario and Stumble character models live");}if(button instanceof Button&&row.has("cycling")&&!"game-close".equals(button.getTag())){((Button)button).setText(row.optBoolean("cycling")?"Pause":"Cycle");button.setContentDescription(row.optBoolean("cycling")?"Pause live character cycling":"Resume live character cycling");button.setEnabled(true);}}
   }});}catch(Exception error){android.util.Log.w("Trough","Live edit status",error);}
  }};owner.setTag(0x7f0d7491,observer);observer.startWatching();
 }

 private static void watchLaunch(final Activity activity,final ViewGroup feed){
  final View owner=feed.getRootView();final Handler main=new Handler(Looper.getMainLooper());watchLiveEdits(activity,owner);
  owner.setTag(0x7f0d7494,new Runnable(){public void run(){start(activity,feed);}});
  final File dir=new File(activity.getFilesDir(),"home/.trough/games/stumble"),launch=new File(dir,"launch");launch.mkdirs();
  Object retained=owner.getTag(0x7f0d7495);final FileObserver observer;
  if(retained instanceof FileObserver)observer=(FileObserver)retained;
  else{observer=new FileObserver(launch.getPath(),FileObserver.CLOSE_WRITE|FileObserver.MOVED_TO){public void onEvent(int event,String name){if(!"open".equals(name))return;File request=new File(launch,name);if(!request.delete())return;try{
   String candidate=GameSlotLease.read(new File(dir,"last-slot-candidate"));if(!candidate.matches("local-slot-[0-9a-f]{20}"))return;
   GameSlotLease.write(new File(dir,"inline-slot-candidate"),candidate);GameSlotLease.write(new File(dir,"inline-game-request"),"open");GameSlotLease.write(new File(dir,"inline-probe-request"),"open");main.post(new Runnable(){public void run(){Object callback=owner.getTag(0x7f0d7494);if(callback instanceof Runnable)((Runnable)callback).run();}});
  }catch(Exception error){android.util.Log.w("Trough","Open game",error);}}};owner.setTag(0x7f0d7495,observer);}
  main.postDelayed(new Runnable(){public void run(){observer.stopWatching();observer.startWatching();new Thread(new Runnable(){public void run(){if(new File(launch,"open").isFile())observer.onEvent(FileObserver.CLOSE_WRITE,"open");}},"Trough pending game launch").start();}},500);
 }
 static void start(final Activity activity,final android.view.ViewGroup feed){
  if(activity==null)return;watchLaunch(activity,feed);
  final File dir=new File(activity.getFilesDir(),"home/.trough/games/stumble");
  final View owner=feed.getRootView();Object retained=owner.getTag(0x7f0d7498);
  Object previousFailure=owner.getTag(0x7f0d7493);if(previousFailure instanceof View){View failed=(View)previousFailure;if(failed.getParent()!=feed){if(failed.getParent() instanceof ViewGroup)((ViewGroup)failed.getParent()).removeView(failed);feed.addView(failed);}}
  if(!(retained instanceof ViewGroup))new File(dir,"inline-restart-request").delete();
  if(retained instanceof ViewGroup && new File(dir,"inline-restart-request").delete()){
   ViewGroup old=(ViewGroup)retained;View close=old.findViewWithTag("game-close");
   if(close==null && old.getChildCount()>0 && old.getChildAt(0) instanceof ViewGroup){ViewGroup header=(ViewGroup)old.getChildAt(0);for(int i=0;i<header.getChildCount();i++)if(header.getChildAt(i) instanceof Button)close=header.getChildAt(i);}
   if(close!=null){close.performClick();feed.postDelayed(new Runnable(){public void run(){start(activity,feed);}},750);return;}
  }
  if(retained instanceof View){View card=(View)retained;if(card.getParent()!=feed){if(card.getParent() instanceof ViewGroup)((ViewGroup)card.getParent()).removeView(card);feed.addView(card);}probeRetained(card,dir);return;}
  File request=new File(dir,"inline-probe-request");if(!request.isFile())return;
  owner.setTag(0x7f0d7496,Json.map("phase","waiting_for_focus","gameplay_verified",false));
  if(!feed.isAttachedToWindow()||!feed.hasWindowFocus()||feed.getWidth()<2){
   if(feed.getTag(0x7f0d7497)!=null)return;
   class Deferred implements android.view.ViewTreeObserver.OnWindowFocusChangeListener,View.OnLayoutChangeListener {
    void attempt(){if(!feed.isAttachedToWindow()||!feed.hasWindowFocus()||feed.getWidth()<2)return;feed.getViewTreeObserver().removeOnWindowFocusChangeListener(this);feed.removeOnLayoutChangeListener(this);feed.setTag(0x7f0d7497,null);start(activity,feed);}
    public void onWindowFocusChanged(boolean focus){if(focus)attempt();}
    public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob){attempt();}
   }
   Deferred deferred=new Deferred();feed.setTag(0x7f0d7497,deferred);feed.getViewTreeObserver().addOnWindowFocusChangeListener(deferred);feed.addOnLayoutChangeListener(deferred);return;
  }
  if(!request.delete())return;
  if(previousFailure instanceof View){View failed=(View)previousFailure;if(failed.getParent() instanceof ViewGroup)((ViewGroup)failed.getParent()).removeView(failed);owner.setTag(0x7f0d7493,null);}
  final java.util.Map<String,Object> status=Json.map("phase","binding","control_protocol",2,"host_pid",android.os.Process.myPid(),"gameplay_verified",false);owner.setTag(0x7f0d7496,status);
  final boolean gameMode=new File(dir,"inline-game-request").delete();
  final Handler main=new Handler(Looper.getMainLooper());
  final GameSlotLease lease;try{lease=GameSlotLease.begin(activity.getFilesDir(),new File(dir,"inline-slot-candidate"));}catch(Exception e){android.util.Log.e("Trough","Inline slot lease",e);return;}
  final LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);
  final LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(12,0,4,0);card.addView(header);
  final TextView label=new TextView(activity);label.setText("Game renderer connection test · separate process");label.setTextColor(0xffbbbbbb);label.setTextSize(11);header.addView(label,new LinearLayout.LayoutParams(0,-2,1));
  final FrameLayout stage=new FrameLayout(activity);card.addView(stage,new LinearLayout.LayoutParams(-1,gameMode?Math.max(240,feed.getWidth()*9/16):120));
  final SurfaceView surface=new SurfaceView(activity);stage.addView(surface,new FrameLayout.LayoutParams(-1,-1));
  final View inputBridge=new View(activity);inputBridge.setContentDescription("Game touch controls");stage.addView(inputBridge,new FrameLayout.LayoutParams(-1,-1));feed.addView(card);if(gameMode)owner.setTag(0x7f0d7498,card);
  class Test implements ServiceConnection {
   final GameResourceSlot<IBinder> workers=new GameResourceSlot<IBinder>();
   final HandlerThread inputThread=new HandlerThread("Trough game input bridge");Handler inputHandler;FileObserver commands;
   final long createdMs=System.currentTimeMillis();volatile GameLiveRestore rosterRestore;
   boolean bound,done,attached,started;int pid=-1;volatile IBinder worker;SurfaceControlViewHost.SurfacePackage remote;
   void finish(final String error,final int pixelResult,final int left,final int right){if(done)return;done=true;if(rosterRestore!=null)rosterRestore.cancel();status.put("phase",error.equals("Closed by user")?"closed":"error");status.put("error",error);if(commands!=null)commands.stopWatching();if(inputHandler!=null)inputThread.quitSafely();if(owner.getTag(0x7f0d7498)==card)owner.setTag(0x7f0d7498,null);try{if(lease!=null)lease.restore();}catch(Exception e){android.util.Log.e("Trough","Restore inline slot",e);}
    if(remote!=null){try{remote.notifyDetachedFromWindow();remote.release();}catch(Exception ignored){}}
    final ViewGroup destination=card.getParent() instanceof ViewGroup?(ViewGroup)card.getParent():feed;
    if(card.getParent() instanceof ViewGroup)((ViewGroup)card.getParent()).removeView(card);
    if(gameMode&&!"Closed by user".equals(error)&&!"Restart requested".equals(error)){
     final LinearLayout failed=new LinearLayout(activity);failed.setGravity(Gravity.CENTER_VERTICAL);failed.setPadding(12,0,4,0);
     TextView reason=new TextView(activity);reason.setText("Game stopped · "+error);reason.setTextColor(0xffe0b26a);reason.setTextSize(12);failed.addView(reason,new LinearLayout.LayoutParams(0,-2,1));
     Button reopen=new Button(activity);reopen.setText("Reopen");reopen.setOnClickListener(new View.OnClickListener(){public void onClick(View view){new Thread(new Runnable(){public void run(){try{File launch=new File(dir,"launch");launch.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(launch,"open"))){out.write(1);}}catch(Exception problem){android.util.Log.w("Trough","Reopen game",problem);}}},"Trough reopen game").start();}});failed.addView(reopen,new LinearLayout.LayoutParams(-2,-2));owner.setTag(0x7f0d7493,failed);destination.addView(failed);
    }
    try{if(bound)activity.unbindService(this);}catch(Exception ignored){}
    final IBinder owned=workers.close();
    new Thread(new Runnable(){public void run(){
     if(owned!=null){Parcel d=Parcel.obtain(),r=Parcel.obtain();try{owned.transact(2,d,r,0);r.readException();}catch(Exception ignored){}finally{d.recycle();r.recycle();}}
     try{File tmp=new File(dir,"inline-probe-result.tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(Json.write(Json.map("slot_worker",lease!=null,"host_pid",android.os.Process.myPid(),"child_pid",pid,"attached",attached,"started",started,"surface_width",surface.getWidth(),"surface_height",surface.getHeight(),"host_token",surface.getHostToken()!=null,"pixel_copy_result",pixelResult,"left_color",left,"right_color",right,"pixels_match",left==0xff29b6f6&&right==0xffffca28,"runtime_requested",gameMode,"gameplay_verified",false,"error",error)).getBytes("UTF-8"));}tmp.renameTo(new File(dir,"inline-probe-result.json"));}catch(Exception e){android.util.Log.w("Trough","Inline probe result",e);}
    }},"Trough surface probe cleanup").start();
   }
   void create(){if(!done && attached && remote!=null){try{surface.setChildSurfacePackage(new SurfaceControlViewHost.SurfacePackage(remote));}catch(Exception error){android.util.Log.w("Trough","Reattach game surface",error);}return;}if(done||started||worker==null||surface.getHostToken()==null||surface.getWidth()<2)return;started=true;
    final IBinder token=surface.getHostToken();final int width=surface.getWidth(),height=surface.getHeight();
    new Thread(new Runnable(){public void run(){Parcel d=Parcel.obtain(),r=Parcel.obtain();try{
     d.writeStrongBinder(token);d.writeInt(width);d.writeInt(height);if(!worker.transact(4,d,r,0))throw new IOException("Surface transaction rejected");r.readException();
     final SurfaceControlViewHost.SurfacePackage pack=SurfaceControlViewHost.SurfacePackage.CREATOR.createFromParcel(r);
     main.post(new Runnable(){public void run(){if(done){pack.release();return;}try{remote=pack;surface.setChildSurfacePackage(new SurfaceControlViewHost.SurfacePackage(pack));attached=true;status.put("phase","surface_attached");surface.requestRectangleOnScreen(new Rect(0,0,width,height),true);if(gameMode)launchGame();else main.postDelayed(new Runnable(){public void run(){sample();}},1000);}catch(Exception e){finish(e.toString(),-1,0,0);}}});
    }catch(final Exception e){main.post(new Runnable(){public void run(){finish(e.toString(),-1,0,0);}});}finally{d.recycle();r.recycle();}}},"Trough remote surface create").start();
   }
   void launchGame(){if(done)return;final IBinder target=worker;label.setText("Stumble Guys · starting");new Thread(new Runnable(){public void run(){Parcel d=Parcel.obtain(),r=Parcel.obtain();try{if(!target.transact(7,d,r,0))throw new IOException("Game start rejected");r.readException();main.post(new Runnable(){public void run(){if(!done){status.put("phase","running");label.setText("Stumble Guys · in Trough");if(rosterRestore==null)rosterRestore=GameLiveRestore.start(target,pid,new File(dir,"live"),createdMs);
     if(new File(dir,"input-probe-request").delete())main.postDelayed(new Runnable(){public void run(){if(done)return;long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,1,1,0),cancel=MotionEvent.obtain(now,now+1,MotionEvent.ACTION_CANCEL,1,1,0);try{inputBridge.dispatchTouchEvent(down);inputBridge.dispatchTouchEvent(cancel);}finally{down.recycle();cancel.recycle();}}},2000);
    }}});}catch(final Exception e){main.post(new Runnable(){public void run(){finish(e.toString(),-1,0,0);}});}finally{d.recycle();r.recycle();}}},"Trough inline Unity startup").start();}
   void sample(){if(done)return;final Bitmap bitmap=Bitmap.createBitmap(16,4,Bitmap.Config.ARGB_8888);int[] xy=new int[2];surface.getLocationInWindow(xy);Rect rect=new Rect(xy[0],xy[1],xy[0]+surface.getWidth(),xy[1]+surface.getHeight());
    try{PixelCopy.request(activity.getWindow(),rect,bitmap,new PixelCopy.OnPixelCopyFinishedListener(){public void onPixelCopyFinished(int result){int left=bitmap.getPixel(3,2),right=bitmap.getPixel(12,2);bitmap.recycle();finish("",result,left,right);}},main);}catch(Exception e){bitmap.recycle();finish(e.toString(),-1,0,0);}
   }
   public void onServiceConnected(ComponentName name,final IBinder loader){try{if(lease!=null)lease.restore();}catch(Exception e){finish("Restore failed: "+e,-1,0,0);return;}if(done)return;new Thread(new Runnable(){public void run(){Parcel d=Parcel.obtain(),r=Parcel.obtain();try{
    try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(new File(dir,"worker.dex"),ParcelFileDescriptor.MODE_READ_ONLY)){d.writeFileDescriptor(fd.getFileDescriptor());d.writeString("com.trough.worker.GameWorker");loader.transact(1,d,r,0);r.readException();}
    final IBinder loaded=r.readStrongBinder();if(loaded==null)throw new IOException("Missing worker");d.recycle();r.recycle();d=Parcel.obtain();r=Parcel.obtain();loaded.transact(1,d,r,0);r.readException();final int child=r.readInt();if(child<=0||child==android.os.Process.myPid())throw new IOException("Expected separate process");
    main.post(new Runnable(){public void run(){if(!workers.install(loaded)){stopWorker(loaded);return;}worker=loaded;pid=child;status.put("worker_pid",child);create();}});
   }catch(final Exception e){main.post(new Runnable(){public void run(){finish(e.toString(),-1,0,0);}});}finally{d.recycle();r.recycle();}}},"Trough remote surface load").start();}
   public void onServiceDisconnected(ComponentName name){if(!done)finish("Worker disconnected",-1,0,0);}
   public void onNullBinding(ComponentName name){finish("Null binding",-1,0,0);}
  }
  final Test test=new Test();
  test.inputThread.start();test.inputHandler=new Handler(test.inputThread.getLooper());
  final File controlDir=new File(dir,"control");controlDir.mkdirs();
  test.commands=new FileObserver(controlDir.getPath(),FileObserver.CLOSE_WRITE|FileObserver.MOVED_TO){public void onEvent(int event,String path){if(!"game-control-request".equals(path))return;final File request=new File(controlDir,path);int value;try{java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.FileReader(request));try{value=Integer.parseInt(reader.readLine().trim());}finally{reader.close();}request.delete();}catch(Exception error){return;}final int command=value;if(command<0||command>5)return;if(command==5&&test.rosterRestore!=null)test.rosterRestore.cancel();
   if(command==3){if(!gameMode||lease==null)return;main.post(new Runnable(){public void run(){if(test.done)return;final IBinder retiring=test.worker;final java.util.concurrent.CountDownLatch departed=new java.util.concurrent.CountDownLatch(retiring==null?0:1);final IBinder.DeathRecipient death=new IBinder.DeathRecipient(){public void binderDied(){departed.countDown();}};if(retiring!=null){try{retiring.linkToDeath(death,0);}catch(android.os.RemoteException alreadyDead){departed.countDown();}}test.finish("Restart requested",-1,0,0);status.put("phase","restarting");new Thread(new Runnable(){public void run(){try{if(!departed.await(8,java.util.concurrent.TimeUnit.SECONDS)){main.post(new Runnable(){public void run(){status.put("phase","error");status.put("error","Restart timed out waiting for old game process to exit");}});return;}GameSlotLease.write(new File(dir,"inline-slot-candidate"),lease.candidate);GameSlotLease.write(new File(dir,"inline-game-request"),"restart");GameSlotLease.write(new File(dir,"inline-probe-request"),"restart");GameSlotLease.write(new File(new File(dir,"launch"),"open"),"restart");}catch(Exception error){android.util.Log.e("Trough","Restart game",error);}finally{if(retiring!=null)try{retiring.unlinkToDeath(death,0);}catch(Exception ignored){}}}},"Trough game restart").start();}});return;}

   test.inputHandler.post(new Runnable(){public void run(){IBinder target=test.worker;if(target==null)return;Parcel data=Parcel.obtain();try{data.writeInt(command);target.transact(9,data,null,IBinder.FLAG_ONEWAY);}catch(Exception error){android.util.Log.w("Trough","Game control",error);}finally{data.recycle();}}});
  }};test.commands.startWatching();
  inputBridge.setOnTouchListener(new View.OnTouchListener(){public boolean onTouch(View view,MotionEvent event){
   if(test.done || test.worker==null)return false;
   final IBinder target=test.worker;final MotionEvent copy=MotionEvent.obtain(event);
   if(!test.inputHandler.post(new Runnable(){public void run(){Parcel data=Parcel.obtain();try{copy.writeToParcel(data,0);target.transact(8,data,null,IBinder.FLAG_ONEWAY);}catch(Exception error){android.util.Log.w("Trough","Game input",error);}finally{copy.recycle();data.recycle();}}}))copy.recycle();
   return true;
  }});
  if(gameMode){final Button morph=new Button(activity);morph.setText("Swap");morph.setTextColor(0xffbbbbbb);morph.setBackgroundColor(android.graphics.Color.TRANSPARENT);morph.setTextSize(11);morph.setMinWidth(0);morph.setMinimumWidth(0);morph.setPadding(8,0,8,0);morph.setContentDescription("Switch complete Mario and Stumble character models live");morph.setOnClickListener(new View.OnClickListener(){public void onClick(View view){if(test.done||test.worker==null)return;if(test.rosterRestore!=null)test.rosterRestore.cancel();final IBinder target=test.worker;morph.setEnabled(false);main.postDelayed(new Runnable(){public void run(){if(!test.done)morph.setEnabled(true);}},1000);test.inputHandler.post(new Runnable(){public void run(){Parcel data=Parcel.obtain();try{data.writeInt(5);target.transact(9,data,null,IBinder.FLAG_ONEWAY);}catch(Exception error){android.util.Log.w("Trough","Live morph request",error);}finally{data.recycle();}}});}});header.addView(morph,new LinearLayout.LayoutParams(-2,(int)(40*activity.getResources().getDisplayMetrics().density+.5f)));Button close=new Button(activity);close.setTag("game-close");close.setText("×");close.setTextColor(0xffbbbbbb);close.setBackgroundColor(android.graphics.Color.TRANSPARENT);close.setMinWidth(0);close.setMinimumWidth(0);close.setPadding(0,0,0,0);close.setContentDescription("Close game");close.setOnClickListener(new View.OnClickListener(){public void onClick(View view){test.finish("Closed by user",-1,0,0);}});int size=(int)(40*activity.getResources().getDisplayMetrics().density+.5f);header.addView(close,new LinearLayout.LayoutParams(size,size));}surface.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder h){test.create();}public void surfaceChanged(SurfaceHolder h,int f,int w,int z){test.create();}public void surfaceDestroyed(SurfaceHolder h){}});
  try{Intent intent=new Intent(lease==null?"com.trough.hot.INLINE_PROBE":"com.trough.hot.SLOT_WORKER");intent.setClassName(activity.getPackageName(),lease==null?"com.trough.app.Components$Sandbox0":"com.trough.app.Slot$S14");test.bound=activity.bindService(intent,test,Context.BIND_AUTO_CREATE);if(!test.bound)test.finish("Binding rejected",-1,0,0);}catch(Exception e){test.finish(e.toString(),-1,0,0);}
  card.post(new Runnable(){public void run(){card.requestRectangleOnScreen(new Rect(0,0,card.getWidth(),card.getHeight()),true);test.create();}});
  main.postDelayed(new Runnable(){public void run(){if(!gameMode||!test.attached)test.finish("Timeout",-1,0,0);}},12000);
 }
}
