package com.trough.hot;
/** Held arrow state; repeat events never create a new press. */
final class HalfSwordMoveKeys {
 int held;
 boolean accepts(int bit,int repeats){return accepts(bit,repeats,false);}
 boolean accepts(int bit,int repeats,boolean touchActive){return !touchActive&&(repeats==0||(held&bit)!=0);}
 boolean press(int bit){boolean fresh=(held&bit)==0;held|=bit;return fresh;}
 void release(int bit){held&=~bit;}
 void clear(){held=0;}
 float x(){return ((held&2)!=0?1:0)-((held&1)!=0?1:0);}
 float y(){return ((held&4)!=0?1:0)-((held&8)!=0?1:0);}
}
