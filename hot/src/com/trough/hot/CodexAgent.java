package com.trough.hot;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Codex as a Trough agent: `codex app-server`, JSON-RPC 2.0 as JSON lines over its pipes. Same
 * shape as ClaudeAgent: one child process, nothing in between.
 *
 * Protocol (codex-cli 0.162.1, traced under the simulated phone filter 10-27):
 *   -> initialize {clientInfo}  <- result;  -> initialized (notification)
 *   -> thread/start {cwd, approvalPolicy, sandbox, model} | thread/resume {threadId}
 *        <- result {thread:{id}, model}
 *   -> turn/start {threadId, input:[{type:text,text}]}   -> turn/interrupt {threadId, turnId}
 *   <- turn/started, item/started|completed {item:{type: agentMessage|commandExecution|
 *      fileChange|mcpToolCall|webSearch|..}}, item/agentMessage/delta {delta}, turn/completed
 *   <- (server requests) item/commandExecution/requestApproval, item/fileChange/requestApproval
 *        -> result {decision: accept | decline}
 * Sign-in: account/read; if signed out, account/login/start {type: chatgptDeviceCode} -> the
 * Listener shows verificationUrl + userCode, and account/login/completed starts the thread. So
 * the phone gets its own Codex login, never a copy of another machine's rotating tokens.
 * approvalPolicy "never" was selected explicitly by the user (2026-10-31).
 * Every emitted ask goes to the Listener. Server requests Trough does not implement get a JSON-RPC
 * error, so codex never hangs waiting on Trough.
 */
public final class CodexAgent implements Agent {
    static final int TRANSPORT_REVISION=15;
    private final TransportHealth health=new TransportHealth();
    private String lastItemType="",lastProtocolError="";
    private final Process proc;
    private final OutputStream in;
    private final Agent.Listener listener;
    private final File cwd;
    private final String sandbox;
    // The pair is guarded by stateLock and is copied together before each dispatch.
    private String model, reasoningEffort;
    private String lastDispatchedModel = "", lastDispatchedEffort = "";
    private volatile String actualModel = "", actualEffort = "", actualSelectionSource = "";
    private String resume;
    private final StringBuilder stderr = new StringBuilder();
    private final Map<Integer, String> calls = new HashMap<>();          // our request id -> method
    private final Map<Integer, SteerSend> steers = new HashMap<>();       // guarded by calls; bounded below
    private final Map<Integer, SteerSend> starts = new HashMap<>();       // initial sends, including before thread init
    private static final long STEER_ACK_TIMEOUT_MS = 10000;
    private static final long START_ACK_TIMEOUT_MS = 20000;
    private java.util.Timer steerTimer;                                  // guarded by calls; retired with transport
    private final Map<String, Object> asks = new HashMap<>();             // our ask id -> codex request id
    private final List<SteerSend> queued = new ArrayList<>();             // sends before the thread exists
    private final Object stateLock = new Object();
    private boolean pendingStart, stopping, interruptSent, closed, exitReported;
    private String lastEvent = "starting", lastError = "", turnFailureDetail = "";
    private long lastEventNanos = System.nanoTime();
    private int counter;
    private long ignoredForeignEvents;
    private long compactionStartedNanos,lastCompactionDurationMs;
    private int compactionCount;
    private volatile String threadId, turnId;

    private static final class SteerSend {
        final int id;
        final String text;
        java.util.TimerTask timeout;
        boolean dispatched, wireStarted, finished, notified;
        String failure;
        SteerSend(int id,String text) { this.id=id; this.text=text; }
    }

    /**
     * sandbox: codex's own command sandbox ("read-only", "workspace-write") needs user
     * namespaces (bubblewrap), which the phone's kernel lacks (DESIGN.md "Namespace
     * investigation"); there Trough passes "danger-full-access" and the Android app sandbox
     * plus Trough's approval cards are the boundary, as for every agent Trough runs.
     */
    public CodexAgent(String codex, File cwd, Map<String, String> env, String resumeThread, String model,
                      String sandbox, Agent.Listener listener) throws Exception {
        this(codex,cwd,env,resumeThread,model,null,sandbox,listener);
    }

