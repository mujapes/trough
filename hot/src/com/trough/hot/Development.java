package com.trough.hot;

import android.app.Activity;
import android.widget.FrameLayout;
import java.io.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;

/** Development transport only. ScenarioProtocol and the executors remain usable locally.
 * Polling belongs to the VM/shared-storage adapter, not the eventual product's work graph.
 */
final class Development implements AutoCloseable, ScenarioProtocol.Handler {
    private final Activity activity;
    private final FrameLayout root;
    private final File bundle;
    private final String runtime;
    private final Thread worker;
    private volatile boolean closed;
    Development(Activity a,FrameLayout root,File bundle,String runtime) {
        activity=a; this.root=root; this.bundle=bundle; this.runtime=runtime;
        worker=new Thread(new Runnable() { public void run() { serve(); }},"trough-development");
        worker.setDaemon(true); worker.start();
    }
    public void close() { closed=true; worker.interrupt(); }
    /** Store oracles that take a data directory; each is also built against bionic (android.*). */
    private static final List<String> STORE=Arrays.asList("store","documents","streams","tasks","workspace","drafts","text","keeper");
    private List<String> scenarios() {
        List<String> all=new ArrayList<>(Arrays.asList("app.observe","core.publication","core.resources","core.replacement","core.derive","core.exec","core.blobs"));
        for(String s:STORE) all.add("core."+s);
        all.add("core.processes"); all.add("build.native");
        if(new File(bundle,"lib/libtrough-android.so").isFile()) {
            for(String s:STORE) all.add("android."+s);
            all.add("android.jni"); all.add("android.ime"); all.add("android.editor"); all.add("android.vulkan"); all.add("android.scene"); all.add("android.glyphs"); all.add("android.text"); all.add("android.surface"); all.add("android.layout"); all.add("android.derive");
            all.add("android.retire.setup"); all.add("android.retire.check");
        }
        return all;
    }
    public boolean supports(String name) { return scenarios().contains(name); }
    public Map<String,Object> execute(String name) throws Exception {
        if(closed) throw new InterruptedException();
        if("app.observe".equals(name)) {
            final FutureTask<Map<String,Object>> result=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call() throws Exception {
                    if(closed) throw new InterruptedException();
                    return Json.map("ok",true,"width",root.getWidth(),"height",root.getHeight(),
                        "window_focus",activity.hasWindowFocus(),"visible",root.isShown(),
                        "density",activity.getResources().getDisplayMetrics().density,
                        "sdk",android.os.Build.VERSION.SDK_INT,"device",android.os.Build.MODEL,
                        "multi_window",activity.isInMultiWindowMode(),
                        "scenarios",scenarios());
                }
            });
            activity.runOnUiThread(result);
            try { return result.get(3,TimeUnit.SECONDS); } finally { result.cancel(false); }
        }
        if(!supports(name)) throw new IllegalArgumentException("scenario");
        if("android.jni".equals(name)) return NativeCore.scenario(bundle,activity.getCacheDir());
        if("android.editor".equals(name)) return EditorOracle.run(activity,bundle);
        if("android.vulkan".equals(name)) return PresentOracle.run(activity,root,bundle);
        if("android.text".equals(name)) return TextOracle.run(bundle);
        if("android.surface".equals(name)) return SurfaceOracle.run(activity,root,bundle);
        if("android.layout".equals(name)) return LayoutOracle.run();
        if("android.derive".equals(name)) return LayoutDeriveOracle.run(bundle);
        if("android.scene".equals(name) || "android.glyphs".equals(name)) {
            NativeCore.load(bundle);
            org.json.JSONObject o=new org.json.JSONObject("android.scene".equals(name)?NativeCore.sceneOracle():NativeCore.glyphOracle());
            Map<String,Object> m=new LinkedHashMap<>();
            for(Iterator<String> it=o.keys();it.hasNext();) { String key=it.next(); m.put(key,o.get(key)); }
            return m;
        }
        if("android.retire.setup".equals(name)) return Retirement.setup(activity,bundle);
        if("android.retire.check".equals(name)) return Retirement.check(activity,bundle);
        if("android.ime".equals(name)) {
            final FutureTask<Map<String,Object>> ime=new FutureTask<>(new Callable<Map<String,Object>>() {
                public Map<String,Object> call() throws Exception { return ImeOracle.run(activity,bundle); }
            });
            activity.runOnUiThread(ime);
            try { return ime.get(100,TimeUnit.SECONDS); } finally { ime.cancel(false); }
        }
        String executable=new File(bundle,"bin/"+(name.startsWith("android.")?"android-core-"+name.substring(8):name.replace('.','-'))).getPath();
        File supervisor=new File(bundle,"bin/trough-run");
        Map<String,String> env=Root.env(activity,activity.getCacheDir());
        if("build.native".equals(name)) return NativeReproduction.run(bundle,activity.getCacheDir(),env,
            "/system/bin/sh",new File(activity.getDataDir(),"d/usr/libexec/trough/tfix").getAbsolutePath());
        if("core.replacement".equals(name)) return ScenarioProcess.run(supervisor,activity.getCacheDir(),env,20,executable,
            new File(bundle,"fixtures/module-1.so").getPath(),new File(bundle,"fixtures/module-2.so").getPath());
        if(name.startsWith("android.") || STORE.contains(name.substring(5)) || "core.processes".equals(name)) {
            File data=new File(activity.getCacheDir(),"scenario-"+name.replace('.','-'));
            Main.deleteTree(data);
            try { return ScenarioProcess.run(supervisor,activity.getCacheDir(),env,60,executable,data.getPath()); }
            finally { Main.deleteTree(data); }
        }
        return ScenarioProcess.run(supervisor,activity.getCacheDir(),env,20,executable);
    }
    private void serve() {
        File transport=new File(Creds.shared(),"control");
        String lastPair="";
        try {
            byte[] publicBytes;
            try(InputStream in=activity.getAssets().open("hot-pubkey.der"); ByteArrayOutputStream b=new ByteArrayOutputStream()) {
                byte[] part=new byte[1024]; for(int n;(n=in.read(part))!=-1;) b.write(part,0,n); publicBytes=b.toByteArray();
            }
            PublicKey verify=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicBytes));
            ScenarioProtocol protocol=new ScenarioProtocol(new File(activity.getFilesDir(),"automation/receipts"),verify,
                bundle.getName(),runtime,new ScenarioProtocol.Clock() { public long millis() { return System.currentTimeMillis(); }},this);
            while(!closed) {
                try {
                    File request=new File(transport,"request.json"), signature=new File(transport,"request.sig");
                    File keyFile=new File(activity.getFilesDir(),"keys/trough_rsa.p8");
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
                    try { ScenarioProtocol.write(new File(activity.getFilesDir(),"automation/transport-status"),
                        e.getClass().getSimpleName().getBytes("UTF-8")); } catch(Exception ignored) {}
                }
                Thread.sleep(1000);
            }
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
          catch(Exception ignored) {}
    }
}
