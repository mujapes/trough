package com.trough.hot;

/** Single producer/consumer bounded ring. Never blocks the rendering or metrics callback. */
final class FrameRing {
    static final int CAPACITY=4096, MASK=CAPACITY-1;
    private final long[] stamps=new long[CAPACITY];
    private final int[] durations=new int[CAPACITY], lost=new int[CAPACITY];
    private final java.util.concurrent.atomic.AtomicLongArray versions=new java.util.concurrent.atomic.AtomicLongArray(CAPACITY);
    private volatile long published;
    private long consumed, overwritten;
    void offer(long stamp,int durationUs,int lostCallbacks){
        long seq=published;int slot=(int)seq&MASK;
        versions.set(slot,-seq-1);stamps[slot]=stamp;durations[slot]=durationUs;lost[slot]=lostCallbacks;
        versions.set(slot,seq+1);published=seq+1;
    }
    int drain(long[] times,int[] costs,int[] misses){
        long end=published;
        if(end-consumed>CAPACITY){long skip=end-consumed-CAPACITY;overwritten+=skip;consumed+=skip;}
        int count=0;
        while(consumed<end&&count<times.length){
            long seq=consumed;int slot=(int)seq&MASK;long version=versions.get(slot);
            if(version!=seq+1)break;
            long stamp=stamps[slot];int cost=durations[slot],miss=lost[slot];
            if(versions.get(slot)!=version)break;
            times[count]=stamp;costs[count]=cost;misses[count]=miss;consumed++;count++;
        }
        return count;
    }
    long published(){return published;}
    long overwritten(){return overwritten;}
}
