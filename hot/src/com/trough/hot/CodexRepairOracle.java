package com.trough.hot;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, Android-free repair oracle. A real code-mode cell delegates five fixed checks to
 * a real Codex app-server's command/exec endpoint. No thread, model turn or approval decision
 * is created. This deliberately reports automatic app-server discovery as unverified: a
 * direct helper launch cannot prove that separate path. The live Chat oracle must prove it.
 */
final class CodexRepairOracle {
    private CodexRepairOracle() {}

    static Map<String,Object> run(String codex, String helper, String shell, File cwd,
                                  Map<String,String> env) throws Exception {
        if (!new File(codex).canExecute() || !new File(helper).canExecute())
            throw new IOException("Codex and its code-mode host must both be executable");
        String marker="trough-codex-repair-"+UUID.randomUUID().toString();
        String leaf="."+marker+".txt";
        String created=marker+"-created", edited=marker+"-edited";
        final String[] scripts={"pwd", "printf '%s\\n' '"+created+"' > '"+leaf+"'",
            "cat '"+leaf+"'", "printf '%s\\n' '"+edited+"' > '"+leaf+"'", "cat '"+leaf+"'"};
        final String[] expected={cwd.getCanonicalPath()+"\n", "", created+"\n", "", edited+"\n"};
        final List<Object> checks=new ArrayList<>();
        try (Peer app=new Peer(new String[]{codex,"app-server"},cwd,env,false);
             Peer host=new Peer(new String[]{helper},cwd,env,true)) {
            app.write(Json.map("jsonrpc","2.0","id",1,"method","initialize","params",Json.map(
                "clientInfo",Json.map("name","trough-repair-oracle","title","Trough repair oracle","version","1"))));
            Map<String,Object> init=app.response(1);
            if (Json.obj(init.get("result"))==null) throw new IOException("Codex initialize failed");
            app.write(Json.map("jsonrpc","2.0","method","initialized"));

            host.write(Json.map("type","connection/hello","supportedVersions",Json.list(1),
                "requiredCapabilities",Json.list(),"optionalCapabilities",Json.list()));
            Map<String,Object> hello=host.next();
            if (!"connection/ready".equals(Json.str(hello,"type"))) throw new IOException("Code-mode handshake failed");
            host.write(Json.map("type","operation/request","id",1,"request",Json.map(
                "method","session/open","sessionId",marker)));
            Map<String,Object> opened=host.response(1);
            if (!"session/ready".equals(Json.str(value(opened),"type"))) throw new IOException("Code-mode session failed");

            Map<String,Object> tool=Json.map("name","repair__check","tool_name",Json.map("namespace","repair","name","check"),
                "description","One fixed local Codex installation check","kind","function",
                "input_schema",Json.map("type","object","properties",Json.map("check",Json.map(
                    "type","integer","minimum",0,"maximum",4)),"required",Json.list("check"),"additionalProperties",false),
                "output_schema",null);
            String source="const checks=[]; for(let check=0;check<5;check++){ const r=await tools.repair__check({check}); "+
                "if(r.exitCode!==0) throw new Error('check '+check+' exited '+r.exitCode); checks.push(r); } text(JSON.stringify(checks));";
            host.write(Json.map("type","operation/request","id",2,"request",Json.map("method","session/execute",
                "sessionId",marker,"request",Json.map("source",source,"tool_call_id",marker,"enabled_tools",Json.list(tool),
                    "yield_time_ms",1000,"max_output_tokens",2048))));
            boolean finished=false;
            Map<String,Object> cell=null;
            int waitId=3;
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(70);
            while (!finished && System.nanoTime()<until) {
                Map<String,Object> message=host.next();
                String type=Json.str(message,"type");
                if ("delegate/request".equals(type)) {
                    Map<String,Object> request=Json.obj(message.get("request"));
                    Map<String,Object> invocation=request==null?null:Json.obj(request.get("invocation"));
                    Map<String,Object> name=invocation==null?null:Json.obj(invocation.get("tool_name"));
                    Map<String,Object> input=invocation==null?null:Json.obj(invocation.get("input"));
                    Object index=input==null?null:input.get("check");
                    int i=index instanceof Number?((Number)index).intValue():-1;
                    if (!marker.equals(Json.str(message,"sessionId")) || !"tool/invoke".equals(Json.str(request,"type")) ||
                        !"repair".equals(Json.str(name,"namespace")) || !"check".equals(Json.str(name,"name")) ||
                        input==null || input.size()!=1 || i!=checks.size() || i<0 || i>=scripts.length)
                        throw new IOException("Unexpected code-mode delegation");
                    int requestId=100+i;
                    app.write(Json.map("jsonrpc","2.0","id",requestId,"method","command/exec","params",Json.map(
                        "command",Json.list(shell,"-c",scripts[i]),"cwd",cwd.getCanonicalPath(),"timeoutMs",10000,
                        "outputBytesCap",4096,"env",env,"sandboxPolicy",Json.map("type","dangerFullAccess"))));
                    Map<String,Object> result=Json.obj(app.response(requestId).get("result"));
                    if (result==null) throw new IOException("Codex command/exec returned no result for check "+i);
                    boolean ok=number(result,"exitCode")==0 && expected[i].equals(Json.str(result,"stdout"));
                    checks.add(Json.map("check",i,"ok",ok,"result",result));
                    host.write(Json.map("type","delegate/response","id",message.get("id"),"result",Json.map(
                        "status","ok","value",Json.map("type","tool/result","result",result))));
                    if (!ok) throw new IOException("Codex command/exec check "+i+" failed: "+Json.write(result));
                } else if ("execute/initialResponse".equals(type) ||
                           "operation/response".equals(type) && number(message,"id")>=3) {
                    Map<String,Object> result=value(message);
                    if("wait/completed".equals(Json.str(result,"type"))) {
                        Map<String,Object> outcome=Json.obj(result.get("outcome"));
                        result=outcome==null?null:Json.obj(outcome.get("LiveCell"));
                    }
                    cell=result==null?null:Json.obj(result.get("Result"));
                    if(cell!=null) {
                        if (cell.get("error_text")!=null) throw new IOException("Code-mode cell failed: "+cell.get("error_text"));
                        finished=true;
                    } else {
                        Map<String,Object> yielded=result==null?null:Json.obj(result.get("Yielded"));
                        String cellId=Json.str(yielded,"cell_id");
                        if(cellId==null) throw new IOException("Unexpected code-mode execution result");
                        host.write(Json.map("type","operation/request","id",waitId++,"request",Json.map(
                            "method","session/wait","sessionId",marker,"request",Json.map(
                                "cell_id",cellId,"yield_time_ms",10000))));
                    }
                }
            }
            if (!finished || checks.size()!=5) throw new IOException("Code-mode checks did not finish");
            List<Object> content=cell==null?null:Json.arr(cell.get("content_items"));
            if(content==null || content.size()!=1) throw new IOException("Code-mode result content missing");
            Map<String,Object> text=Json.obj(content.get(0));
            List<Object> echoed=text==null?null:Json.arr(Json.parse(Json.str(text,"text")));
            if(echoed==null || echoed.size()!=5) throw new IOException("Code-mode delegated results missing");
            for(int i=0;i<5;i++) if(!Json.write(Json.obj(checks.get(i)).get("result")).equals(Json.write(echoed.get(i))))
                throw new IOException("Code-mode delegated output mismatch at "+i);
            return Json.map("ok",true,"codex",codex,"helper",helper,"helper_handshake",true,
                "helper_cell",true,"delegated_codex_commands",true,"checks",checks,
                "test_file",new File(cwd,leaf).getCanonicalPath(),"edited_contents",edited+"\n",
                "code_mode_duration_ns",cell.get("code_mode_host_duration_ns"),
                "automatic_helper_discovery_verified",false,"live_model_turn_started",false,
                "conversation_modified",false,"approval_decisions",0);
        }
    }

