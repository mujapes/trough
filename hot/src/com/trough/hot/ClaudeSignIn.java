package com.trough.hot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Independent subscription sign-in: no agent switch, chat draft or transcript code. */
final class ClaudeSignIn {
    private static final int TAG=0x7f0d74c1;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Context context;
    private final FrameLayout root;
    private final File home,cwd;
    private final String binary;
    private final Map<String,String> env;
    private final ScrollView panel;
    private final TextView status;
    private final EditText code;
    private final Button open,submit,paste,retry;
    private Attempt current; // Android main thread only
    private static final class Attempt {
        final String id=java.util.UUID.randomUUID().toString();
        ClaudeAgent.Login login;
        String url;
        boolean sent,finished;
    }

    static void show(Activity activity,FrameLayout root,File home,String binary,File cwd,Map<String,String> env) {
        Object previous=root.getTag(TAG);
        if(previous instanceof View && (((View)previous).getParent()==root || ((View)previous).getParent()==null)){restore(root);return;}
        new ClaudeSignIn(activity,root,home,binary,cwd,env);
    }
    static void restore(FrameLayout root) {
        Object previous=root.getTag(TAG);
        if(previous instanceof View){View view=(View)previous;if(view.getParent()==null)root.addView(view);if(view.getParent()==root)view.bringToFront();}
    }
    private ClaudeSignIn(Activity activity,FrameLayout owner,File h,String executable,File directory,Map<String,String> environment) {
        context=activity;root=owner;home=h;cwd=directory;binary=executable;env=new HashMap<String,String>(environment);
        env.put("CLAUDE_CONFIG_DIR",new File(home,".claude").getAbsolutePath());
        LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(12),dp(8),dp(12),dp(8));body.setBackgroundColor(0xff17251f);
        TextView title=new TextView(context);title.setText("Claude · Opus sign-in");title.setTextColor(0xffb9edc6);title.setTextSize(18);body.addView(title);
        status=new TextView(context);status.setTextColor(0xffe4ece6);status.setTextSize(13);body.addView(status);
        open=button("Open Claude sign-in");body.addView(open);
        code=new EditText(context);code.setSingleLine(true);code.setTextColor(Color.WHITE);code.setHintTextColor(0xffa7b8ac);code.setHint("One-time code from Claude");code.setTextSize(14);code.setSaveEnabled(false);code.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);if(android.os.Build.VERSION.SDK_INT>=26){code.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);code.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);}body.addView(code);
        LinearLayout actions=new LinearLayout(context);actions.setGravity(Gravity.END);
        paste=button("Paste & finish");submit=button("Finish sign-in");actions.addView(paste);actions.addView(submit);body.addView(actions);
        LinearLayout footer=new LinearLayout(context);footer.setGravity(Gravity.END);retry=button("Start again");Button close=button("Close");footer.addView(retry);footer.addView(close);body.addView(footer);
        panel=new ScrollView(context);panel.setFillViewport(false);panel.addView(body);
        FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(-1,Math.min(dp(290),Math.max(dp(160),root.getHeight()-dp(160))),Gravity.TOP);layout.setMargins(dp(10),dp(60),dp(10),0);root.addView(panel,layout);root.setTag(TAG,panel);panel.bringToFront();
        open.setOnClickListener(new View.OnClickListener(){public void onClick(View v){Attempt a=current;if(a==null||a.finished||a.url==null)return;try{context.getApplicationContext().startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(a.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception e){status.setText("Could not open the browser. Start again to retry.");}}});
        submit.setOnClickListener(new View.OnClickListener(){public void onClick(View v){finishCode(code.getText().toString());}});
        paste.setOnClickListener(new View.OnClickListener(){public void onClick(View v){ClipboardManager cb=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);ClipData clip=cb==null?null:cb.getPrimaryClip();if(clip==null||clip.getItemCount()==0){status.setText("Copy the one-time code on Claude's page first.");return;}CharSequence text=clip.getItemAt(0).coerceToText(context);finishCode(text==null?"":text.toString());}});
        retry.setOnClickListener(new View.OnClickListener(){public void onClick(View v){start();}});
        close.setOnClickListener(new View.OnClickListener(){public void onClick(View v){shutdown();root.removeView(panel);if(root.getTag(TAG)==panel)root.setTag(TAG,null);}});
        start();
    }
    private void finishCode(String value){
        Attempt a=current;String clean=value.trim();
        if(a==null||a.finished||a.sent||a.login==null||a.url==null){status.setText("Wait for Claude's page to be ready, then paste its code.");return;}
        if(clean.isEmpty()||clean.indexOf('\n')>=0||clean.indexOf('\r')>=0){status.setText("Paste only the one-time code from Claude's page.");return;}
        a.sent=true;code.setText("");ready(a);status.setText("Finishing Claude sign-in…");a.login.code(clean);
    }
    private void start(){
        Attempt previous=current;if(previous!=null&&!previous.finished)abandon(previous);
        final Attempt a=new Attempt();current=a;code.setText("");ready(a);retry.setEnabled(true);status.setText("Preparing Claude subscription sign-in…");record(a,false,"pending");
        new Thread(new Runnable(){public void run(){
            ClaudeAgent.Login made;
            try{made=new ClaudeAgent.Login(binary,cwd,env,new Agent.Listener(){
                public void onInit(String id,String model){} public void onTextDelta(String text){} public void onAssistantText(String text){} public void onToolUse(String id,String name,Map<String,Object> input){} public void onToolResult(String id,String text,boolean error){} public void onPermission(String id,String tool,Map<String,Object> input){} public void onResult(boolean ok,double cost,long duration,String text){} public void onExit(int exit,String tail){}
                public void onAuth(final String address,String ignored){main.post(new Runnable(){public void run(){if(current!=a||a.finished)return;Uri uri=Uri.parse(address);if(!"https".equals(uri.getScheme())||!("claude.com".equals(uri.getHost())||"claude.ai".equals(uri.getHost()))){abandon(a);ready(a);record(a,false,"failed");status.setText("Unexpected sign-in address. Tap Start again.");return;}a.url=address;ready(a);status.setText("1 · Open Claude and sign in.\n2 · Copy its code, return here, tap Paste & finish.");}});}
            },new ClaudeAgent.Login.Done(){public void onDone(final boolean ok,String ignored){main.post(new Runnable(){public void run(){if(current!=a||a.finished)return;a.finished=true;a.login=null;code.setText("");ready(a);status.setText(ok?"Signed in to Claude on this phone.":"Sign-in did not complete. Tap Start again.");try{if(ok)Payloads.writeSmall(new File(home,".claude/.trough-own-login"),"1");}catch(Exception ignoredWrite){}record(a,ok,ok?"signed_in":"failed");}});}});
            }catch(Exception failure){main.post(new Runnable(){public void run(){if(current!=a||a.finished)return;a.finished=true;ready(a);record(a,false,"start_failed");status.setText("Could not start Claude sign-in. Tap Start again.");}});return;}
            final ClaudeAgent.Login created=made;
            main.post(new Runnable(){public void run(){if(current!=a||a.finished){created.cancel();return;}a.login=created;ready(a);}});
        }},"trough-helper-sign-in").start();
    }
    private void ready(Attempt a){boolean waiting=a==current&&!a.finished&&a.url!=null&&!a.sent;open.setEnabled(waiting);submit.setEnabled(waiting&&a.login!=null);paste.setEnabled(waiting&&a.login!=null);}
    private void abandon(Attempt a){a.finished=true;if(a.login!=null)a.login.cancel();a.login=null;}
    private void shutdown(){Attempt a=current;current=null;code.setText("");open.setEnabled(false);submit.setEnabled(false);paste.setEnabled(false);retry.setEnabled(true);if(a!=null&&!a.finished){abandon(a);record(a,false,"cancelled");}}
    private void record(Attempt a,boolean ok,String state){try{Payloads.writeSmall(new File(home,".trough/claude-sign-in-result.json"),Json.write(Json.map("ok",ok,"status",state,"attempt",a.id,"at_ms",System.currentTimeMillis())));}catch(Exception ignored){}}
    private Button button(String text){Button b=new Button(context);b.setText(text);b.setTextSize(12);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);return b;}
    private int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
}
