package com.trough.hot;
import java.nio.*;import org.json.*;
/** Read-only authored head queries, only during optional injury training. No health/forces. */
final class HalfSwordHeadContactObservation {
 final ByteBuffer[] body={buffer(176),buffer(176)},result={buffer(64),buffer(64)},segments={buffer(112),buffer(112)};
 final HalfSwordContactHistory[] history={new HalfSwordContactHistory(),new HalfSwordContactHistory()};
 final boolean[] queryValid=new boolean[2],touchingNow=new boolean[2],motionValid=new boolean[2];
 final double[][] velocities={new double[3],new double[3]};
 final double[] velocity=new double[3];final long[] samples=new long[2],contacts=new long[2],motion=new long[2];final double[] gap={Double.NaN,Double.NaN};
 double closestGap=Double.POSITIVE_INFINITY;int closestStep;long updated;int step;String error="";JSONObject lastContact;
 static ByteBuffer buffer(int n){return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());}
 void reset(){for(int i=0;i<2;i++){history[i].reset();queryValid[i]=touchingNow[i]=motionValid[i]=false;samples[i]=contacts[i]=motion[i]=0;gap[i]=Double.NaN;}error="";lastContact=null;closestGap=Double.POSITIVE_INFINITY;closestStep=0;updated=0;step=0;}
 void sample(long handle,ByteBuffer actors,ByteBuffer target,ByteBuffer query,int side,int epoch,int currentStep){
  queryValid[side]=touchingNow[side]=motionValid[side]=false;
  if(actors==null){history[side].reset();return;}
  try{
   if(!HalfSwordBodyTarget.writeHead(actors,target,body[side]))throw new IllegalArgumentException("Head pose rejected");
   int rc=side==0?HalfSwordBridge.weaponBodyContact(handle,query,body[side],result[side]):HalfSwordBridge.secondaryWeaponBodyContact(handle,query,body[side],result[side]);
   if(rc!=0){history[side].reset();error="Head query "+rc;return;}
   queryValid[side]=true;samples[side]++;gap[side]=result[side].getInt(0)>0?result[side].getDouble(8):Double.NaN;
   if(side==0&&Double.isFinite(gap[side])&&gap[side]<closestGap){closestGap=gap[side];closestStep=currentStep;}
   boolean touching=result[side].getInt(4)!=0;touchingNow[side]=touching;if(touching)contacts[side]++;
   int snapshot=HalfSwordBridge.weaponBodySegments(handle,query,body[side],side==1,segments[side]);
   boolean valid=snapshot==0&&history[side].capture(epoch,currentStep,segments[side])&&history[side].velocity(result[side],HalfSwordStepPolicy.SECONDS,40,velocity);
   if(snapshot!=0)history[side].reset();if(valid){motion[side]++;System.arraycopy(velocity,0,velocities[side],0,3);}motionValid[side]=valid;
   updated=System.currentTimeMillis();step=currentStep;
   if(touching)lastContact=new JSONObject().put("side",side==0?"opponent":"player").put("bone","head").put("step",step).put("gap_m",gap[side]).put("motion_valid",valid).put("velocity_ue_cm_s",valid?new JSONArray(velocity):JSONObject.NULL);
  }catch(Exception bad){history[side].reset();error=bad.toString();}catch(LinkageError missing){history[side].reset();error="Head API unavailable";}
 }
 JSONObject observation()throws JSONException{return new JSONObject().put("mode","authored_head_contact_input_no_physical_response").put("actor_index",11).put("bone","head").put("time_ms",updated).put("step",step).put("closest_opponent_gap_m",Double.isFinite(closestGap)?closestGap:JSONObject.NULL).put("closest_step",closestStep).put("samples",new JSONArray(samples)).put("contact_samples",new JSONArray(contacts)).put("valid_motion_samples",new JSONArray(motion)).put("last_contact",lastContact==null?JSONObject.NULL:lastContact).put("error",error).put("limits","Authored capsule transformed by live actor11; injury routing is separate; no head physical response or original impact scheduling.");}
}
