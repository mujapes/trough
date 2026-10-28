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
    public boolean supports(String name) {
        return "app.observe".equals(name) || "core.publication".equals(name) ||
            "core.resources".equals(name) || "core.replacement".equals(name) || "core.store".equals(name) ||
            "core.documents".equals(name) || "core.streams".equals(name) || "core.tasks".equals(name) || "core.workspace".equals(name) || "core.drafts".equals(name) || "core.text".equals(name) || "core.keeper".equals(name) || "core.processes".equals(name) || "build.native".equals(name);
    }
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
                        "scenarios",Arrays.asList("app.observe","core.publication","core.resources","core.replacement","core.store","core.documents","core.streams","core.tasks","core.workspace","core.drafts","core.text","core.keeper","core.processes","build.native"));
                }
            });
            activity.runOnUiThread(result);
            try { return result.get(3,TimeUnit.SECONDS); } finally { result.cancel(false); }
        }
        if(!supports(name)) throw new IllegalArgumentException("scenario");
        String executable=new File(bundle,"bin/"+name.replace('.','-')).getPath();
        File supervisor=new File(bundle,"bin/trough-run");
        Map<String,String> env=Root.env(activity,activity.getCacheDir());
        if("build.native".equals(name)) return NativeReproduction.run(bundle,activity.getCacheDir(),env,
            "/system/bin/sh",new File(activity.getDataDir(),"d/usr/libexec/trough/tfix").getAbsolutePath());
        if("core.replacement".equals(name)) return ScenarioProcess.run(supervisor,activity.getCacheDir(),env,20,executable,
            new File(bundle,"fixtures/module-1.so").getPath(),new File(bundle,"fixtures/module-2.so").getPath());
        if("core.store".equals(name) || "core.documents".equals(name) || "core.streams".equals(name) || "core.tasks".equals(name) || "core.workspace".equals(name) || "core.drafts".equals(name) || "core.text".equals(name) || "core.keeper".equals(name) || "core.processes".equals(name)) {
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
