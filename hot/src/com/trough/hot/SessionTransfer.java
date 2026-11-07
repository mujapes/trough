package com.trough.hot;

import android.content.Context;
import java.io.File;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** User-requested session move. Input is encrypted to this device inside the signed bundle. */
final class SessionTransfer {
    static Map<String,Object> run(final Context ctx, File bundle) throws Exception {
        final JSONObject data;
        File input=new File(bundle,"session-transfer.bin");
        if(input.length()>8*1024*1024) throw new IllegalArgumentException("transfer size");
        ByteBuffer b=ByteBuffer.wrap(Payloads.readSmall(input));
        byte[] wrapped=new byte[b.getShort()&65535]; b.get(wrapped);
        byte[] iv=new byte[12];b.get(iv);byte[] body=new byte[b.remaining()];b.get(body);
        Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.DECRYPT_MODE,KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(
            Payloads.readSmall(new File(ctx.getFilesDir(),"keys/trough_rsa.p8")))),
            new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
        Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(rsa.doFinal(wrapped),"AES"),new GCMParameterSpec(128,iv));
        data=new JSONObject(new String(aes.doFinal(body),"UTF-8"));
        final String id=data.getString("id");
        if(!id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("session id");
        final File home=new File(ctx.getFilesDir(),"home");
        File codex=new File(home,".codex"),sessions=new File(codex,"sessions/2026/10/31");sessions.mkdirs();
        File session=new File(sessions,"rollout-2026-10-31T05-29-27-"+id+".jsonl");
        File mark=new File(codex,".transferred-"+id);
        if(!mark.exists()) {
            Payloads.writeSmall(session,data.getString("rollout"));
            File auth=new File(codex,"auth.json");
            if(auth.exists()) Payloads.writeSmall(new File(codex,"auth.before-session-transfer.json"),new String(Payloads.readSmall(auth),"UTF-8"));
            Payloads.writeSmall(auth,data.getString("auth"));
            android.system.Os.chmod(auth.getPath(),0600);
            Payloads.writeSmall(mark,"imported");
        }
        final FutureTask<Map<String,Object>> task=new FutureTask<>(new Callable<Map<String,Object>>() {
            public Map<String,Object> call() throws Exception { return Main.resumeTransferredCodex(id); }
        });
        new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
        return task.get(5,TimeUnit.SECONDS);
    }
}
