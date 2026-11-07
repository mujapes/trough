package com.trough.hot;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Muted native playback of the local fixture; independent of the user's media session. */
final class MediaOracle {
    interface Listener { void finished(Map<String,Object> result); }
    static void run(Context context,final Listener listener) {
        final Handler main=new Handler(Looper.getMainLooper());final MediaPlayer player=new MediaPlayer();
        final AtomicBoolean finished=new AtomicBoolean();
        final Runnable timeout=new Runnable() { public void run() { if(finished.compareAndSet(false,true)) { player.release();listener.finished(Json.map("ok",false,"error","native media preparation timed out")); } }};
        main.postDelayed(timeout,6000);
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() { public boolean onError(MediaPlayer p,int what,int extra) {
            if(finished.compareAndSet(false,true)) { main.removeCallbacks(timeout);p.release();listener.finished(Json.map("ok",false,"error","decoder error","what",what,"extra",extra)); }return true;
        }});
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { public void onPrepared(MediaPlayer p) {
            if(finished.get()) return;
            final int duration=p.getDuration();final int tracks=p.getTrackInfo().length;
            p.setVolume(0,0);p.start();
            main.postDelayed(new Runnable() { public void run() {
                if(!finished.compareAndSet(false,true)) return;
                main.removeCallbacks(timeout);
                try { int position=player.getCurrentPosition();boolean playing=player.isPlaying();
                    listener.finished(Json.map("ok",duration>1000 && tracks>0 && playing && position>0,"muted",true,"isolated",true,
                        "duration_ms",duration,"tracks",tracks,"position_ms",position,"native_playback_running",playing));
                } finally { player.release(); }
            }},200);
        }});
        final File source=new File(context.getFilesDir(),"home/.trough/media/ordinal-drift.wav");
        Thread worker=new Thread(new Runnable() { public void run() {
            try { player.setDataSource(source.getAbsolutePath());player.prepareAsync(); }
            catch(final Exception error) { main.post(new Runnable() { public void run() {
                if(finished.compareAndSet(false,true)) { main.removeCallbacks(timeout);player.release();listener.finished(Json.map("ok",false,"error",error.toString())); }
            }}); }
        }},"Trough media check");worker.start();
    }
}
