package com.trough.hot;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.icu.text.BreakIterator;
import android.os.SystemClock;
import android.text.Editable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.MenuItem;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Forge's app-owned keyboard. The native editor continues to own its text and selection. */
final class ForgeKeyboard extends LinearLayout implements AutoCloseable {
    private static final String[] LETTERS={"qwertyuiop","asdfghjkl","zxcvbnm"};
    private static final String[][] SYMBOLS={
        {"!","@","#","$","%","^","&","*","(",")"},
        {"[","]","{","}","+","=","/","\\",":",";"},
        {"'","\"",",","?","_","<",">","|","~","`"}
    };
    private android.media.SoundPool instrument;
    private final int[] notes=new int[15];
    private void playNote(int key){if(instrument!=null && key>=0)instrument.play(notes[key%notes.length],.22f,.22f,1,0,1f);}
    private EditText editor;
    private final EditText promptEditor;
    private final View nativeOwner;
    private final LinearLayout powerRow;
    private final LinearLayout[] rows=new LinearLayout[3];
    private final List<Key> presses=new ArrayList<Key>();
    private final BreakIterator characters=BreakIterator.getCharacterInstance(Locale.ROOT);
    private final int touchSlop;
    private boolean symbols,closed,menuOpen;
    private int shiftMode,windowHeight,lastDesiredHeight,lastHeightLimit,lastHeightMode;
    private int adaptiveSignature=-1;
    private int keyTextColor=0xffd2e3df,normalColor=0x1a77b6ad,pressedColor=0x6777b6ad,selectedColor=0x4b77b6ad;
    private float keyTextSp=14.5f;
    private PopupMenu punctuation;
    private Runnable phoneKeyboard;
    void setPhoneKeyboard(Runnable action){phoneKeyboard=action;}
    private Key menuHold;
    private final Paint trailPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trailPath=new Path();
    private Key trailOwner;
    private long trailEnded;
    private final float[] layoutX=new float[26],layoutY=new float[26];
    private boolean lettersReady;
    private final LiteralSwipe.Geometry geometry=new LiteralSwipe.Geometry(dp(1.5f),dp(2),dp(3));
    private final Key[] geometryKeys=new Key[LiteralSwipe.MAX_KEYS];
    // Old learned swipe weights remain restorable, but literal entry never runs a decoder.
    private final PersonalSwipeModel personal=new PersonalSwipeModel();
    private boolean learningReady;
    interface LearningListener {
        void choices(String[] words,String selected,boolean canErase);
        void learned(String encoded,Map<String,Object> state,Map<String,Object> event);
    }
    private LearningListener learningListener;
    interface GestureListener { void active(boolean active); }
    private GestureListener gestureListener;
    private boolean gestureActive;
    private int gestureTransitions;
    private long streamedKeys,streamEdits,repeatLetters;

    private final Runnable showHeldMenu=new Runnable() {
        @Override public void run() {
            Key key=menuHold;
            if(key!=null && key.pointer!=-1 && !closed && isEnabled() && editor.isEnabled()) {
                menuOpen=true; publishGesture();
                cancelTouch(key); key.performLongClick();
            }
        }
    };

