package com.trough.hot;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Latest explicit bone-pose request. Renderer copies a snapshot while holding this monitor. */
final class HalfSwordPose {
    static final int BREATHING = 0;
    static final int REST = 1;
    static final int TARGETS = 2;
    static final int COMPONENTS = 3;
    static final int PHYSICS = 4;
    static final int RIGID_CURRENT = 5;
    static final int RIGID_PREDICTED = 6;
    static final int MAX_TARGETS = 256;
    static final int COMPONENT_COUNT = 67;
    static final int COMPONENT_BYTES = COMPONENT_COUNT * 96;
    static final int MAX_PHYSICS_ACTORS = 22;
    static final int PHYSICS_INDEX_BYTES = COMPONENT_COUNT * 4;
    static final int PHYSICS_ACTOR_BYTES = MAX_PHYSICS_ACTORS * 96;
    static final int RIGID_ACTOR_BYTES = MAX_PHYSICS_ACTORS * 288;
    static final int PHYSICS_ALPHA_BYTES = COMPONENT_COUNT * 4;
    static final int PHYSICS_FRAME_BYTES = 96;
    final ByteBuffer ids = ByteBuffer.allocateDirect(MAX_TARGETS * 4).order(ByteOrder.nativeOrder());
    final ByteBuffer targets = ByteBuffer.allocateDirect(MAX_TARGETS * 56).order(ByteOrder.nativeOrder());
    final ByteBuffer componentTransforms = ByteBuffer.allocateDirect(COMPONENT_BYTES).order(ByteOrder.nativeOrder());
    final ByteBuffer physicsParents = ByteBuffer.allocateDirect(PHYSICS_INDEX_BYTES).order(ByteOrder.nativeOrder());
    final ByteBuffer physicsMap = ByteBuffer.allocateDirect(PHYSICS_INDEX_BYTES).order(ByteOrder.nativeOrder());
    final ByteBuffer physicsActors = ByteBuffer.allocateDirect(PHYSICS_ACTOR_BYTES).order(ByteOrder.nativeOrder());
    final ByteBuffer rigidActors = ByteBuffer.allocateDirect(RIGID_ACTOR_BYTES).order(ByteOrder.nativeOrder());
    final ByteBuffer physicsAlphas = ByteBuffer.allocateDirect(PHYSICS_ALPHA_BYTES).order(ByteOrder.nativeOrder());
    final ByteBuffer physicsOldLocal = ByteBuffer.allocateDirect(COMPONENT_BYTES).order(ByteOrder.nativeOrder());
    final ByteBuffer physicsComponentFrame = ByteBuffer.allocateDirect(PHYSICS_FRAME_BYTES).order(ByteOrder.nativeOrder());
    long revision;
    int mode = BREATHING;
    int count;
    int actorCount;

    synchronized boolean publish(ByteBuffer sourceIds, ByteBuffer sourceTargets, int size) {
        if (size < 0 || size > MAX_TARGETS) return false;
        if (size == 0) {
            mode = REST;
            count = 0;
            revision++;
            notifyAll();
            return true;
        }
        if (sourceIds == null || sourceTargets == null
                || sourceIds.capacity() < size * 4 || sourceTargets.capacity() < size * 56) return false;
        // Copy absolute offset zero without touching either caller cursor.
        // Honor caller limits; unlike JNI this boundary cannot read beyond them.
        if (sourceIds.limit() < size * 4 || sourceTargets.limit() < size * 56) return false;
        for (int i = 0; i < size * 4; i++) ids.put(i, sourceIds.get(i));
        for (int i = 0; i < size * 56; i++) targets.put(i, sourceTargets.get(i));
        count = size;
        mode = TARGETS;
        revision++;
        notifyAll();
        return true;
    }

    synchronized boolean publishComponents(ByteBuffer source, int size) {
        if (size != COMPONENT_COUNT || source == null
                || source.capacity() < COMPONENT_BYTES || source.limit() < COMPONENT_BYTES) return false;
        // Complete component-space TRS records, copied from absolute offset zero.
        // Publication only queues bytes; native validation decides whether to apply them.
        for (int i = 0; i < COMPONENT_BYTES; i++) componentTransforms.put(i, source.get(i));
        count = COMPONENT_COUNT;
        mode = COMPONENTS;
        revision++;
        notifyAll();
        return true;
    }

