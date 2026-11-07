package com.trough.hot;
/** UI-thread ownership, idempotent per control; one cancel cannot release another. */
final class HalfSwordTouchCapture {
 static final int BODY=1,AIM=2,CAMERA=4;private int owners;
 private boolean set(int owner,boolean active){if(owner!=BODY&&owner!=AIM&&owner!=CAMERA)throw new IllegalArgumentException("Unknown touch owner");owners=active?owners|owner:owners&~owner;return owners!=0;}
 int update(int owner,boolean active){int previous=owners;set(owner,active);return owners==previous?-1:owners==0?0:1;}
 int releaseAll(){if(owners==0)return -1;owners=0;return 0;}
 int owners(){return owners;}
}
