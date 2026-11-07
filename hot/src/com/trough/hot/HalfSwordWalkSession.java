package com.trough.hot;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
// Worker-thread confined. Caller keeps ordinary step routing when inactive.
final class HalfSwordWalkSession {
 interface Native {
  int setup(long h);int effort(long h,int actor,double cap);int begin(long h,ByteBuffer packet,int first);
  int step(long h,ByteBuffer forces,int count,ByteBuffer out);int cancel(long h);
 }
 static final int INACTIVE=Integer.MIN_VALUE;
 static final Native JNI=new Native(){
  public int setup(long h){return HalfSwordBridge.characterWalkingStanceSupport(h);}
  public int effort(long h,int actor,double cap){return HalfSwordBridge.characterPositionAssistance(h,actor,cap,1.);}
  public int begin(long h,ByteBuffer p,int first){return HalfSwordBridge.characterWalkBegin(h,p,p.capacity(),first);}
  public int step(long h,ByteBuffer forces,int count,ByteBuffer out){return HalfSwordBridge.characterWalkForces(h,forces,count,out);}
  public int cancel(long h){return HalfSwordBridge.characterWalkCancel(h);}
 };
 final Native api;long context;ByteBuffer packet;double rootCap;volatile boolean ready;volatile boolean active;volatile int frame;volatile String state="Walking setup not selected";
 HalfSwordWalkSession(){this(JNI);}HalfSwordWalkSession(Native api){this.api=api;}
 static boolean supported(){try{return HalfSwordBridge.characterWalkCapabilities()==3;}catch(LinkageError absent){return false;}}
 static double rootCap(ByteBuffer input){
  if(input==null||!input.isDirect()||input.capacity()>400000||input.limit()!=input.capacity())throw new IllegalArgumentException("Prepared direct packet required");
  ByteBuffer b=input.duplicate().order(ByteOrder.LITTLE_ENDIAN);int cursor=0,mass=-1;
  for(int section=0;section<21;section++){
   if(cursor<0||cursor>b.limit()-4)throw new IllegalArgumentException("Packet header");int length=b.getInt(cursor);cursor+=4;
   if(length<0||length>b.limit()-cursor)throw new IllegalArgumentException("Packet section");
   if(section==11){if(length!=176)throw new IllegalArgumentException("Mass section");mass=cursor;}cursor+=length;
  }
  if(cursor!=b.limit()||mass<0)throw new IllegalArgumentException("Packet length");
  double total=0;for(int actor=0;actor<22;actor++){double inverse=b.getDouble(mass+actor*8);if(!Double.isFinite(inverse)||inverse<=0)throw new IllegalArgumentException("Mass value");if(actor!=12&&actor!=13&&actor!=14&&actor!=19&&actor!=20&&actor!=21)total+=1./inverse;}
  double cap=total*9.8;if(!Double.isFinite(cap)||cap<=0)throw new IllegalArgumentException("Root assistance");return cap;
 }
 int attach(long h,ByteBuffer p){
  if(active||h==0)return -1;double cap=rootCap(p);int result=api.setup(h);if(result!=0)return result;
  context=h;packet=p.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);rootCap=cap;ready=true;frame=0;state="Settle for 180 frames, then start walking";return 0;
 }
 private int clearEffort(){int first=0;for(int actor:new int[]{0,14,21}){int result=api.effort(context,actor,0);if(first==0&&result!=0)first=result;}return first;}
 int start(int first,int settledSteps){
  if(!ready||active||settledSteps<180||(first!=14&&first!=21))return -1;
  int result=api.effort(context,0,rootCap);if(result==0)result=api.effort(context,14,100);if(result==0)result=api.effort(context,21,100);
  if(result==0)result=api.begin(context,packet,first);
  if(result!=0){int cleanup=clearEffort();state="Walking start rejected "+result;return cleanup!=0?cleanup:result;}
  frame=0;active=true;state="Walking";return 0;
 }
 int cancel(){
  if(!active)return 0;int result=api.cancel(context);if(result!=0)return result;active=false;result=clearEffort();state="Walking cancelled";return result;
 }
 int step(ByteBuffer forces,int count,ByteBuffer out){
  if(!active)return INACTIVE;int result=api.step(context,forces,count,out);if(result!=0)return result;
  frame++;if(frame==714){int done=cancel();if(done!=0)return done;state="Four-step test complete";}return 0;
 }
 // INACTIVE asks the scene to execute its ordinary step with the same forces.
 int controlledStep(ByteBuffer forces,int count,ByteBuffer out,boolean interrupted){
  if(!active)return INACTIVE;
  if(interrupted){int result=cancel();return result==0?INACTIVE:result;}
  int result=step(forces,count,out);
  if(result==-2){int cleanup=cancel();if(cleanup!=0)return cleanup;state="Walking cancelled by changed pose controls";return INACTIVE;}
  return result;
 }
 void forget(){context=0;packet=null;ready=false;active=false;frame=0;state="Walking setup not selected";}
}
