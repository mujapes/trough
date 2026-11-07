package com.trough.hot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Root-owned native media session. Audio and its position survive chat code replacement. */
final class MediaPanel {
    private static final int TAG=0x7f0d7474,PICK=0x7472;
    private static InvocationHandler owner(Context context,View root) {
        Object saved=root.getTag(TAG);
        if(saved instanceof InvocationHandler) return (InvocationHandler)saved;
        InvocationHandler created=new Session(context.getApplicationContext());root.setTag(TAG,created);return created;
    }
    static void openMusic(Activity activity,View root,String source,String title){call(owner(activity,root),"openMusic",source,title);}
    static void controlMusic(Activity activity,View root,String operation,int value){call(owner(activity,root),"musicControl",operation,Integer.valueOf(value));}
    static void show(Activity activity,View root) { if(activity!=null) call(owner(activity,root),"show",activity); }
    static void attach(Activity activity,View root) {
        Object saved=root.getTag(TAG);if(saved instanceof InvocationHandler) call((InvocationHandler)saved,"attach",activity);
    }
    static boolean result(Object host,String event,Object[] args,View root) {
        if(!"result".equals(event) || !(host instanceof Activity) || args==null || args.length<3
            || !(args[0] instanceof Number) || ((Number)args[0]).intValue()!=PICK) return false;
        if(args[1] instanceof Number && ((Number)args[1]).intValue()==Activity.RESULT_OK && args[2] instanceof Intent)
            call(owner((Activity)host,root),"picked",(Activity)host,args[2]);
        return true;
    }
    @SuppressWarnings("unchecked") static Map<String,Object> state(View root) {
        Object saved=root.getTag(TAG);Object result=saved instanceof InvocationHandler?call((InvocationHandler)saved,"state"):null;
        return result instanceof Map?(Map<String,Object>)result:Json.map("playing",false,"ready",false);
    }
    private static Object call(InvocationHandler bridge,Object... args) {
        try { return bridge.invoke(null,null,args); } catch(Throwable ignored) { return null; }
    }
    private static final class Session implements InvocationHandler,AudioManager.OnAudioFocusChangeListener {
        final Context context;final Handler main=new Handler(Looper.getMainLooper());
        final ExecutorService io=Executors.newSingleThreadExecutor();final AudioManager audio;
        volatile MediaPlayer player;Activity activity;AlertDialog dialog;
        TextView titleView,timeView;Button play;SeekBar seek;SurfaceView surface;
        String uri="",title="Choose audio or video",error="";boolean ready,playing,video,seeking;int duration;
        final Runnable tick=new Runnable() { public void run() { paint();if(dialog!=null) main.postDelayed(this,500); }};
        Session(Context context) { this.context=context;audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE); }
        public Object invoke(Object proxy,Method method,Object[] args) {
            String op=(String)args[0];
            if("openMusic".equals(op))open(Uri.parse((String)args[1]),(String)args[2]);
            else if("musicControl".equals(op)){String action=(String)args[1];int value=((Number)args[2]).intValue();if("toggle".equals(action)){if(playing)pause();else start();}else if("pause".equals(action))pause();else if("seek".equals(action)&&ready&&player!=null)player.seekTo(Math.max(0,Math.min(duration,value)));}
            else if("show".equals(op)) show((Activity)args[1]);
            else if("attach".equals(op)) { activity=(Activity)args[1];if(activity==null && dialog!=null) dialog.dismiss(); }
            else if("picked".equals(op)) {
                activity=(Activity)args[1];Intent result=(Intent)args[2];Uri chosen=result.getData();
                if(chosen!=null) {
                    try { context.getContentResolver().takePersistableUriPermission(chosen,result.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch(SecurityException ignored) {}
                    show(activity);open(chosen,chosen.getLastPathSegment());
                }
            } else if("state".equals(op)) return Json.map("ready",ready,"playing",playing,"video",video,"title",title,
                "position_ms",position(),"duration_ms",duration,"source",uri,"error",error,"session_identity",System.identityHashCode(this));
            return null;
        }
        private void show(final Activity window) {
            activity=window;if(dialog!=null) { dialog.show();return; }
            LinearLayout box=new LinearLayout(window);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(12),dp(18),dp(12));box.setBackgroundColor(0xff101d18);
            titleView=new TextView(window);titleView.setTextColor(0xffe8efe6);titleView.setTextSize(17);box.addView(titleView);
            surface=new SurfaceView(window);surface.setVisibility(video?View.VISIBLE:View.GONE);box.addView(surface,new LinearLayout.LayoutParams(-1,dp(210)));
            surface.getHolder().addCallback(new SurfaceHolder.Callback() {
                public void surfaceCreated(SurfaceHolder holder) { if(player!=null) try { player.setDisplay(holder); } catch(IllegalStateException ignored) {} }
                public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) {}
                public void surfaceDestroyed(SurfaceHolder holder) { if(player!=null) try { player.setDisplay(null);if(video) pause(); } catch(IllegalStateException ignored) {} }
            });
            timeView=new TextView(window);timeView.setTextColor(0xff9fb8aa);box.addView(timeView);
            seek=new SeekBar(window);seek.setMax(1000);box.addView(seek);
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar,int value,boolean user) {}
                public void onStartTrackingTouch(SeekBar bar) { seeking=true; }
                public void onStopTrackingTouch(SeekBar bar) { seeking=false;if(ready && player!=null) try { player.seekTo((int)((long)duration*bar.getProgress()/1000)); } catch(IllegalStateException ignored) {} }
            });
            play=button(window,"Play / pause",box,new Runnable() { public void run() { if(playing) pause();else start();paint(); }});
            button(window,"Choose local audio or video",box,new Runnable() { public void run() {
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/*","video/*"}).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try { window.startActivityForResult(pick,PICK); } catch(RuntimeException failure) { error=failure.getMessage();paint(); }
            }});
            button(window,"Play Ordinal Drift",box,new Runnable() { public void run() {
                File sample=new File(context.getFilesDir(),"home/.trough/media/ordinal-drift.wav");
                if(sample.isFile()) open(Uri.fromFile(sample),"Ordinal Drift · local generative sketch");else { error="Local sketch is unavailable";paint(); }
            }});
            button(window,"Play gematria short",box,new Runnable() { public void run() {
                File sample=new File(context.getFilesDir(),"home/.trough/media/gematria-57.mp4");
                if(sample.isFile()) open(Uri.fromFile(sample),"57 · same total, different words");else { error="The first short is still being made";paint(); }
            }});
            LinearLayout feedback=new LinearLayout(window);box.addView(feedback);
            button(window,"More like this",feedback,new Runnable() { public void run() { feedback(1); }});
            button(window,"Less like this",feedback,new Runnable() { public void run() { feedback(-1); }});
            ScrollView scroll=new ScrollView(window);scroll.addView(box);
            final AlertDialog shown=new AlertDialog.Builder(window).setTitle("Trough · media").setView(scroll).setPositiveButton("Back to chat",null).create();
            dialog=shown;shown.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { public void onDismiss(android.content.DialogInterface ignored) {
                main.removeCallbacks(tick);if(video) pause();dialog=null;surface=null;titleView=null;timeView=null;play=null;seek=null;
            }});shown.show();main.post(tick);
        }
        private Button button(Context c,String label,LinearLayout box,final Runnable action) {
            Button b=new Button(c);b.setText(label);b.setAllCaps(false);b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { action.run(); }});
            box.addView(b);return b;
        }
        private void open(final Uri source,String label) {
            String scheme=source.getScheme();if(!"content".equals(scheme) && !"file".equals(scheme)) { error="Choose a local audio or video document";paint();return; }
            final MediaPlayer previous=player;player=null;ready=false;playing=false;duration=0;error="";uri=source.toString();title=label==null?"Local media":label;
            if(previous!=null) io.execute(new Runnable() { public void run() { previous.release(); }});
            final MediaPlayer next=new MediaPlayer();player=next;next.setAudioStreamType(AudioManager.STREAM_MUSIC);
            next.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { public void onPrepared(MediaPlayer p) {
                if(p!=player) return;ready=true;duration=p.getDuration();video=p.getVideoWidth()>0;
                if(surface!=null) { surface.setVisibility(video?View.VISIBLE:View.GONE);if(surface.getHolder().getSurface().isValid()) p.setDisplay(surface.getHolder()); }
                start();paint();
            }});
            next.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { public void onCompletion(MediaPlayer p) { if(p==player) { playing=false;audio.abandonAudioFocus(Session.this);paint(); } }});
            next.setOnErrorListener(new MediaPlayer.OnErrorListener() { public boolean onError(MediaPlayer p,int what,int extra) {
                if(p==player) { error="This file could not be played ("+what+", "+extra+")";ready=false;playing=false;paint(); }return true;
            }});
            io.execute(new Runnable() { public void run() {
                try {
                    if(next!=player) return;
                    String display=null;
                    if("content".equals(source.getScheme())) try(android.database.Cursor cursor=context.getContentResolver().query(source,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)) {
                        if(cursor!=null && cursor.moveToFirst()) display=cursor.getString(0);
                    }
                    final String name=display;if(name!=null) main.post(new Runnable() { public void run() { if(next==player) { title=name;paint(); } }});
                    next.setDataSource(context,source);next.prepareAsync();
                } catch(final Exception failure) { main.post(new Runnable() { public void run() { if(next==player) { error="Could not open this file: "+failure.getMessage();ready=false;paint(); } }}); }
            }});paint();
        }
        private int position() { try { return ready && player!=null?player.getCurrentPosition():0; } catch(IllegalStateException ignored) { return 0; } }
        private void start() {
            if(!ready || player==null) return;
            if(audio.requestAudioFocus(this,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { error="Another app currently owns audio";return; }
            try { player.start();playing=true;error=""; } catch(IllegalStateException failure) { error=failure.getMessage(); }
        }
        private void pause() { try { if(ready && player!=null) player.pause(); } catch(IllegalStateException ignored) {} playing=false;audio.abandonAudioFocus(this); }
        public void onAudioFocusChange(int change) {
            if(change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
            else if(player!=null && ready) try { float level=change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK?.2f:1f;player.setVolume(level,level); } catch(IllegalStateException ignored) {}
            paint();
        }
        private void feedback(final int preference) {
            if(uri.isEmpty() || !ready) { error="Play something before saving a preference";paint();return; }
            final Map<String,Object> event=Json.map("at_ms",System.currentTimeMillis(),"source",uri,"title",title,"position_ms",position(),"duration_ms",duration,"preference",preference);
            io.execute(new Runnable() { public void run() {
                try { File file=new File(context.getFilesDir(),"home/.trough/media/feedback.jsonl");file.getParentFile().mkdirs();
                    try(java.io.FileOutputStream output=new java.io.FileOutputStream(file,true)) { output.write((Json.write(event)+"\n").getBytes("UTF-8"));output.getFD().sync(); }
                    main.post(new Runnable() { public void run() { if(timeView!=null) timeView.setText("Preference saved"); }});
                } catch(final Exception failure) { main.post(new Runnable() { public void run() { error="Could not save preference: "+failure.getMessage();paint(); }}); }
            }});
        }
        private void paint() {
            if(titleView!=null) titleView.setText(title+(error.isEmpty()?"":"\n"+error));
            if(timeView!=null) timeView.setText((ready?clock(position())+" / "+clock(duration):uri.isEmpty()?"Audio keeps playing while you chat":"Opening media…"));
            if(play!=null) { play.setText(playing?"Pause":"Play");play.setEnabled(ready); }
            if(seek!=null && !seeking) seek.setProgress(duration>0?(int)((long)position()*1000/duration):0);
        }
        private int dp(int n) { return Math.round(n*context.getResources().getDisplayMetrics().density); }
        private String clock(int ms) { int s=Math.max(0,ms)/1000;return (s/60)+":"+(s%60<10?"0":"")+(s%60); }
    }
}
