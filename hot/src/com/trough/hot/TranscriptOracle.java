package com.trough.hot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Map;

/** Isolated real Android layout/draw check; never changes the live transcript or composer. */
final class TranscriptOracle {
    static Map<String,Object> run(Context context) {
        Map<String,Object> result=Json.map("ok",true,"isolated",true);
        ScrollView scroll=new ScrollView(context);
        TranscriptFeed feed=new TranscriptFeed(context);scroll.addView(feed);
        Bitmap image=Bitmap.createBitmap(360,640,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(image);
        try {
            for(int i=0;i<1000;i++) {
                View row=new View(context);row.setBackgroundColor(0xFF000000 | ((i%255+1)<<16));
                feed.addView(row,new android.widget.LinearLayout.LayoutParams(-1,32));
            }
            layout(scroll,360,640);scroll.draw(canvas);
            check(result,"all_history_retained",feed.getChildCount()==1000);
            check(result,"top_window_bounded",number(feed.state(),"recorded_rows")<=32 && number(feed.state(),"first_recorded")==0);
            scroll.scrollTo(0,16000);feed.viewportChanged();scroll.draw(canvas);
            check(result,"middle_window_bounded",number(feed.state(),"recorded_rows")<=43);
            check(result,"middle_window_moves",number(feed.state(),"first_recorded")>400 && number(feed.state(),"last_recorded")<600);
            scroll.scrollTo(0,32000);feed.viewportChanged();scroll.draw(canvas);
            check(result,"bottom_reachable",number(feed.state(),"last_recorded")==999);
            TextView rich=new TextView(context);rich.setText(RichText.render("**Bold** `code` [link](https://example.com)"));rich.setTextIsSelectable(true);
            feed.addView(rich,new android.widget.LinearLayout.LayoutParams(-1,96));layout(scroll,360,640);
            scroll.scrollTo(0,40000);feed.viewportChanged();scroll.draw(canvas);
            check(result,"appended_row_visible",number(feed.state(),"last_recorded")==1000);
            check(result,"native_selection_preserved",rich.isTextSelectable());
            check(result,"rich_link_preserved",rich.getText() instanceof Spanned && ((Spanned)rich.getText()).getSpans(0,rich.length(),URLSpan.class).length==1);
            feed.getChildAt(900).setVisibility(View.GONE);layout(scroll,360,320);scroll.scrollTo(0,16000);feed.viewportChanged();scroll.draw(canvas);
            check(result,"resize_window_bounded",number(feed.state(),"recorded_rows")<=23);
            feed.removeAllViews();layout(scroll,360,640);scroll.draw(canvas);
            check(result,"empty_feed",number(feed.state(),"recorded_rows")==0);
            result.put("checks",10);result.put("history_rows_tested",1001);
        } catch(Throwable error) { result.put("ok",false);result.put("error",error.toString()); }
        finally { image.recycle(); }
        return result;
    }
    private static long number(Map<String,Object> m,String key) { return ((Number)m.get(key)).longValue(); }
    private static void check(Map<String,Object> out,String name,boolean value) { out.put(name,value);if(!value) throw new AssertionError(name); }
    private static void layout(View view,int width,int height) { view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height); }
}
