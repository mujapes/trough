package com.trough.hot;

import android.content.Context;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import java.io.FileDescriptor;

/** The Android host for one draft (docs/INPUT.md): gives the IME an EditorInfo and a TextInput
 * over the native queue, and tells the IME when the text changed under it. The keeper signals
 * an eventfd when it merges an external revision; the main Looper watches that fd, so there is
 * no polling. Rendering belongs to the native presenter; this view only hosts input. */
final class EditorView extends View {
    private static final int SURROUNDING = 1024;
    private long keeper, queue;
    private ParcelFileDescriptor notify;
    private long lastExternal;
    volatile int restarts, events;

    EditorView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    /** Main thread. */
    void bind(long keeper, long queue) {
        this.keeper = keeper; this.queue = queue;
        lastExternal = NativeCore.value(queue)[6];
        try {
            notify = ParcelFileDescriptor.fromFd(NativeCore.notifyFd(keeper));
        } catch (java.io.IOException e) { throw new IllegalStateException("notify fd", e); }
        final FileDescriptor fd = notify.getFileDescriptor();
        Looper.getMainLooper().getQueue().addOnFileDescriptorEventListener(fd,
            MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT, new MessageQueue.OnFileDescriptorEventListener() {
                public int onFileDescriptorEvents(FileDescriptor f, int ev) {
                    try { Os.read(f, new byte[8], 0, 8); } catch (ErrnoException | java.io.InterruptedIOException ignored) {}
                    events++;
                    changed();
                    return EVENT_INPUT;
                }
            });
    }

    /** Main thread: stop watching before the keeper stops (it owns the eventfd). */
    void unbind() {
        if (notify == null) return;
        Looper.getMainLooper().getQueue().removeOnFileDescriptorEventListener(notify.getFileDescriptor());
        try { notify.close(); } catch (java.io.IOException ignored) {}
        notify = null; keeper = queue = 0;
    }

    private void changed() {
        long[] v = NativeCore.value(queue);
        if (v == null || v[6] == lastExternal) return;     // failures also signal; text did not move
        lastExternal = v[6];
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.restartInput(this);           // text changed outside the IME
        restarts++;
    }

    @Override public boolean onCheckIsTextEditor() { return true; }

    @Override public InputConnection onCreateInputConnection(EditorInfo out) {
        if (queue == 0) return null;
        out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
        long[] v = NativeCore.value(queue);
        out.initialSelStart = (int) v[1];
        out.initialSelEnd = (int) v[2];
        int a = (int) Math.min(v[1], v[2]), b = (int) Math.max(v[1], v[2]);
        int from = Math.max(0, a - SURROUNDING), to = (int) Math.min(v[0], (long) b + SURROUNDING);
        String around = NativeCore.slice(queue, from, to);
        if (around != null) out.setInitialSurroundingSubText(around, from);
        return new TextInput(this, keeper, queue);
    }
}
