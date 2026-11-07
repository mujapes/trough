package com.trough.hot;

import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Reused delta/varint encoder and level-one gzip workspace; no per-block codec allocation. */
final class FrameBlockCodec implements AutoCloseable {
    private final byte[] raw=new byte[4096],packed=new byte[8192];
    private final Deflater deflater=new Deflater(1,true);
    private final CRC32 crc=new CRC32();
    private int position;
    private void integer(int value){for(int shift=24;shift>=0;shift-=8)raw[position++]=(byte)(value>>>shift);}
    private void number(long value){integer((int)(value>>>32));integer((int)value);}
    private void variable(long value){while((value&~127L)!=0){raw[position++]=(byte)(((int)value&127)|128);value>>>=7;}raw[position++]=(byte)value;}
    int encode(long wall,int count,long overwritten,int budget,int pid,long[] times,int[] costs,int[] misses){
        position=0;integer(0x54465232);number(wall);integer(count);number(overwritten);number(times[0]);integer(budget);integer(pid);
        long previous=times[0];
        for(int i=0;i<count;i++){
            long delta=(times[i]-previous)/1000;previous=times[i];
            variable((delta<<1)^(delta>>63));variable(Math.max(0,costs[i]));variable(Math.max(0,misses[i]));
        }
        packed[0]=31;packed[1]=(byte)139;packed[2]=8;packed[3]=0;packed[4]=0;packed[5]=0;packed[6]=0;packed[7]=0;packed[8]=4;packed[9]=(byte)255;
        deflater.reset();deflater.setInput(raw,0,position);deflater.finish();
        int length=10;
        while(!deflater.finished()){
            int n=deflater.deflate(packed,length,packed.length-length-8);
            if(n==0)throw new IllegalStateException("Frame compression capacity");length+=n;
        }
        crc.reset();crc.update(raw,0,position);long checksum=crc.getValue();
        for(int shift=0;shift<32;shift+=8)packed[length++]=(byte)(checksum>>>shift);
        for(int shift=0;shift<32;shift+=8)packed[length++]=(byte)(position>>>shift);
        return length;
    }
    byte[] bytes(){return packed;}
    @Override public void close(){deflater.end();}
}
