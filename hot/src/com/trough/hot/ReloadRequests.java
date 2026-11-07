package com.trough.hot;

/** A closed source edit stays pending until a rebuild can actually start. */
final class ReloadRequests {
    private long requested, dispatched;
    private boolean closed;

    synchronized void request() { if (!closed) requested++; }
    synchronized boolean pending() { return !closed && requested != dispatched; }
    synchronized boolean claim(boolean building) {
        if (closed || building || requested == dispatched) return false;
        dispatched = requested;
        return true;
    }
    synchronized void close() { closed = true; }

    static boolean sourceFile(String path) {
        if (path == null || path.length() == 0) return false;
        if (path.startsWith("com/")) return path.endsWith(".java");
        if (!path.startsWith("native/")) return false;
        if (path.startsWith("native/bionic-inputs/")) return false;
        return path.endsWith(".c") || path.endsWith(".h") || path.endsWith(".cc")
            || path.endsWith(".cpp") || path.endsWith(".sh") || path.endsWith(".list")
            || path.endsWith(".vert") || path.endsWith(".frag");
    }
    static boolean sourceDirectory(String path) {
        if (path == null || path.length() == 0) return true;
        return (path.equals("com") || path.startsWith("com/") || path.equals("native")
            || path.startsWith("native/")) && !path.equals("native/bionic-inputs")
            && !path.startsWith("native/bionic-inputs/") && path.indexOf("/.") < 0;
    }
}
