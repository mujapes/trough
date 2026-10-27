package com.trough.hot;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Moves the Claude login from the VM into Trough without it ever sitting readable in shared
 * storage (any app with storage access can read Download/).
 *
 * Trough keeps an RSA-3072 private key in its own data dir and publishes only the public half
 * at Download/trough/app-pubkey.der. tools/send_creds.py encrypts the login to it and writes
 * Download/trough/creds.bin:
 *     u16 wrapped-key length | RSA-OAEP(SHA-256) wrapped AES-256 key | 12-byte IV | AES-GCM
 * Imported into $HOME/.claude/.credentials.json (0600) and the shared copy is deleted.
 */
final class Creds {
    private Creds() {}

    static File shared() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "trough");
    }

    /** Returns a short status string; never the secret. */
    static String sync(Context ctx, File home) throws Exception {
        File keyFile = new File(new File(ctx.getFilesDir(), "keys"), "trough_rsa.p8");
        PrivateKey key;
        if (!keyFile.exists()) {
            keyFile.getParentFile().mkdirs();
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(3072);
            KeyPair kp = g.generateKeyPair();
            write(keyFile, kp.getPrivate().getEncoded());
            keyFile.setReadable(false, false);
            keyFile.setReadable(true, true);
            write(new File(shared(), "app-pubkey.der"), kp.getPublic().getEncoded());
            key = kp.getPrivate();
        } else {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Payloads.readSmall(keyFile)));
        }
        File in = new File(shared(), "creds.bin");
        if (!in.exists()) {
            File have = new File(home, ".claude/.credentials.json");
            return have.exists() ? "installed earlier" : "waiting for creds.bin (public key published)";
        }
        ByteBuffer b = ByteBuffer.wrap(Payloads.readSmall(in));
        byte[] wrapped = new byte[b.getShort() & 0xffff];
        b.get(wrapped);
        byte[] iv = new byte[12];
        b.get(iv);
        byte[] body = new byte[b.remaining()];
        b.get(body);
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.DECRYPT_MODE, key, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] aes = rsa.doFinal(wrapped);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aes, "AES"), new GCMParameterSpec(128, iv));
        byte[] plain = gcm.doFinal(body);
        File dir = new File(home, ".claude");
        dir.mkdirs();
        File out = new File(dir, ".credentials.json");
        write(out, plain);
        out.setReadable(false, false);
        out.setReadable(true, true);
        out.setWritable(false, false);
        out.setWritable(true, true);
        boolean deleted = in.delete();
        return "imported " + plain.length + " bytes" + (deleted ? ", shared copy deleted" : ", COULD NOT delete shared copy");
    }

    private static void write(File f, byte[] data) throws Exception {
        File tmp = new File(f.getPath() + ".new");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(data); }
        if (!tmp.renameTo(f)) throw new IllegalStateException("rename " + f);
    }
}
