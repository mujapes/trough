package com.trough.hot;
import java.nio.*;
/** Prototype torso COM penalty force; UE force units, renderer contact metres. */
final class HalfSwordBodyReaction {
 // flags1 command,2 reaction included,4 rejected optional contact; -1 invalid manual/output.
 static int combine(boolean forcing,double pushX,double pushY,boolean enabled,ByteBuffer contact,double vx,double vy,double vz,double[] out){
  if(out==null||out.length<3||(forcing&&(!Double.isFinite(pushX)||!Double.isFinite(pushY))))return -1;
  double x=forcing?pushX:0,y=forcing?pushY:0,z=0;boolean reaction=false;int flags=0;
  if(enabled){boolean valid=contact!=null&&contact.limit()>=64&&contact.order()==ByteOrder.nativeOrder()&&Double.isFinite(vx)&&Double.isFinite(vy)&&Double.isFinite(vz);
   if(valid&&contact.getInt(4)!=0){double phi=contact.getDouble(8),nx=contact.getDouble(40),ny=contact.getDouble(48),nz=contact.getDouble(56),norm=Math.hypot(Math.hypot(nx,ny),nz);valid=contact.getInt(0)>0&&Double.isFinite(phi)&&phi<=0&&Double.isFinite(norm)&&norm>0;
    if(valid){nx/=norm;ny/=norm;nz/=norm;double speed=(vx*nx+vy*nz+vz*ny)*.01,magnitude=-phi*50000.-speed*2000.;valid=Double.isFinite(magnitude);if(valid){magnitude=Math.max(0.,Math.min(9999.,magnitude));x+=nx*magnitude;y+=nz*magnitude;z+=ny*magnitude;reaction=magnitude>0;}}
   }
   if(!valid)flags|=4;
  }
  double scale=Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)));if(scale>0){double norm=Math.hypot(Math.hypot(x/scale,y/scale),z/scale);if(scale>9999./norm){double capped=9999./norm;x=x/scale*capped;y=y/scale*capped;z=z/scale*capped;}}
  out[0]=x;out[1]=y;out[2]=z;if(x!=0||y!=0||z!=0){flags|=1;if(reaction)flags|=2;}return flags;
 }
}
