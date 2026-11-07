package com.trough.hot;

import android.content.Context;
import java.io.File;
import java.util.Map;
import org.json.JSONObject;

/** Explicit local login inspection; never starts a model turn or returns credentials. */
final class AgentInspection {
    static Map<String,Object> run(Context ctx, File bundle) throws Exception {
        File home = new File(ctx.getFilesDir(), "home");
        JSONObject manifest = new JSONObject(new String(Payloads.readSmall(new File(bundle,"manifest.json")), "UTF-8"));
        String sha = manifest.getJSONObject("payloads").optString("codex", "");
        File executable = CodexRuntime.install(Payloads.dir(ctx), Json.obj(Json.parse(manifest.toString())));
        if (!sha.matches("[a-f0-9]{64}") || executable==null || !executable.isFile())
            return Json.map("ok",true,"installed",false,"saved_login",false,"live_request_verified",false);
        Map<String,String> env = Root.env(ctx,ctx.getCacheDir());
        env.put("CODEX_HOME",new File(home,".codex").getPath());
        // Match CodexAgent's actual Android launch. The glibc supervisor adds texec's
        // path filter, which is a different launch path and can fail independently.
        ProcessBuilder pb = new ProcessBuilder(executable.getPath(),"login","status");
        pb.environment().clear(); pb.environment().putAll(env);
        pb.directory(ctx.getCacheDir());
        pb.redirectOutput(new File("/dev/null")); pb.redirectError(new File("/dev/null"));
        pb.redirectInput(new File("/dev/null"));
        Process process=pb.start();
        boolean done;
        try { done=process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS); }
        finally { if(process.isAlive()) process.destroyForcibly(); }
        int exit=done?process.exitValue():-1;
        File selected = new File(home,".trough-agent");
        String agent = selected.isFile() ? new String(Payloads.readSmall(selected),"UTF-8").trim() : "claude";
        if (!"codex".equals(agent) && !"claude".equals(agent)) agent="other";
        final java.util.concurrent.FutureTask<Map<String,Object>> state = new java.util.concurrent.FutureTask<>(
            new java.util.concurrent.Callable<Map<String,Object>>() { public Map<String,Object> call() { return Main.codexState(); } });
        new android.os.Handler(android.os.Looper.getMainLooper()).post(state);
        return Json.map("ok",true,"installed",true,"selected_agent",agent,"chat",state.get(3,java.util.concurrent.TimeUnit.SECONDS),
            "recovery",LegacyChatRecovery.receipt(ctx,bundle),
            "saved_login",done && exit==0,"exit",exit,
            "timed_out",!done,"live_request_verified",false,"executable",executable.getPath(),
            "code_mode_host",new File(executable.getParentFile(),"codex-code-mode-host").getPath(),
            "code_mode_host_executable",new File(executable.getParentFile(),"codex-code-mode-host").canExecute());
    }
}
