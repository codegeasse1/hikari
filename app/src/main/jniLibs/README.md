# TDLib's native libraries (the Telegram client)

These files are what makes `com.hikari.app.telegram.Td` a real Telegram client:
TDLib itself (the MTProto implementation, its database and its media cache) as a
prebuilt Android native library, plus the Java bindings it registers its natives
against.

Hikari does not build TDLib from source. Compiling TDLib takes an hour and a
full C++/OpenSSL toolchain in CI for a library that never changes between our
releases, so the prebuilt Android bundle Telegram X publishes is used instead —
it is TDLib proper, built for both of our ABIs, and it is the same artifact that
ships inside a client with millions of installs.

## Where each file came from

Everything under `arm64-v8a/` and `armeabi-v7a/` was extracted unmodified from
**Telegram X 0.29.0.1813**:

    https://github.com/TGX-Android/Telegram-X/releases/download/v0.29.0.1813/Telegram-X-0.29.0.1813.apk

| file | what it is |
| --- | --- |
| `libtdjni.so` | TDLib + the JNI bridge Telegram X uses (`JNI_OnLoad` registers every native method against `org.drinkless.tdlib.Client`). |
| `libsslx.so`, `libcryptox.so` | Telegram X's OpenSSL forks — `libtdjni.so` links against both by soname. |
| `libcxx_shared.so` | The C++ runtime. **The file in the APK is `libc++_shared.so`**; see the rename note below. |

The matching Java side lives in
`app/src/main/java/org/drinkless/tdlib/{TdApi.java,Client.java}`, taken from the
bundle repository Telegram X builds it from:

    https://github.com/TGX-Android/tdlib  (main, src/main/java/org/drinkless/tdlib)

`TdApi.java` carries `GIT_COMMIT_HASH` — the TDLib commit it was generated from —
and that same hash appears inside `libtdjni.so`, which is how the two halves are
known to match:

    1085f9cebc5a62379991ae1652673954f229c1f

`TdApi.java` is ~5 MB of generated Java and ~2000 classes. It is kept whole by
`proguard-rules.pro` section 3c on purpose: TDLib finds its own classes **by
name** at runtime (that is how an incoming update arrives as
`TdApi.UpdateNewMessage`), so R8 removing "unused" classes would break the client
only in the release build.

## The `libc++_shared.so` name

Android's linker resolves `DT_NEEDED` entries by **filename**, so the runtime has
to be packaged as `lib/<abi>/libc++_shared.so` or nothing loads. That name cannot
exist in this repository: a `+` is not a writable path in the workspace
toolchain, so the file is committed as `libcxx_shared.so` and
`app/build.gradle.kts` renames it on the way into the APK (the `stageTdJniLibs`
task + the extra `jniLibs` source directory it feeds). If the TDLib library ever
fails to load at runtime with "library libc++_shared.so not found", that rename
is the first thing to check.

## Updating

1. Download the current Telegram X APK (any release; its `lib/<abi>/` directory
   holds all four files).
2. Extract `libtdjni.so`, `libsslx.so`, `libcryptox.so` (as-is) and
   `libc++_shared.so` (renamed to `libcxx_shared.so`) into
   `app/src/main/jniLibs/<abi>/` for `arm64-v8a` and `armeabi-v7a`.
3. Take `TdApi.java` and `Client.java` from the TGX-Android/tdlib repository at
   the revision whose `GIT_COMMIT_HASH` matches the one inside the new
   `libtdjni.so` — check with:
   `grep -a -o -m1 'https\?://[^ ]*' …` is not enough; search the binary for the
   40-character hash it embeds next to the TdApi version string.
4. Build. The APK grows by roughly 25 MB per ABI's worth of libraries (both ABIs
   are in the universal APK), which is the honest cost of a full Telegram client.
