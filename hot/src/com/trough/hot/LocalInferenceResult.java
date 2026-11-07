package com.trough.hot;
import java.util.Map;
/** Child diagnostics are data, never proof that a nonzero process succeeded. */
final class LocalInferenceResult {
 static Map<String,Object> parse(String mode,boolean timedOut,boolean reaped,boolean complete,boolean overflow,String output){
  if(timedOut||!reaped||!complete||overflow)return null;
  String expected="dns".equals(mode)?"dns-v1":"qwen-cpu".equals(mode)?"qwen-infer-v1":"enumerate".equals(mode)?"enumerate-v1":"q2-matmul".equals(mode)?"q2-matmul-v1":("bonsai-cpu".equals(mode)||"bonsai-gpu".equals(mode))?"bonsai-infer-v1":null;
  if(expected==null)return null;
  try{
   String payload=output.trim();int last=payload.lastIndexOf('\n');if(last>=0)payload=payload.substring(last+1);
   Map<String,Object> value=Json.obj(Json.parse(payload));
   return value!=null&&expected.equals(Json.str(value,"kind"))?value:null;
  }catch(Exception malformed){return null;}
 }
 static Map<String,Object> failure(String id,String mode,boolean modelMode,boolean processStarted,long start,long end,int pid,Throwable error){
  return Json.map("id",id,"state","failed","mode",mode,"error",error.toString(),"started_ms",start,"completed_ms",end,"pid",pid,"process_started",processStarted,"model_run",modelMode&&processStarted?null:Boolean.FALSE);
 }
 static boolean completed(String mode,int exit,Map<String,Object> value){
  if(exit!=0||value==null)return false;
  String expected="dns".equals(mode)?"completed":"enumerate".equals(mode)?"enumerated":"q2-matmul".equals(mode)?"verified":"completed";
  return expected.equals(Json.str(value,"stage"));
 }
}
