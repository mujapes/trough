package com.trough.hot;
import java.nio.ByteBuffer;
/** One render writer. Publish copies primitives only; readers allocate before taking the lock. */
final class HalfSwordObservation {
 static final class Copy {final double[] values=new double[12];boolean ready,finite;double peak;int step;long observedMs;}
 private final double[] scratch=new double[12],values=new double[12];private boolean ready,finite;private double peak;private int step;private long observedMs;
 synchronized void clear(){ready=false;step=0;observedMs=0;}
 boolean publish(ByteBuffer snapshot,int nextStep,long now){
  if(snapshot==null||snapshot.limit()<6336){clear();return false;}
  boolean valid=true;double maximum=0;
  for(int i=0;i<22;i++){int offset=i*288;for(int k=0;k<3;k++){double p=snapshot.getDouble(offset+k*8);float v=snapshot.getFloat(offset+40+k*4);valid&=Double.isFinite(p)&&Float.isFinite(v);if(i==0){scratch[k]=p;scratch[3+k]=v;}if(i==8)scratch[6+k]=p;if(i==18)scratch[9+k]=p;}double x=snapshot.getFloat(offset+40),y=snapshot.getFloat(offset+44),z=snapshot.getFloat(offset+48);maximum=Math.max(maximum,Math.sqrt(x*x+y*y+z*z));}
  synchronized(this){System.arraycopy(scratch,0,values,0,12);finite=valid;peak=maximum;step=nextStep;observedMs=now;ready=true;}return true;
 }
 Copy copy(){Copy out=new Copy();synchronized(this){out.ready=ready;out.finite=finite;out.peak=peak;out.step=step;out.observedMs=observedMs;System.arraycopy(values,0,out.values,0,12);}return out;}
}
