package com.trough.hot;
import java.util.Map;
/** Remove only positively identified foreign-lane entries; unknown delivery stays guarded. */
final class InboxLaneCounts {
 static int count(Map<String,Object> snapshot,String key,String owner) {
  Object total=snapshot.get(key);int count=total instanceof Number?Math.max(0,((Number)total).intValue()):0;
  String state="queued_count".equals(key)?"queued":"uncertain_count".equals(key)?"uncertain":"held_count".equals(key)?"held":key;
  Object rows=snapshot.get("messages");
  if(!(rows instanceof Iterable))return count;
  for(Object row:(Iterable<?>)rows)if(row instanceof Map){
   Map<?,?> message=(Map<?,?>)row;Object lane=message.get("agent");
   boolean known="codex".equals(lane)||"halfsword".equals(lane)||"claude".equals(lane);
   if(known&&!lane.equals(owner)&&state.equals(message.get("state")))count=Math.max(0,count-1);
  }
  return count;
 }
}
