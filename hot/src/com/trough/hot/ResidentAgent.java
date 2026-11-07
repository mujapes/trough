package com.trough.hot;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * A transport lives in its original DEX loader while its native UI is replaced.
 * Only bootstrap types cross that boundary: an InvocationHandler, maps, arrays,
 * strings and numbers. In particular an Agent or Listener from another loader is
 * never cast to this loader's copy of those interfaces.
 */
final class ResidentAgent implements Agent {
    private static final String KIND = "trough-resident-agent-1";
    private final InvocationHandler endpoint;
    private final long lease;
    private final InvocationHandler sink;
    private final Agent.Listener transportListener;
    private boolean retired;

    ResidentAgent(Agent.Listener listener) {
        this(listener, new Executor() { public void execute(Runnable action) { action.run(); } });
    }

    /** The app supplies its MAIN executor; the endpoint retains no Activity or Chat. */
    ResidentAgent(Agent.Listener listener, Executor dispatcher) {
        if (listener == null) throw new IllegalArgumentException("listener");
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher");
        endpoint = new Endpoint(dispatcher);
        lease = 1;
        sink = sink(listener);
        transportListener = forwarding(endpoint);
        call("resume", new Object[] {Long.valueOf(lease), sink});
    }

    private ResidentAgent(InvocationHandler endpoint, long lease, Agent.Listener listener) {
        this.endpoint = endpoint;
        this.lease = lease;
        this.sink = sink(listener);
        this.transportListener = null;
    }

    /** Supply this listener to the concrete backend constructor, then attach it. */
    Agent.Listener listener() {
        if (transportListener == null) throw new IllegalStateException("Adopted transport already has its listener");
        return transportListener;
    }

    void attach(Agent backend) {
        if (backend == null) throw new IllegalArgumentException("backend");
        call("attach", new Object[] {Long.valueOf(lease), backend});
    }

    /** Atomically detach event publication before taking the UI snapshot. */
    Map<String, Object> suspend() {
        if (retired) throw new IllegalStateException("Transport adapter already retired");
        call("suspend", new Object[] {Long.valueOf(lease)});
        retired = true;
        Map<String,Object> snapshot = new LinkedHashMap<String,Object>();
        snapshot.put("kind", KIND);
        snapshot.put("endpoint", endpoint);
        snapshot.put("lease", Long.valueOf(lease));
        return snapshot;
    }

    /** Claim an existing endpoint, without yet publishing into the new UI. */
    static ResidentAgent adopt(Map<String,Object> snapshot, Agent.Listener listener) {
        if (snapshot == null || listener == null || !KIND.equals(snapshot.get("kind"))) return null;
        Object endpoint = snapshot.get("endpoint"), lease = snapshot.get("lease");
        if (!(endpoint instanceof InvocationHandler) || !(lease instanceof Number)) return null;
        InvocationHandler bridge = (InvocationHandler) endpoint;
        Object claimed = invoke(bridge, "adopt", new Object[] {Long.valueOf(((Number) lease).longValue())});
        return new ResidentAgent(bridge, ((Number) claimed).longValue(), listener);
    }

    /** Call after the replacement Chat and all its MAIN publication guards exist. */
    void resume() {
        if (retired) throw new IllegalStateException("Transport adapter already retired");
        call("resume", new Object[] {Long.valueOf(lease), sink});
    }

    /** Non-sensitive diagnostics prove that reload adopted the same transport. */
    @SuppressWarnings("unchecked")
    Map<String,Object> state() { return (Map<String,Object>)call("state", new Object[] {Long.valueOf(lease)}); }

