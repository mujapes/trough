package com.trough.hot;

import android.app.Activity;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroupOverlay;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.Map;

/** Native dispatch against isolated editors. Never touches or snapshots the live composer. */
final class KeyboardOracle {
    static Map<String,Object> run(Activity activity) throws Exception {
        EditText editor=new EditText(activity);
        editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final int[] sent={0};
        ForgeKeyboard keys=new ForgeKeyboard(activity,editor,new Runnable() {
            @Override public void run() { sent[0]++; }
        });
        ForgeKeyboard restored=null;
        try {
            layout(keys,1080,1400);
            Map<String,Object> result=Json.map("ok",true,"isolated_editor",true,"live_draft_edited",false);
            check(result,"height_bounded",keys.getMeasuredHeight()>0 && keys.getMeasuredHeight()<=784
                && keys.getMeasuredHeight()<=dp(keys,210));
            check(result,"no_send_or_backspace_row",keys.getChildCount()==4
                && keys.findViewWithTag("Send")==null && keys.findViewWithTag("⌫")==null);
            ViewGroup numberRow=(ViewGroup)keys.getChildAt(0);
            boolean fixed=numberRow instanceof LinearLayout && numberRow.getChildCount()==11 && numberRow.getScrollX()==0;
            int width=numberRow.getChildAt(0).getWidth();
            for(int n=0;n<11;n++) {
                View key=numberRow.getChildAt(n);
                fixed &= (n==10?".":String.valueOf(n)).equals(key.getTag())
                    && key.getLeft()>=0 && key.getRight()<=numberRow.getWidth()
                    && Math.abs(width-key.getWidth())<=1 && keys.testPress(n==10?".":String.valueOf(n));
            }
            check(result,"fixed_number_row",fixed && "0123456789.".equals(editor.getText().toString()));
            check(result,"period_menu_available",keys.findViewWithTag(".").isLongClickable());
            check(result,"native_c_gesture_engine", "native-c".equals(keys.state().get("gesture_engine")));

            View q=keys.findViewWithTag("q"),w=keys.findViewWithTag("w"),e=keys.findViewWithTag("e"),t=keys.findViewWithTag("t");
            final boolean[] gesture={false},editsOwned={true};
            final int[] transitions={0};
            keys.setGestureListener(new ForgeKeyboard.GestureListener() {
                @Override public void active(boolean active) { gesture[0]=active;transitions[0]++; }
            });
            editor.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text,int start,int count,int after) {}
                @Override public void onTextChanged(CharSequence text,int start,int before,int count) {
                    if(count>0 && !gesture[0]) editsOwned[0]=false;
                }
                @Override public void afterTextChanged(Editable text) {}
            });
            editor.setText("abCD"); editor.setSelection(2,4);editsOwned[0]=true;
            long start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            check(result,"down_streams_and_replaces_selection","abq".equals(editor.getText().toString())
                && editor.getSelectionStart()==3 && q.isPressed() && gesture[0]);
            event(q,start,start+1,MotionEvent.ACTION_MOVE,x(q,e),y(q,e));
            check(result,"straight_transit_stays_uncommitted","abq".equals(editor.getText().toString()));
            event(q,start,start+2,MotionEvent.ACTION_UP,x(q,e),y(q,e));
            event(q,start,start+3,MotionEvent.ACTION_UP,x(q,e),y(q,e));
            check(result,"release_commits_endpoint_once","abqe".equals(editor.getText().toString()) && clear(keys));
            check(result,"gesture_pauses_before_first_character",editsOwned[0] && !gesture[0]);
            keys.testPress("q");keys.testPress("w");
            check(result,"separate_strokes_never_insert_spaces","abqeqw".equals(editor.getText().toString()));

            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            event(q,start,start+1,MotionEvent.ACTION_MOVE,x(q,t),y(q,t));
            check(result,"coarse_move_omits_transit_keys","q".equals(editor.getText().toString()));
            event(q,start,start+2,MotionEvent.ACTION_UP,x(q,t),y(q,t));
            check(result,"endpoint_is_selected_without_dictionary","qt".equals(editor.getText().toString())
                && !editor.getText().toString().contains(" ") && clear(keys));

            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            MotionEvent history=MotionEvent.obtain(start,start+1,MotionEvent.ACTION_MOVE,x(q,e),y(q,e),0);
            try {
                history.addBatch(start+2,cx(q),cy(q),1,1,0);
                dispatch(q,history);
            } finally { history.recycle(); }
            check(result,"historical_turn_commits_target_before_release","qe".equals(editor.getText().toString()));
            event(q,start,start+3,MotionEvent.ACTION_UP,cx(q),cy(q));
            check(result,"historical_turn_then_final_endpoint","qeq".equals(editor.getText().toString()));

            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            path(q,t,start,start+1,2);
            pathBack(q,t,start,start+30,2);
            boolean fastLive="qt".equals(editor.getText().toString());
            event(q,start,start+60,MotionEvent.ACTION_UP,cx(q),cy(q));
            String fast=editor.getText().toString();
            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            path(q,t,start,start+1,200);
            pathBack(q,t,start,start+2000,200);
            boolean slowLive="qt".equals(editor.getText().toString());
            event(q,start,start+4000,MotionEvent.ACTION_UP,cx(q),cy(q));
            check(result,"deliberate_turns_are_live_and_speed_independent",fastLive && slowLive
                && "qtq".equals(fast) && fast.equals(editor.getText().toString()));

            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            for(int n=1;n<=24;n++) {
                float progress=n/24f;
                event(q,start,start+n,MotionEvent.ACTION_MOVE,
                    cx(q)+(x(q,t)-cx(q))*progress,cy(q)+(n%2==0?1:-1));
            }
            boolean uncommitted="q".equals(editor.getText().toString());
            event(q,start,start+25,MotionEvent.ACTION_UP,x(q,t),y(q,t));
            check(result,"small_transit_wobbles_do_not_spell_intermediate_keys",uncommitted && "qt".equals(editor.getText().toString()));

            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            float barelyW=x(q,w)-w.getWidth()*.5f+dp(q,2);
            event(q,start,start+1,MotionEvent.ACTION_MOVE,barelyW,cy(q));
            event(q,start,start+2,MotionEvent.ACTION_MOVE,cx(q),cy(q));
            event(q,start,start+3,MotionEvent.ACTION_UP,cx(q),cy(q));
            check(result,"brief_key_edge_crossing_is_not_a_target","q".equals(editor.getText().toString()));

            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            event(q,start,start+1,MotionEvent.ACTION_MOVE,-dp(q,40),-dp(q,40));
            event(q,start,start+2,MotionEvent.ACTION_UP,-dp(q,40),-dp(q,40));
            check(result,"off_keyboard_release_never_guesses_nearest_key","q".equals(editor.getText().toString()) && clear(keys));

            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            event(q,start,start+349,MotionEvent.ACTION_MOVE,cx(q),cy(q));
            boolean stationary="q".equals(editor.getText().toString());
            event(q,start,start+5000,MotionEvent.ACTION_MOVE,cx(q),cy(q));
            check(result,"stationary_hold_never_repeats",stationary && "q".equals(editor.getText().toString()));
            event(q,start,start+5001,MotionEvent.ACTION_UP,cx(q),cy(q));
            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            loop(q,start,start+1,1,true);
            boolean firstLoop="qq".equals(editor.getText().toString());
            loop(q,start,start+100,1,true);
            check(result,"each_deliberate_loop_repeats_letter",firstLoop && "qqq".equals(editor.getText().toString()));
            event(q,start,start+200,MotionEvent.ACTION_UP,cx(q),cy(q));
            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            event(q,start,start+1,MotionEvent.ACTION_MOVE,x(q,w),y(q,w));
            loopAt(q,x(q,w),y(q,w),w,start,start+2,1,true);
            boolean pendingTargetLoop="qww".equals(editor.getText().toString());
            event(q,start,start+30,MotionEvent.ACTION_UP,x(q,w),y(q,w));
            check(result,"loop_selects_pending_target_then_repeats_once",pendingTargetLoop && "qww".equals(editor.getText().toString()));
            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            loop(q,start,start+1,250,false);
            check(result,"loop_is_direction_and_speed_independent","qq".equals(editor.getText().toString()));
            event(q,start,start+5000,MotionEvent.ACTION_UP,cx(q),cy(q));
            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            for(int n=0;n<16;n++) event(q,start,start+n+1,MotionEvent.ACTION_MOVE,cx(q)+(n%2==0?1:-1),cy(q));
            event(q,start,start+20,MotionEvent.ACTION_UP,cx(q),cy(q));
            check(result,"small_finger_jitter_never_repeats","q".equals(editor.getText().toString()));
            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            event(q,start,start+1,MotionEvent.ACTION_MOVE,x(q,w),y(q,w));
            event(q,start,start+2,MotionEvent.ACTION_MOVE,cx(q),cy(q));
            event(q,start,start+3,MotionEvent.ACTION_UP,cx(q),cy(q));
            check(result,"leave_and_return_repeats_letter","qwq".equals(editor.getText().toString()));

            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            event(q,start,start+1,MotionEvent.ACTION_MOVE,x(q,e),y(q,e));
            event(q,start,start+2,MotionEvent.ACTION_CANCEL,x(q,e),y(q,e));
            event(q,start,start+3,MotionEvent.ACTION_UP,x(q,e),y(q,e));
            check(result,"cancel_never_commits_unconfirmed_endpoint","q".equals(editor.getText().toString()) && clear(keys));
            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            event(q,start,start+1,MotionEvent.ACTION_MOVE,x(q,t),y(q,t));
            event(q,start,start+2,MotionEvent.ACTION_MOVE,cx(q),cy(q));
            event(q,start,start+3,MotionEvent.ACTION_CANCEL,cx(q),cy(q));
            check(result,"cancel_preserves_already_confirmed_turn","qt".equals(editor.getText().toString()) && clear(keys));

            editor.setText("");int before=transitions[0];
            pointers(q,MotionEvent.ACTION_DOWN,new int[]{7},new float[]{cx(q)},new float[]{cy(q)});
            pointers(w,MotionEvent.ACTION_DOWN,new int[]{11},new float[]{cx(w)},new float[]{cy(w)});
            boolean overlap="qw".equals(editor.getText().toString()) && keys.hasPendingPress()
                && ((Number)keys.state().get("active_presses")).intValue()==2;
            pointers(q,MotionEvent.ACTION_UP,new int[]{7},new float[]{cx(q)},new float[]{cy(q)});
            overlap &= w.isPressed() && keys.hasPendingPress() && gesture[0] && transitions[0]==before+1;
            pointers(w,MotionEvent.ACTION_UP,new int[]{11},new float[]{cx(w)},new float[]{cy(w)});
            check(result,"two_thumb_release_keeps_other_pause",overlap && "qw".equals(editor.getText().toString())
                && clear(keys) && !gesture[0] && transitions[0]==before+2);
            editor.setText("");
            pointers(q,MotionEvent.ACTION_DOWN,new int[]{7},new float[]{cx(q)},new float[]{cy(q)});
            pointers(w,MotionEvent.ACTION_DOWN,new int[]{11},new float[]{cx(w)},new float[]{cy(w)});
            pointers(q,MotionEvent.ACTION_CANCEL,new int[]{7},new float[]{cx(q)},new float[]{cy(q)});
            boolean independent=w.isPressed() && keys.hasPendingPress() && gesture[0];
            pointers(w,MotionEvent.ACTION_UP,new int[]{11},new float[]{cx(w)},new float[]{cy(w)});
            check(result,"cancel_one_thumb_retains_other",independent && "qw".equals(editor.getText().toString()) && clear(keys));
            editor.setText("");
            pointers(q,MotionEvent.ACTION_DOWN,new int[]{7},new float[]{cx(q)},new float[]{cy(q)});
            pointers(q,MotionEvent.ACTION_UP,new int[]{11},new float[]{cx(q)},new float[]{cy(q)});
            check(result,"wrong_pointer_cannot_append","q".equals(editor.getText().toString()) && clear(keys));

            editor.setText("A😀B");editor.setSelection(3);keys.testAction("erase");
            check(result,"explicit_erase_preserves_emoji","AB".equals(editor.getText().toString()) && editor.getSelectionStart()==1);
            editor.setText("A👨‍👩‍👧‍👦B");editor.setSelection(editor.length()-1);keys.testAction("erase");
            check(result,"explicit_erase_preserves_joined_emoji","AB".equals(editor.getText().toString()));
            editor.setText("Ae\u0301B");editor.setSelection(3);keys.testAction("erase");
            check(result,"explicit_erase_preserves_combining_character","AB".equals(editor.getText().toString()));
            editor.setText("A😀B");editor.setSelection(2);keys.testAction("erase");
            check(result,"explicit_erase_handles_partial_surrogate","AB".equals(editor.getText().toString()));
            editor.setText("A😀B");editor.setSelection(2,3);keys.testAction("erase");
            check(result,"explicit_erase_expands_unicode_selection","AB".equals(editor.getText().toString()));
            editor.setText("");keys.testAction("space");keys.testAction("newline");
            check(result,"space_and_newline_are_explicit"," \n".equals(editor.getText().toString()));
            editor.setText("");keys.testAction("uppercase");layout(keys,1080,1400);keys.testPress("q");keys.testPress("w");
            check(result,"explicit_uppercase_is_one_character","Qw".equals(editor.getText().toString()));
            keys.restore(Json.map("symbols",true));layout(keys,1080,1400);editor.setText("");keys.testPress("?");
            check(result,"symbols_restored","?".equals(editor.getText().toString()));
            keys.restore(Json.map("symbols",false));layout(keys,1080,1400);q=keys.findViewWithTag("q");

            editor.setText("");editor.requestFocus();
            editor.onKeyDown(KeyEvent.KEYCODE_Z,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_Z));
            editor.onKeyUp(KeyEvent.KEYCODE_Z,new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_Z));
            check(result,"hardware_input_without_system_ime","z".equals(editor.getText().toString()) && !editor.getShowSoftInputOnFocus());
            Map<String,Object> saved=keys.handoff();
            restored=new ForgeKeyboard(activity,new EditText(activity),null);restored.restore(saved);
            check(result,"handoff",Json.write(saved).equals(Json.write(restored.handoff())));
            layout(restored,1080,300);check(result,"small_window_height",restored.getMeasuredHeight()<=168);
            KeyboardDock rotating=new KeyboardDock(activity,restored);
            rotating.addView(new View(activity),new LinearLayout.LayoutParams(-1,dp(restored,48)));
            rotating.addView(restored,new LinearLayout.LayoutParams(-1,-2));
            rotating.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.AT_MOST));
            int portraitHeight=restored.getMeasuredHeight();
            rotating.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.AT_MOST));
            check(result,"landscape_keyboard_uses_current_viewport",restored.getMeasuredHeight()==Math.min(dp(restored,210),204));
            rotating.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.AT_MOST));
            check(result,"portrait_keyboard_recovers_before_layout",restored.getMeasuredHeight()==portraitHeight && portraitHeight==Math.min(dp(restored,210),680));
            rotating.removeView(restored);
            check(result,"lifecycle_retains_stream_and_retires_touch",lifecycle(activity));
            Map<String,Object> exercisedKeyboard=keys.state();
            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));keys.close();
            event(q,start,start+1,MotionEvent.ACTION_UP,cx(q),cy(q));q.performClick();
            check(result,"close_stops_callbacks","q".equals(editor.getText().toString()) && clear(keys) && !keys.testPress("q"));
            check(result,"no_model_send_controls",sent[0]==0 && !keys.testPress("Send"));
            Map<String,Object> nativeStats=NativeSwipe.stats();
            check(result,"native_calls_executed",((Number)nativeStats.get("steps")).longValue()>0 && "".equals(nativeStats.get("error")));
            result.put("native_input",nativeStats);
            result.put("checks",result.size()-4);result.put("keyboard",exercisedKeyboard);result.put("closed_keyboard",keys.state());
            return result;
        } finally { keys.close();if(restored!=null) restored.close(); }
    }

    /** Only isolated test keys are attached briefly, so real detach callbacks execute. */
    private static boolean lifecycle(Activity activity) {
        View decor=activity.getWindow().getDecorView();
        if(!(decor instanceof ViewGroup)) return false;
        EditText editor=new EditText(activity);ForgeKeyboard keys=new ForgeKeyboard(activity,editor,null);
        ViewGroupOverlay overlay=((ViewGroup)decor).getOverlay();boolean attached=false;
        try {
            layout(keys,1080,1400);overlay.add(keys);attached=true;
            View q=keys.findViewWithTag("q");long start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));
            boolean initial=keys.isAttachedToWindow() && "q".equals(editor.getText().toString());
            overlay.remove(keys);attached=false;
            event(q,start,start+1,MotionEvent.ACTION_UP,cx(q),cy(q));
            boolean detached=clear(keys) && !keys.isAttachedToWindow() && "q".equals(editor.getText().toString());
            overlay.add(keys);attached=true;editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));keys.setVisibility(View.INVISIBLE);
            event(q,start,start+1,MotionEvent.ACTION_UP,cx(q),cy(q));
            boolean hidden=clear(keys) && "q".equals(editor.getText().toString());
            keys.setVisibility(View.VISIBLE);editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));keys.onWindowFocusChanged(false);
            event(q,start,start+1,MotionEvent.ACTION_UP,cx(q),cy(q));
            boolean unfocused=clear(keys) && "q".equals(editor.getText().toString());
            editor.setText("");start=SystemClock.uptimeMillis();
            event(q,start,start,MotionEvent.ACTION_DOWN,cx(q),cy(q));keys.restore(keys.handoff());
            event(q,start,start+1,MotionEvent.ACTION_UP,cx(q),cy(q));
            boolean restore=clear(keys) && "q".equals(editor.getText().toString());
            return initial&&detached&&hidden&&unfocused&&restore;
        } finally { keys.close();if(attached) overlay.remove(keys); }
    }

    private static void check(Map<String,Object> result,String name,boolean ok) {
        result.put(name,ok);if(!ok) result.put("ok",false);
    }
    private static boolean clear(ForgeKeyboard keys) {
        Map<String,Object> state=keys.state();
        return !keys.hasPendingPress() && ((Number)state.get("active_presses")).intValue()==0
            && ((Number)state.get("active_pointer")).intValue()==-1 && Boolean.FALSE.equals(state.get("gesture_active"));
    }
    private static void layout(ForgeKeyboard keys,int width,int windowHeight) {
        keys.adaptHeight(windowHeight);
        keys.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(windowHeight,View.MeasureSpec.AT_MOST));
        keys.layout(0,0,width,keys.getMeasuredHeight());
    }
    private static int dp(View view,int value) { return (int)(value*view.getResources().getDisplayMetrics().density+.5f); }
    private static float cx(View key) { return key.getWidth()*.5f; }
    private static float cy(View key) { return key.getHeight()*.5f; }
    private static float x(View from,View to) {
        View a=(View)from.getParent(),b=(View)to.getParent();
        return to.getLeft()+b.getLeft()+cx(to)-from.getLeft()-a.getLeft();
    }
    private static float y(View from,View to) {
        View a=(View)from.getParent(),b=(View)to.getParent();
        return to.getTop()+b.getTop()+cy(to)-from.getTop()-a.getTop();
    }
    private static void event(View key,long down,long time,int action,float x,float y) {
        MotionEvent event=MotionEvent.obtain(down,time,action,x,y,0);
        try { dispatch(key,event); } finally { event.recycle(); }
    }
    private static void loop(View key,long down,long time,int stepMs,boolean clockwise) {
        loopAt(key,cx(key),cy(key),key,down,time,stepMs,clockwise);
    }
    private static void loopAt(View owner,float centerX,float centerY,View target,long down,long time,int stepMs,boolean clockwise) {
        float radius=Math.min(target.getWidth(),target.getHeight())*.30f;
        for(int n=0;n<=16;n++) {
            double angle=(clockwise?1:-1)*Math.PI*2*n/16;
            event(owner,down,time+(long)n*stepMs,MotionEvent.ACTION_MOVE,
                centerX+(float)Math.cos(angle)*radius,centerY+(float)Math.sin(angle)*radius);
        }
        event(owner,down,time+17L*stepMs,MotionEvent.ACTION_MOVE,centerX,centerY);
    }
    private static void path(View owner,View target,long down,long time,int stepMs) {
        for(int n=1;n<=8;n++) {
            float progress=n/8f;
            event(owner,down,time+(long)n*stepMs,MotionEvent.ACTION_MOVE,
                cx(owner)+(x(owner,target)-cx(owner))*progress,
                cy(owner)+(y(owner,target)-cy(owner))*progress);
        }
    }
    private static void pathBack(View owner,View target,long down,long time,int stepMs) {
        for(int n=1;n<=8;n++) {
            float progress=n/8f;
            event(owner,down,time+(long)n*stepMs,MotionEvent.ACTION_MOVE,
                x(owner,target)+(cx(owner)-x(owner,target))*progress,
                y(owner,target)+(cy(owner)-y(owner,target))*progress);
        }
    }
    private static void dispatch(View key,MotionEvent event) {
        // Closed/hidden views may reject a stale event; they must not append text.
        key.dispatchTouchEvent(event);
    }
    private static void pointers(View key,int action,int[] ids,float[] x,float[] y) {
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coordinates=new MotionEvent.PointerCoords[ids.length];
        for(int n=0;n<ids.length;n++) {
            properties[n]=new MotionEvent.PointerProperties();properties[n].id=ids[n];properties[n].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coordinates[n]=new MotionEvent.PointerCoords();coordinates[n].x=x[n];coordinates[n].y=y[n];coordinates[n].pressure=1;coordinates[n].size=1;
        }
        long now=SystemClock.uptimeMillis();
        MotionEvent event=MotionEvent.obtain(now,now,action,ids.length,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try { dispatch(key,event); } finally { event.recycle(); }
    }
}
