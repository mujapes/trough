package com.trough.app;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.res.Configuration;

/**
 * Process start in the main process (an Activity, the boot receiver, a job, a bound service or
 * a provider): the loader starts here, so a v2 bundle runs without any window. Slot and
 * isolated processes are left alone (Slot.java, Components.Sandbox).
 */
public class TroughApp extends Application {
    static final int REVIVE_JOB = 1;

    @Override public void onCreate() {
        super.onCreate();
        if (android.os.Process.isIsolated() || !getPackageName().equals(getProcessName())) return;
        HotLoader.init(this);
        revive();
    }

    private void host(String event, Object... args) { HotLoader l = HotLoader.process; if (l != null) l.host(this, "app." + event, args); }
    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); host("trimMemory", level); }
    @Override public void onLowMemory() { super.onLowMemory(); host("lowMemory"); }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); host("configuration", c); }

    /** A persisted periodic job: if Android kills Trough anyway, the job brings the process
     *  (and with it the loader and TroughService) back within about 15 minutes. */
    private void revive() {
        JobScheduler js = getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(REVIVE_JOB) != null) return;
        js.schedule(new JobInfo.Builder(REVIVE_JOB, new ComponentName(this, Components.Revive.class))
            .setPeriodic(15 * 60 * 1000L).setPersisted(true).build());
    }
}
