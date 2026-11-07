package com.trough.hot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
/** Render-thread-owned passive instance. Physics stays instance-local; only the
 * committed pose copy receives the UE/cm world spawn translation and optional180degree facing.
 * No inter-character collision, AI or damage is implied by this instance. */
final class HalfSwordPassiveFighter {
 boolean bladeApproach,approachKnown;double approachGap,approachNx,approachNz;
 void approachSample(int status,ByteBuffer hit){approachKnown=status==0&&hit.getInt(0)>0;if(approachKnown){approachGap=hit.getDouble(8);approachNx=hit.getDouble(40);approachNz=hit.getDouble(56);}}
 private long context;
 private final ByteBuffer snapshot=ByteBuffer.allocateDirect(7568).order(ByteOrder.nativeOrder());
 final ByteBuffer actors=ByteBuffer.allocateDirect(6336).order(ByteOrder.nativeOrder());
 private final HalfSwordRootMotion motion=new HalfSwordRootMotion();
 private final double[] desired=new double[2];
 private final double[] acceleration=new double[2];
 private final ByteBuffer force=ByteBuffer.allocateDirect(40).order(ByteOrder.nativeOrder());
 private final ByteBuffer body=ByteBuffer.allocateDirect(176).order(ByteOrder.nativeOrder()),contact=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()),reaction=ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());
 private boolean pendingReaction;
 private final double[] bladeHandForce=new double[3];long bladeHandApplied;
 void bladeHandForce(double[] player)throws IOException{double n=HalfSwordBladeContactForce.norm(player);if(!Double.isFinite(n)||n>20.0000001)throw new IOException("Opponent hand contact over budget");double scale=n>20.?20./n:1.;for(int k=0;k<3;k++)bladeHandForce[k]=-player[k]*scale;}
 void clearBladeHand(){bladeHandForce[0]=bladeHandForce[1]=bladeHandForce[2]=0;}
 private final double[] pairForce=new double[3];
 private final ByteBuffer combinedForce=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
 void pairForce(double[] force){for(int k=0;k<3;k++)pairForce[k]=-force[k];}
 void clearPair(){pairForce[0]=pairForce[1]=pairForce[2]=0;}

 final HalfSwordTargetPractice practice=new HalfSwordTargetPractice();
 long probes,contacts,responses,reactions;
 boolean touching;double gap=Double.NaN;
 private final HalfSwordOpponentFrame world;
 private boolean supported,relaxed;
 final HalfSwordOpponentSwing swing=new HalfSwordOpponentSwing();boolean armDriven;long swingStarts;
 boolean injuryStrengthApplied;double injuryStrengthScale=1;
 void injuryStrength(HalfSwordInjuryMotorScale.Decision decision)throws IOException{
  if(relaxed||context==0){injuryStrengthApplied=false;injuryStrengthScale=1;return;}
  if(!decision.applied&&!injuryStrengthApplied)return;
  double scale=decision.stiffnessScale;
  int result=HalfSwordBridge.characterPhysicalArmStrength(context,true,scale);
  if(result==0)result=HalfSwordBridge.characterPhysicalArmStrength(context,false,scale);
  if(result!=0)throw new IOException("Opponent injury arm strength "+result);
  injuryStrengthApplied=decision.applied;injuryStrengthScale=scale;
 }
 void requestSwing()throws IOException{if(relaxed)throw new IOException("Reset relaxed opponent before swing");if(swing.start()){armDriven=true;swingStarts++;}}
 long steps,pairReactions;
 HalfSwordPassiveFighter(double x,double y){this(x,y,false);}HalfSwordPassiveFighter(double x,double y,boolean turned){world=new HalfSwordOpponentFrame(x,y,turned);}
 void open(HalfSwordPrototypeInputs config,ByteBuffer profiles,boolean stance,boolean refreshed,boolean friction)throws IOException{
  if(context!=0)throw new IOException("Passive context already open");
  long created=HalfSwordBridge.characterCreatePlatformWorld(config.packet,config.packet.capacity(),config.ground,config.templates,23);
  if(created==0)throw new IOException("Passive context rejected");
  try{
   int result=HalfSwordBridge.characterPhysicalHold(created,profiles,4);
   if(result==0&&stance)result=HalfSwordBridge.characterStanceSupport(created);
   else if(result==0&&refreshed)result=HalfSwordBridge.characterRefreshedSupport(created);
   else if(result==0&&friction)result=HalfSwordBridge.characterGroundFriction(created,true);
   if(result!=0)throw new IOException("Passive support rejected "+result);
   relaxed=false;injuryStrengthApplied=false;injuryStrengthScale=1;armDriven=false;swing.cancel();swingStarts=0;practice.reset();context=created;supported=refreshed||stance;motion.reset();steps=probes=contacts=responses=reactions=pairReactions=bladeHandApplied=0;clearReaction();clearBladeHand();
  }finally{if(context==0)HalfSwordBridge.characterRecordWorldDestroy(created);}
 }
 void relax()throws IOException{
  if(context==0)throw new IOException("Opponent context absent");if(relaxed)return;
  ByteBuffer zero=ByteBuffer.allocateDirect(792).order(ByteOrder.nativeOrder());int result=HalfSwordBridge.characterPhysicalHold(context,zero,4);
  if(result!=0)throw new IOException("Opponent relax rejected "+result);relaxed=true;injuryStrengthApplied=false;injuryStrengthScale=1;supported=false;armDriven=false;swing.cancel();
 }
 void step(long viewer,ByteBuffer[] binding)throws IOException{step(viewer,binding,0,0,false);}
 void step(long viewer,ByteBuffer[] binding,double playerX,double playerY,boolean follow)throws IOException{
  if(context==0)throw new IOException("Passive context absent");
  if(armDriven&&!relaxed){swing.step();int aimed=HalfSwordBridge.characterPhysicalArmAim(context,true,swing.pitch,swing.yaw);if(aimed!=0)throw new IOException("Opponent physical swing "+aimed);}
  boolean paired=pairForce[0]!=0||pairForce[1]!=0||pairForce[2]!=0;boolean forced=pendingReaction||paired;
  if(forced){HalfSwordForceRow.put(combinedForce,0,2,(pendingReaction?force.getDouble(8):0)+world.localHorizontal(pairForce[0])*100.,(pendingReaction?force.getDouble(16):0)+world.localHorizontal(pairForce[2])*100.,(pendingReaction?force.getDouble(24):0)+pairForce[1]*100.,10000.);}
  boolean hand=HalfSwordBladeContactForce.norm(bladeHandForce)>0;int forceCount=forced?1:0;
  if(hand){HalfSwordForceRow.put(combinedForce,forceCount++,8,world.localHorizontal(bladeHandForce[0])*100.,world.localHorizontal(bladeHandForce[2])*100.,bladeHandForce[1]*100.,2000.);}
  int result;
  if(supported){
   desired[0]=desired[1]=0;
   if(follow){if(bladeApproach&&swing.active){desired[0]=desired[1]=0.;}else if(bladeApproach&&approachKnown){if(!HalfSwordOpponentApproach.command(approachGap,approachNx,approachNz,swing.active,desired))throw new IOException("Opponent blade approach rejected");}else if(!HalfSwordOpponentSpacing.command(playerX-world.worldX(snapshot.getDouble(0)),playerY-world.worldY(snapshot.getDouble(8)),desired))throw new IOException("Opponent spacing rejected");}
   if(!HalfSwordMovementVelocity.command(world.localHorizontal(desired[0]),world.localHorizontal(desired[1]),motion.ready?motion.vx:snapshot.getFloat(40),motion.ready?motion.vy:snapshot.getFloat(44),acceleration))throw new IOException("Passive root velocity rejected");
   result=HalfSwordBridge.characterRecordWorldAcceleration(context,acceleration[0],acceleration[1],10000.,forceCount>0?combinedForce:null,forceCount,snapshot);
  }else result=forceCount>0?HalfSwordBridge.characterRecordWorldForces(context,combinedForce,forceCount,snapshot):HalfSwordBridge.characterRecordWorldStep(context,snapshot);
  if(result!=0)throw new IOException("Passive physics step "+result);
  if(pendingReaction){reactions++;clearReaction();}if(paired)pairReactions++;if(hand)bladeHandApplied++;clearBladeHand();clearPair();
  if(!motion.sample(snapshot.getDouble(0),snapshot.getDouble(8),HalfSwordStepPolicy.SECONDS))throw new IOException("Passive committed root rejected");
  world.copyActors(snapshot,actors);
  result=HalfSwordBridge.secondaryCurrentFrame(viewer,binding[0],binding[1],actors,binding[2],binding[3],binding[4]);
  if(result!=0)throw new IOException("Passive pose "+result);steps++;
 }
 ByteBuffer bodyInput(){return body;}
 void clearReaction(){pendingReaction=false;}
 void queueReaction(ByteBuffer input)throws IOException{
  double x=input.getDouble(0),y=input.getDouble(8),z=input.getDouble(16),norm=x*x+y*y+z*z;
  if(!Double.isFinite(norm)||norm>400.000001)throw new IOException("Passive reaction exceeds20N");
  // Renderer metres/Newtons XYZ -> UE centimetres XZY force units. Spawn
  // uses an optional turn; inverse-map horizontal vectors without translation.
  double scale=norm>400.?20./Math.sqrt(norm):1.;
  force.putInt(0,2).putInt(4,0).putDouble(8,world.localHorizontal(x)*100.*scale).putDouble(16,world.localHorizontal(z)*100.*scale).putDouble(24,y*100.*scale).putDouble(32,2000.);
  pendingReaction=norm>0;
 }
 void contact(long viewer,ByteBuffer targetConfig,ByteBuffer bladeConfig,boolean respond)throws IOException{
  if(!HalfSwordBodyTarget.write(actors,targetConfig,body))throw new IOException("Passive torso pose rejected");
  int result=HalfSwordBridge.weaponBodyContact(viewer,bladeConfig,body,contact);probes++;
  if(result<0)throw new IOException("Passive torso query "+result);
  touching=result==0&&contact.getInt(4)!=0;gap=result==0?contact.getDouble(8):Double.NaN;
  practice.step(touching);
  if(touching){contacts++;if(respond){result=HalfSwordBridge.weaponBodyResponse(viewer,contact,reaction);if(result<0)throw new IOException("Passive weapon response "+result);if(result==0){queueReaction(reaction);if(pendingReaction)responses++;}}}
 }
 static void copyWorldActors(ByteBuffer source,ByteBuffer output,double x,double y){
  new HalfSwordOpponentFrame(x,y,false).copyActors(source,output);
 }
 void close(){long old=context;context=0;steps=0;clearReaction();clearPair();clearBladeHand();motion.reset();if(old!=0)HalfSwordBridge.characterRecordWorldDestroy(old);}
}
