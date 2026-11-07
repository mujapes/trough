package com.trough.hot;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.Map;

/** Persistent inline action. Private login opens only after an explicit tap. */
final class ProjectQuestionPin extends LinearLayout {
 private final File question;
 private final TextView label;
 private final Button open;
 private String address;
 private boolean privateSteam;
 private final File privateHome;
 ProjectQuestionPin(Context context,File home){
  super(context);privateHome=home;question=new File(home,".trough/project-question.json");
  setTag("project-question-banner");setOrientation(HORIZONTAL);setGravity(android.view.Gravity.CENTER_VERTICAL);setBackgroundColor(0xff263247);
  label=new TextView(context);label.setTextSize(11);label.setTextColor(0xffedf3ff);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setPadding(dp(6),0,dp(4),0);addView(label,new LayoutParams(0,dp(36),1));label.setGravity(android.view.Gravity.CENTER_VERTICAL);
  open=new Button(context);open.setText("Open");open.setTextSize(11);open.setMinHeight(0);open.setMinimumHeight(0);open.setMinWidth(0);open.setMinimumWidth(0);open.setPadding(dp(10),0,dp(10),0);open.setContentDescription("Open project sign-in when ready");addView(open,new LayoutParams(-2,dp(36)));
  open.setOnClickListener(new OnClickListener(){public void onClick(View view){if(privateSteam){SteamPrivateLogin.show(getContext(),privateHome);return;}if(address==null)return;try{getContext().startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(RuntimeException failure){label.setText("Could not open sign-in. Tap Open to retry.");}}});refresh();
 }
 void refresh(){
  try{
   if(!question.isFile()||question.length()>8192){setVisibility(GONE);return;}
   Map<String,Object> q=Json.obj(Json.parse(new String(Payloads.readSmall(question),"UTF-8")));
   String title=Json.str(q,"title"),url=Json.str(q,"url"),id=Json.str(q,"id");
   if(id==null||id.length()>120||title==null||title.length()>120)throw new IllegalArgumentException("Question header");
   privateSteam="steam_private_login".equals(Json.str(q,"action"));
   Uri uri=url==null?null:Uri.parse(url);address=uri!=null&&"https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&url.length()<2048?url:null;
   label.setText(title.replace('\n',' ').replace('\r',' '));label.setContentDescription(Json.str(q,"question"));open.setText(privateSteam?"Sign in":"Open");open.setVisibility(address==null&&!privateSteam?GONE:VISIBLE);setVisibility(VISIBLE);
   String once=Json.str(q,"open_once");if(privateSteam&&once!=null&&once.matches("[a-zA-Z0-9_-]{1,100}")){File marker=new File(privateHome,".trough/"+once+".opened");if(marker.createNewFile()){post(new Runnable(){public void run(){SteamPrivateLogin.show(getContext(),privateHome);}});}}
  }catch(Exception failure){setVisibility(GONE);}
 }
 private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
}
