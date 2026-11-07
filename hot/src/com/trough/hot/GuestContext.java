package com.trough.hot;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import java.io.File;

/**
 * A guest app's identity inside Trough (docs/LIFT.md step 3): its own data, files, cache and code
 * cache under files/guests/<package>/, and its own assets and resources from its (lifted) APK. The
 * guest's code sees this Context, never the host's, so its state never touches the host's.
 */
final class GuestContext extends ContextWrapper {
    private final File data;
    private final Resources res;

    GuestContext(Context host, String pkg, String apk) throws Exception {
        super(host);
        data = new File(new File(host.getFilesDir(), "guests"), pkg);
        for (String d : new String[]{"files", "cache", "code_cache", "no_backup"}) new File(data, d).mkdirs();
        android.content.pm.PackageManager pm = host.getPackageManager();
        android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(apk, 0);
        if (pi == null) throw new IllegalStateException("not an APK: " + apk);
        pi.applicationInfo.sourceDir = apk;
        pi.applicationInfo.publicSourceDir = apk;
        res = pm.getResourcesForApplication(pi.applicationInfo);
    }

    @Override public File getDataDir() { return data; }
    @Override public File getFilesDir() { return new File(data, "files"); }
    @Override public File getCacheDir() { return new File(data, "cache"); }
    @Override public File getCodeCacheDir() { return new File(data, "code_cache"); }
    @Override public File getNoBackupFilesDir() { return new File(data, "no_backup"); }
    @Override public Resources getResources() { return res; }
    @Override public AssetManager getAssets() { return res.getAssets(); }
    @Override public Context getApplicationContext() { return this; }
}
