package com.trough.hot;
import android.content.Context;
import android.hardware.*;
import android.os.SystemClock;
import android.view.View;
import java.util.Map;
/** Local interaction proxy, not a measurement of emotion or attention. */
final class MediaEngagement implements SensorEventListener {
 final SensorManager sensors;final Sensor accelerometer;boolean registered,closed;long lastTouch,lastSample;float lastMagnitude,motion;int interactions;
 MediaEngagement(Context c){sensors=(SensorManager)c.getSystemService(Context.SENSOR_SERVICE);accelerometer=sensors==null?null:sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);}
 void touch(){lastTouch=SystemClock.elapsedRealtime();interactions++;}
 void visible(boolean visible){if(closed)return;if(visible&&!registered&&accelerometer!=null){try{registered=sensors.registerListener(this,accelerometer,200000);}catch(RuntimeException ignored){registered=false;}}else if(!visible&&registered){sensors.unregisterListener(this);registered=false;}}
 public void onSensorChanged(SensorEvent e){float x=e.values[0],y=e.values[1],z=e.values[2],m=(float)Math.sqrt(x*x+y*y+z*z);long now=SystemClock.elapsedRealtime();if(lastSample>0&&now-lastSample<2000)motion=.9f*motion+.1f*Math.min(1,Math.abs(m-lastMagnitude)/3);lastMagnitude=m;lastSample=now;}
 public void onAccuracyChanged(Sensor s,int accuracy){}
 Map<String,Object> snapshot(View view,boolean playing){boolean shown=view.isShown()&&view.getWindowVisibility()==View.VISIBLE;visible(shown);long now=SystemClock.elapsedRealtime();double recent=lastTouch==0?0:Math.exp(-(now-lastTouch)/30000.0);double score=shown?(playing?.25:0)+.65*recent:0;return Json.map("kind","local-interaction-heuristic-v1","interpretation","Interaction proxy; not emotion or verified attention","score",Math.min(1,score),"visible",shown,"playing",playing,"window_focus",view.hasWindowFocus(),"recent_interaction",recent,"interactions",interactions,"motion",lastSample>0&&now-lastSample<2000?motion:0,"accelerometer_active",registered,"sensor_rate_us",200000,"raw_sensor_history",false,"observed_elapsed_ms",now);}
 void close(){closed=true;if(registered)sensors.unregisterListener(this);registered=false;}
}