    public CodexAgent(String codex, File cwd, Map<String,String> env, String resumeThread, String model,
                      String reasoningEffort, String sandbox, Agent.Listener listener) throws Exception {
        this.listener = listener;
        this.cwd = cwd;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
        this.sandbox = sandbox;
        this.resume = resumeThread;
        ProcessBuilder pb = new ProcessBuilder(codex, "app-server").directory(cwd);
        if (env != null) pb.environment().putAll(env);
        File vendor = new File(codex).getParentFile().getParentFile();
        File libs = new File(vendor,"codex-resources/lib");
        if(libs.isDirectory()) {
            String old=pb.environment().get("LD_LIBRARY_PATH");
            pb.environment().put("LD_LIBRARY_PATH",libs.getPath()+(old==null||old.isEmpty()?"":":"+old));
        }
        File commands = new File(vendor,"codex-path");
        if(commands.isDirectory()) pb.environment().put("PATH",commands.getPath()+":"+pb.environment().get("PATH"));
        proc = pb.start();
        in = proc.getOutputStream();
        thread("trough-codex-out", new Runnable() { @Override public void run() { readOut(); } });
        thread("trough-codex-err", new Runnable() { @Override public void run() { readErr(); } });
        call("initialize", Json.map("clientInfo", Json.map("name", "trough", "title", "Trough", "version", "1")));
    }

    /** Changes the next dispatch only. A running turn keeps its original model and effort. */
    @Override public boolean configureSelection(String selectedModel, String selectedEffort) {
        String nextModel=selectedModel==null?"":selectedModel.trim();
        String nextEffort=selectedEffort==null?"":selectedEffort.trim();
        if(nextModel.length()>200 || nextModel.indexOf('\n')>=0 || nextModel.indexOf('\r')>=0)
            throw new IllegalArgumentException("Invalid Codex model selection");
        if(!nextEffort.isEmpty() && !"none".equals(nextEffort) && !"minimal".equals(nextEffort)
                && !"low".equals(nextEffort) && !"medium".equals(nextEffort) && !"high".equals(nextEffort)
                && !"xhigh".equals(nextEffort) && !"max".equals(nextEffort) && !"ultra".equals(nextEffort))
            throw new IllegalArgumentException("Invalid Codex reasoning effort");
        synchronized(stateLock) {
            if(closed) return false;
            model=nextModel; reasoningEffort=nextEffort;
            return true;
        }
    }

    @Override public void send(String text) {
        if(text==null || text.trim().isEmpty()) throw new IllegalArgumentException("Message is empty");
        final SteerSend send;
        synchronized (stateLock) {
            if(closed) throw new IllegalStateException("Codex connection is closed");
            if(pendingStart || turnId!=null || !queued.isEmpty())
                throw new IllegalStateException("Codex already has a pending turn");
            synchronized(calls) {
                if(!steers.isEmpty() || !starts.isEmpty()) throw new IllegalStateException("Codex is still confirming a previous message; your draft has been kept");
                send=new SteerSend(++counter,text); starts.put(send.id,send);
            }
            if (threadId == null) {
                // Ownership transfers when send() returns even though no RPC can be written yet.
                synchronized(send) { send.dispatched=true; }
                queued.add(send); return;
            }
            pendingStart=true; stopping=false; interruptSent=false; turnFailureDetail=""; health.started();
            event("turn/start/request");
        }
        try { startTurn(send); }
        catch(RuntimeException failure) {
            boolean wired; synchronized(send) { wired=send.wireStarted; }
            if(!wired) {
                synchronized(calls) { calls.remove(send.id); }
                removeSteer(send);
                transportFailed("Codex could not dispatch this message: "+bounded(failure.toString()));
                throw failure;
            }
            // The pipe may have accepted part or all of the line. Keep ownership
            // and publish uncertainty instead of restoring an automatically sendable draft.
            settleSteer(send,"Codex pipe write failed; delivery is unknown. Reconnecting will not resend it.");
        }
        synchronized(send) { send.dispatched=true; }
        reportSteerCompletion(send);
    }

    @Override public boolean supportsAcknowledgements() { return true; }
    @Override public boolean supportsSteering() { return true; }

    @Override public boolean hasPendingSends() {
        synchronized(calls) { return !steers.isEmpty() || !starts.isEmpty(); }
    }

