package com.trough.hot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import java.util.Map;

/** The pause before sending, drawn inside the editor without another control row. */
final class PauseEditor extends EditText {
    private final Paint countdownPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float barWidth;
    private final float density;
    private long beganUptime, durationMs;
    private long pulseBeganUptime, pulseUntilUptime;
    private boolean counting, attached;
    private float energy=.5f,pulseHeat=.5f;
    private int adaptiveSignature=-1,accent=0xffa7b099;

    PauseEditor(Context context) {
        super(context);
        density=getResources().getDisplayMetrics().density;
        barWidth = 3f * density;
        countdownPaint.setColor(accent);
        countdownPaint.setStrokeWidth(barWidth);
        countdownPaint.setStrokeCap(Paint.Cap.ROUND);
        pulsePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Shared controller style; bucket changes rather than redraw-frame changes. */
    void applyAdaptive(Map<String,Object> state) {
        if(state==null) return;
        Object raw=state.get("values");
        Map<?,?> values=raw instanceof Map?(Map<?,?>)raw:state;
        int warmth=bucket(values,"warmth"),strength=bucket(values,"energy"),scale=bucket(values,"text_scale"),contrast=bucket(values,"contrast");
        int signature=warmth|(strength<<4)|(scale<<8)|(contrast<<12);
        if(signature==adaptiveSignature) return;
        adaptiveSignature=signature;
        energy=strength/15f;
        accent=blend(0xff77b6ad,0xffd7aa73,warmth/15f);
        countdownPaint.setColor(accent);
        int text=blend(0xffc6d2ce,0xfff4f5e9,contrast/15f);
        if(getCurrentTextColor()!=text) setTextColor(text);
        setHintTextColor(blend(0xff7e9790,0xffaebbb1,contrast/15f));
        float textSp=12f+5f*(scale/15f);
        if(Math.abs(getTextSize()-getResources().getDisplayMetrics().scaledDensity*textSp)>.01f) setTextSize(textSp);
        if(getBackground() instanceof GradientDrawable) {
            ((GradientDrawable)getBackground()).setStroke(Math.max(1,Math.round(density)),(accent&0x00ffffff)|((22+contrast*2)<<24));
        }
        invalidateBar();
    }

    /** Immediate local feedback for editing; never waits for model inference. */
    void typingPulse(int count) {
        long now=SystemClock.uptimeMillis();
        pulseBeganUptime=now;
        pulseUntilUptime=now+420;
        pulseHeat=Math.min(1f,.25f+Math.max(1,Math.min(8,count))*.09f+energy*.25f);
        invalidateBar();
    }

    private static int bucket(Map<?,?> values,String key) {
        Object raw=values.get(key);
        float value=raw instanceof Number?((Number)raw).floatValue():.5f;
        if(Float.isNaN(value) || Float.isInfinite(value)) value=.5f;
        return Math.round(Math.max(0f,Math.min(1f,value))*15f);
    }

    private static int blend(int a,int b,float t) {
        return Color.rgb(Math.round(Color.red(a)+(Color.red(b)-Color.red(a))*t),
                Math.round(Color.green(a)+(Color.green(b)-Color.green(a))*t),
                Math.round(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t));
    }

    void countdown(long beganUptime, long durationMs) {
        if (durationMs <= 0) { clearCountdown(); return; }
        this.beganUptime = beganUptime;
        this.durationMs = durationMs;
        counting = true;
        invalidateBar();
    }

    void clearCountdown() {
        if (!counting) return;
        counting = false;
        invalidateBar();
    }

    private int barTop() {
        return Math.max(0, getHeight() - (int) Math.ceil(18f*density) - 2);
    }

    private void invalidateBar() {
        invalidate(0, barTop(), getWidth(), getHeight());
    }

    private boolean canAnimate() {
        return attached && getWindowVisibility() == VISIBLE
                && isShown() && hasWindowFocus();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!canAnimate()) return;
        long now=SystemClock.uptimeMillis();
        float left = getPaddingLeft();
        float right = Math.max(left, getWidth() - getPaddingRight());
        float y = getHeight() - barWidth;
        if(counting) {
            long remaining=durationMs-(now-beganUptime);
            if(remaining<=0) counting=false;
            else if(right>left) {
                float fraction=Math.min(1f,(float)remaining/durationMs);
                canvas.drawLine(left,y,left+(right-left)*fraction,y,countdownPaint);
            }
        }
        boolean pulsing=now<pulseUntilUptime;
        if(pulsing && right>left) {
            float age=Math.max(0f,Math.min(1f,(now-pulseBeganUptime)/420f));
            float life=1f-age;
            float travel=Math.min(right-left,(30f+36f*pulseHeat)*density);
            float x=left+travel*(1f-life*life);
            float pulseY=y-2f*density;
            float tail=Math.min(x-left,(10f+20f*pulseHeat)*density)*life;
            pulsePaint.setColor(accent);
            pulsePaint.setStrokeWidth(2f*density);
            pulsePaint.setAlpha(Math.round(140f*life));
            canvas.drawLine(x-tail,pulseY,x,pulseY,pulsePaint);
            pulsePaint.setAlpha(Math.round(45f*life));
            canvas.drawCircle(x,pulseY,(4f+2f*pulseHeat)*density*life,pulsePaint);
            pulsePaint.setAlpha(Math.round(240f*life));
            canvas.drawCircle(x,pulseY,(1.5f+pulseHeat)*density*life,pulsePaint);
        }
        if(counting || pulsing) postInvalidateDelayed(33,0,barTop(),getWidth(),getHeight());
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (counting || pulseUntilUptime>SystemClock.uptimeMillis()) invalidateBar();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && (counting || pulseUntilUptime>SystemClock.uptimeMillis())) invalidateBar();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && (counting || pulseUntilUptime>SystemClock.uptimeMillis())) invalidateBar();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && (counting || pulseUntilUptime>SystemClock.uptimeMillis())) invalidateBar();
    }
}
