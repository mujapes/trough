package com.trough.hot;
import android.media.*;
import java.io.*;
import java.nio.ByteBuffer;
/** Native container adapter: copies encoded tracks without re-encoding. */
final class MediaJoin {
 static void join(File video,File audio,File output)throws Exception {
  File tmp=new File(output.getPath()+".joining");
  MediaExtractor v=new MediaExtractor(),a=new MediaExtractor();MediaMuxer mux=null;boolean started=false;
  try {
   v.setDataSource(video.getPath());a.setDataSource(audio.getPath());
   int vi=find(v,"video/"),ai=find(a,"audio/");
   mux=new MediaMuxer(tmp.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
   int vo=mux.addTrack(v.getTrackFormat(vi)),ao=mux.addTrack(a.getTrackFormat(ai));
   v.selectTrack(vi);a.selectTrack(ai);mux.start();started=true;
   copy(v,mux,vo);copy(a,mux,ao);mux.stop();started=false;mux.release();mux=null;
   if(!tmp.renameTo(output))throw new IOException("Cannot publish merged media");
  }finally{v.release();a.release();if(mux!=null){if(started)try{mux.stop();}catch(Exception ignored){}mux.release();}tmp.delete();}
 }
 static int find(MediaExtractor e,String prefix)throws IOException{for(int i=0;i<e.getTrackCount();i++)if(e.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith(prefix))return i;throw new IOException("Missing "+prefix+" track");}
 static void copy(MediaExtractor e,MediaMuxer m,int track)throws IOException {
  ByteBuffer buffer=ByteBuffer.allocateDirect(4*1024*1024);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
  while(true){if(Thread.currentThread().isInterrupted())throw new IOException("Cancelled");buffer.clear();int size=e.readSampleData(buffer,0);if(size<0)return;if(size>buffer.capacity())throw new IOException("Sample too large");info.set(0,size,e.getSampleTime(),e.getSampleFlags());m.writeSampleData(track,buffer,info);e.advance();}
 }
}
