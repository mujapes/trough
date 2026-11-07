package com.trough.hot;
import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Native Java 8 section reader. Open one immutable content-hash pack per generation.
 * Directory parsing does not inflate payloads. No dependency on Android/UI threads.
 */
public final class HalfSwordPack implements Closeable {
 private final RandomAccessFile file;
 private final Map<String,Entry> entries=new LinkedHashMap<String,Entry>();
 private static final int MAX_SECTION=8*1024*1024;
 private static final class Entry {
  long offset; int encoded,decoded,codec; byte[] hash;
 }
 public HalfSwordPack(File path) throws IOException {
  file=new RandomAccessFile(path,"r");
  try {
   long size=file.length();
   if(size<16 || size>32L*1024*1024)throw new IOException("Pack size");
   byte[] header=new byte[16];file.readFully(header);
   ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
   byte[] magic=new byte[8];h.get(magic);
   if(!Arrays.equals(magic,new byte[]{72,83,55,80,65,75,48,49}) || h.getInt()!=1)throw new IOException("Pack header");
   int count=h.getInt();if(count<1 || count>32 || size<16L+76L*count)throw new IOException("Directory size");
   for(int i=0;i<count;i++) {
    byte[] raw=new byte[76];file.readFully(raw);
    ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    byte[] key=new byte[16];b.get(key);int n=0;
    while(n<16 && key[n]!=0){if(key[n]<0)throw new IOException("Section name");n++;}
    if(n==0 || n>15)throw new IOException("Section name");
    for(int j=n;j<16;j++)if(key[j]!=0)throw new IOException("Section padding");
    String name=new String(key,0,n,"US-ASCII");Entry e=new Entry();
    e.offset=b.getLong();long encoded=b.getLong(),decoded=b.getLong();e.codec=b.getInt();e.hash=new byte[32];b.get(e.hash);
    if(e.offset<16L+76L*count || e.offset%8!=0 || encoded<0 || encoded>size || e.offset>size-encoded || decoded<0 || decoded>MAX_SECTION || (e.codec!=0 && e.codec!=1) || (e.codec==0 && encoded!=decoded))throw new IOException("Section bounds");
    e.encoded=(int)encoded;e.decoded=(int)decoded;
    if(entries.put(name,e)!=null)throw new IOException("Duplicate section");
   }
   List<Entry> sorted=new ArrayList<Entry>(entries.values());
   Collections.sort(sorted,new Comparator<Entry>(){public int compare(Entry a,Entry b){return Long.compare(a.offset,b.offset);}});
   long end=0;for(Entry e:sorted){if(e.offset<end)throw new IOException("Overlapping sections");end=e.offset+e.encoded;}
  }catch(IOException error){file.close();throw error;}catch(RuntimeException error){file.close();throw error;}
 }
 public Set<String> names(){return Collections.unmodifiableSet(entries.keySet());}
 public synchronized byte[] read(String name) throws IOException {
  Entry e=entries.get(name);if(e==null)throw new IOException("Unknown section: "+name);
  byte[] encoded=new byte[e.encoded];file.seek(e.offset);file.readFully(encoded);
  byte[] decoded;
  if(e.codec==0)decoded=encoded;
  else {
   Inflater inflater=new Inflater();
   try {
    inflater.setInput(encoded);decoded=new byte[e.decoded];int used=0;
    while(used<decoded.length && !inflater.finished()) {
     int n=inflater.inflate(decoded,used,decoded.length-used);
     if(n==0)throw new IOException("Incomplete compressed section");used+=n;
    }
    // Consume stream terminator, and detect a stream producing more than its declared size.
    byte[] excess=new byte[1];int more=inflater.inflate(excess);
    if(used!=decoded.length || more!=0 || !inflater.finished() || inflater.getRemaining()!=0)throw new IOException("Compressed section bounds");
   }catch(DataFormatException error){throw new IOException("Corrupt compressed section",error);}finally{inflater.end();}
  }
  try {if(!MessageDigest.isEqual(e.hash,MessageDigest.getInstance("SHA-256").digest(decoded)))throw new IOException("Section digest");}
  catch(NoSuchAlgorithmException error){throw new IOException(error);}
  return decoded;
 }
 public synchronized void close() throws IOException{file.close();}
}