    public void send(String text) { operation("send", new Object[] {text}); }
    public boolean configureSelection(String model, String effort) {
        try { return Boolean.TRUE.equals(operation("select",new Object[] {model,effort})); }
        catch(IllegalArgumentException unsupported) {
            // An endpoint retained from an older DEX has a fixed operation table.
            if("Unknown resident operation select".equals(unsupported.getMessage())) return false;
            throw unsupported;
        }
    }
    public boolean supportsAcknowledgements() { return ((Boolean) operation("ack", new Object[0])).booleanValue(); }
    public boolean supportsSteering() { return ((Boolean) operation("steering", new Object[0])).booleanValue(); }
    public void steer(String text) { operation("steer", new Object[] {text}); }
    public boolean hasPendingSends() {
        // A retired adapter owns no new publications; the resident endpoint does.
        if (retired) return false;
        return ((Boolean) operation("pending", new Object[0])).booleanValue();
    }
    public void allow(String id, Map<String,Object> input) { operation("allow", new Object[] {id, input}); }
    public void deny(String id, String message) { operation("deny", new Object[] {id, message}); }
    public void interrupt() { operation("interrupt", new Object[0]); }
    public void close() {
        if (retired) return;
        retired = true;
        call("close", new Object[] {Long.valueOf(lease)});
    }

    private Object operation(String name, Object[] values) {
        if (retired) throw new IllegalStateException("Transport adapter already retired");
        return call("operation", new Object[] {Long.valueOf(lease), name, values});
    }
    private Object call(String name, Object[] args) { return invoke(endpoint, name, args); }
    private static Object invoke(InvocationHandler endpoint, String name, Object[] args) {
        try { return endpoint.invoke(null, null, new Object[] {name, args}); }
        catch (RuntimeException error) { throw error; }
        catch (Error error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException("Resident transport " + name + " failed", error); }
    }

    private static InvocationHandler sink(final Agent.Listener listener) {
        return new InvocationHandler() {
            @SuppressWarnings("unchecked")
            public Object invoke(Object proxy, Method method, Object[] envelope) {
                String event = (String) envelope[0];
                Object[] args = (Object[]) envelope[1];
                if ("init".equals(event)) listener.onInit((String)args[0], (String)args[1]);
                else if ("delta".equals(event)) listener.onTextDelta((String)args[0]);
                else if ("assistant".equals(event)) listener.onAssistantText((String)args[0]);
                else if ("tool".equals(event)) listener.onToolUse((String)args[0], (String)args[1], (Map<String,Object>)args[2]);
                else if ("toolResult".equals(event)) listener.onToolResult((String)args[0], (String)args[1], ((Boolean)args[2]).booleanValue());
                else if ("accepted".equals(event)) listener.onMessageAccepted((String)args[0]);
                else if ("failure".equals(event)) listener.onSendFailure((String)args[0], (String)args[1]);
                else if ("permission".equals(event)) listener.onPermission((String)args[0], (String)args[1], (Map<String,Object>)args[2]);
                else if ("result".equals(event)) listener.onResult(((Boolean)args[0]).booleanValue(), ((Number)args[1]).doubleValue(), ((Number)args[2]).longValue(), (String)args[3]);
                else if ("exit".equals(event)) listener.onExit(((Number)args[0]).intValue(), (String)args[1]);
                else if ("auth".equals(event)) listener.onAuth((String)args[0], (String)args[1]);
                else throw new IllegalArgumentException("Unknown resident event " + event);
                return null;
            }
        };
    }

    private static Agent.Listener forwarding(final InvocationHandler endpoint) {
        return new Agent.Listener() {
            private void event(String name, Object... args) { ResidentAgent.invoke(endpoint, "event", new Object[] {name, args}); }
            public void onInit(String id, String model) { event("init", id, model); }
            public void onTextDelta(String text) { event("delta", text); }
            public void onAssistantText(String text) { event("assistant", text); }
            public void onToolUse(String id, String name, Map<String,Object> input) { event("tool", id, name, input); }
            public void onToolResult(String id, String text, boolean error) { event("toolResult", id, text, Boolean.valueOf(error)); }
            public void onMessageAccepted(String text) { event("accepted", text); }
            public void onSendFailure(String text, String error) { event("failure", text, error); }
            public void onPermission(String id, String name, Map<String,Object> input) { event("permission", id, name, input); }
            public void onResult(boolean ok, double cost, long duration, String text) { event("result", Boolean.valueOf(ok), Double.valueOf(cost), Long.valueOf(duration), text); }
            public void onExit(int code, String tail) { event("exit", Integer.valueOf(code), tail); }
            public void onAuth(String url, String code) { event("auth", url, code); }
        };
    }

