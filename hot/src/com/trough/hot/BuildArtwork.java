package com.trough.hot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Native ink, shared word choices and learning feedback, carried across live code builds.
 * Actual work supplies material and movement; no synthetic tasks or progress values.
 */
final class BuildArtwork extends View implements AutoCloseable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final Paint cachedPaint=new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint bloomPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cachedBounds=new RectF();
    private final MuNcaField inkField;
    private final InkConstellation constellation;
    private int playPointer=-1,playRound;
    private long playInputAt;
    private final String[][] playPairs=CosmicWords.pairs();
    private long drawNanos,maxDrawNanos;
    private int drawCount,shaderWarm=Integer.MIN_VALUE;
    private String visibleWord="",responseInk="";
    private String lastPrompt="",promptLabel="",promptValue="";
    private long lastPromptAt;
    private int promptBursts,contentTop,contentBottom;
    private final float[] burstX=new float[5],burstY=new float[5],fieldBurstY=new float[5];
    private int wordPasses;
    private LetterValues.Result letterStudy;
    private String studyEquation="";
    private int[] studyValues=new int[0];
    private int largestValue=1;
    private int seed=0x19ad72f, fileCount, passing, failing, eventSize;
    private boolean active, disposed, scheduled;
    private long changedAt, touchedAt;
    private float signatureFrom=2.8f, signatureTarget=2.8f, dragX, dragY, downX, downY;
    private final float[] waveFrom=new float[6], waveTarget=new float[6];
    private final float[] rippleX=new float[8], rippleY=new float[8], glintX=new float[64], glintY=new float[64];
    private final long[] rippleAt=new long[8];
    private final boolean[] glintTaken=new boolean[64];
    private final TouchExpression touchExpression=new TouchExpression();
    private int rippleCursor, touchCount, collected, glintCount, textOrdinal, focusOrdinal;
    private String sourceText="", focusWord="", responseText="";
    private List<String> associations=new ArrayList<>();
    private final Typeface wordTypeface=Typeface.create("serif",Typeface.ITALIC);
    private final Typeface numeralTypeface=Typeface.create("sans-serif-thin",Typeface.NORMAL);
    private Shader atmosphere;
    private Map<String,Object> semanticState=new LinkedHashMap<String,Object>();
    private float semanticCalm=.5f,semanticWarmth=.5f,semanticDepth=.5f,semanticFocus=.5f,semanticEnergy=.5f;
    private float textScale=.5f,contrast=.5f,detail=.5f;
    private Map<String,Object> adaptiveState=new LinkedHashMap<String,Object>();
    private String typingText="",typingCaption="",typingGlyph="",learningCaption="";
    private int inputLeft,inputTop,inputRight,inputBottom,typingEdits,typingTotal;
    private long typingAt,learningAt,learnedUpdates;
    private final float[] typeX=new float[18],typeTone=new float[18];
    private final long[] typeAt=new long[18];
    private int typeCursor;
    private final float[] trailX=new float[32],trailY=new float[32];
    private final long[] trailAt=new long[32];
    private final boolean[] trailStart=new boolean[32];
    private int trailCursor;
    private View feedback;
    interface LearningListener { void adjust(String axis,float value,String source); }
    private LearningListener learningListener;
    interface WordListener { void choose(String word); void erase(); }
    private WordListener wordListener;
    private final String[] choiceWords=new String[3],choiceLabels=new String[3];
    private final RectF[] choiceHits={new RectF(),new RectF(),new RectF(),new RectF()};
    private int choiceCount,choiceGeneration,choicePointer=-1,choicePressed=-1,choicePressedGeneration;
    private int choiceLayoutGeneration=-1;
    private float choiceLayoutWidth=-1,choiceLayoutHeight=-1,choiceLayoutTop=-1,choiceLayoutScale=-1;
    private float choiceDownX,choiceDownY,choiceTextSize;
    private boolean choiceErase,choiceCancelled;
    private String choiceSelected="",learningFitted="";
    private float learningFitWidth=-1,learningFitSize=-1;
    private String learningFitSource="";
    private final int choiceSlop;
    private final Runnable frame=new Runnable() { public void run() {
        scheduled=false;
        if(animated()) { redraw(); schedule(); }
    }};

    BuildArtwork(Context context) {
        super(context);inkField=new MuNcaField(this);constellation=new InkConstellation(context);setMinimumHeight(dp(176));
        choiceSlop=ViewConfiguration.get(context).getScaledTouchSlop();
        bloomPaint.setShader(new RadialGradient(0,0,1,new int[]{0xffe6ffce,0xff8ae2cf,0x006ad8c7},new float[]{0,.2f,1},Shader.TileMode.CLAMP));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setClickable(true);
        setContentDescription("A living canvas of your words and work. Touch and drag to reshape it.");
    }

    /** Only chat material may carry words; the learned texture remains full bleed. */
    void contentBounds(int top,int bottom) {
        int nextTop=Math.max(0,top),nextBottom=Math.max(nextTop,bottom);
        if(contentTop==nextTop && contentBottom==nextBottom) return;
        contentTop=nextTop;contentBottom=nextBottom;visibleWord="";choiceLayoutGeneration=-1;redraw();
    }

    void inputBounds(int left,int top,int right,int bottom) {
        inputLeft=Math.max(0,left);inputTop=Math.max(0,top);inputRight=Math.max(inputLeft,right);inputBottom=Math.max(inputTop,bottom);
        choiceLayoutGeneration=-1;
    }

    void setLearningListener(LearningListener listener) { learningListener=listener; }
    void setWordListener(WordListener listener) { wordListener=listener; }

    boolean isPlaying() { return constellation.active(); }
    void setPlaying(boolean value) {
        if(disposed || value==constellation.active()) return;
        if(value) {
            choicePointer=-1;choicePressed=-1;choiceCancelled=true;
            String word=focusWord;int total=focusOrdinal;String[] matches=new String[0];
            boolean english=letterStudy!=null && "english-ordinal".equals(letterStudy.scheme);
            if(english && !associations.isEmpty() && word.length()<=24) matches=associations.toArray(new String[associations.size()]);
            else if(letterStudy==null || english && (word.length()>24 || associations.isEmpty())) {
                String[] pair=playPairs[playRound++%playPairs.length];
                word=pair[0];total=CosmicWords.ordinal(word);matches=new String[]{pair[1]};
            }
            Map<String,Object> values=Json.obj(adaptiveState.get("values"));
            constellation.material(word,total,matches,values);
        }
        constellation.active(value);redraw();schedule();
    }
    void playAt(float x,float y,boolean down) {
        if(!constellation.active() || disposed) return;
        long now=SystemClock.elapsedRealtime();
        if(!down && now-playInputAt<24) return;
        playInputAt=now;constellation.touch(x,y,down);
        inkField.perturb(x/Math.max(1,getWidth()),y/Math.max(1,getHeight()),down?.24f:.08f);
        redraw();schedule();
    }

    /** Real decoder alternatives share the existing ink strip; no keyboard row is added. */
    void wordChoices(String[] words,String selected,boolean canErase) {
        if(disposed) return;
        int count=0;
        if(words!=null) for(int i=0;i<words.length && count<choiceWords.length;i++) {
            String word=words[i];
            if(word==null || word.isEmpty() || word.length()>128) continue;
            boolean duplicate=false;
            for(int j=0;j<count;j++) if(word.equals(choiceWords[j])) { duplicate=true;break; }
            if(!duplicate) choiceWords[count++]=word;
        }
        for(int i=count;i<choiceWords.length;i++) { choiceWords[i]=null;choiceLabels[i]=null; }
        choiceCount=count;choiceSelected=selected==null?"":selected;choiceErase=count>0 && canErase;
        choiceGeneration++;choiceLayoutGeneration=-1;choiceCancelled=true;
        if(feedback!=null) feedback.setContentDescription(count==0?"":
            "Swipe word alternatives. Tap a word to correct and teach; cross deletes the whole word.");
        redraw();
    }

    /** Acknowledges an actual persisted ranker update; this does not label visual preferences. */
    void wordLearned(String word,long version) {
        if(disposed || word==null || word.isEmpty()) return;
        learningCaption="Learned “"+(word.length()>32?word.substring(0,29)+"…":word)+"” · v"+version;
        learningAt=SystemClock.elapsedRealtime();learningFitWidth=-1;redraw();schedule();
    }

    /** A transparent native layer keeps direct feedback above the conversation's text surfaces. */
    View feedbackView() {
        if(feedback==null) {
            feedback=new View(getContext()) {
                @Override protected void onDraw(Canvas canvas) {
                    if(disposed) return;
                    float top=chatTop(),height=chatHeight();
                    if(height<=0) return;
                    int save=canvas.save();canvas.clipRect(0,top,getWidth(),top+height);
                    if(constellation.active()) {
                        paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(0xff071b18);paint.setAlpha(240);
                        canvas.drawRect(0,top,getWidth(),top+height,paint);
                        constellation.draw(canvas,0,top,getWidth(),height,SystemClock.elapsedRealtime());
                    } else {
                        drawTrail(canvas,SystemClock.elapsedRealtime());
                        canvas.translate(0,top);
                        if(!new java.io.File(getContext().getFilesDir(),"home/.trough/visual-only").isFile())drawTyping(canvas,getWidth(),height,SystemClock.elapsedRealtime());
                    }
                    canvas.restoreToCount(save);
                }
                @Override public boolean onTouchEvent(MotionEvent event) {
                    if(playPointer>=0 || constellation.active()) return playTouch(event);
                    return wordTouch(event);
                }
            };
            feedback.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            feedback.setFocusable(false);feedback.setClickable(false);
        }
        return feedback;
    }
    private void redraw() { invalidate();if(feedback!=null) feedback.invalidate(); }

    private boolean playTouch(MotionEvent event) {
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            if(!constellation.active() || event.getY()<chatTop() || event.getY()>chatTop()+chatHeight()) return false;
            playPointer=event.getPointerId(event.getActionIndex());
            playAt(event.getX(),event.getY(),true);return true;
        }
        if(playPointer<0) return false;
        int at=event.findPointerIndex(playPointer);
        if(at>=0 && constellation.active()) playAt(event.getX(at),event.getY(at),false);
        if(action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_UP
            || action==MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex())==playPointer) playPointer=-1;
        return true;
    }

    /** Observe a gesture without consuming it or treating ordinary scrolling as training. */
    void pointer(float x,float y,boolean start) {
        if(disposed) return;
        long now=SystemClock.elapsedRealtime();
        int previous=Math.floorMod(trailCursor-1,trailAt.length);
        if(!start && trailCursor>0 && now-trailAt[previous]<24) return;
        int index=trailCursor++%trailAt.length;
        trailX[index]=x;trailY[index]=y;trailAt[index]=now;trailStart[index]=start;
        if(start) touchCount++;
        touch(x,y,start);
    }

    private void drawTrail(Canvas canvas,long now) {
        paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(blendColor(0xff8bf5df,0xffffd49a,semanticWarmth));
        int first=Math.max(0,trailCursor-trailAt.length);
        for(int n=first;n<trailCursor;n++) {
            int i=n%trailAt.length;long age=now-trailAt[i];
            if(age<0 || age>=850) continue;
            float fade=1-age/850f;
            paint.setAlpha(Math.round(170*fade));paint.setStrokeWidth(dp(1)*(1+fade*(1+semanticEnergy)));
            if(n>first && !trailStart[i]) {
                int previous=(n-1)%trailAt.length;
                if(now-trailAt[previous]<850)
                    canvas.drawLine(trailX[previous],trailY[previous],trailX[i],trailY[i],paint);
            }
            if(n==trailCursor-1) canvas.drawCircle(trailX[i],trailY[i],dp(4)*(1+fade),paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);
    }

    /** Immediate native ink feedback; semantic inference is never on the typing path. */
    void typing(String text) {
        if(disposed) return;
        String next=bounded(text,4096);
        if(next.equals(typingText)) return;
        typingText=next;typingEdits++;
        long now=SystemClock.elapsedRealtime();typingAt=now;
        if(next.isEmpty()) { typingCaption="";typingGlyph="";redraw();schedule();return; }
        int end=next.length(),start=end;
        while(start>0 && letter(next.charAt(start-1)) && end-start<256) start--;
        String word=next.length()<=256?next:next.substring(start,end);
        LetterValues.Result study=LetterValues.analyse(word);
        typingTotal=study==null?0:study.total;
        typingCaption=next.codePointCount(0,next.length())+" characters"+(study==null?"":"  ·  "+("english-ordinal".equals(study.scheme)?"A1Z26 ":"abjad ")+study.total);
        int cp=next.codePointBefore(end);typingGlyph=new String(Character.toChars(cp));
        float x=.09f+Math.floorMod(next.length()-1,36)/35f*.82f;
        int ring=typeCursor++%typeAt.length;typeX[ring]=x;typeTone[ring]=(cp%29)/28f;typeAt[ring]=now;
        float top=chatTop(),height=chatHeight();
        float y=Math.max(top,top+height-dp(16))/Math.max(1,getHeight());
        inkField.perturb(x,y,.07f+semanticEnergy*.05f);
        redraw();schedule();
    }

    /** Immediate acknowledgement of a durably stored prompt, independent of inference. */
    void promptSent(String text) {
        if(disposed || text==null || text.trim().isEmpty()) return;
        lastPrompt=bounded(text,4096);lastPromptAt=SystemClock.elapsedRealtime();promptBursts++;
        updateWords(Json.map("word",lastPrompt,"prompt",lastPrompt,"response",responseText));
        String label=lastPrompt.replace('\n',' ').trim();
        promptLabel=label.length()>46?label.substring(0,43)+"…":label;
        promptValue=letterStudy==null?"":String.valueOf(letterStudy.total);
        int material=lastPrompt.hashCode()^(focusOrdinal*0x45d9f3b);
        float top=chatTop(),height=chatHeight(),fullHeight=Math.max(1,getHeight());
        for(int i=0;i<burstX.length;i++) {
            material^=material>>>16;material*=0x45d9f3b;material^=material>>>16;
            burstX[i]=.13f+(material&65535)/65535f*.74f;
            burstY[i]=.15f+((material>>>16)&65535)/65535f*.68f;
            fieldBurstY[i]=(top+burstY[i]*height)/fullHeight;
            int ring=rippleCursor++%rippleAt.length;
            rippleX[ring]=burstX[i];rippleY[ring]=fieldBurstY[i];rippleAt[ring]=lastPromptAt+i*65;
            material+=0x19ad72f+i;
        }
        inkField.burst(burstX,fieldBurstY,burstX.length,.78f);
        changedAt=lastPromptAt;redraw();schedule();
    }
    private float chatTop() { return Math.min(getHeight(),Math.max(0,contentTop)); }
    private float chatHeight() {
        float bottom=contentBottom>contentTop?Math.min(getHeight(),contentBottom):getHeight();
        return Math.max(0,bottom-chatTop());
    }

    void update(Map<String,Object> status) {
        Map<String,Object> semantic=Json.obj(status.get("steering"));
        Map<String,Object> values=semantic==null?null:Json.obj(semantic.get("values"));
        if(semantic!=null) semanticState=new LinkedHashMap<String,Object>(semantic);
        Map<String,Object> adaptive=Json.obj(status.get("adaptive"));
        Map<String,Object> learned=adaptive==null?null:Json.obj(adaptive.get("values"));
        if(learned!=null) {
            boolean sawAdaptive=!adaptiveState.isEmpty();
            adaptiveState=new LinkedHashMap<String,Object>(adaptive);values=learned;
            long updates=longValue(adaptive,"updates",longValue(adaptive,"learning_updates",learnedUpdates));
            if(updates>learnedUpdates && sawAdaptive) {
                String feedback=Json.str(adaptive,"last_feedback");
                String source=Json.str(adaptive,"last_source");
                learningCaption=learningLabel(feedback)
                    +(source!=null&&(source.startsWith("cloud")||source.startsWith("Codex"))?" · cloud":"")
                    +" · "+updates+" updates";
                learningAt=SystemClock.elapsedRealtime();
            }
            learnedUpdates=updates;
        }
        if(values!=null) {
            semanticCalm=control(values,"calm"); semanticWarmth=control(values,"warmth"); semanticDepth=control(values,"depth");
            semanticFocus=control(values,"focus");semanticEnergy=control(values,"energy");
            textScale=control(values,"text_scale");contrast=control(values,"contrast");detail=control(values,"detail");
        }
        Map<String,Object> language=Json.obj(status.get("dogshit"));
        Map<String,Object> controls=language==null?null:Json.obj(language.get("values"));
        if(Boolean.TRUE.equals(language==null?null:language.get("ready")) && controls!=null) {
            semanticEnergy=control(controls,"motion");semanticFocus=control(controls,"focus");semanticWarmth=control(controls,"warmth");
        }
        updateWords(status);
        String state=Json.str(status,"state");
        active=state==null || "running".equals(state) || "working".equals(state) || "waiting".equals(state);
        fileCount=size(status,"files"); passing=0; failing=0;
        List<Object> checks=Json.arr(status.get("checks"));
        if(checks!=null) for(Object value:checks) {
            Map<String,Object> check=Json.obj(value); String result=Json.str(check,"state");
            if("failed".equals(result)||"error".equals(result)||Boolean.FALSE.equals(check==null?null:check.get("ok"))) failing++;
            else if("passed".equals(result)||"complete".equals(result)||"done".equals(result)||Boolean.TRUE.equals(check==null?null:check.get("ok"))) passing++;
        }
        String error=Json.str(status,"error");
        if("failed".equals(state)||"error".equals(state)||error!=null&&!error.isEmpty()) failing++;
        String output=Json.str(status,"log"), code=Json.str(status,"code");
        eventSize=(output==null?0:output.length())+(code==null?0:code.length())+sourceText.length()+responseText.length();

        // Visible state steers one persistent ink field; text never resets the learned cells.
        int next=focusWord.hashCode(); next=31*next+focusOrdinal;
        next=31*next+fileCount; next=31*next+passing; next=31*next+failing;
        next=31*next+Math.min(44,eventSize/512);
        if(values!=null) {
            next=31*next+Math.round(semanticCalm*20);next=31*next+Math.round(semanticWarmth*20);
            next=31*next+Math.round(semanticDepth*20);next=31*next+Math.round(semanticFocus*20);
            next=31*next+Math.round(semanticEnergy*20);next=31*next+Math.round(textScale*20);
            next=31*next+Math.round(contrast*20);next=31*next+Math.round(detail*20);
        }
        if(next!=seed) {
            long now=SystemClock.elapsedRealtime();
            signatureFrom=phase(now);
            float destination=(next&65535)/65535f*6.283185f;
            if(values!=null) destination=(semanticDepth*.9f+semanticEnergy*.65f+semanticWarmth*.4f)*3.141593f;
            while(destination-signatureFrom>Math.PI) destination-=6.283185f;
            while(signatureFrom-destination>Math.PI) destination+=6.283185f;
            signatureTarget=destination;
            float[] features=values==null?features(next):new float[]{axis(semanticDepth),axis(semanticEnergy),
                axis(semanticCalm),axis(semanticFocus),axis(semanticWarmth),axis((semanticDepth+semanticCalm)*.5f)};
            for(int i=0;i<waveFrom.length;i++) {
                waveFrom[i]=wave(i,now);
                waveTarget[i]=waveTarget[i]*.68f+features[i]*.32f;
            }
            seed=next; changedAt=now;
            int ordinal=focusOrdinal>0?focusOrdinal:textOrdinal;
            inkField.perturb(.14f+Math.floorMod(ordinal,79)/110f,
                .18f+Math.floorMod(ordinal/7,67)/110f,.08f+semanticEnergy*.08f);
            for(int i=0;i<glintTaken.length;i++) glintTaken[i]=false;
        }
        inkField.controls(semanticWarmth,semanticFocus,semanticDepth,semanticEnergy,semanticCalm,contrast,detail);
        constellation.controls(semanticCalm,semanticFocus,semanticDepth,semanticEnergy,semanticWarmth,
            contrast,detail,textScale,longValue(adaptiveState,"weights_version",0));
        rebuildShaders(); redraw(); schedule();
        String title=Json.str(status,"title"), message=Json.str(status,"message");
        String description=(title==null?"Your words and work":title)+(message==null?"":": "+message)
            +(letterStudy==null?"":". "+letterStudy.explain()+(associations.isEmpty()?"":"; "+associations.get(0)+" shares its value"))
            +". Touch and drag to reshape the same canvas.";
        if(!description.contentEquals(getContentDescription()==null?"":getContentDescription())) setContentDescription(description);
    }

    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight) {
        visibleWord="";shaderWarm=Integer.MIN_VALUE;rebuildShaders();
    }
    private void rebuildShaders() {
        float width=Math.max(1,getWidth()), height=Math.max(1,getHeight());
        int warm=Math.round((semanticWarmth*.8f+failing/(float)(passing+failing+2)*.2f)*16);
        if(atmosphere!=null && shaderWarm==warm) return;
        shaderWarm=warm;
        atmosphere=new RadialGradient(width*.58f,height*.43f,width*.67f,
            new int[]{blendColor(0xff14352f,0xff392d23,warm/16f),0xff101e22,0xff101714},
            new float[]{0,.55f,1},Shader.TileMode.CLAMP);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if(disposed || getWidth()<=0 || getHeight()<=0) return;
        long started=System.nanoTime(),now=SystemClock.elapsedRealtime();
        float width=getWidth(),height=getHeight();
        paint.setStyle(Paint.Style.FILL);paint.setShader(atmosphere);paint.setAlpha(255);
        canvas.drawRect(0,0,width,height,paint);paint.setShader(null);
        cachedBounds.set(0,0,width,height);
        cachedPaint.setAlpha(Math.round(104+detail*64+contrast*36));
        inkField.draw(canvas,cachedBounds,cachedPaint);
        float top=chatTop(),chatHeight=chatHeight();
        if(chatHeight>0) {
            int save=canvas.save();canvas.clipRect(0,top,width,top+chatHeight);
            drawRipples(canvas,width,height,now);
            canvas.translate(0,top);
            drawGlints(canvas,width,chatHeight,phase(now));
            if(!new java.io.File(getContext().getFilesDir(),"home/.trough/visual-only").isFile())drawPromptBloom(canvas,width,chatHeight,now);
            canvas.restoreToCount(save);
        }
        if(new java.io.File(getContext().getFilesDir(),"home/.trough/visual-only").isFile()) {
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));
            for(int band=0;band<4;band++){
                path.reset();
                for(int i=0;i<=64;i++){float x=width*i/64f;float y=height*(.25f+band*.11f)+(float)Math.sin(i*.075+now*.00022+band*.8)*height*.09f+(float)Math.sin(i*.16-now*.00015)*height*.035f;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}
                paint.setColor(new int[]{0xff8bdcc4,0xff9aafed,0xffe1b8ce,0xffd7c594}[band]);paint.setAlpha(140);canvas.drawPath(path,paint);
            }
            paint.setAlpha(255);paint.setStyle(Paint.Style.FILL);
        }
        drawCount++;long elapsed=System.nanoTime()-started;
        drawNanos+=elapsed;maxDrawNanos=Math.max(maxDrawNanos,elapsed);
    }
    private float unit(int salt) {
        // Keep spatial grain continuous; incoming words move the surrounding field instead of teleporting it.
        int value=0x19ad72f^(salt*0x45d9f3b); value^=value>>>16; value*=0x45d9f3b; value^=value>>>16;
        return (value&0x00ffffff)/16777215f;
    }
    private float phase(long now) { float t=blend(now); return signatureFrom+(signatureTarget-signatureFrom)*t; }
    private float wave(int i,long now) { float t=blend(now); return waveFrom[i]+(waveTarget[i]-waveFrom[i])*t; }
    private float blend(long now) { float t=Math.max(0,Math.min(1,(now-changedAt)/1800f)); return t*t*(3-2*t); }
    private static float[] features(int material) {
        float[] values=new float[6];
        for(int i=0;i<values.length;i++) {
            int mixed=material^(0x45d9f3b*(i+1)); mixed^=mixed>>>16; mixed*=0x45d9f3b; mixed^=mixed>>>16;
            values[i]=(mixed&65535)/32767.5f-1;
        }
        return values;
    }
    private static float control(Map<String,Object> values,String name) {
        Object value=values.get(name); float n=value instanceof Number?((Number)value).floatValue():.5f;
        return Float.isNaN(n)||Float.isInfinite(n)?.5f:Math.max(0,Math.min(1,n));
    }
    private static float axis(float value) { return Math.max(-1,Math.min(1,(value-.5f)*4)); }
    private static String learningLabel(String feedback) {
        if(feedback==null || feedback.isEmpty()) return "Preference learned";
        int split=feedback.indexOf('=');
        if(split<1) return feedback.length()>45?feedback.substring(0,42)+"…":feedback;
        float target;
        try { target=Float.parseFloat(feedback.substring(split+1)); }
        catch(NumberFormatException error) { return "Preference learned"; }
        String axis=feedback.substring(0,split);
        boolean more=target>=.5f;
        if("energy".equals(axis)) return more?"Learned faster motion":"Learned slower motion";
        if("warmth".equals(axis)) return more?"Learned warmer colour":"Learned cooler colour";
        if("text_scale".equals(axis)) return more?"Learned larger lettering":"Learned smaller lettering";
        if("contrast".equals(axis)) return more?"Learned stronger contrast":"Learned softer contrast";
        if("detail".equals(axis)) return more?"Learned richer detail":"Learned quieter detail";
        if("calm".equals(axis)) return more?"Learned calmer movement":"Learned livelier movement";
        if("depth".equals(axis)) return more?"Learned deeper layers":"Learned flatter layers";
        return "Learned "+axis;
    }
    private static int blendColor(int a,int b,float t) {
        return 0xff000000|((int)(((a>>16)&255)*(1-t)+((b>>16)&255)*t)<<16)
            |((int)(((a>>8)&255)*(1-t)+((b>>8)&255)*t)<<8)|(int)((a&255)*(1-t)+(b&255)*t);
    }
    private void updateWords(Map<String,Object> status) {
        String draft=bounded(Json.str(status,"word"),4096);
        String response=bounded(Json.str(status,"response"),4096);
        if(!response.equals(responseText)) {
            responseText=response;
            responseInk=responseText.replace('\n',' ').trim();
            if(responseInk.length()>60) responseInk=responseInk.substring(responseInk.length()-60);
        }
        String source=bounded(Json.str(status,"source"),4096);
        String prompt=bounded(Json.str(status,"prompt"),4096);
        if(!prompt.trim().isEmpty()) lastPrompt=prompt;
        String selected=!draft.trim().isEmpty()?draft:!lastPrompt.trim().isEmpty()?lastPrompt:!responseText.trim().isEmpty()?responseText:source;
        if(selected.equals(sourceText)) return;
        sourceText=selected; wordPasses++; visibleWord="";
        textOrdinal=CosmicWords.ordinal(sourceText); focusWord=""; associations=new ArrayList<>();
        String trimmed=sourceText.trim();
        if(trimmed.length()<=256 && LetterValues.analyse(trimmed)!=null) focusWord=trimmed;
        else {
            int end=sourceText.length(), examined=0; String last="";
            while(end>0 && examined<24) {
                while(end>0 && !letter(sourceText.charAt(end-1))) end--;
                int start=end; while(start>0 && letter(sourceText.charAt(start-1))) start--;
                if(start==end) break;
                String word=sourceText.substring(start,end); end=start; examined++;
                if(word.length()>256) continue;
                if(last.isEmpty()) last=word;
                if(!CosmicWords.matches(word).isEmpty()) { focusWord=word; break; }
            }
            if(focusWord.isEmpty()) focusWord=last;
        }
        updateStudy();
        if(letterStudy!=null && "english-ordinal".equals(letterStudy.scheme)) associations=new ArrayList<>(CosmicWords.matches(focusWord));
    }
    private void updateStudy() {
        letterStudy=LetterValues.analyse(focusWord);
        focusOrdinal=letterStudy==null?0:letterStudy.total;
        studyEquation=letterStudy==null?"":letterStudy.equation();
        if(studyEquation.length()>72) studyEquation=studyEquation.substring(0,69)+"…";
        studyValues=letterStudy==null?new int[0]:letterStudy.values();
        largestValue=1; for(int value:studyValues) largestValue=Math.max(largestValue,value);
    }
    private void drawWords(Canvas canvas,float width,float height,float signature) {
        if(focusWord.isEmpty() || letterStudy==null) return;
        paint.setShader(null); paint.setStyle(Paint.Style.FILL);
        float glyphScale=.85f+textScale*.3f;
        paint.setTypeface(numeralTypeface); paint.setTextSize(Math.min(height*.43f,width*.2f)*glyphScale);
        paint.setColor(0xffc4d9b1); paint.setAlpha(Math.round(18+contrast*24));
        canvas.drawText(String.valueOf(focusOrdinal),width*.11f,height*.72f,paint);
        paint.setTypeface(wordTypeface); paint.setTextSize(Math.min(dp(23),width*.065f)*glyphScale);
        paint.setColor(blendColor(0xffcef0e8,0xfff4dfb4,semanticWarmth)); paint.setAlpha(Math.round(130+contrast*90));
        float x=width*.105f+dragX*width*.035f, y=height*(.27f+dragY*.035f);
        if(visibleWord.isEmpty()) {
            visibleWord=focusWord.length()>24?focusWord.substring(0,24)+"…":focusWord;
            while(paint.measureText(visibleWord)>width*.62f && visibleWord.length()>2) visibleWord=visibleWord.substring(0,visibleWord.length()-2)+"…";
        }
        canvas.drawText(visibleWord,x,y,paint);
        paint.setTypeface(Typeface.MONOSPACE); paint.setTextSize(dp(8)*glyphScale); paint.setAlpha(Math.round(80+contrast*76));
        canvas.drawText(letterStudy.label,x,y+dp(18),paint);
        canvas.drawText(studyEquation,x,y+dp(31),paint);
        int count=Math.min(Math.round(12+detail*20),studyValues.length);
        float barWidth=width*.64f/Math.max(1,count),base=y+dp(54);
        paint.setColor(0xffa7d8c0); paint.setAlpha(72);
        for(int i=0;i<count;i++) {
            float barHeight=dp(17)*studyValues[i]/largestValue;
            canvas.drawRect(x+i*barWidth,base-barHeight,x+(i+.64f)*barWidth,base,paint);
        }
        paint.setTypeface(wordTypeface);
        paint.setTextSize(Math.min(dp(15),width*.043f)*glyphScale); paint.setAlpha(Math.round(90+contrast*80)); paint.setColor(0xffd5c9b4);
        if(!associations.isEmpty()) {
            String paired=associations.get(Math.floorMod(collected/3,Math.min(5,associations.size())));
            float pairedX=Math.max(width*.39f,width*.86f-paint.measureText(paired));
            canvas.drawText(paired,pairedX,height*(.78f+.025f*(float)Math.sin(signature)),paint);
        }
        // A few actual response words become quiet ink, not an independent answer panel.
        if(!responseText.isEmpty()) {

            paint.setTypeface(Typeface.MONOSPACE); paint.setTextSize(dp(8)); paint.setAlpha(30);
            canvas.save(); canvas.rotate(-8,width*.5f,height*.5f);
            canvas.drawText(responseInk,width*.26f,height*.39f,paint); canvas.restore();
        }
        paint.setTypeface(Typeface.DEFAULT); paint.setAlpha(255);
    }
    private void drawTyping(Canvas canvas,float width,float height,long now) {
        float baseline=Math.max(dp(20),height-dp(10));
        float left=Math.max(dp(16),inputLeft),right=inputRight>inputLeft?Math.min(width-dp(16),inputRight):width-dp(16);
        if(right-left<dp(48)) { left=dp(16);right=width-dp(16); }
        if(choiceCount>0) {
            drawWordChoices(canvas,width,height,now,left,right);
            paint.setStyle(Paint.Style.FILL);paint.setTypeface(Typeface.DEFAULT);paint.setAlpha(255);
            return;
        }
        float age=typingAt==0?10000:Math.max(0,now-typingAt);
        float pulse=Math.max(0,1-age/1200f);
        if(!typingCaption.isEmpty() || pulse>0) {
            int ink=blendColor(0xff77e7d2,0xffffcf84,semanticWarmth);
            paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(0xff0c1715);paint.setAlpha(112);
            canvas.drawRect(0,baseline-dp(34),width,height,paint);
            paint.setColor(ink);paint.setAlpha(Math.round(60+contrast*60));paint.setStrokeWidth(dp(1));
            canvas.drawLine(left,baseline,right,baseline,paint);
            for(int i=0;i<typeAt.length;i++) {
                long dotAge=now-typeAt[i];if(typeAt[i]==0 || dotAge<0 || dotAge>1400) continue;
                float fade=1-dotAge/1400f,x=left+(right-left)*typeX[i];
                float radius=dp(1)*(2.4f+semanticEnergy*3.2f+fade*3);
                float y=baseline-(float)Math.sin((1-fade)*3.141593f)*dp(7)*(typeTone[i]+.3f);
                paint.setAlpha((int)(fade*(160+contrast*80)));
                canvas.drawCircle(x,y,radius,paint);
            }
            paint.setTypeface(Typeface.MONOSPACE);paint.setTextSize(dp(9)*(.9f+textScale*.2f));
            paint.setAlpha(Math.round(160+contrast*80));
            canvas.drawText(typingCaption,left,baseline-dp(15),paint);
            if(!typingGlyph.isEmpty() && pulse>0) {
                paint.setTypeface(wordTypeface);paint.setTextSize(dp(21)*(.85f+textScale*.3f));paint.setAlpha((int)(255*pulse));
                float x=left+(right-left)*(.09f+Math.floorMod(Math.max(0,typingText.length()-1),36)/35f*.82f);
                canvas.drawText(typingGlyph,x,baseline-dp(13)-dp(8)*(1-pulse),paint);
            }
        }
        long learningAge=now-learningAt;
        if(learningAt>0 && learningAge>=0 && learningAge<5000) {
            float fade=Math.min(1,(5000-learningAge)/650f);
            paint.setColor(0xffd8ecc9);paint.setTypeface(Typeface.MONOSPACE);paint.setTextSize(dp(9)*(.9f+textScale*.2f));
            paint.setAlpha((int)(230*fade));
            canvas.drawText(fitLearning(right-left),left,baseline-dp(29),paint);
        }
        paint.setStyle(Paint.Style.FILL);paint.setTypeface(Typeface.DEFAULT);paint.setAlpha(255);
    }

    private String fitLearning(float width) {
        float size=paint.getTextSize();
        if(width==learningFitWidth && size==learningFitSize && learningCaption.equals(learningFitSource)) return learningFitted;
        learningFitWidth=width;learningFitSize=size;learningFitSource=learningCaption;
        learningFitted=learningCaption.length()>70?learningCaption.substring(0,67)+"…":learningCaption;
        while(learningFitted.length()>4 && paint.measureText(learningFitted)>width)
            learningFitted=learningFitted.substring(0,learningFitted.length()-2)+"…";
        return learningFitted;
    }

    /** Hit bounds are cached in root coordinates; layout and model changes rebuild labels once. */
    private void layoutChoices(float width,float height) {
        float top=chatTop();
        if(choiceLayoutGeneration==choiceGeneration && choiceLayoutWidth==width && choiceLayoutHeight==height
            && choiceLayoutTop==top && choiceLayoutScale==textScale) return;
        choiceLayoutGeneration=choiceGeneration;choiceLayoutWidth=width;choiceLayoutHeight=height;
        choiceLayoutTop=top;choiceLayoutScale=textScale;
        for(int i=0;i<choiceHits.length;i++) choiceHits[i].setEmpty();
        if(choiceCount==0 || height<dp(36) || width<dp(80)) return;
        float left=Math.max(dp(12),inputLeft),right=inputRight>inputLeft?Math.min(width-dp(12),inputRight):width-dp(12);
        if(right-left<dp(80)) { left=dp(12);right=width-dp(12); }
        float bottom=top+height-dp(3),hitTop=bottom-dp(36),gap=dp(3);
        float wordRight=choiceErase?right-dp(36)-gap:right;
        float cell=(wordRight-left-gap*(choiceCount-1))/Math.max(1,choiceCount);
        choiceTextSize=dp(13)*(.92f+textScale*.16f);
        paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(choiceTextSize);
        for(int i=0;i<choiceCount;i++) {
            float x=left+i*(cell+gap);
            choiceHits[i].set(x,hitTop,x+cell,bottom);
            String label=choiceWords[i];float bound=Math.max(dp(8),cell-dp(12));
            if(paint.measureText(label)>bound) {
                while(label.length()>1 && paint.measureText(label+"…")>bound) label=label.substring(0,label.length()-1);
                label+="…";
            }
            choiceLabels[i]=label;
        }
        if(choiceErase) choiceHits[3].set(right-dp(36),hitTop,right,bottom);
    }

    private void drawWordChoices(Canvas canvas,float width,float height,long now,float left,float right) {
        layoutChoices(width,height);
        float rootTop=chatTop();
        paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(0xff0c1715);paint.setAlpha(170);
        canvas.drawRect(0,Math.max(0,height-dp(48)),width,height,paint);
        int accent=blendColor(0xff8cf0da,0xffffd497,semanticWarmth);
        paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(choiceTextSize);
        for(int i=0;i<choiceCount;i++) {
            RectF hit=choiceHits[i];if(hit.isEmpty()) continue;
            boolean selected=choiceWords[i].equals(choiceSelected);
            paint.setColor(selected?accent:0xff51746b);
            paint.setAlpha(choicePressed==i && !choiceCancelled?135:selected?68:38);
            canvas.drawRoundRect(hit.left,hit.top-rootTop,hit.right,hit.bottom-rootTop,dp(8),dp(8),paint);
            paint.setColor(selected?accent:0xffe4f0e8);paint.setAlpha(255);
            String label=choiceLabels[i];
            canvas.drawText(label,hit.centerX()-paint.measureText(label)*.5f,
                hit.centerY()-rootTop-(paint.ascent()+paint.descent())*.5f,paint);
        }
        if(choiceErase && !choiceHits[3].isEmpty()) {
            RectF hit=choiceHits[3];
            paint.setColor(0xffe3bcaa);paint.setAlpha(choicePressed==3 && !choiceCancelled?110:42);
            canvas.drawRoundRect(hit.left,hit.top-rootTop,hit.right,hit.bottom-rootTop,dp(8),dp(8),paint);
            paint.setAlpha(255);paint.setTextSize(dp(21));
            canvas.drawText("×",hit.centerX()-paint.measureText("×")*.5f,
                hit.centerY()-rootTop-(paint.ascent()+paint.descent())*.5f,paint);
        }
        long age=now-learningAt;
        if(learningAt>0 && age>=0 && age<5000) {
            paint.setTypeface(Typeface.MONOSPACE);paint.setTextSize(dp(8));paint.setColor(0xffd8ecc9);
            paint.setAlpha((int)(230*Math.min(1,(5000-age)/650f)));
            canvas.drawText(fitLearning(right-left),left,Math.max(dp(8),height-dp(41)),paint);
        }
    }

    private int choiceAt(float x,float y) {
        for(int i=0;i<choiceCount;i++) if(choiceHits[i].contains(x,y)) return i;
        return choiceErase && choiceHits[3].contains(x,y)?3:-1;
    }

    /** The overlay owns only presses on its word targets; conversation and keys pass through. */
    private boolean wordTouch(MotionEvent event) {
        if(disposed) return false;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            choicePointer=-1;choicePressed=-1;
            if(wordListener==null || choiceCount==0 || event.getPointerCount()!=1) return false;
            layoutChoices(feedback==null?getWidth():feedback.getWidth(),chatHeight());
            int hit=choiceAt(event.getX(),event.getY());if(hit<0) return false;
            choicePointer=event.getPointerId(0);choicePressed=hit;choicePressedGeneration=choiceGeneration;
            choiceDownX=event.getX();choiceDownY=event.getY();choiceCancelled=false;
            redraw();return true;
        }
        if(choicePointer<0) return false;
        int index=event.findPointerIndex(choicePointer);
        if(action==MotionEvent.ACTION_POINTER_DOWN || index<0) choiceCancelled=true;
        if(index>=0 && (Math.abs(event.getX(index)-choiceDownX)>choiceSlop
            || Math.abs(event.getY(index)-choiceDownY)>choiceSlop)) choiceCancelled=true;
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
            int chosen=choicePressed;boolean accept=action==MotionEvent.ACTION_UP && !choiceCancelled && index>=0
                && choicePressedGeneration==choiceGeneration && choiceAt(event.getX(index),event.getY(index))==chosen;
            choicePointer=-1;choicePressed=-1;choiceCancelled=false;redraw();
            if(accept && wordListener!=null) {
                if(chosen==3) wordListener.erase();
                else if(chosen>=0 && chosen<choiceCount) wordListener.choose(choiceWords[chosen]);
            }
        }
        return true;
    }
    private void drawPromptBloom(Canvas canvas,float width,float height,long now) {
        long age=now-lastPromptAt;
        if(lastPromptAt==0 || age<0 || age>2800) return;
        float fade=1-age/2800f;
        for(int i=0;i<burstX.length;i++) {
            float t=Math.max(0,Math.min(1,(age-i*65)/1800f));
            if(age<i*65) continue;
            float radius=dp(18)+(float)Math.sqrt(t)*Math.min(width,height)*.29f;
            bloomPaint.setAlpha((int)(100*fade*(1-t*.65f)));
            int save=canvas.save();canvas.translate(burstX[i]*width,burstY[i]*height);canvas.scale(radius,radius*.73f);
            canvas.drawCircle(0,0,1,bloomPaint);canvas.restoreToCount(save);
        }
        paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setTypeface(wordTypeface);
        paint.setTextSize(Math.min(dp(20),width*.054f));
        String label=promptLabel+(promptValue.isEmpty()?"":" → "+promptValue);
        while(paint.measureText(label)>width*.84f && label.length()>8) {
            int arrow=label.indexOf(" → ");
            if(arrow>5) label=label.substring(0,arrow-2)+"…"+label.substring(arrow);
            else label=label.substring(0,label.length()-2)+"…";
        }
        float x=(width-paint.measureText(label))*.5f,y=Math.max(dp(32),height*.39f)-dp(7)*(1-fade);
        paint.setColor(0xff071b18);paint.setAlpha((int)(220*fade));
        canvas.drawText(label,x+dp(1),y+dp(1),paint);
        paint.setColor(0xffe1ffe1);paint.setAlpha((int)(255*Math.min(1,fade*1.6f)));
        canvas.drawText(label,x,y,paint);
        paint.setTypeface(Typeface.DEFAULT);paint.setAlpha(255);
    }
    private void drawRipples(Canvas canvas,float width,float height,long now) {
        paint.setShader(null); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)*.65f);
        for(int i=0;i<rippleAt.length;i++) {
            long age=now-rippleAt[i]; if(rippleAt[i]==0 || age<0 || age>1500) continue;
            float t=age/1500f, radius=dp(5)+(float)Math.sqrt(t)*Math.min(width,height)*.31f;
            paint.setColor(0xffc4e8cc); paint.setAlpha((int)((1-t)*93));
            path.reset();
            for(int k=0;k<=46;k++) {
                float a=k/46f*6.283185f, r=radius*(1+.05f*(float)Math.sin(a*5+i));
                float x=rippleX[i]*width+(float)Math.cos(a)*r, y=rippleY[i]*height+(float)Math.sin(a)*r*.67f;
                if(k==0) path.moveTo(x,y); else path.lineTo(x,y);
            }
            canvas.drawPath(path,paint);
        }
        paint.setStyle(Paint.Style.FILL); paint.setAlpha(255);
    }
    private void drawGlints(Canvas canvas,float width,float height,float signature) {
        glintCount=Math.round(5+detail*12)+Math.min(7,passing*2+eventSize/1200);
        paint.setStyle(Paint.Style.FILL);paint.setShader(null);paint.setColor(0xffc1dec4);
        for(int i=0;i<glintCount;i++) {
            float t=unit(i+1000),x=width*(.12f+t*.76f);
            float y=height*(.5f+.21f*(float)Math.sin(t*5+signature*.7f)+.1f*(unit(i+1100)-.5f));
            glintX[i]=x;glintY[i]=y;
            if(glintTaken[i]) continue;
            paint.setAlpha(30+(int)(unit(i+1200)*35));
            canvas.drawRect(x,y,x+dp(1)*(2+unit(i+1300)*3),y+dp(1)*.5f,paint);
        }
        paint.setAlpha(255);
    }
    private void touch(float x,float y,boolean ripple) {
        touchedAt=SystemClock.elapsedRealtime();
        inkField.perturb(x/Math.max(1,getWidth()),y/Math.max(1,getHeight()),ripple?.36f:.12f);
        dragX=Math.max(-1,Math.min(1,x/Math.max(1,getWidth())*2-1));
        dragY=Math.max(-1,Math.min(1,y/Math.max(1,getHeight())*2-1));
        if(ripple) {
            int i=rippleCursor++%rippleAt.length; rippleX[i]=x/Math.max(1,getWidth()); rippleY[i]=y/Math.max(1,getHeight()); rippleAt[i]=touchedAt;
        }
        for(int i=0;i<glintCount;i++) {
            float dx=x-glintX[i], dy=y-glintY[i];
            if(!glintTaken[i] && dx*dx+dy*dy<dp(20)*dp(20)) { glintTaken[i]=true; collected++; }
        }
        redraw(); schedule();
    }
    /** Parent conversation surfaces may forward real touch positions into the same material. */
    void perturb(float x,float y) { if(!disposed) { touchCount++; touch(x,y,true); } }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(disposed) return false;
        touchExpression.offer(event,getWidth(),getHeight());
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN)performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX=event.getX(); downY=event.getY(); touchCount++;
                if(getParent()!=null) getParent().requestDisallowInterceptTouchEvent(true);
                touch(downX,downY,true); return true;
            case MotionEvent.ACTION_MOVE:
                touch(event.getX(),event.getY(),false); return true;
            case MotionEvent.ACTION_UP:
                touch(event.getX(),event.getY(),true);
                if(getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false);
                float travel=Math.abs(event.getX()-downX)+Math.abs(event.getY()-downY);
                if(travel<dp(10)) performClick();
                else if(travel>=dp(18) && learningListener!=null) {
                    learningListener.adjust("warmth",Math.max(0,Math.min(1,event.getX()/Math.max(1,getWidth()))),"canvas drag");
                    learningListener.adjust("energy",Math.max(0,Math.min(1,1-event.getY()/Math.max(1,getHeight()))),"canvas drag");
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if(getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false); return true;
            default: return super.onTouchEvent(event);
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    private static boolean letter(char c) { return c>='A'&&c<='Z'||c>='a'&&c<='z'||c>=0x0621&&c<=0x065f||c==0x0670; }
    private static String bounded(String text,int limit) { return text==null?"":text.length()>limit?text.substring(text.length()-limit):text; }
    private static int size(Map<String,Object> map,String key) { List<Object> values=Json.arr(map.get(key)); return values==null?0:values.size(); }
    private int dp(int value) { return Math.max(1,(int)(value*getResources().getDisplayMetrics().density+.5f)); }
    private boolean animated() {
        long now=SystemClock.elapsedRealtime();
        return !disposed && (constellation.active() || now-changedAt<1800 || now-touchedAt<1500 || now-lastPromptAt<2800 || now-typingAt<1500 || now-learningAt<5000)
            && isAttachedToWindow() && isShown() && getWindowVisibility()==VISIBLE;
    }
    private void schedule() {
        if(inkField!=null) inkField.setVisible(!disposed && isAttachedToWindow() && isShown() && getWindowVisibility()==VISIBLE);
        if(!animated()) { if(scheduled) removeCallbacks(frame); scheduled=false; return; }
        if(!scheduled) { scheduled=true; postDelayed(frame,42); }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); schedule(); }
    @Override protected void onDetachedFromWindow() { constellation.active(false);removeCallbacks(frame);scheduled=false;inkField.setVisible(false);super.onDetachedFromWindow(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus);schedule(); }
    @Override protected void onVisibilityChanged(View view,int visibility) { super.onVisibilityChanged(view,visibility); if(frame!=null) schedule(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); if(frame!=null) schedule(); }
    /** Class-loader-neutral state: strings, boxed scalars, lists and cloned primitive arrays. */
    Map<String,Object> handoff() {
        Map<String,Object> field=inkField.handoff();
        if(field==null) return null;
        return Json.map("version",1,"ink_field",field,"seed",seed,"changed_at",changedAt,"touched_at",touchedAt,
            "phase_from",signatureFrom,"phase_target",signatureTarget,
            "wave_from",waveFrom.clone(),"wave_target",waveTarget.clone(),
            "drag_x",dragX,"drag_y",dragY,"down_x",downX,"down_y",downY,
            "ripple_x",rippleX.clone(),"ripple_y",rippleY.clone(),"ripple_at",rippleAt.clone(),
            "ripple_cursor",rippleCursor,"glint_x",glintX.clone(),"glint_y",glintY.clone(),
            "glint_taken",glintTaken.clone(),"glint_count",glintCount,
            "touches",touchCount,"collected",collected,"word",sourceText,"response",responseText,
            "last_prompt",lastPrompt,"last_prompt_at",lastPromptAt,"prompt_bursts",promptBursts,
            "typing_text",typingText,"typing_at",typingAt,"typing_edits",typingEdits,
            "typing_caption",typingCaption,"typing_glyph",typingGlyph,"typing_total",typingTotal,
            "learning_caption",learningCaption,"learning_at",learningAt,"learned_updates",learnedUpdates,
            "prompt_label",promptLabel,"prompt_value",promptValue,"burst_x",burstX.clone(),"burst_y",burstY.clone(),
            "focus_word",focusWord,"ordinal",textOrdinal,"focus_ordinal",focusOrdinal,
            "matches",new ArrayList<String>(associations),"active",active,
            "files",fileCount,"passing",passing,"failing",failing,"event_size",eventSize,
            "steering",new LinkedHashMap<String,Object>(semanticState),"adaptive",new LinkedHashMap<String,Object>(adaptiveState));
    }
    void restore(Map<String,Object> saved) {
        if(disposed || saved==null || integer(saved,"version",0)!=1) return;
        inkField.restore(Json.obj(saved.get("ink_field")));
        visibleWord="";
        seed=integer(saved,"seed",seed); changedAt=longValue(saved,"changed_at",changedAt);
        touchedAt=longValue(saved,"touched_at",touchedAt);
        signatureFrom=real(saved,"phase_from",signatureFrom); signatureTarget=real(saved,"phase_target",signatureTarget);
        restoreFloats(saved.get("wave_from"),waveFrom); restoreFloats(saved.get("wave_target"),waveTarget);
        dragX=real(saved,"drag_x",dragX); dragY=real(saved,"drag_y",dragY);
        downX=real(saved,"down_x",downX); downY=real(saved,"down_y",downY);
        restoreFloats(saved.get("ripple_x"),rippleX); restoreFloats(saved.get("ripple_y"),rippleY);
        Object times=saved.get("ripple_at");
        if(times instanceof long[]) System.arraycopy(times,0,rippleAt,0,Math.min(((long[])times).length,rippleAt.length));
        rippleCursor=Math.max(0,integer(saved,"ripple_cursor",rippleCursor));
        restoreFloats(saved.get("glint_x"),glintX); restoreFloats(saved.get("glint_y"),glintY);
        Object taken=saved.get("glint_taken");
        if(taken instanceof boolean[]) System.arraycopy(taken,0,glintTaken,0,Math.min(((boolean[])taken).length,glintTaken.length));
        glintCount=Math.max(0,Math.min(glintTaken.length,integer(saved,"glint_count",glintCount)));
        touchCount=Math.max(0,integer(saved,"touches",touchCount)); collected=Math.max(0,integer(saved,"collected",collected));
        sourceText=bounded(Json.str(saved,"word"),4096); responseText=bounded(Json.str(saved,"response"),4096);
        focusWord=bounded(Json.str(saved,"focus_word"),256);
        lastPrompt=bounded(Json.str(saved,"last_prompt"),4096);lastPromptAt=longValue(saved,"last_prompt_at",0);
        promptBursts=Math.max(0,integer(saved,"prompt_bursts",0));
        promptLabel=bounded(Json.str(saved,"prompt_label"),48);promptValue=bounded(Json.str(saved,"prompt_value"),12);
        typingText=bounded(Json.str(saved,"typing_text"),4096);typingAt=longValue(saved,"typing_at",0);
        typingEdits=Math.max(0,integer(saved,"typing_edits",0));typingTotal=Math.max(0,integer(saved,"typing_total",0));
        typingCaption=bounded(Json.str(saved,"typing_caption"),100);typingGlyph=bounded(Json.str(saved,"typing_glyph"),4);
        learningCaption=bounded(Json.str(saved,"learning_caption"),160);learningAt=longValue(saved,"learning_at",0);
        learnedUpdates=Math.max(0,longValue(saved,"learned_updates",0));
        restoreFloats(saved.get("burst_x"),burstX);restoreFloats(saved.get("burst_y"),burstY);
        responseInk=responseText.replace('\n',' ').trim();
        if(responseInk.length()>60) responseInk=responseInk.substring(responseInk.length()-60);
        textOrdinal=CosmicWords.ordinal(sourceText); updateStudy();
        associations=letterStudy!=null && "english-ordinal".equals(letterStudy.scheme)
            ?new ArrayList<>(CosmicWords.matches(focusWord)):new ArrayList<String>();
        active=Boolean.TRUE.equals(saved.get("active"));
        fileCount=Math.max(0,integer(saved,"files",0)); passing=Math.max(0,integer(saved,"passing",0));
        failing=Math.max(0,integer(saved,"failing",0)); eventSize=Math.max(0,integer(saved,"event_size",0));
        Map<String,Object> semantic=Json.obj(saved.get("steering"));
        if(semantic!=null) {
            semanticState=new LinkedHashMap<String,Object>(semantic);
            Map<String,Object> values=Json.obj(semantic.get("values"));
            if(values!=null) { semanticCalm=control(values,"calm");semanticWarmth=control(values,"warmth");semanticDepth=control(values,"depth");semanticFocus=control(values,"focus");semanticEnergy=control(values,"energy"); }
        }
        Map<String,Object> adaptive=Json.obj(saved.get("adaptive"));
        Map<String,Object> values=adaptive==null?null:Json.obj(adaptive.get("values"));
        if(values!=null) {
            adaptiveState=new LinkedHashMap<String,Object>(adaptive);
            semanticCalm=control(values,"calm");semanticWarmth=control(values,"warmth");semanticDepth=control(values,"depth");
            semanticFocus=control(values,"focus");semanticEnergy=control(values,"energy");
            textScale=control(values,"text_scale");contrast=control(values,"contrast");detail=control(values,"detail");
        }
        inkField.controls(semanticWarmth,semanticFocus,semanticDepth,semanticEnergy,semanticCalm,contrast,detail);
        constellation.controls(semanticCalm,semanticFocus,semanticDepth,semanticEnergy,semanticWarmth,
            contrast,detail,textScale,longValue(adaptiveState,"weights_version",0));
        rebuildShaders(); redraw(); schedule();
    }
    private static int integer(Map<String,Object> saved,String key,int fallback) {
        Object value=saved.get(key); return value instanceof Number?((Number)value).intValue():fallback;
    }
    private static long longValue(Map<String,Object> saved,String key,long fallback) {
        Object value=saved.get(key); return value instanceof Number?((Number)value).longValue():fallback;
    }
    private static float real(Map<String,Object> saved,String key,float fallback) {
        Object value=saved.get(key); float number=value instanceof Number?((Number)value).floatValue():fallback;
        return Float.isNaN(number)||Float.isInfinite(number)?fallback:number;
    }
    private static void restoreFloats(Object saved,float[] target) {
        if(!(saved instanceof float[])) return;
        float[] source=(float[])saved;
        for(int i=0;i<Math.min(source.length,target.length);i++)
            if(!Float.isNaN(source[i])&&!Float.isInfinite(source[i])) target[i]=source[i];
    }
    Map<String,Object> state() { return Json.map("active",active,"scheduled",scheduled,"disposed",disposed,"snapshot_hash",Integer.toHexString(seed),"files",fileCount,"passing",passing,"failing",failing,
        "word",sourceText,"ordinal",textOrdinal,"focus_word",focusWord,"focus_ordinal",focusOrdinal,"cipher",letterStudy==null?"":letterStudy.scheme,"equation",studyEquation,"explanation",letterStudy==null?"":letterStudy.explain(),"matches",new ArrayList<String>(associations),"touches",touchCount,"collected",collected,"drag_x",dragX,"drag_y",dragY,
        "last_prompt",lastPrompt,"prompt_age_ms",lastPromptAt==0?-1:SystemClock.elapsedRealtime()-lastPromptAt,
        "prompt_bursts",promptBursts,"burst_x",burstX.clone(),"burst_y",burstY.clone(),
        "content_top",contentTop,"content_bottom",contentBottom,
        "input_bounds",new int[]{inputLeft,inputTop,inputRight,inputBottom},"typing_edits",typingEdits,"typing_text",typingText,
        "typing_total",typingTotal,"typing_age_ms",typingAt==0?-1:SystemClock.elapsedRealtime()-typingAt,
        "learning_caption",learningCaption,"learned_updates",learnedUpdates,
        "steering",new LinkedHashMap<String,Object>(semanticState),"adaptive",new LinkedHashMap<String,Object>(adaptiveState),"visual_model",inkField.stats()); }
    Map<String,Object> renderingStats() {
        Map<String,Object> stats=inkField.stats();
        stats.put("draw_frames",drawCount);stats.put("draw_ns",drawNanos);stats.put("max_draw_ns",maxDrawNanos);
        stats.put("word_passes",wordPasses);stats.put("raster_builds",0);
        stats.put("foreground_feedback",feedback!=null);stats.put("typing_edits",typingEdits);
        stats.put("gesture_points",trailCursor);
        stats.put("expressive_touch",touchExpression.state());
        stats.put("swipe_choice_count",choiceCount);stats.put("swipe_choices",java.util.Arrays.asList(choiceWords.clone()));
        stats.put("swipe_selected",choiceSelected);stats.put("swipe_word_erase",choiceErase);
        stats.put("constellation",constellation.state());
        return stats;
    }
    @Override public void close() {
        constellation.active(false);disposed=true;wordListener=null;choicePointer=-1;choicePressed=-1;playPointer=-1;
        removeCallbacks(frame);scheduled=false;inkField.close();
    }
}
