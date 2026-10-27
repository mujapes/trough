package com.trough.hot;

import java.util.Map;

/**
 * One conversation with a model, whatever runs it. Trough is not tied to Claude: every backend
 * (ClaudeAgent over claude's stream-json, CodexAgent over codex app-server, and Trough's own
 * loop over any model's API next) speaks this, and Chat only ever sees this.
 * Tool use is never pre-approved by a backend: every request reaches Listener.onPermission.
 */
public interface Agent {
    void send(String text);
    void allow(String requestId, Map<String, Object> input);
    void deny(String requestId, String message);
    void interrupt();
    void close();

    interface Listener {
        /** sessionId: what Chat saves to resume this conversation after a restart or reload. */
        void onInit(String sessionId, String model);
        void onTextDelta(String text);
        void onAssistantText(String text);
        void onToolUse(String id, String name, Map<String, Object> input);
        void onToolResult(String toolUseId, String text, boolean isError);
        /** Answer with allow() or deny(); the turn waits until then. */
        void onPermission(String requestId, String toolName, Map<String, Object> input);
        void onResult(boolean ok, double costUsd, long durationMs, String text);
        void onExit(int code, String stderrTail);
        /** The backend needs its own sign-in: open url in a browser and enter code (may be null). */
        default void onAuth(String url, String code) {}
    }
}
