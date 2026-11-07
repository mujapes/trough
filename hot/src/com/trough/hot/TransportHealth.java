package com.trough.hot;
import java.util.*;

/** Work progress uses a monotonic clock. Account housekeeping cannot keep a stalled turn alive. */
final class TransportHealth {
    interface Clock { long millis(); }
    static final long STALL_MS=180000, NETWORK_RECOVERY_MS=30000;
    private final Clock clock;
    private long progressAt;
    private boolean active, blockedFailure;
    private long networkFailureAt=-1;
    private final Set<String> tools=new HashSet<String>(),compactions=new HashSet<String>();
    TransportHealth(){this(new Clock(){public long millis(){return System.nanoTime()/1000000L;}});}
    TransportHealth(Clock clock){this.clock=clock;progressAt=clock.millis();}
    synchronized void started(){active=true;tools.clear();compactions.clear();progressAt=clock.millis();networkFailureAt=-1;blockedFailure=false;}
    static boolean meaningfulProgress(String method,Map<String,Object> parameters) {
        if(method==null || !method.startsWith("item/"))return false;
        String delta=Json.str(parameters,"delta");
        if(delta!=null)return !delta.trim().isEmpty() && !ConnectionRecovery.retryNotice(delta);
        Map<String,Object> item=Json.obj(parameters==null?null:parameters.get("item"));
        if(item==null)return false;
        String type=Json.str(item,"type"),text=Json.str(item,"text");
        if("agentMessage".equals(type))return text!=null && !text.trim().isEmpty() && !ConnectionRecovery.retryNotice(text);
        return type!=null;
    }
    synchronized void event(String method,Map<String,Object> parameters){
        if(method==null)return;
        if("error".equals(method) && active) {
            String detail=ConnectionRecovery.errorText(Json.obj(parameters==null?null:parameters.get("error")));
            if(ConnectionRecovery.requiresSignIn(detail) || ConnectionRecovery.rateLimited(detail)) {
                networkFailureAt=-1;blockedFailure=true;
            } else if(ConnectionRecovery.transientFailure(detail) && networkFailureAt<0)networkFailureAt=clock.millis();
        }
        if(meaningfulProgress(method,parameters)){networkFailureAt=-1;blockedFailure=false;}
        if(method.startsWith("item/")||"turn/started".equals(method)||"turn/completed".equals(method))progressAt=clock.millis();
        if("turn/started".equals(method)){active=true;networkFailureAt=-1;blockedFailure=false;}
        if("turn/completed".equals(method)){active=false;tools.clear();compactions.clear();networkFailureAt=-1;blockedFailure=false;}
        if("item/started".equals(method)||"item/completed".equals(method)) {
            Map<String,Object> item=Json.obj(parameters==null?null:parameters.get("item"));
            String id=Json.str(item,"id"),type=Json.str(item,"type");
            if(id==null)return;
            if("item/completed".equals(method)){tools.remove(id);compactions.remove(id);}
            else if("contextCompaction".equals(type))compactions.add(id);
            else if("commandExecution".equals(type)||"mcpToolCall".equals(type)||"webSearch".equals(type)||"fileChange".equals(type))tools.add(id);
        }
    }
    synchronized Map<String,Object> snapshot(){
        long age=Math.max(0,clock.millis()-progressAt);
        long deadline=compactions.isEmpty()?STALL_MS:WorkTurnSchedule.COMPACTION_SILENCE_MS;
        long networkAge=networkFailureAt<0?0:Math.max(0,clock.millis()-networkFailureAt);
        boolean networkDue=active && !blockedFailure && networkFailureAt>=0 && networkAge>=NETWORK_RECOVERY_MS;
        return Json.map("progress_age_ms",age,"active_tools",tools.size(),"active_compactions",compactions.size(),"stall_after_ms",deadline,
            "network_failure_age_ms",networkAge,"network_recovery_due",networkDue,"blocked_by_auth_or_quota",blockedFailure,
            "active_turn",active,"stalled",active&&!blockedFailure&&((tools.isEmpty()&&compactions.isEmpty()&&age>=deadline)||networkDue));
    }
}
