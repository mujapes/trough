package com.trough.hot;
import java.io.*;import java.nio.file.*;
/** Short, reversible service-loader selection. Never edits the original boot bundle. */
final class GameSlotLease {
 final File pointer;final String original,candidate;boolean restored;
 private GameSlotLease(File p,String o,String c){pointer=p;original=o;candidate=c;}
 static String read(File f)throws IOException{return new String(Files.readAllBytes(f.toPath()),"UTF-8").trim();}
 static void write(File f,String text)throws IOException{File tmp=new File(f.getParentFile(),f.getName()+".slot-tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write((text+"\n").getBytes("UTF-8"));out.getFD().sync();}Files.move(tmp.toPath(),f.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
 static GameSlotLease begin(File files,File request)throws IOException{
  if(!request.isFile())return null;
  String candidate=read(request);if(!candidate.matches("local-slot-[0-9a-f]{20}"))throw new IOException("Invalid local candidate");
  File hot=new File(files,"hot"),pointer=new File(hot,"good"),dir=new File(hot,candidate);
  if(!new File(dir,"classes.dex").isFile()||!new File(dir,"manifest.json").isFile())throw new IOException("Incomplete local candidate");
  String original=read(pointer);if(original.startsWith("local-slot-"))throw new IOException("Another slot lease is active");
  GameSlotLease lease=new GameSlotLease(pointer,original,candidate);
  write(new File(request.getParentFile(),"slot-restore-original"),original);
  write(new File(request.getParentFile(),"last-slot-candidate"),candidate);
  if(!request.delete())throw new IOException("Cannot consume slot request");
  write(pointer,candidate);return lease;
 }
 synchronized void restore()throws IOException{if(restored)return;if(read(pointer).equals(candidate))write(pointer,original);restored=true;}
}
