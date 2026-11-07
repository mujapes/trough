package com.trough.hot;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.Map;

/** Retains native rich-text/selection views but records only the current scroll window. */
final class TranscriptFeed extends LinearLayout {
    private int drawn,first=-1,last=-1;
    private long frames,lastDrawNs,maxDrawNs;
    TranscriptFeed(Context context) { super(context);setOrientation(VERTICAL); }

    /** Parent scrolling doesn't otherwise dirty this child's cached hardware display list. */
    void viewportChanged() { invalidate(); }

    @Override protected void dispatchDraw(Canvas canvas) {
        long started=System.nanoTime();
        drawn=0;first=-1;last=-1;
        if(!(getParent() instanceof ScrollView)) {
            super.dispatchDraw(canvas);drawn=getChildCount();
        } else {
            ScrollView viewport=(ScrollView)getParent();
            int height=viewport.getHeight();
            if(height>0) {
                // RecordingCanvas bounds cover the entire tall feed, not the parent's viewport.
                // Use actual scroll geometry, with half a screen spare for fast scrolling.
                int spare=Math.max(64,height/2);
                long top=(long)viewport.getScrollY()-getTop()-spare;
                long bottom=(long)viewport.getScrollY()-getTop()+height+spare;
                long when=getDrawingTime();
                int saved=canvas.save();
                if(getClipToPadding()) canvas.clipRect(getPaddingLeft(),getPaddingTop(),
                    getWidth()-getPaddingRight(),getHeight()-getPaddingBottom());
                for(int i=0,n=getChildCount();i<n;i++) {
                    View child=getChildAt(i);
                    if(child.getVisibility()!=View.VISIBLE || child.getBottom()<top || child.getTop()>bottom) continue;
                    if(first<0) first=i;last=i;drawn++;
                    drawChild(canvas,child,when);
                }
                canvas.restoreToCount(saved);
            }
        }
        frames++;lastDrawNs=System.nanoTime()-started;maxDrawNs=Math.max(maxDrawNs,lastDrawNs);
    }

    @Override public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        if(getChildCount()==0) { drawn=0;first=-1;last=-1; }
    }

    Map<String,Object> state() {
        return Json.map("mode","viewport_native_views","rows",getChildCount(),"recorded_rows",drawn,
            "first_recorded",first,"last_recorded",last,"frames",frames,
            "last_draw_ns",lastDrawNs,"max_draw_ns",maxDrawNs,"history_preserved",true);
    }
}
