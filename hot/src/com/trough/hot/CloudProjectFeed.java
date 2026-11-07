package com.trough.hot;
import java.io.File;
import java.util.*;
/** Cloud output enters only the Half Sword transcript; human input remains local. */
final class CloudProjectFeed {
 private static long last;private static boolean reading;
 static void poll(final ProjectWorker owner){
  if(reading||owner.retired||System.currentTimeMillis()-last<1500)return;last=System.currentTimeMillis();reading=true;
  owner.disk.execute(new Runnable(){public void run(){try{
   final File file=new File(owner.home,".trough/half-sword-cloud-feed.json"),seenFile=new File(owner.home,".trough/half-sword-cloud-seen.json");
   if(!file.isFile()||file.length()>65536){reading=false;return;}
   final Map<String,Object> data=Json.obj(Json.parse(new String(Payloads.readSmall(file),"UTF-8")));if(!"cloud-sol".equals(Json.str(data,"source"))){reading=false;return;}
   final LinkedHashSet<String> seen=new LinkedHashSet<String>();
   if(seenFile.isFile()&&seenFile.length()<32768){List<Object> old=Json.arr(Json.parse(new String(Payloads.readSmall(seenFile),"UTF-8")));if(old!=null)for(Object id:old)if(id instanceof String)seen.add((String)id);}
   final List<Object> entries=Json.arr(data.get("entries"));
   owner.post(new Runnable(){public void run(){try{boolean changed=false;if(entries!=null)for(Object value:entries){Map<String,Object> e=Json.obj(value);String id=Json.str(e,"id"),text=Json.str(e,"text");if(id==null||id.length()>180||!id.startsWith("cloud-sol-")||text==null||text.length()>6000||seen.contains(id))continue;seen.add(id);changed=true;owner.rows.add(Json.map("id",id,"kind","assistant","text","[Cloud Sol]\n"+text,"time_ms",System.currentTimeMillis()));}if(!changed)return;while(owner.rows.size()>12)owner.rows.remove(0);owner.saveRows();while(seen.size()>256)seen.remove(seen.iterator().next());final String encoded=Json.write(new ArrayList<String>(seen));owner.disk.execute(new Runnable(){public void run(){try{Payloads.writeSmall(seenFile,encoded);Payloads.writeSmall(new File(owner.home,".trough/half-sword-cloud-delivery.json"),Json.write(Json.map("source","cloud-sol","seen_events",seen.size(),"updated_ms",System.currentTimeMillis())));}catch(Exception e){android.util.Log.e("CloudProjectFeed","Persist",e);}}});}finally{reading=false;}}});
  }catch(Exception failure){reading=false;android.util.Log.e("CloudProjectFeed","Read",failure);}}});
 }
}