    private static final class Event {
        final String name;
        final Object[] args;
        final long bytes;
        Event(String name, Object[] args) { this.name=name; this.args=args; this.bytes=measure(args, 0); }
        private static long measure(Object value, int depth) {
            if (value == null) return 0;
            if (value instanceof String) return 32L + ((String)value).length()*2L;
            if (depth >= 16) return 128;
            long size = 32;
            if (value instanceof Object[]) for (Object child : (Object[])value) size += measure(child, depth+1);
            else if (value instanceof Map) for (Object child : ((Map<?,?>)value).entrySet()) {
                Map.Entry<?,?> entry=(Map.Entry<?,?>)child;
                size += measure(entry.getKey(), depth+1) + measure(entry.getValue(), depth+1);
            }
            else if (value instanceof Iterable) for (Object child : (Iterable<?>)value) size += measure(child, depth+1);
            return size;
        }
    }

    /** Old-loader owner. Its listener never closes over Chat, Activity or views. */
    private static final class Endpoint implements InvocationHandler {
        private static final int MAX_EVENTS = 256;
        private static final long MAX_BYTES = 1024L*1024L;
        private final ArrayDeque<Event> events = new ArrayDeque<Event>();
        private final Executor dispatcher;
        private Agent backend;
        private InvocationHandler consumer;
        private long lease = 1, bytes, nextTicket, publishTicket, adoptions, delivered;
        private boolean paused = true, closed, draining, scheduled;
        private Thread submittingThread;

        Endpoint(Executor dispatcher) { this.dispatcher=dispatcher; }

        public Object invoke(Object proxy, Method method, Object[] envelope) throws Throwable {
            String operation=(String)envelope[0];
            Object[] args=(Object[])envelope[1];
            if ("event".equals(operation)) { event((String)args[0], (Object[])args[1]); return null; }
            if ("state".equals(operation)) {
                Agent target;
                Map<String,Object> state=new LinkedHashMap<String,Object>();
                synchronized (this) {
                    check(((Number)args[0]).longValue()); target=backend;
                    state.put("resident",Boolean.TRUE);
                    state.put("identity",Integer.valueOf(System.identityHashCode(this)));
                    state.put("backend_identity",Integer.valueOf(System.identityHashCode(backend)));
                    state.put("backend",backend==null?"":backend.getClass().getSimpleName());
                    state.put("lease",Long.valueOf(lease));
                    state.put("adoptions",Long.valueOf(adoptions));
                    state.put("queued_events",Integer.valueOf(events.size()));
                    state.put("queued_bytes",Long.valueOf(bytes));
                    state.put("delivered_events",Long.valueOf(delivered));
                    state.put("paused",Boolean.valueOf(paused));
                }
                // Backend locks must never be acquired inside the publication monitor.
                state.put("pending_sends",Boolean.valueOf(target!=null && target.hasPendingSends()));
                if(target!=null && "CodexAgent".equals(target.getClass().getSimpleName())) {
                    try {
                        Method diagnostics=target.getClass().getMethod("state");diagnostics.setAccessible(true);
                        state.put("backend_state",diagnostics.invoke(target));
                        Method effort=target.getClass().getMethod("reportedEffort");effort.setAccessible(true);
                        state.put("reasoning_effort",effort.invoke(target));
                    } catch(ReflectiveOperationException ignored) {}
                }
                return state;
            }
            if ("operation".equals(operation)) {
                Agent target;
                synchronized (this) { check(((Number)args[0]).longValue()); target=backend; }
                if (target == null) throw new IllegalStateException("Resident backend not attached");
                String name=(String)args[1]; Object[] values=(Object[])args[2];
                if ("send".equals(name)) target.send((String)values[0]);
                else if ("select".equals(name)) return Boolean.valueOf(target.configureSelection((String)values[0],(String)values[1]));
                else if ("steer".equals(name)) target.steer((String)values[0]);
                else if ("ack".equals(name)) return Boolean.valueOf(target.supportsAcknowledgements());
                else if ("steering".equals(name)) return Boolean.valueOf(target.supportsSteering());
                else if ("pending".equals(name)) return Boolean.valueOf(target.hasPendingSends());
                else if ("allow".equals(name)) { @SuppressWarnings("unchecked") Map<String,Object> input=(Map<String,Object>)values[1]; target.allow((String)values[0],input); }
                else if ("deny".equals(name)) target.deny((String)values[0],(String)values[1]);
                else if ("interrupt".equals(name)) target.interrupt();
                else throw new IllegalArgumentException("Unknown resident operation " + name);
                return null;
            }
            if ("close".equals(operation)) {
                Agent target;
                synchronized (this) {
                    // A stale adapter can never shut down its replacement's transport.
                    if (closed || ((Number)args[0]).longValue()!=lease) return null;
                    closed=true; consumer=null; events.clear(); bytes=0; target=backend; backend=null; notifyAll();
                }
                // A backend may join its reader: never hold the event lock here.
                if (target!=null) target.close();
                return null;
            }
            synchronized (this) {
                long requested=((Number)args[0]).longValue();
                check(requested);
                if ("attach".equals(operation)) {
                    if (backend!=null) throw new IllegalStateException("Resident backend already attached");
                    backend=(Agent)args[1];
                } else if ("suspend".equals(operation)) {
                    consumer=null; paused=true;
                } else if ("adopt".equals(operation)) {
                    if (!paused) throw new IllegalStateException("Resident backend is not suspended");
                    lease++; adoptions++; return Long.valueOf(lease);
                } else if ("resume".equals(operation)) {
                    consumer=(InvocationHandler)args[1]; paused=false; scheduleDrain(); notifyAll();
                } else throw new IllegalArgumentException("Unknown resident operation " + operation);
                return null;
            }
        }

