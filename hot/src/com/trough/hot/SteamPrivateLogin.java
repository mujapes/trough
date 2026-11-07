package com.trough.hot;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import org.json.JSONObject;
/** Explicit-tap private login; credentials never enter the conversation transport. */
final class SteamPrivateLogin {
 private static String accountName="";
 static void show(final Context context,final File home){
  LinearLayout fields=new LinearLayout(context);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(20,8,20,8);
  final EditText user=new EditText(context);user.setHint("Steam account name");user.setSingleLine(true);user.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);user.setSaveEnabled(false);user.setText(accountName);fields.addView(user);
  final EditText password=new EditText(context);password.setHint("Steam password");password.setSingleLine(true);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setSaveEnabled(false);fields.addView(password);
  android.widget.CheckBox reveal=new android.widget.CheckBox(context);reveal.setText("Show password");reveal.setTextColor(0xffdce9f8);fields.addView(reveal);reveal.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(android.widget.CompoundButton button,boolean checked){int at=password.getSelectionStart();password.setTransformationMethod(checked?null:android.text.method.PasswordTransformationMethod.getInstance());if(at>=0)password.setSelection(Math.min(at,password.length()));}});
  final long[] submitted={0};
  final android.widget.TextView result=new android.widget.TextView(context);result.setText("Use your Steam login account name, not your display name.");result.setPadding(0,8,0,8);fields.addView(result);
  final EditText guard=new EditText(context);guard.setHint("Steam Guard code (after sign-in requests it)");guard.setSingleLine(true);guard.setSaveEnabled(false);guard.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);fields.addView(guard);
  if(android.os.Build.VERSION.SDK_INT>=26){fields.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);}
  final android.app.Dialog dialog=new android.app.Dialog(context);dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
  fields.setBackgroundColor(0xff182434);fields.setPadding(dp(context,20),dp(context,20),dp(context,20),dp(context,16));
  android.widget.TextView title=new android.widget.TextView(context);title.setText("STEAM • CLOUD SIGN-IN");title.setTextSize(21);title.setTextColor(0xffeaf2ff);title.setPadding(0,0,0,16);fields.addView(title,0);
  user.setHint("Account name — not display name");
  for(EditText entry:new EditText[]{user,password,guard}){entry.setTextColor(0xfff1f6ff);entry.setHintTextColor(0xffaabbd0);entry.setTextSize(17);entry.setPadding(12,12,12,12);entry.setMinHeight(dp(context,52));entry.setBackgroundColor(0xff24364c);LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,dp(context,52));ep.bottomMargin=dp(context,10);entry.setLayoutParams(ep);}
  result.setTextColor(0xffffd180);result.setTextSize(16);result.setBackgroundColor(0xff0f1927);result.setPadding(dp(context,12),dp(context,12),dp(context,12),dp(context,12));
  label(context,fields,user,"1  STEAM ACCOUNT NAME");label(context,fields,password,"PASSWORD");
  final android.widget.TextView guardLabel=label(context,fields,guard,"2  STEAM GUARD • ONLY WHEN REQUESTED");guardLabel.setVisibility(android.view.View.GONE);guard.setVisibility(android.view.View.GONE);
  final android.widget.Button signIn=new android.widget.Button(context);signIn.setText("SIGN IN PRIVATELY");fields.addView(signIn);
  final android.widget.Button sendCode=new android.widget.Button(context);sendCode.setText("SEND STEAM GUARD CODE");fields.addView(sendCode);sendCode.setVisibility(android.view.View.GONE);
  signIn.setTextColor(0xff06131c);signIn.setBackgroundColor(0xff66d5f3);signIn.setMinHeight(dp(context,52));sendCode.setMinHeight(dp(context,48));
  android.widget.Button close=new android.widget.Button(context);close.setText("BACK TO HALF SWORD");fields.addView(close);close.setOnClickListener(new android.view.View.OnClickListener(){public void onClick(android.view.View v){password.setText("");dialog.dismiss();}});
  android.widget.ScrollView scroll=new android.widget.ScrollView(context);scroll.setFillViewport(true);scroll.addView(fields);dialog.setContentView(scroll);
  dialog.setOnShowListener(new DialogInterface.OnShowListener(){public void onShow(DialogInterface shown){final android.app.Dialog d=(android.app.Dialog)shown;
   signIn.setOnClickListener(new android.view.View.OnClickListener(){public void onClick(android.view.View v){try{String u=user.getText().toString().trim(),p=password.getText().toString();if(u.length()<1||u.length()>128||p.length()<1||p.length()>512)throw new IllegalArgumentException();JSONObject q=new JSONObject();q.put("action","login");q.put("username",u);q.put("password",p);write(home,q);submitted[0]=System.currentTimeMillis();accountName=u;password.setText("");result.setText("Sign-in sent privately. Waiting for Steam…");signIn.setEnabled(false);new android.os.Handler().postDelayed(new Runnable(){public void run(){signIn.setEnabled(true);}},5000);}catch(Exception e){Toast.makeText(context,"Could not queue login. Check fields.",Toast.LENGTH_LONG).show();}}});
   sendCode.setOnClickListener(new android.view.View.OnClickListener(){public void onClick(android.view.View v){try{String code=guard.getText().toString().trim();if(!code.matches("[A-Za-z0-9]{1,32}"))throw new IllegalArgumentException();JSONObject q=new JSONObject();q.put("action","guard");q.put("code",code);write(home,q);submitted[0]=System.currentTimeMillis();guard.setText("");result.setText("Guard code sent privately. Waiting for Steam…");}catch(Exception e){Toast.makeText(context,"Enter a valid Steam Guard code",Toast.LENGTH_LONG).show();}}});
  }});dialog.setCanceledOnTouchOutside(false);dialog.show();dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xff182434));dialog.getWindow().setLayout((int)(context.getResources().getDisplayMetrics().widthPixels*.94),-2);dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  final android.os.Handler monitor=new android.os.Handler();monitor.postDelayed(new Runnable(){long seen=0;public void run(){if(!dialog.isShowing())return;try{File f=new File(home,".trough/steam-auth-requests/cloud-status.json");if(f.isFile()&&f.lastModified()>seen&&f.length()<4096){seen=f.lastModified();JSONObject state=new JSONObject(new String(Payloads.readSmall(f),"UTF-8"));if(state.optDouble("updated_unix",0)*1000>=submitted[0]){String phase=state.optString("phase","");result.setText(state.optString("display","Waiting for Steam…"));boolean needs=phase.equals("guard_required")||phase.equals("guard_submitted");guard.setVisibility(needs?android.view.View.VISIBLE:android.view.View.GONE);guardLabel.setVisibility(needs?android.view.View.VISIBLE:android.view.View.GONE);sendCode.setVisibility(needs?android.view.View.VISIBLE:android.view.View.GONE);result.setTextColor(phase.equals("login_or_download_failed")?0xffff8b8b:0xffffd180);}}}catch(Exception ignored){}monitor.postDelayed(this,1000);}},1000);
 }
 private static int dp(Context c,int n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
 private static android.widget.TextView label(Context c,LinearLayout parent,android.view.View field,String text){android.widget.TextView l=new android.widget.TextView(c);l.setText(text);l.setTextSize(12);l.setTextColor(0xffb4c9e2);l.setPadding(0,dp(c,10),0,dp(c,6));parent.addView(l,parent.indexOfChild(field));return l;}
 private static void write(File home,JSONObject q)throws Exception{
  File dir=new File(home,".trough/steam-auth-requests");if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException();android.system.Os.chmod(dir.getAbsolutePath(),0700);
  String id=UUID.randomUUID().toString();File temp=new File(dir,id+".tmp"),target=new File(dir,id+".request");
  if(!temp.createNewFile())throw new java.io.IOException();android.system.Os.chmod(temp.getAbsolutePath(),0600);
  try{FileOutputStream out=new FileOutputStream(temp);try{out.write(q.toString().getBytes("UTF-8"));out.getFD().sync();}finally{out.close();}if(!temp.renameTo(target))throw new java.io.IOException();}finally{temp.delete();}
 }
}
