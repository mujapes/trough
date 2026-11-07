package com.trough.hot;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** App-private, atomic transcript snapshots. Disk work never runs on Android's UI thread. */
final class TranscriptStore implements AutoCloseable {
    interface Loaded { void loaded(List<String[]> rows); }
    private final File home, file;
    private final TranscriptSegments segments;
    private final String leaseKey;private final long lease;
    private final Executor dispatch;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private List<String[]> next;
    private String nextSession;
    private boolean writing,closed;
    volatile String error="";
    TranscriptStore(File home,Executor dispatch) {
        this.home=home;this.dispatch=dispatch;segments=new TranscriptSegments(home);leaseKey="trough.transcript.owner."+home.getAbsolutePath();synchronized(System.getProperties()){String key=leaseKey+".sequence";lease=Long.parseLong(System.getProperty(key,"0"))+1;System.setProperty(key,Long.toString(lease));}file=new File(home,".trough/chat-transcript.json");
    }
    void load(final String session,final Loaded callback) {
        io.execute(new Runnable(){public void run(){
            List<String[]> result;
            try { result=segments.read(session); }
            catch(Exception bad) { result=null;error=bad.toString(); }
            if(result==null) try { result=readSnapshot(file,session); }
            catch(Exception bad) { error=bad.toString(); }
            if(result==null) try { result=readSnapshot(new File(file+".previous"),session); } catch(Exception ignored) {}
            try {
                if(result==null)result=recoverRollout(home,session);
                else mergeRollout(home,session,result);
            }catch(Exception bad){error=bad.toString();}
            final List<String[]> rows=result==null?new ArrayList<String[]>():result;
            dispatch.execute(new Runnable(){public void run(){callback.loaded(rows);}});
        }});
    }
    synchronized void save(String session,List<String[]> rows) {
        if(closed || session==null || session.isEmpty()) return;
        ArrayList<String[]> copy=new ArrayList<String[]>(rows.size());
        for(String[] row:rows) copy.add(row.clone());
        next=copy;nextSession=session;
        if(writing)return;
        writing=true;
        io.execute(new Runnable(){public void run(){drain();}});
    }
    private void drain() {
        while(true) {
            List<String[]> rows;String session;
            synchronized(this) {
                if(next==null){writing=false;return;}
                rows=next;session=nextSession;next=null;
            }
            try { if(segments.write(session,rows,new TranscriptSegments.Active(){public boolean active(){return claimWriter();}}))error=""; }
            catch(Exception failure) {error=failure.toString();}
        }
    }
    static void writeSnapshot(File file,String session,List<String[]> rows) throws Exception {
        file.getParentFile().mkdirs();File tmp=new File(file+".new");
        try(FileOutputStream out=new FileOutputStream(tmp)) {
            final OutputStream target=out;
            OutputStream bounded=new OutputStream(){long count;
                public void write(int value)throws IOException{if(count>=64L*1024*1024)throw new IOException("Transcript exceeds snapshot capacity; prior snapshot retained");target.write(value);count++;}
                public void write(byte[] data,int offset,int length)throws IOException{if(length>64L*1024*1024-count)throw new IOException("Transcript exceeds snapshot capacity; prior snapshot retained");target.write(data,offset,length);count+=length;}
            };
            Writer writer=new BufferedWriter(new OutputStreamWriter(bounded,"UTF-8"),16384);
            writer.write("{\"version\":1,\"session_id\":");quote(writer,session);writer.write(",\"entries\":[");
            boolean comma=false;
            for(String[] row:rows){if(comma)writer.write(',');comma=true;writer.write('[');quote(writer,row[0]);writer.write(',');quote(writer,row[1]);writer.write(']');}
            writer.write("]}");writer.flush();out.getFD().sync();
        } catch(Exception failure){tmp.delete();throw failure;}
        // Keep a recovery copy without removing the current committed snapshot before publication.
        if(file.isFile()) {
            File backup=new File(file+".previous.new");
            try(InputStream in=new FileInputStream(file);FileOutputStream out=new FileOutputStream(backup)) {
                byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);out.getFD().sync();
            }
            if(!backup.renameTo(new File(file+".previous")))throw new IOException("Transcript backup rename failed");
        }
        if(!tmp.renameTo(file))throw new IOException("Transcript publication failed");
        try(java.nio.channels.FileChannel dir=java.nio.channels.FileChannel.open(file.getParentFile().toPath(),java.nio.file.StandardOpenOption.READ)){dir.force(true);}
    }
    private static void quote(Writer out,String text)throws IOException {
        out.write('"');int start=0;
        for(int k=0;k<text.length();k++){
            char c=text.charAt(k);if(c!='"'&&c!='\\'&&c>=32&&(c<0xd800||c>0xdfff))continue;
            if(k>start)out.write(text,start,k-start);
            if(c=='"')out.write("\\\"");else if(c=='\\')out.write("\\\\");
            else if(c=='\n')out.write("\\n");else if(c=='\r')out.write("\\r");else if(c=='\t')out.write("\\t");
            else {out.write("\\u");for(int shift=12;shift>=0;shift-=4)out.write("0123456789abcdef".charAt((c>>shift)&15));}
            start=k+1;
        }
        if(start<text.length())out.write(text,start,text.length()-start);out.write('"');
    }
    /** Pair equality without copying potentially large message text into a new key. */
    static List<String> rowKey(String[] row){return Arrays.asList("work_assistant".equals(row[0])?"assistant":row[0],row[1]);}
    static List<String[]> readSnapshot(File file,String session) throws Exception {
        if(!file.isFile())return null;
        if(file.length()>64*1024*1024)throw new IOException("Transcript size");
        ArrayList<String[]> rows=new ArrayList<String[]>();String savedSession=null;boolean entries=false;
        try(PushbackReader in=new PushbackReader(new BufferedReader(new InputStreamReader(new FileInputStream(file),"UTF-8")),1)){
            expect(in,'{');int next=nonspace(in);
            while(next!='}'){
                in.unread(next);Object name=Json.parse(value(in));expect(in,':');
                if("entries".equals(name)){
                    if(entries)throw new IOException("Duplicate transcript entries");entries=true;expect(in,'[');int item=nonspace(in);
                    while(item!=']'){
                        in.unread(item);List<Object> pair=Json.arr(Json.parse(value(in)));
                        if(pair==null||pair.size()!=2||!(pair.get(0) instanceof String)||!(pair.get(1) instanceof String))throw new IOException("Transcript row");
                        rows.add(new String[]{(String)pair.get(0),(String)pair.get(1)});
                        item=nonspace(in);if(item==','){item=nonspace(in);if(item==']')throw new IOException("Transcript trailing comma");}else if(item!=']')throw new IOException("Transcript separator");
                    }
                }else {Object field=Json.parse(value(in));if("session_id".equals(name))savedSession=field instanceof String?(String)field:null;}
                next=nonspace(in);if(next==','){next=nonspace(in);if(next=='}')throw new IOException("Transcript trailing comma");}else if(next!='}')throw new IOException("Transcript separator");
            }
            if(nonspace(in)!=-1)throw new IOException("Transcript trailing content");
        }
        if(!session.equals(savedSession))return null;
        if(!entries)throw new IOException("Transcript entries");return rows;
    }
    private static int nonspace(PushbackReader in)throws IOException{int c;do{c=in.read();}while(c==' '||c=='\n'||c=='\r'||c=='\t');return c;}
    private static void expect(PushbackReader in,int expected)throws IOException{if(nonspace(in)!=expected)throw new IOException("Transcript syntax");}
    /** Read only one field/row. Quoted brackets and escaped quotes are data. */
    private static String value(PushbackReader in)throws IOException{
        StringBuilder out=new StringBuilder();int c=nonspace(in),depth=0;boolean quoted=false,escape=false;
        if(c<0)throw new EOFException("Transcript value");
        while(c!=-1){
            if(!quoted&&depth==0&&(c==','||c==']'||c=='}')){in.unread(c);break;}
            out.append((char)c);
            if(quoted){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quoted=false;}
            else if(c=='"')quoted=true;else if(c=='['||c=='{')depth++;else if(c==']'||c=='}')depth--;
            if(!quoted&&depth==0&&(c=='"'||c==']'||c=='}'))break;
            c=in.read();
        }
        if(quoted||depth!=0||out.length()==0)throw new IOException("Transcript incomplete value");return out.toString();
    }

    static List<String[]> recoverRollout(File home,String session) throws Exception {
        ArrayList<String[]> rows=new ArrayList<String[]>();readRollout(home,session,rows,null);return rows;
    }
    static void mergeRollout(File home,String session,List<String[]> rows)throws Exception{
        HashSet<List<String>> known=new HashSet<List<String>>();for(String[] row:rows)known.add(rowKey(row));
        readRollout(home,session,rows,known);
    }
    private static void readRollout(File home,String session,List<String[]> rows,Set<List<String>> known)throws Exception{
        if(session==null || !session.matches("[a-fA-F0-9-]{32,64}"))return;
        File rollout=find(new File(home,".codex/sessions"),session,4);
        if(rollout==null)return;
        try(FileInputStream raw=new FileInputStream(rollout)) {
            boolean tail=known!=null && rollout.length()>1024*1024;
            if(tail)raw.getChannel().position(rollout.length()-1024*1024);
            BufferedReader in=new BufferedReader(new InputStreamReader(raw,"UTF-8"));
            if(tail)in.readLine();
            for(String line;(line=in.readLine())!=null;)try {
                Map<String,Object> record=Json.obj(Json.parse(line));
                if(!"response_item".equals(Json.str(record,"type")))continue;
                Map<String,Object> message=Json.obj(record.get("payload"));
                if(!"message".equals(Json.str(message,"type")))continue;
                String role=Json.str(message,"role");if(!"assistant".equals(role)&&!"user".equals(role))continue;
                List<Object> content=Json.arr(message.get("content"));if(content==null)continue;
                StringBuilder text=new StringBuilder();
                for(Object part:content){String t=Json.str(Json.obj(part),"text");if(t!=null){if(text.length()>0)text.append('\n');text.append(t);}}
                if(text.length()>0 && !("user".equals(role) && HistoryPreview.internalPrompt(text.toString()))){String[] row={role,text.toString()};if(known==null||known.add(rowKey(row)))rows.add(row);}
            }catch(RuntimeException partial){ /* A killed process may leave an incomplete final line. */ }
        }
    }
    private static File find(File dir,String session,int depth) {
        if(depth<0)return null;File[] files=dir.listFiles();if(files==null)return null;
        for(File f:files)if(f.isFile()&&f.getName().endsWith("-"+session+".jsonl"))return f;
        for(File f:files)if(f.isDirectory()){File found=find(f,session,depth-1);if(found!=null)return found;}
        return null;
    }
    boolean claimWriter(){synchronized(System.getProperties()){long owner=Long.parseLong(System.getProperty(leaseKey,"0"));if(owner>lease)return false;if(owner<lease)System.setProperty(leaseKey,Long.toString(lease));return true;}}
    @Override public synchronized void close(){closed=true;io.shutdown();}
}
