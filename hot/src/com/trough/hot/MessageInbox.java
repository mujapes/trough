package com.trough.hot;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable local outbox and a readable inbox for the currently running coding agent.
 * Call on one I/O worker. The fsynced journal is authoritative; the JSON snapshot is
 * an atomic, recoverable projection. A message is never retried after an ambiguous send.
 */
public final class MessageInbox {
    /** Invalid local commands may be quarantined without implying message loss. */
    public static final class ActionRejectedException extends IOException {
        public ActionRejectedException(String message) { super(message); }
        public ActionRejectedException(String message,Throwable cause) { super(message,cause); }
    }
    private final File journal, inbox, draftFile, draftStateFile, actions;
    private final LinkedHashMap<String, Map<String, Object>> messages = new LinkedHashMap<String, Map<String, Object>>();
    private String savedDraft = "", snapshotError = "", draftError = "", draftVersion;
    private boolean draftStateDirty;
    private long updatedMs;
    private boolean poisoned;

    public MessageInbox(File home) throws IOException {
        this(home,true);
    }

    /** A live in-process owner handoff keeps its acknowledged transport and pending sends. */
    public MessageInbox(File home,boolean recoverInterruptedSends) throws IOException {
        File root = new File(home, ".trough").getCanonicalFile();
        File records = new File(root, "messages");
        actions = new File(root, "inbox-actions");
        mkdir(records); mkdir(actions);
        journal = new File(records, "journal.jsonl");
        inbox = new File(root, "inbox.json");
        draftFile = new File(root, "draft.txt");
        draftStateFile = new File(root, "draft.json");
        if (draftStateFile.exists()) {
            Map<String,Object> state;
            try { state=Json.obj(Json.parse(readUtf8(draftStateFile,-1))); }
            catch(RuntimeException invalid) { throw new IOException("Invalid saved draft metadata",invalid); }
            savedDraft=Json.str(state,"text"); draftVersion=Json.str(state,"version");
            if(savedDraft==null || draftVersion==null) throw new IOException("Invalid saved draft metadata");
            try { UUID.fromString(draftVersion); }
            catch(IllegalArgumentException invalid) { throw new IOException("Invalid saved draft version",invalid); }
        } else {
            if (draftFile.exists()) savedDraft = readUtf8(draftFile, -1);
            draftVersion=UUID.randomUUID().toString();
            atomicWrite(draftStateFile,Json.write(Json.map("text",savedDraft,"version",draftVersion))+"\n");
        }
        replay();
        persistDraftProjection();
        // A process could have died after sending but before recording acceptance.
        // Explicit retry or incorporation is required; never resend automatically.
        if(recoverInterruptedSends) for (Map<String, Object> message : new ArrayList<Map<String, Object>>(messages.values())) {
            if ("sending".equals(message.get("state"))) {
                transitionInternal((String) message.get("id"), "uncertain", "Process restarted before send acceptance was recorded");
            }
        }
        // Repair historical pre-write rejection classification without replaying text.
        for(Map<String,Object> message:new ArrayList<Map<String,Object>>(messages.values()))
            if("codex".equals(message.get("agent")) && "uncertain".equals(message.get("state"))
                && knownPrewriteRejection(Json.str(message,"detail")))
                transitionInternal((String)message.get("id"),"failed","Confirmed not sent before transport write; preserved for review, no automatic replay");
        publishBestEffort();
    }

    static boolean knownPrewriteRejection(String error){return "java.lang.IllegalStateException: Codex is starting; your draft is ready to send when the turn starts".equals(error);}

    /** Explicit no-write and RPC rejection prove non-acceptance; ambiguous sends stay unknown. */
    public static String deliveryFailureState(String error) {
        return error!=null && (knownPrewriteRejection(error) || error.startsWith("Codex did not accept this message: ")
            || error.startsWith("Codex did not send this message: ")) ? "failed" : "uncertain";
    }

    public String draft() { return savedDraft; }

    public void saveDraft(String text) throws IOException {
        if (text == null) text = "";
        if (savedDraft.equals(text)) { persistDraftProjection(); return; }
        String nextVersion=UUID.randomUUID().toString();
        atomicWrite(draftStateFile,Json.write(Json.map("text",text,"version",nextVersion))+"\n");
        savedDraft = text; draftVersion=nextVersion; draftStateDirty=false;
        persistDraftProjection();
    }

    public Map<String, Object> enqueue(String text, String agent, String sessionId) throws IOException {
        return enqueueInternal(text,agent,sessionId,"queued","");
    }

