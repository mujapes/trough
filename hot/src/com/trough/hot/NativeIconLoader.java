package com.trough.hot;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import java.io.*;
import java.util.concurrent.*;
/** Rasterize launcher icons off the UI thread; bounded memory and thumbnail disk cache. */
final class NativeIconLoader implements AutoCloseable {
 final Context context;final File directory;volatile boolean closed;final ExecutorService io=Executors.newSingleThreadExecutor();
 final android.util.LruCache<String,Bitmap> cache=new android.util.LruCache<String,Bitmap>(8*1024*1024){protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}};
 NativeIconLoader(Context c){context=c.getApplicationContext();directory=new File(c.getCacheDir(),"home-icons");}
 static final int ICON_KEY=0x7f0d74ea;
 void seed(android.view.View view){if(view instanceof ImageView){Object key=view.getTag(ICON_KEY);Drawable image=((ImageView)view).getDrawable();if(key instanceof String&&image instanceof android.graphics.drawable.BitmapDrawable){Bitmap bitmap=((android.graphics.drawable.BitmapDrawable)image).getBitmap();if(bitmap!=null&&!bitmap.isRecycled())cache.put((String)key,bitmap);}}if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)seed(group.getChildAt(i));}}
 void load(final ResolveInfo app,final ImageView target,int pixels){final int size=Math.min(128,Math.max(32,pixels));final String key=app.activityInfo.packageName+"-"+size;target.setTag(ICON_KEY,key);Bitmap ready=cache.get(key);if(ready!=null){target.setImageBitmap(ready);return;}try{io.execute(new Runnable(){public void run(){if(closed)return;android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);try{directory.mkdirs();File file=new File(directory,key+".png");Bitmap bitmap=BitmapFactory.decodeFile(file.getPath());if(bitmap==null){Drawable source=app.loadIcon(context.getPackageManager());bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);source.setBounds(0,0,size,size);source.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}trim();}if(closed)return;cache.put(key,bitmap);final Bitmap result=bitmap;target.post(new Runnable(){public void run(){if(!closed&&key.equals(target.getTag(ICON_KEY))&&target.isAttachedToWindow())target.setImageBitmap(result);}});}catch(Exception ignored){}}});}catch(RejectedExecutionException ignored){}
 }
 void trim(){File[] files=directory.listFiles();if(files==null)return;long total=0;for(File f:files)total+=f.length();if(total<=8*1024*1024)return;java.util.Arrays.sort(files,new java.util.Comparator<File>(){public int compare(File a,File b){return Long.compare(a.lastModified(),b.lastModified());}});for(File f:files){if(total<=6*1024*1024)break;long bytes=f.length();if(f.delete())total-=bytes;}}
 public void close(){closed=true;io.shutdownNow();cache.evictAll();}
}
