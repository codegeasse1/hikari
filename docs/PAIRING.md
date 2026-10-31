# Pair & sync — moving a setup between two devices over Wi-Fi

Settings → **Backup & Restore** → **Pair & sync** (`ui/screens/PairScreen.kt`).

Two Hikari installs on the same Wi-Fi move a whole setup from one to the other
with no file, no account, no cable and no cloud. The device that is already set
up shows a **QR code and a six-character code**; the new device either **scans**
the QR or **types** the code.

## The shape of it

| piece | file | what it does |
| --- | --- | --- |
| `PairProtocol` | `pair/PairProtocol.kt` | the code (alphabet, generation, normalisation), the QR payload (`hikari://pair?h=…&p=…&c=…&n=…&v=…`), the forgiving `parse()` for a pasted/scanned string, local IPv4 lookup, UDP constants |
| `PairHost` | `pair/PairHost.kt` | the sending end: a NanoHTTPD server on `8787+` and a UDP beacon, both alive only while the screen is open |
| `PairClient` | `pair/PairClient.kt` | the receiving end: discovery by broadcast, then `GET /bundle?t=<code>` over OkHttp |
| `QrCode` | `pair/QrCode.kt` | zxing: `encode()` → bitmap, `decodePlanes()` → text from one YUV frame |
| `PairScreen` | `ui/screens/PairScreen.kt` | both ends in one page, plus the camera scanner |

## The payload is a BACKUP, not a format of its own

`PairHost.start()` calls `BackupManager.export(app)` **once** and serves those
bytes; `PairClient.download()` returns them; `BackupManager.restore(app, bytes)`
applies them. So pairing has no serialisation of its own, and it inherits
everything the file path already guarantees:

- the same contents (all preferences + the extension/scraper files under
  `cs3/`, `hiki/`, `nuvio/scrapers`, `nuvio/settings`);
- the same path-traversal validation on every restored file;
- the same `refreshLiveState()` afterwards (element blocks, WebView UA, DNS,
  language, extension verification, provider reload);
- the same failure sentences ("That file is not a Hikari backup.").

If a change lands in `BackupManager`, pairing gets it for free — and if a
pairing transfer ever behaves differently from a file restore, the bug is in the
network half, never in the format.

## How the two devices find each other

1. **The QR** carries `h` (host IPv4), `p` (port), `c` (code), `n` (device
   name) and `v` (app version). A scan needs no discovery at all, and the guest
   can say WHAT it found ("Pixel 7", not "192.168.1.5").
2. **The typed code** needs discovery, because the user has nothing else: the
   guest broadcasts `HIKARI-PAIR?<code>` to `255.255.255.255:47821`, and a host
   whose code matches replies `HIKARI-PAIR!<port>`. The reply's *source address*
   is the host's address — nothing has to be read off a screen. A
   `WifiManager.MulticastLock` is held while the beacon listens (Wi-Fi power
   saving filters broadcasts away otherwise) and released on `stop()`.
3. **The typed address** is the fallback for a network that filters broadcasts
   (guest isolation, some APs): the address field accepts `192.168.1.5` or
   `192.168.1.5:8787`, and the send screen always prints its own address.

Ports: `8787` upward, first free one wins. The port that actually opened travels
in the QR and in the beacon's reply, so the walk is invisible to the guest.

## What keeps this safe

- **The code is the authentication.** Six characters from a 32-symbol alphabet
  (no `0/O`, no `1/I/L` — it has to be readable off a TV and typeable on a
  phone) is ~2^30 combinations. `/bundle` returns 403 for any other token, and
  the beacon only answers a probe that already carries the right code.
- **Nothing listens when the screen is closed.** `DisposableEffect { onDispose
  { host.stop() } }` stops the server and the beacon, so the window is the
  lifetime of the secret.
- **The landing page (`/`) carries no code.** It is the one route that answers
  without a token, so it deliberately only says what to do next — otherwise the
  server guarding the code would also be the place it leaks from.
- **Nothing is uploaded anywhere.** The bytes go device-to-device on the LAN,
  which is also why the screen says so.
- The receiving direction is **destructive** (it replaces this device's setup),
  so it is confirmed in a dialog before anything is fetched.

## The scanner

`QrScannerPage` is a full-screen CameraX preview (`PreviewView` + `ImageAnalysis`
with `STRATEGY_KEEP_ONLY_LATEST`) whose analyzer decodes the **Y plane** of each
frame directly (`QrCode.decodePlanes`) — no RGB conversion, which is why the
preview stays smooth while decoding. `rowStride` (not `width`) is passed as the
plane's row length: hardware pads rows, and using the visible width shears the
image so nothing ever decodes.

Decoding runs on a dedicated single-thread executor (never the main thread), the
first successful read wins (`AtomicBoolean.compareAndSet`), and the camera is
unbound when the page leaves. The scanner button is **not drawn** on a device
without a camera (a television), and a denied permission leaves the typed code,
which is the path a TV takes anyway.

## Dependencies

- `com.google.zxing:core` — QR encode/decode, pure Java, ~500 KB.
- `androidx.camera:camera-{core,camera2,lifecycle,view}` — the scanner.
- NanoHTTPD **was already on the classpath** (an Aniyomi extension API names
  `HttpServer`), so the server side added no dependency.

The manifest gains `CAMERA` (scanner only) and `CHANGE_WIFI_MULTICAST_STATE`
(the beacon), plus `android.hardware.camera.any` as `required="false"` so a
television can still install the APK.