    /** Recovery and explicit failures belong in the inbox, never back in the composer. */
    public Map<String,Object> enqueueHeld(String text,String agent,String sessionId,String detail) throws IOException {
        return enqueueInternal(text,agent,sessionId,"held",clean(detail));
    }

    public void recoverDraftToInbox(String agent,String sessionId) throws IOException {
        if(savedDraft.trim().length()!=0) enqueueHeld(savedDraft,agent,sessionId,
                "Recovered unsent text; held for review");
    }

    private Map<String,Object> enqueueInternal(String text,String agent,String sessionId,String state,String detail) throws IOException {
        if (text == null || text.trim().length() == 0) throw new IllegalArgumentException("Empty message");
        long now = System.currentTimeMillis();
        Map<String, Object> message = Json.map("id", UUID.randomUUID().toString(), "text", text,
                "agent", clean(agent), "session_id", clean(sessionId), "created_ms", now,
                "updated_ms", now, "state", state, "detail", detail,"triage",MessageTriage.classify(text));
        String clearsDraft=!"halfsword".equals(agent)&&savedDraft.trim().equals(text.trim())?draftVersion:null;
        Map<String,Object> event=Json.map("version", 1, "event", "enqueue", "message", message);
        if(clearsDraft!=null) event.put("clears_draft_version",clearsDraft);
        append(event);
        messages.put((String) message.get("id"), message);
        updatedMs = now;
        clearOwnedDraft(clearsDraft);
        persistDraftProjection();
        publishBestEffort();
        Map<String, Object> result = copy(message);
        if (snapshotError.length() != 0) result.put("snapshot_error", snapshotError);
        return result;
    }

    public void transition(String id, String state, String detail) throws IOException {
        transitionInternal(id, state, detail);
        publishBestEffort();
    }

    /** Claim only the state that was reviewed, after applying any newer inbox actions. */
    public boolean transitionIf(String id,String expectedState,String state,String detail) throws IOException {
        Map<String,Object> message=messages.get(id);
        if(message==null || !clean(expectedState).equals(message.get("state"))) return false;
        transition(id,state,detail);
        return true;
    }

    /** A stale Retry dialog must never resend a message that has since been accepted. */
    public void retry(String id) throws IOException {
        Map<String,Object> message=messages.get(id);
        if(message==null) throw new IOException("Unknown inbox message "+id);
        String state=Json.str(message,"state");
        if(!"uncertain".equals(state) && !"failed".equals(state) && !"held".equals(state)) return;
        transition(id,"queued","Retry requested in Trough");
    }