    ForgeKeyboard(Context context, EditText editor, Runnable send) { this(context,editor,send,null); }
    ForgeKeyboard(Context context, EditText editor, Runnable send,View nativeOwner) {
        super(context);
        this.nativeOwner=nativeOwner;
        this.editor=editor; this.promptEditor=editor;
        if(new java.io.File(context.getFilesDir(),"home/.trough/musical-keyboard").isFile()) {
            try {instrument=new android.media.SoundPool.Builder().setMaxStreams(12).setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_GAME).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()).build();
                for(int i=0;i<notes.length;i++)notes[i]=instrument.load(new java.io.File(context.getFilesDir(),"home/.trough/media/keys/"+i+".wav").getPath(),1);
            }catch(RuntimeException unavailable){if(instrument!=null)instrument.release();instrument=null;}
        }
        touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();
        setMotionEventSplittingEnabled(true);
        setOrientation(VERTICAL); setFocusable(false); setClipToPadding(false);
        setPadding(dp(3),dp(2),dp(3),dp(3));
        editor.setShowSoftInputOnFocus(false);
        powerRow=new LinearLayout(context); powerRow.setOrientation(HORIZONTAL);
        addView(powerRow,new LayoutParams(-1,0,.8f));
        for(int n=0;n<10;n++) {
            String digit=String.valueOf(n);
            powerRow.addView(new Key(digit,digit,"Digit "+digit),keyParams(0,1));
        }
        powerRow.addView(new Key(".",".","Period; long press for explicit editing, symbols, capitals, space, and new line"),keyParams(0,1));
        for(int n=0;n<rows.length;n++) {
            rows[n]=new LinearLayout(context); rows[n].setOrientation(HORIZONTAL);
            if(n==1) rows[n].setPadding(dp(10),0,dp(10),0);
            if(n==2) rows[n].setPadding(dp(22),0,dp(22),0);
            addView(rows[n],new LayoutParams(-1,0,1));
        }
        setWillNotDraw(false);
        trailPaint.setStyle(Paint.Style.STROKE); trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND); trailPaint.setStrokeWidth(dp(3));
        renderLetters(); hideIme();
    }

    private int dp(float value) { return (int)(value*getResources().getDisplayMetrics().density+.5f); }

    void setLearningListener(LearningListener listener) { learningListener=listener; publishChoices(); }
    /** One pause owner for all fingers; releasing one thumb cannot release another's pause. */
    void setGestureListener(GestureListener listener) {
        gestureListener=listener; gestureActive=!presses.isEmpty() || menuOpen;
        if(listener!=null) listener.active(gestureActive);
    }
    private void publishGesture() {
        boolean active=!presses.isEmpty() || menuOpen;
        if(active==gestureActive) return;
        gestureActive=active; gestureTransitions++;
        if(gestureListener!=null) gestureListener.active(active);
    }
    Map<String,Object> learningState() {
        Map<String,Object> state=personal.state();
        state.put("ready",learningReady); state.put("active",false);
        state.put("input_mode","movement-intent-stream"); state.put("decoder_active",false);
        return state;
    }
    void restoreLearning(String encoded) {
        if(closed || learningReady) return;
        if(encoded!=null && !encoded.isEmpty()) personal.restore(encoded);
        learningReady=true; publishChoices();
    }
    private void publishChoices() {
        if(learningListener!=null) learningListener.choices(new String[0],"",false);
    }
    void chooseWord(String displayed) { publishChoices(); }
    void eraseWord() {
        Editable text=editor.getText();
        int a=selection(editor.getSelectionStart(),text.length()),b=selection(editor.getSelectionEnd(),text.length());
        int start=Math.min(a,b),end=Math.max(a,b);
        if(start==end) {
            while(start>0 && Character.isWhitespace(text.charAt(start-1))) start--;
            while(start>0 && !Character.isWhitespace(text.charAt(start-1))) start--;
        }
        if(start==end) return;
        text.delete(start,end); editor.setSelection(start); editor.requestFocus();
    }

    /** Match the editor and surface without replacing keys or their active touches. */
    void applyAdaptive(Map<String,Object> state) {
        if(closed || state==null) return;
        Object raw=state.get("values");
        Map<?,?> values=raw instanceof Map?(Map<?,?>)raw:state;
        int warmth=bucket(values,"warmth"),energy=bucket(values,"energy"),scale=bucket(values,"text_scale"),contrast=bucket(values,"contrast");
        int signature=warmth|(energy<<4)|(scale<<8)|(contrast<<12);
        if(signature==adaptiveSignature) return;
        adaptiveSignature=signature;
        int accent=blend(0xff77b6ad,0xffd7aa73,warmth/15f);
        keyTextColor=blend(0xffc6d2ce,0xfff4f5e9,contrast/15f);
        normalColor=withAlpha(accent,18+contrast);
        pressedColor=withAlpha(accent,65+energy*5);
        selectedColor=withAlpha(accent,45+energy*3);
        trailPaint.setColor(withAlpha(accent,220));
        keyTextSp=12f+5f*(scale/15f);
        styleRow(powerRow);
        for(LinearLayout row:rows) styleRow(row);
    }

    private void styleRow(LinearLayout row) {
        if(row==null) return;
        for(int n=0;n<row.getChildCount();n++) ((Key)row.getChildAt(n)).applyStyle();
    }

    private static int bucket(Map<?,?> values,String key) {
        Object raw=values.get(key);
        float value=raw instanceof Number?((Number)raw).floatValue():.5f;
        if(Float.isNaN(value) || Float.isInfinite(value)) value=.5f;
        return Math.round(Math.max(0f,Math.min(1f,value))*15f);
    }

    private static int withAlpha(int color,int alpha) { return (color&0x00ffffff)|(alpha<<24); }

    private static int blend(int a,int b,float t) {
        return Color.rgb(Math.round(Color.red(a)+(Color.red(b)-Color.red(a))*t),
            Math.round(Color.green(a)+(Color.green(b)-Color.green(a))*t),
            Math.round(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t));
    }

    private LayoutParams keyParams(int width,float weight) {
        LayoutParams p=new LayoutParams(width,-1,weight);
        p.setMargins(dp(1.5f),dp(1.5f),dp(1.5f),dp(1.5f));
        return p;
    }

    /** A single size owner keeps the composer and history visible, including split windows. */
    void adaptHeight(int totalHeightPx) {
        if(totalHeightPx>0 && totalHeightPx!=windowHeight) { windowHeight=totalHeightPx; requestLayout(); }
    }

    void adaptHeightForMeasure(int totalHeightPx) { if(totalHeightPx>0) windowHeight=totalHeightPx; }

    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int total=windowHeight;
        View root=getRootView();
        if(total<=0 && root!=this && root.getHeight()>0) total=root.getHeight();
        if(total<=0) total=getResources().getDisplayMetrics().heightPixels;
        boolean shortWindow=total<dp(600);
        float fraction=shortWindow ? .48f : .25f;
        int height=Math.max(0,Math.min(dp(shortWindow ? 300 : 210),(int)(total*fraction)));
        if(new java.io.File(getContext().getFilesDir(),"home/.trough/keyboard-only").isFile()) height=Math.max(0,total-dp(22));
        lastDesiredHeight=height;lastHeightLimit=MeasureSpec.getSize(heightSpec);lastHeightMode=MeasureSpec.getMode(heightSpec);
        if(MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED) height=Math.min(height,MeasureSpec.getSize(heightSpec));
        super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
        super.onLayout(changed,left,top,right,bottom);
        int count=0;
        count=layoutRow(powerRow,count);
        for(LinearLayout row:rows) count=layoutRow(row,count);
        if(geometry.count!=count) { geometry.count=count;geometry.version++; }
        lettersReady=!symbols && count==37;
    }

    private int layoutRow(LinearLayout row,int count) {
        for(int i=0;i<row.getChildCount() && count<geometryKeys.length;i++) {
            Key key=(Key)row.getChildAt(i);
            geometryKeys[count]=key; key.geometryIndex=count;
            float x=row.getLeft()+key.getLeft(),y=row.getTop()+key.getTop();
            geometry.set(count,x,y,x+key.getWidth(),y+key.getHeight(),
                key.action.length()==1 && Character.isLetter(key.action.charAt(0)));
            if(key.action.length()==1 && key.action.charAt(0)>='a' && key.action.charAt(0)<='z') {
                int at=key.action.charAt(0)-'a';
                layoutX[at]=x+key.getWidth()*.5f; layoutY[at]=y+key.getHeight()*.5f;
            }
            count++;
        }
        return count;
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        Key owner=trailOwner;
        float alpha=1;
        if(owner!=null && owner.pointer<0) alpha=Math.max(0,1-(SystemClock.uptimeMillis()-trailEnded)/260f);
        if(owner==null || owner.pathCount<2 || alpha<=0) { if(alpha<=0) trailOwner=null; return; }
        trailPath.reset(); trailPath.moveTo(owner.pathX[0],owner.pathY[0]);
        for(int i=1;i<owner.pathCount;i++) trailPath.lineTo(owner.pathX[i],owner.pathY[i]);
        trailPaint.setColor(withAlpha(keyTextColor,(int)(190*alpha)));
        canvas.drawPath(trailPath,trailPaint);
        if(owner.pointer<0) postInvalidateOnAnimation();
    }

    private void renderLetters() {
        for(int row=0;row<rows.length;row++) {
            int count=symbols?SYMBOLS[row].length:LETTERS[row].length();
            boolean reuse=rows[row].getChildCount()==count;
            for(int n=0;reuse && n<count;n++) {
                String action=symbols?SYMBOLS[row][n]:String.valueOf(LETTERS[row].charAt(n));
                reuse=action.equals(((Key)rows[row].getChildAt(n)).action);
            }
            if(!reuse) {
                for(int n=0;n<rows[row].getChildCount();n++) {
                    Key key=(Key)rows[row].getChildAt(n);cancelTouch(key);closePath(key.literal);
                }
                rows[row].removeAllViews();
            }
            for(int n=0;n<count;n++) {
                String action=symbols?SYMBOLS[row][n]:String.valueOf(LETTERS[row].charAt(n));
                String label=!symbols && shiftMode!=0?action.toUpperCase(Locale.ROOT):action;
                if(reuse) {
                    Key key=(Key)rows[row].getChildAt(n); key.setText(label); key.setContentDescription(keyDescription(action,label));
                } else rows[row].addView(new Key(action,label,keyDescription(action,label)),keyParams(0,1));
            }
        }
    }

    private String keyDescription(String action,String label) {
        return action.length()==1 && Character.isLetter(action.charAt(0))
            ? label+"; slide toward letters and turn to select them; straight travel skips passed keys; small loops repeat letters" : label;
    }

    private void showPunctuation(View anchor) {
        if(closed || !isEnabled() || !editor.isEnabled()) return;
        if(punctuation!=null) { punctuation.setOnDismissListener(null); punctuation.dismiss(); }
        menuOpen=true; publishGesture();
        final PopupMenu opened=new PopupMenu(getContext(),anchor);
        punctuation=opened;
        opened.setOnDismissListener(new PopupMenu.OnDismissListener() {
            @Override public void onDismiss(PopupMenu popup) {
                if(punctuation==opened) { punctuation=null; menuOpen=false; publishGesture(); }
            }
        });
        punctuation.getMenu().add(0,1,0,symbols?"Letters":"Symbols");
        punctuation.getMenu().add(0,2,1,"Uppercase next letter");
        punctuation.getMenu().add(0,3,2,shiftMode==2?"Caps lock off":"Caps lock on");
        punctuation.getMenu().add(0,4,3,"Space");
        punctuation.getMenu().add(0,5,4,"New line");
        punctuation.getMenu().add(0,6,5,"Delete previous word");
        punctuation.getMenu().add(0,7,6,"Delete previous letter");
        if(phoneKeyboard!=null)punctuation.getMenu().add(0,8,7,"Phone keyboard · swipe and predictions");
        punctuation.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                if(item.getItemId()==8 && phoneKeyboard!=null){phoneKeyboard.run();return true;}
                String[] names={"","symbols","uppercase","caps","space","newline","erase_word","erase"};
                int id=item.getItemId(); return id>0 && id<names.length && testAction(names[id]);
            }
        });
        punctuation.show();
    }

    /** Native oracle uses the same explicit commands as the period menu. */
    boolean testAction(String action) {
        if(closed || !isEnabled() || !editor.isEnabled()) return false;
        if("symbols".equals(action)) { cancelTouch(); symbols=!symbols; renderLetters(); }
        else if("uppercase".equals(action)) { cancelTouch(); symbols=false; shiftMode=1; renderLetters(); }
        else if("caps".equals(action)) { cancelTouch(); symbols=false; shiftMode=shiftMode==2?0:2; renderLetters(); }
        else if("space".equals(action)) insert(" ");
        else if("newline".equals(action)) insert("\n");
        else if("erase_word".equals(action)) eraseWord();
        else if("erase".equals(action)) erase();
        else return false;
        return true;
    }

    /** Pin one field for the entire gesture; selecting an app field never edits the AI draft. */
    private void selectEditor() {
        if(!presses.isEmpty()) return;
        View focused=nativeOwner==null?null:nativeOwner.findFocus();
        editor=focused instanceof EditText && focused.isShown() && focused.isEnabled()
            ?(EditText)focused:promptEditor;
    }

    private void act(String action) {
        selectEditor();
        if(closed || !isEnabled() || !editor.isEnabled()) return;
        String value=action;
        boolean letter=action.length()==1 && Character.isLetter(action.charAt(0));
        if(letter && shiftMode!=0) value=value.toUpperCase(Locale.ROOT);
        insert(value);
        if(letter && shiftMode==1) { shiftMode=0; renderLetters(); }
    }

    private int selection(int value,int length) { return value<0?length:Math.max(0,Math.min(length,value)); }

    private void insert(String value) {
        Editable text=editor.getText();
        int a=selection(editor.getSelectionStart(),text.length()),b=selection(editor.getSelectionEnd(),text.length());
        int start=Math.min(a,b),end=Math.max(a,b);
        editor.requestFocus();
        text.replace(start,end,value); editor.setSelection(start+value.length()); hideIme();
    }

    /** ICU character boundaries preserve surrogate pairs, combining marks, and ZWJ emoji. */
    private void erase() {
        Editable text=editor.getText();
        int a=selection(editor.getSelectionStart(),text.length()),b=selection(editor.getSelectionEnd(),text.length());
        int start=Math.min(a,b),end=Math.max(a,b);
        if(start==0 && end==0) return;
        characters.setText(text.toString());
        if(start==end) {
            if(!characters.isBoundary(end)) { int next=characters.following(end); if(next!=BreakIterator.DONE) end=next; }
            int previous=characters.preceding(end); start=previous==BreakIterator.DONE?0:previous;
        } else {
            if(!characters.isBoundary(start)) { int previous=characters.preceding(start); start=previous==BreakIterator.DONE?0:previous; }
            if(!characters.isBoundary(end)) { int next=characters.following(end); end=next==BreakIterator.DONE?text.length():next; }
        }
        text.delete(start,end); editor.setSelection(start); editor.requestFocus(); hideIme();
    }

    void hideIme() {
        editor.setShowSoftInputOnFocus(false);
        InputMethodManager input=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if(input!=null && editor.getWindowToken()!=null) input.hideSoftInputFromWindow(editor.getWindowToken(),0);
    }

    private boolean repeatEligible() {
        return !closed && isEnabled() && editor.isEnabled() && isAttachedToWindow()
            && isShown() && getWindowVisibility()==VISIBLE && hasWindowFocus();
    }

    private void cancelTouch() {
        if(presses==null) return;
        removeCallbacks(showHeldMenu); menuHold=null;
        for(int i=presses.size()-1;i>=0;i--) retire(presses.get(i));
        presses.clear();
        if(trailOwner!=null) { trailOwner=null; invalidate(); }
        requestDisallowInterceptTouchEvent(false); publishGesture();
    }

    private void retire(Key key) {
        key.literal.end(); key.pending.setLength(0);
        key.pointer=-1; key.setPressed(false);
        if(trailOwner==key) { trailEnded=SystemClock.uptimeMillis(); postInvalidateOnAnimation(); }
    }
    private void cancelTouch(Key key) {
        if(presses==null || !presses.contains(key)) return;
        if(menuHold==key) { removeCallbacks(showHeldMenu); menuHold=null; }
        retire(key); presses.remove(key);
        if(presses.isEmpty()) requestDisallowInterceptTouchEvent(false);
        publishGesture();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if(!closed) hideIme(); }
    @Override protected void onDetachedFromWindow() { cancelTouch(); super.onDetachedFromWindow(); }
    @Override public void setVisibility(int visibility) {
        if(visibility!=VISIBLE && presses!=null) cancelTouch();
        super.setVisibility(visibility);
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); if(visibility!=VISIBLE) cancelTouch();
    }
    @Override protected void onVisibilityChanged(View changed,int visibility) {
        super.onVisibilityChanged(changed,visibility); if(visibility!=VISIBLE) cancelTouch();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); if(!focused) cancelTouch();
    }

    private SwipePath createPath(LiteralSwipe.Geometry geometry,LiteralSwipe.Listener listener) {
        NativeSwipe nativePath=NativeSwipe.open(getContext(),nativeOwner,geometry,listener);
        return nativePath!=null?nativePath:new LiteralSwipe(geometry,listener);
    }
    private void closePath(SwipePath path) {
        if(path instanceof NativeSwipe) ((NativeSwipe)path).close();else path.end();
    }
    private void closePaths(LinearLayout row) {
        for(int i=0;i<row.getChildCount();i++) closePath(((Key)row.getChildAt(i)).literal);
    }
    private int nativePathCount() {
        int count=0;
        for(int i=0;i<geometry.count;i++) if(geometryKeys[i]!=null && geometryKeys[i].literal instanceof NativeSwipe
            && ((NativeSwipe)geometryKeys[i].literal).healthy()) count++;
        return count;
    }

    Map<String,Object> state() {
        Key active=presses.isEmpty()?null:presses.get(0);
        return Json.map("kind","forge_native","model","movement-intent-stream","symbols",symbols,"shift_mode",shiftMode,
            "gesture_engine",nativePathCount()==geometry.count && geometry.count>0?"native-c":"java-fallback",
            "native_paths",nativePathCount(),"native_input",NativeSwipe.stats(),
            "closed",closed,"repeat_scheduled",false,"held_key",active==null?null:active.action,
            "active_key",active==null?null:active.action,"active_pointer",active==null?-1:active.pointer,
            "active_presses",presses.size(),"gesture_active",!presses.isEmpty() || menuOpen,"menu_open",menuOpen,"gesture_transitions",gestureTransitions,"repeat_eligible",repeatEligible(),
            "adaptive_style",adaptiveSignature,"adaptive_font_sp",keyTextSp,
            "swipe_typing",true,"swipe_words",0,"swipe_fallbacks",0,"swipe_pending",0,"swipe_last_ns",0,
            "swipe_learning",learningState(),"trailing_space",false,"automatic_spaces",false,"decoder_active",false,
            "streamed_keys",streamedKeys,"stream_edits",streamEdits,"repeated_letters",repeatLetters,
            "targeting","direction-change","intent_turn_degrees",65,"intent_incoming_fraction",.55f,"intent_outgoing_fraction",.28f,
            "repeat_dwell_ms",0,"repeat_mode","same-key-loop","repeat_loop_radius_fraction",.16f,"path_capacity",192,
            "height",getMeasuredHeight(),"desired_height",lastDesiredHeight,"parent_height_limit",lastHeightLimit,"height_measure_mode",lastHeightMode,"density",getResources().getDisplayMetrics().density,"configuration_height_dp",getResources().getConfiguration().screenHeightDp,"root_measured_height",getRootView().getMeasuredHeight(),"window_height_basis",windowHeight,"root_height",getRootView().getHeight(),"height_limit_fraction",.34,"bottom_toolbar",false,"swipe_editing",false,"ime_suppressed",!editor.getShowSoftInputOnFocus(),
            "editor_enabled",editor.isEnabled(),"editor_focus",editor.hasFocus(),"number_row_keys",11,"number_row_scrollable",false,"power_scroll",0);
    }

    boolean hasPendingPress() { return !presses.isEmpty() || menuOpen; }
    boolean isRefiningSwipe() { return false; }
    Map<String,Object> handoff() { return Json.map("symbols",symbols,"shift_mode",shiftMode,"power_scroll",0,
        "swipe_learning",learningReady?personal.encode():null); }
    void restore(Map<?,?> state) {
        if(state==null || closed) return;
        cancelTouch(); symbols=Boolean.TRUE.equals(state.get("symbols"));
        Object memory=state.get("swipe_learning");
        if(memory instanceof String && personal.restore((String)memory)) learningReady=true;
        Object shift=state.get("shift_mode"); shiftMode=shift instanceof Number?Math.max(0,Math.min(2,((Number)shift).intValue())):0;
        renderLetters(); hideIme();
    }

    /** Drive the real key touch path against the actual visible keys. */
    boolean testPress(String literalKey) {
        if(closed || "Send".equals(literalKey)) return false;
        View key=findViewWithTag(literalKey);
        if(key==null || key.getWidth()<=0 || key.getHeight()<=0) return false;
        long now=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,key.getWidth()/2f,key.getHeight()/2f,0);
        MotionEvent up=MotionEvent.obtain(now,now+1,MotionEvent.ACTION_UP,key.getWidth()/2f,key.getHeight()/2f,0);
        try { return key.dispatchTouchEvent(down) && key.dispatchTouchEvent(up); }
        finally { down.recycle(); up.recycle(); }
    }

    /** Exercise continuous literal movement; erase flag chooses only its direction. */
    boolean testSwipe(String literalKey,boolean erase) {
        if(closed) return false;
        View key=findViewWithTag(literalKey);
        if(!(key instanceof Key) || !((Key)key).streamKey() || key.getWidth()<=0 || key.getHeight()<=0) return false;
        long now=SystemClock.uptimeMillis();
        float x=key.getWidth()/2f,y=key.getHeight()/2f,end=x+(erase?-1:1)*dp(64);
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);
        MotionEvent move=MotionEvent.obtain(now,now+1,MotionEvent.ACTION_MOVE,end,y,0);
        MotionEvent up=MotionEvent.obtain(now,now+2,MotionEvent.ACTION_UP,end,y,0);
        try { return key.dispatchTouchEvent(down) && key.dispatchTouchEvent(move) && key.dispatchTouchEvent(up); }
        finally { down.recycle(); move.recycle(); up.recycle(); }
    }

    /** Native oracle helper: follows the real per-key dispatch stream across letter centres. */
    boolean testGlide(String word) {
        if(closed || symbols || !lettersReady || word==null || word.length()<2) return false;
        for(int i=0;i<word.length();i++) if(word.charAt(i)<'a' || word.charAt(i)>'z') return false;
        Key key=(Key)findViewWithTag(word.substring(0,1));
        if(key==null || key.getWidth()<=0) return false;
        View row=(View)key.getParent();
        float ox=key.getLeft()+row.getLeft(),oy=key.getTop()+row.getTop();
        long began=SystemClock.uptimeMillis(),at=began;
        int first=word.charAt(0)-'a';
        MotionEvent down=MotionEvent.obtain(began,at++,MotionEvent.ACTION_DOWN,layoutX[first]-ox,layoutY[first]-oy,0);
        boolean handled;
        try { handled=key.dispatchTouchEvent(down); } finally { down.recycle(); }
        for(int i=1;i<word.length();i++) {
            int from=word.charAt(i-1)-'a',to=word.charAt(i)-'a';
            for(int j=1;j<=8;j++) {
                float t=j/8f;
                float x=layoutX[from]+(layoutX[to]-layoutX[from])*t-ox;
                float y=layoutY[from]+(layoutY[to]-layoutY[from])*t-oy;
                MotionEvent move=MotionEvent.obtain(began,at++,MotionEvent.ACTION_MOVE,x,y,0);
                try { handled&=key.dispatchTouchEvent(move); } finally { move.recycle(); }
            }
        }
        int last=word.charAt(word.length()-1)-'a';
        MotionEvent up=MotionEvent.obtain(began,at,MotionEvent.ACTION_UP,layoutX[last]-ox,layoutY[last]-oy,0);
        try { return key.dispatchTouchEvent(up) && handled; } finally { up.recycle(); }
    }

    @Override public void close() {
        if(!closed) {
            closed=true; cancelTouch();
            if(instrument!=null){instrument.release();instrument=null;}
            if(punctuation!=null) { punctuation.dismiss(); punctuation=null; }
            closePaths(powerRow);for(LinearLayout row:rows) closePaths(row);
            learningListener=null; gestureListener=null;
        }
    }

    private final class Key extends TextView {
        final String action;
        final GradientDrawable normalBackground,pressedBackground,selectedBackground;
        int pointer=-1,geometryIndex=-1;
        float downX,downY;
        boolean deferredPeriod,casingDirty;
        final float[] pathX=new float[192],pathY=new float[192];
        int pathCount;
        final StringBuilder pending=new StringBuilder(32);
        final SwipePath literal=createPath(geometry,new LiteralSwipe.Listener() {
            @Override public void key(int index,boolean repeated) {
                Key crossed=index>=0 && index<geometry.count?geometryKeys[index]:null;
                if(crossed==null || crossed.action.length()!=1) return;
                char value=crossed.action.charAt(0);
                if(Character.isLetter(value) && shiftMode!=0) {
                    value=Character.toUpperCase(value);
                    if(shiftMode==1) { shiftMode=0; casingDirty=true; }
                }
                pending.append(value); streamedKeys++; playNote(index);
                if(repeated) repeatLetters++;
            }
        });

        Key(String action,String label,String description) {
            super(ForgeKeyboard.this.getContext()); this.action=action;
            setTag(action); setText(label); setContentDescription(description);
            setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
            setGravity(Gravity.CENTER); setIncludeFontPadding(false); setPadding(0,0,0,0);
            setMinWidth(0); setMinHeight(0); setSingleLine(true);
            setFocusable(false); setFocusableInTouchMode(false); setClickable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            StateListDrawable background=new StateListDrawable();
            pressedBackground=keyBackground(pressedColor);
            selectedBackground=keyBackground(selectedColor);
            normalBackground=keyBackground(normalColor);
            background.addState(new int[]{android.R.attr.state_pressed},pressedBackground);
            background.addState(new int[]{android.R.attr.state_selected},selectedBackground);
            background.addState(new int[]{},normalBackground);
            setBackground(background); applyStyle();
            setOnClickListener(new OnClickListener() { @Override public void onClick(View view) { act(Key.this.action); } });
            if(".".equals(action)) setOnLongClickListener(new OnLongClickListener() {
                @Override public boolean onLongClick(View view) { showPunctuation(view); return true; }
            });
        }
        void applyStyle() {
            if(new java.io.File(getContext().getFilesDir(),"home/.trough/keyboard-only").isFile()) {
                setTextColor(0xffe9e4d5);setTextSize(22);
                int degree=geometryIndex>=0?geometryIndex%5:0;
                normalBackground.setColor(new int[]{0xff203f44,0xff27474a,0xff304b50,0xff344853,0xff3b4858}[degree]);
                pressedBackground.setColor(0xff9bcfc0);selectedBackground.setColor(0xff628f91);return;
            }
            if(getCurrentTextColor()!=keyTextColor) setTextColor(keyTextColor);
            float scaled=getResources().getDisplayMetrics().scaledDensity*keyTextSp;
            if(Math.abs(getTextSize()-scaled)>.01f) setTextSize(keyTextSp);
            pressedBackground.setColor(pressedColor);
            selectedBackground.setColor(selectedColor);
            normalBackground.setColor(normalColor);
        }
        @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }
        @Override public void setVisibility(int visibility) {
            if(visibility!=VISIBLE && presses!=null && presses.contains(this)) cancelTouch(this);
            super.setVisibility(visibility);
        }
        @Override protected void onDetachedFromWindow() { cancelTouch(this); super.onDetachedFromWindow(); }
        @Override protected void onVisibilityChanged(View changed,int visibility) {
            super.onVisibilityChanged(changed,visibility);
            if(visibility!=VISIBLE) cancelTouch(this);
        }
        private boolean inside(float x,float y) { return x>=0 && y>=0 && x<getWidth() && y<getHeight(); }
        private boolean streamKey() { return action.length()==1; }
        private float rootX(float local) { return local+getLeft()+((View)getParent()).getLeft(); }
        private float rootY(float local) { return local+getTop()+((View)getParent()).getTop(); }
        private void trail(float x,float y) {
            if(pathCount>0) {
                float dx=x-pathX[pathCount-1],dy=y-pathY[pathCount-1];
                if(dx*dx+dy*dy<dp(2)*dp(2)) return;
            }
            if(pathCount==pathX.length) {
                for(int i=1;i<pathCount/2;i++) { pathX[i]=pathX[i*2]; pathY[i]=pathY[i*2]; }
                pathCount/=2;
            }
            pathX[pathCount]=x; pathY[pathCount++]=y;
            trailOwner=this; postInvalidateOnAnimation();
        }
        private void sample(float localX,float localY,long at) {
            float x=rootX(localX),y=rootY(localY);
            trail(x,y);
            if(deferredPeriod) {
                float dx=localX-downX,dy=localY-downY;
                if(dx*dx+dy*dy<=touchSlop*touchSlop) return;
                deferredPeriod=false;
                if(menuHold==this) { removeCallbacks(showHeldMenu); menuHold=null; }
                literal.begin(rootX(downX),rootY(downY),at,geometryIndex);
            }
            literal.move(x,y,at);
        }
        private void sample(MotionEvent event,int owner) {
            for(int i=0;i<event.getHistorySize();i++)
                sample(event.getHistoricalX(owner,i),event.getHistoricalY(owner,i),event.getHistoricalEventTime(i));
            sample(event.getX(owner),event.getY(owner),event.getEventTime());
        }
        /** One edit per Android event, even when a sparse event crossed many keys. */
        private void commit() {
            if(pending.length()>0 && !closed && editor.isEnabled()) {
                Editable text=editor.getText();
                int a=selection(editor.getSelectionStart(),text.length()),b=selection(editor.getSelectionEnd(),text.length());
                int start=Math.min(a,b),end=Math.max(a,b),count=pending.length();
                text.replace(start,end,pending); pending.setLength(0);
                editor.setSelection(Math.min(start+count,text.length())); streamEdits++;
            } else pending.setLength(0);
            if(casingDirty) { casingDirty=false; renderLetters(); }
        }
        private boolean validPeriod(MotionEvent event,int owner) {
            if(!inside(event.getX(owner),event.getY(owner))) return false;
            for(int i=0;i<event.getHistorySize();i++) if(!inside(event.getHistoricalX(owner,i),event.getHistoricalY(owner,i))) return false;
            return true;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN) selectEditor();
            if(closed || getVisibility()!=VISIBLE || ForgeKeyboard.this.getVisibility()!=VISIBLE
                || !isEnabled() || !ForgeKeyboard.this.isEnabled() || !editor.isEnabled()) {
                cancelTouch(this); return false;
            }
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if(pointer!=-1) return true;
                    int index=event.getActionIndex();
                    if(!inside(event.getX(index),event.getY(index))) return true;
                    pointer=event.getPointerId(index); downX=event.getX(index); downY=event.getY(index);
                    pathCount=0; pending.setLength(0); casingDirty=false;
                    deferredPeriod=".".equals(action);
                    presses.add(this); setPressed(true);
                    // Pause autosend BEFORE the first visible character or any TextWatcher callback.
                    publishGesture(); requestDisallowInterceptTouchEvent(true);
                    if(!editor.hasFocus()) editor.requestFocus();
                    hideIme();
                    trail(rootX(downX),rootY(downY));
                    if(deferredPeriod) {
                        menuHold=this; ForgeKeyboard.this.postDelayed(showHeldMenu,ViewConfiguration.getLongPressTimeout());
                    } else if(streamKey()) {
                        literal.begin(rootX(downX),rootY(downY),event.getEventTime(),geometryIndex); commit();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if(pointer!=-1) {
                        int owner=event.findPointerIndex(pointer);
                        if(owner<0) cancelTouch(this);
                        else if(streamKey()) { sample(event,owner); commit(); }
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP:
                    if(pointer!=-1) {
                        int owner=event.findPointerIndex(pointer);
                        if(event.getPointerId(event.getActionIndex())==pointer) {
                            if(owner>=0 && streamKey()) {
                                sample(event,owner);
                                literal.finish(rootX(event.getX(owner)),rootY(event.getY(owner)),event.getEventTime());
                                commit();
                            }
                            boolean period=owner>=0 && deferredPeriod && validPeriod(event,owner);
                            boolean other=owner>=0 && !streamKey() && inside(event.getX(owner),event.getY(owner));
                            // Retire only this pointer; another thumb continues to own autosend pause.
                            cancelTouch(this);
                            if(period || other) performClick();
                        } else if(event.getActionMasked()==MotionEvent.ACTION_UP || owner<0) cancelTouch(this);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_OUTSIDE:
                    // Already emitted text is the user's committed literal stream, never rolled back.
                    cancelTouch(this); return true;
                default: return true;
            }
        }
    }

    private GradientDrawable keyBackground(int color) {
        GradientDrawable shape=new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(5)); return shape;
    }
}

