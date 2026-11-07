package com.trough.hot;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;

/** Detached copies verify real text/touch/migration/rendering without changing a user's draft. */
final class WorkSurfaceOracle {
    static Map<String,Object> run(Activity activity) throws Exception {
        BuildArtwork first=new BuildArtwork(activity), restored=new BuildArtwork(activity);
        Bitmap before=null,after=null;
        try {
            Map<String,Object> event=Json.map("word","MOON","response","GLOW","state","idle","log","pwd\n/data/user/0/t.t/files/work",
                "checks",Json.list(Json.map("name","pwd","state","passed")));
            first.update(event); first.perturb(.73f,.24f);
            Map<String,Object> state=first.state(), retained=first.handoff();
            boolean words="MOON".equals(Json.str(state,"word")) && ((Number)state.get("ordinal")).intValue()==57;
            boolean associations=Json.arr(state.get("matches")).contains("GLOW");
            restored.restore(retained);
            Map<String,Object> successor=restored.state(), second=restored.handoff();
            boolean continuity=Json.write(state).equals(Json.write(successor));
            boolean owned=true;
            for(String key:retained.keySet()) {
                Object a=retained.get(key), b=second.get(key);
                if(a instanceof float[]) owned &= a!=b && java.util.Arrays.equals((float[])a,(float[])b);
                if(a instanceof long[]) owned &= a!=b && java.util.Arrays.equals((long[])a,(long[])b);
                if(a instanceof boolean[]) owned &= a!=b && java.util.Arrays.equals((boolean[])a,(boolean[])b);
            }
            int width=1080,height=914;
            first.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            first.layout(0,0,width,height);
            before=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); first.draw(new Canvas(before));
            first.update(Json.map("word","LISTEN","response","SILENT","state","idle"));
            after=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); first.draw(new Canvas(after));
            int changed=0,varied=0,base=before.getPixel(0,0);
            for(int y=0;y<height;y+=8) for(int x=0;x<width;x+=8) {
                if(before.getPixel(x,y)!=after.getPixel(x,y)) changed++;
                if(before.getPixel(x,y)!=base) varied++;
            }
            File image=new File(Creds.shared(),"work-surface-preview.png");
            try(FileOutputStream output=new FileOutputStream(image)) { after.compress(Bitmap.CompressFormat.PNG,100,output); }
            Map<String,Object> arabicEvent=Json.map("word","اللَّهِ","state","idle");
            first.update(arabicEvent);
            Map<String,Object> arabic=first.state();
            boolean abjad="eastern-arabic-abjad".equals(Json.str(arabic,"cipher"))
                && ((Number)arabic.get("focus_ordinal")).intValue()==66
                && Json.str(arabic,"equation").endsWith(" = 66")
                && Json.arr(arabic.get("matches")).isEmpty();
            int passes=((Number)first.renderingStats().get("word_passes")).intValue();
            first.update(arabicEvent);
            boolean reuse=passes==((Number)first.renderingStats().get("word_passes")).intValue();
            restored.restore(first.handoff());
            boolean abjadMigration=Json.write(first.state()).equals(Json.write(restored.state()));
            LetterValues.Result arithmetic=LetterValues.analyse("بسم الله الرحمن الرحيم");
            abjad &= arithmetic!=null && arithmetic.total==786;
            int[] ownedValues=arithmetic.values(); char[] ownedLetters=arithmetic.letters();
            ownedValues[0]=-1; ownedLetters[0]='X';
            boolean letterOwnership=arithmetic.values()[0]==2 && arithmetic.letters()[0]=='ب';
            first.draw(new Canvas(after));
            int arabicChanged=0;
            for(int y=0;y<height;y+=8) for(int x=0;x<width;x+=8)
                if(before.getPixel(x,y)!=after.getPixel(x,y)) arabicChanged++;
            File studyImage=new File(Creds.shared(),"work-letter-study-preview.png");
            try(FileOutputStream output=new FileOutputStream(studyImage)) { after.compress(Bitmap.CompressFormat.PNG,100,output); }
            first.close(); restored.close();
            boolean stopped=Boolean.FALSE.equals(first.state().get("scheduled")) && Boolean.FALSE.equals(restored.state().get("scheduled"));
            return Json.map("ok",words&&associations&&continuity&&owned&&changed>20&&varied>100&&stopped
                    &&abjad&&reuse&&abjadMigration&&letterOwnership&&arabicChanged>20,
                "ordinal_pass",words,"associations_pass",associations,"migration_pass",continuity,"migration_arrays_owned",owned,
                "changed_samples",changed,"varied_samples",varied,"disposed_callbacks_stopped",stopped,"image",image.getPath(),
                "arabic_abjad_pass",abjad,"arabic_migration_pass",abjadMigration,"unchanged_word_reused",reuse,
                "letter_arrays_owned",letterOwnership,"arabic_changed_samples",arabicChanged,"arabic_state",arabic,
                "study_image",studyImage.getPath());
        } finally { first.close(); restored.close(); if(before!=null) before.recycle(); if(after!=null) after.recycle(); }
    }
}
