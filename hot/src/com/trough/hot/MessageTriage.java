package com.trough.hot;

import java.util.Locale;
import java.util.Map;

/** Cheap local arrival routing. These rules label messages; they do not infer intent or interrupt work. */
public final class MessageTriage {
    private MessageTriage() {}

    public static Map<String,Object> classify(String text) {
        String compact=compact(text);
        if (isStop(compact)) return Json.map("kind","stop","priority","urgent",
                "reason","Explicit stop or pause command");
        String[] corrections={"actually","instead","correction","switchdirection","changedirection",
                "firstworkon","getridof","fixthat","thatdidntwork","thatdoesntwork","youmisunderstood"};
        for (String cue:corrections) {
            if (compact.contains(cue)) return Json.map("kind","correction","priority","high",
                    "reason","Local correction cue: "+cue+"; review before changing direction");
        }
        String value=text==null?"":text.trim();
        String[] questions={"why","what","how","when","where","which","who","canwe","canyou",
                "couldyou","arewe","isthere","dowe","doesit","didyou"};
        boolean question=value.endsWith("?");
        for(String cue:questions) if(compact.startsWith(cue)) { question=true; break; }
        return Json.map("kind",question?"question":"message","priority","normal",
                "reason",question?"Local question cue; agent should review":"Review at the next work checkpoint");
    }

    static int rank(Map<String,Object> message) {
        Map<String,Object> triage=Json.obj(message.get("triage"));
        if(triage==null) triage=classify(Json.str(message,"text"));
        String priority=Json.str(triage,"priority");
        return "urgent".equals(priority)?2:"high".equals(priority)?1:0;
    }

    private static boolean isStop(String value) {
        return "stop".equals(value)||"stopnow".equals(value)||"stopworking".equals(value)
                ||"cancel".equals(value)||"cancelthat".equals(value)||"pause".equals(value)
                ||"pleasestop".equals(value)||"pleasestopnow".equals(value);
    }

    private static String compact(String text) {
        if(text==null) return "";
        String lower=text.toLowerCase(Locale.ROOT);
        StringBuilder result=new StringBuilder(lower.length());
        for(int i=0;i<lower.length();i++) {
            char letter=lower.charAt(i);
            if(Character.isLetterOrDigit(letter)) result.append(letter);
        }
        return result.toString();
    }
}
