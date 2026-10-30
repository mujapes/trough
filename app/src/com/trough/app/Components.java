package com.trough.app;

import android.app.Service;
import android.app.admin.DeviceAdminReceiver;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.inputmethodservice.InputMethodService;
import android.media.browse.MediaBrowser;
import android.net.Uri;
import android.net.VpnService;
import android.nfc.cardemulation.HostApduService;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.OutcomeReceiver;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.service.autofill.AutofillService;
import android.service.autofill.FillCallback;
import android.service.autofill.FillRequest;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.service.credentials.BeginCreateCredentialRequest;
import android.service.credentials.BeginCreateCredentialResponse;
import android.service.credentials.BeginGetCredentialRequest;
import android.service.credentials.BeginGetCredentialResponse;
import android.service.credentials.ClearCredentialStateRequest;
import android.service.credentials.CredentialProviderService;
import android.service.dreams.DreamService;
import android.service.media.MediaBrowserService;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.service.quicksettings.TileService;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;
import android.service.wallpaper.WallpaperService;
import android.speech.RecognitionService;
import android.telecom.Call;
import android.telecom.CallScreeningService;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.InCallService;
import android.telecom.PhoneAccountHandle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.InMemoryDexClassLoader;

/**
 * Every Android component Trough declares, as mechanism only (docs/HOST.md). Each forwards its
 * callbacks to the running bundle's dispatch(host, event, args) and falls back to a harmless
 * default when no bundle handles them, so declaring a component never changes behaviour until a
 * bundle implements it. Roles and grants (IME, notification access, VPN, default dialer/SMS/
 * assistant/browser/home, autofill, credentials, device admin) stay off until the user enables
 * them in Android's settings. Bundles get the host object itself (call its APIs directly, e.g.
 * getCurrentInputConnection(), vpn.new Builder(), wallpaper.new Engine() {...}).
 */
public final class Components {
    private Components() {}

    static Object send(Object host, String event, Object... args) {
        HotLoader l = HotLoader.process;
        return l == null ? null : l.dispatch(host, event, args);
    }
    private static boolean yes(Object o) { return Boolean.TRUE.equals(o); }

    // --- process lifetime ---

