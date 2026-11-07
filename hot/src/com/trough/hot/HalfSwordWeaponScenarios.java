package com.trough.hot;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.io.FileOutputStream;
import org.json.*;

/** Interactive native decision examples; explicitly not live scene or solver output. */
final class HalfSwordWeaponScenarios extends LinearLayout {
 private final File base;private final TextView status,traceLabel,liveStatus;private final Plot plot;
 private final double[] rates=new double[96];private int samples,steps,scenario;private boolean left=true;
 private HalfSwordWeaponRelease.Input input;private final JSONArray trace=new JSONArray();
 private final LinearLayout sceneImages;private long imageVersion;
 private String receiptError="";
 private static final String[] NAMES={"Overload","Drop blocked","Grab release","Two-hand break","Two-hand transfer","Quiet force"};
 HalfSwordWeaponScenarios(Context context,File evidenceBase){
  super(context);base=evidenceBase;setOrientation(VERTICAL);setPadding(8,8,8,8);
  addView(text("RECOVERED WEAPON DECISIONS",21,0xff78ffe2));
  addView(text("NATIVE JAVA · SUPPLIED INPUTS · ACTION TRACE",11,0xffffd477));
  addView(text("Source: original left/right weapon timelines. This executes recovered decisions with example force/state; it is not a live game playthrough or recovered constraint solver.",12,0xffc8dbe8));
  liveStatus=text("Live shadow evidence loading…",12,0xff9bffbf);addView(liveStatus);sceneImages=new LinearLayout(context);addView(sceneImages,new LayoutParams(-1,400));
  LinearLayout side=new LinearLayout(context);side.addView(button("Left hand",new Runnable(){public void run(){left=true;reset();}}));side.addView(button("Right hand",new Runnable(){public void run(){left=false;reset();}}));addView(side);
  HorizontalScrollView scenarios=new HorizontalScrollView(context);LinearLayout choices=new LinearLayout(context);
  for(int i=0;i<NAMES.length;i++){final int choice=i;choices.addView(button(NAMES[i],new Runnable(){public void run(){scenario=choice;reset();}}));}scenarios.addView(choices);addView(scenarios);
  status=text("",15,Color.WHITE);addView(status);plot=new Plot(context);addView(plot,new LayoutParams(-1,190));
  LinearLayout controls=new LinearLayout(context);controls.addView(button("Step 0.1s",new Runnable(){public void run(){step();}}));controls.addView(button("Run 1s",new Runnable(){public void run(){for(int i=0;i<10;i++)advance();refresh();save();}}));controls.addView(button("Reset",new Runnable(){public void run(){reset();}}));addView(controls);
  traceLabel=text("",12,0xff97f5ff);traceLabel.setTypeface(android.graphics.Typeface.MONOSPACE);addView(traceLabel);
  addView(text("Each action is a requested boundary call, not an applied constraint change. Setup uses an opaque source-pinned WeaponParts literal. No video/audio playback.",11,0xffffce77));
  reset();for(int i=0;i<10;i++)advance();refresh();save();
 }
 void showObservation(JSONObject scene,JSONObject verified){
  loadSceneImages();
  JSONObject shadow=scene.optJSONObject("weapon_release_shadow");if(shadow==null){liveStatus.setText("LIVE SHADOW · no scene sample available");return;}
  JSONObject saved=verified.optJSONObject("shadow");String savedText=saved==null?"":"\nSaved verified episode "+saved.optLong("previous_episode_samples")+" samples · peak "+String.format(java.util.Locale.US,"%.2f",saved.optDouble("previous_episode_peak_force"))+" prototype N (worker checkpoint)";
  long captured=scene.optLong("time_ms"),age=Math.max(0,System.currentTimeMillis()-captured)/1000;
  liveStatus.setText("LIVE RULE · "+(scene.optBoolean("recovered_drop_enabled")?"ON":"OFF")+" · applied "+scene.optLong("recovered_drop_applied")+" since view opened\nLIVE SHADOW · "+(shadow.optBoolean("active")?"active sample":"inactive / completed episode")+" · capture "+age+"s ago\nCurrent samples "+shadow.optLong("samples")+" · previous episode "+shadow.optLong("previous_episode_samples")+" · previous peak "+String.format(java.util.Locale.US,"%.2f",shadow.optDouble("previous_episode_peak_force"))+" prototype N"+savedText+"\nShadow applies no actions; live rule opt-in · force ×100 and fixed grip/tonus/health assumed; original constraint force and state remain missing.");
 }
 private void loadSceneImages(){
  File held=new File(base,"build/recovered-drop-live/held.png"),released=new File(base,"build/recovered-drop-live/released.png");long version=held.lastModified()+released.lastModified();if(version==imageVersion)return;imageVersion=version;sceneImages.removeAllViews();
  for(String name:new String[]{"held","released"}){File file=new File(base,"build/recovered-drop-live/"+name+".png");if(!file.isFile()||file.length()>1048576)continue;android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(file.getPath(),options);options.inJustDecodeBounds=false;options.inSampleSize=1;while(options.outWidth/options.inSampleSize>640||options.outHeight/options.inSampleSize>960)options.inSampleSize*=2;android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(file.getPath(),options);if(bitmap==null)continue;LinearLayout cell=new LinearLayout(getContext());cell.setOrientation(VERTICAL);cell.addView(text(name.equals("held")?"HELD · TAP TO ZOOM":"DROPPED · TAP TO ZOOM",10,0xff9bffbf));ImageView image=new ImageView(getContext());image.setImageBitmap(bitmap);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setOnClickListener(new OnClickListener(){public void onClick(View v){android.view.ViewGroup.LayoutParams p=sceneImages.getLayoutParams();p.height=p.height==400?800:400;sceneImages.setLayoutParams(p);}});cell.addView(image,new LayoutParams(-1,0,1));sceneImages.addView(cell,new LayoutParams(0,-1,1));}
 }
 private Button findButton(android.view.View view,String name){
  if(view instanceof Button&&name.equals(((Button)view).getText().toString()))return (Button)view;
  if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button result=findButton(group.getChildAt(i),name);if(result!=null)return result;}}return null;
 }
 private void click(String label){Button b=findButton(this,label);if(b==null||!b.performClick())throw new IllegalStateException("Control unavailable: "+label);}
 void checkRequested(){
  File request=new File(base,"build/weapon-decision-programs/native-controls-check-request.json");if(!request.isFile())return;
  JSONObject result=new JSONObject();try{
   if(request.length()>512)throw new java.io.IOException("Oversize control request");JSONObject data=new JSONObject(new String(Payloads.readSmall(request),"UTF-8"));long requested=data.getLong("requested_ms");if(!request.delete())throw new java.io.IOException("Cannot claim control check");
   JSONArray observations=new JSONArray();
   for(String hand:new String[]{"Left hand","Right hand"})for(String example:NAMES){
    click(hand);click(example);click("Reset");if(steps!=0||input.releaseRate!=.65)throw new IllegalStateException("Reset did not restore state");click("Step 0.1s");if(steps!=1)throw new IllegalStateException("Step did not advance once");
    double first=input.releaseRate;if(("Drop blocked".equals(example)||"Quiet force".equals(example))?first>=.65:first<.65)throw new IllegalStateException("Unexpected rate direction");click("Run 1s");if(steps!=11)throw new IllegalStateException("Run did not advance ten steps");
    JSONObject observation=new JSONObject().put("hand",hand).put("scenario",example).put("rate_after_step",first).put("steps_after_run",steps).put("final_rate",input.releaseRate).put("last_action",trace.getJSONObject(trace.length()-1).getString("action"));observations.put(observation);
   }
   click("Left hand");click("Two-hand transfer");click("Run 1s");if(trace.getJSONObject(trace.length()-1).getInt("action_code")!=HalfSwordWeaponRelease.SETUP_RIGHT)throw new IllegalStateException("Two-hand transfer action mismatch");
   result.put("requested_ms",requested).put("passed",true).put("observations",observations).put("scope","Actual native Button.performClick selection/reset/step/run; example decisions only, no game actions");
  }catch(Exception e){try{result.put("passed",false).put("error",e.toString());}catch(Exception ignored){}}
  try{result.put("time_ms",System.currentTimeMillis());File target=new File(base,"build/weapon-decision-programs/native-controls-check-result.json");FileOutputStream stream=new FileOutputStream(target);try{stream.write(result.toString(2).getBytes("UTF-8"));}finally{stream.close();}}catch(Exception e){receiptError=e.getClass().getSimpleName();refresh();}
 }
 private TextView text(String s,int size,int color){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(3,4,3,4);return t;}
 private Button button(String s,final Runnable action){Button b=new Button(getContext());b.setText(s);b.setTextSize(11);b.setMinHeight(0);b.setMinimumHeight(0);b.setOnClickListener(new OnClickListener(){public void onClick(View v){action.run();}});return b;}
 private void reset(){
  input=new HalfSwordWeaponRelease.Input();input.left=left;input.gripType=scenario==2?0:1;input.releaseRate=.65;input.armTonus=50;input.bodyTonus=1;input.armHealth=50;input.consciousness=100;input.constraintRate=.5;input.forceMagnitude=scenario==5?0:10000;input.dt=.1;input.weaponConstraintValid=true;input.grabConstraintValid=true;input.blockDrop=scenario==1;
  input.softNotAttached=scenario!=4;input.leftSoftNotAttached=true;input.leftSoftNotRotated=true;input.rightSoftNotAttached=scenario!=4;input.twoHanded=scenario==3||scenario==4;input.leftOffhand=scenario==3;
  samples=1;rates[0]=input.releaseRate;steps=0;while(trace.length()>0)trace.remove(trace.length()-1);receiptError="";refresh();save();
 }
 private String action(HalfSwordWeaponRelease.Result r){switch(r.action){case 1:return "Release Grab "+(left?"L":"R");case 2:return "Break "+(left?"L Constraint("+r.breakLeftTransfer+")":"R Constraint");case 3:return "Set Up Right Hand Weapon";case 4:return "Set Up Left Hand Weapon";default:return "No action";}}
 private void advance(){
  HalfSwordWeaponRelease.Result r=HalfSwordWeaponRelease.update(input);input.releaseRate=r.releaseRate;steps++;
  if(samples==rates.length){System.arraycopy(rates,1,rates,0,rates.length-1);samples--;}rates[samples++]=r.releaseRate;
  try{JSONObject e=new JSONObject();e.put("step",steps).put("time_s",steps*.1).put("rate",r.releaseRate).put("threshold",r.threshold).put("action",action(r)).put("action_code",r.action).put("left_transfer",r.breakLeftTransfer);if(trace.length()>=12)trace.remove(0);trace.put(e);}catch(Exception ignored){}
 }
 private void step(){advance();refresh();save();}
 private void refresh(){
  HalfSwordWeaponRelease.Result r=HalfSwordWeaponRelease.update(input);
  status.setText((left?"LEFT":"RIGHT")+" · "+NAMES[scenario]+"\nRelease "+String.format(java.util.Locale.US,"%.2f",input.releaseRate)+" / 1 · force "+(int)input.forceMagnitude+" · threshold "+(int)r.threshold+"\nObserved steps "+steps+" · action requests repeat until caller updates state");
  StringBuilder s=new StringBuilder();int start=Math.max(0,trace.length()-5);for(int i=start;i<trace.length();i++){JSONObject e=trace.optJSONObject(i);s.append(String.format(java.util.Locale.US,"%4.1fs  %.2f  %s\n",e.optDouble("time_s"),e.optDouble("rate"),e.optString("action")));}if(trace.length()==0)s.append("Ready · step or run a bounded 1s example");if(!receiptError.isEmpty())s.append("\nReceipt: ").append(receiptError);traceLabel.setText(s.toString());plot.invalidate();
 }
 private void save(){
  try{JSONObject d=new JSONObject();d.put("time_ms",System.currentTimeMillis()).put("runtime","Android ART native dashboard").put("scenario",NAMES[scenario]).put("left",left).put("steps",steps).put("rate",input.releaseRate).put("trace",trace).put("live_game_integrated",false).put("force_source","supplied example");File target=new File(base,"build/weapon-decision-programs/android-scenario-latest.json");target.getParentFile().mkdirs();File temp=new File(target.getPath()+".tmp");FileOutputStream stream=new FileOutputStream(temp);try{stream.write(d.toString(2).getBytes("UTF-8"));}finally{stream.close();}if(!temp.renameTo(target))throw new java.io.IOException("Receipt rename failed");}catch(Exception e){receiptError=e.getClass().getSimpleName();}
 }
 private final class Plot extends View {
  private final Paint paint=new Paint(3);Plot(Context c){super(c);}
  protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),l=32,t=25,b=h-26,r=w-10;c.drawColor(0xff071a2d);paint.setTextSize(20);paint.setColor(0xffa4bfd4);c.drawText("RELEASE RATE / bounded steps",l,19,paint);paint.setStrokeWidth(1);
   for(int i=0;i<=4;i++){float y=b-(b-t)*i/4;c.drawLine(l,y,r,y,paint);}paint.setColor(0xffff72ca);paint.setStrokeWidth(3);c.drawLine(l,t,r,t,paint);paint.setColor(0xff65ffe1);paint.setStrokeWidth(4);for(int i=1;i<samples;i++)c.drawLine(l+(r-l)*(i-1)/Math.max(10,samples-1),b-(float)rates[i-1]*(b-t),l+(r-l)*i/Math.max(10,samples-1),b-(float)rates[i]*(b-t),paint);paint.setTextSize(17);c.drawText("0",9,b,paint);c.drawText("1",9,t+6,paint);
  }
 }
}