    @Override public void steer(String text) {
        if(text==null || text.trim().isEmpty()) throw new IllegalArgumentException("Message is empty");
        String thread, turn;
        final SteerSend send;
        int id;
        synchronized(stateLock) {
            if(closed) throw new IllegalStateException("Codex connection is closed");
            if(stopping) throw new IllegalStateException("Codex is stopping; your draft is ready to send afterward");
            if(pendingStart || turnId==null)
                throw new IllegalStateException("Codex is starting; your draft is ready to send when the turn starts");
            thread=threadId; turn=turnId;
            synchronized(calls) {
                if(steers.size()>=8) throw new IllegalStateException("Codex is still accepting previous messages; your draft has been kept");
                id=++counter; send=new SteerSend(id,text);
                calls.put(id,"turn/steer"); steers.put(id,send);
                if(steerTimer==null) steerTimer=new java.util.Timer("trough-codex-steer",true);
                send.timeout=ackTimeout(send,"Codex did not confirm a follow-up within 10 seconds; delivery is unknown. Reconnecting will not resend it.");
                steerTimer.schedule(send.timeout,STEER_ACK_TIMEOUT_MS);
            }
        }
        try {
            boolean cancelled;
            synchronized(send) { cancelled=send.finished; if(!cancelled) send.wireStarted=true; }
            if(cancelled) {
                synchronized(send) { send.dispatched=true; }
                reportSteerCompletion(send); return;
            }
            write(Json.map("id",id,"method","turn/steer","params",Json.map(
                "threadId",thread,"expectedTurnId",turn,"input",Json.list(Json.map("type","text","text",text)))));
        } catch(RuntimeException failure) {
            boolean wired; synchronized(send) { wired=send.wireStarted; }
            if(!wired) {
                synchronized(calls) { calls.remove(id); }
                removeSteer(send); throw failure;
            }
            settleSteer(send,"Codex pipe write failed; delivery is unknown. Reconnecting will not resend it.");
        }
        synchronized(send) { send.dispatched=true; }
        reportSteerCompletion(send);
    }

    private boolean settleSteer(SteerSend send,String failure) {
        if(send==null) return false;
        synchronized(send) {
            if(send.finished) return false;
            if(failure!=null && !send.wireStarted) failure="Codex did not send this message: "+failure;
            send.finished=true; send.failure=failure==null?null:bounded(failure);
            if(send.timeout!=null) send.timeout.cancel();
            return true;
        }
    }

    private void finishSteer(SteerSend send,String failure) {
        settleSteer(send,failure);
        if(send!=null) reportSteerCompletion(send);
    }

    private java.util.TimerTask ackTimeout(final SteerSend send,final String detail) {
        return new java.util.TimerTask() { public void run() {
            // response() claims this ID first once it has a confirmation in hand.
            synchronized(calls) { if(calls.remove(send.id)==null) return; }
            if(settleSteer(send,detail)) transportFailed(detail);
        }};
    }

    private void reportSteerCompletion(SteerSend send) {
        String failure;
        synchronized(send) {
            if(!send.dispatched || !send.finished || send.notified) return;
            send.notified=true; failure=send.failure;
        }
        try {
            if(failure!=null) listener.onSendFailure(send.text,failure);
            else listener.onMessageAccepted(send.text);
        }
        finally {
            // Keep ownership across callback registration: an empty transport queue must
            // never become observable before the UI has retained its recovery publication.
            removeSteer(send);
        }
    }

    private void removeSteer(SteerSend send) {
        synchronized(calls) {
            steers.remove(send.id);
            starts.remove(send.id);
            if(send.timeout!=null) send.timeout.cancel();
            if(steers.isEmpty() && starts.isEmpty() && steerTimer!=null) { steerTimer.cancel(); steerTimer=null; }
        }
    }

    private void failSteers(String detail) {
        List<SteerSend> pending;
        synchronized(calls) {
            pending=new ArrayList<>(steers.values()); pending.addAll(starts.values()); calls.clear();
            if(steerTimer!=null) { steerTimer.cancel(); steerTimer=null; }
        }
        for(SteerSend send:pending) finishSteer(send,bounded(detail));
    }

    private void startTurn(SteerSend send) {
        synchronized(send) { if(send.finished) return; }
        Map<String,Object> params = Json.map("threadId", threadId,"approvalPolicy","never",
            "input", Json.list(Json.map("type", "text", "text", send.text)));
        final String turnModel, turnEffort;
        synchronized(stateLock) {
            turnModel=model; turnEffort=reasoningEffort;
            lastDispatchedModel=turnModel==null?"":turnModel;
            lastDispatchedEffort=turnEffort==null?"":turnEffort;
        }
        if(turnModel!=null && !turnModel.isEmpty()) params.put("model",turnModel);
        if(turnEffort!=null && !turnEffort.isEmpty()) params.put("effort",turnEffort);
        // Sign-in/thread setup has finished. Bound the write itself as well as
        // acceptance, and claim the wire atomically against cancellation.
        synchronized(calls) { synchronized(send) {
            if(send.finished || !starts.containsKey(send.id)) return;
            send.wireStarted=true; calls.put(send.id,"turn/start");
            if(steerTimer==null) steerTimer=new java.util.Timer("trough-codex-steer",true);
            send.timeout=ackTimeout(send,"Codex did not confirm this message within 20 seconds; delivery is unknown. Reconnecting will not resend it.");
            steerTimer.schedule(send.timeout,START_ACK_TIMEOUT_MS);
        }}
        write(Json.map("id",send.id,"method","turn/start","params",params));
    }