    /** Boot, package replaced: the process is up (TroughApp started the loader); keep it up. */
    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                    || "android.intent.action.QUICKBOOT_POWERON".equals(a))
                c.startForegroundService(new Intent(c, TroughService.class));
            send(this, "receive", c, i);
        }
    }
    public static final class Boot extends Receiver {}
    public static final class Packages extends Receiver {}
    public static final class Sms extends Receiver {}       // SMS_DELIVER: default SMS app role
    public static final class Mms extends Receiver {}       // WAP_PUSH_DELIVER: default SMS app role
    public static final class Events extends Receiver {}    // everything else a bundle registers for statically

    /** TroughApp's periodic revive job. */
    public static final class Revive extends JobService {
        @Override public boolean onStartJob(JobParameters p) {
            startForegroundService(new Intent(this, TroughService.class));
            return yes(send(this, "job", p));
        }
        @Override public boolean onStopJob(JobParameters p) { return yes(send(this, "jobStop", p)); }
    }
    /** Jobs a bundle schedules itself (any job id except TroughApp.REVIVE_JOB). */
    public static final class Jobs extends JobService {
        @Override public boolean onStartJob(JobParameters p) { return yes(send(this, "job", p)); }
        @Override public boolean onStopJob(JobParameters p) { return yes(send(this, "jobStop", p)); }
    }

    /** A plain service for anything bound or started by intent (RESPOND_VIA_MESSAGE for the SMS role, ...). */
    public static class Plain extends Service {
        @Override public int onStartCommand(Intent i, int flags, int id) { send(this, "start", i); return START_NOT_STICKY; }
        @Override public IBinder onBind(Intent i) { Object b = send(this, "bind", i); return b instanceof IBinder ? (IBinder) b : null; }
    }
    public static final class RespondViaMessage extends Plain {}

    // --- input, notifications, network ---

    public static final class Ime extends InputMethodService {
        @Override public void onCreate() { super.onCreate(); send(this, "create"); }
        @Override public View onCreateInputView() { Object v = send(this, "inputView"); return v instanceof View ? (View) v : super.onCreateInputView(); }
        @Override public View onCreateCandidatesView() { Object v = send(this, "candidatesView"); return v instanceof View ? (View) v : super.onCreateCandidatesView(); }
        @Override public void onStartInput(EditorInfo e, boolean restarting) { super.onStartInput(e, restarting); send(this, "startInput", e, restarting); }
        @Override public void onStartInputView(EditorInfo e, boolean restarting) { super.onStartInputView(e, restarting); send(this, "startInputView", e, restarting); }
        @Override public void onFinishInputView(boolean finishing) { send(this, "finishInputView", finishing); super.onFinishInputView(finishing); }
        @Override public void onFinishInput() { send(this, "finishInput"); super.onFinishInput(); }
        @Override public void onUpdateSelection(int os, int oe, int ns, int ne, int cs, int ce) {
            super.onUpdateSelection(os, oe, ns, ne, cs, ce);
            send(this, "selection", os, oe, ns, ne, cs, ce);
        }
        @Override public boolean onEvaluateFullscreenMode() { return yes(send(this, "fullscreen")); }
        @Override public boolean onKeyDown(int k, KeyEvent e) { return yes(send(this, "keyDown", k, e)) || super.onKeyDown(k, e); }
        @Override public boolean onKeyUp(int k, KeyEvent e) { return yes(send(this, "keyUp", k, e)) || super.onKeyUp(k, e); }
        @Override public void onDestroy() { send(this, "destroy"); super.onDestroy(); }
    }

    public static final class Notifications extends NotificationListenerService {
        @Override public void onListenerConnected() { send(this, "connected"); }
        @Override public void onListenerDisconnected() { send(this, "disconnected"); }
        @Override public void onNotificationPosted(StatusBarNotification n, RankingMap r) { send(this, "posted", n, r); }
        @Override public void onNotificationRemoved(StatusBarNotification n, RankingMap r, int reason) { send(this, "removed", n, r, reason); }
        @Override public void onNotificationRankingUpdate(RankingMap r) { send(this, "ranking", r); }
    }

    /** Trough's own network layer when the user enables it: every packet through a tun fd. */
    public static final class Vpn extends VpnService {
        @Override public int onStartCommand(Intent i, int flags, int id) { send(this, "start", i); return START_STICKY; }
        @Override public void onRevoke() { send(this, "revoke"); super.onRevoke(); }
        @Override public void onDestroy() { send(this, "destroy"); super.onDestroy(); }
    }

    // --- telephony roles (default dialer, default SMS, call screening, own VoIP calls) ---

    public static final class InCall extends InCallService {
        @Override public void onCallAdded(Call c) { send(this, "callAdded", c); }
        @Override public void onCallRemoved(Call c) { send(this, "callRemoved", c); }
        @Override public void onBringToForeground(boolean showDialpad) { send(this, "foreground", showDialpad); }
        @Override public void onCanAddCallChanged(boolean can) { send(this, "canAddCall", can); }
    }

    public static final class Connections extends ConnectionService {
        @Override public Connection onCreateOutgoingConnection(PhoneAccountHandle a, ConnectionRequest r) { return connection(send(this, "outgoing", a, r)); }
        @Override public Connection onCreateIncomingConnection(PhoneAccountHandle a, ConnectionRequest r) { return connection(send(this, "incoming", a, r)); }
        private static Connection connection(Object o) {
            return o instanceof Connection ? (Connection) o : Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR));
        }
    }

    public static final class Screening extends CallScreeningService {
        @Override public void onScreenCall(Call.Details d) {
            if (!yes(send(this, "screen", d))) respondToCall(d, new CallResponse.Builder().build());   // unhandled: allow
        }
    }

    // --- assistant role ---

    public static final class Voice extends VoiceInteractionService {
        @Override public void onReady() { super.onReady(); send(this, "ready"); }
        @Override public void onShutdown() { send(this, "shutdown"); super.onShutdown(); }
    }
    public static final class VoiceSessions extends VoiceInteractionSessionService {
        @Override public VoiceInteractionSession onNewSession(Bundle args) {
            Object s = send(this, "session", args);
            return s instanceof VoiceInteractionSession ? (VoiceInteractionSession) s : new VoiceInteractionSession(this);
        }
    }
    public static final class Recognition extends RecognitionService {
        @Override protected void onStartListening(Intent i, Callback cb) {
            if (!yes(send(this, "listen", i, cb))) try { cb.error(android.speech.SpeechRecognizer.ERROR_CLIENT); } catch (Exception ignored) {}
        }
        @Override protected void onCancel(Callback cb) { send(this, "cancel", cb); }
        @Override protected void onStopListening(Callback cb) { send(this, "stopListening", cb); }
    }

    // --- system surfaces ---

    public static final class Tile extends TileService {
        @Override public void onClick() {
            if (!yes(send(this, "click"))) startActivityAndCollapse(new Intent(this, TroughActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        @Override public void onStartListening() { send(this, "listening", true); }
        @Override public void onStopListening() { send(this, "listening", false); }
        @Override public void onTileAdded() { send(this, "added"); }
        @Override public void onTileRemoved() { send(this, "removed"); }
    }

    public static final class Dream extends DreamService {
        @Override public void onAttachedToWindow() { super.onAttachedToWindow(); send(this, "attached"); }
        @Override public void onDreamingStarted() { super.onDreamingStarted(); send(this, "started"); }
        @Override public void onDreamingStopped() { send(this, "stopped"); super.onDreamingStopped(); }
        @Override public void onDetachedFromWindow() { send(this, "detached"); super.onDetachedFromWindow(); }
    }

    public static final class Wallpaper extends WallpaperService {
        @Override public Engine onCreateEngine() { Object e = send(this, "engine"); return e instanceof Engine ? (Engine) e : new Engine(); }
    }

    public static final class Media extends MediaBrowserService {
        @Override public BrowserRoot onGetRoot(String pkg, int uid, Bundle hints) {
            Object r = send(this, "root", pkg, uid, hints);
            return r instanceof BrowserRoot ? (BrowserRoot) r : null;
        }
        @Override public void onLoadChildren(String parent, Result<List<MediaBrowser.MediaItem>> result) {
            if (!yes(send(this, "children", parent, result))) result.sendResult(new ArrayList<MediaBrowser.MediaItem>());
        }
    }

    // --- credentials, autofill, NFC card emulation, device admin ---

    public static final class Autofill extends AutofillService {
        @Override public void onFillRequest(FillRequest r, CancellationSignal s, FillCallback cb) { if (!yes(send(this, "fill", r, s, cb))) cb.onSuccess(null); }
        @Override public void onSaveRequest(SaveRequest r, SaveCallback cb) { if (!yes(send(this, "save", r, cb))) cb.onSuccess(); }
    }

    public static final class Credentials extends CredentialProviderService {
        @Override public void onBeginGetCredential(BeginGetCredentialRequest r, CancellationSignal s,
                OutcomeReceiver<BeginGetCredentialResponse, android.credentials.GetCredentialException> out) {
            if (!yes(send(this, "get", r, s, out))) out.onResult(new BeginGetCredentialResponse.Builder().build());
        }
        @Override public void onBeginCreateCredential(BeginCreateCredentialRequest r, CancellationSignal s,
                OutcomeReceiver<BeginCreateCredentialResponse, android.credentials.CreateCredentialException> out) {
            if (!yes(send(this, "create", r, s, out))) out.onResult(new BeginCreateCredentialResponse.Builder().build());
        }
        @Override public void onClearCredentialState(ClearCredentialStateRequest r, CancellationSignal s,
                OutcomeReceiver<Void, android.credentials.ClearCredentialStateException> out) {
            if (!yes(send(this, "clear", r, s, out))) out.onResult(null);
        }
    }

    public static final class Apdu extends HostApduService {
        @Override public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
            Object r = send(this, "apdu", apdu, extras);
            return r instanceof byte[] ? (byte[]) r : new byte[]{0x6F, 0x00};
        }
        @Override public void onDeactivated(int reason) { send(this, "deactivated", reason); }
    }

    public static final class Admin extends DeviceAdminReceiver {
        @Override public void onReceive(Context c, Intent i) { super.onReceive(c, i); send(this, "receive", c, i); }
    }

    // --- content: Trough's files to other apps and the system file picker ---

    /** content://t.t.files/...: whatever the bundle shares (query/openFile/getType/insert/update/delete). */
    public static final class Files extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { Object r = send(this, "query", u, p, s, a, o); return r instanceof Cursor ? (Cursor) r : null; }
        @Override public String getType(Uri u) { Object r = send(this, "type", u); return r instanceof String ? (String) r : null; }
        @Override public Uri insert(Uri u, ContentValues v) { Object r = send(this, "insert", u, v); return r instanceof Uri ? (Uri) r : null; }
        @Override public int delete(Uri u, String s, String[] a) { Object r = send(this, "delete", u, s, a); return r instanceof Integer ? (Integer) r : 0; }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) { Object r = send(this, "update", u, v, s, a); return r instanceof Integer ? (Integer) r : 0; }
        @Override public Bundle call(String method, String arg, Bundle extras) { Object r = send(this, "call", method, arg, extras); return r instanceof Bundle ? (Bundle) r : null; }
        @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
            Object r = send(this, "open", u, mode);
            if (r instanceof ParcelFileDescriptor) return (ParcelFileDescriptor) r;
            throw new FileNotFoundException(u.toString());
        }
    }

    /** Trough's filesystem in Android's file picker (Termux does the same for its home). */
    public static final class Documents extends DocumentsProvider {
        private static final String[] ROOT = {DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_ICON};
        private static final String[] DOC = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        @Override public boolean onCreate() { return true; }
        private static Cursor cursor(Object r, String[] p, String[] def) { return r instanceof Cursor ? (Cursor) r : new MatrixCursor(p != null ? p : def); }
        @Override public Cursor queryRoots(String[] p) { return cursor(send(this, "roots", (Object) p), p, ROOT); }
        @Override public Cursor queryDocument(String id, String[] p) throws FileNotFoundException { return cursor(send(this, "document", id, p), p, DOC); }
        @Override public Cursor queryChildDocuments(String parent, String[] p, String sort) throws FileNotFoundException { return cursor(send(this, "children", parent, p, sort), p, DOC); }
        @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal s) throws FileNotFoundException {
            Object r = send(this, "open", id, mode, s);
            if (r instanceof ParcelFileDescriptor) return (ParcelFileDescriptor) r;
            throw new FileNotFoundException(id);
        }
        @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
            Object r = send(this, "create", parent, mime, name);
            if (r instanceof String) return (String) r;
            throw new FileNotFoundException(parent);
        }
        @Override public void deleteDocument(String id) throws FileNotFoundException { if (!yes(send(this, "delete", id))) throw new FileNotFoundException(id); }
        @Override public String renameDocument(String id, String name) throws FileNotFoundException { Object r = send(this, "rename", id, name); return r instanceof String ? (String) r : null; }
        @Override public boolean isChildDocument(String parent, String id) { return yes(send(this, "isChild", parent, id)); }
    }

    // --- isolated processes: untrusted work (web content, guest code) with no app privileges ---

    /**
     * android:isolatedProcess: no app files, no network, no permissions. Bind with
     * Context.bindIsolatedService(intent, flags, instanceName, executor, connection): every
     * instanceName is its own process, so one declaration gives any number of sandboxes.
     * Protocol on the returned binder: transaction LOAD (1) with a ParcelFileDescriptor of dex
     * bytes and the entry class name; the sandbox loads it in memory, calls
     *     public static IBinder isolated(Service host, Intent intent)
     * and replies with that binder. The sandbox cannot read the bundle itself.
     */
    public static class Sandbox extends Service {
        static final int LOAD = 1;
        private Intent bound;
        @Override public IBinder onBind(Intent i) {
            bound = i;
            final Service host = this;
            return new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
                    if (code != LOAD) return super.onTransact(code, data, reply, flags);
                    try (ParcelFileDescriptor fd = data.readFileDescriptor(); FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
                        String entry = data.readString();
                        byte[] dex = HotLoader.readAll(in);
                        ClassLoader l = new InMemoryDexClassLoader(ByteBuffer.wrap(dex), Sandbox.class.getClassLoader());
                        IBinder b = (IBinder) l.loadClass(entry).getMethod("isolated", Service.class, Intent.class).invoke(null, host, bound);
                        if (reply != null) { reply.writeNoException(); reply.writeStrongBinder(b); }
                    } catch (Throwable t) {
                        if (reply != null) reply.writeException(new IllegalStateException(String.valueOf(t.getCause() != null ? t.getCause() : t)));
                    }
                    return true;
                }
            };
        }
    }
    public static final class Sandbox0 extends Sandbox {}
    public static final class Sandbox1 extends Sandbox {}
}
