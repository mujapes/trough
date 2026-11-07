package com.trough.hot;
import android.content.Context;
import android.graphics.*;
import android.media.MediaPlayer;
import android.view.*;
import android.widget.FrameLayout;
/** One native decoder, with a bounded twelve-strip visual performance. */
final class DanceVideo extends FrameLayout implements TextureView.SurfaceTextureListener {
 final TextureView texture;final Shards shards;MediaPlayer player;Surface surface;String path;
 MediaPlayer.OnPreparedListener prepared;MediaPlayer.OnCompletionListener completed;MediaPlayer.OnErrorListener error;
 boolean ready,closed;long until,lastSnapshot;Bitmap snapshot;
 DanceVideo(Context c){super(c);texture=new TextureView(c);texture.setSurfaceTextureListener(this);addView(texture,new LayoutParams(-1,-1));shards=new Shards(c);addView(shards,new LayoutParams(-1,-1));setClipChildren(true);}
 void setOnPreparedListener(MediaPlayer.OnPreparedListener p){prepared=p;}
 void setOnCompletionListener(MediaPlayer.OnCompletionListener p){completed=p;}
 void setOnErrorListener(MediaPlayer.OnErrorListener p){error=p;}
 void setVideoPath(String p){path=p;open();}
 void open(){if(path==null||surface==null||player!=null||closed)return;try{player=new MediaPlayer();player.setSurface(surface);player.setDataSource(path);player.setOnPreparedListener(new MediaPlayer.OnPreparedListener(){public void onPrepared(MediaPlayer p){ready=true;if(prepared!=null)prepared.onPrepared(p);}});player.setOnCompletionListener(completed);player.setOnErrorListener(error);player.prepareAsync();}catch(Exception e){if(error!=null)error.onError(player,-1,0);}}
 public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){surface=new Surface(t);if(player!=null)player.setSurface(surface);else open();}
 public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){}
 public boolean onSurfaceTextureDestroyed(SurfaceTexture t){removeCallbacks(frame);if(player!=null)player.setSurface(null);if(surface!=null){surface.release();surface=null;}return true;}
 public void onSurfaceTextureUpdated(SurfaceTexture t){}
 void start(){if(ready)player.start();}
 void pause(){if(ready)player.pause();}
 boolean isPlaying(){return ready&&player!=null&&player.isPlaying();}
 int getDuration(){return ready?player.getDuration():0;}
 int getCurrentPosition(){return ready?player.getCurrentPosition():0;}
 void seekTo(int p){if(ready)player.seekTo(p);}
 void stopPlayback(){if(closed)return;closed=true;removeCallbacks(frame);if(player!=null){player.release();player=null;}ready=false;if(surface!=null){surface.release();surface=null;}if(snapshot!=null){snapshot.recycle();snapshot=null;}}
 void dance(){if(closed)return;until=android.os.SystemClock.uptimeMillis()+9000;removeCallbacks(frame);post(frame);}
 final Runnable frame=new Runnable(){public void run(){if(closed)return;long now=android.os.SystemClock.uptimeMillis();if(now>=until){texture.setAlpha(1);shards.setVisibility(GONE);return;}if(texture.isAvailable()&&now-lastSnapshot>=120){if(snapshot==null)snapshot=Bitmap.createBitmap(480,270,Bitmap.Config.ARGB_8888);texture.getBitmap(snapshot);lastSnapshot=now;}texture.setAlpha(.03f);shards.setVisibility(VISIBLE);shards.invalidate();postDelayed(this,33);}};
 final class Shards extends View {
  final Paint paint=new Paint(3);final Rect src=new Rect();final RectF dest=new RectF();
  Shards(Context c){super(c);setVisibility(GONE);}
  protected void onDraw(Canvas canvas){if(snapshot==null)return;float t=(android.os.SystemClock.uptimeMillis()%100000)/1000f;float fade=Math.min(1,Math.max(0,(until-android.os.SystemClock.uptimeMillis())/1200f));float w=getWidth(),h=getHeight();canvas.drawColor(0xff071116);
   for(int i=0;i<12;i++){float pulse=(float)Math.sin(t*3.6+i*.72)*fade;float dx=pulse*w*.065f;float dy=(float)Math.cos(t*2.3+i)*h*.018f*fade;int y=i*270/12,next=(i+1)*270/12;src.set(0,y,480,next);dest.set(dx,i*h/12+dy,w+dx,(i+1)*h/12+dy);paint.setAlpha(255);canvas.drawBitmap(snapshot,src,dest,paint);paint.setColor(i%2==0?0xff8cf6d4:0xffb9a0ff);paint.setAlpha((int)(100*fade));canvas.drawRect(dest.left,dest.bottom-1,dest.right,dest.bottom+1,paint);}
  }
 }
}
