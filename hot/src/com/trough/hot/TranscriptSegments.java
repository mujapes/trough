package com.trough.hot;
import java.io.*;import java.nio.channels.*;import java.security.*;import java.util.*;
/** Immutable bounded parts plus atomic current/previous manifests. Legacy snapshot is retained. */
final class TranscriptSegments {
 interface Active {boolean active();}
 private final File index,dir;private List<String[]> previous=Collections.emptyList();private List<Part> cached=Collections.emptyList();private String session="";private boolean recoveredPrimary;private final Map<String,long[]> verified=new HashMap<String,long[]>();
 private static final int PART_ROWS=128,PART_CHARS=2*1024*1024,ROW_CHARS=8*1024*1024;
 private static final class Part {final String name;final int start,end;Part(String n,int s,int e){name=n;start=s;end=e;}}
 TranscriptSegments(File home){index=new File(home,".trough/chat-transcript.index.json");dir=new File(home,".trough/chat-transcript.parts");}
 private FileLock lock(FileChannel channel)throws Exception{long end=System.nanoTime()+30000000000L;while(true){try{FileLock lock=channel.tryLock();if(lock!=null)return lock;}catch(OverlappingFileLockException busy){}if(System.nanoTime()>end)throw new IOException("Transcript lock timeout");Thread.sleep(10);}}
 List<String[]> read(String id)throws Exception{
  if(!index.isFile())return null;dir.mkdirs();try(RandomAccessFile f=new RandomAccessFile(new File(dir,".lock"),"rw");FileChannel c=f.getChannel();FileLock held=lock(c)){
   try{List<String[]> result=readIndex(index,id);recoveredPrimary=false;return result;}catch(Exception original){recoveredPrimary=true;cached=Collections.emptyList();previous=Collections.emptyList();verified.clear();File backup=new File(index+".previous");if(!backup.isFile())throw original;try{List<String[]> recovered=readIndex(backup,id);if(recovered==null)throw original;return recovered;}catch(Exception failed){throw original;}}
  }
 }
 private Map<String,Object> manifest(File path)throws Exception{if(!path.isFile()||path.length()>1024*1024)throw new IOException("Transcript manifest size/missing");byte[] bytes=new byte[(int)path.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(path))){in.readFully(bytes);}Map<String,Object> m=Json.obj(Json.parse(new String(bytes,"UTF-8")));if(m==null||!(m.get("version") instanceof Number)||((Number)m.get("version")).intValue()!=2)throw new IOException("Transcript manifest version");return m;}
 private List<String[]> readIndex(File path,String id)throws Exception{
  Map<String,Object> m=manifest(path);if(!id.equals(Json.str(m,"session_id")))return null;List<Object> parts=Json.arr(m.get("parts"));if(parts==null||parts.size()>4096)throw new IOException("Transcript part count");ArrayList<String[]> rows=new ArrayList<String[]>();
  for(Object item:parts){Map<String,Object> p=Json.obj(item);String name=Json.str(p,"name");if(name==null||!name.matches("part-[a-f0-9]{64}\\.json"))throw new IOException("Transcript part name");List<String[]> loaded=TranscriptStore.readSnapshot(new File(dir,name),id);if(loaded==null||!(p.get("rows") instanceof Number)||loaded.size()!=((Number)p.get("rows")).intValue()||!name.equals("part-"+digest(id,loaded,0,loaded.size())+".json"))throw new IOException("Transcript part integrity");rows.addAll(loaded);}
  if(!(m.get("rows") instanceof Number)||rows.size()!=((Number)m.get("rows")).intValue())throw new IOException("Transcript row count");return rows;
 }
 boolean write(String id,List<String[]> rows,Active active)throws Exception{
  dir.mkdirs();try(RandomAccessFile f=new RandomAccessFile(new File(dir,".lock"),"rw");FileChannel c=f.getChannel();FileLock held=lock(c)){
   if(!active.active())return false;int common=0;if(id.equals(session))while(common<previous.size()&&common<rows.size()&&Arrays.equals(previous.get(common),rows.get(common)))common++;
   ArrayList<Part> parts=new ArrayList<Part>();int start=0;
   if(id.equals(session))for(Part p:cached){if(p.start!=start||p.end>common||!verified(p.name))break;parts.add(p);start=p.end;}
   while(start<rows.size()){
    int end=start,chars=0;while(end<rows.size()&&end-start<PART_ROWS){String[] row=rows.get(end);if(row==null||row.length!=2||row[0]==null||row[1]==null)throw new IOException("Transcript row invalid");long length=(long)row[0].length()+row[1].length();if(length>ROW_CHARS)throw new IOException("Transcript row exceeds bounded part capacity");if(end>start&&chars+length>PART_CHARS)break;chars+=(int)length;end++;}
    String name="part-"+digest(id,rows,start,end)+".json";ensurePart(name,id,rows.subList(start,end));parts.add(new Part(name,start,end));if(parts.size()>4096)throw new IOException("Transcript manifest capacity");start=end;
   }
   ArrayList<Object> descriptions=new ArrayList<Object>();for(Part p:parts)descriptions.add(Json.map("name",p.name,"rows",p.end-p.start));byte[] data=Json.write(Json.map("version",2,"session_id",id,"rows",rows.size(),"parts",descriptions)).getBytes("UTF-8");if(data.length>1024*1024)throw new IOException("Transcript manifest capacity");
   File tmp=new File(index+".new");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(data);out.getFD().sync();}
   if(!active.active()){tmp.delete();return false;}
   if(index.isFile()&&!recoveredPrimary){try{manifest(index);}catch(Exception invalid){recoveredPrimary=true;}}
   if(index.isFile()&&!recoveredPrimary){File backup=new File(index+".previous.new");copy(index,backup);if(!backup.renameTo(new File(index+".previous")))throw new IOException("Transcript manifest backup rename");}
   if(!tmp.renameTo(index))throw new IOException("Transcript manifest publication");try(FileChannel parent=FileChannel.open(dir.getParentFile().toPath(),java.nio.file.StandardOpenOption.READ)){parent.force(true);}
   previous=rows;cached=parts;session=id;recoveredPrimary=false;collect();return true;
  }
 }
 private boolean verified(String name){File f=new File(dir,name);long[] stamp=verified.get(name);return stamp!=null&&f.isFile()&&stamp[0]==f.length()&&stamp[1]==f.lastModified();}
 private void ensurePart(String name,String id,List<String[]> rows)throws Exception{
  if(verified(name))return;File part=new File(dir,name);if(part.exists()&&!part.isFile())throw new IOException("Transcript part is not a file");
  if(!part.exists())TranscriptStore.writeSnapshot(part,id,rows);
  else {File check=new File(dir,name+".check-"+UUID.randomUUID().toString());try{TranscriptStore.writeSnapshot(check,id,rows);if(!Arrays.equals(fileDigest(check),fileDigest(part))){if(!check.renameTo(part))throw new IOException("Transcript part repair rename");try(FileChannel parent=FileChannel.open(dir.toPath(),java.nio.file.StandardOpenOption.READ)){parent.force(true);}}}finally{check.delete();new File(check+".previous").delete();}}
  verified.put(name,new long[]{part.length(),part.lastModified()});
 }
 private static byte[] fileDigest(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[16384];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}return digest.digest();}
 private void collect()throws Exception{
  HashSet<String> keep=new HashSet<String>();for(File file:new File[]{index,new File(index+".previous")})if(file.isFile()){Map<String,Object> m=manifest(file);List<Object> parts=Json.arr(m.get("parts"));if(parts==null)return;for(Object item:parts){String name=Json.str(Json.obj(item),"name");if(name==null||!name.matches("part-[a-f0-9]{64}\\.json"))return;keep.add(name);}}
  File[] files=dir.listFiles();if(files!=null)for(File f:files)if(f.getName().matches("part-[a-f0-9]{64}\\.json")&&!keep.contains(f.getName())&&!f.delete())throw new IOException("Transcript orphan cleanup failed");
 }
 private static void copy(File from,File to)throws Exception{try(InputStream in=new FileInputStream(from);FileOutputStream out=new FileOutputStream(to)){byte[] b=new byte[16384];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);out.getFD().sync();}}
 private static String digest(String session,List<String[]> rows,int start,int end)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] scratch=new byte[4096];hashText(d,session,scratch);for(int i=start;i<end;i++){hashText(d,rows.get(i)[0],scratch);hashText(d,rows.get(i)[1],scratch);}StringBuilder hex=new StringBuilder(64);for(byte b:d.digest()){hex.append("0123456789abcdef".charAt((b>>>4)&15));hex.append("0123456789abcdef".charAt(b&15));}return hex.toString();}
 private static void hashText(MessageDigest d,String text,byte[] b){int length=text.length();for(int shift=24;shift>=0;shift-=8)d.update((byte)(length>>>shift));int used=0;for(int i=0;i<length;i++){char c=text.charAt(i);b[used++]=(byte)(c>>>8);b[used++]=(byte)c;if(used==b.length){d.update(b,0,used);used=0;}}if(used>0)d.update(b,0,used);}
}
