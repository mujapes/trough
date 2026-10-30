# The host: Trough's fixed APK

The APK is the only part of Trough that needs reinstalling, so it holds mechanism only and is
designed to be final (DESIGN.md "The host"). Everything else ships as signed hot bundles
(tools/hot.sh) or is self-built on the phone. Change the host only for something a bundle
cannot do at runtime: a manifest declaration, or a lifecycle hook Android gives only to
declared classes. A host change needs the user to install `Download/trough/trough-os.apk`.

Source: `app/AndroidManifest.xml`, `app/src/com/trough/app/*.java`, `app/res/xml/*`.
Build: `FRAMEWORK_RES=/usr/share/android-framework-res/framework-res.apk tools/build.sh`
(Debian's arm64 aapt2 cannot read the SDK's android-35 resource table; Debian's Android 14
framework-res works, but lacks names newer than 14, e.g. the FGS types specialUse and
remoteMessaging, which do not matter at targetSdk 28). CI builds with Google's aapt2, but
signs with a CI key, so a CI APK cannot update the phone's install. Sign with
~/.trough/debug.keystore (never committed).

## Classes

| Class | Role |
| --- | --- |
| `TroughApp` | Application. In the main process only: `HotLoader.init`, and schedules the revive job. Slot and isolated processes are left alone. |
| `HotLoader` | One per main process (`HotLoader.process`). Verifies, activates and recovers bundles; owns the root view; polls Download/trough/hot (1 s visible, 5 s otherwise); forwards intents and component events. |
| `TroughActivity` | singleTask. Shows `loader.root()`, reports attach/detach/visibility, forwards every Intent. |
| `TroughService` | Foreground service (sticky, `stopWithTask=false`), notification with Open and Quit. Quit stops the bundle and kills the process. |
| `Components` | Every other declared component, as forwarding shims (below). |
| `Slot` | Eight process slots `:p0`..`:p7`, each an Activity (`Slot$A<n>`) and a Service (`Slot$S<n>`). They load the last settled bundle themselves. |

## Bundle entry points

The bundle's entry class (manifest.json "entry", today `com.trough.hot.Main`) exposes these
public static methods. All are optional except one of `boot`/`start`. Missing ones fall back as stated.

| Method | Called | Fallback when absent |
| --- | --- | --- |
| `boot(Context app, FrameLayout root, File dir)` | at activation, with or without a window (v2) | use `start` (v1) |
| `start(Activity, FrameLayout root, File dir)` | v1: at activation once an Activity exists | (bundle waits for a window) |
| `attach(Activity)` | a new Activity shows `root` while the bundle keeps running; throw to refuse | v1: the bundle is stopped with its Activity and restarted on the next |
| `detach()` | the Activity is gone (Recents swipe, finish, recreation) | nothing |
| `intent(Intent)` | every Intent delivered to TroughActivity; held (max 16) until a bundle runs | dropped |
| `dispatch(Object host, String event, Object[] args)` -> Object | every component callback, on the thread Android uses (main or binder) | the component's default |
| `stop()` | replacement by a new bundle, or Quit | nothing |
| `slotActivity(Activity, Bundle)` | a slot Activity started (in its slot process) | the slot finishes |
| `slotBind(Service, Intent)` -> IBinder | a slot Service bound (in its slot process) | null |

Isolated sandboxes cannot read the bundle. The bundle binds `Components$Sandbox0`/`1` with
`Context.bindIsolatedService(intent, flags, instanceName, executor, conn)` (each instance name
is its own process), then sends transaction 1 with a `ParcelFileDescriptor` of dex bytes and an
entry class name. The sandbox loads it with `InMemoryDexClassLoader` and replies with the binder
returned by `public static IBinder isolated(Service host, Intent intent)`.

Recovery rules are unchanged (HotLoader comment, docs/HOT_RELOAD.md): `good`, `starting`,
`bad/<sha>`. A v1 bundle waiting for a window has not started, so it is not "starting".

## Components and their events

`dispatch` receives the host object itself, so the bundle calls its Android APIs directly
(`getCurrentInputConnection()`, `vpn.new Builder()`, `wallpaper.new Engine() {...}`). Events
return `Boolean.TRUE` when the bundle handled something that otherwise has a default.

