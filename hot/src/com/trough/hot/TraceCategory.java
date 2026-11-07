package com.trough.hot;
import java.util.Locale;
import java.util.Map;
/** Presentation hints inferred from actual commands/output, never execution-status claims. */
final class TraceCategory {
 static final java.util.concurrent.ConcurrentHashMap<String,java.util.regex.Pattern> PATTERNS=new java.util.concurrent.ConcurrentHashMap<String,java.util.regex.Pattern>();
 static java.util.Map<String,TraceCategory> bounded(){return java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String,TraceCategory>(64,.75f,true){protected boolean removeEldestEntry(java.util.Map.Entry<String,TraceCategory> entry){return size()>256;}});}
 static final java.util.Map<String,TraceCategory> CALLS=bounded(),RESULTS=bounded();
 static TraceCategory call(String raw){TraceCategory cached=CALLS.get(raw);if(cached!=null)return cached;TraceCategory value=describeCall(raw);CALLS.put(raw,value);return value;}
 static TraceCategory result(String raw,boolean error){if(error)return of("Error output","!",0xffffa49a);TraceCategory cached=RESULTS.get(raw);if(cached!=null)return cached;TraceCategory value=describeResult(raw,false);RESULTS.put(raw,value);return value;}
 final String title,icon;final int color;
 TraceCategory(String name,String symbol,int tint){title=name;icon=symbol;color=tint;}
 static TraceCategory of(String name,String icon,int color){return new TraceCategory(name,icon,color);}
 static boolean command(String text,String commands){java.util.regex.Pattern pattern=PATTERNS.get(commands);if(pattern==null){pattern=java.util.regex.Pattern.compile("(?:^|[\\s;&|/])(?:"+commands+")(?:\\s|$)");java.util.regex.Pattern previous=PATTERNS.putIfAbsent(commands,pattern);if(previous!=null)pattern=previous;}return pattern.matcher(text).find();}

 static TraceCategory describeCall(String raw){
  String n=raw.trim();if(n.startsWith("▸"))n=n.substring(1).trim();int split=n.indexOf(' ');String tool=(split<0?n:n.substring(0,split)).toLowerCase(Locale.ROOT);String body=n;
  int nl=raw.indexOf('\n');if(nl>=0)try{Map<String,Object> args=Json.obj(Json.parse(raw.substring(nl+1)));for(String k:new String[]{"command","cmd","pattern","file_path","url"}){Object v=args.get(k);if(v instanceof String){body=(String)v;break;}}}catch(Exception ignored){}
  String b=body.toLowerCase(Locale.ROOT);
  if(tool.contains("patch")||tool.contains("edit")||tool.contains("write")||b.contains(".write_text(")||b.contains(".write(")||command(b,"apply_patch"))return of("Editing files","✎",0xffffce8a);
  if(command(b,"pytest|unittest|ctest")||b.contains("--check")||b.matches("(?s).*python3?\\s+\\S*test_[^\\s]*\\.py(?:\\s|$).*"))return of("Testing & validation","✓",0xffa2e3bb);
  if(command(b,"javac|gradle|gradlew|cmake|ninja|make|d8|gcc|g\\+\\+"))return of("Building code","⚒",0xffffbd86);
  if(tool.contains("search")||tool.contains("grep")||command(b,"rg|grep|find"))return of("Searching","⌕",0xffceb7ff);
  if(tool.contains("image")||b.contains("trough-inbox capture")||b.contains("screenshot"))return of("Images & screen capture","▣",0xffecb7df);
  if(tool.contains("web")||tool.contains("fetch")||command(b,"curl|wget|ssh|scp|rsync"))return of("Network & remote access","◎",0xff9acaff);
  if(command(b,"git|gh"))return of("Version control","⑂",0xfff4b0a3);
  if(tool.contains("read")||tool.contains("view")||command(b,"cat|head|tail|sed"))return of("Reading files","▤",0xffa9d9ea);
  if(command(b,"ls|du|df|stat|mkdir|cp|mv|rm|tar|unzip"))return of("Files & storage","▱",0xffe3d498);
  if(tool.contains("wait")||command(b,"sleep"))return of("Waiting for work","◷",0xffb4c3ce);
  if(b.contains("trough-inbox")||b.contains("trough-memory")||b.contains("ui-runtime"))return of("Trough state & messages","◈",0xff94d9cd);
  if(command(b,"python3|python"))return of("Python automation","λ",0xffb9dba6);
  if(command(b,"ps|top|free|kill")||b.contains("/proc/"))return of("Process diagnostics","≋",0xff9cd9e5);
  if(tool.contains("bash")||tool.contains("shell")||tool.contains("exec"))return of("Shell command",">_",0xffa5cfff);
  return of("Tool operation","◇",0xffbccbd9);
 }
 static TraceCategory describeResult(String raw,boolean error){
  String b=raw.trim().toLowerCase(Locale.ROOT);
  if(error)return of("Error output","!",0xffffa49a);
  if(b.isEmpty())return of("No output","·",0xff9eb5c0);
  if(b.contains("tests passed")||b.contains("\"passed\":true")||b.contains("\"passed\": true"))return of("Validation results","✓",0xffa2e3bb);
  if(b.contains("warning: [options]")||b.contains("compilation")||b.contains("javac"))return of("Build output","⚒",0xffffbd86);
  if(b.contains("\"image\"")||b.contains("\"saved_ms\"")||b.contains("image/png"))return of("Image results","▣",0xffecb7df);
  if(b.startsWith("{")||b.startsWith("["))return of("Structured data","{}",0xff94d9cd);
  if(b.contains("diff --git")||b.startsWith("@@"))return of("Code differences","±",0xfff4b0a3);
  return of("Text output","≡",0xffa9d9ea);
 }
}
