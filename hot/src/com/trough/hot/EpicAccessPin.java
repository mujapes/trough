package com.trough.hot;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/** Browser-owned Epic linking; source access confirmed with Trough's GitHub credential. */
final class EpicAccessPin extends LinearLayout {
    private static final String LINK="https://www.epicgames.com/account/connections";
    private final File pin,request,diagnostic,credential,pendingInvitation;
    private final TextView label;
    private final Button check,link;
    private boolean checking,closed,browserOpened;
    private long lastCheck;
    private String account="",access="not_checked",error="";
    private int httpStatus;
    private final Runnable automaticCheck=new Runnable(){public void run(){if(isAttachedToWindow()&&!closed&&pin.isFile()&&System.currentTimeMillis()-lastCheck>10000)checkAccess();}};
    EpicAccessPin(Context context,File home){
        super(context);pin=new File(home,".trough/epic-source-access-pin");request=new File(home,".trough/epic-source-access-open");diagnostic=new File(home,".trough/epic-source-access-runtime.json");credential=new File(home,".config/trough/github-token");pendingInvitation=new File(home,".trough/epic-source-access-invitation-pending");if(pendingInvitation.isFile())access="invitation_pending";
        setTag("epic-source-access-pin");setOrientation(HORIZONTAL);setGravity(android.view.Gravity.CENTER_VERTICAL);setBackgroundColor(0xff263247);int pad=dp(6);setPadding(pad,0,pad,0);
        label=new TextView(context);label.setTextColor(0xffedf3ff);label.setTextSize(11);addView(label,new LayoutParams(0,-2,1));
        link=button("Link GitHub","Open Epic linked accounts, then select Link under GitHub");link.setOnClickListener(new OnClickListener(){public void onClick(View v){open();}});
        check=button("Check","Check whether Trough can read Unreal Engine source");check.setOnClickListener(new OnClickListener(){public void onClick(View v){checkAccess();}});
        Button hide=button("×","Unpin Epic source access");hide.setOnClickListener(new OnClickListener(){public void onClick(View v){if(pin.delete()||!pin.exists()){request.delete();closed=true;removeCallbacks(automaticCheck);setVisibility(GONE);report();}else label.setText("Could not unpin. Try again.");}});
        setVisibility(pin.isFile()?VISIBLE:GONE);render();
    }
    private Button button(String title,String description){Button b=new Button(getContext());b.setText(title);b.setTextSize(11);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(7),0,dp(7),0);b.setContentDescription(description);addView(b,new LayoutParams("×".equals(title)?dp(36):-2,dp(44)));return b;}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();closed=false;if(pin.isFile()){post(automaticCheck);if(request.isFile())post(new Runnable(){public void run(){if(isAttachedToWindow()&&request.isFile())open();}});}}
    @Override protected void onDetachedFromWindow(){closed=true;removeCallbacks(automaticCheck);super.onDetachedFromWindow();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&pin.isFile())post(automaticCheck);}
    private void open(){try{getContext().startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(linkUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));request.delete();browserOpened=true;error="";render();report();}catch(RuntimeException failure){error="Could not open browser";render();report();}}
    private String linkUrl(){return "invitation_pending".equals(access)?"https://github.com/orgs/EpicGames/invitation":LINK;}
    private void render(){
        if(link!=null){link.setText("invitation_pending".equals(access)?"Join Epic":"Link GitHub");link.setContentDescription("invitation_pending".equals(access)?"Open your pending EpicGames GitHub invitation":"Open Epic linked accounts");}
        String state=checking?"Checking source access…":"available".equals(access)?"Unreal source access confirmed":"invitation_pending".equals(access)?"Invitation waiting — tap Join Epic":"credential_missing".equals(access)?"GitHub credential unavailable":"not_available".equals(access)?"Source access not available yet":"Choose GitHub → Link";
        label.setText((account.length()==0?"Epic → GitHub":("GitHub: "+account))+"\n"+state+(error.length()==0?"":"\n"+error));if(check!=null)check.setEnabled(!checking);
    }
    private void checkAccess(){
        if(checking||closed||!pin.isFile())return;checking=true;lastCheck=System.currentTimeMillis();render();
        new Thread(new Runnable(){public void run(){String login="",state="not_available",problem="";int code=0;
            try{
                String token=read(credential,8192).trim();if(token.length()==0)state="credential_missing";else{
                    JSONObject user=get("https://api.github.com/user",token);login=user.optString("login","");JSONObject repo=get("https://api.github.com/repos/EpicGames/UnrealEngine",token);code=repo.optInt("_http",0);state=code==200&&"EpicGames/UnrealEngine".equals(repo.optString("full_name"))?"available":"not_available";
                    if(code==404){JSONObject membership=get("https://api.github.com/user/memberships/orgs/EpicGames",token);if("pending".equals(membership.optString("state")))state="invitation_pending";}
                    if(code!=200&&code!=404)problem="GitHub HTTP "+code;
                }
            }catch(java.io.FileNotFoundException missing){state="credential_missing";}catch(Exception failure){problem="Access check failed; tap Check to retry";}
            final String resultAccount=login,resultState=state,resultError=problem;final int resultCode=code;
            post(new Runnable(){public void run(){checking=false;if(closed)return;account=resultAccount;access=resultState;if("invitation_pending".equals(access)){try{pendingInvitation.createNewFile();}catch(Exception ignored){}}else if("available".equals(access))pendingInvitation.delete();error=resultError;httpStatus=resultCode;render();report();}});
        }},"epic-source-access-check").start();
    }
    private static JSONObject get(String address,String token)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();try{
            connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("Authorization","Bearer "+token);connection.setRequestProperty("Accept","application/vnd.github+json");connection.setRequestProperty("User-Agent","Trough-source-access-check");int code=connection.getResponseCode();JSONObject result=new JSONObject();
            if(code==200){try(java.io.InputStream in=connection.getInputStream()){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[2048];for(int n;(n=in.read(buffer))!=-1;){if(bytes.size()+n>131072)throw new java.io.IOException("Response limit");bytes.write(buffer,0,n);}result=new JSONObject(new String(bytes.toByteArray(),"UTF-8"));}}
            result.put("_http",code);return result;
        }finally{connection.disconnect();}
    }
    private static String read(File file,int limit)throws Exception{try(FileInputStream in=new FileInputStream(file)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[1024];for(int n;(n=in.read(buffer))!=-1;){if(out.size()+n>limit)throw new java.io.IOException("File limit");out.write(buffer,0,n);}return new String(out.toByteArray(),"UTF-8");}}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private void report(){
        final String json=Json.write(Json.map("pinned",pin.isFile(),"browser_opened",browserOpened,"url",linkUrl(),"github_account",account,"source_access",access,"http_status",httpStatus,"error",error,"at_ms",System.currentTimeMillis()));
        new Thread(new Runnable(){public void run(){try(FileOutputStream out=new FileOutputStream(diagnostic)){out.write(json.getBytes("UTF-8"));}catch(Exception ignored){}}},"epic-access-state").start();
    }
}
