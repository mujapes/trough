package com.trough.hot;
import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.os.Bundle;
import java.io.File;
import java.util.Locale;
/** Explicitly requested eyes-free feedback, independent of chat rendering. */
final class NativeVoice {
 private static TextToSpeech voice;
 private static android.media.MediaPlayer audio;private static long lastAudioAttempt;
 private static boolean initializing;private static long lastAttempt;
 static synchronized void pending(final Context context){
  if(new File(context.getFilesDir(),"home/.trough/voice-muted").isFile()){if(voice!=null){voice.stop();voice.shutdown();voice=null;}if(audio!=null){audio.release();audio=null;}return;}
  final File audioRequest=new File(context.getFilesDir(),"home/.trough/voice-audio.txt");
  if(audioRequest.isFile()&&audioRequest.length()<256&&audioRequest.lastModified()!=lastAudioAttempt){
   lastAudioAttempt=audioRequest.lastModified();
   try{
    String name=new String(Payloads.readSmall(audioRequest),"UTF-8").trim();File dir=new File(context.getFilesDir(),"home/.trough/media"),clip=new File(dir,name);
    if(!name.equals(clip.getName())||!name.endsWith(".wav")||!dir.getCanonicalFile().equals(clip.getCanonicalFile().getParentFile())||!clip.isFile()||clip.length()>16000000)throw new IllegalArgumentException("Invalid voice clip");
    if(voice!=null){voice.stop();voice.shutdown();voice=null;}if(audio!=null)audio.release();
    final android.media.MediaPlayer candidate=new android.media.MediaPlayer();audio=candidate;candidate.setDataSource(clip.getPath());candidate.setVolume(.65f,.65f);
    candidate.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener(){public void onPrepared(android.media.MediaPlayer p){p.start();audioRequest.delete();try{Payloads.writeSmall(new File(context.getFilesDir(),"home/.trough/voice-status.json"),Json.write(Json.map("ready",true,"playing",true,"engine","local-neural-wav","time_ms",System.currentTimeMillis())));}catch(Exception ignored){}}});
    candidate.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener(){public void onCompletion(android.media.MediaPlayer p){p.release();if(audio==p)audio=null;}});
    candidate.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener(){public boolean onError(android.media.MediaPlayer p,int what,int extra){p.release();if(audio==p)audio=null;return true;}});candidate.prepareAsync();
   }catch(Exception error){try{Payloads.writeSmall(new File(context.getFilesDir(),"home/.trough/voice-status.json"),Json.write(Json.map("ready",false,"engine","local-neural-wav","error",error.getClass().getSimpleName(),"time_ms",System.currentTimeMillis())));}catch(Exception ignored){}}
   return;
  }
  final File request=new File(context.getFilesDir(),"home/.trough/speak-once.txt");
  if(!request.isFile()||request.length()>2000||initializing||request.lastModified()==lastAttempt)return;
  final String words;try{words=new String(Payloads.readSmall(request),"UTF-8");}catch(Exception e){return;}
  initializing=true;lastAttempt=request.lastModified();
  if(voice!=null){voice.shutdown();voice=null;}
  voice=new TextToSpeech(context.getApplicationContext(),new TextToSpeech.OnInitListener(){public void onInit(int status){
   initializing=false;
   try{if(status!=TextToSpeech.SUCCESS){Payloads.writeSmall(new File(context.getFilesDir(),"home/.trough/voice-status.json"),"{\"ready\":false}");return;}
    voice.setLanguage(Locale.UK);voice.setSpeechRate(1f);voice.setPitch(1f);Bundle options=new Bundle();options.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,.7f);
    int result=voice.speak(words,TextToSpeech.QUEUE_FLUSH,options,"eyes-free");
    Payloads.writeSmall(new File(context.getFilesDir(),"home/.trough/voice-status.json"),Json.write(Json.map("ready",true,"accepted",result==TextToSpeech.SUCCESS,"time_ms",System.currentTimeMillis())));
    if(result==TextToSpeech.SUCCESS)request.delete();
   }catch(Exception ignored){}
  }});
 }
}
