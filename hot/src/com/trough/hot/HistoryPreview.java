package com.trough.hot;
/** Bound layout work; complete text stays in the transcript and details. */
final class HistoryPreview {
    static final int LIMIT=4096;
    static String text(String full) {
        if(full==null)return "";
        return full.length()<=LIMIT?full:full.substring(0,LIMIT)+"\n… Tap for complete message";
    }
    static boolean internalPrompt(String text) {
        String t=text.trim();
        return t.startsWith("# AGENTS.md instructions for ") || t.startsWith("<environment_context>")
            || t.startsWith("You are doing a CONTEXT CHECKPOINT COMPACTION")
            || t.startsWith("Another language model started working on this problem")
            || t.startsWith("A previous model generated the following summary");
    }
}
