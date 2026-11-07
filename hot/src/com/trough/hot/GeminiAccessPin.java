package com.trough.hot;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import org.json.JSONObject;

/** User-driven browser authentication; backend owns credentials and access verification. */
final class GeminiAccessPin extends LinearLayout {
    private final File directory,pin,runtime,url,start,check,code;
    private final TextView label;
    private final Button signIn,verify,enter;
    private boolean attached;
    private final Runnable poll=new Runnable(){public void run(){if(!attached)return;render();postDelayed(this,2000);}};
    GeminiAccessPin(Context context,File home){
        super(context);directory=new File(home,".trough");pin=new File(directory,"gemini-access-pin");runtime=new File(directory,"gemini-access-runtime.json");url=new File(directory,"gemini-auth-url");start=new File(directory,"gemini-auth-start");check=new File(directory,"gemini-access-check");code=new File(directory,"gemini-auth-code");
        setTag("gemini-access-pin");setOrientation(HORIZONTAL);setGravity(android.view.Gravity.CENTER_VERTICAL);setBackgroundColor(0xff263247);setPadding(dp(6),0,dp(6),0);
        label=new TextView(context);label.setTextColor(0xffedf3ff);label.setTextSize(11);label.setMaxLines(3);addView(label,new LayoutParams(0,-2,1));
        signIn=button("Sign in","Start Google sign in for Gemini delegation");signIn.setOnClickListener(new OnClickListener(){public void onClick(View v){authenticate();}});
        enter=button("Code","Enter Google authorization code");enter.setOnClickListener(new OnClickListener(){public void onClick(View v){enterCode();}});
        verify=button("Check","Verify Gemini access");verify.setOnClickListener(new OnClickListener(){public void onClick(View v){request(check);}});
        Button hide=button("×","Unpin Gemini access");hide.setOnClickListener(new OnClickListener(){public void onClick(View v){if(pin.delete()||!pin.exists())setVisibility(GONE);}});render();
    }
    private Button button(String title,String description){Button b=new Button(getContext());b.setText(title);b.setTextSize(11);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(7),0,dp(7),0);b.setContentDescription(description);addView(b,new LayoutParams("×".equals(title)?dp(36):-2,dp(44)));return b;}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;post(poll);}
    @Override protected void onDetachedFromWindow(){attached=false;removeCallbacks(poll);super.onDetachedFromWindow();}
    private JSONObject state(){try{return new JSONObject(read(runtime,8192));}catch(Exception ignored){return new JSONObject();}}
    private void render(){setVisibility(pin.isFile()?VISIBLE:GONE);if(!pin.isFile())return;JSONObject s=state();String message=s.optString("message","Sign in to enable Gemini delegation");message=message.replace('\n',' ').replace('\r',' ');if(message.length()>180)message=message.substring(0,180);label.setText("Gemini\n"+message);boolean ready="ready".equals(s.optString("state"));signIn.setVisibility(ready?GONE:VISIBLE);signIn.setText(validUrl()!=null?"Open login":"Sign in");enter.setVisibility(!ready&&s.optBoolean("code_required",false)?VISIBLE:GONE);}
    private void authenticate(){
        String address=validUrl();if(address==null){request(start);return;}
        try{getContext().startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(RuntimeException failure){label.setText("Gemini\nCould not open browser. Try again.");}
    }
    private String validUrl(){try{String address=read(url,16384).trim();Uri u=Uri.parse(address);String host=u.getHost();if(!"https".equalsIgnoreCase(u.getScheme())||u.getUserInfo()!=null||u.getPort()!=-1||!("accounts.google.com".equalsIgnoreCase(host)||"oauth2.googleapis.com".equalsIgnoreCase(host)))return null;return address;}catch(Exception ignored){return null;}}
    private void enterCode(){final EditText input=new EditText(getContext());input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);input.setHint("Authorization code");new AlertDialog.Builder(getContext()).setTitle("Complete Gemini sign in").setMessage("Paste the code returned by Google. Trough will verify access.").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Submit",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface dialog,int which){String value=input.getText().toString().trim();if(value.length()>0&&value.length()<=4096){writePrivate(code,value);input.setText("");}else label.setText("Gemini\nCode was empty or too long. Try again.");}}).show();}
    private void request(File destination){if(writePrivate(destination,Long.toString(System.currentTimeMillis())))label.setText("Gemini\nRequest queued…");}
    private boolean writePrivate(File destination,String value){File temporary=new File(directory,destination.getName()+".ui-tmp");try{directory.mkdirs();try(FileOutputStream out=new FileOutputStream(temporary)){temporary.setReadable(false,false);temporary.setWritable(false,false);temporary.setReadable(true,true);temporary.setWritable(true,true);out.write(value.getBytes("UTF-8"));}if(!temporary.renameTo(destination))throw new java.io.IOException("Could not save request");return true;}catch(Exception failure){temporary.delete();label.setText("Gemini\nCould not save request. Try again.");return false;}}
    private static String read(File file,int limit)throws Exception{try(FileInputStream in=new FileInputStream(file)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];for(int n;(n=in.read(b))!=-1;){if(out.size()+n>limit)throw new java.io.IOException("File limit");out.write(b,0,n);}return new String(out.toByteArray(),"UTF-8");}}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
}