    @Override public void allow(String requestId, Map<String, Object> input) { answer(requestId, "accept"); }

    @Override public void deny(String requestId, String message) { answer(requestId, "decline"); }

    @Override public void interrupt() {
        boolean cancelledQueued=false;
        List<SteerSend> cancelled=new ArrayList<>();
        synchronized(stateLock) {
            if(closed) return;
            if(!queued.isEmpty()) {
                cancelled.addAll(queued); queued.clear(); cancelledQueued=true;
                event("turn/cancelled-before-start");
            } else if(pendingStart || turnId!=null) {
                stopping=true; event("turn/stop-requested");
            }
        }
        for(SteerSend send:cancelled) finishSteer(send,"Cancelled before Codex accepted this message");
        if(cancelledQueued) listener.onResult(false,0,0,"Cancelled before Codex started the turn.");
        sendInterruptIfReady();
    }

    @Override public void close() {
        synchronized(stateLock) {
            closed=true; pendingStart=false; stopping=false; interruptSent=false; queued.clear();
            event("closed");
        }
        failSteers("Codex connection closed before this message was confirmed");
        // Killing first also releases a sender blocked on a pipe the child stopped reading.
        proc.destroy();
        try { in.close(); } catch (Exception ignored) {}
    }

    /** Local transport state only: no messages, credentials, or approval details. */
    public Map<String,Object> state() {
        synchronized(stateLock) {
            int pendingSends;
            synchronized(calls) { pendingSends=steers.size()+starts.size(); }
            return Json.map("last_item_type",lastItemType,"protocol_error",lastProtocolError,"transport_revision",TRANSPORT_REVISION,"ignored_foreign_events",ignoredForeignEvents,"health",health.snapshot(),"compaction_count",compactionCount,"last_compaction_duration_ms",lastCompactionDurationMs,"compaction_elapsed_ms",compactionStartedNanos==0?0:Math.max(0,(System.nanoTime()-compactionStartedNanos)/1000000L),"thread_id",threadId==null?"":threadId,"turn_id",turnId==null?"":turnId,
                "pending_start",pendingStart,"stopping",stopping,"interrupt_sent",interruptSent,
                "queued_count",queued.size(),"pending_send_count",pendingSends,"closed",closed,"last_event",lastEvent,
                "last_event_age_ms",Math.max(0,(System.nanoTime()-lastEventNanos)/1000000L),"last_error",lastError,
                "selection_mutable",true,"selected_model",model==null?"":model,
                "selected_effort",reasoningEffort==null?"":reasoningEffort,
                "last_dispatched_model",lastDispatchedModel,"last_dispatched_effort",lastDispatchedEffort,
                "reported_model",actualModel,"reported_effort",actualEffort,
                "reported_selection_source",actualSelectionSource);
        }
    }

    private void event(String name) { lastEvent=name; lastEventNanos=System.nanoTime(); }

    private static String bounded(String message) {
        if(message==null) return "";
        return message.length()>600 ? message.substring(0,600) : message;
    }

    private void sendInterruptIfReady() {
        String thread, turn;
        synchronized(stateLock) {
            if(closed || !stopping || interruptSent || threadId==null || turnId==null) return;
            interruptSent=true; thread=threadId; turn=turnId; event("turn/interrupt/request");
        }
        call("turn/interrupt",Json.map("threadId",thread,"turnId",turn));
    }

    private void acceptTurn(Map<String,Object> turn) {
        String id=Json.str(turn,"id");
        if(id==null || id.isEmpty()) return;
        synchronized(stateLock) {
            if(closed || (!pendingStart && turnId==null)) return;
            if(turnId!=null && !turnId.equals(id)) return;
            turnId=id; pendingStart=false; event("turn/started");
        }
        sendInterruptIfReady();
    }

    private void exited(int code,String detail) {
        synchronized(stateLock) {
            if(exitReported) return;
            exitReported=true; closed=true; pendingStart=false; stopping=false; interruptSent=false;
            queued.clear(); turnId=null; lastError=bounded(detail); event("exited");
        }
        failSteers("Codex disconnected before this message was confirmed: "+bounded(detail));
        listener.onExit(code,bounded(detail));
    }

    private void transportFailed(String detail) {
        // Killing first releases a blocked pipe writer, including a child that
        // ignores SIGTERM. Publish settlement before potentially blocking close.
        proc.destroyForcibly();
        exited(-1,detail);
        try { in.close(); } catch(Exception ignored) {}
    }

