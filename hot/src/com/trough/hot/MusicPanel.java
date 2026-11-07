package com.trough.hot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Explicit source handoff. No claim to control an external app's playback. */
final class MusicPanel {
    static void show(final Activity a) {
        if (a == null) return;
        final android.content.SharedPreferences saved = a.getSharedPreferences("trough.music", 0);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView status = new TextView(a);
        status.setText("Your music, alongside your work\n\nOpen YouTube Music to choose or resume your queue. Playback stays in that app.");
        status.setTextSize(16);
        box.addView(status);
        final EditText link = new EditText(a);
        link.setHint("YouTube Music playlist or song link");
        link.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        link.setText(saved.getString("link", ""));
        box.addView(link);
        final Button open = new Button(a);
        open.setText("Open song or playlist");
        open.setAllCaps(false);
        box.addView(open);
        Button library = new Button(a);
        library.setText("Open YouTube Music");
        library.setAllCaps(false);
        box.addView(library);
        TextView note = new TextView(a);
        note.setText("Trough playback controls are not connected yet.");
        box.addView(note);
        final AlertDialog dialog = new AlertDialog.Builder(a).setTitle("Music")
            .setView(box).setNegativeButton("Back to Trough", null).create();
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String value = link.getText().toString().trim();
                Uri uri = Uri.parse(value);
                String host = uri.getHost();
                if (!"https".equals(uri.getScheme()) || !("music.youtube.com".equals(host)
                        || "www.youtube.com".equals(host) || "youtube.com".equals(host) || "youtu.be".equals(host))) {
                    link.setError("Paste an HTTPS YouTube or YouTube Music link");
                    return;
                }
                saved.edit().putString("link", value).apply();
                launch(a, uri, link);
            }
        });
        library.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                launch(a, Uri.parse("https://music.youtube.com/"), link);
            }
        });
        dialog.show();
    }

    private static void launch(Activity a, Uri uri, EditText error) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setPackage("com.google.android.apps.youtube.music");
        try { a.startActivity(intent); }
        catch (android.content.ActivityNotFoundException missing) {
            error.setError("YouTube Music is not installed or cannot open this link");
        }
    }
}
