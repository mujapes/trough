package com.trough.hot;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Retain phone-side composer edits for review before a bundle seed merges its sources. */
final class SourceCheckpoint {
    static void capture(Context context) {
        try {
            File source=new File(Self.src(context),"com/trough/hot").getCanonicalFile();
            File target=new File(Creds.shared(),"input-source-before-20261031-typing");
            retainLetterStudy(context,source,target);
            if(new File(target,"inventory.json").isFile()) return;
            List<Object> files=new ArrayList<Object>();
            for(String name:new String[]{"Chat.java","BuildArtwork.java","CodexAgent.java","Main.java"}) {
                File file=new File(source,name);
                if(!file.isFile() || file.length()>350000 || !file.getCanonicalFile().getParentFile().equals(source)) continue;
                byte[] bytes=ScenarioProtocol.read(file,350000);
                ScenarioProtocol.write(new File(target,name),bytes);
                files.add(Json.map("name",name,"bytes",bytes.length,"sha256",ScenarioProtocol.sha(bytes)));
            }
            ScenarioProtocol.write(new File(target,"inventory.json"),ScenarioProtocol.encode(Json.map("files",files)));
        } catch(Exception ignored) { /* Self.seed independently preserves conflicting edits in git. */ }
    }

    private static void retainLetterStudy(Context context,File source,File target) {
        File output=new File(target,"LetterValues.java");
        if(output.isFile()) return;
        try {
            File live=new File(source,"LetterValues.java");
            if(live.isFile() && live.length()<350000 && live.getCanonicalFile().getParentFile().equals(source)) {
                ScenarioProtocol.write(output,ScenarioProtocol.read(live,350000)); return;
            }
            String refs=Self.git(context,Self.src(context),null,"for-each-ref","--sort=-committerdate","--format=%(refname:short)","refs/heads/agent-*");
            int tried=0;
            for(String ref:refs.split("\\s+")) {
                if(!ref.matches("agent-[0-9]+") || tried++>=12) continue;
                try {
                    String java=Self.git(context,Self.src(context),null,"show",ref+":com/trough/hot/LetterValues.java");
                    if(java.length()<350000 && java.contains("class LetterValues")) {
                        ScenarioProtocol.write(output,java.getBytes("UTF-8")); return;
                    }
                } catch(Exception missing) {}
            }
        } catch(Exception ignored) {}
    }
}
