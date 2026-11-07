package com.trough.hot;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trough's own UI over an Agent (any backend: claude, codex, ...): native views, no HTML, no WebView. Forge's dock is the spec:
 * a feed of the conversation (live text, compact tool lines, inline permission cards), a status
 * strip, and a native editor that sends after a typing pause.
 */
final class Chat implements Agent.Listener {
    private static final int BG = 0xFF0B0F0D, FG = 0xFFE8EFE6, DIM = 0xFF8FA394, USER = 0xFF9FD3A8,
        TOOL = 0xFF7FA7C9, WARN = 0xFFE0B26A, PANEL = 0xFF151B18;

    private final android.content.Context c;   // the context views were built with: the first window, or the application
    private final FrameLayout root;
    private final LinearLayout gameDock;
    private final View.OnLayoutChangeListener keyboardLayout;
    private Activity a;   // the current window or null: attach() replaces it, the views stay
    private final ArrayList<String[]> log = new ArrayList<>();   // the feed as data {kind, text}: handed to a successor
    private final BuildArtwork surfaceArt;
    private final VisualSteering visualSteering;
    private final ForgeKeyboard keyboard;
    private boolean surfaceQueued;
    private final Runnable surfaceRefresh=new Runnable() { public void run() {
        surfaceQueued=false;
        if(!closed) refreshSurface();
    }};
    private BuildProgress workVisual;
    private android.app.Dialog workDialog;
    private Map<String,Object> workSnapshot;
    private final ArrayList<Object> workChecks=new ArrayList<>(), workFiles=new ArrayList<>();
    private final StringBuilder workOutput=new StringBuilder();
    private long workStarted;
    private int repairStart = -1;
    private int liveEntry = -1;      // log index of the streaming assistant text
    private final File home;
    private final Runnable reload;   // the boot's: rebuild Trough from files/src and swap it in
    private final TranscriptFeed feed;
    private final ArrayList<TextView> retainedEntries;
    private int retainedEntryCount;
    private long restoreDurationMs;
    private static final int HISTORY_LIMIT=80, HISTORY_TAG=0x7f0d7491, ENTRY_SOURCE_TAG=0x7f0d74eb;
    private boolean browsingHistory;
    private int historyStart,historyEnd;
    private Button olderHistory,newerHistory;
    private final View.OnAttachStateChangeListener rootAttachment=new View.OnAttachStateChangeListener() {
        public void onViewAttachedToWindow(View view) { if(!closed)startStatusTicker(); }
        public void onViewDetachedFromWindow(View view) { MAIN.removeCallbacks(statusTick); }
    };
    private int historySize(){return transcriptPrefix.size()+log.size();}
    private String[] historyRow(int i){return i<transcriptPrefix.size()?transcriptPrefix.get(i):log.get(i-transcriptPrefix.size());}
    private void historyButtons(){
        if(olderHistory!=null){olderHistory.setEnabled(historyStart>0);olderHistory.setText(historyStart>0?"↑ Earlier ("+historyStart+")":"Start of chat");}
        if(newerHistory!=null)newerHistory.setEnabled(browsingHistory);
    }
    void jumpToBottom(){
        browsingHistory=false;followTail=true;root.setTag(0x7f0d7483,Boolean.FALSE);
        showHistory(historySize());follow();
    }
    private void showHistory(int end){
        historyEnd=Math.min(historySize(),Math.max(0,end));historyStart=Math.max(0,historyEnd-HISTORY_LIMIT);
        java.util.HashMap<Integer,TextView> reusable=new java.util.HashMap<Integer,TextView>();
        if(retainedEntries!=null)for(TextView item:retainedEntries)if(item.getTag(HISTORY_TAG) instanceof Integer)reusable.put((Integer)item.getTag(HISTORY_TAG),item);
        for(int i=feed.getChildCount()-1;i>=0;i--){View item=feed.getChildAt(i);if(item.getTag(HISTORY_TAG) instanceof Integer){if(item instanceof TextView)reusable.put((Integer)item.getTag(HISTORY_TAG),(TextView)item);feed.removeViewAt(i);}}
        for(int i=historyStart,at=0;i<historyEnd;i++){
            String[] row=historyRow(i);TextView v=reusable.get(Integer.valueOf(i));
            if(v!=null&&!TranscriptRowIdentity.matches(v.getTag(ENTRY_SOURCE_TAG),row[0],row[1]))v=null;
            if(live!=null&&i==transcriptPrefix.size()+liveEntry)v=live;
            else if(v==null)v=entry(row[0],row[1]);
            else {retainedEntryCount++;String kind=row[0].startsWith("work_")?row[0].substring(5):row[0];v.setOnClickListener(null);bindToolDetails(v,kind,"user".equals(kind)?NativeTraceRenderer.visibleUser(row[1]):row[1],false);if(isAssistantKind(kind)||"user".equals(kind))styleProse(v,"user".equals(kind));}
            if(v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);
            v.setTag(HISTORY_TAG,Integer.valueOf(i));feed.addView(v,at++);
        }
        historyButtons();feed.viewportChanged();
    }
    private void trimHistory(){
        int count=0;for(int i=0;i<feed.getChildCount();i++)if(feed.getChildAt(i).getTag(HISTORY_TAG) instanceof Integer)count++;
        for(int i=0;count>HISTORY_LIMIT&&i<feed.getChildCount();){
            View v=feed.getChildAt(i);
            if(v.getTag(HISTORY_TAG) instanceof Integer&&v!=live){feed.removeViewAt(i);count--;}else i++;
        }
        historyEnd=historySize();historyStart=Math.max(0,historyEnd-HISTORY_LIMIT);historyButtons();
    }
    private final ScrollView scroll;
    private final TextView status;
    private final HalfSwordProgress halfSwordProgress;
    private long turnStartedAt,lastAgentActivityAt,lastUserActivityAt,turnFinishedAt,turnLastDuration;
    private String turnOutcome="idle";
    private WorkPolicy workPolicy=new WorkPolicy(android.os.SystemClock.elapsedRealtime());
    private boolean workStateSaving,routeUpgraded,backgroundDispatching,backgroundYielded,inboxLoaded;
    private String selectedRoute="",selectedEffort="",activeWorkId="",workPolicyError="";
    private long observedUserActivity=-1;
    private int backgroundStartLogIndex;
    private final java.util.LinkedHashMap<String,String> backgroundWires=new java.util.LinkedHashMap<String,String>();
    private final Runnable saveWorkState=new Runnable() { public void run() { persistWorkPolicy(null); }};
    private Runnable outputSelection;
    private void pollOutputSelection(){
        File request=new File(home,".trough/output-tab-request.json");if(outputSelection==null||!request.isFile())return;
        try{if(request.length()>256)throw new IllegalArgumentException("Output selection size");org.json.JSONObject value=new org.json.JSONObject(new String(Payloads.readSmall(request),"UTF-8"));String tab=value.getString("tab");long age=System.currentTimeMillis()-value.getLong("requested_ms");if(age<0||age>30000||!("work".equals(tab)||"thought".equals(tab)))throw new IllegalArgumentException("Output selection expired or invalid");if(!request.delete())return;root.setTag(0x7f0d74d0,tab);outputSelection.run();}catch(Exception e){request.delete();report("output selection",e.getClass().getSimpleName());}
    }
    private ProjectQuestionPin projectQuestionPin;
    private LinearLayout projectQuestionColumn;
    private void pollProjectQuestion(){
        if(projectQuestionPin!=null){projectQuestionPin.refresh();int inset=projectQuestionPin.getVisibility()==View.VISIBLE?dp(36):0;if(projectQuestionColumn!=null&&projectQuestionColumn.getPaddingTop()!=inset)projectQuestionColumn.setPadding(0,inset,0,0);}
    }
    private void pollProjectWorker(){
        if(inboxBridge==null||agents==null||cwd==null||env==null||agents.get("codex")==null)return;
        try { Map<String,Object> settings=codexSettings();
        ProjectWorker.poll(root,home,cwd,env,agents.get("codex"),Json.str(settings,"model"),Json.str(settings,"effort"),new java.lang.reflect.InvocationHandler(){
            public Object invoke(Object proxy,java.lang.reflect.Method method,Object[] args){
                if(closed)return null;
                if("claim".equals(args[0]))inboxBridge.transitionIf((String)args[1],"queued","sending","Dedicated project transport dispatch",(Runnable)args[2],(Runnable)args[3],(Runnable)args[3]);
                else if("transition".equals(args[0]))inboxBridge.transition((String)args[1],(String)args[2],(String)args[3],null);
                return null;
            }
        },inboxSnapshot);
        }catch(Exception e){android.util.Log.e("ProjectWorker","Poll",e);}
    }
    private final Runnable statusTick=new Runnable() { public void run() {
        if(closed || a==null) return;
        LocalGpuProbe.poll(a,home);
        LocalInferenceProbe.poll(a,home);
        LocalInferenceChild.poll(a,home);
        pollProjectWorker();
        pollProjectQuestion();
        focusHalfSwordRequest();
        if(!root.isAttachedToWindow()){MAIN.postDelayed(this,1000);return;}
        pollOutputSelection();
        HalfSwordPresentation.poll(root,a);
        if(root.getWindowVisibility()==View.VISIBLE && root.isShown()) { paintStatus(); presentHalfSwordRequest(); }
        MAIN.postDelayed(this,1000);
    }};
    private final PauseEditor input;
    private final Button stop,hold;
    private Button keyboardToggle;
    private LinearLayout historyBar;
    private boolean keyboardCollapsed;
    private boolean draftForWork;
    private String thoughtDraft="",workDraft="";
    private final InboxBridge inboxBridge;
    private Map<String,Object> inboxSnapshot=Json.map("queued",0,"uncertain",0,"messages",new ArrayList<Object>());
    private boolean enqueuePending,clearingSavedDraft;
    private String activeInboxId,activeInboxWire,lastInboxError="",lastInboxWarning="",lastScheduledDraft;
    private String lastSharedAction="";
    private String lastUserPrompt="";
    private final AdaptiveHeuristic heuristic=new AdaptiveHeuristic();
    private Map<String,Object> semanticValues=new HashMap<String,Object>();
    private Map<String,Object> languageState=Json.map("ready",false);
    private boolean heuristicReady,adjustingScene;
    private long lastDirectAdjustment;
    private final ArrayList<Map<String,Object>> pendingTeaching=new ArrayList<Map<String,Object>>();
    private int outputStyleBucket=Integer.MIN_VALUE;
    private long styledWeightsVersion=-1;
    private android.app.AlertDialog learningDialog;
    private TextView learningSummary;
    private View learningWeights;
    private final Runnable saveLearning=new Runnable() { public void run() {
        if(!closed && heuristicReady) inboxBridge.saveHeuristic(heuristic.encode());
    }};
    private final Map<String,String> inboxAcknowledgements=new HashMap<String,String>();
    private final Runnable pollInbox=new Runnable() { public void run() {
        if(closed) return;
        pollConnectionRecovery();
        if(!upgradeTransportIfIdle() && !recoverStalledTransport())workTick();
        inboxBridge.poll(agent,sessionId,Json.map("protocol","trough-live-inbox-1","pid",android.os.Process.myPid(),
            "agent",agent,"session_id",sessionId,"busy",busy,"initialized",initialized,"draft_length",input.length(),
            "auto_send",true,"auto_send_armed",autoSendArmed,"auto_send_held",autoSendHeld,"auto_send_keyboard_gesture",keyboardGestureActive,"auto_send_hold_mode","press_or_tap_toggle","auto_send_latched",holdIntent.latched(),"pause_ms",AUTO_SEND_PAUSE_MS,"saving",enqueuePending,
            "window_attached",a!=null,"window_focus",input.hasWindowFocus(),"editor_focus",input.hasFocus(),
            "last_shared_action",lastSharedAction,"send_button",false,"keyboard_toolbar",false,
            "queued",inboxCount("queued"),"uncertain",inboxCount("uncertain"),"held",inboxCount("held_count"),"storage_error",lastInboxError,
            "inbox_error_protocol",3,"inbox_action_warning",lastInboxWarning,
            "elapsed_ms",turnElapsed(),"last_agent_activity_age_ms",activityAge(lastAgentActivityAt),
            "last_user_activity_age_ms",activityAge(lastUserActivityAt),"work_state",workState(),
            "turn_last_duration_ms",turnLastDuration,"visual_model",surfaceArt.renderingStats(),"adaptive",adaptiveStatus(),
            "scene_context",sceneContext(),"input_learning",keyboard.learningState(),"live_reload",Main.liveReloadState(),
            "durable_transcript_ready",transcriptReady,"durable_transcript_error",transcriptStore.error,
            "live_metrics",NativeHomePanel.liveMetrics(root),"trace_metrics",NativeTraceRenderer.metrics(),"durable_transcript_rows",transcriptPrefix.size()+log.size(),"retained_chat_rows",retainedEntryCount,"chat_restore_ms",restoreDurationMs,"transcript",feed.state(),
            "transport",session instanceof ResidentAgent?((ResidentAgent)session).state():Json.map("resident",false),
            "output_tab","work".equals(root.getTag(0x7f0d74d0))?"work":"thought","chat_viewport_height",scroll.getHeight(),"game_dock_height",gameDock.getHeight(),"keyboard_collapsed",keyboardCollapsed,"keyboard",keyboard.state(),"game",GameInlineProbe.state(root),"media",MediaPanel.state(root),"dogshit",languageState,"codex",codexState(),"work_policy",workPolicyStatus(),"power",Power.state()));
        MAIN.postDelayed(this,450);
    }};
    private final Runnable saveDraft=new Runnable() { public void run() { persistDraft(); }};
    private boolean composerReady, suppressAutoSend, autoSendArmed, autoSendHeld, keyboardGestureActive;
    private int holdPointer=-1;
    private final HoldIntent holdIntent=new HoldIntent();
    private float holdStartX;
    private float holdStartY,holdOriginX,holdOriginY;
    private long draftEditedAt;
    private static final long AUTO_SEND_PAUSE_MS=1900;
    private final Runnable autoSubmit=new Runnable() { public void run() {
        if(closed || autoSendHeld || keyboardGestureActive || !autoSendArmed) return;
        long remaining=AUTO_SEND_PAUSE_MS-(android.os.SystemClock.uptimeMillis()-draftEditedAt);
        if(remaining>0) { MAIN.postDelayed(this,remaining); return; }
        // Never send while the app is hidden, a finger is down, or an IME owns unfinished text.
        if(!AutoSendVisibility.allows(input.isShown(),new File(a.getFilesDir(),"home/.trough/keyboard-only").isFile() || new File(a.getFilesDir(),"home/.trough/visual-only").isFile(),root.isShown(),keyboard.isShown(),root.getWindowVisibility()==View.VISIBLE)
            || keyboard.hasPendingPress() || android.view.inputmethod.BaseInputConnection.getComposingSpanStart(input.getText())>=0) {
            MAIN.postDelayed(this,300); return;
        }
        String text=input.getText().toString();
        if(text.trim().isEmpty()) { autoSendArmed=false; input.clearCountdown(); return; }
        if(enqueuePending) { MAIN.postDelayed(this,100); return; }
        // Credentials and slash commands wait for a deliberate newline; prose needs only a pause.
        if((login!=null || text.trim().startsWith("/")) && !text.endsWith("\n")) return;
        autoSendArmed=false; input.clearCountdown(); submit();
    }};
    private boolean followTail=true, touchingScroll, followQueued, streamQueued;
    private StringBuilder liveSource;
    private final StringBuilder pendingDelta=new StringBuilder();
    private final Runnable renderStream=new Runnable() { public void run() {
        streamQueued=false; if(!closed) flushStream(false);
    }};
    private Agent session;
    private Map<String,Object> residentTicket;
    private boolean handingOff;
    private final Agent.SendHandoff sendHandoff = new Agent.SendHandoff();
    private ClaudeAgent.Login login;   // a /login in progress: the next message is its code
    private int generation;          // events from a replaced agent are dropped
    private Map<String, String> agents = new java.util.LinkedHashMap<>();   // name -> executable
    private final TranscriptStore transcriptStore;
    private boolean transcriptReady,transcriptLoading;
    private final ArrayList<String[]> transcriptPrefix=new ArrayList<String[]>();
    private final Runnable checkpointTranscript=new Runnable(){public void run(){persistTranscript();}};
    private void persistTranscript() {
        if(!transcriptReady || sessionId==null || sessionId.isEmpty())return;
        ArrayList<String[]> entries=new ArrayList<String[]>(transcriptPrefix);
        entries.addAll(log);
        if(liveSource!=null && liveEntry>=0 && liveEntry<log.size())
            entries.set(transcriptPrefix.size()+liveEntry,new String[]{log.get(liveEntry)[0],liveSource.toString()});
        transcriptStore.save(sessionId,entries);
    }
    private void queueTranscript() {
        MAIN.removeCallbacks(checkpointTranscript);MAIN.postDelayed(checkpointTranscript,250);
    }
    private boolean restoreTranscript() {
        if(transcriptReady)return true;
        if(transcriptLoading)return false;
        transcriptLoading=true;
        String id="";
        try{if(sessionFile().isFile())id=new String(Payloads.readSmall(sessionFile()),"UTF-8").trim();}catch(Exception ignored){}
        final String savedSession=id;
        transcriptStore.load(id,new TranscriptStore.Loaded(){public void loaded(List<String[]> saved){
            if(closed)return;
            java.util.HashSet<List<String>> present=new java.util.HashSet<List<String>>();
            for(String[] row:log)present.add(TranscriptStore.rowKey(row));
            final ArrayList<String[]> missing=new ArrayList<String[]>();
            for(String[] row:saved)if(!present.contains(TranscriptStore.rowKey(row)))missing.add(row);
            final int first=Math.max(0,missing.size()-120);
            transcriptPrefix.addAll(missing.subList(0,first));
            final ArrayList<String[]> restore=new ArrayList<String[]>(missing.subList(first,missing.size()));
            if(liveEntry>=0)liveEntry+=restore.size();
            log.addAll(0,restore);
            for(int i=0;i<feed.getChildCount();i++)if(feed.getChildAt(i) instanceof TextView)feed.getChildAt(i).setTag(HISTORY_TAG,Integer.valueOf(-1));
            transcriptReady=true;transcriptLoading=false;
            if(sessionId==null && !savedSession.isEmpty())sessionId=savedSession;
            showHistory(historySize());persistTranscript();follow();
            if(agents!=null && session==null)start(agents,cwd,env);
        }});
        return false;
    }
    private String agent = "claude";
    private File cwd;
    private Map<String, String> env;
    private TextView live;          // the assistant text being streamed
    private boolean busy, resumed, stopping;   // resumed: built from a predecessor's handoff (no "starting" note)
    private Runnable stopFallback;
    private boolean initialized, authRequired;
    private final java.util.HashSet<String> pendingPermissions = new java.util.HashSet<>();
    private final Map<String,Map<String,Object>> permissionRequests=new HashMap<String,Map<String,Object>>();
    private final Map<String,Map<String,Object>> carriedPermissions=new HashMap<String,Map<String,Object>>();
    private final Map<String, android.app.AlertDialog> cosmicApprovals = new HashMap<>();
    private boolean reopenCosmic, closed;
    private String lastError = "";
    private int connectionRetries;
    private long connectionReadyAt, connectionRetryAt;
    private Runnable connectionRetry, connectionDeadline;
    private void cancelConnectionTimers() {
        if(connectionRetry!=null) MAIN.removeCallbacks(connectionRetry);
        if(connectionDeadline!=null) MAIN.removeCallbacks(connectionDeadline);
        connectionRetry=null; connectionDeadline=null; connectionRetryAt=0;
    }
    private void retryConnection() {
        if(closed || !"codex".equals(agent) || authRequired || login!=null || !pendingPermissions.isEmpty() || connectionRetry!=null) return;
        long now=android.os.SystemClock.elapsedRealtime();
        if(connectionReadyAt>0 && now-connectionReadyAt>=60000) connectionRetries=0;
        connectionReadyAt=0;
        final int expected=generation;
        long delay=ConnectionRecovery.retryDelay(connectionRetries);
        connectionRetryAt=now+delay;
        connectionRetries=Math.min(connectionRetries+1,6);
        connectionRetry=new Runnable(){public void run(){
            if(connectionRetry!=this)return;
            connectionRetry=null;connectionRetryAt=0;
            if(!closed && generation==expected && session==null && !authRequired && login==null && pendingPermissions.isEmpty()) {
                report("recovery","Reconnecting transport to saved conversation; no message replay"); connect();
            }
        }};
        MAIN.postDelayed(connectionRetry,delay);
    }
    private void pollConnectionRecovery() {
        if(closed || initialized || session!=null) return;
        long now=android.os.SystemClock.elapsedRealtime();
        if(connectionRetry!=null && connectionRetryAt>0 && now-connectionRetryAt>=5000)
            cancelConnectionTimers();
        if(connectionRetry==null) retryConnection();
    }
    private void reconnectAfterNetworkFailure() {
        cancelConnectionTimers();
        Agent failed=session; ++generation;session=null;initialized=false;
        selectedRoute="";selectedEffort="";
        try { if(failed!=null) failed.close(); } catch(RuntimeException closeError) {report("recovery","Transport close failed; retry remains scheduled");}
        retryConnection();
    }
    private String model = "", sessionId;
    private double cost;