| Component (`Components$...`) | Android role / grant | Events -> expected return |
| --- | --- | --- |
| `Boot`, `Packages`, `Sms`, `Mms`, `Events` (receivers) | boot, package changes, default SMS app (SMS_DELIVER, WAP_PUSH_DELIVER), power/time/locale | `receive(Context, Intent)`. Boot also starts TroughService. |
| `Revive`, `Jobs` (JobService) | `Revive` = TroughApp's 15-minute job id 1; `Jobs` for the bundle's own jobs | `job(JobParameters)`, `jobStop` -> Boolean (keep running / reschedule) |
| `RespondViaMessage` | SMS role quick replies | `start(Intent)`, `bind(Intent)` -> IBinder |
| `Ime` | Trough as the system keyboard | `create`, `inputView` -> View, `candidatesView` -> View, `startInput(EditorInfo, Boolean)`, `startInputView`, `finishInputView(Boolean)`, `finishInput`, `selection(6 ints)`, `fullscreen` -> Boolean, `keyDown`/`keyUp(Integer, KeyEvent)` -> Boolean, `destroy` |
| `Notifications` | notification access | `connected`, `disconnected`, `posted(sbn, ranking)`, `removed(sbn, ranking, reason)`, `ranking(map)` |
| `Vpn` | Trough's network layer (tun fd) | `start(Intent)`, `revoke`, `destroy` |
| `InCall`, `Connections`, `Screening` | default dialer, self-managed calls, call screening | `callAdded/callRemoved(Call)`, `foreground(Boolean)`, `canAddCall(Boolean)`; `outgoing/incoming(handle, request)` -> Connection (else failed); `screen(Call.Details)` -> Boolean (else allowed) |
| `Voice`, `VoiceSessions`, `Recognition` | default assistant | `ready`, `shutdown`; `session(Bundle)` -> VoiceInteractionSession; `listen(Intent, Callback)` -> Boolean (else ERROR_CLIENT), `cancel`, `stopListening` |
| `Tile` | quick-settings tile | `click` -> Boolean (else opens Trough), `listening(Boolean)`, `added`, `removed` |
| `Dream` | screensaver | `attached`, `started`, `stopped`, `detached` |
| `Wallpaper` | live wallpaper | `engine` -> WallpaperService.Engine |
| `Media` | media browser (car, Bluetooth controls) | `root(pkg, uid, hints)` -> BrowserRoot, `children(parent, Result)` -> Boolean (else empty) |
| `Autofill` | autofill service | `fill(request, signal, callback)`, `save(request, callback)` -> Boolean (else empty success) |
| `Credentials` | credential provider (passwords, passkeys) | `get`, `create`, `clear` (request, signal, OutcomeReceiver) -> Boolean (else empty result) |
| `Apdu` | NFC card emulation (AID F054524F554748; more at runtime) | `apdu(byte[], Bundle)` -> byte[] (else 6F00), `deactivated(Integer)` |
| `Admin` | device admin (and device owner, if provisioned over adb) | `receive(Context, Intent)` |
| `Files` (`t.t.files`) | content Trough shares out | `query`, `type`, `insert`, `delete`, `update`, `call(method, arg, extras)`, `open(Uri, mode)` -> ParcelFileDescriptor |
| `Documents` (`t.t.documents`) | Trough's filesystem in Android's file picker | `roots(projection)`, `document(id, p)`, `children(parent, p, sort)` -> Cursor; `open(id, mode, signal)` -> ParcelFileDescriptor; `create`, `delete`, `rename`, `isChild` |
| `Sandbox0`, `Sandbox1` | isolated processes | binder protocol above |

TroughActivity's filters: launcher, HOME, http/https (browser role), APP_BROWSER, file/content
VIEW/EDIT of any type, mailto/geo/market/magnet/ftp/ssh/sftp/git/rtsp/intent/data/about/trough
schemes, SEND/SEND_MULTIPLE, PROCESS_TEXT, ASSIST/VOICE_COMMAND/WEB_SEARCH/SEARCH, CREATE_NOTE,
DIAL and tel:, sms/smsto/mms/mmsto, APK files and INSTALL_PACKAGE, camera capture, media search,
music, alarms and timers, GET_CONTENT/PICK/OPEN_DOCUMENT, USB device/accessory attached (any
device), NFC NDEF/TECH/TAG, app preferences and network usage.

Roles become active only when the user picks Trough in Settings > Apps > Default apps (home,
browser, phone, SMS, digital assistant, autofill/passwords) or grants the special access
(notification access, VPN, input method, device admin, battery, display over other apps, all
files access, usage access, exact alarms).

## Not declared

An accessibility service (user decision, 2026-10-30). Anything system-signature-only that no
grant path can reach is left out as noise; development permissions are declared because
`pm grant` over the shell-uid bridge can grant them.
