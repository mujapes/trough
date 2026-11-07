package com.trough.hot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Minimal native surface adapter. Policy is executed by the Dogshit interpreter. */
final class DogshitPlayer {
 static final int CHAT_INPUT=0x7f0d74b4;
 static FrameLayout chatRoot(View from){View cursor=from;while(cursor!=null){if(cursor instanceof FrameLayout&&cursor.getTag(HalfSwordView.CHAT_STATUS) instanceof View)return (FrameLayout)cursor;android.view.ViewParent next=cursor.getParent();cursor=next instanceof View?(View)next:null;}return null;}

 static void show(Activity a,File file,ShortPresentation.Listener listener) {new Session(a,file,listener,null).show();}
 static void showInline(Activity a,File file,ShortPresentation.Listener listener,android.view.ViewGroup parent) {Object old=parent.getRootView().getTag(0x7f0d747e);if(old instanceof Runnable)((Runnable)old).run();
  Session session=new Session(a,file,listener,parent);parent.getRootView().setTag(0x7f0d747e,session);session.show();}
 static boolean isPinned(android.view.ViewGroup parent){Object owner=parent.getRootView().getTag(0x7f0d747e);return owner instanceof Session&&((Session)owner).pinned&&!((Session)owner).closed;}
 static void release(android.view.ViewGroup parent){
  View root=parent.getRootView();Object owner=root.getTag(0x7f0d747e);
  try{if(owner instanceof Runnable)((Runnable)owner).run();}
  finally{if(root.getTag(0x7f0d747e)==owner){root.setTag(0x7f0d747e,null);root.setTag(0x7f0d7483,Boolean.FALSE);}}
 }
 static Map<String,Object> snapshot(android.view.ViewGroup parent){
  Object owner=parent.getRootView().getTag(0x7f0d747e);
  if(owner instanceof Callable)try{Object state=((Callable<?>)owner).call();if(state instanceof Map)return (Map<String,Object>)state;}catch(Exception ignored){}
  return null;
 }
 static void restore(Activity activity,android.view.ViewGroup parent,Map<?,?> state){
  Object name=state.get("file");if(!(name instanceof String))return;
  File directory=new File(activity.getFilesDir(),"home/.trough/media"),file=new File(directory,(String)name);
  try{if(!name.equals(file.getName())||!file.isFile()||!directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile()))return;}catch(IOException error){return;}
  Object old=parent.getRootView().getTag(0x7f0d747e);if(old instanceof Runnable)((Runnable)old).run();
  Session session=new Session(activity,file,new ShortPresentation.Listener(){public void finished(Map<String,Object> result){}},parent);
  session.resumeState=state;parent.getRootView().setTag(0x7f0d747e,session);session.show();
 }
 static int position(Map<?,?> state,String key,int duration){Object value=state.get(key);if(!(value instanceof Number))return 0;long n=((Number)value).longValue();return (int)Math.max(0,Math.min(Math.max(0,duration),n));}
 static final class SavedClips {
  static List<File> list(File directory) throws IOException {
   final File root=directory.getCanonicalFile();
   File[] entries=directory.listFiles(new FilenameFilter(){public boolean accept(File dir,String name){return name.matches("clip-[0-9]+\\.mp4");}});
   List<File> clips=new ArrayList<File>();
   if(entries==null)throw new IOException("Could not read saved clips");
   for(File entry:entries){if(!entry.isFile()||entry.length()==0||!root.equals(entry.getCanonicalFile().getParentFile()))continue;
    clips.add(entry);Collections.sort(clips,new Comparator<File>(){public int compare(File a,File b){int order=Long.compare(b.lastModified(),a.lastModified());return order!=0?order:b.getName().compareTo(a.getName());}});
    if(clips.size()>8)clips.remove(8);
   }
   return clips;
  }
 }
 static final class LoopRange {
  int start=-1,end=-1;
  void markStart(int position){start=Math.max(0,position);end=-1;}
  boolean markEnd(int position){if(start<0||position-start<500)return false;end=position;return true;}
  boolean active(){return start>=0&&end-start>=500;}
  boolean reached(int position){return active()&&position>=end;}
  void clear(){start=end=-1;}
 }
 static final class Session implements Runnable,Callable<Map<String,Object>> {
  Map<?,?> resumeState;int resumeBackingPosition;
  final Activity activity;final File file;final ShortPresentation.Listener listener;final android.view.ViewGroup parent;LinearLayout box;
  final Handler main=new Handler(Looper.getMainLooper());
  final ExecutorService worker=Executors.newSingleThreadExecutor();
  FrameLayout pinRoot;View pinHeader,pinInput;LinearLayout miniControls,fullControls,editControls;View clipControls;Button miniToggle,pinButton;boolean reparenting,audioOnly;
  final View.OnLayoutChangeListener pinLayout=new View.OnLayoutChangeListener(){public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob){layoutPinned();}};
  boolean pinned,controlPending,nextPending;long controlRead;float livePan;
  MediaEngagement engagement;
  final LoopRange loop=new LoopRange();
  String[] chapterNames=new String[0];int[] chapterStarts=new int[0];
  DanceVideo video;TextView title,status;Button toggle;SeekBar seek;AlertDialog dialog;MediaPlayer decoder,backing;boolean backingReady,backingManual;String backingName="";float backingGain=.12f;
  boolean requested=true,ready,buffering,failed,ended,closed,reported,dragging;float volume=.7f;long generation;
  Session(Activity a,File f,ShortPresentation.Listener l,android.view.ViewGroup p){activity=a;file=f;listener=l;parent=p;}
  boolean playbackPaused(){return new File(activity.getFilesDir(),"home/.trough/media/playback-paused").isFile();}
  void show(){
   engagement=new MediaEngagement(activity);
   if(resumeState!=null){requested=Boolean.TRUE.equals(resumeState.get("requested"));volume=value((Map<String,Object>)resumeState,"volume",.7f,0,1);backingGain=value((Map<String,Object>)resumeState,"backing_gain",.12f,0,.3f);backingManual=Boolean.TRUE.equals(resumeState.get("backing_manual"));loop.start=position(resumeState,"loop_start",Integer.MAX_VALUE);loop.end=position(resumeState,"loop_end",Integer.MAX_VALUE);if(!loop.active())loop.clear();resumeBackingPosition=position(resumeState,"backing_position",Integer.MAX_VALUE);}

   if(playbackPaused())requested=false;
   pinRoot=parent==null?null:chatRoot(parent);pinned=pinRoot!=null&&(new File(activity.getFilesDir(),"home/.trough/media/pinned-player").isFile()||(resumeState!=null&&Boolean.TRUE.equals(resumeState.get("pinned"))));
   if(parent!=null)parent.getRootView().setTag(0x7f0d7483,Boolean.valueOf(!pinned));
   final boolean strawberry=file.getName().startsWith("strawberry-");
   audioOnly=file.getName().endsWith(".wav")||file.getName().endsWith(".m4a")||file.getName().endsWith(".mp3")||file.getName().endsWith(".ogg")||file.getName().endsWith(".flac");
   box=new LinearLayout(activity);box.setOrientation(1);box.setPadding(0,0,0,0);box.setBackgroundColor(0x80101a20);
   title=new TextView(activity);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setText(file.getName().startsWith("phonk-godmode-mix")?"PHONK GODMODE · 20-track mix":file.getName().contains("9mm")?"Memphis Cult · 9MM"+(file.getName().contains("hour")?" · one-hour version":""):file.getName().startsWith("clip-")?"Saved cut · "+file.getName():file.getName().startsWith("frenemies-")?"Frenemies fallout · Spill Sesh · 2021 commentary":strawberry?"Strawberry Fields Forever · "+(file.getName().contains("reverse")?"backwards":"forward"):file.getName().equals("dogshit-night-current.wav")?"Night Current · Dogshit synthesis":file.getName().contains("recomposed")?"Them Changes · Trough cut":file.getName().contains("them-changes")?"Thundercat · Them Changes":"Labi Siffre · My Song");title.setTextSize(12);title.setPadding(12,8,12,8);title.setTextColor(0xffe2eee8);box.addView(title);
   video=new DanceVideo(activity);box.addView(video,new LinearLayout.LayoutParams(-1,audioOnly?1:(int)(activity.getResources().getDisplayMetrics().heightPixels*(parent==null?.43f:.24f))));
   status=new TextView(activity);status.setTextColor(0xff9ed5ca);status.setText(audioOnly?"Preparing audio…":"Preparing video…");box.addView(status);
   seek=new SeekBar(activity);seek.setMax(1000);box.addView(seek,new LinearLayout.LayoutParams(-1,26));seek.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff91e5cc));
   seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean u){}public void onStartTrackingTouch(SeekBar b){dragging=true;}public void onStopTrackingTouch(SeekBar b){dragging=false;engagement.touch();if(ready){video.seekTo((int)((long)video.getDuration()*b.getProgress()/1000));ended=false;evaluate();}}});
   LinearLayout controls=new LinearLayout(activity);fullControls=controls;controls.setOrientation(0);box.addView(controls);
   LinearLayout editing=new LinearLayout(activity);editControls=editing;box.addView(editing);
   final Button markA=new Button(activity),markB=new Button(activity),clearLoop=new Button(activity);
   markA.setText(loop.active()?"A "+clock(loop.start):"A · start");markB.setText(loop.active()?"B "+clock(loop.end):"B · end");clearLoop.setText("Clear loop");
   for(Button b:new Button[]{markA,markB,clearLoop}){b.setTextSize(11);b.setTextColor(0xffb2e3d5);b.setBackgroundColor(0x00101a20);editing.addView(b,new LinearLayout.LayoutParams(0,(int)(40*activity.getResources().getDisplayMetrics().density),1));}
   markA.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(!ready||closed)return;loop.markStart(video.getCurrentPosition());markA.setText("A "+clock(loop.start));markB.setText("B · end");}});
   markB.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(!ready||closed)return;if(loop.markEnd(video.getCurrentPosition())){markB.setText("B "+clock(loop.end));video.seekTo(loop.start);ended=false;requested=true;evaluate();}else markB.setText("Set A first · +0.5s");}});
   final Button saveLoop=new Button(activity);saveLoop.setText("Save loop");saveLoop.setTextSize(11);saveLoop.setTextColor(0xffb2e3d5);saveLoop.setBackgroundColor(0x00101a20);editing.addView(saveLoop,new LinearLayout.LayoutParams(0,(int)(40*activity.getResources().getDisplayMetrics().density),1));
   saveLoop.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(closed||!loop.active()){saveLoop.setText("Mark A + B");return;}saveLoop.setEnabled(false);saveLoop.setText("Saving…");final int start=loop.start,end=loop.end;
    new Thread(new Runnable(){public void run(){File destination=new File(file.getParentFile(),"clip-"+System.currentTimeMillis()+".mp4");File temporary=new File(destination.getPath()+".part.mp4");Process process=null;String error="";try{
     List<String> cmd=new ArrayList<String>(Arrays.asList(new File(Root.os(),"d/usr/bin/ffmpeg").getPath(),"-hide_banner","-loglevel","error","-nostdin","-y","-threads","1","-ss",String.valueOf(start/1000.0),"-i",file.getPath(),"-t",String.valueOf((end-start)/1000.0),"-vf","scale=320:-2,fps=20","-c:v","libx264","-preset","ultrafast","-crf","30","-threads","1","-c:a","aac","-b:a","64k","-movflags","+faststart",temporary.getPath()));
     ProcessBuilder pb=new ProcessBuilder(cmd);pb.environment().putAll(Root.env(activity,activity.getCacheDir()));String libs=new File(activity.getFilesDir(),"work/trough-lab/audio-recompose/lib").getPath();String old=pb.environment().get("LD_LIBRARY_PATH");pb.environment().put("LD_LIBRARY_PATH",libs+(old==null?"":":"+old));File log=new File(activity.getCacheDir(),"clip-export.log");pb.redirectErrorStream(true);pb.redirectOutput(log);process=pb.start();if(!process.waitFor(120,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Export timed out");}if(process.exitValue()!=0)throw new IOException("Export failed; see clip-export.log");if(!temporary.renameTo(destination))throw new IOException("Could not save clip");
    }catch(Exception e){error=e.getMessage();}finally{if(process!=null&&process.isAlive())process.destroyForcibly();temporary.delete();}
    final String failure=error;final File result=destination;main.post(new Runnable(){public void run(){if(closed)return;saveLoop.setEnabled(true);saveLoop.setText(failure.isEmpty()?"Saved ✓":"Retry save");status.setText(failure.isEmpty()?"Saved "+result.getName():failure);}});
   }},"Trough clip export").start();}});
   clearLoop.setOnClickListener(new View.OnClickListener(){public void onClick(View v){loop.clear();markA.setText("A · start");markB.setText("B · end");}});
   Button chat=new Button(activity);chat.setText("Chat");chat.setTextSize(11);chat.setBackgroundColor(0x00101a20);chat.setTextColor(0xffb2e3d5);controls.addView(chat);chat.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(parent==null)return;parent.getRootView().setTag(0x7f0d7483,Boolean.FALSE);if(pinned)box.setVisibility(View.GONE);android.view.View p=parent;while(p.getParent() instanceof android.view.View){p=(android.view.View)p.getParent();if(p instanceof ScrollView){((ScrollView)p).fullScroll(View.FOCUS_DOWN);break;}}}});
   toggle=new Button(activity);toggle.setText("Ⅱ");toggle.setTextColor(0xffd7eee7);toggle.setBackgroundColor(0x00101a20);controls.addView(toggle,new LinearLayout.LayoutParams(0,(int)(44*activity.getResources().getDisplayMetrics().density),1));toggle.setOnClickListener(new View.OnClickListener(){public void onClick(View v){engagement.touch();requested=!requested;if(ended&&requested){video.seekTo(0);ended=false;}evaluate();}});
   Button dance=new Button(activity);dance.setText(strawberry?(file.getName().contains("reverse")?"▶ Forward":"◀ Reverse"):"✧ scatter");dance.setTextSize(12);dance.setTextColor(0xffbda6ff);dance.setBackgroundColor(0x00101a20);controls.addView(dance,new LinearLayout.LayoutParams(0,(int)(44*activity.getResources().getDisplayMetrics().density),2));dance.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
    if(strawberry && parent!=null){File other=new File(file.getParentFile(),file.getName().contains("reverse")?"strawberry-forward.mp4":"strawberry-reverse.mp4");if(other.isFile()){close();parent.removeView(box);DogshitPlayer.showInline(activity,other,listener,parent);}return;}
    engagement.touch();if(!audioOnly)video.dance();}});
   Button mix=new Button(activity);mix.setText("Music");mix.setTextSize(11);mix.setTextColor(0xffb2e3d5);mix.setBackgroundColor(0x00101a20);controls.addView(mix);mix.setOnClickListener(new View.OnClickListener(){public void onClick(View v){backingManual=true;selectBacking(backingName.isEmpty()?"them-changes-audio.m4a":"");}});
   final HorizontalScrollView clipShelf=new HorizontalScrollView(activity);clipControls=clipShelf;clipShelf.setVisibility(View.GONE);box.addView(clipShelf);
   final LinearLayout clipItems=new LinearLayout(activity);clipShelf.addView(clipItems);
   final Button clipsButton=new Button(activity);clipsButton.setText("Clips");clipsButton.setTextSize(11);clipsButton.setTextColor(0xffb2e3d5);clipsButton.setBackgroundColor(0x00101a20);controls.addView(clipsButton);
   clipsButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
    if(closed)return;if(clipShelf.getVisibility()==View.VISIBLE){clipShelf.setVisibility(View.GONE);return;}
    clipShelf.setVisibility(View.VISIBLE);clipItems.removeAllViews();clipsButton.setEnabled(false);clipsButton.setText("Loading…");
    try{worker.execute(new Runnable(){public void run(){List<File> found=Collections.emptyList();String error="";try{found=SavedClips.list(file.getParentFile());}catch(IOException e){error="Could not read clips";}
     final List<File> saved=found;final String failure=error;main.post(new Runnable(){public void run(){if(closed)return;clipsButton.setEnabled(true);clipsButton.setText("Clips");
      if(saved.isEmpty()){TextView empty=new TextView(activity);empty.setText(failure.isEmpty()?"No saved cuts yet · mark A + B, then Save loop":failure);empty.setTextColor(0xffb2e3d5);empty.setPadding(12,8,12,8);clipItems.addView(empty);}
      for(final File clip:saved){Button play=new Button(activity);play.setText("▶ "+clip.getName());play.setTextSize(11);play.setTextColor(0xffb2e3d5);play.setBackgroundColor(0x00101a20);clipItems.addView(play);play.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(closed||!clip.isFile())return;if(parent!=null){close();showInline(activity,clip,new ShortPresentation.Listener(){public void finished(Map<String,Object> result){}},parent);}else{dialog.dismiss();DogshitPlayer.show(activity,clip,new ShortPresentation.Listener(){public void finished(Map<String,Object> result){}});}}});}
     }});
    }});}catch(RejectedExecutionException stopped){clipsButton.setEnabled(true);clipsButton.setText("Clips");}
   }});
   LinearLayout volumeRow=new LinearLayout(activity);volumeRow.setGravity(android.view.Gravity.CENTER_VERTICAL);box.addView(volumeRow,new LinearLayout.LayoutParams(-1,dp(40)));
   final TextView volumeLabel=new TextView(activity);volumeLabel.setTextColor(0xffb2e3d5);volumeLabel.setTextSize(11);volumeLabel.setText("Vol "+(int)(volume*100)+"%");volumeRow.addView(volumeLabel,new LinearLayout.LayoutParams(dp(58),-2));
   final SeekBar gain=new SeekBar(activity);gain.setMax(100);gain.setProgress((int)(volume*100));gain.setContentDescription("Music volume");volumeRow.addView(gain,new LinearLayout.LayoutParams(0,-1,1));gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){engagement.touch();}public void onProgressChanged(SeekBar b,int p,boolean u){if(u){volume=p/100f;volumeLabel.setText("Vol "+p+"%");if(decoder!=null)decoder.setVolume(volume*(livePan>0?1-livePan:1),volume*(livePan<0?1+livePan:1));}}public void onStopTrackingTouch(SeekBar b){evaluate();}});
   if(audioOnly)dance.setVisibility(View.GONE);
   video.setOnClickListener(new View.OnClickListener(){public void onClick(View v){engagement.touch();if(pinned)toggle.performClick();else if(!audioOnly)video.dance();}});
   installPinControls();
   if(parent==null){
    dialog=new AlertDialog.Builder(activity).setView(box).setPositiveButton("Back to chat",null).create();
    dialog.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){close();}});
   }else{
    box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){main.removeCallbacks(tick);main.post(tick);}public void onViewDetachedFromWindow(View v){main.removeCallbacks(tick);if(!reparenting&&(!ready||!requested))close();}});
    if(pinned){pinRoot.addView(box,new FrameLayout.LayoutParams(1,1));watchPinLayout();compact(true);layoutPinned();}
    else parent.addView(box,new android.view.ViewGroup.LayoutParams(-1,-2));
   }
   video.setOnPreparedListener(new MediaPlayer.OnPreparedListener(){public void onPrepared(MediaPlayer p){if(closed)return;final boolean resuming=resumeState!=null;decoder=p;ready=true;p.setLooping(false);if(resumeState!=null){video.seekTo(position(resumeState,"position",video.getDuration()));Object music=resumeState.get("backing");if(music instanceof String)selectBacking((String)music);if(!pinned&&Boolean.TRUE.equals(resumeState.get("hidden"))){box.setVisibility(View.GONE);parent.getRootView().setTag(0x7f0d7483,Boolean.FALSE);}resumeState=null;}evaluate();video.postDelayed(new Runnable(){public void run(){if(!resuming && !pinned && !audioOnly && !strawberry)video.dance();}},900);}});
   video.setOnCompletionListener(new MediaPlayer.OnCompletionListener(){public void onCompletion(MediaPlayer p){if(closed)return;if(loop.active()&&requested){video.seekTo(loop.start);ended=false;evaluate();return;}ended=true;requested=false;advanceFeed();if(!box.isAttachedToWindow()){close();return;}evaluate();}});
   video.setOnErrorListener(new MediaPlayer.OnErrorListener(){public boolean onError(MediaPlayer p,int what,int extra){if(closed)return true;failed=true;status.setText("Playback error "+what+" / "+extra);report(false,"decoder "+what+" / "+extra);return true;}});
   if(dialog!=null)dialog.show();video.setVideoPath(file.getAbsolutePath());loadMetadata();main.post(tick);
  }
  int dp(int n){return (int)(n*activity.getResources().getDisplayMetrics().density+.5f);}
  Button miniButton(String label,LinearLayout target){Button b=new Button(activity);b.setText(label);b.setTextSize(10);b.setTextColor(0xffd7eee7);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);b.setBackgroundColor(0x00101a20);target.addView(b,new LinearLayout.LayoutParams(0,dp(30),1));return b;}
  void installPinControls(){
   pinButton=new Button(activity);pinButton.setText("Pin");pinButton.setTextSize(11);fullControls.addView(pinButton);pinButton.setVisibility(parent==null?View.GONE:View.VISIBLE);pinButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){setPinned(true);}});
   miniControls=new LinearLayout(activity);box.addView(miniControls);miniControls.setVisibility(View.GONE);
   miniToggle=miniButton("Ⅱ",miniControls);miniToggle.setContentDescription("Pause or play pinned video");miniToggle.setOnClickListener(new View.OnClickListener(){public void onClick(View v){toggle.performClick();}});
   Button unpin=miniButton("Unpin",miniControls);unpin.setOnClickListener(new View.OnClickListener(){public void onClick(View v){setPinned(false);}});
   Button shut=miniButton("Close",miniControls);shut.setOnClickListener(new View.OnClickListener(){public void onClick(View v){close();}});
  }
  void compact(boolean small){
   fullControls.setVisibility(small?View.GONE:View.VISIBLE);editControls.setVisibility(small?View.GONE:View.VISIBLE);miniControls.setVisibility(small?View.VISIBLE:View.GONE);if(small)clipControls.setVisibility(View.GONE);
   title.setPadding(dp(4),dp(2),dp(4),dp(2));title.setTextSize(small?10:12);status.setTextSize(small?9:12);status.setSingleLine(small);seek.getLayoutParams().height=small?dp(12):26;
   video.getLayoutParams().height=audioOnly?1:small?dp(90):(int)(activity.getResources().getDisplayMetrics().heightPixels*.24f);video.requestLayout();box.requestLayout();
  }
  void watchPinLayout(){if(pinRoot==null)return;pinRoot.addOnLayoutChangeListener(pinLayout);Object h=pinRoot.getTag(HalfSwordView.CHAT_HEADER),i=pinRoot.getTag(CHAT_INPUT);pinHeader=h instanceof View?(View)h:null;pinInput=i instanceof View?(View)i:null;if(pinHeader!=null)pinHeader.addOnLayoutChangeListener(pinLayout);if(pinInput!=null)pinInput.addOnLayoutChangeListener(pinLayout);}
  void unwatchPinLayout(){if(pinRoot!=null)pinRoot.removeOnLayoutChangeListener(pinLayout);if(pinHeader!=null)pinHeader.removeOnLayoutChangeListener(pinLayout);if(pinInput!=null)pinInput.removeOnLayoutChangeListener(pinLayout);}
  void setPinned(boolean value){
   if(closed||parent==null||value==pinned)return;FrameLayout root=chatRoot(parent);if(value&&root==null)return;
   File flag=new File(activity.getFilesDir(),"home/.trough/media/pinned-player");try{if(value)flag.createNewFile();else if(flag.isFile()&&!flag.delete())throw new IOException("Pin preference removal");}catch(IOException error){status.setText("Could not save pin preference");return;}
   reparenting=true;unwatchPinLayout();if(box.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)box.getParent()).removeView(box);pinned=value;pinRoot=root;
   if(pinned){pinRoot.addView(box,new FrameLayout.LayoutParams(1,1));watchPinLayout();}else parent.addView(box,new android.view.ViewGroup.LayoutParams(-1,-2));
   compact(pinned);parent.getRootView().setTag(0x7f0d7483,Boolean.valueOf(!pinned));reparenting=false;if(pinned)layoutPinned();main.removeCallbacks(tick);main.post(tick);
  }
  void layoutPinned(){
   if(closed||!pinned||pinRoot==null||box.getParent()!=pinRoot)return;
   int[] base=new int[2],inputAt=new int[2];pinRoot.getLocationInWindow(base);int top=HalfSwordView.chatStatusBottom(pinRoot)+dp(4),bottom=pinRoot.getHeight()-dp(8);if(pinInput!=null&&pinInput.isShown()){pinInput.getLocationInWindow(inputAt);bottom=Math.min(bottom,inputAt[1]-base[1]-dp(6));}
   int available=bottom-top;boolean immersive=new File(activity.getFilesDir(),"home/.trough/keyboard-only").isFile()||new File(activity.getFilesDir(),"home/.trough/visual-only").isFile();box.setAlpha(immersive?0f:1f);box.setVisibility(available>=dp(110)?View.VISIBLE:View.INVISIBLE);int height=Math.max(1,Math.min(dp(audioOnly?116:200),available)),width=Math.max(1,Math.min(dp(260),pinRoot.getWidth()-dp(12)));
   FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)box.getLayoutParams();if(lp.width!=width||lp.height!=height||lp.topMargin!=top){lp.width=width;lp.height=height;lp.topMargin=top;lp.rightMargin=dp(6);lp.gravity=android.view.Gravity.TOP|android.view.Gravity.RIGHT;box.setLayoutParams(lp);}
   if(!audioOnly){int target=Math.max(dp(32),height-dp(70));if(video.getLayoutParams().height!=target){video.getLayoutParams().height=target;video.requestLayout();}}
  }
  Map<String,Object> paneState(){int[] at=new int[2],base=new int[2],inputAt=new int[2];box.getLocationInWindow(at);int headerBottom=-1,inputTop=-1;if(pinRoot!=null){pinRoot.getLocationInWindow(base);headerBottom=base[1]+HalfSwordView.chatStatusBottom(pinRoot);if(pinInput!=null&&pinInput.isShown()){pinInput.getLocationInWindow(inputAt);inputTop=inputAt[1];}}return Json.map("protocol",1,"time_ms",System.currentTimeMillis(),"file",file.getName(),"pinned",pinned,"video",!audioOnly,"closed",closed,"visible",!closed&&box.isShown()&&box.getWindowVisibility()==View.VISIBLE,"playing",!closed&&video.isPlaying(),"position_ms",ready?video.getCurrentPosition():0,"bounds",Arrays.asList(at[0],at[1],at[0]+box.getWidth(),at[1]+box.getHeight()),"header_bottom",headerBottom,"composer_top",inputTop,"clears_header",!pinned||at[1]>=headerBottom,"clears_composer",!pinned||inputTop<0||at[1]+box.getHeight()<=inputTop,"current_session_owner",parent!=null&&parent.getRootView().getTag(0x7f0d747e)==this);}
  void writePinClosed(){final String state=Json.write(paneState());new Thread(new Runnable(){public void run(){try{Payloads.writeSmall(new File(activity.getFilesDir(),"home/.trough/media/pinned-runtime.json"),state);}catch(Exception ignored){}}},"pinned-player-closed").start();}
  void loadMetadata(){
   try{worker.execute(new Runnable(){public void run(){try{
    String name=file.getName();int dot=name.lastIndexOf('.');File metadata=new File(file.getParentFile(),(dot>0?name.substring(0,dot):name)+".metadata.json");if(!metadata.isFile()||metadata.length()>65536)return;
    Map<String,Object> data=Json.obj(Json.parse(new String(Payloads.readSmall(metadata),"UTF-8")));Object heading=data.get("display_title");final String label=heading instanceof String&&((String)heading).length()<=160?((String)heading).trim():"";
    ArrayList<String> names=new ArrayList<String>();ArrayList<Integer> starts=new ArrayList<Integer>();Object chapters=data.get("chapters");int last=-1;
    if(chapters instanceof List&&((List<?>)chapters).size()<=256)for(Object item:(List<?>)chapters){if(!(item instanceof Map))continue;Map<?,?> row=(Map<?,?>)item;Object seconds=row.get("start_time"),text=row.get("title");if(!(seconds instanceof Number)||!(text instanceof String))continue;double time=((Number)seconds).doubleValue();String chapter=((String)text).trim();if(!Double.isFinite(time)||time<0||time>86400||chapter.isEmpty()||chapter.length()>160)continue;int start=(int)(time*1000);if(start<last)continue;last=start;names.add(chapter);starts.add(Integer.valueOf(start));}
    final String[] titles=names.toArray(new String[names.size()]);final int[] times=new int[starts.size()];for(int i=0;i<times.length;i++)times[i]=starts.get(i).intValue();
    main.post(new Runnable(){public void run(){if(closed)return;chapterNames=titles;chapterStarts=times;if(!label.isEmpty())title.setText(label);}});
   }catch(Exception ignored){}}});}catch(RejectedExecutionException stopped){}
  }
  String chapter(int position){int low=0,high=chapterStarts.length-1,best=-1;while(low<=high){int middle=(low+high)>>>1;if(chapterStarts[middle]<=position){best=middle;low=middle+1;}else high=middle-1;}return best<0?"":chapterNames[best];}
  static void disable(View view){if(view==null)return;view.setEnabled(false);if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)disable(group.getChildAt(i));}}
  public Map<String,Object> call(){if(closed||!ready||parent==null||failed)return null;return Json.map("file",file.getName(),"position",video.getCurrentPosition(),"requested",requested,"volume",volume,"loop_start",loop.start,"loop_end",loop.end,"backing",backingName,"backing_gain",backingGain,"backing_manual",backingManual,"backing_position",backingReady&&backing!=null?backing.getCurrentPosition():0,"hidden",box.getVisibility()!=View.VISIBLE,"pinned",pinned);}
  public void run(){close();}
  void close(){if(closed)return;closed=true;unwatchPinLayout();writePinClosed();if(engagement!=null)engagement.close();ready=false;requested=false;generation++;disable(box);if(status!=null)status.setText("Playback closed · use the current player");main.removeCallbacks(tick);seek.setOnSeekBarChangeListener(null);toggle.setOnClickListener(null);video.setOnPreparedListener(null);video.setOnCompletionListener(null);video.setOnErrorListener(null);video.stopPlayback();if(backing!=null){backing.release();backing=null;}backingReady=false;worker.shutdownNow();if(pinned&&box.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)box.getParent()).removeView(box);if(!reported)report(false,"Closed before playback");}
  final Runnable tick=new Runnable(){public void run(){if(closed)return;if(ready){if(playbackPaused()){if(requested||video.isPlaying()){requested=false;++generation;if(video.isPlaying())video.pause();toggle.setText("▶");if(miniToggle!=null)miniToggle.setText("▶");}if(backingReady&&backing!=null&&backing.isPlaying())backing.pause();}readLiveControls();if(backingReady&&backing!=null){boolean play=video.isPlaying();if(play&&!backing.isPlaying())backing.start();else if(!play&&backing.isPlaying())backing.pause();}int pos=video.getCurrentPosition(),duration=video.getDuration();String track=chapter(pos);if(!dragging&&requested&&loop.reached(pos)){video.seekTo(loop.start);pos=loop.start;}if(!dragging&&duration>0)seek.setProgress((int)(1000L*pos/duration));status.setText((video.isPlaying()?"Playing · ":ended?"Finished · ":"Paused · ")+clock(pos)+" / "+clock(duration)+(backingReady?" · music mix":"")+(loop.active()?" · Loop "+clock(loop.start)+"–"+clock(loop.end):"")+(track.isEmpty()?"":" · "+track));if(!reported&&video.isPlaying()&&pos>200)report(true,"");}main.postDelayed(this,400);}};
  void advanceFeed(){if(playbackPaused()||parent==null||closed||nextPending)return;nextPending=true;
   try{worker.execute(new Runnable(){public void run(){File chosen=null;try{File list=new File(file.getParentFile(),"feed-playlist.txt");if(list.isFile()&&list.length()<8192){String[] names=new String(Payloads.readSmall(list),"UTF-8").split("\\r?\\n");for(int i=0;i+1<names.length;i++)if(names[i].trim().equals(file.getName())){String name=names[i+1].trim();File next=new File(file.getParentFile(),name);if(name.equals(next.getName())&&!name.equals(file.getName())&&next.isFile())chosen=next;break;}}}catch(Exception ignored){}
    final File next=chosen;main.post(new Runnable(){public void run(){nextPending=false;if(playbackPaused()||closed||!ended||next==null)return;close();showInline(activity,next,new ShortPresentation.Listener(){public void finished(Map<String,Object> result){}},parent);}});
   }});}catch(RejectedExecutionException stopped){nextPending=false;}
  }
  void readLiveControls(){long now=android.os.SystemClock.uptimeMillis();if(controlPending||now-controlRead<1000||closed)return;controlRead=now;controlPending=true;final Map<String,Object> interaction=engagement.snapshot(box,video.isPlaying());interaction.put("pinned",pinned);interaction.put("video",!audioOnly);interaction.put("pane",paneState());interaction.put("file",file.getName());interaction.put("position_ms",video.getCurrentPosition());interaction.put("duration_ms",video.getDuration());interaction.put("chapter",chapter(video.getCurrentPosition()));
   try{worker.execute(new Runnable(){public void run(){Map<String,Object> values=null;try{Payloads.writeSmall(new File(activity.getFilesDir(),"home/.trough/media/pinned-runtime.json"),Json.write(interaction.get("pane")));}catch(Exception ignored){}try{File state=new File(activity.getFilesDir(),"home/.trough/media/engagement.json"),temp=new File(state.getPath()+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(Json.write(interaction).getBytes("UTF-8"));}if(!temp.renameTo(state))temp.delete();}catch(Exception ignored){}try{File f=new File(activity.getFilesDir(),"home/.trough/media/live-controls.json");if(f.isFile()&&f.length()<4096)values=Json.obj(Json.parse(new String(Payloads.readSmall(f),"UTF-8")));}catch(Exception ignored){}
    final Map<String,Object> controls=values;main.post(new Runnable(){public void run(){controlPending=false;if(closed||!ready||controls==null)return;
     if(!backingManual&&controls.get("backing") instanceof String)selectBacking((String)controls.get("backing"));backingGain=value(controls,"backing_gain",backingGain,0,.3f);if(backingReady&&backing!=null)backing.setVolume(backingGain,backingGain);
     float zoom=value(controls,"zoom",1,1,1.3f),rotation=value(controls,"rotation",0,-5,5);livePan=value(controls,"pan",0,-.7f,.7f);
     video.texture.animate().scaleX(zoom).scaleY(zoom).rotation(rotation).setDuration(800).start();
     if(decoder!=null)decoder.setVolume(volume*(livePan>0?1-livePan:1),volume*(livePan<0?1+livePan:1));
    }});
   }});}catch(RejectedExecutionException stopped){controlPending=false;}
  }
  void selectBacking(String name){if(name.equals(backingName))return;File track=new File(file.getParentFile(),name);if(!name.isEmpty()&&(!name.equals(track.getName())||!(name.endsWith(".m4a")||name.endsWith(".wav"))))return;
   backingName=name;backingReady=false;if(backing!=null){backing.release();backing=null;}if(name.isEmpty())return;
   try{final MediaPlayer music=new MediaPlayer();backing=music;music.setDataSource(track.getPath());music.setLooping(true);music.setVolume(backingGain,backingGain);music.setOnPreparedListener(new MediaPlayer.OnPreparedListener(){public void onPrepared(MediaPlayer p){if(closed||backing!=p)return;backingReady=true;p.setVolume(backingGain,backingGain);if(resumeBackingPosition>0){p.seekTo(Math.min(resumeBackingPosition,Math.max(0,p.getDuration())));resumeBackingPosition=0;}if(video.isPlaying()&&!playbackPaused())p.start();}});music.setOnErrorListener(new MediaPlayer.OnErrorListener(){public boolean onError(MediaPlayer p,int what,int extra){if(backing==p){backingReady=false;p.release();backing=null;}return true;}});music.prepareAsync();}catch(Exception error){if(backing!=null){backing.release();backing=null;}backingReady=false;}
  }
  static float value(Map<String,Object> m,String key,float fallback,float min,float max){Object o=m.get(key);float v=o instanceof Number?((Number)o).floatValue():fallback;return Float.isNaN(v)||Float.isInfinite(v)?fallback:Math.max(min,Math.min(max,v));}
  static String clock(int ms){int s=Math.max(0,ms)/1000;return s/60+":"+(s%60<10?"0":"")+s%60;}
  void report(boolean ok,String error){reported=true;Map<String,Object> r=Json.map("ok",ok,"visible",parent!=null?box.isShown():dialog!=null&&dialog.isShowing(),"inline",parent!=null,"pinned",pinned,"playing",ok&&video.isPlaying(),"position_ms",ready?video.getCurrentPosition():0,"duration_ms",ready?video.getDuration():0,"file",file.getName(),"backing",backingName,"backing_playing",backingReady&&backing!=null&&backing.isPlaying(),"policy","Dogshit player.ds","decoder","Android native media","error",error);listener.finished(r);}
  void evaluate(){
   if(closed || worker.isShutdown())return;
   if(playbackPaused())requested=false;
   final long version=++generation;final boolean want=requested,isReady=ready,isEnded=ended,isFailed=failed,isBuffering=buffering;final float gain=volume;
   toggle.setText(want?"Ⅱ":"▶");if(miniToggle!=null)miniToggle.setText(want?"Ⅱ":"▶");toggle.setContentDescription(want?"Pause":"Play");
   try { worker.execute(new Runnable(){public void run(){
    try{
     File root=new File(activity.getFilesDir(),"work/trough-lab");
     List<String> command=new ArrayList<String>();command.add(new File(root,"dogshit-core/out/dogshit").getPath());command.add(new File(root,"dogshit-player/player.ds").getPath());
     String[] names={"requested","ready","buffering","failed","ended","volume"};String[] values={String.valueOf(want),String.valueOf(isReady),String.valueOf(isBuffering),String.valueOf(isFailed),String.valueOf(isEnded),String.valueOf(gain)};
     for(int i=0;i<names.length;i++){command.add("--set");command.add(names[i]);command.add(values[i]);}
     ProcessBuilder pb=new ProcessBuilder(command).redirectErrorStream(true);pb.environment().putAll(Root.env(activity,activity.getCacheDir()));
     Process process=pb.start();boolean finished=false;
     try{finished=process.waitFor(5,TimeUnit.SECONDS);if(!finished)throw new IOException("Dogshit timed out");if(process.exitValue()!=0)throw new IOException("Dogshit exited "+process.exitValue());
      BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),"UTF-8"));boolean play=false;double level=0;String line;boolean foundPlay=false,foundGain=false;
      while((line=reader.readLine())!=null){if(line.startsWith("play:bool=")){play=line.endsWith("=true");foundPlay=true;}if(line.startsWith("gain:unit=")){level=Double.parseDouble(line.substring(10));foundGain=true;}}
      reader.close();if(!foundPlay||!foundGain||!Double.isFinite(level)||level<0||level>1)throw new IOException("Invalid Dogshit outputs");
      final boolean start=play;final float outputGain=(float)level;
      main.post(new Runnable(){public void run(){if(closed||generation!=version)return;if(decoder!=null)decoder.setVolume(outputGain,outputGain);if(start&&!playbackPaused())video.start();else if(video.isPlaying())video.pause();}});
     }finally{if(!finished)process.destroyForcibly();}
    }catch(final Exception error){main.post(new Runnable(){public void run(){if(closed||generation!=version)return;failed=true;status.setText("Policy error: "+error.getMessage());report(false,error.toString());}});}
   }}); } catch(RejectedExecutionException stopped) { if(!closed) { failed=true;status.setText("Player stopped"); } }
  }
 }
}