    /** state: a predecessor's handoff() (feed, draft, scroll, agent), or null for a fresh start. */
    Chat(android.content.Context ctx, Activity window, FrameLayout root, File home, Runnable reload, Map<?, ?> state) {
        this(ctx,window,root,home,reload,state,null);
    }
    Chat(android.content.Context ctx, Activity window, FrameLayout root, File home, Runnable reload, Map<?, ?> state,File visualModel) {
        // Views take the window as context when there is one (IME and popups are proven that way);
        // without a window, the application.
        c = window != null ? window : ctx.getApplicationContext();
        this.root=root;
        root.setMotionEventSplittingEnabled(true);
        retainedEntries=reusableEntries(root,state);
        a = window;
        this.home = home;
        try{File f=new File(home,".trough/output-draft-thought.txt");if(f.isFile())thoughtDraft=new String(Payloads.readSmall(f),"UTF-8");f=new File(home,".trough/output-draft-work.txt");if(f.isFile())workDraft=new String(Payloads.readSmall(f),"UTF-8");}catch(Exception ignored){}
        if(state!=null){if(state.get("thoughtDraft") instanceof String)thoughtDraft=(String)state.get("thoughtDraft");if(state.get("workDraft") instanceof String)workDraft=(String)state.get("workDraft");}

        transcriptStore=new TranscriptStore(home,new java.util.concurrent.Executor(){public void execute(Runnable task){MAIN.post(task);}});
        transcriptReady=state!=null && Boolean.TRUE.equals(state.get("transcript_ready"));
        if(state!=null && state.get("transcript_prefix") instanceof List)
            for(Object row:(List<?>)state.get("transcript_prefix"))if(row instanceof String[])transcriptPrefix.add(((String[])row).clone());
        loadWorkPolicy(state);
        this.reload = reload;
        surfaceArt=new BuildArtwork(c);
        surfaceArt.setLearningListener(new BuildArtwork.LearningListener() {
            public void adjust(String axis,float value,String source) { teachScene(axis,value,source); }
        });
        visualSteering=new VisualSteering(visualModel,NativeDogshit.open(c,root),new VisualSteering.Listener() {
            public void ready(Map<String,Object> material) {
                if(closed) return;
                Map<String,Object> language=Json.obj(material.get("dogshit"));if(language!=null) languageState=language;
                Map<String,Object> steering=Json.obj(material.get("steering"));
                Map<String,Object> values=steering==null?null:Json.obj(steering.get("values"));
                if(values!=null) semanticValues=new HashMap<String,Object>(values);
                applyScene(material);
            }
        });
        root.addView(surfaceArt,new FrameLayout.LayoutParams(-1,-1));
        if (a != null) a.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        Power.start(c, a, home);
        LinearLayout col = new LinearLayout(c);
        projectQuestionColumn=col;
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        col.setFitsSystemWindows(true);

        status = text(DIM, 12);
        status.setLines(1);
        status.setTypeface(Typeface.MONOSPACE);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(dp(10), dp(3), dp(10), dp(3));
        status.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if(!pendingPermissions.isEmpty()) {
                    for(android.app.AlertDialog approval:cosmicApprovals.values()) { approval.show(); return; }
                }
                pickAgent(v);
            }
        });
        status.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View view) { showLearning(); return true; }
        });
        col.addView(status);
        projectQuestionPin=new ProjectQuestionPin(c,home);
        col.setPadding(0,projectQuestionPin.getVisibility()==View.VISIBLE?dp(36):0,0,0);
        col.addView(new EpicAccessPin(c,home),new LinearLayout.LayoutParams(-1,-2));
        col.addView(new GeminiAccessPin(c,home),new LinearLayout.LayoutParams(-1,-2));
        halfSwordProgress=new HalfSwordProgress(c,root,home,new Runnable(){public void run(){openHalfSwordSimulator();}});
        halfSwordProgress.setOutputRenderer(new HalfSwordProgress.OutputRenderer(){public TextView render(String kind,String text){return entry(kind,text);}});
        col.addView(halfSwordProgress,new LinearLayout.LayoutParams(-1,-2));
        root.setTag(HalfSwordView.CHAT_STATUS,status);
        root.setTag(HalfSwordView.CHAT_HEADER,halfSwordProgress);
        historyBar=new LinearLayout(c);
        olderHistory=button("↑ Earlier",PANEL,DIM);newerHistory=button("Newer ↓",PANEL,DIM);
        Button bottom=button("Bottom ↓",PANEL,FG);
        historyBar.addView(olderHistory,new LinearLayout.LayoutParams(0,dp(28),1));
        historyBar.addView(newerHistory,new LinearLayout.LayoutParams(0,dp(28),1));
        historyBar.addView(bottom,new LinearLayout.LayoutParams(0,dp(28),1));
        olderHistory.setOnClickListener(new View.OnClickListener(){public void onClick(View v){browsingHistory=true;followTail=false;showHistory(Math.max(1,historyStart+12));scroll.post(new Runnable(){public void run(){scroll.scrollTo(0,0);}});}});
        newerHistory.setOnClickListener(new View.OnClickListener(){public void onClick(View v){int end=Math.min(historySize(),historyEnd+HISTORY_LIMIT-12);if(end==historySize())jumpToBottom();else {showHistory(end);scroll.post(new Runnable(){public void run(){scroll.scrollTo(0,0);}});}}});
        bottom.setOnClickListener(new View.OnClickListener(){public void onClick(View v){jumpToBottom();}});
        for(int n=0;n<historyBar.getChildCount();n++)if(historyBar.getChildAt(n) instanceof Button){Button control=(Button)historyBar.getChildAt(n);control.setMinHeight(0);control.setMinimumHeight(0);control.setPadding(0,0,0,0);control.setTextSize(11);}
        col.addView(historyBar);
        gameDock=new LinearLayout(c);
        gameDock.setOrientation(LinearLayout.VERTICAL);
        final ScrollView gameViewport=new ScrollView(c) {
            @Override protected void onMeasure(int widthSpec,int heightSpec) {
                historyBar.setVisibility(gameDock.getChildCount()>0?View.GONE:View.VISIBLE);
                int available=View.MeasureSpec.getSize(heightSpec);
                int window=root.getHeight();
                int limit=available>0?available:Math.max(dp(72),(int)(window*.3f));
                if(available>0) limit=Math.min(limit,available);
                super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST));
            }
        };
        gameViewport.setVisibility(View.GONE);
        gameDock.setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener(){
            public void onChildViewAdded(View parent,View child){gameViewport.setVisibility(View.GONE);}
            public void onChildViewRemoved(View parent,View child){if(gameDock.getChildCount()==0)gameViewport.setVisibility(View.GONE);}
        });
        gameViewport.setFillViewport(false);
        gameViewport.setContentDescription("Game panel; scroll for controls");
        gameViewport.addView(gameDock,new ScrollView.LayoutParams(-1,-2));
        final LinearLayout conversationPanes=new LinearLayout(c);
        conversationPanes.setOrientation(LinearLayout.HORIZONTAL);root.setTag(0x7f0d74d3,conversationPanes);
        conversationPanes.addView(gameViewport,new LinearLayout.LayoutParams(0,-1,1));

        scroll = new ScrollView(c) {
            boolean gameGesture;
            @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
                return gameGesture ? false : super.onInterceptTouchEvent(event);
            }
            @Override public boolean onTouchEvent(android.view.MotionEvent event) {
                return gameGesture || super.onTouchEvent(event);
            }
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                int action=event.getActionMasked();
                if(action==android.view.MotionEvent.ACTION_DOWN) {
                    Object retained=getRootView().getTag(0x7f0d7498);
                    gameGesture=false;
                    if(retained instanceof android.view.ViewGroup) {
                        android.view.ViewGroup card=(android.view.ViewGroup)retained;
                        for(int i=0;i<card.getChildCount();i++) {
                            View child=card.getChildAt(i);
                            if(child instanceof android.view.SurfaceView) {
                                android.graphics.Rect bounds=new android.graphics.Rect();
                                int[] screen=new int[2];getLocationOnScreen(screen);
                                gameGesture=child.getGlobalVisibleRect(bounds) && bounds.contains(
                                    (int)(event.getX()+screen[0]),(int)(event.getY()+screen[1]));
                                break;
                            }
                        }
                    }
                }
                if(gameGesture) {
                    requestDisallowInterceptTouchEvent(true);
                    boolean handled=super.dispatchTouchEvent(event);
                    if(action==android.view.MotionEvent.ACTION_UP || action==android.view.MotionEvent.ACTION_CANCEL) {
                        gameGesture=false;requestDisallowInterceptTouchEvent(false);
                    }
                    return handled;
                }
                if(event.getPointerCount()==1 && !adjustingScene
                    && (action==android.view.MotionEvent.ACTION_DOWN || action==android.view.MotionEvent.ACTION_MOVE
                        || action==android.view.MotionEvent.ACTION_UP))
                    surfaceArt.pointer(event.getX(),event.getY()+getTop(),action==android.view.MotionEvent.ACTION_DOWN);
                if(event.getPointerCount()>=2 || adjustingScene) {
                    if(!adjustingScene) {
                        android.view.MotionEvent cancel=android.view.MotionEvent.obtain(event);
                        cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);cancel.recycle();
                    }
                    if(routeSceneGesture(event)) return true;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        scroll.setPadding(0,0,0,dp(48));
        scroll.setClipToPadding(true);
        scroll.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, android.view.MotionEvent event) {
                int action=event.getActionMasked();
                if(action==android.view.MotionEvent.ACTION_DOWN) { touchingScroll=true; lastUserActivityAt=android.os.SystemClock.elapsedRealtime(); }
                if(action==android.view.MotionEvent.ACTION_UP || action==android.view.MotionEvent.ACTION_CANCEL) {
                    touchingScroll=false; followTail=!scroll.canScrollVertically(1);
                }
                return false;
            }
        });
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            public void onScrollChange(View view,int x,int y,int oldX,int oldY) {
                if(feed!=null) feed.viewportChanged();
                if(touchingScroll || !scroll.canScrollVertically(1)) followTail=!scroll.canScrollVertically(1);
            }
        });
        scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View view,int l,int t,int r,int b,int ol,int ot,int or,int ob) {
                surfaceArt.contentBounds(t,b);
                if(feed!=null) feed.viewportChanged();
                if(b-t!=ob-ot) follow();
            }
        });
        feed = new TranscriptFeed(c);
        feed.setOrientation(LinearLayout.VERTICAL);
        feed.setPadding(dp(14), dp(4), dp(14), dp(12));
        feed.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View view,int l,int t,int r,int b,int ol,int ot,int or,int ob) {
                if(b-t!=ob-ot) follow();
            }
        });
        scroll.addView(feed);
        LinearLayout outputPane=new LinearLayout(c);outputPane.setOrientation(LinearLayout.VERTICAL);
        LinearLayout outputTabs=new LinearLayout(c);
        final Button workTab=button("Half Sword",PANEL,FG),thoughtTab=button("Chat",PANEL,FG);
        outputTabs.addView(workTab,new LinearLayout.LayoutParams(0,dp(34),1));outputTabs.addView(thoughtTab,new LinearLayout.LayoutParams(0,dp(34),1));outputPane.addView(outputTabs,new LinearLayout.LayoutParams(-1,-2));
        FrameLayout outputStack=new FrameLayout(c);final FrameLayout workLane=new FrameLayout(c),thoughtLane=new FrameLayout(c);
        ScrollView workViewport=halfSwordProgress.detachOutputViewport();workViewport.setBackgroundColor(android.graphics.Color.TRANSPARENT);workLane.addView(workViewport,new FrameLayout.LayoutParams(-1,-1));thoughtLane.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        outputStack.addView(workLane,new FrameLayout.LayoutParams(-1,-1));outputStack.addView(thoughtLane,new FrameLayout.LayoutParams(-1,-1));outputPane.addView(outputStack,new LinearLayout.LayoutParams(-1,0,1));
        if(state!=null&&state.get("outputTab") instanceof String)root.setTag(0x7f0d74d0,state.get("outputTab"));
        final Runnable chooseOutput=new Runnable(){public void run(){boolean work="work".equals(root.getTag(0x7f0d74d0));if(input!=null&&work!=draftForWork){if(enqueuePending){root.setTag(0x7f0d74d0,draftForWork?"work":"thought");return;}if(draftForWork)workDraft=input.getText().toString();else thoughtDraft=input.getText().toString();MAIN.removeCallbacks(autoSubmit);autoSendArmed=false;input.clearCountdown();suppressAutoSend=true;try{input.setText(work?workDraft:thoughtDraft);input.setSelection(input.length());}finally{suppressAutoSend=false;}lastScheduledDraft=null;}draftForWork=work;workLane.setVisibility(work?View.VISIBLE:View.GONE);thoughtLane.setVisibility(work?View.GONE:View.VISIBLE);historyBar.setVisibility(work?View.GONE:View.VISIBLE);workTab.setSelected(work);thoughtTab.setSelected(!work);workTab.setText(work?"Half Sword ✓":"Half Sword");thoughtTab.setText(work?"Chat":"Chat ✓");}};outputSelection=chooseOutput;
        workTab.setOnClickListener(new View.OnClickListener(){public void onClick(View v){root.setTag(0x7f0d74d0,"work");chooseOutput.run();}});thoughtTab.setOnClickListener(new View.OnClickListener(){public void onClick(View v){root.setTag(0x7f0d74d0,"thought");chooseOutput.run();}});chooseOutput.run();
        conversationPanes.addView(outputPane,new LinearLayout.LayoutParams(0,-1,2));
        col.addView(conversationPanes,new LinearLayout.LayoutParams(-1,0,1));

        final LinearLayout dock = new LinearLayout(c);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.BOTTOM);
        dock.setPadding(dp(6), dp(2), dp(6), dp(2));
        input = new PauseEditor(c);
        root.setTag(DogshitPlayer.CHAT_INPUT,input);
        input.setTextColor(FG);
        input.setHintTextColor(DIM);
        input.setHint("Message…");
        input.setContentDescription("Message. Glide through deliberate letter targets. A clear turn near a key selects it after movement confirms the turn; lifting selects your last key. Straight travel across other keys and small finger wobbles are ignored. Draw a small loop on a letter to repeat it. No automatic spaces or word guessing. Hold period for capitals, symbols, spaces, new lines and deletion. Autosend pauses through a drag and while the editing menu is open; release starts a fresh 1.9 second countdown. End slash commands with a newline.");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setMaxLines(2);
        input.setEnabled(true);
        input.setFocusableInTouchMode(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView view,int action,android.view.KeyEvent event) {
                if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { submit(); return true; }
                return false;
            }
        });
        input.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View view,int key,android.view.KeyEvent event) {
                if(key==android.view.KeyEvent.KEYCODE_ENTER && event.isCtrlPressed()) {
                    if(event.getAction()==android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount()==0) submit();
                    return true;
                }
                return false;
            }
        });
        input.setBackground(round(PANEL, 18));
        input.setPadding(dp(10), dp(5), dp(10), dp(5));input.setMinHeight(0);input.setMinimumHeight(0);
        input.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text,int start,int count,int after) {}
            public void afterTextChanged(android.text.Editable text) {}
            public void onTextChanged(CharSequence text,int start,int before,int count) {
                queueSurfaceRefresh();
                if(composerReady && !suppressAutoSend) {
                    if(!clearingSavedDraft && !keyboard.isRefiningSwipe()) lastUserActivityAt=android.os.SystemClock.elapsedRealtime();
                    if(!clearingSavedDraft) {
                        surfaceArt.typing(text.toString()); input.typingPulse(count);
                    }
                    if(!keyboard.isRefiningSwipe()) queueAutoSubmit();
                    MAIN.removeCallbacks(saveDraft); MAIN.postDelayed(saveDraft,180);
                }
            }
        });
        dock.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        input.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View view,int l,int t,int r,int b,int ol,int ot,int or,int ob) {
                int[] at=new int[2],base=new int[2]; input.getLocationInWindow(at);root.getLocationInWindow(base);
                surfaceArt.inputBounds(at[0]-base[0],at[1]-base[1],at[0]-base[0]+r-l,at[1]-base[1]+b-t);
            }
        });
        hold=new Button(c) {
            @Override public void onWindowFocusChanged(boolean focused) {
                super.onWindowFocusChanged(focused);
                if(!focused) releaseAutoSendHold(false);
            }
            @Override protected void onDetachedFromWindow() {
                releaseAutoSendHold(false);
                super.onDetachedFromWindow();
            }
        };
        hold.setText("HOLD");hold.setTextColor(DIM);hold.setTextSize(TypedValue.COMPLEX_UNIT_SP,11);
        hold.setAllCaps(false);hold.setMinWidth(0);hold.setMinimumWidth(0);hold.setPadding(0,0,0,0);
        hold.setBackground(round(PANEL,14));
        hold.setContentDescription("Tap briefly to latch autosend pause; tap again to release. Press and hold for a temporary pause. Slide upward while holding to stir an ink constellation.");
        hold.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view,android.view.MotionEvent event) {
                int action=event.getActionMasked(),index=event.getActionIndex();
                if(action==android.view.MotionEvent.ACTION_DOWN) {
                    if(holdPointer<0) {
                        holdStartY=event.getY(index);holdStartX=event.getX(index);
                        holdIntent.begin(android.os.SystemClock.uptimeMillis());
                        int[] at=new int[2],base=new int[2];view.getLocationInWindow(at);root.getLocationInWindow(base);
                        holdOriginX=at[0]-base[0];holdOriginY=at[1]-base[1];
                        beginAutoSendHold(event.getPointerId(index));
                    }
                } else if(action==android.view.MotionEvent.ACTION_CANCEL) releaseAutoSendHold(false);
                else if(action==android.view.MotionEvent.ACTION_UP || action==android.view.MotionEvent.ACTION_POINTER_UP) {
                    if(holdPointer==event.getPointerId(index)) {
                        if(Math.abs(event.getX(index)-holdStartX)>android.view.ViewConfiguration.get(c).getScaledTouchSlop()
                            || Math.abs(event.getY(index)-holdStartY)>android.view.ViewConfiguration.get(c).getScaledTouchSlop()) holdIntent.moved();
                        view.performClick();
                    }
                } else if(action==android.view.MotionEvent.ACTION_MOVE && holdPointer>=0) {
                    int owner=event.findPointerIndex(holdPointer);
                    if(owner<0) releaseAutoSendHold(false);
                    else {
                        if(Math.abs(event.getX(owner)-holdStartX)>android.view.ViewConfiguration.get(c).getScaledTouchSlop()
                            || Math.abs(event.getY(owner)-holdStartY)>android.view.ViewConfiguration.get(c).getScaledTouchSlop()) holdIntent.moved();
                        boolean starting=!surfaceArt.isPlaying() && event.getY(owner)<holdStartY-dp(24);
                        if(starting) surfaceArt.setPlaying(true);
                        if(surfaceArt.isPlaying()) surfaceArt.playAt(holdOriginX+event.getX(owner),holdOriginY+event.getY(owner),starting);
                    }
                }
                return true;
            }
        });
        // A semantic accessibility click toggles the same intentional latch.
        hold.setOnClickListener(new View.OnClickListener() { public void onClick(View view) {
            if(holdPointer>=0) holdIntent.release(android.os.SystemClock.uptimeMillis());else holdIntent.toggle();
            releaseAutoSendHold(true);
        }});
        LinearLayout.LayoutParams holdLp=new LinearLayout.LayoutParams(dp(46),dp(36));holdLp.leftMargin=dp(6);
        dock.addView(hold,holdLp);
        stop=new Button(c); stop.setText("■"); stop.setTextColor(DIM); stop.setAllCaps(false);
        stop.setContentDescription("Interrupt this response; project work continues");
        stop.setMinWidth(0); stop.setMinimumWidth(0); stop.setPadding(0,0,0,0);
        stop.setBackground(round(PANEL,14));
        stop.setOnClickListener(new View.OnClickListener() { public void onClick(View view) { interruptResponse(); }});
        LinearLayout.LayoutParams stopLp=new LinearLayout.LayoutParams(dp(40),dp(36)); stopLp.leftMargin=dp(6);
        dock.addView(stop,stopLp);
        keyboard=new ForgeKeyboard(c,input,new Runnable() { public void run() { submit(); }},root);
        final KeyboardDock inputLayer=new KeyboardDock(c,keyboard);
        inputLayer.setAlpha(0.68f);
        inputLayer.addView(dock);
        keyboard.setGestureListener(new ForgeKeyboard.GestureListener() {
            public void active(boolean active) { keyboardGestureChanged(active); }
        });
        keyboardToggle=new Button(c);
        keyboardToggle.setAllCaps(false);
        keyboardToggle.setTextColor(FG);
        keyboardToggle.setTextSize(12);
        keyboardToggle.setMinHeight(0);keyboardToggle.setMinimumHeight(0);
        keyboardToggle.setPadding(0,0,0,0);
        keyboardToggle.setBackground(round(PANEL,10));
        keyboardToggle.setOnClickListener(new View.OnClickListener(){public void onClick(View v){setKeyboardCollapsed(!keyboardCollapsed);}});
        LinearLayout voiceBar=new LinearLayout(c);voiceBar.setOrientation(LinearLayout.HORIZONTAL);
        voiceBar.addView(keyboardToggle,new LinearLayout.LayoutParams(0,dp(32),1));
        final Button voiceToggle=new Button(c);voiceToggle.setText("Voice off");voiceToggle.setTextSize(12);voiceToggle.setMinHeight(0);voiceToggle.setMinimumHeight(0);voiceToggle.setPadding(0,0,0,0);voiceToggle.setTextColor(DIM);voiceToggle.setBackground(round(PANEL,10));voiceToggle.setContentDescription("Toggle spoken replies");
        voiceBar.addView(voiceToggle,new LinearLayout.LayoutParams(dp(112),dp(32)));
        final Button homeToggle=new Button(c);homeToggle.setText("Home");homeToggle.setAllCaps(false);homeToggle.setTextSize(12);homeToggle.setMinHeight(0);homeToggle.setMinimumHeight(0);homeToggle.setPadding(0,0,0,0);homeToggle.setTextColor(FG);homeToggle.setBackground(round(PANEL,10));homeToggle.setContentDescription("Open Trough home screen");voiceBar.addView(homeToggle,new LinearLayout.LayoutParams(dp(72),dp(32)));homeToggle.setOnClickListener(new View.OnClickListener(){public void onClick(View view){try{Object panel=root.getTag(NativeHomePanel.TAG);if(panel instanceof NativeHomePanel){((NativeHomePanel)panel).close();return;}root.setTag(0x7f0d74d0,"thought");if(outputSelection!=null)outputSelection.run();new File(a.getFilesDir(),"home/.trough/native-home-open").createNewFile();}catch(Exception error){note("Could not open home: "+error.getClass().getSimpleName());}}});
        voiceToggle.setOnClickListener(new View.OnClickListener(){public void onClick(View view){File muted=new File(a.getFilesDir(),"home/.trough/voice-muted");try{if(muted.isFile())muted.delete();else muted.createNewFile();}catch(Exception ignored){}NativeVoice.pending(a);voiceToggle.setText(muted.isFile()?"Voice off":"Mute voice");}});
        inputLayer.addView(voiceBar,new LinearLayout.LayoutParams(-1,dp(32)));voiceBar.setVisibility(View.GONE);

        inputLayer.addView(keyboard,new LinearLayout.LayoutParams(-1,-2));
        setKeyboardCollapsed(state!=null && Boolean.TRUE.equals(state.get("keyboardCollapsed")));
        keyboardLayout=new View.OnLayoutChangeListener() {
            public void onLayoutChange(View view,int l,int t,int r,int b,int ol,int ot,int or,int ob) {
                if(!closed) {
                    keyboard.adaptHeight(root.getHeight());
                    scroll.post(new Runnable(){public void run(){
                        if(closed)return;
                        int overlap=keyboard.getHeight()+dock.getHeight();
                        scroll.setClipToPadding(true);
                        scroll.setPadding(0,0,0,dp(12));
                    }});
                }
            }
        };
        root.addOnLayoutChangeListener(keyboardLayout);
        col.addOnLayoutChangeListener(keyboardLayout);
        inputLayer.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob){if(!closed){scroll.setClipToPadding(true);scroll.setPadding(0,0,0,dp(12));for(int i=0;i<root.getChildCount();i++){View panel=root.getChildAt(i);if("half-sword-expanded".equals(panel.getTag()))HalfSwordView.layoutPanel(root,panel);}}}});
        root.addOnAttachStateChangeListener(rootAttachment);
        restore(state);
        suppressAutoSend=true;try{input.setText(draftForWork?workDraft:(thoughtDraft.isEmpty()?input.getText().toString():thoughtDraft));input.setSelection(input.length());}finally{suppressAutoSend=false;}
        composerReady=true;
        if(state!=null && input.length()>0 && (Boolean.TRUE.equals(state.get("autoSendArmed"))
            || Boolean.TRUE.equals(state.get("autoSendHeld")))) {
            autoSendArmed=true;
            Object edited=state.get("draftEditedAt");
            draftEditedAt=!Boolean.TRUE.equals(state.get("autoSendHeld")) && edited instanceof Number
                ?((Number)edited).longValue():android.os.SystemClock.uptimeMillis();
            input.countdown(draftEditedAt,AUTO_SEND_PAUSE_MS);
            MAIN.postDelayed(autoSubmit,Math.max(0,AUTO_SEND_PAUSE_MS-(android.os.SystemClock.uptimeMillis()-draftEditedAt)));
        }
        root.addView(col,new FrameLayout.LayoutParams(-1,-1));
        root.post(new Runnable(){public void run(){if(closed)return;NativeCarePanel.pending(a,root);NativeAppHostProbe.pending(a);NativeContainerProbe.pending(a);NativeHomePanel.pending(a,root,inputLayer,input);NativeHomePanel.working(root,busy,Main.liveReloadState(),workChecks,activityAge(lastAgentActivityAt));if(NativeHomePanel.wantsTrace(root))NativeHomePanel.trace(root,nativeHomeTrace());NativeVoice.pending(a);root.postDelayed(this,1000);}});
        col.addView(inputLayer,new LinearLayout.LayoutParams(-1,-2));final Button chatHomeCorner=new Button(c);chatHomeCorner.setText("⌂");chatHomeCorner.setContentDescription("Home");chatHomeCorner.setTextColor(FG);chatHomeCorner.setBackground(round(PANEL,20));FrameLayout.LayoutParams cornerLp=new FrameLayout.LayoutParams(dp(44),dp(38),android.view.Gravity.TOP|android.view.Gravity.RIGHT);cornerLp.rightMargin=dp(10);cornerLp.topMargin=dp(6);root.addView(chatHomeCorner,cornerLp);chatHomeCorner.setElevation(dp(150));chatHomeCorner.setOnClickListener(new View.OnClickListener(){public void onClick(View v){homeToggle.performClick();}});root.post(new Runnable(){public void run(){if(closed)return;chatHomeCorner.setVisibility(root.getTag(NativeHomePanel.TAG)==null?View.VISIBLE:View.GONE);root.postDelayed(this,250);}});
        final File keyboardOnlyFlag=new File(a.getFilesDir(),new File(a.getFilesDir(),"home/.trough/keyboard-only").isFile()?"home/.trough/keyboard-only":"home/.trough/visual-only");
        final View.OnClickListener[] modeExit=new View.OnClickListener[1];
        if(keyboardOnlyFlag.isFile()) {
            final int[] previousVisibility=new int[col.getChildCount()];
            for(int i=0;i<col.getChildCount();i++){View child=col.getChildAt(i);previousVisibility[i]=child.getVisibility();if(child!=inputLayer)child.setVisibility(keyboardOnlyFlag.getName().equals("visual-only") && child.getLayoutParams() instanceof LinearLayout.LayoutParams && ((LinearLayout.LayoutParams)child.getLayoutParams()).weight>0?View.INVISIBLE:View.GONE);}
            dock.setVisibility(View.GONE);keyboard.setVisibility(View.VISIBLE);
            inputLayer.setLayoutParams(new LinearLayout.LayoutParams(-1,0,1));
            if(keyboardOnlyFlag.getName().equals("visual-only")){col.removeView(inputLayer);root.addView(inputLayer,new FrameLayout.LayoutParams(-1,-2,android.view.Gravity.BOTTOM));}
            keyboardToggle.setText("Back to chat");keyboardToggle.setContentDescription("Leave full screen keyboard");
            modeExit[0]=new View.OnClickListener(){public void onClick(View v){
                if(!keyboardOnlyFlag.delete())return;
                if(inputLayer.getParent()==root){root.removeView(inputLayer);col.addView(inputLayer,new LinearLayout.LayoutParams(-1,-2));}
                for(int i=0;i<previousVisibility.length;i++)col.getChildAt(i).setVisibility(previousVisibility[i]);
                dock.setVisibility(View.VISIBLE);inputLayer.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));setKeyboardCollapsed(false);
                keyboardToggle.setOnClickListener(new View.OnClickListener(){public void onClick(View view){setKeyboardCollapsed(!keyboardCollapsed);}});
            }};keyboardToggle.setOnClickListener(modeExit[0]);
        }

        keyboard.setPhoneKeyboard(new Runnable(){public void run(){
            dock.setVisibility(View.VISIBLE);keyboard.setVisibility(View.GONE);keyboardToggle.setText("Trough keyboard");
            input.setShowSoftInputOnFocus(true);input.requestFocus();
            final android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            input.post(new Runnable(){public void run(){ime.showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);}});
            keyboardToggle.setOnClickListener(new View.OnClickListener(){public void onClick(View v){input.setShowSoftInputOnFocus(false);ime.hideSoftInputFromWindow(input.getWindowToken(),0);if(keyboardOnlyFlag.isFile())dock.setVisibility(View.GONE);setKeyboardCollapsed(false);if(keyboardOnlyFlag.isFile()&&modeExit[0]!=null){keyboardToggle.setText("Back to chat");keyboardToggle.setOnClickListener(modeExit[0]);}else keyboardToggle.setOnClickListener(new View.OnClickListener(){public void onClick(View view){setKeyboardCollapsed(!keyboardCollapsed);}});}});
        }});
        root.addView(surfaceArt.feedbackView(),new FrameLayout.LayoutParams(-1,-1));
        projectQuestionPin.setElevation(dp(64));
        root.addView(projectQuestionPin,new FrameLayout.LayoutParams(-1,-2,android.view.Gravity.TOP));
        final boolean recoverDraft=state==null;
        inboxBridge=new InboxBridge(home,root,new InboxBridge.Listener() {
            public void modelLoaded(String encoded) {
                try { if(!heuristicReady && !encoded.isEmpty()) heuristic.restore(encoded); }
                catch(RuntimeException error) { note("Learning memory could not load: "+error.getMessage()); }
                heuristicReady=true;
                ArrayList<Map<String,Object>> waiting=new ArrayList<Map<String,Object>>(pendingTeaching);pendingTeaching.clear();
                for(Map<String,Object> item:waiting) {
                    String context=Json.str(item,"context");Map<String,Object> semantics=Json.obj(item.get("semantic"));
                    heuristic.observe(context,semantics,sceneOrdinal(context),Boolean.TRUE.equals(item.get("busy")));
                    if("text".equals(item.get("kind"))) heuristic.learnText(Json.str(item,"text"));
                    else heuristic.teach(Json.str(item,"axis"),((Number)item.get("target")).floatValue(),Json.str(item,"source"));
                }
                if(!waiting.isEmpty()) inboxBridge.saveHeuristic(heuristic.encode());
                refreshSurface();
            }
            public void swipeLoaded(String encoded) {
                keyboard.restoreLearning(encoded);
                refreshSurface();
            }
            public void teaching(Map<String,Object> action,String ticket) { receiveTeaching(action,ticket); }
            public void workControl(String command,String id,String ticket) {
                Map<String,Object> result=controlWork(command);
                result.put("id",id);
                inboxBridge.finishWorkControl(ticket,result);
            }
            public void presentShort(final String id,final String ticket) {
                root.setTag(0x7f0d7475,new String[]{id,ticket});
                if(a!=null) presentPendingShort();
                else inboxBridge.finishPresentation(ticket,Json.map("id",id,"ok",true,"queued_for_window",true));
            }
            public void makeShort(final String id,final String ticket) {
                Map<String,Object> controls=Json.obj(languageState.get("values"));
                final Map<String,Object> visualControls=controls==null?null:new HashMap<String,Object>(controls);
                final File output=new File(c.getFilesDir(),"home/.trough/media/gematria-57.mp4");
                Thread worker=new Thread(new Runnable() { public void run() {
                    Map<String,Object> result;
                    try { result=ShortMaker.make(output,visualControls); }
                    catch(Throwable failure) { result=Json.map("ok",false,"error",failure.toString()); }
                    result.put("id",id);result.put("at_ms",System.currentTimeMillis());inboxBridge.finishShort(ticket,result);
                }},"Trough short maker");worker.start();
            }
            public void platformTextCheck(final String id,final String ticket) {
                try {inboxBridge.checkPlatformText(id,ticket,NativePlatformTextOracle.factory(c,root));}
                catch(Throwable failure){inboxBridge.finishPlatformTextCheck(ticket,Json.map("id",id,"at_ms",System.currentTimeMillis(),"ok",false,"error",failure.toString()));}
            }
            public void nativeTextCheck(final String id,final String ticket) {
                try {inboxBridge.checkNativeText(id,ticket,NativeTextAdapterOracle.factory(c,root));}
                catch(Throwable failure){inboxBridge.finishNativeTextCheck(ticket,Json.map("id",id,"at_ms",System.currentTimeMillis(),"ok",false,"error",failure.toString()));}
            }
            public void mediaCheck(final String id,final String ticket) {
                MediaOracle.run(c,new MediaOracle.Listener() { public void finished(Map<String,Object> result) {
                    result.put("id",id);result.put("at_ms",System.currentTimeMillis());inboxBridge.finishMediaCheck(ticket,result);
                }});
            }
            public void transcriptCheck(String id,String ticket) {
                Map<String,Object> result=TranscriptOracle.run(c);result.put("id",id);result.put("at_ms",System.currentTimeMillis());
                inboxBridge.finishTranscriptCheck(ticket,result);
            }
            public void keyboardCheck(String id,String ticket) {
                Map<String,Object> result=inputOracle();
                result.put("id",id);result.put("at_ms",System.currentTimeMillis());
                inboxBridge.finishKeyboardCheck(ticket,result);
            }
            public void loaded(String draft,Map<String,Object> snapshot) {
                inboxSnapshot=snapshot;inboxLoaded=true;
                // Only an in-process handoff restores active editing. Old durable text belongs
                // in the same inbox as submitted messages, never back in the prompt box.
                if(recoverDraft && draft.trim().length()>0) inboxBridge.recoverDraft(draft,agent,sessionId);
                lastScheduledDraft=recoverDraft?null:draft;
                paint();
            }
            public void changed(Map<String,Object> snapshot) { inboxSnapshot=snapshot; paint(); }
            public void candidate(Map<String,Object> message) { dispatchInbox(message); }
            public void error(String message) {
                lastScheduledDraft=null;
                if(!message.equals(lastInboxError)) { lastInboxError=message; note("Message storage: "+message); }
                paint();
            }
            public void warning(String message) {
                if(!message.equals(lastInboxWarning)) { lastInboxWarning=message; note("Inbox diagnostic: "+message); }
                paint();
            }
        },residentTicket!=null);
        keyboard.setLearningListener(new ForgeKeyboard.LearningListener() {
            public void choices(String[] words,String selected,boolean canErase) {
                if(!closed) surfaceArt.wordChoices(words,selected,canErase);
            }
            public void learned(String encoded,Map<String,Object> state,Map<String,Object> event) {
                if(closed) return;
                inboxBridge.saveSwipeLearning(encoded,event);
                Object version=state.get("weights_version");
                surfaceArt.wordLearned(Json.str(state,"last_feedback"),version instanceof Number?((Number)version).longValue():0);
                refreshSurface();
            }
        });
        surfaceArt.setWordListener(new BuildArtwork.WordListener() {
            public void choose(String word) { if(!closed) keyboard.chooseWord(word); }
            public void erase() { if(!closed) keyboard.eraseWord(); }
        });
        MAIN.post(pollInbox);
        startStatusTicker();
        refreshSurface();
        paint();
        scheduleCosmicRestore();
        resumeResident();
        NativeHomePanel.pending(a,root,inputLayer,input);
        root.post(new Runnable(){public void run(){restoreHalfSwordPreview();ClaudeSignIn.restore(root);}});
    }

    /**
     * agents: every backend this bundle carries, name -> executable. The one last picked (tap the
     * status strip) is remembered in home/.trough-agent; each keeps its own conversation to resume.
     */
    void start(Map<String, String> agents, File cwd, Map<String, String> env) {
        if(closed) return;
        this.agents = agents;
        this.cwd = cwd;
        this.env = env;
        // Even a carried connection can need one-time restoration from the backend's durable history.
        try {
            File f = new File(home, ".trough-agent");
            String saved = f.exists() ? new String(Payloads.readSmall(f), "UTF-8").trim() : "";
            if (agents.containsKey(saved)) agent = saved;
        } catch (Exception ignored) {}
        if (!agents.containsKey(agent) && !agents.isEmpty()) agent = agents.keySet().iterator().next();
        if(!restoreTranscript())return;
        if(session!=null){persistTranscript();return;}
        ui(new Runnable() { @Override public void run() { if(!closed && session==null) connect(); } });
    }

    private boolean transportUpgradePending;
    private long lastStallRecoveryAt=-180000L;
    private boolean upgradeTransportIfIdle() {
        if(!transportUpgradePending || closed || busy || !initialized || session==null || !"codex".equals(agent)
                || login!=null || authRequired || !pendingPermissions.isEmpty() || !sendHandoff.ready(session))return false;
        transportUpgradePending=false;
        report("recovery","Replacing an older resident transport with the installed version; same conversation, no replay");
        persistTranscript();connect();return true;
    }
    private boolean recoverStalledTransport() {
        if(closed || !busy || stopping || !initialized || session==null || !"codex".equals(agent)
                || login!=null || authRequired || !pendingPermissions.isEmpty() || !sendHandoff.ready(session))return false;
        Map<String,Object> state=session instanceof ResidentAgent?((ResidentAgent)session).state():Json.map();
        Map<String,Object> backend=Json.obj(state.get("backend_state"));
        Map<String,Object> health=backend==null?null:Json.obj(backend.get("health"));
        long now=android.os.SystemClock.elapsedRealtime();
        boolean networkDue=health!=null && Boolean.TRUE.equals(health.get("network_recovery_due"));
        if(health==null || !Boolean.TRUE.equals(health.get("stalled")) || (!networkDue && now-lastStallRecoveryAt<180000L))return false;
        lastStallRecoveryAt=now;
        if(!networkDue && health.get("active_compactions") instanceof Number
            && ((Number)health.get("active_compactions")).intValue()>0)return false;
        Main.recordTransportStall(health,Json.map("last_event",backend.get("last_event"),"last_item_type",backend.get("last_item_type"),
            "last_event_age_ms",backend.get("last_event_age_ms"),"turn_id",backend.get("turn_id"),"model",backend.get("reported_model")));
        if(networkDue) {
            report("recovery","Network retries made no work progress for 30 seconds; retiring transport for indefinite reconnect polling");
            persistTranscript();onExit(-1,"Network connection stalled during backend retries; reconnecting saved conversation without replay");return true;
        }
        note("The connection stopped producing work updates. Reconnecting to this conversation; saved messages are retained.");
        report("recovery","Work-progress watchdog: requesting cancellation of silent turn before reconnect");
        persistTranscript();interruptTurn(false);return true;
    }
    private void resumeResident() {
        if(residentTicket==null) return;
        try {
            ResidentAgent carried=ResidentAgent.adopt(residentTicket,bound(generation));
            if(carried==null) throw new IllegalStateException("Invalid resident connection ticket");
            session=carried;residentTicket=null;sendHandoff.track(session);
            Map<String,Object> carriedState=carried.state();
            Map<String,Object> backend=Json.obj(carriedState.get("backend_state"));
            Object revision=backend==null?null:backend.get("transport_revision");
            transportUpgradePending="codex".equals(agent) && (!(revision instanceof Number)
                || ((Number)revision).intValue()<CodexAgent.TRANSPORT_REVISION);
            for(Map.Entry<String,Map<String,Object>> item:carriedPermissions.entrySet())
                onPermission(item.getKey(),Json.str(item.getValue(),"tool"),Json.obj(item.getValue().get("input")));
            carriedPermissions.clear();
            paint();carried.resume();
            if(stopping && busy) { stopping=false;interruptTurn(false); }
        } catch(RuntimeException error) {
            residentTicket=null;
            note("Could not retain the live connection: "+error.getMessage());
        }
    }

    private File sessionFile() { return new File(home, "claude".equals(agent) ? ".trough-session" : ".trough-session-" + agent); }

    private void connect() {
        transportUpgradePending=false;
        cancelConnectionTimers();
        finishBackgroundWork(false,stopping,"Connection changed");
        selectedRoute="";selectedEffort="";
        if(busy) finishTurnClock(stopping?"stopped":"interrupted");
        settleInbox("uncertain","Connection changed before delivery was confirmed");
        finishStream(); live=null; liveSource=null;
        cancelStopFallback();
        final int gen = ++generation;  // retire callbacks before closing the previous transport
        dismissCosmicApprovals();
        if (session != null) session.close();
        session = null;
        busy = false;
        stopping = false;
        initialized = false;
        authRequired = false;
        pendingPermissions.clear();
        permissionRequests.clear();
        lastError = "";
        model = "";
        String exe = agents.get(agent), resume = "";
        try { if (sessionFile().exists()) resume = new String(Payloads.readSmall(sessionFile()), "UTF-8").trim(); } catch (Exception ignored) {}
        try {
            final ResidentAgent resident=new ResidentAgent(bound(gen),PUBLICATIONS);
            Agent.Listener l = resident.listener();
            Agent backend;
            if (Models.MARK.equals(exe)) {
                String key = Models.readKey(home, agent);
                if (key == null) {
                    lastError = agent + " needs an API key. Return to chat to add it.";
                    note(agent + " needs an API key: send  /key <your key>  (kept in Trough's private storage, never sent to a model, never shown)");
                    paint();
                    return;
                }
                Map<String, Object> cfg = Models.config(home, agent);
                backend = new TroughAgent(Provider.of(cfg, key), agent + " (" + Json.str(cfg, "model") + ")", cwd, env,
                    new File(home, ".trough/conversations"), resume, l);
            } else if ("codex".equals(agent)) {
                Map<String,Object> settings = codexSettings();
                backend = new CodexAgent(exe, cwd, env, resume, Json.str(settings,"model"),
                    Json.str(settings,"effort"), "danger-full-access", l);
            } else {
                List<String> cmd = new ArrayList<>(Arrays.asList(exe, "--model", "opus"));
                if (!resume.isEmpty()) { cmd.add("--resume"); cmd.add(resume); }
                backend = new ClaudeAgent(cmd, cwd, env, l);
            }
            resident.attach(backend);session=resident;
            sendHandoff.track(session);
            if("codex".equals(agent)) {
                connectionDeadline=new Runnable(){public void run(){
                    if(!closed && generation==gen && session!=null && !initialized && !authRequired && login==null)
                        onExit(-1,"Codex initialization timed out after 30 seconds");
                }};
                MAIN.postDelayed(connectionDeadline,30000);
            }
            if (!resumed || resume.isEmpty()) note(agent + " starting" + (resume.isEmpty() ? "" : " (resuming)"));
            resumed = false;
            if ("claude".equals(agent) && new File(home, ".claude/.credentials.json").exists()
                    && !new File(home, ".claude/.trough-own-login").exists())
                note("claude here uses a copy of the VM's login; its refresh token rotates, so the two can log each other out. Send /login to give this phone its own.");
        } catch (Exception e) {
            lastError = "Could not start " + agent + ": " + e;
            note("could not start " + agent + ": " + e);
            reconnectAfterNetworkFailure();
        }
        paint();
    }

    private void refreshSurface() {
        Map<String,Object> state=workSnapshot==null ? Json.map("state",busy?"running":"idle")
            : new java.util.LinkedHashMap<String,Object>(workSnapshot);
        state.put("word",input==null?"":input.getText().toString());
        state.put("prompt",lastUserPrompt);
        String response=liveSource==null?"":liveSource.substring(Math.max(0,liveSource.length()-12000));
        if(liveSource==null) for(int i=log.size()-1;i>=0;i--) if(isAssistantKind(log.get(i)[0])) { response=log.get(i)[1]; break; }
        if(response.length()>12000) response=response.substring(response.length()-12000);
        state.put("response",response);
        applyScene(state);
        visualSteering.offer(state);
    }

    /** One learned state feeds every native surface; semantic inference may arrive later. */
    private void applyScene(Map<String,Object> material) {
        String text=sceneText();
        heuristic.observe(text,semanticValues,sceneOrdinal(text),busy);
        Map<String,Object> state=adaptiveStatus();
        material.put("word",input==null?"":input.getText().toString());material.put("prompt",lastUserPrompt);
        material.put("adaptive",state);material.put("dogshit",languageState);material.put("held",autoSendHeld);
        material.put("input_learning",keyboard==null?Json.map():keyboard.learningState());
        surfaceArt.update(material);
        if(keyboard!=null) keyboard.applyAdaptive(state);
        if(input!=null) input.applyAdaptive(state);
        int bucket=Math.round(heuristic.value("text_scale")*16)*32+Math.round(heuristic.value("contrast")*16);
        long version=((Number)state.get("weights_version")).longValue();
        if(version!=styledWeightsVersion && bucket!=outputStyleBucket && feed!=null) {
            styledWeightsVersion=version;
            outputStyleBucket=bucket;
            for(int i=0;i<feed.getChildCount();i++) {
                View child=feed.getChildAt(i);
                if(child instanceof TextView && ("assistant".equals(child.getTag()) || "user".equals(child.getTag())))
                    styleProse((TextView)child,"user".equals(child.getTag()));
            }
        }
        updateLearningView();
    }

    private void styleProse(TextView view,boolean user) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP,14+heuristic.value("text_scale")*4);
        view.setLineSpacing(dp(2),1.1f);
        view.setTextColor(user?USER:FG);
    }

    private void teachScene(String axis,float target,String source) {
        String context=sceneText();
        if(!heuristicReady) { pendingTeaching.add(Json.map("axis",axis,"target",target,"source",source,"context",context,
            "semantic",new HashMap<String,Object>(semanticValues),"busy",busy));return; }
        heuristic.observe(context,semanticValues,sceneOrdinal(context),busy);
        heuristic.teach(axis,target,source);
        lastUserActivityAt=android.os.SystemClock.elapsedRealtime();
        refreshSurface();
        MAIN.removeCallbacks(saveLearning);MAIN.postDelayed(saveLearning,450);
    }

    private String sceneText() {
        String text=input==null?"":input.getText().toString();
        if(text.trim().isEmpty()) text=lastUserPrompt;
        return text.length()>2048?text.substring(0,2048):text;
    }
    private int sceneOrdinal(String text) {
        LetterValues.Result letters=LetterValues.analyse(text);
        return letters==null?CosmicWords.ordinal(text):letters.total;
    }
    private int sceneContext() { return 31*(31*sceneText().hashCode()+semanticValues.hashCode())+(busy?1:0); }
    private Map<String,Object> adaptiveStatus() {
        Map<String,Object> state=heuristic.state();state.put("ready",heuristicReady);return state;
    }
    private long exactInteger(Object value,long minimum,long maximum) {
        if(!(value instanceof Number)) throw new IllegalArgumentException("An integer is required");
        double number=((Number)value).doubleValue();
        if(Double.isNaN(number)||Double.isInfinite(number)||number<minimum||number>maximum||number!=Math.rint(number))
            throw new IllegalArgumentException("Invalid integer");
        return ((Number)value).longValue();
    }

    private boolean routeSceneGesture(android.view.MotionEvent event) {
        int action=event.getActionMasked();
        if(event.getPointerCount()>=2 && action!=android.view.MotionEvent.ACTION_POINTER_UP && action!=android.view.MotionEvent.ACTION_CANCEL) {
            adjustingScene=true;touchingScroll=false;
            long now=android.os.SystemClock.elapsedRealtime();
            if(now-lastDirectAdjustment>=120) {
                lastDirectAdjustment=now;
                float x=(event.getX(0)+event.getX(1))*.5f/Math.max(1,scroll.getWidth());
                float y=(event.getY(0)+event.getY(1))*.5f/Math.max(1,scroll.getHeight());
                teachScene("warmth",Math.max(0,Math.min(1,x)),"you: two-finger colour");
                teachScene("energy",Math.max(0,Math.min(1,1-y)),"you: two-finger motion");
            }
            return true;
        }
        if(adjustingScene) {
            if(action==android.view.MotionEvent.ACTION_UP || action==android.view.MotionEvent.ACTION_CANCEL) adjustingScene=false;
            return true;
        }
        return false;
    }

    private void receiveTeaching(Map<String,Object> action,String ticket) {
        Map<String,Object> current=heuristic.state();
        Object expected=action.get("expected_version");
        long version=((Number)current.get("weights_version")).longValue();
        String outcome="applied";
        try {
            if(!heuristicReady) throw new IllegalStateException("Learning state is still loading");
            if(exactInteger(expected,0,9007199254740991L)!=version) outcome="stale";
            else if(exactInteger(action.get("scene_context"),Integer.MIN_VALUE,Integer.MAX_VALUE)!=sceneContext()) outcome="stale context";
            else {
                String axis=Json.str(action,"axis");Object value=action.get("target");
                if(!(value instanceof Number) || Double.isNaN(((Number)value).doubleValue()) || Double.isInfinite(((Number)value).doubleValue())
                    || ((Number)value).doubleValue()<0 || ((Number)value).doubleValue()>1) throw new IllegalArgumentException("A target between 0 and 1 is required");
                String note=Json.str(action,"note");
                heuristic.observe(sceneText(),semanticValues,sceneOrdinal(sceneText()),busy);
                heuristic.teach(axis,((Number)value).floatValue(),"cloud"+(note==null||note.isEmpty()?"":": "+note));
                refreshSurface();
            }
        } catch(RuntimeException error) { outcome="rejected: "+error.getMessage(); }
        inboxBridge.finishTeaching(ticket,heuristic.encode(),Json.map("id",action.get("id"),"outcome",outcome,
            "axis",action.get("axis"),"target",action.get("target"),"expected_version",expected,
            "weights_version",heuristic.state().get("weights_version"),"source","cloud","at_ms",System.currentTimeMillis()));
    }

    private void updateLearningView() {
        if(learningSummary==null) return;
        Map<String,Object> state=heuristic.state();
        Map<String,Object> swipe=keyboard.learningState();
        learningSummary.setText("Local learning · "+state.get("parameters")+" weights · version "+state.get("weights_version")
            +"\n"+state.get("examples")+" feedback examples · "+state.get("updates")+" weight updates"
            +"\n"+String.valueOf(state.get("last_feedback"))
            +"\nInput · continuous swipe targets"
            +"\nWord guessing off · no automatic spaces"
            +"\nSaved swipe learner · version "+swipe.get("weights_version")+" · inactive");
        if(learningWeights!=null) learningWeights.invalidate();
    }

    private void showLearning() {
        if(a==null) return;
        if(learningDialog!=null) learningDialog.dismiss();
        LinearLayout panel=new LinearLayout(c);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(18),dp(12),dp(18),dp(12));
        learningSummary=text(FG,14);panel.addView(learningSummary);
        TextView help=text(DIM,13);help.setText("Type preferences such as ‘slower’ or ‘brighter’. Two fingers in the conversation adjust colour and motion. These controls teach the same local model.");
        help.setPadding(0,dp(8),0,dp(8));panel.addView(help);
        learningWeights=new View(c) {
            final android.graphics.Paint ink=new android.graphics.Paint();
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                float[] weights=heuristic.weights();float cellW=getWidth()/32f,cellH=getHeight()/8f;
                for(int i=0;i<weights.length;i++) {
                    float value=(float)Math.tanh(weights[i]);int alpha=30+(int)(Math.abs(value)*210);
                    ink.setColor(value>=0?android.graphics.Color.argb(alpha,132,225,182):android.graphics.Color.argb(alpha,189,146,225));
                    canvas.drawRect((i%32)*cellW,(i/32)*cellH,(i%32+1)*cellW-dp(1),(i/32+1)*cellH-dp(1),ink);
                }
            }
        };
        learningWeights.setContentDescription("Actual learned weights; green positive, violet negative");
        panel.addView(learningWeights,new LinearLayout.LayoutParams(-1,dp(80)));
        for(final String axis:AdaptiveHeuristic.AXES) {
            TextView label=text(FG,13);label.setText(axis.replace('_',' '));panel.addView(label);
            android.widget.SeekBar level=new android.widget.SeekBar(c);level.setMax(100);level.setProgress(Math.round(heuristic.value(axis)*100));
            level.setContentDescription("Teach "+axis.replace('_',' '));
            level.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(android.widget.SeekBar bar) {}
                public void onStopTrackingTouch(android.widget.SeekBar bar) {}
                public void onProgressChanged(android.widget.SeekBar bar,int value,boolean user) { if(user) teachScene(axis,value/100f,"you: learning controls"); }
            });
            panel.addView(level,new LinearLayout.LayoutParams(-1,dp(32)));
        }
        ScrollView contents=new ScrollView(c);contents.addView(panel);
        learningDialog=new android.app.AlertDialog.Builder(a).setTitle("Living Trough").setView(contents)
            .setPositiveButton("Close",null).setNeutralButton("Saved messages",new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface dialog,int which) { showInbox(); }
            }).setNegativeButton("Reset learning",new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface dialog,int which) { heuristic.reset();refreshSurface();inboxBridge.saveHeuristic(heuristic.encode()); }
            }).create();
        learningDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { public void onDismiss(android.content.DialogInterface dialog) {
            learningSummary=null;learningWeights=null;learningDialog=null;
        }});
        updateLearningView();learningDialog.show();
    }
    private void queueSurfaceRefresh() {
        if(closed || surfaceQueued) return;
        surfaceQueued=true;
        MAIN.postDelayed(surfaceRefresh,90);
    }
    void showWork(Map<String,Object> state) {
        if(closed) return;
        workSnapshot=new java.util.LinkedHashMap<String,Object>(state);
        queueSurfaceRefresh();
        if(workVisual!=null) workVisual.update(workSnapshot);
        reopenCosmic=false; CosmicPanel.close();
    }
    private void showWorkDetails() {
        if(a==null || workSnapshot==null) return;
        if(workDialog!=null) workDialog.dismiss();
        workVisual=BuildProgress.create(c); workVisual.update(workSnapshot);
        workDialog=new android.app.Dialog(a); workDialog.setContentView(workVisual); workDialog.show();
        android.view.Window window=workDialog.getWindow();
        if(window!=null) window.setLayout(-1,-2);
    }
    Map<String,Object> surfaceState() {
        Map<String,Object> state=surfaceArt.state();
        state.put("model",visualSteering.state()); state.put("keyboard",keyboard.state());
        state.put("composer",Json.map("auto_send",true,"pause_ms",AUTO_SEND_PAUSE_MS,
            "armed",autoSendArmed,"auto_send_held",autoSendHeld,"keyboard_gesture",keyboardGestureActive,"send_button",false,"draft_length",input.length()));
        state.put("inbox",inboxSnapshot);
        state.put("timing",timingState());
        return state;
    }

    /** Both diagnostic names use a separate editor; never touch the live draft or its timer. */
    Map<String,Object> inputOracle() {
        if(a==null) return Json.map("ok",false,"draft_preserved",true,"error","Trough's window is unavailable");
        try {
            Map<String,Object> result=KeyboardOracle.run(a);
            result.put("draft_preserved",true);result.put("isolated_editor",true);
            return result;
        } catch(Exception error) {
            return Json.map("ok",false,"draft_preserved",true,"isolated_editor",true,"error",String.valueOf(error));
        }
    }

    private void workEvent(String stage, String state, String detail) {
        if(workStarted==0) workStarted=android.os.SystemClock.elapsedRealtime();
        if(detail!=null && !detail.isEmpty()) {
            workOutput.append(detail).append('\n');
            if(workOutput.length()>10000) workOutput.delete(0,workOutput.length()-10000);
        }
        showWork(Json.map("title","Trough · live work","stage",stage,"state",state,"message",detail,
            "elapsed_ms",android.os.SystemClock.elapsedRealtime()-workStarted,"log",workOutput.toString(),
            "checks",new ArrayList<Object>(workChecks),"files",new ArrayList<Object>(workFiles)));
    }

    Map<String,Object> verifyCodexTurn() {
        if(!"codex".equals(agent) || !initialized || busy || !pendingPermissions.isEmpty() || !sendHandoff.ready(session))
            return Json.map("ok",false,"error","Codex must be ready and idle");
        repairStart=log.size();
        beginTurn("Phone Codex installation verification requested by the user: run exactly pwd using your command tool, "
            + "then return its actual output. Run no other commands, make no changes, do not request elevated permissions, "
            + "and do not delegate. This verifies the local code-mode host and shell on the phone.");
        return Json.map("ok",true,"session_id",sessionId,"started",true);
    }
    Map<String,Object> codexVerificationState() {
        ArrayList<Object> entries=new ArrayList<>();
        if(repairStart>=0) for(int i=repairStart;i<log.size() && entries.size()<60;i++)
            entries.add(Json.map("kind",log.get(i)[0],"text",log.get(i)[1]));
        return Json.map("ok",true,"session",codexState(),"started",repairStart>=0,
            "waiting_permission",!pendingPermissions.isEmpty(),"error",lastError,"entries",entries);
    }

    private void loadWorkPolicy(Map<?,?> handoff) {
        try {
            Map<String,Object> saved=handoff==null?null:Json.obj(handoff.get("workPolicy"));
            if(saved==null) {
                File file=new File(home,".trough/work-policy.json");
                if(file.isFile() && file.length()<=32768) saved=Json.obj(Json.parse(new String(Payloads.readSmall(file),"UTF-8")));
            }
            if(saved!=null) {
                // User 0fb84bb5: background work uses the same selected model as foreground.
                saved.put("active_model","gpt-6.1-sol");
                saved.put("idle_model",saved.get("active_model"));
                workPolicy=WorkPolicy.restore(saved,android.os.SystemClock.elapsedRealtime());
                workPolicy.setIdleDelay(WorkPolicy.DEFAULT_IDLE_MS,android.os.SystemClock.elapsedRealtime());
                if(!saved.containsKey("foreground_grace_remaining_ms")) workPolicy.foregroundFinished(android.os.SystemClock.elapsedRealtime());
                WorkPolicy.Lease lease=workPolicy.currentLease();
                if(handoff==null && lease!=null) {
                    if(lease.running) workPolicy.finish(lease.id,WorkPolicy.Outcome.FAILED,android.os.SystemClock.elapsedRealtime());
                    else workPolicy.cancel(lease.id,android.os.SystemClock.elapsedRealtime());
                }
            }
            workPolicy.setContinuousWork(new File(home,".trough/continuous-work").isFile(),android.os.SystemClock.elapsedRealtime());
        } catch(Exception error) { workPolicyError="Work checkpoint: "+error.getMessage();workPolicy.pause("Checkpoint unreadable",android.os.SystemClock.elapsedRealtime()); }
    }

    private WorkPolicy.Input workInput() {
        WorkPolicy.Input i=new WorkPolicy.Input();
        i.turnRunning=busy || stopping;
        File screenOffTest=new File(home,".trough/screen-off-test-once");
        long testAge=System.currentTimeMillis()-screenOffTest.lastModified();
        i.immediateScreenOffTest=screenOffTest.isFile() && testAge>=0 && testAge<1200000L;
        i.deviceInteractive=!Boolean.FALSE.equals(Power.state().get("interactive"));
        i.appForeground=a!=null && input.hasWindowFocus();
        i.ready=inboxLoaded && initialized && session!=null && "codex".equals(agent) && sendHandoff.ready(session);
        i.queueEmpty=inboxCount("queued")==0 && activeInboxId==null && !enqueuePending;
        i.draftEmpty=input.length()==0;
        i.gestureIdle=!autoSendHeld && !keyboardGestureActive && !keyboard.hasPendingPress() && !touchingScroll;
        i.authPending=login!=null || authRequired;i.permissionPending=!pendingPermissions.isEmpty();
        // Held drafts are preserved unsent text, not failed delivery or active work.
        i.failurePending=inboxCount("uncertain")>0 || !lastInboxError.isEmpty();
        Map<String,Object> reloadState=Main.liveReloadState();
        i.externalBlocked=!workPolicyError.isEmpty() || Boolean.TRUE.equals(reloadState.get("building")) || Boolean.TRUE.equals(reloadState.get("pending"));
        return i;
    }

    private void noteWorkUserActivity() {
        lastUserActivityAt=android.os.SystemClock.elapsedRealtime();
        workPolicy.userActivity(lastUserActivityAt);observedUserActivity=lastUserActivityAt;
        MAIN.removeCallbacks(saveWorkState);MAIN.postDelayed(saveWorkState,1000);
    }

    private void persistWorkPolicy(final Runnable saved) {
        final String encoded=Json.write(workPolicy.exportState(android.os.SystemClock.elapsedRealtime()));
        inboxBridge.saveWorkPolicy(encoded,saved,new Runnable() { public void run() {
            if(backgroundDispatching && !busy && !activeWorkId.isEmpty()) {
                workPolicy.finish(activeWorkId,WorkPolicy.Outcome.FAILED,android.os.SystemClock.elapsedRealtime());activeWorkId="";
            }
            workStateSaving=false;backgroundDispatching=false;workPolicyError="Cannot persist autonomous work";
            workPolicy.pause(workPolicyError,android.os.SystemClock.elapsedRealtime());
        }});
    }

    Map<String,Object> controlWork(String command) {
        long now=android.os.SystemClock.elapsedRealtime();
        if("pause".equals(command)) { workPolicy.pause("Paused by user",now);if(!activeWorkId.isEmpty()) interruptTurn(false); }
        else if("resume".equals(command)) { workPolicyError="";workPolicy.resume(now); }
        else if(!"status".equals(command)) return Json.map("ok",false,"error","Use pause, resume, or status");
        if(!"status".equals(command)) persistWorkPolicy(null);
        return Json.map("ok",true,"work_policy",workPolicyStatus(),"codex",codexState(),"power",Power.state());
    }

    private Map<String,Object> workPolicyStatus() {
        long now=android.os.SystemClock.elapsedRealtime();
        WorkPolicy.Decision d=workPolicy.evaluate(now,workInput());
        Map<String,Object> state=workPolicy.exportState(now);
        // Prompts stay in the durable checkpoint; diagnostics expose identity/status only.
        state.remove("lease");
        state.put("reason",d.reason);state.put("remaining_ms",d.remainingMs);
        state.put("next_model",d.model);state.put("next_effort",workEffort(d.model));
        state.put("active_work_id",activeWorkId);state.put("first_astra_handoff_complete",routeUpgraded);
        state.put("error",workPolicyError);
        state.put("delivery_failure_detail",!lastInboxError.isEmpty()?lastInboxError:inboxCount("uncertain")>0?"Uncertain message delivery":"");
        state.put("action_warning",lastInboxWarning);
        state.put("preserved_held_drafts",inboxCount("held_count"));
        return state;
    }

    private String foregroundEffort() {
        try {
            File file=new File(home,".trough/model-route-request.json");
            if(file.isFile() && file.length()<4096) {
                Map<String,Object> request=Json.obj(Json.parse(new String(Payloads.readSmall(file),"UTF-8")));
                String effort=Json.str(request,"effort");
                if("gpt-6.1-sol".equals(Json.str(request,"model")) && Arrays.asList("low","medium","high","xhigh","max","ultra").contains(effort)) return effort;
            }
        } catch(Exception ignored) {}
        return "low";
    }

    private String workEffort(String target) {
        return "gpt-6.1-sol".equals(target)?foregroundEffort():"low";
    }

    private boolean ensureRoute(String target,String effort) {
        if(busy || stopping || session==null || !initialized || login!=null || authRequired || !pendingPermissions.isEmpty()
            || !sendHandoff.ready(session) || !"codex".equals(agent)) return false;
        if(target.equals(selectedRoute) && effort.equals(selectedEffort)) return true;
        try {
            Map<String,Object> result=configureCodex(target,effort);
            return Boolean.TRUE.equals(result.get("ok")) && initialized && session!=null;
        } catch(Exception error) { workPolicyError="Model selection: "+error.getMessage();return false; }
    }

    private void workTick() {
        if(lastUserActivityAt>observedUserActivity) {
            workPolicy.userActivity(android.os.SystemClock.elapsedRealtime());observedUserActivity=lastUserActivityAt;
            MAIN.removeCallbacks(saveWorkState);MAIN.postDelayed(saveWorkState,1000);
        }
        if(!"codex".equals(agent) || busy || stopping || !inboxLoaded) return;
        if(!routeUpgraded) {
            if(!ensureRoute("gpt-6.1-sol",foregroundEffort())) return;
            routeUpgraded=true;
        }
        if(workStateSaving || backgroundDispatching || !activeWorkId.isEmpty()) return;
        WorkPolicy.Input conditions=workInput();
        WorkPolicy.Decision decision=workPolicy.evaluate(android.os.SystemClock.elapsedRealtime(),conditions);
        // Keep the interactive selection ready; idle fallback is selected only for a work turn.
        if(!decision.canStartBackground) { if(!decision.idle) ensureRoute("gpt-6.1-sol",foregroundEffort());return; }
        if(!decision.canStartBackground) return;
        if(!ensureRoute(decision.model,workEffort(decision.model))) return;
        final WorkPolicy.Lease lease=workPolicy.claim(android.os.SystemClock.elapsedRealtime(),workInput(),
            "Trough Android source: /data/user/0/t.t/files/src. Roadmap: /data/user/0/t.t/files/work/trough-lab/roadmap.json. "
            +"Handoff/checkpoint: /data/user/0/t.t/files/work/trough-lab/model-handoff.md. Preserve native input, rich output and live reload. "
            +"Save ongoing progress in /data/user/0/t.t/files/work/trough-lab/background-checkpoint.md.",lastUserPrompt);
        if(lease==null) return;
        workStateSaving=true;
        persistWorkPolicy(new Runnable() { public void run() {
            workStateSaving=false;
            if(closed) return;
            long now=android.os.SystemClock.elapsedRealtime();
            if(lastUserActivityAt>observedUserActivity) { workPolicy.userActivity(now);observedUserActivity=lastUserActivityAt; }
            if(!workPolicy.start(lease.id,now,workInput())) { persistWorkPolicy(null);return; }
            // Persist running ownership before transport dispatch. A restart never replays it.
            backgroundDispatching=true;activeWorkId=lease.id;
            persistWorkPolicy(new Runnable() { public void run() {
                backgroundDispatching=false;
                if(closed) return;
                long dispatchAt=android.os.SystemClock.elapsedRealtime();
                if(lastUserActivityAt>observedUserActivity) { workPolicy.userActivity(dispatchAt);observedUserActivity=lastUserActivityAt; }
                WorkPolicy.Input fresh=workInput();
                WorkPolicy.Decision current=workPolicy.evaluate(android.os.SystemClock.elapsedRealtime(),fresh);
                if(!fresh.ready || !fresh.queueEmpty || !fresh.draftEmpty || !fresh.gestureIdle || fresh.turnRunning
                    || fresh.authPending || fresh.permissionPending || fresh.failurePending || fresh.externalBlocked
                    || Boolean.TRUE.equals(workPolicy.exportState(android.os.SystemClock.elapsedRealtime()).get("paused"))) {
                    workPolicy.finish(lease.id,WorkPolicy.Outcome.YIELDED,android.os.SystemClock.elapsedRealtime());
                    activeWorkId="";persistWorkPolicy(null);return;
                }
                if(!current.model.equals(selectedRoute) || !workEffort(current.model).equals(selectedEffort)) {
                    workPolicy.finish(lease.id,WorkPolicy.Outcome.YIELDED,android.os.SystemClock.elapsedRealtime());
                    activeWorkId="";persistWorkPolicy(null);return;
                }
                try { beginBackgroundTurn(lease.prompt); }
                catch(RuntimeException error) { finishBackgroundWork(false,false,error.toString()); }
            }});
        }});
    }

    private void beginBackgroundTurn(String prompt) {
        if(session==null || !initialized || busy || !sendHandoff.ready(session)) throw new IllegalStateException("Background transport not ready");
        finishStream();live=null;liveSource=null;
        backgroundStartLogIndex=log.size();
        backgroundWires.put(prompt,activeWorkId);
        while(backgroundWires.size()>8) backgroundWires.remove(backgroundWires.keySet().iterator().next());
        session.send(prompt);new File(home,".trough/screen-off-test-once").delete();backgroundYielded=false;stopping=false;
        turnStartedAt=android.os.SystemClock.elapsedRealtime();turnFinishedAt=0;turnOutcome="working";
        lastAgentActivityAt=turnStartedAt;workStarted=turnStartedAt;workChecks.clear();workFiles.clear();workOutput.setLength(0);
        workEvent("background","running","Continuing project work while you are inactive");lastError="";busy=true;paint();
    }

    private void finishBackgroundWork(boolean ok,boolean cancelled,String error) {
        if(activeWorkId.isEmpty()) return;
        WorkPolicy.Outcome outcome=backgroundYielded || cancelled || (!ok && ConnectionRecovery.transientFailure(error))?WorkPolicy.Outcome.YIELDED:WorkPolicy.Outcome.FAILED;
        if(ok && !cancelled) {
            String finalText="";
            for(int i=log.size()-1;i>=backgroundStartLogIndex;i--) if(isAssistantKind(log.get(i)[0])) { finalText=log.get(i)[1];break; }
            String stripped=finalText.trim();
            int line=stripped.lastIndexOf('\n');
            String lastLine=stripped.substring(line+1).trim();
            outcome=WorkPolicy.Outcome.FAILED;
            if(lastLine.matches("TROUGH_WORK: (PROGRESS|NO_WORK|BLOCKED|FAILED|RATE_LIMITED)"))
                outcome=WorkPolicy.Outcome.valueOf(lastLine.substring(13));
        } else if(error!=null && (error.toLowerCase(java.util.Locale.US).contains("rate limit") || error.contains("429"))) outcome=WorkPolicy.Outcome.RATE_LIMITED;
        if(outcome==WorkPolicy.Outcome.BLOCKED || outcome==WorkPolicy.Outcome.FAILED || outcome==WorkPolicy.Outcome.RATE_LIMITED)
            note("Background issue recorded · "+outcome.name()+". Other work remains eligible after backoff. "+(error==null?"See the saved work checkpoint for details.":clip(error)));
        workPolicy.finish(activeWorkId,outcome,android.os.SystemClock.elapsedRealtime());
        activeWorkId="";backgroundYielded=false;persistWorkPolicy(null);
    }

    private Map<String,Object> codexSettings() throws Exception {
        File file = new File(home,".trough-codex-settings.json");
        return file.isFile() ? Json.obj(Json.parse(new String(Payloads.readSmall(file),"UTF-8"))) : Json.map();
    }

    Map<String,Object> configureCodex(String target, String effort) throws Exception {
        if(busy || login!=null || !pendingPermissions.isEmpty() || !sendHandoff.ready(session)) return Json.map("ok",false,"error","chat busy");
        if(!agents.containsKey("codex")) return Json.map("ok",false,"error","codex unavailable");
        String retained = sessionId;
        Payloads.writeSmall(new File(home,".trough-codex-settings.json"), Json.write(Json.map("model",target,"effort",effort)));
        Payloads.writeSmall(new File(home,".trough-agent"),"codex");
        boolean selected="codex".equals(agent) && session!=null && session.configureSelection(target,effort);
        agent="codex";
        if(!selected) connect();
        selectedRoute=target;selectedEffort=effort;
        return Json.map("ok",true,"requested_model",target,"requested_effort",effort,"prior_session",retained==null?"":retained);
    }

    Map<String,Object> codexState() {
        Map<String,Object> transport=session instanceof ResidentAgent?((ResidentAgent)session).state()
            :session instanceof CodexAgent?((CodexAgent)session).state():Json.map();
        return Json.map("selected_agent",agent,"session_id",sessionId==null?"":sessionId,
            "initialized",initialized,"model",model,"selected_model",selectedRoute,"selected_effort",selectedEffort,"busy",busy,
            "stopping",stopping,"connection_retry_pending",connectionRetry!=null,"connection_retry_attempts",connectionRetries,
            "connection_retry_in_ms",connectionRetryAt==0?0:Math.max(0,connectionRetryAt-android.os.SystemClock.elapsedRealtime()),"waiting_permission",!pendingPermissions.isEmpty(),
            "auth_required",login!=null||authRequired,"draft_length",input.length(),"error",lastError,
            "transport",transport,
            "reasoning_effort",session instanceof ResidentAgent?Json.str(transport,"reasoning_effort")
                :session instanceof CodexAgent?((CodexAgent)session).reportedEffort():"");
    }

    /** Explicit user recovery cancels a turn; it never replays a message or answers an approval. */
    Map<String,Object> recoverCodex() {
        if(!"codex".equals(agent) || login!=null || authRequired)
            return Json.map("ok",false,"error","Select the signed-in Codex conversation before recovering");
        // Explicit recovery replaces the process even if a turn is stuck. Stop alone can
        // immediately dispatch the next queued turn onto the same broken process.
        persistTranscript();connect();
        return Json.map("ok",true,"session",codexState(),"draft_preserved",true,"message_replayed",false);
    }

    /** A projection of the same conversation; Cosmos never owns another agent or message queue. UI thread only. */
    Map<String,Object> cosmicState() {
        String reply = "";
        for (int i = log.size() - 1; i >= 0; i--) {
            String[] item = log.get(i);
            if (isAssistantKind(item[0]) && !item[1].isEmpty()) {
                reply = item[1];
                if (reply.length() > 8000) reply = "…" + reply.substring(reply.length() - 8000);
                break;
            }
        }
        boolean waiting = !pendingPermissions.isEmpty();
        boolean confirming = !sendHandoff.ready(session);
        boolean ready = session != null && initialized && !busy && login == null && !authRequired && !waiting && !confirming;
        String state = waiting ? "Waiting for your approval" : (login != null || authRequired)
            ? "Return to chat to sign in" : busy ? "Working" : confirming ? "Confirming your follow-up…" : ready ? "Ready"
            : !lastError.isEmpty() ? lastError : session != null ? "Connecting…" : "Return to chat to connect";
        return Json.map("selected_agent", agent, "model", model, "session_id", sessionId == null ? "" : sessionId,
            "busy", busy, "ready", ready, "waiting_permission", waiting, "auth_required", login != null || authRequired,
            "assistant_text", reply, "error", lastError, "status", state);
    }

    /** An explicit Send in Cosmos captures only that field; the resident chat draft and selection stay intact. */
    Map<String,Object> sendFromCosmos(String message) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            return Json.map("ok", false, "accepted", false, "error", "Send must run on the UI thread");
        String text = message == null ? "" : message;
        String error = text.trim().isEmpty() ? "Write a message first" : a == null ? "Open Trough before sending"
            : !pendingPermissions.isEmpty() ? "Return to chat to approve"
            : login != null || authRequired ? "Return to chat to sign in"
            : busy ? "The model is working. Wait for this reply before sending again."
            : !sendHandoff.ready(session) ? "The model is still confirming your previous follow-up."
            : session == null || !initialized ? "The model is not ready. Return to chat to connect." : "";
        if (!error.isEmpty()) return Json.map("ok", false, "accepted", false, "error", error);
        try {
            beginTurn(text);
            return Json.map("ok", true, "accepted", true, "selected_agent", agent,
                "session_id", sessionId == null ? "" : sessionId);
        } catch (RuntimeException e) {
            return Json.map("ok", false, "accepted", false, "error", lastError);
        }
    }
    Map<String,Object> resumeTransferredCodex(String id) throws Exception {
        if(busy || login!=null || !sendHandoff.ready(session)) return Json.map("ok",false,"error","chat busy");
        if(!agents.containsKey("codex")) return Json.map("ok",false,"error","codex unavailable");
        Payloads.writeSmall(new File(home,".trough-session-codex"),id);
        Payloads.writeSmall(new File(home,".trough-agent"),"codex");
        agent="codex"; sessionId=null; connect();
        note("Resuming your conversation from the VM in Trough.");
        return Json.map("ok",true,"requested_session",id,"selected_agent","codex");
    }
    private void pickAgent(View anchor) {
        if (a == null) return;
        android.widget.PopupMenu menu = new android.widget.PopupMenu(a, anchor);
        for (String name : agents.keySet()) menu.getMenu().add(name.equals(agent) ? name + "  ✓" : name);
        menu.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                String name = item.getTitle().toString().replace("  ✓", "");
                if (name.equals(agent) || busy || enqueuePending || activeInboxId!=null || !sendHandoff.ready(session)) return true;
                agent = name;
                try { Payloads.writeSmall(new File(home, ".trough-agent"), name); } catch (Exception ignored) {}
                connect();
                return true;
            }
        });
        menu.show();
    }

    /** Claude's own sign-in for this phone (ClaudeAgent.Login): card with the page, then the code. */
    private void startLogin() {
        if (login != null) login.cancel();
        try {
            login = new ClaudeAgent.Login(agents.get("claude"), cwd, env, this, new ClaudeAgent.Login.Done() {
                @Override public void onDone(final boolean ok, final String detail) {
                    ui(new Runnable() { @Override public void run() {
                        login = null;
                        if (ok) try { Payloads.writeSmall(new File(home, ".claude/.trough-own-login"), "1"); } catch (Exception ignored) {}
                        note(ok ? "claude: signed in on this phone" : "claude sign-in did not complete: " + detail);
                        report("login", ok ? "ok" : "failed");
                        if (ok) connect();
                    } });
                }
            });
            note("claude sign-in started: open the page, sign in, then paste the code shown there as a message");
        } catch (Exception e) {
            note("could not start claude sign-in: " + e);
        }
    }

    /** This Chat as the listener of agent generation gen only. */
    private Agent.Listener bound(final int gen) {
        final Chat c = this;
        final String ownerAgent=agent;
        return new Agent.Listener() {
            volatile String ownerThread=sessionId;
            void deliver(final Runnable event) {
                ui(new Runnable() { public void run() { if(!closed && gen==generation) event.run(); }});
            }
            @Override public void onInit(final String id, final String m) {
                ownerThread=id;
                deliver(new Runnable() { public void run() { c.onInit(id,m); }});
            }
            @Override public void onTextDelta(final String t) { deliver(new Runnable() { public void run() { c.onTextDelta(t); }}); }
            @Override public void onAssistantText(final String t) { deliver(new Runnable() { public void run() { c.onAssistantText(t); }}); }
            @Override public void onToolUse(final String id, final String n, final Map<String, Object> in) { deliver(new Runnable() { public void run() { c.onToolUse(id,n,in); }}); }
            @Override public void onToolResult(final String id, final String t, final boolean e) { deliver(new Runnable() { public void run() { c.onToolResult(id,t,e); }}); }
            @Override public void onMessageAccepted(final String text) {
                ui(sendHandoff.publication(new Runnable() { public void run() {
                    if(!closed && ownerAgent.equals(agent) && ownerThread!=null && ownerThread.equals(sessionId))
                        c.onMessageAccepted(text);
                }}));
            }
            @Override public void onSendFailure(final String text,final String error) {
                // A very fast rejection may be observed inside steer() itself. Defer
                // restoration until submit() has finished clearing the dispatched draft.
                ui(sendHandoff.publication(new Runnable() { public void run() {
                    // Closing a transport during an explicit reconnect can reject its
                    // pending follow-ups. Preserve that user-owned text in this same
                    // conversation even after its transport generation has retired.
                    if(!closed && ownerAgent.equals(agent) && ownerThread!=null && ownerThread.equals(sessionId))
                        c.onSendFailure(text,error);
                }}));
            }
            @Override public void onPermission(final String r, final String t, final Map<String, Object> in) { deliver(new Runnable() { public void run() { c.onPermission(r,t,in); }}); }
            @Override public void onResult(final boolean ok, final double cost, final long ms, final String t) { deliver(new Runnable() { public void run() { c.onResult(ok,cost,ms,t); }}); }
            @Override public void onExit(final int code, final String err) { deliver(new Runnable() { public void run() { c.onExit(code,err); }}); }
            @Override public void onAuth(final String url, final String code) { deliver(new Runnable() { public void run() { c.onAuth(url,code); }}); }
        };
    }

    /** A new Activity took over Trough's root view (the old one was swiped away or recreated), or null: no window. */
    boolean mediaResult(Object host,String event,Object[] args) { return MediaPanel.result(host,event,args,root); }

    private void presentPendingShort() {
        Object pending=root.getTag(0x7f0d7475);
        if(a==null || !(pending instanceof String[])) return;
        final String[] request=(String[])pending;root.setTag(0x7f0d7475,null);
        ShortPresentation.Listener callback=new ShortPresentation.Listener(){public void finished(Map<String,Object> result){
            result.put("id",request[0]);inboxBridge.finishPresentation(request[1],result);
        }};
        File redditRequest=new File(c.getFilesDir(),"home/.trough/reddit-present");
        if(redditRequest.isFile()){redditRequest.delete();followTail=false;RedditReader.show(a,feed,scroll);callback.finished(Json.map("ok",true,"kind","reddit","inline",true));return;}
        File claudeSignInRequest=new File(c.getFilesDir(),"home/.trough/claude-sign-in-present");
        if(claudeSignInRequest.isFile()){claudeSignInRequest.delete();ClaudeSignIn.show(a,root,home,agents.get("claude"),cwd,env);callback.finished(Json.map("ok",true,"kind","claude-sign-in","inline",true));return;}
        File halfSwordRequest=new File(c.getFilesDir(),"home/.trough/half-sword-present");
        if(halfSwordRequest.isFile()){halfSwordRequest.delete();followTail=false;HalfSwordView.show(a,gameDock,scroll,root);callback.finished(Json.map("ok",true,"kind","half-sword-preview","inline",true));return;}
        File arenaRequest=new File(c.getFilesDir(),"home/.trough/arena-present");
        if(arenaRequest.isFile()){arenaRequest.delete();ArenaView.show(a,feed,scroll);callback.finished(Json.map("ok",true,"kind","arena","inline",true));return;}
        final File mediaDir=new File(c.getFilesDir(),"home/.trough/media");
        if(new File(mediaDir,"playback-paused").isFile()){callback.finished(Json.map("ok",false,"error","Media presentation paused"));return;}
        try {
            File selection=new File(mediaDir,"selected-media.txt");
            if(selection.isFile()) {
                String name=new String(Payloads.readSmall(selection),"UTF-8").trim();
                File chosen=new File(mediaDir,name);
                if(!name.isEmpty() && name.equals(chosen.getName()) && chosen.isFile()) {
                    final int index=feed.getChildCount();DogshitPlayer.showInline(a,chosen,callback,feed);
                    scroll.postDelayed(new Runnable(){public void run(){if(!DogshitPlayer.isPinned(feed)&&index<feed.getChildCount())scroll.smoothScrollTo(0,feed.getChildAt(index).getTop());}},350);return;
                }
            }
        } catch(Exception ignored) {}
        File synthesis=new File(mediaDir,"dogshit-night-current.wav");
        if(synthesis.isFile()) {
            final int index=feed.getChildCount();DogshitPlayer.showInline(a,synthesis,callback,feed);
            scroll.postDelayed(new Runnable(){public void run(){if(!DogshitPlayer.isPinned(feed)&&index<feed.getChildCount())scroll.smoothScrollTo(0,feed.getChildAt(index).getTop());}},350);return;
        }
        File remixed=new File(mediaDir,"them-changes-recomposed.mp4");
        if(remixed.isFile()) {
            final int index=feed.getChildCount();DogshitPlayer.showInline(a,remixed,callback,feed);
            scroll.postDelayed(new Runnable(){public void run(){if(!DogshitPlayer.isPinned(feed)&&index<feed.getChildCount())scroll.smoothScrollTo(0,feed.getChildAt(index).getTop());}},350);return;
        }
        final File thundercat=new File(mediaDir,"them-changes.mp4");
        if(new File(mediaDir,"them-changes-video.mp4").isFile() && new File(mediaDir,"them-changes-audio.m4a").isFile()) {
            final ShortPresentation.Listener target=callback;
            new Thread(new Runnable(){public void run(){try{
                if(!thundercat.isFile())MediaJoin.join(new File(mediaDir,"them-changes-video.mp4"),new File(mediaDir,"them-changes-audio.m4a"),thundercat);
                MAIN.post(new Runnable(){public void run(){if(a==null){target.finished(Json.map("ok",false,"error","No window"));return;}final int index=feed.getChildCount();DogshitPlayer.showInline(a,thundercat,target,feed);scroll.postDelayed(new Runnable(){public void run(){if(!DogshitPlayer.isPinned(feed)&&index<feed.getChildCount())scroll.smoothScrollTo(0,feed.getChildAt(index).getTop());}},350);}});
            }catch(final Exception error){MAIN.post(new Runnable(){public void run(){target.finished(Json.map("ok",false,"error",error.toString()));}});}}},"Trough track prepare").start();return;
        }
        File mediaFile=new File(c.getFilesDir(),"home/.trough/media/requested-video.mp4");
        if(mediaFile.isFile()) {
            final int mediaIndex=feed.getChildCount();
            DogshitPlayer.showInline(a,mediaFile,callback,feed);
            scroll.postDelayed(new Runnable(){public void run(){
                if(!DogshitPlayer.isPinned(feed)&&mediaIndex<feed.getChildCount())scroll.smoothScrollTo(0,feed.getChildAt(mediaIndex).getTop());
            }},350);
        }
        else ShortPresentation.show(a,callback);
    }
    /** Explicit simulator requests must not depend on an unrelated media callback. */
    private void focusHalfSwordRequest(){
        File focusRequest=new File(c.getFilesDir(),"home/.trough/half-sword-focus-request");
        if(!closed&&a!=null&&focusRequest.isFile()){
            try{android.content.ComponentName component=a.getComponentName();if(component==null)throw new IllegalStateException("Resident Activity unavailable");android.content.Intent intent=new android.content.Intent().setComponent(component);intent.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);a.startActivity(intent);focusRequest.delete();Payloads.writeSmall(new File(c.getFilesDir(),"home/.trough/half-sword-focus-result.json"),new org.json.JSONObject().put("requested",true).put("component",component.flattenToShortString()).put("time_ms",System.currentTimeMillis()).toString());}
            catch(Exception error){focusRequest.delete();try{Payloads.writeSmall(new File(c.getFilesDir(),"home/.trough/half-sword-focus-result.json"),new org.json.JSONObject().put("requested",false).put("error",error.toString()).put("time_ms",System.currentTimeMillis()).toString());}catch(Exception ignored){}}
        }
    }
    private void presentHalfSwordRequest(){
        if(closed||a==null||!HalfSwordView.flag(c).isFile())return;
        File request=new File(c.getFilesDir(),"home/.trough/half-sword-present");
        if(!request.isFile())return;
        long requested=request.lastModified();
        try{HalfSwordView.show(a,gameDock,scroll,root);request.delete();Payloads.writeSmall(new File(c.getFilesDir(),"home/.trough/half-sword-present-result.json"),new org.json.JSONObject().put("requested_ms",requested).put("presented",true).put("time_ms",System.currentTimeMillis()).toString());}
        catch(Exception error){request.delete();try{Payloads.writeSmall(new File(c.getFilesDir(),"home/.trough/half-sword-present-result.json"),new org.json.JSONObject().put("requested_ms",requested).put("presented",false).put("error",error.toString()).put("time_ms",System.currentTimeMillis()).toString());}catch(Exception ignored){}}
    }

    private void openHalfSwordSimulator(){
        if(closed||a==null)return;
        try{HalfSwordView.flag(c).createNewFile();new File(c.getFilesDir(),"home/.trough/half-sword-expanded").createNewFile();new File(c.getFilesDir(),"home/.trough/half-sword-fullscreen").createNewFile();HalfSwordView.show(a,gameDock,scroll,root);}
        catch(Exception error){note("Simulator could not open: "+error.getClass().getSimpleName());}
    }
    private void restoreHalfSwordPreview(){if(a!=null && HalfSwordView.flag(c).isFile()){HalfSwordView.show(a,gameDock,scroll,root);scroll.post(new Runnable(){public void run(){if(!closed)jumpToBottom();}});}}
    void attach(Activity na) {
        MediaPanel.attach(na,root);
        if (a != na) dismissCosmicApprovals();
        a = na;
        if(na!=null) root.post(new Runnable(){public void run(){presentPendingShort();if(a!=null && ArenaView.flag(c).isFile())ArenaView.show(a,feed,scroll);restoreHalfSwordPreview();ClaudeSignIn.restore(root);}});
        if (a != null) a.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        Power.attach(na);
        startStatusTicker();
        scheduleCosmicRestore();
    }

    /** The live transport survives code replacement; only a native gesture or disk publication
     *  finishes on its current owner before views change. Neither blocks input or autosend. */
    void probeImportedGameSurface(){GameInlineProbe.start(a,gameDock);}
    void attachImportedGame(){GameSurface.start(c,a,feed);}

    Map<String, Object> handoff() {
        NativeHomePanel.saveTracePosition(root);
        persistDraft();
        if(login!=null || enqueuePending || inboxBridge.hasPendingWrites() || keyboard.hasPendingPress() || holdPointer>=0) return null;
        Map<String,Object> visualHandoff=surfaceArt.handoff();
        if(visualHandoff==null) return null;
        flushStream(false);
        if(liveSource!=null && liveEntry>=0 && liveEntry<log.size()) log.get(liveEntry)[1]=liveSource.toString();
        ArrayList<String[]> entries = new ArrayList<>();
        for (int i = 0; i < log.size(); i++) entries.add(log.get(i).clone());
        Map<String, Object> m = new HashMap<>();
        m.put("entries", entries);
        m.put("historyEnd",historyEnd);m.put("browsingHistory",browsingHistory);
        m.put("transcript_ready",transcriptReady);
        m.put("transcript_prefix",new ArrayList<String[]>(transcriptPrefix));
        m.put("redditCommunity",RedditReader.openCommunity(feed));
        m.put("redditState",RedditReader.snapshot(feed));
        m.put("mediaState",DogshitPlayer.snapshot(feed));
        m.put("draft", input.getText().toString());
        m.put("lastUserPrompt",lastUserPrompt);
        m.put("workPolicy",workPolicy.exportState(android.os.SystemClock.elapsedRealtime()));
        m.put("activeWorkId",activeWorkId);m.put("backgroundYielded",backgroundYielded);m.put("backgroundStartLogIndex",backgroundStartLogIndex);m.put("backgroundWires",new java.util.LinkedHashMap<String,String>(backgroundWires));
        m.put("routeUpgraded",routeUpgraded);m.put("selectedRoute",selectedRoute);m.put("selectedEffort",selectedEffort);
        m.put("heuristic",heuristic.encode());
        m.put("autoSendArmed",autoSendArmed);m.put("holdLatched",holdIntent.latched());
        m.put("draftEditedAt",draftEditedAt);
        m.put("selection", new int[]{input.getSelectionStart(), input.getSelectionEnd()});
        m.put("focused", input.hasFocus());
        m.put("scrollY", scroll.getScrollY());
        m.put("atBottom", !scroll.canScrollVertically(1));
        m.put("cost", cost);
        m.put("model", model);
        m.put("agent",agent);m.put("session_id",sessionId);m.put("initialized",initialized);
        m.put("busy",busy);m.put("stopping",stopping);m.put("authRequired",authRequired);m.put("lastError",lastError);
        m.put("activeInboxId",activeInboxId);m.put("activeInboxWire",activeInboxWire);
        m.put("inboxAcknowledgements",new HashMap<String,String>(inboxAcknowledgements));
        m.put("inboxSnapshot",inboxSnapshot);
        m.put("permissions",new HashMap<String,Map<String,Object>>(permissionRequests));
        m.put("liveEntry",liveEntry);m.put("liveSource",liveSource==null?null:liveSource.toString());
        m.put("workChecks",new ArrayList<Object>(workChecks));m.put("workFiles",new ArrayList<Object>(workFiles));
        m.put("workOutput",workOutput.toString());m.put("outputTab",root.getTag(0x7f0d74d0));
        m.put("pendingTeaching",new ArrayList<Map<String,Object>>(pendingTeaching));
        if(session instanceof ResidentAgent) m.put("resident_agent",((ResidentAgent)session).suspend());
        m.put("workProgress",workSnapshot);
        m.put("timing",timingState());
        m.put("workStarted",workStarted);
        m.put("surfaceState",visualHandoff);
        m.put("keyboardState",keyboard.handoff());
        m.put("keyboardCollapsed",keyboardCollapsed);m.put("thoughtDraft",draftForWork?thoughtDraft:input.getText().toString());m.put("workDraft",draftForWork?input.getText().toString():workDraft);
        m.put("cosmicOpen", reopenCosmic || Boolean.TRUE.equals(CosmicPanel.state().get("showing")));
        handingOff=true;
        return m;
    }

    @SuppressWarnings("unchecked")
    private void restore(Map<?, ?> state) {
        if (state == null) return;
        long restoringAt=android.os.SystemClock.elapsedRealtime();
        resumed = true;
        activeWorkId=Json.str((Map)state,"activeWorkId");if(activeWorkId==null) activeWorkId="";
        backgroundYielded=Boolean.TRUE.equals(state.get("backgroundYielded"));
        Map<String,Object> wires=Json.obj(state.get("backgroundWires"));
        if(wires!=null) for(Map.Entry<String,Object> item:wires.entrySet())
            if(item.getValue() instanceof String && backgroundWires.size()<8) backgroundWires.put(item.getKey(),(String)item.getValue());
        Object startIndex=state.get("backgroundStartLogIndex");backgroundStartLogIndex=startIndex instanceof Number?((Number)startIndex).intValue():0;
        routeUpgraded=Boolean.TRUE.equals(state.get("routeUpgraded"));
        selectedRoute=Json.str((Map)state,"selectedRoute");if(selectedRoute==null) selectedRoute="";
        selectedEffort=Json.str((Map)state,"selectedEffort");if(selectedEffort==null) selectedEffort="";
        holdIntent.restore(Boolean.TRUE.equals(state.get("holdLatched")));
        autoSendHeld=holdIntent.paused();hold.setText(holdIntent.latched()?"HELD":"HOLD");hold.setPressed(autoSendHeld); // Only an intentional latch survives reload.
        residentTicket=Json.obj(state.get("resident_agent"));
        if(residentTicket!=null) {
            String selected=Json.str((Map)state,"agent");if(selected!=null) agent=selected;
            sessionId=Json.str((Map)state,"session_id");initialized=Boolean.TRUE.equals(state.get("initialized"));
            busy=Boolean.TRUE.equals(state.get("busy"));stopping=Boolean.TRUE.equals(state.get("stopping"));
            authRequired=Boolean.TRUE.equals(state.get("authRequired"));
            String error=Json.str((Map)state,"lastError");if(error!=null) lastError=error;
            activeInboxId=Json.str((Map)state,"activeInboxId");activeInboxWire=Json.str((Map)state,"activeInboxWire");
            Map<String,Object> acknowledgements=Json.obj(state.get("inboxAcknowledgements"));
            if(acknowledgements!=null) for(Map.Entry<String,Object> e:acknowledgements.entrySet())
                if(e.getValue() instanceof String) inboxAcknowledgements.put(e.getKey(),(String)e.getValue());
            Map<String,Object> snapshot=Json.obj(state.get("inboxSnapshot"));if(snapshot!=null) inboxSnapshot=snapshot;
            Map<String,Object> permissions=Json.obj(state.get("permissions"));
            if(permissions!=null) for(Map.Entry<String,Object> e:permissions.entrySet()) {
                Map<String,Object> request=Json.obj(e.getValue());if(request!=null) carriedPermissions.put(e.getKey(),request);
            }
        }
        List<Object> teachings=Json.arr(state.get("pendingTeaching"));
        if(teachings!=null) for(Object item:teachings) { Map<String,Object> teaching=Json.obj(item);if(teaching!=null) pendingTeaching.add(teaching); }
        Object learned=state.get("heuristic");
        if(learned instanceof String) try { heuristic.restore((String)learned);heuristicReady=true; }
            catch(RuntimeException ignored) {}
        Object previousPrompt=state.get("lastUserPrompt");
        if(previousPrompt instanceof String) lastUserPrompt=(String)previousPrompt;
        Object surface=state.get("surfaceState");
        Object keys=state.get("keyboardState");
        if(keys instanceof Map) keyboard.restore((Map<?,?>)keys);
        if(surface instanceof Map) surfaceArt.restore((Map<String,Object>)surface);
        reopenCosmic = false;
        Object progress=state.get("workProgress");
        Map<String,Object> timing=Json.obj(state.get("timing"));
        if(timing!=null) {
            turnStartedAt=timingValue(timing,"turn_started_at");
            lastAgentActivityAt=timingValue(timing,"last_agent_activity_at");
            lastUserActivityAt=timingValue(timing,"last_user_activity_at");
            observedUserActivity=lastUserActivityAt;
            turnFinishedAt=timingValue(timing,"turn_finished_at");
            turnLastDuration=timingValue(timing,"turn_last_duration_ms");
            String outcome=Json.str(timing,"turn_outcome");
            if(outcome!=null) turnOutcome=outcome;
        }
        Object previousWorkStarted=state.get("workStarted");
        if(previousWorkStarted instanceof Number) workStarted=((Number)previousWorkStarted).longValue();
        Object entries = state.get("entries");
        if (entries instanceof List) for (Object e : (List<Object>) entries)
            if (e instanceof String[] && ((String[]) e).length == 2) {
                String[] item = ((String[]) e).clone();
                log.add(item);
                int index=log.size()-1;

            }
        browsingHistory=Boolean.TRUE.equals(state.get("browsingHistory"));
        Object savedHistoryEnd=state.get("historyEnd");
        showHistory(browsingHistory&&savedHistoryEnd instanceof Number?((Number)savedHistoryEnd).intValue():historySize());
        if(progress instanceof Map) showWork((Map<String,Object>)progress);
        List<Object> checks=Json.arr(state.get("workChecks")),files=Json.arr(state.get("workFiles"));
        if(checks!=null) workChecks.addAll(checks);if(files!=null) workFiles.addAll(files);
        Object workText=state.get("workOutput");if(workText instanceof String) workOutput.append((String)workText);
        Object streaming=state.get("liveSource"),index=state.get("liveEntry");
        if(residentTicket!=null && streaming instanceof String && index instanceof Number) {
            int i=((Number)index).intValue();
            if(i>=0 && i<log.size()
                && isAssistantKind(log.get(i)[0])) {
                liveEntry=i;live=null;
                for(int n=0;n<feed.getChildCount();n++){View v=feed.getChildAt(n);if(v instanceof TextView&&Integer.valueOf(transcriptPrefix.size()+i).equals(v.getTag(HISTORY_TAG)))live=(TextView)v;}
                if(live==null){live=entry(log.get(i)[0],(String)streaming);live.setTag(HISTORY_TAG,Integer.valueOf(transcriptPrefix.size()+i));}
                liveSource=new StringBuilder((String)streaming);
                live.setText(HistoryPreview.text((String)streaming),TextView.BufferType.EDITABLE);
            }
        }
        if(retainedEntries!=null) retainedEntries.clear();
        Object draft = state.get("draft"), sel = state.get("selection");
        if (draft instanceof String) {
            input.setText((String) draft);
            if (sel instanceof int[] && ((int[]) sel).length == 2) {
                int n = input.length(), s0 = Math.min(((int[]) sel)[0], n), s1 = Math.min(((int[]) sel)[1], n);
                if (s0 >= 0 && s1 >= 0) input.setSelection(s0, s1);
            }
        }
        if (Boolean.TRUE.equals(state.get("focused"))) input.requestFocus();
        if (state.get("cost") instanceof Double) cost = (Double) state.get("cost");
        if (state.get("model") instanceof String) model = (String) state.get("model");
        final boolean bottom = !Boolean.FALSE.equals(state.get("atBottom"));
        followTail=bottom;
        final int y = state.get("scrollY") instanceof Integer ? (Integer) state.get("scrollY") : 0;
        // Before the first draw of the new screen: the old one stays up until this one is laid out.
        scroll.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                if (bottom) scroll.scrollTo(0, Math.max(0, feed.getHeight() - scroll.getHeight()));
                else scroll.scrollTo(0, y);
                return true;
            }
        });
        final Object redditState=state.get("redditState");
        final Object redditCommunity=state.get("redditCommunity");
        if(a!=null && redditCommunity instanceof String) scroll.post(new Runnable(){public void run(){if(!closed){followTail=false;if(redditState instanceof Map)RedditReader.restore(a,feed,scroll,(Map<?,?>)redditState);else RedditReader.show(a,feed,scroll,(String)redditCommunity);}}});
        final Object mediaState=state.get("mediaState");
        if(a!=null && mediaState instanceof Map && !new File(c.getFilesDir(),"home/.trough/media/playback-paused").isFile()) scroll.post(new Runnable(){public void run(){if(!closed)DogshitPlayer.restore(a,feed,(Map<?,?>)mediaState);}});
        restoreDurationMs=android.os.SystemClock.elapsedRealtime()-restoringAt;
    }

    /** Shares may arrive from this editor's own selection menu: never append it to itself. */
    void intent(android.content.Intent i) {
        String action = i.getAction();
        if (android.content.Intent.ACTION_MAIN.equals(action)) return;
        lastSharedAction=action==null?"":action;
        CharSequence shared = i.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT);
        if (shared == null) shared = i.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT);
        if (shared != null) {
            String value=shared.toString(),current=input.getText().toString();
            if(value.isEmpty()) return;
            boolean already=current.equals(value) || (android.content.Intent.ACTION_PROCESS_TEXT.equals(action) && current.contains(value));
            if(!already) {
                int start=Math.max(0,input.getSelectionStart()),end=Math.max(start,input.getSelectionEnd());
                input.getText().replace(Math.min(start,input.length()),Math.min(end,input.length()),value);
            }
            input.requestFocus(); queueAutoSubmit();
            return;
        }
        note("received " + action + (i.getData() != null ? " " + i.getData() : "") + (i.getType() != null ? " (" + i.getType() + ")" : ""));
    }

    void close() {
        ProjectWorker.detach(root);
        MAIN.removeCallbacks(checkpointTranscript);
        persistTranscript();
        transcriptStore.close();
        if(heuristicReady && !handingOff) inboxBridge.saveHeuristic(heuristic.encode());
        MAIN.removeCallbacks(saveLearning);
        if(learningDialog!=null) learningDialog.dismiss();
        if(!handingOff) persistDraft();
        if(!handingOff) finishStream();
        closed = true;
        root.removeOnAttachStateChangeListener(rootAttachment);
        halfSwordProgress.close();
        try { DogshitPlayer.release(feed); } catch (RuntimeException error) { android.util.Log.w("Trough", "Media teardown failed", error); }
        cancelConnectionTimers();
        MAIN.removeCallbacks(autoSubmit); autoSendArmed=false; input.clearCountdown();
        MAIN.removeCallbacks(saveWorkState); MAIN.removeCallbacks(saveDraft); MAIN.removeCallbacks(pollInbox); inboxBridge.close();
        MAIN.removeCallbacks(statusTick);
        MAIN.removeCallbacks(renderStream); streamQueued=false;
        root.removeOnLayoutChangeListener(keyboardLayout);
        MAIN.removeCallbacks(surfaceRefresh); surfaceQueued=false;
        visualSteering.close(); keyboard.close();
        ++generation;
        cancelStopFallback();
        surfaceArt.close();
        if(workVisual!=null) workVisual.close();
        if(workDialog!=null) workDialog.dismiss();
        dismissCosmicApprovals();
        CosmicPanel.close();
        Power.stop();
        if (session != null) session.close();
    }

    private void beginAutoSendHold(int pointer) {
        if(closed) return;
        holdPointer=pointer;autoSendHeld=true;hold.setPressed(true);
        MAIN.removeCallbacks(autoSubmit);autoSendArmed=false;input.clearCountdown();
        lastUserActivityAt=android.os.SystemClock.elapsedRealtime();
        persistDraft();paint();
    }

    private void releaseAutoSendHold(boolean restart) {
        boolean wasHeld=autoSendHeld || holdPointer>=0;
        holdPointer=-1;holdIntent.cancel();autoSendHeld=holdIntent.paused();
        surfaceArt.setPlaying(false);
        if(hold!=null) { hold.setPressed(autoSendHeld);hold.setText(holdIntent.latched()?"HELD":"HOLD"); }
        if(!wasHeld && !restart) return;
        MAIN.removeCallbacks(autoSubmit);autoSendArmed=false;input.clearCountdown();
        if(!closed && !handingOff) {
            lastUserActivityAt=android.os.SystemClock.elapsedRealtime();
            queueAutoSubmit();persistDraft();paint();
        }
    }

    /** A glide owns its entire physical gesture, including time before any text is committed. */
    private void setKeyboardCollapsed(boolean collapsed) {
        keyboardCollapsed=collapsed;
        keyboard.setVisibility(collapsed?View.GONE:View.VISIBLE);
        keyboardToggle.setText(collapsed?"Show keyboard ▴":"Hide keyboard ▾");
        keyboardToggle.setContentDescription(collapsed?"Expand keyboard":"Collapse keyboard to show more chat");
        root.requestLayout();
        if(collapsed && composerReady) scroll.post(new Runnable(){public void run(){if(!closed)jumpToBottom();}});
    }

    private void keyboardGestureChanged(boolean active) {
        if(keyboardGestureActive==active) return;
        keyboardGestureActive=active;
        if(closed || handingOff || !composerReady || suppressAutoSend) return;
        lastUserActivityAt=android.os.SystemClock.elapsedRealtime();
        MAIN.removeCallbacks(autoSubmit);autoSendArmed=false;input.clearCountdown();
        // Last finger up/cancel starts a complete pause; decoder refinement never extends it.
        if(!active) queueAutoSubmit();
    }

    private void queueAutoSubmit() {
        MAIN.removeCallbacks(autoSubmit);
        if(autoSendHeld || keyboardGestureActive || keyboard.hasPendingPress()) { autoSendArmed=false;input.clearCountdown();return; }
        autoSendArmed=!input.getText().toString().trim().isEmpty();
        if(!autoSendArmed) { input.clearCountdown(); return; }
        draftEditedAt=android.os.SystemClock.uptimeMillis();
        String text=input.getText().toString();
        if((login!=null || text.trim().startsWith("/")) && !text.endsWith("\n")) input.clearCountdown();
        else input.countdown(draftEditedAt,AUTO_SEND_PAUSE_MS);
        MAIN.postDelayed(autoSubmit,AUTO_SEND_PAUSE_MS);
    }

    private void persistDraft() {
        if(inboxBridge==null || input==null || suppressAutoSend) return;
        String text=input.getText().toString();
        if(login!=null || text.trim().startsWith("/key ")) text="";
        if(text.equals(lastScheduledDraft)) return;
        lastScheduledDraft=text;if(draftForWork)workDraft=text;else thoughtDraft=text;
        inboxBridge.saveOutputDraft(draftForWork?"work":"thought",text);
        if(!draftForWork)inboxBridge.saveDraft(text);
    }

    private int inboxCount(String key) {
        return InboxLaneCounts.count(inboxSnapshot,key,agent);
    }

    private void queueMessage(final String text,final String capturedDraft) {
        if(enqueuePending) return;
        lastUserActivityAt=android.os.SystemClock.elapsedRealtime();
        enqueuePending=true; paint();
        final boolean projectMessage=draftForWork;
        inboxBridge.enqueue(text,projectMessage?"halfsword":agent,projectMessage?null:(initialized?sessionId:null),new Runnable() { public void run() {
            enqueuePending=false;
            if(!projectMessage)lastUserPrompt=text; surfaceArt.promptSent(text);
            String context=text.length()>2048?text.substring(0,2048):text;
            heuristic.observe(context,semanticValues,sceneOrdinal(context),busy);
            if(!heuristicReady) pendingTeaching.add(Json.map("kind","text","text",text,"context",context,
                "semantic",new HashMap<String,Object>(semanticValues),"busy",busy));
            else if(heuristic.learnText(text)) { inboxBridge.saveHeuristic(heuristic.encode());refreshSurface(); }
            if(!projectMessage)add(record("user",text));
            String current=input.getText().toString();
            clearingSavedDraft=true;
            try {
                if(current.equals(capturedDraft)) input.getText().clear();
                else if(current.startsWith(capturedDraft)) input.getText().delete(0,capturedDraft.length());
                else { MAIN.removeCallbacks(autoSubmit); autoSendArmed=false; input.clearCountdown(); }
            } finally { clearingSavedDraft=false; }
            lastScheduledDraft=null; persistDraft();
            queueSurfaceRefresh();
            input.requestFocus(); paint();
        }},new Runnable() { public void run() { enqueuePending=false; autoSendArmed=false; input.clearCountdown(); paint(); }});
    }

    private void dispatchInbox(final Map<String,Object> message) {
        if(closed || !agent.equals(Json.str(message,"agent"))) return;
        String thread=Json.str(message,"session_id");
        if(thread!=null && !thread.isEmpty() && !thread.equals(sessionId)) return;
        final String id=Json.str(message,"id"),text=Json.str(message,"text");
        Map<String,Object> current=session instanceof ResidentAgent?((ResidentAgent)session).state():Json.map();
        Map<String,Object> currentBackend=Json.obj(current.get("backend_state"));
        Map<String,Object> currentHealth=currentBackend==null?null:Json.obj(currentBackend.get("health"));
        boolean preparing=currentHealth!=null && currentHealth.get("active_compactions") instanceof Number
            && ((Number)currentHealth.get("active_compactions")).intValue()>0;
        if(busy && preparing && !"stop".equals(Json.str(MessageTriage.classify(text),"kind")))return;
        if(busy && !activeWorkId.isEmpty() && !stopping && !"stop".equals(Json.str(MessageTriage.classify(text),"kind"))) {
            backgroundYielded=true;interruptTurn(false);return;
        }
        if(busy && "stop".equals(Json.str(MessageTriage.classify(text),"kind"))) {
            inboxBridge.transitionIf(id,"queued","incorporated","Explicit stop handled by Trough",
                new Runnable() { public void run() { if(!closed) stopTurn(); }},null,null);
            return;
        }
        if(stopping || enqueuePending || activeInboxId!=null || session==null || !initialized
            || login!=null || authRequired || !pendingPermissions.isEmpty() || !sendHandoff.ready(session)) return;
        Map<String,Object> transport=session instanceof ResidentAgent?((ResidentAgent)session).state():Json.map();
        Map<String,Object> backend=Json.obj(transport.get("backend_state"));
        String action=WorkTurnSchedule.messageAction(busy,!activeWorkId.isEmpty(),
            backend!=null && "contextCompaction".equals(Json.str(backend,"last_item_type")),session.supportsSteering());
        if("wait".equals(action))return;
        if("interrupt".equals(action)) { interruptTurn(false);return; }
        final boolean followup="steer".equals(action);
        if(!followup && !ensureRoute("gpt-6.1-sol",foregroundEffort())) return;
        final Agent owner=session;
        final String wire=text+"\n\n[Trough message "+id+". Answer this user message at the next safe checkpoint before continuing project work. Check the live queue; instructions are in AGENTS.md.]";
        activeInboxId=id; activeInboxWire=wire; inboxAcknowledgements.put(wire,id);
        final Runnable releaseClaim=new Runnable() { public void run() {
            if(id.equals(activeInboxId)) { activeInboxId=null; activeInboxWire=null; }
            inboxAcknowledgements.remove(wire);
        }};
        inboxBridge.transitionIf(id,"queued","sending","Dispatching saved message",new Runnable() { public void run() {
            if(closed) return;
            if(owner!=session || (busy && !followup) || (!busy && followup) || stopping || !initialized) {
                activeInboxId=null; activeInboxWire=null; inboxAcknowledgements.remove(wire);
                inboxBridge.transition(id,"queued","Waiting for the active conversation",null); return;
            }
            try {
                noteWorkUserActivity();
                if(followup) owner.steer(wire); else beginTurn(text,wire,false);
                if(!owner.supportsAcknowledgements()) settleInbox("dispatched","Written to backend; delivery acknowledgement is unavailable");
            } catch(RuntimeException error) { settleInbox(MessageInbox.deliveryFailureState(error.toString()),error.toString()); }
        }},releaseClaim,releaseClaim);
    }

    private void settleInbox(String state,String detail) {
        if(activeInboxId==null) return;
        String id=activeInboxId,wire=activeInboxWire;
        activeInboxId=null; activeInboxWire=null;
        if(!"uncertain".equals(state)) inboxAcknowledgements.remove(wire);
        inboxBridge.transition(id,state,detail,null);
    }

    @Override public void onMessageAccepted(final String text) {
        ui(new Runnable() { public void run() {
            String id=inboxAcknowledgements.remove(text);
            if(id==null) return;
            if(id.equals(activeInboxId)) { activeInboxId=null; activeInboxWire=null; }
            inboxBridge.transition(id,"accepted","Backend acknowledged this message",null);
        }});
    }

    private void showInbox() {
        if(a==null) return;
        List<Object> messages=Json.arr(inboxSnapshot.get("messages"));
        if(messages==null || messages.isEmpty()) { note("No saved messages yet. New messages queue after a typing pause."); return; }
        final ArrayList<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();
        ArrayList<String> labels=new ArrayList<String>();
        for(int i=messages.size()-1;i>=0 && rows.size()<40;i--) {
            Map<String,Object> item=Json.obj(messages.get(i));
            if(item==null) continue;
            rows.add(item); labels.add(Json.str(item,"state")+" · "+clip(Json.str(item,"text")));
        }
        new android.app.AlertDialog.Builder(a).setTitle("Saved messages")
            .setItems(labels.toArray(new String[0]),new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface dialog,int which) {
                    final Map<String,Object> item=rows.get(which);
                    String state=Json.str(item,"state");
                    android.app.AlertDialog.Builder detail=new android.app.AlertDialog.Builder(a)
                        .setTitle(state).setMessage(Json.str(item,"text")+"\n\n"+Json.str(item,"detail"))
                        .setPositiveButton("Close",null);
                    if("uncertain".equals(state) || "failed".equals(state) || "held".equals(state)) detail.setNeutralButton("Retry",new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface dialog,int which) {
                            inboxBridge.retry(Json.str(item,"id"));
                        }
                    });
                    detail.show();
                }
            }).setPositiveButton("Close",null).show();
    }

    private ArrayList<String> nativeTraceInputs;private CharSequence nativeTraceCached;
    private CharSequence nativeHomeTrace() {
        final int total=historySize();final int[] range=NativeHomePanel.traceRange(root,total);ArrayList<String[]> rows=new ArrayList<String[]>();for(int i=range[0];i<range[1];i++){String[] row=historyRow(i);rows.add(new String[]{row[0],row[1],String.valueOf(i)});}
        List<String[]> shown=NativeHomePanel.traceRows(root,rows);String liveText=range[1]==total&&liveSource!=null?liveSource.substring(Math.max(0,liveSource.length()-4500)):null;ArrayList<String> inputs=new ArrayList<String>();for(String[] row:shown){inputs.add(row[0]);inputs.add(row[1]);inputs.add(row.length>2?row[2]:"");}inputs.add(liveText);inputs.add(String.valueOf(NativeHomePanel.traceMode(root)));inputs.add(NativeHomePanel.traceExpanded(root).toString());inputs.add(range[0]+":"+range[1]+":"+total);if(nativeTraceCached!=null&&inputs.equals(nativeTraceInputs))return nativeTraceCached;
        NativeTraceRenderer.Toggle toggle=new NativeTraceRenderer.Toggle(){public void toggle(String key){NativeHomePanel.toggleTraceItem(root,key);NativeHomePanel.trace(root,nativeHomeTrace());}};
        CharSequence body=NativeTraceRenderer.render(shown,liveText,NativeHomePanel.traceMode(root)==2,NativeHomePanel.traceExpanded(root),toggle);
        nativeTraceInputs=inputs;nativeTraceCached=NativeHomePanel.traceMode(root)==2?NativeTraceRenderer.history(body,range[0],total-range[1],toggle):body;return nativeTraceCached;
    }

    private void submit() {
        MAIN.removeCallbacks(autoSubmit); autoSendArmed=false; input.clearCountdown();
        String text = input.getText().toString().trim();
        if(text.equals("/work") || text.startsWith("/work ")) {
            input.setText("");
            String command=text.length()>5?text.substring(6).trim():"status";
            Map<String,Object> result=controlWork(command);
            note(Json.write(result));return;
        }
        if(text.equals("/reddit") || text.startsWith("/reddit ")) { String sub=text.length()>7?text.substring(8).trim():null;if(sub!=null&&!sub.matches("[A-Za-z0-9_]{1,40}")){note("Use /reddit followed by a community name");return;}input.setText("");followTail=false;if(a!=null)RedditReader.show(a,feed,scroll,sub);return;}
        if(text.equals("/halfsword")) { input.setText(""); try { HalfSwordView.flag(c).createNewFile(); } catch(Exception ignored) {} if(a!=null)HalfSwordView.show(a,gameDock,scroll,root); return; }
        if(text.equals("/arena")) { input.setText(""); try { ArenaView.flag(c).createNewFile(); } catch(Exception ignored) {} if(a!=null)ArenaView.show(a,feed,scroll); return; }
        if(text.equals("/music") || text.equals("/media")) { input.setText(""); MediaPanel.show(a,root); return; }
        if (text.equals("/reload")) {
            input.setText("");
            note("rebuilding Trough from files/src (swaps in only if it changed)");
            reload.run();
            return;
        }
        if (login != null && !text.isEmpty() && !text.startsWith("/")) {
            input.setText("");   // the sign-in code goes to claude's login only, never the feed or a model
            login.code(text);
            note("code sent, finishing sign-in");
            return;
        }
        if (text.equals("/help")) {
            input.setText("");
            note("Tap the status line to pick the agent (" + android.text.TextUtils.join(", ", agents.keySet()) + ").\n"
                + "/login  sign claude in on this phone (its own login, not the VM's)\n"
                + "/key <secret>  API key for a model Trough runs itself (home/.trough/models.json)\n"
                + "/work pause|resume|status  control idle project work\n"
                + "/media  local audio/video and offline generated music\n"
                + "/awake  keep Trough running: screen, cpu, back, battery exemption, child processes\n"
                + "/reload  rebuild Trough from files/src now (it also does this after every turn)\n"
                + "/help  this list");
            return;
        }
        if (text.equals("/awake") || text.startsWith("/awake ")) {
            input.setText("");
            note(Power.command(text.substring(6)));
            return;
        }
        if (text.equals("/login")) {
            input.setText("");
            if (!"claude".equals(agent)) { note("/login is for claude; " + agent + " signs in its own way"); return; }
            startLogin();
            return;
        }
        if (text.startsWith("/key ")) {
            input.setText("");   // the secret never reaches the feed or a model
            if (!Models.MARK.equals(agents.get(agent))) { note("/key is for models Trough runs itself (models.json); " + agent + " signs in its own way"); return; }
            try { Models.writeKey(home, agent, text.substring(5).trim()); note("key saved for " + agent); connect(); }
            catch (Exception e) { note("could not save key: " + e); }
            return;
        }
        if (text.isEmpty()) return;
        followTail=true;follow();
        if(NativeHomePanel.consumeInput(root,text)){input.setText("");input.requestFocus();return;}
        String homeContext=NativeHomePanel.inputContext(root);if(homeContext!=null)text=text+"\n\n[Trough home context: "+homeContext+". Use this instruction to improve the visible UI; keep text output optional.]";
        queueMessage(text,input.getText().toString());
    }

    /** Both input surfaces submit one captured message through the normal turn machinery. */
    private void beginTurn(String text) {
        beginTurn(text, text);
    }

    private void beginTurn(String text, String modelInput) {
        beginTurn(text,modelInput,true);
    }
    private void beginTurn(String text, String modelInput,boolean addUser) {
        if(session==null || !initialized || busy) throw new IllegalStateException("The model is not ready");
        if(!sendHandoff.ready(session)) throw new IllegalStateException("The previous follow-up is still being confirmed; your draft is preserved");
        session.send(modelInput);
        if(addUser || !text.equals(lastUserPrompt)) { lastUserPrompt=text; surfaceArt.promptSent(text); }
        cancelStopFallback(); stopping=false;
        if(addUser) add(record("user", text));
        turnStartedAt=android.os.SystemClock.elapsedRealtime(); turnFinishedAt=0; turnOutcome="working";
        lastAgentActivityAt=turnStartedAt;
        workStarted=turnStartedAt; workChecks.clear(); workFiles.clear(); workOutput.setLength(0);
        workEvent("response","running",text);
        finishStream(); live=null; liveSource=null;
        lastError = "";
        busy = true;
        paint();
    }

    void interruptResponse() {
        workPolicy.responseInterrupted(android.os.SystemClock.elapsedRealtime());
        if(!activeWorkId.isEmpty()) backgroundYielded=true;
        persistWorkPolicy(null);
        interruptTurn(false);
    }

    /** Explicit stop/pause language remains a durable project pause. */
    void stopTurn() { interruptTurn(true); }

    private void interruptTurn(boolean pauseBackground) {
        if(pauseBackground) { workPolicy.pause("Stopped by user",android.os.SystemClock.elapsedRealtime());persistWorkPolicy(null); }
        if(!busy || stopping) return;
        stopping=true;
        final int requestedGeneration=generation;
        final Agent requestedSession=session;
        note("Stopping this response…");
        paint();
        stopFallback=new Runnable() { public void run() {
            stopFallback=null;
            if(closed || !busy || !stopping || generation!=requestedGeneration || session!=requestedSession) return;
            note("The response did not stop; reconnecting to the same conversation. Your draft is preserved.");
            report("recovery","explicit stop timed out; reconnecting without replay");
            connect();
        }};
        input.postDelayed(stopFallback,6000);
        try { if(requestedSession!=null) requestedSession.interrupt(); }
        catch(RuntimeException e) { lastError="Could not interrupt the response: "+e.getMessage(); }
    }

    private void cancelStopFallback() {
        if(stopFallback!=null) { input.removeCallbacks(stopFallback); stopFallback=null; }
    }

    // --- Agent.Listener: called on the reader thread, so everything hops to the UI thread ---

    @Override public void onInit(final String id, final String m) {
        ui(new Runnable() { @Override public void run() {
            markAgentActivity();
            sessionId = id;
            model = m == null ? "" : m;
            initialized = true;
            cancelConnectionTimers(); connectionReadyAt=android.os.SystemClock.elapsedRealtime();
            authRequired = false;
            lastError = "";
            try { Payloads.writeSmall(sessionFile(), id); } catch (Exception ignored) {}
            persistTranscript();
            report("init", "model " + model);
            paint();
        } });
    }

    @Override public void onTextDelta(final String t) {
        ui(new Runnable() { @Override public void run() {
            if(t==null || t.isEmpty()) return;
            markAgentActivity();
            if(live==null) {
                live=record("assistant",""); liveEntry=log.size()-1;
                liveSource=new StringBuilder(); live.setText("",TextView.BufferType.EDITABLE); add(live);
            }
            liveSource.append(t); pendingDelta.append(t);
            if(!streamQueued) { streamQueued=true; MAIN.postDelayed(renderStream,50); }
        } });
    }

    private void finishStream() { flushStream(true); }
    private void flushStream(boolean finish) {
        if(streamQueued) MAIN.removeCallbacks(renderStream);
        streamQueued=false;
        if(live==null || liveSource==null) { pendingDelta.setLength(0); return; }
        if(pendingDelta.length()>0) {
            String delta=pendingDelta.toString(); pendingDelta.setLength(0);
            if(liveSource.length()<=HistoryPreview.LIMIT) live.append(delta);
            else live.setText(HistoryPreview.text(liveSource.toString()));
            workEvent("response","running",delta);
        }
        if(finish) {
            String completed=liveSource.toString();
            live.setText(RichText.render(HistoryPreview.text(completed))); log.get(liveEntry)[1]=completed;
            bindToolDetails(live,"assistant",completed,true);
        }
        queueTranscript();
        follow();
    }

    @Override public void onSendFailure(final String text,final String error) {
        ui(new Runnable() { public void run() {
            if(closed) return;
            String backgroundId=text==null?null:backgroundWires.get(text);
            if(backgroundId!=null) {
                if(backgroundId.equals(activeWorkId)) { finishBackgroundWork(false,false,error);lastError=error;paint(); }
                return;
            }
            WorkPolicy.Lease work=workPolicy.currentLease();
            if(work!=null && work.running && text!=null && text.equals(work.prompt)) {
                finishBackgroundWork(false,false,error);lastError=error;paint();return;
            }
            String known=text==null?null:inboxAcknowledgements.get(text);
            String failureState=MessageInbox.deliveryFailureState(error);
            if(activeInboxId!=null && text!=null && text.equals(activeInboxWire)) {
                settleInbox(failureState,error);
            } else if(known!=null) {
                inboxBridge.transition(known,failureState,error,null);
            } else if(text!=null && !text.trim().isEmpty()) {
                inboxBridge.enqueueHeld(text,agent,sessionId,"Delivery was not confirmed: "+error);
            }
            note("failed".equals(failureState) ? "Your message was rejected and remains in Saved messages for pickup; it has not been resent." : "Your message is saved in Saved messages; delivery was not confirmed. Hold the status line to inspect or retry it.");
            paint();
        }});
    }

    @Override public void onAssistantText(final String t) {
        ui(new Runnable() { @Override public void run() {
            markAgentActivity();
            finishStream();
            if(t!=null && !t.isEmpty()) {
                if(live==null) add(record("assistant",t));
                else {
                    // The completed item is authoritative, including any missed delta or tail.
                    live.setText(RichText.render(HistoryPreview.text(t)));
                    bindToolDetails(live,"assistant",t,true);
                    if(liveEntry>=0 && liveEntry<log.size()) log.get(liveEntry)[1]=t;
                    follow();
                }
            }
            live = null; liveSource=null;  // the next text block starts a new view
            persistTranscript();
        } });
    }

    @Override public void onToolUse(final String id, final String name, final Map<String, Object> in) {
        ui(new Runnable() { @Override public void run() {
            markAgentActivity();
            finishStream();
            add(record("tool", "▸ " + name + "  " + summary(name, in)+(in==null?"":"\n"+Json.write(in))));
            if("Edit".equals(name)) { workFiles.add(Json.map("path",summary(name,in),"state","running")); }
            workEvent("command","running",name+" · "+summary(name,in));
            live = null; liveSource=null;
        } });
    }

    @Override public void onToolResult(String toolUseId, final String t, final boolean err) {
        ui(new Runnable() { @Override public void run() {
            markAgentActivity();
            String first = t == null ? "" : t.trim();
            int nl = first.indexOf('\n');
            int lines = first.isEmpty() ? 0 : first.split("\n").length;
            if (nl >= 0) first = first.substring(0, nl);
            if (first.length() > 160) first = first.substring(0, 160) + "…";
            add(record(err ? "error" : "result",t==null?"":t));
            workChecks.add(Json.map("name","Command outcome","state",err?"failed":"passed","detail",first));
            workEvent("command",err?"failed":"running",t);
        } });
    }

    /** A backend's own sign-in, in the system browser (never a WebView). */
    @Override public void onAuth(final String url, final String code) {
        ui(new Runnable() { @Override public void run() {
            markAgentActivity();
            authRequired = true;
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(round(PANEL, 12));
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            TextView what = text(WARN, 14);
            what.setText("Sign in to " + agent + (code == null ? "" : ", then enter this code:"));
            card.addView(what);
            if (code != null) {
                TextView c = text(FG, 22);
                c.setTypeface(Typeface.MONOSPACE);
                c.setTextIsSelectable(true);
                c.setText(code);
                card.addView(c);
            }
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.END);
            Button open = button("Open sign-in page", USER, BG);
            row.addView(open);
            card.addView(row);
            open.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (code != null) {
                        android.content.ClipboardManager cb = (android.content.ClipboardManager) c.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("code", code));
                    }
                    android.content.Intent view = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    if (a != null) a.startActivity(view);
                    else c.getApplicationContext().startActivity(view.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            });
            add(card);
            report("auth", agent + " sign-in requested");
        } });
    }

    @Override public void onPermission(final String rid, final String tool, final Map<String, Object> in) {
        ui(new Runnable() { @Override public void run() {
            markAgentActivity();
            pendingPermissions.add(rid);
            permissionRequests.put(rid,Json.map("tool",tool,"input",in));
            final Agent asker = session;   // answer the agent that asked, even after a switch
            final LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(round(PANEL, 12));
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            final TextView what = text(WARN, 14);
            what.setText("Allow " + tool + "?");
            card.addView(what);
            TextView detail = text(FG, 13);
            detail.setTypeface(Typeface.MONOSPACE);
            detail.setText(summary(tool, in));
            card.addView(detail);
            final LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.END);
            final Button deny = button("Deny", PANEL, FG), allow = button("Allow", USER, BG);
            row.addView(deny);
            row.addView(allow);
            card.addView(row);
            final View.OnClickListener answer = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean ok = v == allow;
                    if (asker == null) return;
                    if (ok) asker.allow(rid, in); else asker.deny(rid, "The user denied this in Trough.");
                    if (asker == session) { pendingPermissions.remove(rid);permissionRequests.remove(rid); }
                    android.app.AlertDialog popup = cosmicApprovals.remove(rid);
                    if (popup != null) popup.dismiss();
                    what.setText((ok ? "Allowed " : "Denied ") + tool);
                    card.removeView(row);
                    paint();
                }
            };
            deny.setOnClickListener(answer);
            allow.setOnClickListener(answer);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            feed.addView(card, lp);
            follow();
            paint();
            if (a != null && !a.isFinishing() && !a.isDestroyed()
                    && input.getWindowVisibility()==View.VISIBLE) {
                android.app.AlertDialog popup = new android.app.AlertDialog.Builder(a)
                    .setTitle("Allow " + tool + "?")
                    .setMessage(in == null ? "" : Json.write(in))
                    .setCancelable(false)
                    .setNegativeButton("Deny", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int which) { answer.onClick(deny); }
                    })
                    .setPositiveButton("Allow", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int which) { answer.onClick(allow); }
                    })
                    .setNeutralButton("View in chat", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int which) {
                            cosmicApprovals.remove(rid);
                            CosmicPanel.close();
                        }
                    }).create();
                cosmicApprovals.put(rid, popup);
                popup.show();
            }
        } });
    }

    @Override public void onResult(final boolean ok, final double c, final long ms, final String t) {
        ui(new Runnable() { @Override public void run() {
            finishStream();
            boolean cancelled=stopping;
            boolean reconnect=!ok && !cancelled && "codex".equals(agent) && ConnectionRecovery.transientFailure(t);
            if(activeWorkId.isEmpty() && !reconnect) { workPolicy.foregroundFinished(android.os.SystemClock.elapsedRealtime(),ok&&!cancelled);persistWorkPolicy(null); }
            finishBackgroundWork(ok,cancelled,t);
            if(reconnect) {workPolicy.connectionInterrupted(android.os.SystemClock.elapsedRealtime());persistWorkPolicy(null);}
            markAgentActivity();
            finishTurnClock(cancelled?"stopped":ok?"done":"failed");
            cancelStopFallback(); stopping=false;
            busy = false;
            pendingPermissions.clear();
            permissionRequests.clear();
            dismissCosmicApprovals();
            cost += c;
            workEvent("done",cancelled?"idle":ok?"done":"failed",cancelled?"Response stopped":ok?"Response complete":"Response failed");
            live = null; liveSource=null;
            if (!ok && !cancelled) {
                lastError = t==null || t.trim().isEmpty()?"The model turn ended with an error.":clip(t.trim());
                note(lastError);
            }
            report("result", (ok ? "ok" : "error") + String.format(" $%.4f %d ms", c, ms));
            paint();
            if(reconnect) reconnectAfterNetworkFailure();
            else if(!ok && ConnectionRecovery.requiresSignIn(t)) authRequired=true;
            MAIN.post(new Runnable(){public void run(){upgradeTransportIfIdle();}});
            reload.run();   // the agent may have edited Trough's source: rebuild, swap if changed
        } });
    }

    @Override public void onExit(final int code, final String err) {
        ui(new Runnable() { @Override public void run() {
            markAgentActivity();
            boolean wasStopping=stopping;
            boolean hadBackgroundWork=!activeWorkId.isEmpty();
            if(busy) finishTurnClock(stopping?"stopped":"failed");
            finishBackgroundWork(false,false,err);
            settleInbox("uncertain","Backend exited before delivery was confirmed");
            finishStream(); live=null; liveSource=null;
            cancelStopFallback(); stopping=false;
            busy = false;
            initialized = false;
            pendingPermissions.clear();
            permissionRequests.clear();
            dismissCosmicApprovals();
            cancelConnectionTimers();
            Agent failedSession=session;
            ++generation;
            session = null;selectedRoute="";selectedEffort="";
            try { if(failedSession!=null) failedSession.close(); } catch(RuntimeException closeError) {report("recovery","Transport close failed; reconnect remains eligible");}
            String tail = err == null ? "" : err.trim();
            if (tail.length() > 600) tail = tail.substring(tail.length() - 600);
            lastError = agent + " disconnected (" + code + ")" + (tail.isEmpty()?".":": "+tail);
            note(agent + " exited (" + code + ")" + (tail.isEmpty() ? "" : "\n" + tail));
            report("exit " + code, tail);
            if(ConnectionRecovery.requiresSignIn(tail)) authRequired=true;
            if(!authRequired) {
                if(ConnectionRecovery.rateLimited(tail)) {
                    if(!hadBackgroundWork) workPolicy.foregroundFinished(android.os.SystemClock.elapsedRealtime(),false);
                    persistWorkPolicy(null);
                } else if(!wasStopping) {workPolicy.connectionInterrupted(android.os.SystemClock.elapsedRealtime());persistWorkPolicy(null);}
                retryConnection();
            }
            paint();
        } });
    }

    // --- helpers ---

    private void dismissCosmicApprovals() {
        for (android.app.AlertDialog popup : cosmicApprovals.values()) popup.dismiss();
        cosmicApprovals.clear();
    }

    private void scheduleCosmicRestore() {
        if (!reopenCosmic || closed || a == null) return;
        input.post(new Runnable() { @Override public void run() {
            if (!reopenCosmic || closed || a == null) return;
            CosmicPanel.show(a);
            if (Boolean.TRUE.equals(CosmicPanel.state().get("showing"))) reopenCosmic = false;
        } });
    }

    /** Session diagnostics stay in this installation's private storage. */
    private void report(String event, String detail) {
        try {
            File reports = new File(home,".trough/reports");reports.mkdirs();
            File f = new File(reports,"chat-state.txt");
            String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                + " " + event + (detail == null || detail.isEmpty() ? "" : " | " + detail.replace('\n', ' ')) + "\n";
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f, true)) { o.write(line.getBytes("UTF-8")); }
        } catch (Exception ignored) {}
    }

    private static String summary(String tool, Map<String, Object> in) {
        if (in == null) return "";
        for (String k : new String[]{"command", "file_path", "path", "pattern", "url", "description"}) {
            Object v = in.get(k);
            if (v instanceof String) return clip((String) v);
        }
        return clip(Json.write(in));
    }

    private static String clip(String s) { return s.length() > 300 ? s.substring(0, 300) + "…" : s; }

    private void note(String s) { add(record("note", s)); }

    /** A feed entry, logged as data (so a successor can rebuild it) and rendered. */
    private static boolean isAssistantKind(String kind){return "assistant".equals(kind)||"work_assistant".equals(kind);}
    private TextView record(String kind, String text) {
        // Background status is not project identity. Only the dedicated project file feeds Half Sword.
        log.add(new String[]{kind, text});
        queueTranscript();
        TextView view=entry(kind,text);view.setTag(HISTORY_TAG,Integer.valueOf(historySize()-1));return view;
    }

    /** Keep the native text/layout caches across a code swap instead of reparsing the whole chat. */
    private static ArrayList<TextView> reusableEntries(View view,Map<?,?> state) {
        if(state==null || !(state.get("entries") instanceof List)) return null;
        if(view instanceof ScrollView && ((ScrollView)view).getChildCount()==1
            && ((ScrollView)view).getChildAt(0) instanceof LinearLayout) {
            LinearLayout candidate=(LinearLayout)((ScrollView)view).getChildAt(0);
            ArrayList<TextView> found=new ArrayList<TextView>();
            for(int i=0;i<candidate.getChildCount();i++) {
                View child=candidate.getChildAt(i);
                if(child instanceof TextView && child.getTag(HISTORY_TAG) instanceof Integer
                    && child.getTag(ENTRY_SOURCE_TAG) instanceof String[])found.add((TextView)child);
            }
            if(!found.isEmpty())return found;
        }
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {
                ArrayList<TextView> found=reusableEntries(group.getChildAt(i),state);
                if(found!=null) return found;
            }
        }
        return null;
    }

    /** The one rendering of each entry kind, used live and when rebuilding from a handoff. */
    private TextView entry(String kind, String text) {
        String originalText=text;
        if("user".equals(kind))text=NativeTraceRenderer.visibleUser(text);
        String storedKind=kind;if(kind.startsWith("work_"))kind=kind.substring(5);
        TextView v;
        if ("user".equals(kind)) { v = text(USER, 16); v.setPadding(0, dp(14), 0, dp(4)); }
        else if (isAssistantKind(kind)) { v = text(FG, 16); v.setPadding(dp(8), dp(8), dp(8), dp(6)); v.setTextIsSelectable(true); v.setBackground(round(0xA00B1210,12)); }
        else if ("tool".equals(kind)) { v = text(TOOL, 13); v.setTypeface(Typeface.MONOSPACE); v.setPadding(0, dp(6), 0, 0); }
        else if ("result".equals(kind) || "error".equals(kind)) { v = text("error".equals(kind) ? WARN : DIM, 12); v.setTypeface(Typeface.MONOSPACE); }
        else { v = text(DIM, 12); v.setPadding(0, dp(6), 0, 0); }
        boolean activity="tool".equals(kind)||"result".equals(kind)||"error".equals(kind);
        final String complete=text==null?"":text;
        final String preview=activity?toolPreview(complete,3):HistoryPreview.text(complete);
        v.setText(isAssistantKind(kind)?RichText.render(HistoryPreview.text(complete)):preview);
        v.setTag(storedKind);v.setTag(ENTRY_SOURCE_TAG,new String[]{storedKind,originalText});
        if(isAssistantKind(kind) || "user".equals(kind)) styleProse(v,"user".equals(kind));
        bindToolDetails(v,kind,complete,true);
        return v;
    }

    /** Rebind retained rows to the new owner without retaining old Chat callbacks. */
    private void bindToolDetails(TextView v,String kind,String complete,boolean fresh) {
        if("tool".equals(kind) || "result".equals(kind) || "error".equals(kind) || (complete!=null && complete.length()>HistoryPreview.LIMIT)) {
            final TextView details=v;
            details.setTextSize(11);
            final int collapsed=3;
            final String full=complete==null?"":complete;
            final String compact=toolPreview(full,collapsed);
            if(!compact.contentEquals(details.getText())) details.setText(compact);
            details.setMaxLines(collapsed);details.setEllipsize(android.text.TextUtils.TruncateAt.END);
            int tint="error".equals(kind)?0xFFFF9A8B:"result".equals(kind)?0xFFACC8B6
                :full.contains("Bash")?0xFF9EC9FF:full.contains("Edit")?0xFFFFCF86
                :full.contains("Search")?0xFFD1B0FF:0xFF8EDDD4;
            details.setTextColor(tint);
            details.setBackground(round("error".equals(kind)?0xD033191D:0xD0101B24,8));
            details.setPadding(dp(10),dp(6),dp(10),dp(6));
            details.setTextIsSelectable(true);
            details.setOnClickListener(new View.OnClickListener() { public void onClick(View view) {
                TextView body=text(FG,11);
                body.setTypeface(Typeface.MONOSPACE);body.setTextIsSelectable(true);
                body.setText(full);body.setPadding(dp(12),dp(12),dp(12),dp(12));
                ScrollView scroll=new ScrollView(c);scroll.addView(body);
                new android.app.AlertDialog.Builder(c).setTitle("Tool details")
                    .setView(scroll).setPositiveButton("Close",null).show();
            }});
            details.setContentDescription("Tool activity preview. Tap for complete selectable output.");
        }
    }

    /** Collapsing the view must also bound its layout input; the complete output stays in the log. */
    private static String toolPreview(String full,int lines) {
        int end=Math.min(full.length(),320),seen=0;
        for(int i=0;i<end;i++) if(full.charAt(i)=='\n' && ++seen>=lines) { end=i;break; }
        return end<full.length()?full.substring(0,end)+"…":full;
    }

    private void startStatusTicker() {
        MAIN.removeCallbacks(statusTick);
        if(!closed && a!=null) MAIN.post(statusTick);
    }

    private void markAgentActivity() { lastAgentActivityAt=android.os.SystemClock.elapsedRealtime(); }

    private void finishTurnClock(String outcome) {
        long now=android.os.SystemClock.elapsedRealtime();
        if(turnStartedAt>0) turnLastDuration=Math.max(0,now-turnStartedAt);
        turnFinishedAt=now;
        turnOutcome=outcome;
    }

    private long turnElapsed() {
        return busy && turnStartedAt>0?Math.max(0,android.os.SystemClock.elapsedRealtime()-turnStartedAt):turnLastDuration;
    }

    private long activityAge(long at) {
        return at<=0?-1:Math.max(0,android.os.SystemClock.elapsedRealtime()-at);
    }

    private String workState() {
        if(stopping) return "stopping";
        if(!pendingPermissions.isEmpty()) return "approval";
        if(busy) return "working";
        if(!initialized && lastError.isEmpty()) return "connecting";
        return turnOutcome;
    }

    private static long timingValue(Map<String,Object> state,String key) {
        Object value=state.get(key);
        return value instanceof Number?Math.max(0,((Number)value).longValue()):0;
    }

    private Map<String,Object> timingState() {
        return Json.map("turn_started_at",turnStartedAt,"last_agent_activity_at",lastAgentActivityAt,
            "last_user_activity_at",lastUserActivityAt,"turn_finished_at",turnFinishedAt,
            "turn_outcome",turnOutcome,"elapsed_ms",turnElapsed(),"work_state",workState(),
            "turn_last_duration_ms",turnLastDuration,"last_agent_activity_age_ms",activityAge(lastAgentActivityAt),
            "last_user_activity_age_ms",activityAge(lastUserActivityAt));
    }

    private static String clock(long milliseconds) {
        if(milliseconds<0) return "—";
        long seconds=milliseconds/1000;
        return (seconds/60)+":"+(seconds%60<10?"0":"")+(seconds%60);
    }

    /** Only this fixed-height label changes on a clock tick; the conversation and art stay untouched. */
    private void paintStatus() {
        if(status==null) return;
        boolean compacting=false;
        // Transport events include work which does not produce a transcript row.
        if(session instanceof ResidentAgent) {
            Map<String,Object> transport=((ResidentAgent)session).state();
            Map<String,Object> backend=Json.obj(transport.get("backend_state"));
            compacting=busy && backend!=null && "contextCompaction".equals(Json.str(backend,"last_item_type"));
            if(backend!=null && backend.get("last_event_age_ms") instanceof Number) {
                long age=((Number)backend.get("last_event_age_ms")).longValue();
                long now=android.os.SystemClock.elapsedRealtime();
                if(age>=0 && age<=now) lastAgentActivityAt=Math.max(lastAgentActivityAt,now-age);
            }
        }
        String state=workState();
        String label="working".equals(state)?"Working":"stopping".equals(state)?"Stopping"
            :"approval".equals(state)?"Approval":"done".equals(state)?"Done":"stopped".equals(state)?"Stopped"
            :"failed".equals(state)?"Failed":"interrupted".equals(state)?"Interrupted"
            :"connecting".equals(state)?"Connecting":"Ready";
        if(!busy && workPolicy.isPaused())label="Project paused · /work resume";
        if(compacting)label="Preparing long conversation";
        String headline=label;
        if(busy || turnFinishedAt>0) headline+=" "+clock(turnElapsed());
        if(!busy && turnFinishedAt>0) headline+=" · idle "+clock(activityAge(turnFinishedAt));
        if(autoSendHeld) headline+=" · draft held";
        if(enqueuePending) headline+=" · saving";
        if(inboxCount("uncertain")>0) headline+=" · check delivery";
        String route=selectedRoute.isEmpty()?model:selectedRoute;
        if(route.startsWith("gpt-6-astra")) route="Astra";
        else if(route.startsWith("gpt-6.1-sol")) route="Sol";
        String details="Last event "+clock(activityAge(lastAgentActivityAt))+" · you "+clock(activityAge(lastUserActivityAt))+" · "+route+(selectedEffort.isEmpty()?"":" "+selectedEffort);
        if(!activeWorkId.isEmpty()) details+=" · background";
        String value=headline+"\n"+details;
        if(!value.contentEquals(status.getText())) {
            status.setText(value);
            status.setContentDescription(label+". Turn duration "+clock(turnElapsed())
                +". Time since last agent action "+clock(activityAge(lastAgentActivityAt))
                +". Time since your last action "+clock(activityAge(lastUserActivityAt))
                +". "+agent+" "+model+". "
                +inboxCount("uncertain")+" awaiting delivery confirmation, "+inboxCount("held_count")+" saved for review. "
                +"Tap to choose the agent; hold to review saved messages."
                +(cost>0?String.format(java.util.Locale.US," Total cost $%.3f.",cost):""));
        }
    }

    private void paint() {
        paintStatus();
        paintComposer();
    }

    private void paintComposer() {
        if(hold!=null) {
            String holdLabel=autoSendHeld?"HELD":"HOLD";
            if(!holdLabel.contentEquals(hold.getText())) hold.setText(holdLabel);
            if(hold.isSelected()!=autoSendHeld) {
                hold.setSelected(autoSendHeld);hold.setTextColor(autoSendHeld?WARN:DIM);
                hold.setBackground(round(autoSendHeld?0xff343025:PANEL,14));
            }
            hold.setContentDescription(autoSendHeld
                ?"Autosend pauses while you press. Release for a fresh 1.9 second countdown. The agent keeps working."
                :"Tap briefly to latch autosend pause; tap again to release. Press and hold for a temporary pause. Slide upward while holding to stir an ink constellation.");
        }
        if(stop==null) return;
        int visibility=busy?View.VISIBLE:View.GONE;
        if(stop.getVisibility()!=visibility) stop.setVisibility(visibility);
        stop.setEnabled(busy && !stopping);
        String label=stopping?"…":"■";
        if(!label.contentEquals(stop.getText())) stop.setText(label);
    }

    private void add(View v) {
        if(browsingHistory && v.getTag(HISTORY_TAG) instanceof Integer){historyButtons();return;}
        feed.addView(v);trimHistory();follow();
    }

    private void follow() {
        if(!followTail || touchingScroll || followQueued || closed || Boolean.TRUE.equals(root.getTag(0x7f0d7483))) return;
        followQueued=true;
        scroll.postOnAnimation(new Runnable() { public void run() {
            followQueued=false;
            if(followTail && !touchingScroll && !closed && !Boolean.TRUE.equals(root.getTag(0x7f0d7483)))
                scroll.scrollTo(0,Math.max(0,feed.getHeight()-scroll.getHeight()+scroll.getPaddingBottom()));
        }});
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.concurrent.Executor PUBLICATIONS=new java.util.concurrent.Executor() {
        public void execute(Runnable event) { MAIN.post(event); }
    };
    private void ui(Runnable r) { if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run(); else MAIN.post(r); }

    private TextView text(int color, int sp) {
        TextView t = new TextView(c);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private Button button(String label, int bg, int fg) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackground(round(bg, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}