interface SwipePath {
    void begin(float x,float y,long at,int key);
    void move(float x,float y,long at);
    void finish(float x,float y,long at);
    void end();
}

/** Java reference and fallback, kept for parity checks and failed native startup. */
final class LiteralSwipe implements SwipePath {
    static final int MAX_KEYS=48;
    private static final float FULL_TURN=(float)(Math.PI*2),TURN_COS=.42261826f;
    interface Listener { void key(int index,boolean repeated); }
    static final class Geometry {
        final float[] left=new float[MAX_KEYS],top=new float[MAX_KEYS],right=new float[MAX_KEYS],bottom=new float[MAX_KEYS];
        final boolean[] repeatable=new boolean[MAX_KEYS];
        final float inset,hysteresis,minLoopRadius;
        int count,version;
        Geometry(float inset,float hysteresis,float minLoopRadius) {
            this.inset=inset; this.hysteresis=hysteresis; this.minLoopRadius=minLoopRadius;
        }
        void set(int index,float left,float top,float right,float bottom,boolean repeatable) {
            if(index>=count || this.left[index]!=left || this.top[index]!=top || this.right[index]!=right
                || this.bottom[index]!=bottom || this.repeatable[index]!=repeatable) version++;
            this.left[index]=left; this.top[index]=top; this.right[index]=right; this.bottom[index]=bottom;
            this.repeatable[index]=repeatable; count=Math.max(count,index+1);
        }
        float size(int key) { return Math.min(right[key]-left[key],bottom[key]-top[key]); }
        boolean holds(int key,float x,float y) {
            return key>=0 && key<count && x>=left[key]-hysteresis && x<=right[key]+hysteresis
                && y>=top[key]-hysteresis && y<=bottom[key]+hysteresis;
        }
        int hit(float x,float y,boolean central) {
            for(int key=0;key<count;key++) {
                float ix=central?(right[key]-left[key])*.15f:0,iy=central?(bottom[key]-top[key])*.15f:0;
                if(x>=left[key]+ix && x<right[key]-ix && y>=top[key]+iy && y<bottom[key]-iy) return key;
            }
            return -1;
        }
        float centreDistance(int key,float x,float y) {
            float dx=(x-(left[key]+right[key])*.5f)/(right[key]-left[key]);
            float dy=(y-(top[key]+bottom[key])*.5f)/(bottom[key]-top[key]);
            return dx*dx+dy*dy;
        }
    }
    private final Geometry geometry;
    private final Listener listener;
    private final float[] historyX=new float[32],historyY=new float[32];
    private int historyStart,historyCount,current=-1,lastCommitted=-1,candidate=-1;
    private boolean active,hasIncoming;
    private float x,y,winding,loopTravel,candidateX,candidateY,candidateDistance,incomingX,incomingY;
    LiteralSwipe(Geometry geometry,Listener listener) { this.geometry=geometry; this.listener=listener; }
    @Override public void begin(float x,float y,long at,int key) {
        end(); if(!finite(x) || !finite(y) || key<0 || key>=geometry.count) return;
        active=true; this.x=x; this.y=y; current=key;
        lastCommitted=key; listener.key(key,false);
        remember(x,y); target(key,x,y);
    }
    @Override public void end() {
        active=false; current=-1; candidate=-1; lastCommitted=-1;
        winding=0; loopTravel=0; historyStart=0; historyCount=0; hasIncoming=false;
    }
    boolean active() { return active; }
    int current() { return current; }
    /** Release is the one explicit final target. Cancellation calls end() instead. */
    @Override public void finish(float nx,float ny,long at) {
        if(!active) return;
        move(nx,ny,at);
        int key=finite(nx) && finite(ny)?geometry.hit(nx,ny,false):-1;
        if(key>=0 && key!=lastCommitted) emit(key,false);
        end();
    }
    private void emit(int key,boolean repeated) {
        if(repeated && key!=lastCommitted) { listener.key(key,false); lastCommitted=key; }
        if(repeated || key!=lastCommitted) { listener.key(key,repeated); lastCommitted=key; }
    }
    private void target(int key,float px,float py) {
        candidate=key; candidateX=px; candidateY=py;
        candidateDistance=geometry.centreDistance(key,px,py); hasIncoming=false;
        float minimum=geometry.size(key)*.55f;
        for(int offset=historyCount-1;offset>=0;offset--) {
            int at=(historyStart+offset)%historyX.length;
            float dx=px-historyX[at],dy=py-historyY[at],length2=dx*dx+dy*dy;
            if(length2>=minimum*minimum) {
                float length=(float)Math.sqrt(length2);
                incomingX=dx/length; incomingY=dy/length; hasIncoming=true; break;
            }
        }
    }
    private void confirm(float nx,float ny) {
        if(candidate<0 || candidate==lastCommitted || !hasIncoming) return;
        float dx=nx-candidateX,dy=ny-candidateY,length2=dx*dx+dy*dy,size=geometry.size(candidate);
        if(length2<size*size*.28f*.28f) return;
        float length=(float)Math.sqrt(length2);
        if((incomingX*dx+incomingY*dy)/length<=TURN_COS) emit(candidate,false);
        else if(length>size*.9f) { candidate=-1; hasIncoming=false; }
    }
    private void remember(float nx,float ny) {
        if(historyCount>0) {
            int last=(historyStart+historyCount-1)%historyX.length;
            float dx=nx-historyX[last],dy=ny-historyY[last];
            float spacing=current>=0?geometry.size(current)*.1f:geometry.minLoopRadius;
            if(dx*dx+dy*dy<spacing*spacing) return;
        }
        if(historyCount==historyX.length) { historyStart=(historyStart+1)%historyX.length; historyCount--; }
        int at=(historyStart+historyCount++)%historyX.length; historyX[at]=nx; historyY[at]=ny;
    }
    @Override public void move(float nx,float ny,long at) {
        if(!active || !finite(nx) || !finite(ny)) return;
        float dx=nx-x,dy=ny-y;
        if(dx==0 && dy==0) return;
        int hit=geometry.hit(nx,ny,true);
        // Keep the closest approach to the target centre. Small incoming wiggles do not become turns.
        if(hit>=0 && hit==candidate && geometry.centreDistance(hit,nx,ny)<candidateDistance) target(hit,nx,ny);
        else confirm(nx,ny);
        if(hit>=0 && hit!=candidate) target(hit,nx,ny);
        int before=current;
        if(!geometry.holds(current,nx,ny)) {
            current=geometry.hit(nx,ny,false); winding=0; loopTravel=0;
        }
        if(current>=0 && current==before && geometry.repeatable[current]) loop(nx,ny,dx,dy);
        remember(nx,ny); x=nx; y=ny;
    }
    private void loop(float nx,float ny,float dx,float dy) {
        float cx=(geometry.left[current]+geometry.right[current])*.5f,cy=(geometry.top[current]+geometry.bottom[current])*.5f;
        float radius=Math.max(geometry.minLoopRadius,geometry.size(current)*.16f);
        float ax=x-cx,ay=y-cy,bx=nx-cx,by=ny-cy;
        float length2=dx*dx+dy*dy;
        float projection=length2<=0?0:Math.max(0,Math.min(1,-(ax*dx+ay*dy)/length2));
        float closeX=ax+projection*dx,closeY=ay+projection*dy;
        if(ax*ax+ay*ay<radius*radius || bx*bx+by*by<radius*radius
            || closeX*closeX+closeY*closeY<radius*radius*.56f) { winding=0; loopTravel=0; return; }
        float angle=(float)Math.atan2(ax*by-ay*bx,ax*bx+ay*by);
        if(Math.abs(angle)>(float)Math.PI*.75f) { winding=0; loopTravel=0; return; }
        winding+=angle; loopTravel+=(float)Math.sqrt(length2);
        if(Math.abs(winding)>=FULL_TURN-.0005f && loopTravel>=radius*5f) {
            emit(current,true); winding-=Math.copySign(FULL_TURN,winding); loopTravel=0;
        }
    }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
}
