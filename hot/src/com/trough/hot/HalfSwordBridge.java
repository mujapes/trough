package com.trough.hot;
import android.content.Context;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;
import org.json.JSONObject;
final class HalfSwordBridge {
 private static boolean loaded;
 static volatile String loadedModuleSha256="";
 static synchronized void load(Context context)throws Exception {
  if(loaded)return;
  File build=new File(context.getFilesDir(),"work/trough-lab/half-sword/build/android");
  JSONObject module=new JSONObject(new String(Payloads.readSmall(new File(build,"current.json")),"UTF-8"));String name=module.getString("file"),hash=module.getString("sha256");
  if(module.getInt("abi")!=1||!name.matches("libhs-core-[a-f0-9]{16}\\.so")||!hash.matches("[a-f0-9]{64}"))throw new IOException("Native module descriptor");
  HalfSwordModuleIdentity.require(hash);
  File original=new File(build,name);
  if(!original.isFile()||original.length()>2097152||original.length()!=module.getLong("bytes")||!hash.equals(NativeSwipe.sha(original)))throw new IOException("Native mesh library unavailable or changed");
  File dir=new File(context.getCodeCacheDir(),"half-sword");dir.mkdirs();
  File copy=new File(dir,hash.substring(0,20)+"-"+Long.toHexString(System.nanoTime())+".so");
  try(InputStream in=new FileInputStream(original);FileOutputStream out=new FileOutputStream(copy)){byte[] block=new byte[16384];int n;while((n=in.read(block))!=-1)out.write(block,0,n);out.getFD().sync();}
  if(!copy.setReadOnly()||!hash.equals(NativeSwipe.sha(copy)))throw new IOException("Native mesh library verification");
  System.load(copy.getAbsolutePath());loadedModuleSha256=hash;loaded=true;
 }
 static native long open(Surface surface,ByteBuffer vertices,int vertexCount,ByteBuffer indices,int indexCount,ByteBuffer palette);
 static native int cameraFocus(long handle,float x,float z);
 static native int draw(long handle,int width,int height,float yaw,double seconds,boolean shapes);
 static native int collisionLines(long handle,ByteBuffer lines);
 static native int animate(long handle,ByteBuffer rig,ByteBuffer clip,float rate,float duration);
 // Render thread only. Native-order u32 bone IDs and packed double q[4],p[3]
 // BONE world targets in Unreal/cm. Success holds this pose across draw calls.
 static native int worldTargets(long handle,ByteBuffer boneIds,ByteBuffer targets,int count);
 // Render thread only. Exactly 67 complete component-space TRS records,
 // each 96 bytes: double q[4], p[4], s[4], with p.w/s.w zero padding.
 static native int componentTransforms(long handle,ByteBuffer components,int count);
 // Render thread only, after animate. Persistent scratch belongs to this binding.
 static native int physicsSetup(long handle);
 static native int secondarySetup(long handle,ByteBuffer rig,ByteBuffer clip);
 static native int secondaryCurrentFrame(long handle,ByteBuffer parents,ByteBuffer map,ByteBuffer actors,ByteBuffer alphas,ByteBuffer oldLocal,ByteBuffer componentFrame);
 // Full 67-bone frame: native-order i32 parents/map, double actor TRS (96B),
 // float alphas, double old-local TRS and one component-to-world TRS (96B).
 // Actor count is 0..22; a zero count permits null actors. No pose promotion.
 static native int physicsFrame(long handle,ByteBuffer parents,ByteBuffer map,
  ByteBuffer actors,int actorCount,ByteBuffer alphas,ByteBuffer oldLocal,ByteBuffer componentFrame);
 // Explicit selected actor stage, packed hs_rigid_state records (288B each).
 // The five other frame inputs and physicsSetup lifetime match physicsFrame.
 // Neither entry promotes predicted poses into current actor state.
 static native int rigidCurrentFrame(long handle,ByteBuffer parents,ByteBuffer map,
  ByteBuffer actors,int actorCount,ByteBuffer alphas,ByteBuffer oldLocal,ByteBuffer componentFrame);
 static native int rigidPredictedFrame(long handle,ByteBuffer parents,ByteBuffer map,
  ByteBuffer actors,int actorCount,ByteBuffer alphas,ByteBuffer oldLocal,ByteBuffer componentFrame);
 // Character context has an independent render-thread-owned lifetime. Create
 // copies absolute bytes[0..bytes), allowing readonly direct input. Step and
 // snapshot write absolute7568 bytes (actors6336+history1232), with no cursor
 // changes. Direct output must be writable with limit>=7568. No implicit loop.
 static native int weaponState(long handle,ByteBuffer output);
 static native int weaponHoldMode(long handle,boolean enabled);
 static native int weaponDropState(long handle,ByteBuffer output);
 static native int weaponRelease(long handle);
 static native int weaponContactReset(long handle);
 static native int weaponTargetProbe(ByteBuffer input,ByteBuffer output);
 static native int torsoPairContact(ByteBuffer body0,ByteBuffer body1,double cull,ByteBuffer output);
 static native int weaponBodyResponse(long handle,ByteBuffer contact,ByteBuffer force);
 static native int secondaryWeaponBodyContact(long handle,ByteBuffer config,ByteBuffer body,ByteBuffer output);
 // Requires contact-segments module; caller must gate availability before use. Output: 14 doubles.
 static native int weaponBodySegments(long handle,ByteBuffer config,ByteBuffer body,boolean secondary,ByteBuffer output);
 static native int weaponBodyContact(long handle,ByteBuffer config,ByteBuffer body,ByteBuffer output);
 static native int weaponTargetContact(long handle,ByteBuffer config,ByteBuffer output);
 static native int characterRecordWorldAcceleration(long context,double ax,double ay,double budget,ByteBuffer forces,int count,ByteBuffer snapshot);
 static native int characterRecordWorldForces(long context,ByteBuffer forces,int count,ByteBuffer snapshot);
 static native int characterRecordWorldMotor(long context,int joint,ByteBuffer state,ByteBuffer bone,double damping,double stiffnessScale,double dampingScale);
 static native long characterCreateRecordWorld(ByteBuffer packet,int bytes,ByteBuffer ground,ByteBuffer templates,int count);
 static native int characterRefreshedSupport(long context);
 static native int characterStanceSupport(long context);
 static native int characterArmAimCapabilities();
 static native int characterWalkCapabilities();
 static native int characterWalkingStanceSupport(long context);
 static native int characterWalkBegin(long context,ByteBuffer packet,int bytes,int first);
 static native int characterWalkForces(long context,ByteBuffer forces,int count,ByteBuffer output);
 static native int characterWalkCancel(long context);
 static native int characterPositionAssistance(long context,int actor,double cap,double stiffness);
 static native int characterPhysicalArmAim(long context,boolean right,int pitch,int yaw);
 static native int characterPhysicalArmStrength(long context,boolean right,double scale);
 static native int characterGroundFriction(long context,boolean enabled);
 static native int trainingTargetOffset(long handle,int first,float x,float y,float z);
 static native int characterPhysicalHold(long context,ByteBuffer profiles,int passes);
 static native long characterCreatePlatformWorld(ByteBuffer packet,int bytes,ByteBuffer ground,ByteBuffer templates,int count);
 static native int characterRecordWorldStep(long context,ByteBuffer output);
 static native int characterRecordWorldForceStep(long context,int body,double x,double y,double z,double limit,ByteBuffer output);
 static native int characterRecordWorldSnapshot(long context,ByteBuffer output);
 static native void characterRecordWorldDestroy(long context);
 static native long characterCreate(ByteBuffer packet,int bytes);
 // Explicit optional ground/template buffers; same owned-copy lifetime.
 static native long characterCreateGround(ByteBuffer packet,int bytes,ByteBuffer ground,ByteBuffer templates,int count);
 static native int characterStep(long context,ByteBuffer snapshot);
 // Explicit one-frame source-unit force, Euclidean length <= caller maximum.
 // No persistent force or wake callbacks; same output/lifetime contract.
 static native int characterForceStep(long context,int actor,double fx,double fy,double fz,double maximum,ByteBuffer snapshot);
 static native int characterSnapshot(long context,ByteBuffer snapshot);
 static native void characterDestroy(long context);
 // Absolute exact-size direct buffers: bodies352B each, descriptor496B, point72B.
 static native int sphereBodyContactStep(ByteBuffer body0,ByteBuffer body1,ByteBuffer descriptor,ByteBuffer point);
 static native int breathing(long handle);
 static native void close(long handle);
}