    // --- wire ---

    private void write(Map<String, Object> msg) {
        Exception problem;
        synchronized(this) {
            try {
                msg.put("jsonrpc", "2.0");
                in.write((Json.write(msg) + "\n").getBytes(StandardCharsets.UTF_8));
                in.flush(); return;
            } catch(Exception error) { problem=error; }
        }
        // Never invoke recovery/listeners while holding the write monitor.
        String detail="Codex pipe write failed: "+bounded(String.valueOf(problem));
        transportFailed(detail);
        throw new IllegalStateException(bounded(detail),problem);
    }

    private void call(String method, Map<String, Object> params) {
        int id;
        synchronized (calls) { id = ++counter; calls.put(id, method); }
        try { write(Json.map("id", id, "method", method, "params", params)); }
        catch(RuntimeException failure) { synchronized(calls) { calls.remove(id); } throw failure; }
    }

    private void answer(String askId, String decision) {
        Object id;
        synchronized (asks) { id = asks.remove(askId); }
        if (id != null) write(Json.map("id", id, "result", Json.map("decision", decision)));
    }

    public String reportedEffort() { return actualEffort; }

    private void startThread() {
        Map<String,Object> p = Json.map("cwd",cwd.getPath(),"approvalPolicy","never","sandbox",sandbox);
        final String threadModel, threadEffort;
        synchronized(stateLock) { threadModel=model; threadEffort=reasoningEffort; }
        if(threadModel!=null && !threadModel.isEmpty()) p.put("model",threadModel);
        Map<String,Object> config=Json.map("compact_prompt",
            "Create a concise checkpoint for the next model continuing this conversation. Target at most 250 words; use terse factual bullets. Do not restate this prompt.  "
            +"Preserve the current user request, constraints and authorizations, decisions, changed file paths, "
            +"validation results, and exact next steps. Keep necessary identifiers and unresolved blockers. "
            +"Omit repeated history, copied instructions, tool transcripts, completed unrelated tasks and long logs. "
            +"Refer to files for details. Return only the checkpoint; do not continue the task.",
            "tool_output_token_limit",4000);
        if(threadEffort!=null && !threadEffort.isEmpty())config.put("model_reasoning_effort",threadEffort);
        p.put("config",config);
        if(resume!=null && !resume.isEmpty()) {
            p.put("threadId",resume);
            call("thread/resume",p);
        } else call("thread/start",p);
    }