    private static long number(Map<String,Object> m,String key) {
        Object n=m==null?null:m.get(key); return n instanceof Number?((Number)n).longValue():Long.MIN_VALUE;
    }
    private static Map<String,Object> value(Map<String,Object> message) throws IOException {
        Map<String,Object> result=Json.obj(message.get("result"));
        if(!"ok".equals(Json.str(result,"status"))) throw new IOException("Code-mode operation failed: "+Json.write(result));
        return Json.obj(result.get("value"));
    }

    private static final class Peer implements AutoCloseable {
        private final Process process;
        private final OutputStream stdin;
        private final boolean framed;
        private final LinkedBlockingQueue<Object> messages=new LinkedBlockingQueue<>(32);
        private final StringBuilder errors=new StringBuilder();
        Peer(String[] command,File cwd,Map<String,String> env,boolean framed) throws IOException {
            this.framed=framed;
            ProcessBuilder pb=new ProcessBuilder(command).directory(cwd);
            pb.environment().clear(); pb.environment().putAll(env);
            process=pb.start(); stdin=process.getOutputStream();
            Thread stdout=new Thread(new Runnable() { public void run() {
                try {
                    InputStream stream=process.getInputStream();
                    if(Peer.this.framed) while(true) {
                        byte[] length=read(stream,4);
                        int size=(length[0]&255)|((length[1]&255)<<8)|((length[2]&255)<<16)|((length[3]&255)<<24);
                        if(size<1 || size>131072) throw new IOException("Code-mode frame bounds");
                        deliver(Json.obj(Json.parse(new String(read(stream,size),StandardCharsets.UTF_8))));
                    } else {
                        BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8));
                        for(String line;(line=reader.readLine())!=null;) {
                            if(!line.isEmpty()) deliver(Json.obj(Json.parse(line)));
                        }
                        throw new EOFException("Codex app-server closed its output");
                    }
                } catch(Exception e) { messages.offer(e); }
            }},"trough-codex-repair-out"); stdout.setDaemon(true); stdout.start();
            Thread stderr=new Thread(new Runnable() { public void run() {
                try {
                    BufferedReader reader=new BufferedReader(new InputStreamReader(process.getErrorStream(),StandardCharsets.UTF_8));
                    for(String line;(line=reader.readLine())!=null;) synchronized(errors) {
                        errors.append(line).append('\n'); if(errors.length()>2000) errors.delete(0,errors.length()-2000);
                    }
                } catch(IOException ignored) {}
            }},"trough-codex-repair-err"); stderr.setDaemon(true); stderr.start();
        }
        private void deliver(Map<String,Object> value) throws IOException {
            if(value==null || !messages.offer(value)) throw new IOException("Codex repair response capacity");
        }
        void write(Map<String,Object> message) throws IOException {
            byte[] data=Json.write(message).getBytes(StandardCharsets.UTF_8);
            if(framed) stdin.write(new byte[]{(byte)data.length,(byte)(data.length>>>8),(byte)(data.length>>>16),(byte)(data.length>>>24)});
            stdin.write(data); if(!framed) stdin.write('\n'); stdin.flush();
        }
        Map<String,Object> next() throws Exception {
            Object message=messages.poll(20,TimeUnit.SECONDS);
            if(message==null) throw new IOException("Codex repair response timeout");
            if(message instanceof Exception) {
                String tail; synchronized(errors) { tail=errors.toString(); }
                throw new IOException("Codex repair child failed: "+((Exception)message).getMessage()+" "+tail);
            }
            return Json.obj(message);
        }
        Map<String,Object> response(int id) throws Exception {
            for(int count=0;count<128;count++) {
                Map<String,Object> message=next();
                if(number(message,"id")==id && !message.containsKey("method")) return message;
                if(message.containsKey("id") && message.containsKey("method"))
                    throw new IOException("Unexpected approval or server request; no decision sent");
            }
            throw new IOException("Codex repair response limit");
        }
        public void close() {
            try { stdin.close(); } catch(IOException ignored) {}
            process.destroy();
            try { if(!process.waitFor(1,TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch(InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
        private static byte[] read(InputStream stream,int size) throws IOException {
            byte[] bytes=new byte[size];
            for(int at=0;at<size;) { int n=stream.read(bytes,at,size-at); if(n<0) throw new EOFException("Code-mode host closed its output"); at+=n; }
            return bytes;
        }
    }
}
