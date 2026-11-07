package com.trough.hot;
/** User messages must reach a running work turn; compaction cannot wait forever. */
final class WorkTurnSchedule {
    static final long COMPACTION_SILENCE_MS=120000L;
    static String messageAction(boolean busy,boolean background,boolean compacting,boolean canSteer) {
        if(!busy)return "send";
        if(compacting)return "wait";
        if(background || !canSteer)return "interrupt";
        return "steer";
    }
}
