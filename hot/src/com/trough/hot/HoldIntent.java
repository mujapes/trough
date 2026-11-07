package com.trough.hot;

/** Physical hold and intentional quick-tap latch share one pause state. */
final class HoldIntent {
    private boolean latched,down,moved,wasLatched;
    private long began;
    void begin(long now) { down=true;moved=false;wasLatched=latched;began=now; }
    void moved() { moved=true; }
    void release(long now) {
        if(!down) return;
        latched=!moved && now>=began && now-began<=250 ? !wasLatched : false;
        down=false;
    }
    void cancel() { down=false; }
    void toggle() { down=false;latched=!latched; }
    void restore(boolean value) { down=false;latched=value; }
    boolean latched() { return latched; }
    boolean paused() { return down || latched; }
}
