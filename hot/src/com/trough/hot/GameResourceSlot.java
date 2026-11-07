package com.trough.hot;
/** Owns one asynchronous resource; a rejected arrival remains the caller's responsibility. */
final class GameResourceSlot<T> {
 private T resource;private boolean closed;
 synchronized boolean install(T candidate){if(candidate==null)throw new IllegalArgumentException("Missing resource");if(closed||resource!=null)return false;resource=candidate;return true;}
 synchronized T close(){if(closed)return null;closed=true;T result=resource;resource=null;return result;}
}