        private void check(long requested) {
            if (closed) throw new IllegalStateException("Resident backend closed");
            if (requested!=lease) throw new IllegalStateException("Stale resident adapter");
        }

        private synchronized void event(String name, Object[] args) throws Throwable {
            Event event=new Event(name,args);
            long ticket=nextTicket++;
            boolean interrupted=false;
            while (!closed && (ticket!=publishTicket ||
                    (!events.isEmpty() && (events.size()>=MAX_EVENTS || bytes+event.bytes>MAX_BYTES)))) {
                // Backpressure during a slow rebuild, rather than dropping acknowledgements,
                // results or deltas. One individually oversized backend event is allowed.
                try { wait(); } catch (InterruptedException error) { interrupted=true; }
            }
            if (!closed) {
                events.addLast(event); bytes+=event.bytes; publishTicket++;
                scheduleDrain(); notifyAll();
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        private void scheduleDrain() {
            if (closed || paused || consumer==null || events.isEmpty() || scheduled || draining) return;
            scheduled=true;
            Thread previous=submittingThread;
            submittingThread=Thread.currentThread();
            try {
                dispatcher.execute(new Runnable() { public void run() {
                    synchronized (Endpoint.this) {
                        scheduled=false;
                        try {
                            do {
                                drain();
                                // Direct executors need a trampoline, rather than one
                                // recursive stack frame for every bounded batch.
                            } while (submittingThread==Thread.currentThread() &&
                                !closed && !paused && consumer!=null && !events.isEmpty());
                        }
                        catch (Throwable failure) {
                            // Preserve the unacknowledged event for a replacement consumer.
                            // Consumer callbacks must publish atomically and must not throw.
                            consumer=null; paused=true;
                        }
                        scheduleDrain();
                    }
                }});
            } catch (RuntimeException failure) { scheduled=false; throw failure; }
            finally { submittingThread=previous; }
        }

        /** The dispatcher publishes under this monitor: suspend cannot race a UI snapshot. */
        private void drain() throws Throwable {
            if (draining) return;
            draining=true;
            try {
                int batch=0;
                long started=System.nanoTime();
                while (!closed && !paused && consumer!=null && !events.isEmpty()) {
                    Event event=events.getFirst();
                    consumer.invoke(null,null,new Object[] {event.name,event.args});
                    events.removeFirst(); bytes-=event.bytes;
                    delivered++;
                    notifyAll();
                    // A rebuilt feed can have a backlog. Yield to input and drawing
                    // between short MAIN batches instead of freezing the new view.
                    if (++batch>=32 || System.nanoTime()-started>=4000000L) break;
                }
            } finally { draining=false; }
        }
    }
}
