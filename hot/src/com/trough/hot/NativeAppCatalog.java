package com.trough.hot;
import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.os.SystemClock;
import android.widget.FrameLayout;
import java.util.*;
/** Platform-only root tag survives bundle class loaders; package broadcasts invalidate it. */
final class NativeAppCatalog {
 static final int TAG=0x7f0d74ec;
 final ArrayList<ResolveInfo> apps=new ArrayList<ResolveInfo>();
 final Map<String,String> labels=new HashMap<String,String>();
 final boolean cached;final long elapsedUs;
 @SuppressWarnings("unchecked") NativeAppCatalog(Activity activity,FrameLayout root){
  long start=System.nanoTime(),now=SystemClock.elapsedRealtime();String locale=activity.getResources().getConfiguration().getLocales().toLanguageTags();Object stored=root.getTag(TAG);boolean hit=false;
  if(stored instanceof Map){Map<?,?> record=(Map<?,?>)stored;Object time=record.get("time"),items=record.get("apps"),names=record.get("labels");
   if(time instanceof Long&&now>=(Long)time&&now-(Long)time<300000&&locale.equals(record.get("locale"))&&items instanceof List&&names instanceof Map){
    boolean valid=true;for(Object item:(List<?>)items)if(!(item instanceof ResolveInfo)){valid=false;break;}
    if(valid){apps.addAll((List<ResolveInfo>)items);labels.putAll((Map<String,String>)names);hit=true;}
   }
  }
  if(!hit){final PackageManager pm=activity.getPackageManager();List<ResolveInfo> found=pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);Set<String> seen=new HashSet<String>();
   for(ResolveInfo app:found)if(seen.add(app.activityInfo.packageName)){apps.add(app);labels.put(app.activityInfo.packageName,String.valueOf(app.loadLabel(pm)));}
   final java.text.Collator order=java.text.Collator.getInstance();Collections.sort(apps,new Comparator<ResolveInfo>(){public int compare(ResolveInfo a,ResolveInfo b){return order.compare(name(a),name(b));}});
   Map<String,Object> record=new HashMap<String,Object>();record.put("time",Long.valueOf(now));record.put("locale",locale);record.put("apps",new ArrayList<ResolveInfo>(apps));record.put("labels",new HashMap<String,String>(labels));root.setTag(TAG,record);
  }
  cached=hit;elapsedUs=(System.nanoTime()-start)/1000;
 }
 String name(ResolveInfo app){String name=labels.get(app.activityInfo.packageName);return name==null?app.activityInfo.packageName:name;}
 static BroadcastReceiver watch(Activity activity,final FrameLayout root){BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context context,Intent event){root.setTag(TAG,null);}};IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_PACKAGE_ADDED);filter.addAction(Intent.ACTION_PACKAGE_REMOVED);filter.addAction(Intent.ACTION_PACKAGE_CHANGED);filter.addDataScheme("package");activity.registerReceiver(receiver,filter);return receiver;}
}
