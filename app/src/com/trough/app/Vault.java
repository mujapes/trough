package com.trough.app;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-lifetime handoff between bundle generations (docs/HOT_RELOAD.md "The Forge lesson").
 * Bundle classes differ per class loader, so generations exchange objects of platform or host
 * types here: views and surfaces, swapchain owners, ParcelFileDescriptors, ByteBuffers, native
 * handles (as Long), JSON, strings. The host never reads it; keys and lifetimes are bundle policy.
 */
public final class Vault {
    private Vault() {}
    public static final ConcurrentHashMap<String, Object> objects = new ConcurrentHashMap<>();
}
