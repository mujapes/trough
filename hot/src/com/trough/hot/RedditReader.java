package com.trough.hot;
import android.app.Activity;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.text.Html;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
/** Experimental Dogshit UI interpreter: source-defined controls and card fields; native RSS adapter. */
final class RedditReader {
 static String openCommunity(android.view.ViewGroup parent){View box=parent.findViewWithTag("trough-reddit");Object sub=box==null?null:box.getTag(0x7f0d7481);return sub instanceof String?(String)sub:null;}
 static Map<String,Object> snapshot(android.view.ViewGroup parent){View box=parent.findViewWithTag("trough-reddit");Object owner=box==null?null:box.getTag(0x7f0d7482);return owner instanceof Screen?((Screen)owner).snapshot():null;}
 static void restore(Activity a,android.view.ViewGroup parent,ScrollView outer,Map<?,?> saved){
  Object sub=saved.get("community");if(!(sub instanceof String))return;
  Screen screen=new Screen(a,parent,outer,(String)sub);screen.saved=saved;screen.open();
 }
 static boolean freshCache(long now,long saved){return saved>0 && now>=saved && now-saved<300000L;}
 static long retryDelay(String seconds){try{return Math.min(86400L,Math.max(900L,Long.parseLong(seconds)))*1000L;}catch(Exception e){return 900000L;}}
 static final String SOURCE="work/trough-lab/reddit/reader.ds";
 static final class Spec {
  String title="Reddit",hint="",community="android",input="Community";int limit=20;
  final ArrayList<String[]> buttons=new ArrayList<String[]>();final LinkedHashMap<String,Integer> fields=new LinkedHashMap<String,Integer>();
  static Spec parse(String text){Spec s=new Spec();String[]lines=text.split("\\r?\\n");if(lines.length==0||!lines[0].equals("dogshit ui 1"))throw new IllegalArgumentException("Expected dogshit ui 1");
   for(int i=1;i<lines.length;i++){String l=lines[i].trim();if(l.isEmpty()||l.startsWith("#"))continue;String[]p=l.split(" +",3);if(p.length<2)throw new IllegalArgumentException("Line "+(i+1));
    if(p[0].equals("title"))s.title=l.substring(6);else if(p[0].equals("hint"))s.hint=l.substring(5);else if(p[0].equals("community"))s.community=p[1];else if(p[0].equals("input"))s.input=l.substring(6);
    else if(p[0].equals("limit")){s.limit=Integer.parseInt(p[1]);if(s.limit<1||s.limit>30)throw new IllegalArgumentException("Limit 1..30");}
    else if(p[0].equals("button")&&p.length==3){if(!Arrays.asList("browse","new","back","reload").contains(p[2])||s.buttons.size()>=6)throw new IllegalArgumentException("Invalid action");s.buttons.add(new String[]{p[1],p[2]});}
    else if(p[0].equals("card")&&p.length==3){int size=Integer.parseInt(p[2]);if(!Arrays.asList("title","author","body").contains(p[1])||size<10||size>24)throw new IllegalArgumentException("Invalid field");s.fields.put(p[1],size);}
    else throw new IllegalArgumentException("Unknown instruction at line "+(i+1));
   }if(!s.community.matches("[A-Za-z0-9_]{1,40}")||s.fields.isEmpty())throw new IllegalArgumentException("Incomplete screen");return s;
  }
 }
 static final class Post {String title="",author="",body="",link="",image="";}
 static void show(Activity a,android.view.ViewGroup parent,ScrollView outer){show(a,parent,outer,null);}
 static void show(Activity a,android.view.ViewGroup parent,ScrollView outer,String subreddit){
  for(int i=0;i<parent.getChildCount();i++){View v=parent.getChildAt(i);if("trough-reddit".equals(v.getTag())){parent.removeView(v);break;}}
  new Screen(a,parent,outer,subreddit).open();
 }
 static final class Screen {
  final Activity a;final android.view.ViewGroup parent;final ScrollView outer;final Handler main=new Handler(Looper.getMainLooper());
  final ExecutorService images=Executors.newFixedThreadPool(2);
  final ExecutorService worker=Executors.newSingleThreadExecutor();final ArrayDeque<String> history=new ArrayDeque<String>();
  LinearLayout box,cards;TextView community,status;final String requestedCommunity;int shown=10;ArrayList<Post> currentPosts;Spec spec;boolean closed;int generation;String path="";Map<?,?> saved;
  Screen(Activity a,android.view.ViewGroup p,ScrollView o,String sub){this.a=a;parent=p;outer=o;requestedCommunity=sub;}
  int dp(int value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
  TextView text(String value,int size,int color){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(8),dp(2),dp(8),dp(2));return t;}
  void open(){parent.getRootView().setTag(0x7f0d7483,Boolean.TRUE);try{File f=new File(a.getFilesDir(),SOURCE);spec=Spec.parse(read(new FileInputStream(f),32768));if(requestedCommunity!=null&&requestedCommunity.matches("[A-Za-z0-9_]{1,40}"))spec.community=requestedCommunity;}catch(Exception e){TextView t=text("Reddit screen: "+e.getMessage(),14,0xfff3b5ae);parent.addView(t);worker.shutdownNow();return;}
   box=new LinearLayout(a);box.setOrientation(1);box.setTag("trough-reddit");box.setTag(0x7f0d7481,spec.community);box.setTag(0x7f0d7482,this);box.setPadding(10,12,10,12);box.setBackgroundColor(0xf2102229);
   LinearLayout header=new LinearLayout(a);header.addView(text(spec.title,16,0xffc4f1dc),new LinearLayout.LayoutParams(0,-2,1));Button close=new Button(a);close.setText("×");header.addView(close,new LinearLayout.LayoutParams(dp(44),dp(44)));close.setOnClickListener(new View.OnClickListener(){public void onClick(View v){parent.getRootView().setTag(0x7f0d7483,Boolean.FALSE);close();parent.removeView(box);}});Button chat=new Button(a);chat.setText("Chat");chat.setTextSize(11);header.addView(chat);chat.setOnClickListener(new View.OnClickListener(){public void onClick(View v){parent.getRootView().setTag(0x7f0d7483,Boolean.FALSE);outer.fullScroll(View.FOCUS_DOWN);}});box.addView(header);if(!spec.hint.isEmpty())box.addView(text(spec.hint,11,0xff93b4b6));
   community=text("r/"+spec.community+"  ·  change with /reddit community",12,0xff93b4b6);box.addView(community);

   LinearLayout actions=new LinearLayout(a);for(String[]b:spec.buttons){final String action=b[1];Button button=new Button(a);button.setText(b[0]);button.setTextSize(12);button.setTextColor(0xffb2e3d5);button.setBackgroundColor(Color.TRANSPARENT);actions.addView(button,new LinearLayout.LayoutParams(0,dp(44),1));button.setOnClickListener(new View.OnClickListener(){public void onClick(View v){act(action);}});}box.addView(actions);
   LinearLayout local=new LinearLayout(a);local.setOrientation(1);local.addView(text("Offline media · no downloads",11,0xff93b4b6));final LinearLayout playerSlot=new LinearLayout(a);playerSlot.setOrientation(1);
   String[][]clips={{"Them Changes","them-changes-low.mp4"},{"Strawberry backwards","strawberry-reverse.mp4"},{"Night Current audio","dogshit-night-current.wav"}};
   LinearLayout choices=new LinearLayout(a);for(String[]clip:clips){final File file=new File(a.getFilesDir(),"home/.trough/media/"+clip[1]);if(!file.isFile())continue;Button choice=new Button(a);choice.setText(clip[0]);choice.setTextSize(10);choice.setTextColor(0xffb2e3d5);choice.setBackgroundColor(Color.TRANSPARENT);choices.addView(choice,new LinearLayout.LayoutParams(0,dp(48),1));choice.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(closed)return;playerSlot.removeAllViews();DogshitPlayer.showInline(a,file,new ShortPresentation.Listener(){public void finished(Map<String,Object> result){if(!Boolean.TRUE.equals(result.get("ok"))&&!closed)status.setText("Playback: "+String.valueOf(result.get("error")));}},playerSlot);}});}local.addView(choices);local.addView(playerSlot);box.addView(local);
   status=text("Connecting…",11,0xffa1b7bb);box.addView(status);cards=new LinearLayout(a);cards.setOrientation(1);box.addView(cards,new LinearLayout.LayoutParams(-1,-2));
   box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){close();}});
   parent.addView(box,new android.view.ViewGroup.LayoutParams(-1,-2));if(saved==null)outer.post(new Runnable(){public void run(){if(!closed)outer.scrollTo(0,box.getTop());}});if(!restoreSaved())act("browse");
  }
  Map<String,Object> snapshot(){Map<String,Object> m=new HashMap<String,Object>();m.put("community",spec.community);m.put("path",path);m.put("history",new ArrayList<String>(history));m.put("shown",shown);m.put("offset",outer.getScrollY()-box.getTop());m.put("status",status.getText().toString());
   if(currentPosts!=null){ArrayList<String[]> rows=new ArrayList<String[]>();for(Post p:currentPosts)rows.add(new String[]{p.title,p.author,p.body,p.link,p.image});m.put("posts",rows);}return m;}
  boolean restoreSaved(){if(saved==null||!(saved.get("posts") instanceof List)||!(saved.get("path") instanceof String))return false;
   path=(String)saved.get("path");Object count=saved.get("shown");shown=count instanceof Number?Math.max(1,Math.min(30,((Number)count).intValue())):10;
   Object previous=saved.get("history");if(previous instanceof List)for(Object item:(List<?>)previous)if(item instanceof String&&history.size()<30)history.addLast((String)item);
   ArrayList<Post> posts=new ArrayList<Post>();for(Object row:(List<?>)saved.get("posts")){if(!(row instanceof String[])||((String[])row).length<4)continue;String[]v=(String[])row;Post p=new Post();p.title=v[0];p.author=v[1];p.body=v[2];p.link=v[3];if(v.length>4)p.image=v[4];posts.add(p);if(posts.size()==30)break;}
   render(posts);status.setText(String.valueOf(saved.get("status")));final int offset=saved.get("offset") instanceof Number?((Number)saved.get("offset")).intValue():0;
   box.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){box.getViewTreeObserver().removeOnPreDrawListener(this);if(!closed)outer.scrollTo(0,Math.max(0,box.getTop()+offset));return true;}});saved=null;return true;
  }
  void close(){if(closed)return;closed=true;generation++;worker.shutdownNow();images.shutdownNow();}
  void act(String action){if(closed)return;if(action.equals("reload")){Map<String,Object> state=snapshot();close();parent.removeView(box);restore(a,parent,outer,state);return;}if(action.equals("back")){if(!history.isEmpty())load(history.pop(),false);return;}
   String sub=spec.community;
   history.clear();load("/r/"+sub+"/"+(action.equals("new")?"new":"hot")+"/.rss?limit="+spec.limit,false);
  }
  void load(final String next,boolean remember){if(closed)return;if(remember&&!path.isEmpty())history.push(path);path=next;final int version=++generation;status.setText("Loading Reddit…");
   try{worker.execute(new Runnable(){public void run(){String xml="",message="Live Reddit RSS · read-only";File cache=new File(a.getFilesDir(),"home/.trough/reddit-cache/"+Integer.toHexString(next.hashCode())+".xml");
    try{long now=System.currentTimeMillis();File cooldown=new File(a.getFilesDir(),"home/.trough/reddit-cache/cooldown");
     if(cache.isFile()&&freshCache(now,cache.lastModified())){xml=read(new FileInputStream(cache),1048576);message="Cached Reddit · less than 5 minutes old";}
     else {
     if(cooldown.lastModified()>now)throw new IOException("Reddit cooldown · try again later");
     HttpURLConnection c=(HttpURLConnection)new URL("https://www.reddit.com"+next).openConnection();c.setRequestProperty("User-Agent","TroughReader/0.1 (Android; read-only RSS)");c.setConnectTimeout(12000);c.setReadTimeout(12000);try{int code=c.getResponseCode();if(code==429){cooldown.getParentFile().mkdirs();cooldown.createNewFile();cooldown.setLastModified(System.currentTimeMillis()+retryDelay(c.getHeaderField("Retry-After")));}
     if(code!=200)throw new IOException("Reddit HTTP "+code);xml=read(c.getInputStream(),1048576);}finally{c.disconnect();}
     parse(xml,spec.limit);cache.getParentFile().mkdirs();File temp=new File(cache.getPath()+".new");try(FileOutputStream o=new FileOutputStream(temp)){o.write(xml.getBytes("UTF-8"));}if(!temp.renameTo(cache))temp.delete();
     }
    }catch(Exception e){message="Offline copy · "+e.getMessage();try{xml=read(new FileInputStream(cache),1048576);}catch(Exception absent){xml="";message="Could not load Reddit: "+e.getMessage();}}
    final String note=message;final ArrayList<Post> posts;try{posts=parse(xml,spec.limit);}catch(Exception e){main.post(new Runnable(){public void run(){if(!closed&&version==generation)status.setText("Reddit returned an unreadable feed");}});return;}
    main.post(new Runnable(){public void run(){if(closed||version!=generation)return;shown=10;render(posts);outer.post(new Runnable(){public void run(){if(!closed)outer.scrollTo(0,box.getTop());}});status.setText(note+(posts.isEmpty()?" · no posts":""));}});
   }});}catch(RejectedExecutionException ignored){}
  }
  void preview(LinearLayout card,final Post post){
   final ImageView view=new ImageView(a);view.setScaleType(ImageView.ScaleType.CENTER_CROP);view.setBackgroundColor(0xff203d43);view.setContentDescription("Image preview: "+post.title+". Tap to expand");card.addView(view,new LinearLayout.LayoutParams(-1,dp(120)));
   view.setOnClickListener(new View.OnClickListener(){boolean expanded;public void onClick(View v){expanded=!expanded;view.setScaleType(expanded?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP);view.getLayoutParams().height=dp(expanded?300:120);view.requestLayout();}});
   final int version=generation;try{images.execute(new Runnable(){public void run(){try{final android.graphics.Bitmap bitmap=fetchImage(post.image,new File(a.getCacheDir(),"reddit-previews"));main.post(new Runnable(){public void run(){if(!closed&&version==generation)view.setImageBitmap(bitmap);}});}catch(Exception e){main.post(new Runnable(){public void run(){if(!closed&&version==generation){view.setImageDrawable(null);view.getLayoutParams().height=dp(32);view.setContentDescription("Preview unavailable or exceeds 64 KB data limit");view.requestLayout();}}});}}});}catch(RejectedExecutionException ignored){}
  }
  void render(ArrayList<Post>posts){currentPosts=posts;cards.removeAllViews();for(int entry=0;entry<Math.min(shown,posts.size());entry++){final Post post=posts.get(entry);LinearLayout card=new LinearLayout(a);card.setOrientation(1);card.setPadding(0,dp(6),0,dp(7));card.setBackgroundColor(entry%2==0?0xff152e35:0xff11282f);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(2);cards.addView(card,lp);
    for(Map.Entry<String,Integer> field:spec.fields.entrySet()){String key=field.getKey(),value=key.equals("title")?post.title:key.equals("author")?post.author:post.body;if(value.isEmpty())continue;if(key.equals("body")&&value.length()>900)value=value.substring(0,900)+"…";TextView t=text(value,field.getValue(),key.equals("title")?0xffe1f0e7:key.equals("author")?0xff8bbfb8:0xffbdcdd0);if(!path.contains("/comments/")){t.setMaxLines(key.equals("title")?2:1);t.setEllipsize(android.text.TextUtils.TruncateAt.END);}card.addView(t);}
    if(!post.image.isEmpty())preview(card,post);
    card.setContentDescription(post.title+". Open discussion");card.setOnClickListener(new View.OnClickListener(){public void onClick(View v){try{URI uri=new URI(post.link);String host=uri.getHost(),p=uri.getPath();if(("www.reddit.com".equals(host)||"reddit.com".equals(host))&&p!=null&&p.matches("/r/[A-Za-z0-9_]+/comments/[A-Za-z0-9]+/.*")){load(p+(p.endsWith("/")?"":"/")+".rss?limit="+spec.limit,true);}else status.setText("No Reddit discussion link in this entry");}catch(Exception e){status.setText("Invalid discussion link");}}});
   }if(shown<posts.size()){Button more=new Button(a);more.setText("Show more posts");more.setTextColor(0xffb2e3d5);more.setBackgroundColor(Color.TRANSPARENT);cards.addView(more);more.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(closed)return;final int y=outer.getScrollY();shown+=5;render(currentPosts);outer.post(new Runnable(){public void run(){if(!closed)outer.scrollTo(0,y);}});}});}

  }
 }
 static final class PreviewDiskCache {
  static File entry(File directory,String url)throws IOException{
   try{byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"));StringBuilder key=new StringBuilder();for(byte b:digest){int value=b&255;key.append("0123456789abcdef".charAt(value>>>4));key.append("0123456789abcdef".charAt(value&15));}return new File(directory,key+".bin");}catch(java.security.NoSuchAlgorithmException error){throw new IOException(error);}
  }
  static synchronized byte[] load(File directory,String url)throws IOException{
   File file=entry(directory,url);if(!file.isFile())return null;
   if(!directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile()))return null;
   if(file.length()<1||file.length()>65536){file.delete();return null;}
   try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] buffer=new byte[4096];int count;while((count=in.read(buffer))!=-1){if(out.size()+count>65536)throw new IOException("Cached preview too large");out.write(buffer,0,count);}return out.toByteArray();
   }
  }
  static synchronized void store(File directory,String url,byte[] data)throws IOException{
   if(data.length<1||data.length>65536)return;
   if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Preview cache unavailable");
   File temporary=File.createTempFile("preview-",".part",directory);
   try{try(OutputStream out=new FileOutputStream(temporary)){out.write(data);}if(!temporary.renameTo(entry(directory,url)))throw new IOException("Preview cache rename failed");}finally{temporary.delete();}
   File[] entries=directory.listFiles(new FilenameFilter(){public boolean accept(File dir,String name){return name.matches("[0-9a-f]{64}\\.bin");}});
   if(entries==null)return;Arrays.sort(entries,new Comparator<File>(){public int compare(File a,File b){int order=Long.compare(b.lastModified(),a.lastModified());return order!=0?order:a.getName().compareTo(b.getName());}});
   for(int i=32;i<entries.length;i++)entries[i].delete();
  }
 }
 static final android.util.LruCache<String,android.graphics.Bitmap> IMAGE_CACHE=new android.util.LruCache<String,android.graphics.Bitmap>(8*1024*1024){protected int sizeOf(String key,android.graphics.Bitmap b){return b.getByteCount();}};
 static boolean imageHost(String value){try{URI u=new URI(value);String host=u.getHost();return "https".equals(u.getScheme())&&host!=null&&(host.equals("i.redd.it")||host.equals("preview.redd.it")||host.equals("external-preview.redd.it")||host.equals("b.thumbs.redditmedia.com")||host.equals("a.thumbs.redditmedia.com"));}catch(Exception e){return false;}}
 static String imageSource(String html){java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?is)<img\\b[^>]*?\\bsrc=[\"']([^\"']+)[\"']").matcher(html);if(!m.find())return "";String url=Html.fromHtml(m.group(1),Html.FROM_HTML_MODE_LEGACY).toString();return imageHost(url)?url:"";}
 static android.graphics.Bitmap fetchImage(String url,File disk)throws Exception{
  android.graphics.Bitmap cached=IMAGE_CACHE.get(url);if(cached!=null)return cached;if(!imageHost(url))throw new IOException("Unsupported image host");
  try{byte[] local=PreviewDiskCache.load(disk,url);if(local!=null){try{android.graphics.Bitmap image=decodeImage(local);IMAGE_CACHE.put(url,image);return image;}catch(IOException corrupt){PreviewDiskCache.entry(disk,url).delete();}}}catch(IOException cacheFailure){}
  HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(8000);c.setReadTimeout(8000);byte[] data;
  try{if(c.getResponseCode()!=200)throw new IOException("Image unavailable");if(c.getContentLengthLong()>65536L)throw new IOException("Preview exceeds data budget");try(InputStream in=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[]buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(bytes.size()+n>65536)throw new IOException("Image too large");bytes.write(buf,0,n);}data=bytes.toByteArray();}}finally{c.disconnect();}
  android.graphics.Bitmap image=decodeImage(data);IMAGE_CACHE.put(url,image);try{PreviewDiskCache.store(disk,url,data);}catch(IOException cacheFailure){}return image;
 }
 static android.graphics.Bitmap decodeImage(byte[] data)throws IOException{
  android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(data,0,data.length,o);if(o.outWidth<=0||o.outHeight<=0)throw new IOException("Invalid image");o.inSampleSize=1;while(o.outWidth/o.inSampleSize>320||o.outHeight/o.inSampleSize>320)o.inSampleSize*=2;o.inJustDecodeBounds=false;
  android.graphics.Bitmap b=android.graphics.BitmapFactory.decodeByteArray(data,0,data.length,o);if(b==null)throw new IOException("Image decode failed");return b;
 }
 static String read(InputStream in,int limit)throws IOException{try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[]b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Feed exceeds limit");out.write(b,0,n);}return out.toString("UTF-8");}finally{in.close();}}
 static String clean(String value){return value.replace("\ufffc", "").replace("[link]", "").replace("[comments]", "").replaceAll("(?s)(?:^|\\n)[ \\t]*submitted by\\s+/u/.*$", "").replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();}
 static String plain(String html){return clean(Html.fromHtml(html,Html.FROM_HTML_MODE_LEGACY).toString());}
 static ArrayList<Post> parse(String xml,int limit)throws Exception{ArrayList<Post> out=new ArrayList<Post>();if(xml.isEmpty())return out;XmlPullParser p=android.util.Xml.newPullParser();p.setInput(new StringReader(xml));Post current=null;boolean author=false;for(int event=p.next();event!=XmlPullParser.END_DOCUMENT;event=p.next()){
  if(event==XmlPullParser.START_TAG){String name=p.getName();if(name.equals("entry")){current=new Post();author=false;}else if(current!=null){if(name.equals("author"))author=true;else if(name.equals("name")&&author)current.author=p.nextText();else if(name.equals("title"))current.title=p.nextText();else if(name.equals("content"))current.body=p.nextText();else if(name.equals("link")){String href=p.getAttributeValue(null,"href");if(href!=null)current.link=href;}}}
  else if(event==XmlPullParser.END_TAG){if(p.getName().equals("author"))author=false;if(p.getName().equals("entry")&&current!=null){current.image=imageSource(current.body);current.body=plain(current.body.length()>12000?current.body.substring(0,12000):current.body);out.add(current);current=null;if(out.size()>=limit)break;}}
 }return out;}
}