    private void readOut() {
        String streamFailure="";
        try (CodexStream r = new CodexStream(proc.getInputStream())) {
            for (Object frame; (frame = r.next()) != null; ) {
                try { dispatch(Json.obj(frame)); } catch (RuntimeException bad) { synchronized(stateLock){lastProtocolError=bounded(bad.toString());} }
            }
        } catch (Exception failure) {
            streamFailure="Codex output read failed: "+failure;
        }
        int code=-1;
        try {
            if(proc.isAlive()) {
                proc.destroy();
                if(!proc.waitFor(2,java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly();
            }
            if(proc.waitFor(2,java.util.concurrent.TimeUnit.SECONDS)) code=proc.exitValue();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        String tail;
        synchronized (stderr) { tail = stderr.toString(); }
        exited(code,!streamFailure.isEmpty()?streamFailure+(tail.isEmpty()?"":"; "+tail):tail.isEmpty()?"Codex output stream closed":tail);
    }

    private void readErr() {
        try (InputStreamReader r = new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8)) {
            char[] chunk=new char[2048];
            for (int n; (n=r.read(chunk))!=-1;) {
                synchronized (stderr) {
                    stderr.append(chunk,0,n);
                    if (stderr.length()>4000) stderr.delete(0,stderr.length()-4000);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void dispatch(Map<String, Object> m) {
        if (m == null) return;
        String method = Json.str(m, "method");
        Object id = m.get("id");
        if (method == null && id instanceof Number) { response(((Number) id).intValue(), m); return; }
        Map<String, Object> p = Json.obj(m.get("params"));
        if (method == null || p == null) return;
        // app-server multiplexes child-agent threads on this same pipe. Their
        // turn IDs, text, completion and health must never mutate this session.
        String eventThread=Json.str(p,"threadId");
        if(eventThread==null) eventThread=Json.str(p,"thread_id");
        if(eventThread==null) eventThread=Json.str(p,"conversationId");
        if(eventThread==null && method.startsWith("thread/"))
            eventThread=Json.str(Json.obj(p.get("thread")),"id");
        String owner=threadId;
        boolean scoped=method.startsWith("turn/") || method.startsWith("item/");
        if((scoped && eventThread==null) || (eventThread!=null && (owner==null || !owner.equals(eventThread)))) {
            synchronized(stateLock) { ignoredForeignEvents++; }
            // A request must be answered, but cannot become a parent approval.
            if(id!=null) write(Json.map("id",id,"error",Json.map("code",-32602,
                "message","Request belongs to a different conversation")));
            return;
        }
        health.event(method,p);
        if("turn/started".equals(method) || TransportHealth.meaningfulProgress(method,p))synchronized(stateLock){turnFailureDetail="";}
        if("item/started".equals(method)){Map<String,Object> item=Json.obj(p.get("item"));synchronized(stateLock){lastItemType=Json.str(item,"type");}}
        if("item/started".equals(method)){Map<String,Object> item=Json.obj(p.get("item"));if("contextCompaction".equals(Json.str(item,"type")))synchronized(stateLock){if(compactionStartedNanos==0){compactionStartedNanos=System.nanoTime();compactionCount++;}}}
        if("item/completed".equals(method)){Map<String,Object> item=Json.obj(p.get("item"));if("contextCompaction".equals(Json.str(item,"type")))synchronized(stateLock){lastItemType="";if(compactionStartedNanos!=0)lastCompactionDurationMs=Math.max(0,(System.nanoTime()-compactionStartedNanos)/1000000L);compactionStartedNanos=0;}}
        synchronized(stateLock) { if(closed) return; event(method); }
        if (id != null) { request(id, method, p); return; }
        switch (method) {
            case "account/login/completed":
                if (Boolean.TRUE.equals(p.get("success"))) startThread();
                else transportFailed("codex sign-in failed: " + Json.str(p, "error"));
                break;
            case "turn/started": {
                Map<String, Object> t = Json.obj(p.get("turn"));
                acceptTurn(t);
                break;
            }
            case "error": {
                Map<String,Object> error=Json.obj(p.get("error"));
                synchronized(stateLock) {
                    lastError=bounded(ConnectionRecovery.errorText(error));
                    if((pendingStart || turnId!=null) && (ConnectionRecovery.transientFailure(lastError)
                        || ConnectionRecovery.requiresSignIn(lastError) || ConnectionRecovery.rateLimited(lastError))) {
                        boolean blocked=ConnectionRecovery.requiresSignIn(turnFailureDetail) || ConnectionRecovery.rateLimited(turnFailureDetail);
                        if(!blocked || ConnectionRecovery.requiresSignIn(lastError) || ConnectionRecovery.rateLimited(lastError))turnFailureDetail=lastError;
                    }
                }
                break;
            }
            case "thread/closed":
                if(threadId!=null && threadId.equals(Json.str(p,"threadId"))) transportFailed("Codex thread closed");
                break;
            case "item/agentMessage/delta":
                listener.onTextDelta(Json.str(p, "delta"));
                break;
            case "item/started": {
                Map<String, Object> item = Json.obj(p.get("item"));
                String type = item == null ? null : Json.str(item, "type");
                if ("contextCompaction".equals(type))
                    listener.onToolUse(Json.str(item,"id"),"Compacting history",Json.map("description","Preparing this long conversation; your messages are preserved"));
                else if ("commandExecution".equals(type))
                    listener.onToolUse(Json.str(item, "id"), "Bash", Json.map("command", Json.str(item, "command")));
                else if ("fileChange".equals(type))
                    listener.onToolUse(Json.str(item, "id"), "Edit", Json.map("file_path", paths(item)));
                else if ("mcpToolCall".equals(type))
                    listener.onToolUse(Json.str(item, "id"), Json.str(item, "server") + "/" + Json.str(item, "tool"), Json.obj(item.get("arguments")));
                else if ("webSearch".equals(type))
                    listener.onToolUse(Json.str(item, "id"), "WebSearch", Json.map("description", Json.str(item, "query")));
                break;
            }
            case "item/completed": {
                Map<String, Object> item = Json.obj(p.get("item"));
                String type = item == null ? null : Json.str(item, "type");
                if ("contextCompaction".equals(type)) listener.onToolResult(Json.str(item,"id"),"Conversation prepared",false);
                else if ("agentMessage".equals(type)) listener.onAssistantText(Json.str(item, "text"));
                else if ("commandExecution".equals(type)) {
                    Object exit = item.get("exitCode");
                    String out = Json.str(item, "aggregatedOutput");
                    boolean failed = !"completed".equals(Json.str(item, "status")) || (exit instanceof Number && ((Number) exit).intValue() != 0);
                    listener.onToolResult(Json.str(item, "id"), (out == null ? "" : out) + (exit instanceof Number && ((Number) exit).intValue() != 0 ? "\n(exit " + exit + ")" : ""), failed);
                } else if ("mcpToolCall".equals(type)) {
                    Map<String,Object> result=Json.obj(item.get("result"));
                    String status=Json.str(item,"status");
                    boolean failed=item.get("error")!=null || (result!=null && Boolean.TRUE.equals(result.get("isError")))
                        || (status!=null && !"completed".equals(status));
                    listener.onToolResult(Json.str(item,"id"),mcpOutput(item),failed);
                } else if (type != null && !"userMessage".equals(type) && !"reasoning".equals(type)) {
                    String status=Json.str(item,"status");
                    // Search completions have no status in some protocol versions. Unknown
                    // status-less items are not tool results (and must not print "null").
                    if(status!=null || "fileChange".equals(type) || "webSearch".equals(type)) {
                        String text=status!=null?status:"webSearch".equals(type)?"Search complete":"File change complete";
                        if(item.get("error")!=null) text+="\n"+outputValue(item.get("error"));
                        listener.onToolResult(Json.str(item,"id"),text,item.get("error")!=null || (status!=null && !"completed".equals(status)));
                    }
                }
                break;
            }
            case "turn/completed": {
                Map<String, Object> t = Json.obj(p.get("turn"));
                Map<String, Object> err = t == null ? null : Json.obj(t.get("error"));
                Object dur = t == null ? null : t.get("durationMs");
                boolean completed=t != null && "completed".equals(Json.str(t,"status"));
                String failure=err==null?null:bounded(ConnectionRecovery.errorText(err));
                synchronized(stateLock) {
                    String completedId=Json.str(t,"id");
                    if(!pendingStart && turnId==null) break;
                    if(turnId!=null && completedId!=null && !turnId.equals(completedId)) break;
                    turnId=null; pendingStart=false; stopping=false; interruptSent=false;
                    if(!completed && (failure==null || failure.isEmpty()) && !turnFailureDetail.isEmpty())failure=turnFailureDetail;
                    lastItemType="";compactionStartedNanos=0;
                    lastError=completed?"":bounded(failure);turnFailureDetail="";event("turn/completed");
                }
                listener.onResult(completed, 0, dur instanceof Number ? ((Number) dur).longValue() : 0, failure);
                break;
            }
            default:
                break;
        }
    }

    private void response(int id, Map<String, Object> m) {
        String method;
        SteerSend steer, start;
        synchronized (calls) { method = calls.remove(id); steer=steers.get(id); start=starts.get(id); }
        Map<String, Object> result = Json.obj(m.get("result"));
        Map<String, Object> error = Json.obj(m.get("error"));
        if("turn/steer".equals(method)) {
            String failure=error!=null ? "Codex did not accept this message: "+bounded(Json.str(error,"message"))
                : result==null ? "Codex returned no confirmation for this message" : null;
            synchronized(stateLock) {
                if(!closed) {
                    if(failure!=null) lastError=bounded(failure);
                    event(failure==null?"turn/steer/ack":"turn/steer/error");
                }
            }
            finishSteer(steer,failure);
            return;
        }
        synchronized(stateLock) { if(closed) return; event("response/"+method); }
        if ("initialize".equals(method) && result != null) {
            write(Json.map("method", "initialized"));
            call("account/read", Json.map());
        } else if ("initialize".equals(method)) {
            transportFailed("Codex initialize failed: "+bounded(Json.str(error,"message")));
        } else if ("account/read".equals(method)) {
            if(error!=null) { transportFailed("Codex account read failed: "+bounded(Json.str(error,"message"))); return; }
            boolean signedOut = result != null && result.get("account") == null && Boolean.TRUE.equals(result.get("requiresOpenaiAuth"));
            if (signedOut) call("account/login/start", Json.map("type", "chatgptDeviceCode"));
            else startThread();
        } else if ("account/login/start".equals(method)) {
            if (result == null) { transportFailed("codex sign-in failed to start: " + (error == null ? "" : Json.str(error, "message"))); return; }
            listener.onAuth(Json.str(result, "verificationUrl"), Json.str(result, "userCode"));
        } else if (("thread/start".equals(method) || "thread/resume".equals(method))) {
            boolean defaultModel;
            synchronized(stateLock) { defaultModel=model==null || model.isEmpty(); }
            // A failed resume must preserve the saved ID. A new conversation needs an explicit user action.
            if(result==null && "thread/resume".equals(method)) {
                transportFailed("Could not resume saved conversation "+resume+": "+(error==null?"no response":Json.str(error,"message")));
                return;
            }
            Map<String, Object> t = result == null ? null : Json.obj(result.get("thread"));
            if (t == null) { transportFailed("codex " + method + " failed: " + (error == null ? "" : Json.str(error, "message"))); return; }
            List<SteerSend> pending;
            synchronized (stateLock) {
                threadId = Json.str(t, "id"); pending = new ArrayList<>(queued); queued.clear();
                if(!pending.isEmpty()) { pendingStart=true; event("turn/start/request"); }
            }
            actualEffort = Json.str(result,"reasoningEffort");
            if(actualEffort==null) actualEffort="";
            actualModel = Json.str(result,"model");
            if(actualModel==null) actualModel="";
            actualSelectionSource = method;
            listener.onInit(threadId, actualModel);
            for (SteerSend send : pending) startTurn(send);
        } else if ("turn/start".equals(method)) {
            Map<String,Object> turn=Json.obj(result==null?null:result.get("turn"));
            String acceptedId=Json.str(turn,"id");
            String failure=error!=null ? "Codex did not accept this message: "+bounded(Json.str(error,"message"))
                : acceptedId==null || acceptedId.isEmpty() ? "Codex returned no confirmation for this message" : null;
            if(failure!=null) {
                synchronized(stateLock) {
                    pendingStart=false; turnId=null; stopping=false; interruptSent=false;
                    lastError=bounded(failure); event("turn/start/error");
                }
                finishSteer(start,failure);
                listener.onResult(false,0,0,bounded(failure));
            } else {
                acceptTurn(turn);
                synchronized(stateLock) { event("turn/start/ack"); }
                finishSteer(start,null);
            }
        } else if("turn/interrupt".equals(method)) {
            synchronized(stateLock) {
                if(error!=null) {
                    lastError=bounded("Codex stop failed: "+Json.str(error,"message"));
                    interruptSent=false; event("turn/interrupt/error");
                } else event("turn/interrupt/ack");
            }
        }
    }

    private void request(Object id, String method, Map<String, Object> p) {
        String tool = "item/commandExecution/requestApproval".equals(method) ? "Bash"
            : "item/fileChange/requestApproval".equals(method) ? "Edit" : null;
        if (tool == null) {
            write(Json.map("id", id, "error", Json.map("code", -32601, "message", "Trough does not implement " + method)));
            return;
        }
        String ask = "codex_" + id;
        synchronized (asks) { asks.put(ask, id); }
        Map<String, Object> input = new HashMap<>();
        for (String k : new String[]{"command", "cwd", "reason", "grantRoot"}) if (p.get(k) != null) input.put(k, p.get(k));
        listener.onPermission(ask, tool, input);
    }

    /** Preserve MCP output instead of replacing it with the call's completion status. */
    private static String mcpOutput(Map<String,Object> item) {
        StringBuilder text=new StringBuilder();
        Object raw=item.get("result");
        Map<String,Object> result=Json.obj(raw);
        if(result!=null) {
            List<Object> content=Json.arr(result.get("content"));
            if(content!=null) for(Object value:content) {
                Map<String,Object> block=Json.obj(value);
                String body=Json.str(block,"text");
                if(body==null && block!=null && "resource".equals(Json.str(block,"type")))
                    body=Json.str(Json.obj(block.get("resource")),"text");
                appendOutput(text,body!=null?body:outputValue(value));
            }
            if(result.get("structuredContent")!=null) appendOutput(text,outputValue(result.get("structuredContent")));
            if(content==null && result.get("structuredContent")==null) appendOutput(text,outputValue(result));
        } else if(raw!=null) appendOutput(text,outputValue(raw));
        if(item.get("error")!=null) appendOutput(text,outputValue(item.get("error")));
        if(text.length()==0) {
            String status=Json.str(item,"status");
            return status==null?"Tool completed":status;
        }
        return text.toString();
    }

    private static void appendOutput(StringBuilder text,String value) {
        if(value==null || value.isEmpty()) return;
        if(text.length()>0) text.append('\n');
        text.append(value);
    }

    private static String outputValue(Object value) {
        return value instanceof String?(String)value:Json.write(value);
    }

    private static String paths(Map<String, Object> item) {
        List<Object> changes = Json.arr(item.get("changes"));
        if (changes == null) return "";
        StringBuilder b = new StringBuilder();
        for (Object c : changes) {
            String path = Json.str(Json.obj(c), "path");
            if (path != null) b.append(b.length() == 0 ? "" : ", ").append(path);
        }
        return b.toString();
    }

    private static void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
