package com.trough.hot;

import android.app.Activity;
import android.widget.FrameLayout;
import org.json.JSONObject;
import java.io.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;

/** Development transport only. ScenarioProtocol and the executors remain usable locally.
 * Polling belongs to the VM/shared-storage adapter, not the eventual product's work graph.
 */
final class Development implements AutoCloseable, ScenarioProtocol.Handler {
    private final android.content.Context ctx;   // the application: store and native scenarios need no window
    private volatile Activity activity;   // the current window, or null (attach/detach); window scenarios need it
    private final FrameLayout root;
    private final File bundle;
    private final String runtime;
    private final Thread worker;
    private volatile boolean closed;
    Development(android.content.Context c,Activity a,FrameLayout root,File bundle,String runtime) {
        ctx=c.getApplicationContext(); activity=a; this.root=root; this.bundle=bundle; this.runtime=runtime;
        worker=new Thread(new Runnable() { public void run() { serve(); }},"trough-development");
        worker.setDaemon(true); worker.start();
    }
    public void close() { closed=true; worker.interrupt(); }
    void attach(Activity a) { activity=a; }
    private Activity window() {
        Activity a=activity;
        if(a==null) throw new IllegalStateException("needs Trough's window (no Activity now)");
        return a;
    }
    private void onUi(Runnable r) { new android.os.Handler(android.os.Looper.getMainLooper()).post(r); }
    /** Store oracles that take a data directory; each is also built against bionic (android.*). */
    private static final List<String> STORE=Arrays.asList("store","documents","streams","tasks","workspace","drafts","text","keeper");
    private List<String> scenarios() {
        List<String> all=new ArrayList<>(Arrays.asList("app.observe","core.publication","core.resources","core.replacement","core.derive","core.exec","core.blobs","core.atlas","core.order"));
        all.add("app.repair.inspect");all.add("app.repair.bottom");
        all.add("app.screen");
        all.add("app.screen.tree");
        all.add("app.codex.status");
        all.add("app.codex.recover");
        all.add("app.codex.stop");all.add("app.work.pause");all.add("app.work.resume");all.add("app.work.status");
        all.add("app.codex.repair");
        all.add("app.work.progress");
        all.add("app.work.capture");
        all.add("app.work.oracle");
        all.add("app.input.oracle");
        all.add("app.keyboard.oracle");
        all.add("app.visual.oracle");
        all.add("app.codex.verify");
        all.add("app.codex.verify.state");
        all.add("app.codex.configure");
        all.add("app.codex.resume");
        all.add("app.cosmic");
        all.add("app.cosmic.open");
        for(String s:STORE) all.add("core."+s);
        all.add("core.processes"); all.add("build.native"); all.add("lift.slot"); all.add("lift.guest"); all.add("lift.guest.window");
        if(new File(bundle,"lib/libtrough-android.so").isFile()) {
            for(String s:STORE) all.add("android."+s);
            all.add("android.jni"); all.add("android.ime"); all.add("android.editor"); all.add("android.vulkan"); all.add("android.scene"); all.add("android.glyphs"); all.add("android.text"); all.add("android.shape"); all.add("android.measure"); all.add("android.bidi"); all.add("android.breaks"); all.add("android.metrics"); all.add("android.paragraphmeasure"); all.add("android.visual"); all.add("android.nativelayout"); all.add("android.surface"); all.add("android.layout"); all.add("android.derive"); all.add("android.view"); all.add("android.frames"); all.add("android.viewsurface"); all.add("android.touch"); all.add("android.caret"); all.add("android.selection"); all.add("android.paragraphs"); all.add("android.docime"); all.add("android.anchor"); all.add("android.access"); all.add("android.keys"); all.add("android.cost");
            all.add("android.retire.setup"); all.add("android.retire.check");
        }
        return all;
    }
    public boolean supports(String name) { return scenarios().contains(name); }
    public boolean acceptsParameters(String name, Map<String,Object> parameters) {
        if("app.work.progress".equals(name)) {
            if(parameters==null || parameters.isEmpty() || Json.write(parameters).length()>7000) return false;
            Set<String> allowed=new HashSet<>(Arrays.asList("title","stage","state","message","detail","log","code","files","checks","next_action","elapsed_ms","started_ms","progress"));
            return allowed.containsAll(parameters.keySet());
        }
        if(!"app.codex.configure".equals(name)) return parameters==null;
        return parameters!=null && parameters.size()==2
            && ("gpt-6.1-sol".equals(Json.str(parameters,"model")) || "gpt-6-astra".equals(Json.str(parameters,"model")))
            && Arrays.asList("low","medium","high","xhigh","max","ultra").contains(Json.str(parameters,"effort"))
            && !("gpt-6-astra".equals(Json.str(parameters,"model")) && "ultra".equals(Json.str(parameters,"effort")));
    }
    public Map<String,Object> execute(String name, final Map<String,Object> parameters) throws Exception {
        if("app.work.progress".equals(name)) {
            Main.buildProgress(parameters);
            return Json.map("ok",true,"shown",true);
        }
        if(!"app.codex.configure".equals(name)) return execute(name);
        final FutureTask<Map<String,Object>> selection = new FutureTask<>(new Callable<Map<String,Object>>() {
            public Map<String,Object> call() throws Exception {
                return Main.configureCodex(Json.str(parameters,"model"),Json.str(parameters,"effort"));
            }
        });
        onUi(selection);
        try { return selection.get(5,TimeUnit.SECONDS); } finally { selection.cancel(false); }
    }
    public Map<String,Object> execute(String name) throws Exception {
        if(closed) throw new InterruptedException();
        if("app.observe".equals(name)) {
            final FutureTask<Map<String,Object>> result=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call() throws Exception {
                    if(closed) throw new InterruptedException();
                    Activity a=activity;
                    return Json.map("ok",true,"width",root.getWidth(),"height",root.getHeight(),
                        "window",a!=null,"window_focus",a!=null&&a.hasWindowFocus(),"visible",root.isShown(),
                        "density",ctx.getResources().getDisplayMetrics().density,
                        "sdk",android.os.Build.VERSION.SDK_INT,"device",android.os.Build.MODEL,
                        "multi_window",a!=null&&a.isInMultiWindowMode(),
                        "scenarios",scenarios());
                }
            });
            onUi(result);
            try { return result.get(3,TimeUnit.SECONDS); } finally { result.cancel(false); }
        }
        if(!supports(name)) throw new IllegalArgumentException("scenario");
        if("app.repair.inspect".equals(name)||"app.repair.bottom".equals(name)) {
            final boolean bottom="app.repair.bottom".equals(name);
            FutureTask<Map<String,Object>> task=new FutureTask<>(new Callable<Map<String,Object>>() {public Map<String,Object> call()throws Exception{
                if(bottom)Main.jumpToBottom();return RepairInspection.run(ctx,root);
            }});onUi(task);try{return task.get(8,TimeUnit.SECONDS);}finally{task.cancel(false);}
        }
        if("app.screen".equals(name)) return ScreenInspection.inspect(ctx);
        if("app.screen.tree".equals(name)) return ScreenInspection.tree();
        if("app.visual.oracle".equals(name)) return SemanticSteeringOracle.run(ctx,VisualSteering.modelFile(ctx,bundle));
        if("app.input.oracle".equals(name) || "app.keyboard.oracle".equals(name)) {
            final boolean live="app.input.oracle".equals(name);
            FutureTask<Map<String,Object>> task=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call() throws Exception { return live?Main.inputOracle():KeyboardOracle.run(window()); }
            });
            onUi(task);
            try { return task.get(10,TimeUnit.SECONDS); } finally { task.cancel(false); }
        }
        if("app.work.oracle".equals(name)) {
            FutureTask<Map<String,Object>> oracle=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call() throws Exception { return WorkSurfaceOracle.run(window()); }
            });
            onUi(oracle);
            try { return oracle.get(10,TimeUnit.SECONDS); } finally { oracle.cancel(false); }
        }
        if("app.work.capture".equals(name)) {
            FutureTask<Map<String,Object>> capture=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call() { Main.screenshot(root,"work"); return Json.map("ok",true,"captured",true); }
            });
            onUi(capture);
            try { return capture.get(5,TimeUnit.SECONDS); } finally { capture.cancel(false); }
        }
        if("app.codex.repair".equals(name)) {
            JSONObject manifest=new JSONObject(new String(Payloads.readSmall(new File(bundle,"manifest.json")),"UTF-8"));
            File executable=CodexRuntime.install(Payloads.dir(ctx),Json.obj(Json.parse(manifest.toString())));
            Map<String,String> environment=Root.env(ctx,ctx.getCacheDir());
            File home=new File(ctx.getFilesDir(),"home");
            environment.put("CODEX_HOME",new File(home,".codex").getPath());
            return CodexRepairOracle.run(executable.getPath(),new File(executable.getParentFile(),"codex-code-mode-host").getPath(),
                environment.get("SHELL"),Main.work(ctx),environment);
        }
        if("app.codex.verify".equals(name) || "app.codex.verify.state".equals(name)) {
            final boolean start="app.codex.verify".equals(name);
            FutureTask<Map<String,Object>> task=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call() { return start ? Main.verifyCodexTurn() : Main.codexVerificationState(); }
            });
            onUi(task);
            try { return task.get(5,TimeUnit.SECONDS); } finally { task.cancel(false); }
        }
        if("app.codex.status".equals(name)) return AgentInspection.run(ctx,bundle);
        if("app.codex.stop".equals(name)||"app.work.pause".equals(name)||"app.work.resume".equals(name)||"app.work.status".equals(name)) {
            final String action=name.substring(name.lastIndexOf('.')+1);
            FutureTask<Map<String,Object>> task=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call(){return "stop".equals(action)?Main.stopCodex():Main.workControl(action);}
            });onUi(task);try{return task.get(5,TimeUnit.SECONDS);}finally{task.cancel(false);}
        }
        if("app.codex.recover".equals(name)) {
            FutureTask<Map<String,Object>> task=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call() { return Main.recoverCodex(); }
            });
            onUi(task);
            try { return task.get(5,TimeUnit.SECONDS); } finally { task.cancel(false); }
        }
        if("app.codex.resume".equals(name)) return SessionTransfer.run(ctx,bundle);
        if("app.cosmic".equals(name) || "app.cosmic.open".equals(name)) {
            final boolean open="app.cosmic.open".equals(name);
            final FutureTask<Map<String,Object>> cosmic=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call() throws Exception {
                    if(open) { CosmicPanel.show(window()); return CosmicPanel.state(); }
                    return CosmicPanel.smoke(window());
                }
            });
            onUi(cosmic);
            try { return cosmic.get(10,TimeUnit.SECONDS); } finally { cosmic.cancel(false); }
        }
        if("lift.slot".equals(name)) return LiftOracle.run(ctx);
        if("lift.guest".equals(name)) return LiftOracle.run(ctx, true);
        if("lift.guest.window".equals(name)) return LiftOracle.window(window());
        if("android.jni".equals(name)) return NativeCore.scenario(bundle,ctx.getCacheDir());
        if("android.editor".equals(name)) return EditorOracle.run(window(),bundle);
        if("android.vulkan".equals(name)) return PresentOracle.run(window(),root,bundle);
        if("android.text".equals(name)) return TextOracle.run(bundle);
        if("android.shape".equals(name)) return ShapeOracle.run(bundle);
        if("android.bidi".equals(name)) return BidiOracle.run(bundle);
        if("android.breaks".equals(name)) return BreakOracle.run(bundle);
        if("android.nativelayout".equals(name)) return NativeLayoutOracle.run(bundle);
        if("android.visual".equals(name)) return VisualOracle.run(bundle);
        if("android.paragraphmeasure".equals(name)) return BreakOracle.runNative(bundle);
        if("android.metrics".equals(name)) return MetricsOracle.run(bundle);
        if("android.measure".equals(name)) return MeasureOracle.run(bundle);
        if("android.surface".equals(name)) return SurfaceOracle.run(window(),root,bundle);
        if("android.layout".equals(name)) return LayoutOracle.run();
        if("android.derive".equals(name)) return LayoutDeriveOracle.run(bundle);
        if("android.cost".equals(name)) return CostOracle.run(bundle);
        if("android.view".equals(name)) return ViewOracle.run(bundle);
        if("android.viewsurface".equals(name)) return ViewSurfaceOracle.run(window(),root,bundle);
        if("android.touch".equals(name)) return TouchOracle.run(window(),root,bundle);
        if("android.caret".equals(name)) { NativeCore.load(bundle); return CaretOracle.run(); }
        if("android.selection".equals(name)) { NativeCore.load(bundle); return SelectionOracle.run(); }
        if("android.paragraphs".equals(name)) { NativeCore.load(bundle); return ParagraphsOracle.run(); }
        if("android.scene".equals(name) || "android.glyphs".equals(name) || "android.frames".equals(name)) {
            NativeCore.load(bundle);
            org.json.JSONObject o=new org.json.JSONObject("android.scene".equals(name)?NativeCore.sceneOracle():"android.glyphs".equals(name)?NativeCore.glyphOracle():NativeCore.framesOracle());
            Map<String,Object> m=new LinkedHashMap<>();
            for(Iterator<String> it=o.keys();it.hasNext();) { String key=it.next(); m.put(key,o.get(key)); }
            return m;
        }
        if("android.retire.setup".equals(name)) return Retirement.setup(ctx,bundle);
        if("android.retire.check".equals(name)) return Retirement.check(ctx,bundle);
        if("android.keys".equals(name)) {
            NativeCore.load(bundle);
            final FutureTask<Map<String,Object>> keys=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call() throws Exception { return KeysOracle.run(window()); }
            });
            onUi(keys);
            try { return keys.get(100,TimeUnit.SECONDS); } finally { keys.cancel(false); }
        }
        if("android.access".equals(name)) {
            NativeCore.load(bundle);
            final FutureTask<Map<String,Object>> access=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call() throws Exception { return AccessOracle.run(window()); }
            });
            onUi(access);
            try { return access.get(100,TimeUnit.SECONDS); } finally { access.cancel(false); }
        }
        if("android.anchor".equals(name)) {
            NativeCore.load(bundle);
            final FutureTask<Map<String,Object>> anchor=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call() throws Exception { return AnchorOracle.run(window()); }
            });
            onUi(anchor);
            try { return anchor.get(100,TimeUnit.SECONDS); } finally { anchor.cancel(false); }
        }
        if("android.docime".equals(name)) {
            NativeCore.load(bundle);
            final FutureTask<Map<String,Object>> docime=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call() throws Exception { return DocImeOracle.run(window()); }
            });
            onUi(docime);
            try { return docime.get(100,TimeUnit.SECONDS); } finally { docime.cancel(false); }
        }
        if("android.ime".equals(name)) {
            final FutureTask<Map<String,Object>> ime=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call() throws Exception { return ImeOracle.run(window(),bundle); }
            });
            onUi(ime);
            try { return ime.get(100,TimeUnit.SECONDS); } finally { ime.cancel(false); }
        }
        String executable=new File(bundle,"bin/"+(name.startsWith("android.")?"android-core-"+name.substring(8):name.replace('.','-'))).getPath();
        File supervisor=new File(bundle,"bin/trough-run");
        Map<String,String> env=Root.env(ctx,ctx.getCacheDir());
        if("build.native".equals(name)) return NativeReproduction.run(bundle,ctx.getCacheDir(),env,
            "/system/bin/sh",new File(Root.os(),"d/usr/libexec/trough/tfix").getAbsolutePath());
        if("core.replacement".equals(name)) return ScenarioProcess.run(supervisor,ctx.getCacheDir(),env,20,executable,
            new File(bundle,"fixtures/module-1.so").getPath(),new File(bundle,"fixtures/module-2.so").getPath());
        if(name.startsWith("android.") || STORE.contains(name.substring(5)) || "core.processes".equals(name)) {
            File data=new File(ctx.getCacheDir(),"scenario-"+name.replace('.','-'));
            Main.deleteTree(data);
            try { return ScenarioProcess.run(supervisor,ctx.getCacheDir(),env,60,executable,data.getPath()); }
            finally { Main.deleteTree(data); }
        }
        return ScenarioProcess.run(supervisor,ctx.getCacheDir(),env,20,executable);
    }
    private void serve() {
        File transport=new File(Creds.shared(),"control");
        String lastPair="";
        try {
            byte[] publicBytes;
            try(InputStream in=ctx.getAssets().open("hot-pubkey.der"); ByteArrayOutputStream b=new ByteArrayOutputStream()) {
                byte[] part=new byte[1024]; for(int n;(n=in.read(part))!=-1;) b.write(part,0,n); publicBytes=b.toByteArray();
            }
            PublicKey verify=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicBytes));
            ScenarioProtocol protocol=new ScenarioProtocol(new File(ctx.getFilesDir(),"automation/receipts"),verify,
                bundle.getName(),runtime,new ScenarioProtocol.Clock() { public long millis() { return System.currentTimeMillis(); }},this);
            while(!closed) {
                try {
                    File request=new File(transport,"request.json"), signature=new File(transport,"request.sig");
                    File keyFile=new File(ctx.getFilesDir(),"keys/trough_rsa.p8");
                    if(request.isFile() && signature.isFile() && keyFile.isFile()) {
                        byte[] bytes=ScenarioProtocol.read(request,ScenarioProtocol.REQUEST_LIMIT);
                        byte[] sig=ScenarioProtocol.read(signature,512);
                        String pair=ScenarioProtocol.sha(bytes)+ScenarioProtocol.sha(sig);
                        if(!pair.equals(lastPair)) {
                            PrivateKey signing=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(
                                ScenarioProtocol.read(keyFile,8192)));
                            byte[] response=protocol.process(bytes,sig);
                            Signature signer=Signature.getInstance("SHA256withRSA"); signer.initSign(signing); signer.update(response);
                            if(!closed) {
                                ScenarioProtocol.write(new File(transport,"result.sig"),signer.sign());
                                ScenarioProtocol.write(new File(transport,"result.json"),response);
                                lastPair=pair;
                            }
                        }
                    }
                } catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
                  catch(Exception e) {
                    // Local diagnostic holds only an exception class, never a key or request body.
                    try { ScenarioProtocol.write(new File(ctx.getFilesDir(),"automation/transport-status"),
                        e.getClass().getSimpleName().getBytes("UTF-8")); } catch(Exception ignored) {}
                }
                Thread.sleep(1000);
            }
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
          catch(Exception ignored) {}
    }
}