    synchronized boolean publishPhysics(ByteBuffer sourceParents, ByteBuffer sourceMap,
            ByteBuffer sourceActors, int actorSize, ByteBuffer sourceAlphas,
            ByteBuffer sourceOldLocal, ByteBuffer sourceComponentFrame) {
        if (actorSize < 0 || actorSize > MAX_PHYSICS_ACTORS) return false;
        int actorBytes = actorSize * 96;
        // Validate every required source before changing any mailbox bytes.
        // Zero actors has no actor-buffer access and remains a PHYSICS request.
        if (!hasBytes(sourceParents, PHYSICS_INDEX_BYTES)
                || !hasBytes(sourceMap, PHYSICS_INDEX_BYTES)
                || (actorSize != 0 && !hasBytes(sourceActors, actorBytes))
                || !hasBytes(sourceAlphas, PHYSICS_ALPHA_BYTES)
                || !hasBytes(sourceOldLocal, COMPONENT_BYTES)
                || !hasBytes(sourceComponentFrame, PHYSICS_FRAME_BYTES)) return false;
        // Queue explicit caller-selected frames as opaque bytes. Native code
        // validates numbers; this mailbox does not choose or promote a pose.
        copyBytes(physicsParents, sourceParents, PHYSICS_INDEX_BYTES);
        copyBytes(physicsMap, sourceMap, PHYSICS_INDEX_BYTES);
        if (actorSize != 0) copyBytes(physicsActors, sourceActors, actorBytes);
        copyBytes(physicsAlphas, sourceAlphas, PHYSICS_ALPHA_BYTES);
        copyBytes(physicsOldLocal, sourceOldLocal, COMPONENT_BYTES);
        copyBytes(physicsComponentFrame, sourceComponentFrame, PHYSICS_FRAME_BYTES);
        actorCount = actorSize;
        count = COMPONENT_COUNT;
        mode = PHYSICS;
        revision++;
        notifyAll();
        return true;
    }

    synchronized boolean publishRigid(int requestedMode, ByteBuffer sourceParents,
            ByteBuffer sourceMap, ByteBuffer sourceActors, int actorSize,
            ByteBuffer sourceAlphas, ByteBuffer sourceOldLocal,
            ByteBuffer sourceComponentFrame) {
        if ((requestedMode != RIGID_CURRENT && requestedMode != RIGID_PREDICTED)
                || actorSize < 0 || actorSize > MAX_PHYSICS_ACTORS) return false;
        int actorBytes = actorSize * 288;
        // Validate all required fields before changing shared frame metadata.
        // Zero actors accesses no actor bytes and retains the requested stage.
        if (!hasBytes(sourceParents, PHYSICS_INDEX_BYTES)
                || !hasBytes(sourceMap, PHYSICS_INDEX_BYTES)
                || (actorSize != 0 && !hasBytes(sourceActors, actorBytes))
                || !hasBytes(sourceAlphas, PHYSICS_ALPHA_BYTES)
                || !hasBytes(sourceOldLocal, COMPONENT_BYTES)
                || !hasBytes(sourceComponentFrame, PHYSICS_FRAME_BYTES)) return false;
        // Keep typed actor records separate from PHYSICS world-transform records.
        // Stage selection queues bytes; it does not promote or validate a pose.
        copyBytes(physicsParents, sourceParents, PHYSICS_INDEX_BYTES);
        copyBytes(physicsMap, sourceMap, PHYSICS_INDEX_BYTES);
        if (actorSize != 0) copyBytes(rigidActors, sourceActors, actorBytes);
        copyBytes(physicsAlphas, sourceAlphas, PHYSICS_ALPHA_BYTES);
        copyBytes(physicsOldLocal, sourceOldLocal, COMPONENT_BYTES);
        copyBytes(physicsComponentFrame, sourceComponentFrame, PHYSICS_FRAME_BYTES);
        actorCount = actorSize;
        count = COMPONENT_COUNT;
        mode = requestedMode;
        revision++;
        notifyAll();
        return true;
    }

    private static boolean hasBytes(ByteBuffer source, int bytes) {
        return source != null && source.capacity() >= bytes && source.limit() >= bytes;
    }

    private static void copyBytes(ByteBuffer destination, ByteBuffer source, int bytes) {
        // Absolute offset zero leaves both caller and mailbox cursors untouched.
        for (int i = 0; i < bytes; i++) destination.put(i, source.get(i));
    }

    synchronized boolean request(int requestedMode) {
        if (requestedMode != BREATHING && requestedMode != REST) return false;
        mode = requestedMode;
        count = 0;
        revision++;
        notifyAll();
        return true;
    }
}