    /** Apply agent-written action files, each atomically renamed to UUID.json.
     * Successfully quarantined validation errors use ActionRejectedException.
     * Storage, journal and quarantine failures remain critical IOExceptions.
     */
    public void applyActions() throws IOException {
        File[] files = actions.listFiles();
        if (files == null) throw new IOException("Cannot list inbox action directory");
        Arrays.sort(files);
        IOException failure = null;
        boolean changed = false;
        for (File file : files) {
            if (!file.getName().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.json")) continue;
            try {
                if (!file.getCanonicalFile().equals(file.getAbsoluteFile()) || !file.isFile()) throw new ActionRejectedException("Action must be a regular local file");
                if (file.length()>65536) throw new ActionRejectedException("Action is too large");
                Map<String, Object> action = Json.obj(Json.parse(readUtf8(file, 65536)));
                if (action == null) throw new ActionRejectedException("Action must be a JSON object");
                String id = Json.str(action, "id"), operation = Json.str(action, "action");
                if (id == null || !messages.containsKey(id)) throw new ActionRejectedException("Action references an unknown message");
                Map<String, Object> message = messages.get(id);
                String state = (String) message.get("state");
                if ("incorporate".equals(operation)) {
                    String note=clean(Json.str(action,"note"));
                    boolean recovering="uncertain".equals(state) || "failed".equals(state);
                    if(recovering && note.trim().isEmpty())
                        throw new ActionRejectedException("Uncertain delivery pickup requires a reconciliation note");
                    if ("queued".equals(state) || recovering) {
                        // Explicit agent pickup, never automatic resend. Preserve
                        // the original failure in the append-only journal.
                        transitionInternal(id, "incorporated", note);
                        changed = true;
                    }
                } else if ("retry".equals(operation)) {
                    if ("uncertain".equals(state) || "failed".equals(state) || "held".equals(state)) {
                        transitionInternal(id, "queued", clean(Json.str(action, "note")));
                        changed = true;
                    }
                } else throw new ActionRejectedException("Unknown inbox action");
                if (!file.delete()) throw new IOException("Cannot remove applied inbox action");
            } catch (IllegalArgumentException | CharacterCodingException invalid) {
                IOException problem = quarantineRejected(file,invalid);
                if (failure == null || failure instanceof ActionRejectedException) failure = problem;
            } catch (ActionRejectedException invalid) {
                IOException problem = quarantineRejected(file,invalid);
                if (failure == null || failure instanceof ActionRejectedException) failure = problem;
            } catch (IOException problem) {
                // Do not hide a failed journal append as an invalid action.
                if (poisoned) throw problem;
                if (!file.renameTo(new File(actions, file.getName() + ".rejected"))) problem.addSuppressed(new IOException("Could not quarantine action"));
                if (failure == null || failure instanceof ActionRejectedException) failure = new IOException("Inbox action " + file.getName() + ": " + problem.getMessage(), problem);
            }
        }
        if (changed || snapshotError.length() != 0) publishBestEffort();
        if (failure != null) throw failure;
    }

    private IOException quarantineRejected(File file,Exception invalid) {
        String message="Rejected inbox action "+file.getName()+": "+invalid.getMessage();
        if (!file.renameTo(new File(actions,file.getName()+".rejected")))
            return new IOException("Could not quarantine inbox action "+file.getName(),invalid);
        return new ActionRejectedException(message,invalid);
    }

    public Map<String, Object> snapshot() {
        List<Object> list = new ArrayList<Object>();
        int queued = 0, uncertain = 0, held = 0;
        for (Map<String, Object> message : messages.values()) {
            list.add(copy(message));
            if ("queued".equals(message.get("state"))) queued++;
            if ("uncertain".equals(message.get("state"))) uncertain++;
            if ("held".equals(message.get("state"))) held++;
        }
        return Json.map("protocol_version", 1, "runtime_protocol", "trough-live-inbox-1",
                "last_updated_ms", updatedMs, "journal_path", journal.getAbsolutePath(),
                "actions_path", actions.getAbsolutePath(), "draft_path", draftFile.getAbsolutePath(),
                "inbox_path", inbox.getAbsolutePath(), "queued", queued, "uncertain", uncertain,
                "queued_count", queued, "uncertain_count", uncertain,"held_count",held, "messages", list,
                "snapshot_error", snapshotError, "draft_error",draftError);
    }

    public Map<String, Object> next(String agent, String sessionId) {
        String selectedAgent = clean(agent), selectedSession = clean(sessionId);
        Map<String,Object> selected=null;
        int selectedRank=-1;
        for (Map<String, Object> message : messages.values()) {
            if (!"queued".equals(message.get("state")) || !selectedAgent.equals(message.get("agent"))) continue;
            String queuedSession = clean((String) message.get("session_id"));
            if (queuedSession.length() != 0 && !queuedSession.equals(selectedSession)) continue;
            int rank=MessageTriage.rank(message);
            if(rank>selectedRank) { selected=message; selectedRank=rank; }
        }
        return selected==null?null:copy(selected);
    }

    private void transitionInternal(String id, String state, String detail) throws IOException {
        Map<String, Object> previous = messages.get(id);
        if (previous == null) throw new IOException("Unknown inbox message " + id);
        if (state == null || state.length() == 0 || state.length() > 64) throw new IllegalArgumentException("Invalid message state");
        detail = clean(detail);
        if (state.equals(previous.get("state")) && detail.equals(previous.get("detail"))) return;
        long now = System.currentTimeMillis();
        append(Json.map("version", 1, "event", "transition", "id", id, "state", state,
                "detail", detail, "updated_ms", now));
        Map<String, Object> replacement = copy(previous);
        replacement.put("state", state); replacement.put("detail", detail); replacement.put("updated_ms", now);
        messages.put(id, replacement);
        updatedMs = now;
    }

    private void append(Map<String, Object> event) throws IOException {
        if (poisoned) throw new IOException("Message journal write failed; reopen inbox before another write");
        try {
            FileOutputStream out = new FileOutputStream(journal, true);
            try {
                out.write((Json.write(event) + "\n").getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } finally { out.close(); }
        } catch (IOException failure) { poisoned = true; throw failure; }
    }

    private void replay() throws IOException {
        if (!journal.exists()) return;
        BufferedInputStream input = new BufferedInputStream(new FileInputStream(journal));
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        long complete = 0, consumed = 0, lineNumber = 0;
        try {
            for (int next; (next = input.read()) != -1;) {
                consumed++;
                if (next != '\n') { line.write(next); continue; }
                lineNumber++;
                try { replayEvent(Json.obj(Json.parse(decode(line.toByteArray())))); }
                catch (RuntimeException invalid) { throw new IOException("Invalid inbox journal line " + lineNumber, invalid); }
                line.reset(); complete = consumed;
            }
        } finally { input.close(); }
        if (consumed != complete) {
            RandomAccessFile repair = new RandomAccessFile(journal, "rw");
            try { repair.setLength(complete); repair.getFD().sync(); }
            finally { repair.close(); }
        }
    }

    private void replayEvent(Map<String, Object> event) {
        if (event == null || !(event.get("version") instanceof Number) || ((Number) event.get("version")).intValue() != 1) throw new IllegalArgumentException("Unknown journal version");
        String type = Json.str(event, "event");
        if ("enqueue".equals(type)) {
            Map<String, Object> message = Json.obj(event.get("message"));
            String id = Json.str(message, "id");
            if (id == null || !UUID.fromString(id).toString().equals(id) || messages.containsKey(id)
                    || Json.str(message, "text") == null || Json.str(message, "agent") == null
                    || Json.str(message, "session_id") == null || !("queued".equals(Json.str(message, "state")) || "held".equals(Json.str(message,"state")))
                    || !(message.get("created_ms") instanceof Number) || !(message.get("updated_ms") instanceof Number)) throw new IllegalArgumentException("Invalid enqueue record");
            messages.put(id, copy(message));
            updatedMs = ((Number) message.get("updated_ms")).longValue();
            clearOwnedDraft(Json.str(event,"clears_draft_version"));
        } else if ("transition".equals(type)) {
            String id = Json.str(event, "id"), state = Json.str(event, "state"), detail = Json.str(event, "detail");
            if (id == null || !messages.containsKey(id) || state == null || state.length() == 0 || state.length() > 64 || detail == null || !(event.get("updated_ms") instanceof Number)) throw new IllegalArgumentException("Invalid transition record");
            Map<String, Object> replacement = copy(messages.get(id));
            replacement.put("state", state); replacement.put("detail", detail); replacement.put("updated_ms", event.get("updated_ms"));
            messages.put(id, replacement);
            updatedMs = ((Number) event.get("updated_ms")).longValue();
        } else throw new IllegalArgumentException("Unknown journal event");
    }

    private void publishBestEffort() {
        String previousError = snapshotError;
        snapshotError = "";
        try { atomicWrite(inbox, Json.write(snapshot()) + "\n"); }
        catch (IOException problem) { snapshotError = "Journal saved; inbox snapshot unavailable: " + problem.getMessage(); }
    }

    private void clearOwnedDraft(String version) {
        if(version==null || !version.equals(draftVersion)) return;
        savedDraft=""; draftVersion=UUID.randomUUID().toString(); draftStateDirty=true;
    }

    /** Journal ownership recovers a failed post-commit clear; mirrors cannot invalidate a commit. */
    private void persistDraftProjection() {
        draftError="";
        try {
            if(draftStateDirty) {
                atomicWrite(draftStateFile,Json.write(Json.map("text",savedDraft,"version",draftVersion))+"\n");
                draftStateDirty=false;
            }
            if(!draftFile.exists() || !savedDraft.equals(readUtf8(draftFile,-1))) atomicWrite(draftFile,savedDraft);
        } catch(IOException problem) { draftError="Draft is recoverable; projection unavailable: "+problem.getMessage(); }
    }

    private static void atomicWrite(File file, String value) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try { out.write(value.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
        finally { out.close(); }
        if (!temp.renameTo(file)) throw new IOException("Cannot publish " + file.getName());
    }

    private static String readUtf8(File file, int maxBytes) throws IOException {
        if (maxBytes >= 0 && file.length() > maxBytes) throw new IOException("File exceeds " + maxBytes + " bytes");
        FileInputStream input = new FileInputStream(file);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            for (int size; (size = input.read(buffer)) != -1;) {
                if (maxBytes >= 0 && bytes.size() + size > maxBytes) throw new IOException("File exceeds " + maxBytes + " bytes");
                bytes.write(buffer, 0, size);
            }
        } finally { input.close(); }
        return decode(bytes.toByteArray());
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void mkdir(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw new IOException("Cannot create " + directory);
    }
    private static String clean(String value) { return value == null ? "" : value; }
    private static Map<String, Object> copy(Map<String, Object> original) {
        Map<String,Object> result=new LinkedHashMap<String,Object>(original);
        Map<String,Object> triage=Json.obj(original.get("triage"));
        result.put("triage",triage==null?MessageTriage.classify(Json.str(original,"text")):
                new LinkedHashMap<String,Object>(triage));
        return result;
    }
}
