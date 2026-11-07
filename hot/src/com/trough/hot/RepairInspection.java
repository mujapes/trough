package com.trough.hot;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Fixed, bounded local diagnostics for the signed development channel. */
final class RepairInspection {
 private static Object get(Object o,String name)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static Map<String,Object> run(Context ctx,FrameLayout root)throws Exception {
  Map<String,Object> out=Json.map("ok",true,"codex",Main.codexState(),"width",root.getWidth(),"height",root.getHeight());
  Field cf=Main.class.getDeclaredField("chat");cf.setAccessible(true);Object chat=cf.get(null);
  if(chat!=null)try {
   Object session=get(chat,"session"),endpoint=get(session,"endpoint"),backend=get(endpoint,"backend"),stderr=get(backend,"stderr");
   synchronized(stderr){String value=stderr.toString();out.put("stderr",value.length()>4000?value.substring(value.length()-4000):value);}
  }catch(Exception e){out.put("stderr_error",e.toString());}
  File dir=new File(android.os.Environment.getExternalStoragePublicDirectory("Download"),"trough/crash3");dir.mkdirs();
  if(!root.isShown() && root.getWidth()>0 && root.getHeight()>0){int w=root.getWidth(),h=root.getHeight();root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);}
  if(root.getWidth()>0&&root.getHeight()>0){Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);try{root.draw(new Canvas(bitmap));try(FileOutputStream stream=new FileOutputStream(new File(dir,"screen.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,90,stream);}}finally{bitmap.recycle();}}
  ArrayList<Object> nodes=new ArrayList<>();walk(root,nodes,0);out.put("views",nodes);
  return out;
 }
 private static void walk(View v,List<Object> nodes,int depth){if(nodes.size()>=45||depth>5)return;nodes.add(Json.map("class",v.getClass().getSimpleName(),"x",v.getX(),"y",v.getY(),"width",v.getWidth(),"height",v.getHeight(),"visibility",v.getVisibility(),"children",v instanceof ViewGroup?((ViewGroup)v).getChildCount():0));if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)walk(((ViewGroup)v).getChildAt(i),nodes,depth+1);}
}
