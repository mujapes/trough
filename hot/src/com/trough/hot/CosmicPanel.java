package com.trough.hot;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** A deliberately silent, local word-and-particle toy on Trough's development surface. */
final class CosmicPanel {
    private static final int INK = Color.rgb(7, 11, 22);
    private static final int WHITE = Color.rgb(233, 238, 249);
    private static final int MUTED = Color.rgb(144, 160, 183);
    private static final int GOLD = Color.rgb(255, 202, 127);
    private static final int TEAL = Color.rgb(113, 231, 218);
    private static final int VIOLET = Color.rgb(173, 149, 255);
    private static final String[][] PAIRS = CosmicWords.pairs();
    private static Panel active;

    private CosmicPanel() {}

    static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (active != null && active.activity == activity && active.dialog != null
                && active.dialog.isShowing()) return;
        close();
        Panel next = new Panel(activity, true);
        active = next;
        next.show();
    }

    static void close() {
        Panel old = active;
        active = null;
        if (old != null) old.dismiss();
    }

    static Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        Panel p = active;
        out.put("showing", p != null && p.dialog != null && p.dialog.isShowing());
        if (p != null) {
            out.put("word", p.input.getText().toString());
            out.put("sum", ordinal(p.input.getText().toString()));
            out.put("pair", partner(p.input.getText().toString()));
            out.put("drift", p.sky.drift);
            out.put("paused", p.sky.paused);
            out.put("animating", p.sky.scheduled);
            out.put("touches", p.sky.touches);
            out.put("mode", p.sky.modeName());
            out.put("palette", p.sky.paletteName());
            out.put("game", p.sky.gameState());
            out.put("prompt", p.input.getText().toString());
        }
        return out;
    }

    /** ASCII English ordinal; punctuation, digits and non-ASCII letters contribute zero. */
    static int ordinal(String text) {
        int sum = 0;
        if (text == null) return sum;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z') sum += c - 'a' + 1;
            else if (c >= 'A' && c <= 'Z') sum += c - 'A' + 1;
        }
        return sum;
    }

    private static String letters(String text) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z') s.append((char) (c - 'a' + 'A'));
            else if (c >= 'A' && c <= 'Z') s.append(c);
        }
        return s.toString();
    }

    private static String partner(String word) {
        String key = letters(word);
        for (String[] pair : PAIRS) {
            if (key.equals(letters(pair[0]))) return pair[1];
            if (key.equals(letters(pair[1]))) return pair[0];
        }
        List<String> matches = CosmicWords.matches(word);
        return matches.isEmpty() ? "" : matches.get(0);
    }

    private interface MessageSender { Map<String,Object> send(String text); }

    private static int dp(Activity a, float value) {
        return (int) (value * a.getResources().getDisplayMetrics().density + .5f);
    }

    private static GradientDrawable shape(int fill, int edge, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (edge != 0) drawable.setStroke(1, edge);
        return drawable;
    }

    private static final class Panel {
        final Activity activity;
        final boolean persistent;
        final SharedPreferences saved;
        final LinearLayout root;
        final Universe sky;
        final EditText input;
        final TextView breakdown;
        final Button surprise;
        final Button drift;
        final Button pause;
        final Button send;
        final Button mode;
        final Button palette;
        final TextView modelStatus;
        final TextView reply;
        final LinearLayout matches;
        boolean initializing = true;
        String lastReply = "";
        MessageSender sender = new MessageSender() {
            public Map<String,Object> send(String text) { return Main.sendCosmicMessage(text); }
        };
        final Runnable chatTick = new Runnable() {
            public void run() {
                if(disposed || dialog==null || !dialog.isShowing()) return;
                refreshChat();
                if(root.isShown() && root.getWindowVisibility()==View.VISIBLE) root.postDelayed(this,500);
            }
        };
        Dialog dialog;
        boolean disposed;
        int pairIndex;
        final Application.ActivityLifecycleCallbacks lifecycle;

        Panel(final Activity a, boolean persist) {
            activity = a;
            persistent = persist;
            saved = a.getSharedPreferences("trough.cosmic.v1", 0);
            root = new LinearLayout(a) {
                @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
                    super.onSizeChanged(w,h,oldw,oldh);
                    compact(this,h<dp(a,440),h<dp(a,300));
                }
            };
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(INK);
            root.setFocusableInTouchMode(true);

            LinearLayout header = new LinearLayout(a);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(a, 19), dp(a, 9), dp(a, 9), dp(a, 4));
            LinearLayout branding = new LinearLayout(a);
            branding.setOrientation(LinearLayout.VERTICAL);
            TextView title = text("COSMIC / LIVE", 13, WHITE);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setLetterSpacing(.17f);
            branding.addView(title);
            TextView subtitle = text("Play it. Prompt it. Change it.", 11, MUTED);
            subtitle.setTag("cosmic.optional");
            branding.addView(subtitle);
            header.addView(branding, new LinearLayout.LayoutParams(0, -2, 1));
            Button back = button("Back to chat", MUTED);
            back.setContentDescription("Back to Trough chat");
            header.addView(back, new LinearLayout.LayoutParams(-2, dp(a, 42)));
            root.addView(header, new LinearLayout.LayoutParams(-1, -2));

            sky = new Universe(a);
            sky.setContentDescription("Cosmic game. Drag to collect stars and dodge meteors. Prompt Codex below to change the game.");
            sky.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            root.addView(sky, new LinearLayout.LayoutParams(-1, 0, 1));

            LinearLayout controls = new LinearLayout(a);
            controls.setOrientation(LinearLayout.VERTICAL);
            controls.setPadding(dp(a, 18), dp(a, 4), dp(a, 18), dp(a, 13));
            TextView instruction = text("Collect stars · Dodge meteors · Prompt a new rule", 11, MUTED);
            instruction.setTag("cosmic.optional");
            instruction.setGravity(Gravity.CENTER);
            controls.addView(instruction, new LinearLayout.LayoutParams(-1, dp(a, 23)));

            modelStatus = text("Connecting to your model…",11,TEAL);
            controls.addView(modelStatus,new LinearLayout.LayoutParams(-1,dp(a,21)));
            ScrollView answerBox=new ScrollView(a);
            answerBox.setTag("cosmic.answer");
            answerBox.setFillViewport(false);
            reply=text("Messages go directly to your selected model. Ask anything, or ask to change Cosmos.",12,WHITE);
            reply.setTextIsSelectable(true);
            reply.setPadding(dp(a,8),dp(a,4),dp(a,8),dp(a,4));
            answerBox.addView(reply,new ScrollView.LayoutParams(-1,-2));
            controls.addView(answerBox,new LinearLayout.LayoutParams(-1,dp(a,64)));

            input = new EditText(a);
            input.setSingleLine(true);
            input.setTextColor(WHITE);
            input.setHintTextColor(MUTED);
            input.setTextSize(18);
            input.setPadding(dp(a, 14), dp(a, 5), dp(a, 14), dp(a, 5));
            input.setBackground(shape(Color.rgb(16, 25, 40), Color.rgb(45, 61, 79), dp(a, 14)));
            input.setHint("Message your model…");
            input.setContentDescription("Live game prompt. Send or the keyboard send key delivers your message to the selected model.");
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4000)});
            LinearLayout compose=new LinearLayout(a);
            compose.addView(input,new LinearLayout.LayoutParams(0,dp(a,48),1));
            send=button("Send",TEAL);
            compose.addView(send,new LinearLayout.LayoutParams(dp(a,65),dp(a,48)));
            controls.addView(compose,new LinearLayout.LayoutParams(-1,-2));

            breakdown = text("", 11, MUTED);
            breakdown.setGravity(Gravity.CENTER);
            breakdown.setSingleLine(true);
            breakdown.setEllipsize(android.text.TextUtils.TruncateAt.END);
            breakdown.setTag("cosmic.optional");
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(a, 25));
            controls.addView(breakdown, bp);

            HorizontalScrollView collisions=new HorizontalScrollView(a);
            collisions.setTag("cosmic.optional");
            collisions.setHorizontalScrollBarEnabled(false);
            matches=new LinearLayout(a);
            collisions.addView(matches,new HorizontalScrollView.LayoutParams(-2,-1));
            controls.addView(collisions,new LinearLayout.LayoutParams(-1,dp(a,32)));

            LinearLayout actions = new LinearLayout(a);
            actions.setGravity(Gravity.CENTER);
            surprise = button("Surprise me", GOLD);
            drift = button("Drift: off", TEAL);
            pause = button("Pause", VIOLET);
            mode=button("Orbit",TEAL);
            palette=button("Aurora",VIOLET);
            Button save=button("☆ Save",GOLD);
            Button kept=button("Saved",MUTED);
            Button[] buttons = {surprise, drift, pause,mode,palette,save,kept};
            for (int i = 0; i < buttons.length; i++) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(a,94), dp(a, 43));
                if (i > 0) p.leftMargin = dp(a, 7);
                actions.addView(buttons[i], p);
            }
            HorizontalScrollView toolbar=new HorizontalScrollView(a);
            toolbar.setTag("cosmic.toolbar");
            toolbar.setHorizontalScrollBarEnabled(false);
            toolbar.addView(actions,new HorizontalScrollView.LayoutParams(-2,-2));
            controls.addView(toolbar,new LinearLayout.LayoutParams(-1,-2));
            root.addView(controls, new LinearLayout.LayoutParams(-1, -2));

            sky.onCycle = new Runnable() {
                @Override public void run() { if(!input.hasFocus()) nextPair(); }
            };
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    updateWord(s.toString());
                }
                @Override public void afterTextChanged(Editable e) {}
            });
            input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override public boolean onEditorAction(TextView v, int action, KeyEvent event) {
                    if (action == EditorInfo.IME_ACTION_SEND || (event!=null && event.getKeyCode()==KeyEvent.KEYCODE_ENTER)) {
                        if(event==null || event.getAction()==KeyEvent.ACTION_DOWN) sendMessage();
                        return true;
                    }
                    return false;
                }
            });
            send.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { sendMessage(); } });
            input.setOnFocusChangeListener(new View.OnFocusChangeListener() { public void onFocusChange(View v,boolean focus) {
                if(focus) { sky.drift=false; updateButtons(); persist(); }
            }});
            mode.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                sky.setMode(sky.mode+1); updateButtons(); persist();
            }});
            palette.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                sky.setPalette(sky.palette+1); updateButtons(); persist();
            }});
            save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveWord(); } });
            kept.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showSaved(); } });
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dismiss(); }
            });
            surprise.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    nextPair();
                    sky.burst();
                }
            });
            drift.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sky.drift = !sky.drift;
                    sky.cycleTime = 0;
                    updateButtons();
                    persist();
                }
            });
            pause.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sky.setPaused(!sky.paused);
                    updateButtons();
                    persist();
                }
            });
            String word = persistent ? saved.getString("word", "MOON") : "MOON";
            sky.drift = persistent && saved.getBoolean("drift", false);
            sky.setPaused(persistent && saved.getBoolean("paused", false));
            pairIndex = persistent ? Math.floorMod(saved.getInt("pair", 0), PAIRS.length) : 0;
            sky.setMode(persistent?saved.getInt("mode",0):0);
            sky.setPalette(persistent?saved.getInt("palette",0):0);
            if(persistent) sky.restoreGame(saved);
            input.setText(persistent?saved.getString("prompt",""):"");
            sky.setWord(word,partner(word));
            input.setSelection(input.length());
            input.clearFocus();
            root.requestFocus();
            updateButtons();
            initializing=false;

            lifecycle = new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity x, Bundle b) {}
                @Override public void onActivityStarted(Activity x) {}
                @Override public void onActivityResumed(Activity x) {
                    if (x == activity) { sky.setHostActive(true); root.removeCallbacks(chatTick); root.post(chatTick); }
                }
                @Override public void onActivityPaused(Activity x) {
                    if (x == activity) { sky.setHostActive(false); root.removeCallbacks(chatTick); persist(); }
                }
                @Override public void onActivityStopped(Activity x) {
                    if (x == activity) { sky.setHostActive(false); root.removeCallbacks(chatTick); }
                }
                @Override public void onActivitySaveInstanceState(Activity x, Bundle b) {
                    if (x == activity) persist();
                }
                @Override public void onActivityDestroyed(Activity x) {
                    if (x == activity) dismiss();
                }
            };
        }

        TextView text(String value, float size, int color) {
            TextView t = new TextView(activity);
            t.setText(value);
            t.setTextSize(size);
            t.setTextColor(color);
            t.setIncludeFontPadding(false);
            return t;
        }

        Button button(String label, int color) {
            Button b = new Button(activity);
            b.setAllCaps(false);
            b.setText(label);
            b.setTextSize(12);
            b.setTextColor(color);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setPadding(dp(activity, 7), 0, dp(activity, 7), 0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setBackground(shape(Color.rgb(18, 26, 43), Color.rgb(42, 55, 75), dp(activity, 12)));
            return b;
        }

        void show() {
            dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(root);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface ignored) { dispose(); }
            });
            activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setStatusBarColor(INK);
                window.setNavigationBarColor(INK);
            }
            dialog.show();
            if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            refreshChat();
            root.post(chatTick);
        }

        void refreshChat() {
            Map<String,Object> state = Main.cosmicChatState();
            modelStatus.setText(String.valueOf(state.get("selected_agent")) + " · "
                    + String.valueOf(state.get("model")) + " · " + String.valueOf(state.get("status")));
            send.setEnabled(Boolean.TRUE.equals(state.get("ready")));
            String answer = String.valueOf(state.get("assistant_text"));
            if (!answer.isEmpty() && !answer.equals(lastReply)) {
                lastReply = answer;
                reply.setText(answer);
            }
        }

        void sendMessage() {
            String message = input.getText().toString();
            if (message.trim().isEmpty()) return;
            Map<String,Object> result = sender.send(message);
            if (Boolean.TRUE.equals(result.get("accepted"))) {
                input.setText("");
                persist();
                refreshChat();
            } else modelStatus.setText(String.valueOf(result.get("error")));
        }

        void compact(View view, boolean small, boolean tiny) {
            if ("cosmic.optional".equals(view.getTag())) view.setVisibility(small ? View.GONE : View.VISIBLE);
            if ("cosmic.toolbar".equals(view.getTag())) view.setVisibility(tiny ? View.GONE : View.VISIBLE);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup)view;
                for (int i=0;i<group.getChildCount();i++) compact(group.getChildAt(i),small,tiny);
            }
        }

        void saveWord() {
            String word = sky.word;
            java.util.Set<String> kept = new java.util.HashSet<String>(saved.getStringSet("kept", new java.util.HashSet<String>()));
            kept.add(word);
            saved.edit().putStringSet("kept", kept).apply();
            modelStatus.setText("Saved " + word);
        }

        void showSaved() {
            final String[] kept = saved.getStringSet("kept", new java.util.HashSet<String>()).toArray(new String[0]);
            java.util.Arrays.sort(kept);
            new android.app.AlertDialog.Builder(activity).setTitle("Saved words").setItems(kept,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) { updateWord(kept[which]); }
                }).setNegativeButton("Close", null).show();
        }

        void updateWord(String word) {
            sky.setWord(word, partner(word));
            String clean = letters(word);
            StringBuilder terms = new StringBuilder();
            if (clean.length() > 0 && clean.length() <= 10) {
                for (int i = 0; i < clean.length(); i++) {
                    if (i > 0) terms.append(" + ");
                    terms.append(clean.charAt(i) - 'A' + 1);
                }
                terms.append(" = ").append(ordinal(word));
            } else terms.append("English ordinal  ·  A=1 … Z=26  ·  ASCII letters only");
            breakdown.setText(terms.toString());
            persist();
        }

        void nextPair() {
            pairIndex = (pairIndex + 1) % PAIRS.length;
            updateWord(PAIRS[pairIndex][0]);
            sky.cycleTime = 0;
            persist();
        }

        void updateButtons() {
            drift.setText(sky.drift ? "Drift: on" : "Drift: off");
            drift.setContentDescription(sky.drift ? "Drift on. Change word pair every eight seconds. Tap to stop." : "Start drift. Change word pair every eight seconds.");
            mode.setText(sky.modeName());
            palette.setText(sky.paletteName());
            pause.setText(sky.paused ? "Resume" : "Pause");
            pause.setContentDescription(sky.paused ? "Resume cosmic motion" : "Pause cosmic motion and drift");
        }

        void persist() {
            if (!persistent || disposed || initializing) return;
            saved.edit().putString("word", sky.word).putString("prompt", input.getText().toString())
                    .putInt("mode", sky.mode).putInt("palette", sky.palette)
                    .putBoolean("drift", sky.drift).putBoolean("paused", sky.paused)
                    .putInt("pair", pairIndex).apply();
        }

        void hideKeyboard() {
            InputMethodManager ime = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }

        void dismiss() {
            if (disposed) return;
            persist();
            if (dialog != null && dialog.isShowing()) {
                hideKeyboard();
                dialog.dismiss();
            } else dispose();
        }

        void dispose() {
            if (disposed) return;
            persist();
            disposed = true;
            root.removeCallbacks(chatTick);
            sky.dispose();
            activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
            if (active == this) active = null;
        }
    }

    private static final class Universe extends View {
        private static final int COUNT = 92;
        private static final float TWO_PI = (float) (Math.PI * 2);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint display = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF oval = new RectF();
        final float[] x = new float[COUNT], y = new float[COUNT];
        final float[] vx = new float[COUNT], vy = new float[COUNT];
        final float[] starX = new float[110], starY = new float[110], starR = new float[110];
        final float[] oldX = new float[COUNT], oldY = new float[COUNT];
        final float density;
        Shader background;
        Shader glow;
        String word = "MOON", other = "GLOW", glyphs = "MOON";
        int sum = 57;
        int mode = 0, palette = 0;
        int primary = TEAL, secondary = VIOLET, accent = GOLD;
        float time, cycleTime, impulse, touchX, touchY, ripple = 2;
        boolean touching, drift, paused, scheduled, attached, hostActive = true, disposed;
        long lastFrame;
        int touches;
        private static final int PICKUPS = 5, METEORS = 4;
        final float[] pickupX = new float[PICKUPS], pickupY = new float[PICKUPS];
        final float[] meteorX = new float[METEORS], meteorY = new float[METEORS];
        final float[] meteorSpeed = new float[METEORS], meteorSide = new float[METEORS];
        float playerX = .5f, playerY = .78f, gameClock, grace, comboClock, collectionGlow;
        int score, bestScore, shields = 3, combo = 1, gameSeed = 5713852;
        boolean gameOver;
        Runnable onCycle;
        final Runnable frame = new Runnable() {
            @Override public void run() {
                scheduled = false;
                if (!canAnimate()) return;
                long now = SystemClock.uptimeMillis();
                if (lastFrame == 0) lastFrame = now;
                float dt = Math.min(.05f, (now - lastFrame) / 1000f);
                if (dt >= .027f) {
                    advance(dt);
                    lastFrame = now;
                    invalidate();
                }
                schedule();
            }
        };

        Universe(Activity activity) {
            super(activity);
            density = activity.getResources().getDisplayMetrics().density;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setFocusable(true);
            setClickable(true);
            mono.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            display.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
            Random r = new Random(5713852);
            for (int i = 0; i < starX.length; i++) {
                starX[i] = r.nextFloat();
                starY[i] = r.nextFloat();
                starR[i] = .25f + r.nextFloat() * .8f;
            }
            for (int i = 0; i < COUNT; i++) {
                float angle = r.nextFloat() * TWO_PI;
                float radius = .18f + r.nextFloat() * .27f;
                x[i] = .5f + (float) Math.cos(angle) * radius;
                y[i] = .5f + (float) Math.sin(angle) * radius;
                oldX[i] = x[i]; oldY[i] = y[i];
                vx[i] = -(float) Math.sin(angle) * .022f;
                vy[i] = (float) Math.cos(angle) * .022f;
            }
            restartGame();
        }

        void setWord(String text, String pair) {
            word = text.trim();
            other = pair;
            glyphs = letters(text);
            sum = ordinal(text);
            String name = word.length() == 0 ? "No word entered" : word;
            setContentDescription(name + ", English ordinal " + sum
                    + (pair.length() == 0 ? "" : ". " + pair + " also equals " + sum)
                    + ". Drag your diamond to collect cyan stars and dodge orange meteors. Three shields. Tap after a loss to restart.");
            impulse = .9f;
            invalidate();
        }

        void burst() {
            impulse = 2;
            ripple = 0;
            touchX = .5f;
            touchY = .5f;
            invalidate();
        }

        void setMode(int value) {
            mode = Math.max(0, Math.min(2, value));
            for (int i = 0; i < COUNT; i++) {
                float dx = x[i] - .5f, dy = y[i] - .5f;
                float length = Math.max(.001f, (float) Math.sqrt(dx * dx + dy * dy));
                vx[i] = mode == 2 ? dx / length * .045f : -dy / length * .022f;
                vy[i] = mode == 2 ? dy / length * .045f : dx / length * .022f;
                oldX[i] = x[i]; oldY[i] = y[i];
            }
            invalidate();
        }

        void setPalette(int value) {
            palette = Math.max(0, Math.min(2, value));
            primary = palette == 1 ? Color.rgb(255, 157, 105)
                    : palette == 2 ? Color.rgb(125, 230, 255) : TEAL;
            secondary = palette == 1 ? Color.rgb(244, 117, 175)
                    : palette == 2 ? Color.rgb(126, 160, 255) : VIOLET;
            accent = palette == 1 ? Color.rgb(255, 216, 144)
                    : palette == 2 ? Color.rgb(212, 241, 255) : GOLD;
            updateShaders(getWidth(), getHeight());
            invalidate();
        }

        String modeName() { return mode == 1 ? "Nebula" : mode == 2 ? "Warp" : "Orbit"; }
        String paletteName() { return palette == 1 ? "Ember" : palette == 2 ? "Ice" : "Aurora"; }
        private int alpha(int color, int opacity) { return (color & 0xffffff) | (opacity << 24); }

        private float gameRandom() {
            gameSeed = gameSeed * 1664525 + 1013904223;
            return (gameSeed >>> 8) / 16777216f;
        }

        private void spawnPickup(int i, boolean initial) {
            pickupX[i] = .10f + gameRandom() * .8f;
            pickupY[i] = initial ? .23f + gameRandom() * .43f : -.08f - gameRandom() * .35f;
        }

        private void spawnMeteor(int i, boolean initial) {
            meteorX[i] = .09f + gameRandom() * .82f;
            meteorY[i] = initial ? -.12f - i * .24f : -.12f - gameRandom() * .55f;
            meteorSpeed[i] = .085f + gameRandom() * .055f;
            meteorSide[i] = (gameRandom() - .5f) * .075f;
        }

        void restartGame() {
            score = 0; shields = 3; combo = 1; gameClock = 0;
            grace = 1.2f; comboClock = 0; collectionGlow = 0;
            playerX = .5f; playerY = .78f; gameOver = false;
            gameSeed = 5713852;
            for (int i = 0; i < PICKUPS; i++) spawnPickup(i, true);
            for (int i = 0; i < METEORS; i++) spawnMeteor(i, true);
            invalidate();
        }

        private float distanceToPlayer(float px, float py) {
            float w = getWidth() > 0 ? getWidth() : 345;
            float h = getHeight() > 0 ? getHeight() : 400;
            float size = Math.min(w, h);
            float dx = (px - playerX) * w / size, dy = (py - playerY) * h / size;
            return dx * dx + dy * dy;
        }

        private void advanceGame(float dt) {
            if (gameOver) return;
            gameClock += dt;
            grace = Math.max(0, grace - dt);
            collectionGlow = Math.max(0, collectionGlow - dt);
            comboClock = Math.max(0, comboClock - dt);
            if (comboClock == 0) combo = 1;
            float difficulty = Math.min(.11f, gameClock * .0011f);
            for (int i = 0; i < PICKUPS; i++) {
                pickupY[i] += (.044f + i * .004f) * dt;
                if (distanceToPlayer(pickupX[i], pickupY[i]) < .0025f) {
                    score = Math.min(999999999, score + 10 * combo);
                    bestScore = Math.max(bestScore, score);
                    combo = Math.min(9, combo + 1); comboClock = 4; collectionGlow = .55f;
                    spawnPickup(i, false);
                } else if (pickupY[i] > .95f) spawnPickup(i, false);
            }
            for (int i = 0; i < METEORS; i++) {
                meteorY[i] += (meteorSpeed[i] + difficulty) * dt;
                meteorX[i] += meteorSide[i] * dt;
                if (meteorX[i] < .04f || meteorX[i] > .96f) meteorSide[i] = -meteorSide[i];
                meteorX[i] = Math.max(.04f, Math.min(.96f, meteorX[i]));
                if (grace == 0 && distanceToPlayer(meteorX[i], meteorY[i]) < .003f) {
                    shields--; combo = 1; comboClock = 0; grace = 1.5f;
                    spawnMeteor(i, false);
                    if (shields <= 0) { shields = 0; gameOver = true; break; }
                } else if (meteorY[i] > 1.08f) spawnMeteor(i, false);
            }
        }

        private float bounded(float value, float low, float high, float fallback) {
            return Float.isNaN(value) || Float.isInfinite(value) ? fallback : Math.max(low, Math.min(high, value));
        }

        void saveGame(SharedPreferences.Editor editor) {
            editor.putInt("cosmic.game.version", 1).putInt("cosmic.game.score", score)
                    .putInt("cosmic.game.best", bestScore).putInt("cosmic.game.shields", shields)
                    .putInt("cosmic.game.combo", combo).putInt("cosmic.game.seed", gameSeed)
                    .putFloat("cosmic.game.playerX", playerX).putFloat("cosmic.game.playerY", playerY)
                    .putFloat("cosmic.game.clock", gameClock).putFloat("cosmic.game.grace", grace)
                    .putFloat("cosmic.game.comboClock", comboClock);
            for (int i = 0; i < PICKUPS; i++) {
                editor.putFloat("cosmic.game.px" + i, pickupX[i]).putFloat("cosmic.game.py" + i, pickupY[i]);
            }
            for (int i = 0; i < METEORS; i++) {
                editor.putFloat("cosmic.game.mx" + i, meteorX[i]).putFloat("cosmic.game.my" + i, meteorY[i])
                        .putFloat("cosmic.game.ms" + i, meteorSpeed[i]).putFloat("cosmic.game.md" + i, meteorSide[i]);
            }
        }

        void restoreGame(SharedPreferences prefs) {
            if (prefs.getInt("cosmic.game.version", 0) != 1) return;
            score = Math.max(0, Math.min(999999999, prefs.getInt("cosmic.game.score", 0)));
            bestScore = Math.max(score, Math.min(999999999, prefs.getInt("cosmic.game.best", 0)));
            shields = Math.max(0, Math.min(3, prefs.getInt("cosmic.game.shields", 3)));
            combo = Math.max(1, Math.min(9, prefs.getInt("cosmic.game.combo", 1)));
            gameSeed = prefs.getInt("cosmic.game.seed", 5713852);
            playerX = bounded(prefs.getFloat("cosmic.game.playerX", .5f), .05f, .95f, .5f);
            playerY = bounded(prefs.getFloat("cosmic.game.playerY", .78f), .19f, .89f, .78f);
            gameClock = bounded(prefs.getFloat("cosmic.game.clock", 0), 0, 1000000, 0);
            grace = bounded(prefs.getFloat("cosmic.game.grace", 0), 0, 1.5f, 0);
            comboClock = bounded(prefs.getFloat("cosmic.game.comboClock", 0), 0, 4, 0);
            for (int i = 0; i < PICKUPS; i++) {
                pickupX[i] = bounded(prefs.getFloat("cosmic.game.px" + i, pickupX[i]), .04f, .96f, .5f);
                pickupY[i] = bounded(prefs.getFloat("cosmic.game.py" + i, pickupY[i]), -.6f, 1.1f, -.1f);
            }
            for (int i = 0; i < METEORS; i++) {
                meteorX[i] = bounded(prefs.getFloat("cosmic.game.mx" + i, meteorX[i]), .04f, .96f, .5f);
                meteorY[i] = bounded(prefs.getFloat("cosmic.game.my" + i, meteorY[i]), -1.5f, 1.1f, -.1f);
                meteorSpeed[i] = bounded(prefs.getFloat("cosmic.game.ms" + i, meteorSpeed[i]), .06f, .2f, .11f);
                meteorSide[i] = bounded(prefs.getFloat("cosmic.game.md" + i, meteorSide[i]), -.05f, .05f, 0);
            }
            gameOver = shields == 0; collectionGlow = 0;
            invalidate();
        }

        Map<String, Object> gameState() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("score", score); result.put("best", bestScore); result.put("shields", shields);
            result.put("combo", combo); result.put("clock", gameClock); result.put("gameOver", gameOver);
            result.put("playerX", playerX); result.put("playerY", playerY);
            return result;
        }

        Map<String, Object> gameProbe() {
            Universe probe = new Universe((Activity) getContext());
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            try {
                probe.pickupX[0] = probe.playerX; probe.pickupY[0] = probe.playerY;
                probe.advanceGame(0);
                boolean collect = probe.score == 10 && probe.combo == 2;
                probe.pickupX[1] = probe.playerX; probe.pickupY[1] = probe.playerY;
                probe.advanceGame(0);
                boolean comboPass = probe.score == 30 && probe.combo == 3;
                probe.grace = 0;
                probe.meteorX[0] = probe.playerX; probe.meteorY[0] = probe.playerY;
                probe.advanceGame(0);
                boolean damage = probe.shields == 2 && probe.combo == 1;
                probe.meteorX[1] = probe.playerX; probe.meteorY[1] = probe.playerY;
                probe.advanceGame(0);
                boolean protection = probe.shields == 2;
                for (int i = 0; i < 2; i++) {
                    probe.grace = 0; probe.meteorX[0] = probe.playerX; probe.meteorY[0] = probe.playerY;
                    probe.advanceGame(0);
                }
                float stopped = probe.gameClock;
                probe.advanceGame(.05f);
                boolean loss = probe.gameOver && probe.shields == 0 && probe.gameClock == stopped;
                probe.restartGame();
                boolean restart = !probe.gameOver && probe.shields == 3 && probe.score == 0 && probe.gameClock == 0;
                result.put("collectPass", collect); result.put("comboPass", comboPass);
                result.put("damagePass", damage); result.put("invulnerabilityPass", protection);
                result.put("lossPass", loss); result.put("restartPass", restart);
                result.put("pass", collect && comboPass && damage && protection && loss && restart);
            } finally { probe.dispose(); }
            return result;
        }

        void setPaused(boolean value) { paused = value; syncLoop(); invalidate(); }
        void setHostActive(boolean value) { hostActive = value; syncLoop(); }
        boolean canAnimate() {
            return attached && !disposed && !paused && hostActive && isShown()
                    && getWindowVisibility() == VISIBLE && hasWindowFocus();
        }
        void schedule() {
            if (!scheduled && canAnimate()) { scheduled = true; postOnAnimation(frame); }
        }
        void syncLoop() {
            if (canAnimate()) { lastFrame = 0; schedule(); }
            else { removeCallbacks(frame); scheduled = false; lastFrame = 0; }
        }
        void dispose() { disposed = true; onCycle = null; syncLoop(); }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; syncLoop(); }
        @Override protected void onDetachedFromWindow() { attached = false; syncLoop(); super.onDetachedFromWindow(); }
        @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); syncLoop(); }
        @Override protected void onVisibilityChanged(View changed, int visibility) { super.onVisibilityChanged(changed, visibility); syncLoop(); }
        @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); syncLoop(); }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            updateShaders(w, h);
        }

        private void updateShaders(int w, int h) {
            if (w <= 0 || h <= 0) return;
            background = new LinearGradient(0, 0, w, h,
                    new int[] {INK,
                            palette == 1 ? Color.rgb(34, 18, 26) : palette == 2 ? Color.rgb(9, 24, 41) : Color.rgb(12, 22, 37),
                            palette == 1 ? Color.rgb(32, 15, 35) : palette == 2 ? Color.rgb(13, 19, 46) : Color.rgb(19, 13, 38), INK},
                    new float[] {0, .37f, .75f, 1}, Shader.TileMode.CLAMP);
            glow = new RadialGradient(w * .5f, h * .48f, Math.max(1, Math.min(w, h) * .55f),
                    new int[] {alpha(primary, 30), alpha(secondary, 13), Color.TRANSPARENT},
                    new float[] {0, .55f, 1}, Shader.TileMode.CLAMP);
        }

        void advance(float dt) {
            if (paused || disposed) return;
            if (Float.isNaN(dt) || Float.isInfinite(dt)) return;
            dt = Math.max(0, Math.min(.05f, dt));
            time += dt;
            cycleTime += dt;
            impulse = Math.max(0, impulse - dt * .6f);
            ripple += dt * .72f;
            advanceGame(dt);
            if (drift && cycleTime >= 8) {
                cycleTime = 0;
                if (onCycle != null) onCycle.run();
                burst();
            }
            for (int i = 0; i < COUNT; i++) {
                oldX[i] = x[i]; oldY[i] = y[i];
                float dx = x[i] - .5f, dy = y[i] - .5f;
                float radius = (float) Math.sqrt(dx * dx + dy * dy) + .001f;
                float target = .24f + (i % 4) * .047f;
                float spring = (target - radius) * .17f;
                float swirl = .0042f + (sum % 19) * .00009f;
                if (mode == 2) {
                    float speed = .035f + radius * .38f;
                    vx[i] += (dx / radius * speed - vx[i]) * dt * 2;
                    vy[i] += (dy / radius * speed - vy[i]) * dt * 2;
                } else if (mode == 1) {
                    float flowX = (float) Math.sin(y[i] * 8 + time * .32f + i * .019f);
                    float flowY = (float) Math.cos(x[i] * 7 - time * .27f + i * .027f);
                    vx[i] += (flowX * .035f - dx * .032f) * dt;
                    vy[i] += (flowY * .035f - dy * .032f) * dt;
                } else {
                    vx[i] += (dx / radius * spring - dy / radius * swirl) * dt;
                    vy[i] += (dy / radius * spring + dx / radius * swirl) * dt;
                }
                if (impulse > 0) {
                    vx[i] += dx / radius * impulse * .014f * dt;
                    vy[i] += dy / radius * impulse * .014f * dt;
                }
                if (touching) {
                    float tx = touchX - x[i], ty = touchY - y[i];
                    float distance = (float) Math.sqrt(tx * tx + ty * ty) + .025f;
                    vx[i] += tx / distance * .11f * dt;
                    vy[i] += ty / distance * .11f * dt;
                }
                float drag = (float) Math.pow(.985f, dt * 30);
                vx[i] *= drag; vy[i] *= drag;
                vx[i] = Math.max(-.32f, Math.min(.32f, vx[i]));
                vy[i] = Math.max(-.32f, Math.min(.32f, vy[i]));
                x[i] += vx[i] * dt; y[i] += vy[i] * dt;
                if (mode == 2 && (x[i] < .035f || x[i] > .965f || y[i] < .035f || y[i] > .965f)) {
                    float angle = i * 2.399963f + time * .08f;
                    float seedRadius = .025f + (i % 7) * .005f;
                    x[i] = .5f + (float) Math.cos(angle) * seedRadius;
                    y[i] = .5f + (float) Math.sin(angle) * seedRadius;
                    oldX[i] = x[i]; oldY[i] = y[i];
                    vx[i] = (float) Math.cos(angle) * .04f;
                    vy[i] = (float) Math.sin(angle) * .04f;
                }
                // A bounded elastic field keeps long drifts finite and on the canvas.
                if (x[i] < .035f) { x[i] = .035f; vx[i] = Math.abs(vx[i]) * .6f; }
                if (x[i] > .965f) { x[i] = .965f; vx[i] = -Math.abs(vx[i]) * .6f; }
                if (y[i] < .035f) { y[i] = .035f; vy[i] = Math.abs(vy[i]) * .6f; }
                if (y[i] > .965f) { y[i] = .965f; vy[i] = -Math.abs(vy[i]) * .6f; }
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                if (getWidth() <= 0 || getHeight() <= 0) return false;
                if (action == MotionEvent.ACTION_DOWN && gameOver) restartGame();
                playerX = Math.max(.05f, Math.min(.95f, event.getX() / getWidth()));
                playerY = Math.max(.19f, Math.min(.89f, event.getY() / getHeight()));
                float size = Math.min(getWidth(), getHeight() * 1.12f);
                touchX = .5f + (event.getX() - getWidth() * .5f) / size;
                touchY = .5f + (event.getY() - getHeight() * .5f) / size;
                touchX = Math.max(0, Math.min(1, touchX));
                touchY = Math.max(0, Math.min(1, touchY));
                touching = true;
                if (action == MotionEvent.ACTION_DOWN) { touches++; ripple = 0; }
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touching = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (action == MotionEvent.ACTION_UP) performClick();
                invalidate();
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override public boolean performClick() { super.performClick(); return true; }

        private void label(Canvas c, String text, float x, float y, float size, int color, float tracking) {
            mono.setTextAlign(Paint.Align.CENTER);
            mono.setTextSize(size);
            mono.setColor(color);
            mono.setLetterSpacing(0); // Paint spacing is provided manually below for Java framework compatibility.
            if (tracking <= 0 || text.length() < 2) { c.drawText(text, x, y, mono); return; }
            float width = mono.measureText(text) + tracking * (text.length() - 1);
            float left = x - width * .5f;
            mono.setTextAlign(Paint.Align.LEFT);
            for (int i = 0; i < text.length(); i++) {
                String ch = text.substring(i, i + 1);
                c.drawText(ch, left, y, mono);
                left += mono.measureText(ch) + tracking;
            }
        }

        private void nebula(Canvas c, float w, float h, float unit) {
            paint.setStyle(Paint.Style.STROKE);
            for (int ribbon = 0; ribbon < 6; ribbon++) {
                path.reset();
                for (int step = 0; step <= 32; step++) {
                    float u = step / 32f;
                    float px = (u * 1.2f - .1f) * w;
                    float wave = (float) Math.sin(u * 7 + time * .19f + ribbon * .57f) * .14f
                            + (float) Math.sin(u * 13 - time * .12f + ribbon) * .045f;
                    float py = h * (.31f + ribbon * .075f + wave);
                    if (step == 0) path.moveTo(px, py); else path.lineTo(px, py);
                }
                int color = ribbon % 2 == 0 ? primary : secondary;
                paint.setColor(alpha(color, 10));
                paint.setStrokeWidth((18 + ribbon * 2) * unit);
                c.drawPath(path, paint);
                paint.setColor(alpha(color, 35));
                paint.setStrokeWidth(.8f * unit);
                c.drawPath(path, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        private void gameStar(Canvas c, float cx, float cy, float radius, float rotation, int color) {
            path.reset();
            for (int i = 0; i < 8; i++) {
                float angle = rotation + i * TWO_PI / 8;
                float r = i % 2 == 0 ? radius : radius * .32f;
                float px = cx + (float) Math.cos(angle) * r, py = cy + (float) Math.sin(angle) * r;
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            path.close();
            paint.setStyle(Paint.Style.FILL); paint.setColor(color);
            c.drawPath(path, paint);
        }

        private void drawGame(Canvas c, float w, float h, float unit) {
            unit = Math.min(unit, h / 215f);
            int cyan = Color.rgb(119, 248, 239), danger = Color.rgb(255, 133, 93);
            c.save();
            c.clipRect(0, h * .16f, w, h * .93f);
            for (int i = 0; i < PICKUPS; i++) {
                float px = pickupX[i] * w, py = pickupY[i] * h;
                paint.setStyle(Paint.Style.FILL); paint.setColor(alpha(cyan, 24));
                c.drawCircle(px, py, 15 * unit, paint);
                gameStar(c, px, py, 7.5f * unit, time * .35f + i, cyan);
                paint.setColor(WHITE); c.drawCircle(px, py, 1.8f * unit, paint);
            }
            for (int i = 0; i < METEORS; i++) {
                float px = meteorX[i] * w, py = meteorY[i] * h;
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(9 * unit);
                paint.setColor(alpha(danger, 25));
                c.drawLine(px - meteorSide[i] * w * .65f, py - 26 * unit, px, py, paint);
                paint.setStrokeWidth(3 * unit); paint.setColor(alpha(danger, 100));
                c.drawLine(px - meteorSide[i] * w * .4f, py - 17 * unit, px, py, paint);
                paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(255, 105, 87));
                c.drawCircle(px, py, 9 * unit, paint);
                paint.setColor(danger); c.drawCircle(px - 2 * unit, py - 2 * unit, 6 * unit, paint);
                paint.setColor(Color.rgb(117, 47, 62));
                c.drawCircle(px + 3 * unit, py + unit, 2.2f * unit, paint);
            }
            float px = playerX * w, py = playerY * h;
            paint.setStyle(Paint.Style.FILL); paint.setColor(alpha(cyan, 30));
            c.drawCircle(px, py, (18 + collectionGlow * 14) * unit, paint);
            if (grace > 0 && !gameOver) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.1f * unit);
                paint.setColor(alpha(cyan, 135)); c.drawCircle(px, py, 17 * unit, paint);
            }
            gameStar(c, px, py, 11 * unit, -TWO_PI * .25f, gameOver ? MUTED : WHITE);
            gameStar(c, px, py, 7.3f * unit, -TWO_PI * .25f, gameOver ? MUTED : cyan);
            c.restore();

            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(210, 7, 11, 22));
            c.drawRect(0, 0, w, 48 * unit, paint);
            label(c, "SCORE " + score, w * .19f, 20 * unit, 10 * unit, WHITE, .4f * unit);
            label(c, "SHIELDS", w * .51f, 17 * unit, 8 * unit, MUTED, .8f * unit);
            for (int i = 0; i < 3; i++) {
                paint.setColor(i < shields ? cyan : Color.rgb(51, 64, 78));
                c.drawCircle(w * .51f + (i - 1) * 12 * unit, 31 * unit, 3.4f * unit, paint);
            }
            label(c, "BEST " + bestScore, w * .82f, 20 * unit, 9 * unit, MUTED, .3f * unit);
            if (combo > 1 && !gameOver) label(c, "COMBO ×" + combo, w * .19f, 36 * unit, 9 * unit, cyan, .4f * unit);
            label(c, paused ? "PAUSED · TAKE A BREATH" : "DRAG TO COLLECT ✦ / DODGE METEORS",
                    w * .5f, h - 12 * unit, 8.2f * unit, WHITE, .55f * unit);
            if (gameOver) {
                paint.setColor(Color.argb(238, 9, 18, 32));
                oval.set(w * .1f, h * .34f, w * .9f, h * .69f);
                c.drawRoundRect(oval, 18 * unit, 18 * unit, paint);
                label(c, "RUN COMPLETE", w * .5f, h * .43f, 17 * unit, WHITE, 1.5f * unit);
                label(c, score + " POINTS", w * .5f, h * .52f, 21 * unit, cyan, 1 * unit);
                label(c, "TAP TO PLAY AGAIN", w * .5f, h * .62f, 10 * unit, accent, 1 * unit);
            } else if (collectionGlow > 0 && combo > 1) {
                label(c, "+" + (10 * Math.max(1, combo - 1)), px, Math.max(62 * unit, py - 22 * unit),
                        11 * unit, cyan, .3f * unit);
            }
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float unit = Math.min(density, w / 345f);
            float cx = w * .5f, cy = h * .49f;
            float size = Math.min(w * .95f, h * 1.03f);
            float radius = size * .355f;
            boolean compact = h < 210 * unit;
            paint.setShader(background);
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(glow);
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            if (mode == 1) nebula(c, w, h, unit);
            for (int i = 0; i < starX.length; i++) {
                int alpha = (int) (36 + 45 * (.5f + .5f * Math.sin(time * .4f + i * 1.7f)));
                paint.setColor(alpha(i % 3 == 0 ? secondary : primary, alpha));
                if (mode == 2) {
                    float depth = (starR[i] + time * .065f) % 1;
                    float perspective = .08f + depth * depth * 2.2f;
                    float sx = (starX[i] - .5f) * w, sy = (starY[i] - .5f) * h;
                    float previous = Math.max(.02f, perspective - .015f - depth * .05f);
                    paint.setStrokeWidth((.4f + depth * 1.1f) * unit);
                    c.drawLine(cx + sx * previous, cy + sy * previous,
                            cx + sx * perspective, cy + sy * perspective, paint);
                } else {
                    c.drawCircle(starX[i] * w, starY[i] * h, starR[i] * unit, paint);
                }
            }

            // Fine tilted orbits provide depth without a bitmap or external asset.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, .6f * unit));
            for (int i = 0; i < (mode == 0 ? 4 : mode == 2 ? 3 : 0); i++) {
                c.save();
                if (mode == 0) c.rotate(-32 + i * 37 + (float) Math.sin(time * .06f) * 8, cx, cy);
                paint.setColor(alpha(i % 2 == 0 ? primary : secondary, mode == 2 ? 20 : 40));
                float r = radius * (mode == 2 ? .8f + i * .27f : 1 + i * .075f);
                float squash = mode == 2 ? 1 : .66f;
                oval.set(cx - r, cy - r * squash, cx + r, cy + r * squash);
                c.drawOval(oval, paint);
                c.restore();
            }

            float baseX = cx - size * .5f, baseY = cy - size * .5f;
            for (int i = 0; i < COUNT; i++) {
                float px = baseX + x[i] * size, py = baseY + y[i] * size;
                int tint = i % 3 == 0 ? accent : i % 3 == 1 ? primary : secondary;
                if (mode != 2 && i % 4 == 0) {
                    int j = (i + 11) % COUNT;
                    float ddx = x[i] - x[j], ddy = y[i] - y[j];
                    if (ddx * ddx + ddy * ddy < .022f) {
                        paint.setColor((tint & 0xffffff) | 0x1f000000);
                        paint.setStrokeWidth(.6f * unit);
                        c.drawLine(px, py, baseX + x[j] * size, baseY + y[j] * size, paint);
                    }
                }
                paint.setStrokeWidth((i % 5 == 0 ? 1.5f : .8f) * unit);
                paint.setColor((tint & 0xffffff) | 0x65000000);
                float trail = mode == 1 ? 1.7f : mode == 2 ? .38f : .7f;
                c.drawLine(baseX + (x[i] - vx[i] * trail) * size,
                        baseY + (y[i] - vy[i] * trail) * size, px, py, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor((tint & 0xffffff) | 0x0c000000);
                c.drawCircle(px, py, (i % 7 == 0 ? 7 : 4) * unit, paint);
                paint.setColor((tint & 0xffffff) | 0x35000000);
                c.drawCircle(px, py, 2.7f * unit, paint);
                paint.setColor((tint & 0xffffff) | 0xd0000000);
                c.drawCircle(px, py, (i % 7 == 0 ? 1.8f : 1.0f) * unit, paint);
                paint.setStyle(Paint.Style.STROKE);
            }

            if (ripple < 1.4f) {
                paint.setColor(alpha(primary, (int) (100 * (1 - Math.min(1, ripple / 1.4f)))));
                paint.setStrokeWidth(1.2f * unit);
                c.drawCircle(baseX + touchX * size, baseY + touchY * size, ripple * size * .45f, paint);
            }
            if (touching) {
                paint.setColor(alpha(primary, 135));
                paint.setStrokeWidth(.8f * unit);
                c.drawCircle(baseX + touchX * size, baseY + touchY * size, 12 * unit, paint);
            }

            int n = Math.min(8, glyphs.length());
            for (int i = 0; i < n; i++) {
                float angle = -TWO_PI * .25f + i * TWO_PI / Math.max(4, n) + time * .035f;
                float lx = cx + (float) Math.cos(angle) * radius * 1.04f;
                float ly = cy + (float) Math.sin(angle) * radius * .88f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(70, 12, 22, 36));
                c.drawCircle(lx, ly, 9 * unit, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(.8f * unit);
                paint.setColor(alpha(primary, 35));
                c.drawCircle(lx, ly, 9 * unit, paint);
                label(c, glyphs.substring(i, i + 1), lx, ly + 3 * unit, 8 * unit, alpha(primary, 75), 0);
            }

            // The word stays as a quiet backdrop; the playable field owns the foreground.
            paint.setStyle(Paint.Style.FILL);
            String sumText = String.valueOf(sum);
            float numeralSize = Math.min(41 * unit, h * .14f);
            display.setTextAlign(Paint.Align.CENTER);
            display.setTextSize(numeralSize);
            display.setColor(alpha(accent, 70));
            float baseline = cy - (display.ascent() + display.descent()) * .5f - 4 * unit;
            c.drawText(sumText, cx, baseline, display);
            display.clearShadowLayer();

            String shown = word.length() == 0 ? "YOUR WORD HERE" : word;
            if (shown.length() > 22) shown = shown.substring(0, 21) + "…";
            float wordY = cy + numeralSize * .54f;
            float wordSize = (shown.length() > 15 ? 10 : 12) * unit;
            label(c, shown, cx, wordY, wordSize, alpha(WHITE, 65), 1.5f * unit);
            if (other.length() > 0) {
                label(c, "↔  " + other, cx, wordY + 16 * unit, 9 * unit, alpha(primary, 65), .9f * unit);
            }
            drawGame(c, w, h, unit);
        }
    }

    /** Real offscreen rendering and deterministic interaction evidence; never edits saved user state. */
    static Map<String, Object> smoke(Activity activity) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Panel probe = new Panel(activity, false);
        Bitmap image = null;
        try {
            int[] expected = {57, 79, 52, 58, 89, 138, 24, 26};
            List<Map<String, Object>> pairs = new ArrayList<Map<String, Object>>();
            boolean arithmetic = true;
            for (int i = 0; i < expected.length; i++) {
                Map<String, Object> pair = new LinkedHashMap<String, Object>();
                int left = ordinal(PAIRS[i][0]), right = ordinal(PAIRS[i][1]);
                pair.put("left", PAIRS[i][0]); pair.put("right", PAIRS[i][1]);
                pair.put("leftSum", left); pair.put("rightSum", right); pair.put("expected", expected[i]);
                pair.put("pass", left == expected[i] && right == expected[i]);
                arithmetic &= left == expected[i] && right == expected[i];
                pairs.add(pair);
            }
            final java.util.List<String> sent = new java.util.ArrayList<String>();
            probe.sender = new MessageSender() {
                public Map<String,Object> send(String message) {
                    sent.add(message);
                    return Json.map("accepted", !message.equals("reject"), "error", "Not ready");
                }
            };
            String direct = "  hello model 🙂 /literal  ";
            probe.input.setText(direct);
            probe.send.performClick();
            boolean sendPass = sent.size()==1 && direct.equals(sent.get(0)) && probe.input.length()==0;
            probe.input.setText("reject");
            probe.input.onEditorAction(EditorInfo.IME_ACTION_SEND);
            sendPass &= sent.size()==2 && "reject".equals(probe.input.getText().toString());
            probe.input.setText("draft stays");
            probe.surprise.performClick();
            sendPass &= "draft stays".equals(probe.input.getText().toString());
            probe.pairIndex=0;
            result.put("messagePassthroughPass", sendPass);
            result.put("pairs", pairs);
            String[] samples = {"a Z!é🙂", "123—", "moon", "Moon Starer", "ＡＺ", ""};
            int[] sums = {27, 0, 57, 138, 0, 0};
            List<Map<String, Object>> inputs = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < samples.length; i++) {
                probe.input.setText(samples[i]);
                Map<String, Object> input = new LinkedHashMap<String, Object>();
                input.put("text", samples[i]); input.put("actual", probe.sky.sum); input.put("expected", sums[i]);
                input.put("pass", probe.sky.sum == sums[i]);
                arithmetic &= probe.sky.sum == sums[i];
                inputs.add(input);
            }
            result.put("inputCases", inputs);
            result.put("arithmeticPass", arithmetic);

            probe.input.setText("MOON");
            probe.surprise.performClick();
            boolean surprisePass = "LISTEN".equals(probe.sky.word) && probe.sky.sum == 79 && "SILENT".equals(probe.sky.other);
            probe.drift.performClick();
            for (int i = 0; i < 165; i++) probe.sky.advance(.05f);
            boolean driftPass = "EARTH".equals(probe.sky.word) && probe.sky.sum == 52;
            probe.drift.performClick();
            result.put("surprisePass", surprisePass);
            result.put("driftPass", driftPass);

            int width = 1080, height = 1700;
            probe.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            probe.root.layout(0, 0, width, height);
            float before = probe.sky.x[0];
            MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
                    probe.sky.getWidth() * .81f, probe.sky.getHeight() * .25f, 0);
            probe.sky.onTouchEvent(down);
            down.recycle();
            for (int i = 0; i < 90; i++) probe.sky.advance(1f / 30f);
            MotionEvent up = MotionEvent.obtain(0, 3000, MotionEvent.ACTION_UP,
                    probe.sky.getWidth() * .81f, probe.sky.getHeight() * .25f, 0);
            probe.sky.onTouchEvent(up);
            up.recycle();
            boolean interaction = probe.sky.touches == 1 && Math.abs(probe.sky.x[0] - before) > .001f && !probe.sky.touching;
            boolean finite = true;
            for (int i = 0; i < Universe.COUNT; i++) {
                finite &= !Float.isNaN(probe.sky.x[i]) && !Float.isInfinite(probe.sky.x[i])
                        && !Float.isNaN(probe.sky.y[i]) && !Float.isInfinite(probe.sky.y[i])
                        && probe.sky.x[i] >= 0 && probe.sky.x[i] <= 1 && probe.sky.y[i] >= 0 && probe.sky.y[i] <= 1;
            }
            result.put("touchPass", interaction);
            result.put("finiteParticlesPass", finite);
            probe.pause.performClick();
            float stoppedTime = probe.sky.time;
            probe.sky.advance(.05f);
            boolean pausePass = probe.sky.paused && !probe.sky.scheduled && probe.sky.time == stoppedTime;
            probe.pause.performClick();
            probe.sky.setHostActive(false);
            result.put("pausePass", pausePass);
            result.put("hiddenSchedulerStopped", !probe.sky.scheduled);

            // A fresh scene keeps the saved preview identical to the first user-facing state.
            Panel preview = new Panel(activity, false);
            try {
                preview.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                preview.root.layout(0, 0, width, height);
                for (int i = 0; i < 60; i++) preview.sky.advance(1f / 30f);
                image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                preview.root.draw(new Canvas(image));
            } finally { preview.dispose(); }
            int varied = 0;
            int anchor = image.getPixel(0, 0);
            for (int py = 0; py < height; py += 17) {
                for (int px = 0; px < width; px += 17) if (image.getPixel(px, py) != anchor) varied++;
            }
            File dir = Creds.shared();
            if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("Cannot create cosmic preview directory");
            File file = new File(dir, "cosmic-preview.png");
            FileOutputStream stream = new FileOutputStream(file);
            try { if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new java.io.IOException("PNG encode failed"); }
            finally { stream.close(); }
            result.put("image", file.getAbsolutePath());
            result.put("width", width); result.put("height", height);
            result.put("variedPixels", varied); result.put("renderPass", varied > 300);
            result.put("pass", sendPass && arithmetic && surprisePass && driftPass && interaction && finite && pausePass && varied > 300);
        } finally {
            probe.dispose();
            if (image != null) image.recycle();
        }
        result.put("disposedSchedulerStopped", !probe.sky.scheduled && probe.sky.disposed);
        result.put("ok", Boolean.TRUE.equals(result.get("pass")) && !probe.sky.scheduled && probe.sky.disposed);
        return result;
    }
}
