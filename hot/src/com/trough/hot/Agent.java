package com.trough.hot;

import java.util.Map;

/**
 * One conversation with a model, whatever runs it. Trough is not tied to Claude: every backend
 * (ClaudeAgent over claude's stream-json, CodexAgent over codex app-server, and Trough's own
 * loop over any model's API next) speaks this, and Chat only ever sees this.
 * Any approval request emitted by a backend reaches Listener.onPermission; each backend keeps
 * its explicitly selected approval policy.
 */
public interface Agent {
    void send(String text);
    /** Update the selection for later turns without interrupting the current turn.
     * False means this transport cannot update it in place; the caller may reconnect when idle. */
    default boolean configureSelection(String model, String effort) { return false; }
    /** Whether successful message delivery is confirmed through onMessageAccepted. */
    default boolean supportsAcknowledgements() { return false; }
    /** Whether this backend can accept an explicit follow-up while its current turn runs. */
    default boolean supportsSteering() { return false; }
    /** Returning means the follow-up was dispatched; later rejection reaches onSendFailure. */
    default void steer(String text) { throw new IllegalStateException("This agent cannot accept a follow-up during a turn"); }
    /** Unconfirmed sends stay owned here until their rejection callback has returned. */
    default boolean hasPendingSends() { return false; }
    void allow(String requestId, Map<String, Object> input);
    void deny(String requestId, String message);
    void interrupt();
    void close();

    /** Joins transport ownership with draft restoration on the consumer's publication queue. */
    final class SendHandoff {
        private int publications;
        private final java.util.Set<Agent> owners = new java.util.HashSet<Agent>();
        public synchronized void track(Agent agent) { if(agent!=null) owners.add(agent); }
        public synchronized Runnable publication(final Runnable action) {
            publications++;
            return new Runnable() { public void run() {
                try { action.run(); }
                finally { synchronized(SendHandoff.this) { publications--; } }
            }};
        }
        public synchronized boolean ready(Agent agent) {
            if(agent!=null) owners.add(agent);
            boolean pending=false;
            for(java.util.Iterator<Agent> it=owners.iterator();it.hasNext();) {
                Agent owner=it.next();
                // Retired transports still own callbacks until publication() registers
                // them, even when the consumer has connected a replacement or has none.
                if(owner.hasPendingSends()) pending=true;
                else if(owner!=agent) it.remove();
            }
            return !pending && publications==0;
        }
    }

    interface Listener {
        /** sessionId: what Chat saves to resume this conversation after a restart or reload. */
        void onInit(String sessionId, String model);
        void onTextDelta(String text);
        void onAssistantText(String text);
        void onToolUse(String id, String name, Map<String, Object> input);
        void onToolResult(String toolUseId, String text, boolean isError);
        /** The server accepted this exact message; this does not mean its work has completed. */
        default void onMessageAccepted(String text) {}
        /** A dispatched message was not confirmed as accepted. Retain this exact text for recovery. */
        default void onSendFailure(String text, String error) {}
        /** Answer with allow() or deny(); the turn waits until then. */
        void onPermission(String requestId, String toolName, Map<String, Object> input);
        void onResult(boolean ok, double costUsd, long durationMs, String text);
        void onExit(int code, String stderrTail);
        /** The backend needs its own sign-in: open url in a browser and enter code (may be null). */
        default void onAuth(String url, String code) {}
    }
}
