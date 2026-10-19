# Cockatrice Android Port

An **unofficial, self-contained Android host for unmodified Cockatrice**, with a
native framebuffer viewer. This is a packaging/launcher project, not a rewrite
of Cockatrice's game logic or Qt interface and not an official upstream release.

The installed game needs **one APK**: no separate Termux, Debian download, VNC
app, root access, or pasted launch command. The APK prepares its bundled runtime
automatically on first launch. Android's normal APK installation approval still
applies; online play needs the usual internet connection and game-server account.

## Status

- Experimental RC4 lineage; ARM64 (`arm64-v8a`) and Android 8+ (`minSdk 26`).
- Native Android startup/display was confirmed working on an ARM64 phone.
- The game executable is pinned to Cockatrice `3.0.0 (2026-05-08)`, source commit
  [`63143f9`](https://github.com/Cockatrice/Cockatrice/commit/63143f941643dc2f46f658cb51ea3b5727da3e56).
- It is **not patched or rebuilt** by this project. Its required SHA-256 is in
  [`runtime/COCKATRICE.sha256`](runtime/COCKATRICE.sha256).
- Multiplayer, long sessions, and the full deck workflow still need broader
  real-world use. Do not treat this as an official or stable Cockatrice release.

This initial repository publishes the clean host/packaging source. No runtime
filesystem, game binary, card database, signing key, or APK is committed to Git.
Before distributing a compiled APK, include the matching runtime source/license
material described in [third-party notices](THIRD_PARTY_NOTICES.md).

## How it works

```text
Android activity + foreground game service
  -> APK-installed PRoot engine and loader
  -> bundled, allowlisted ARM64 Linux libraries + original Cockatrice
  -> private Xvfb/Fluxbox display and password-protected loopback VNC
  -> native Android canvas, touch and keyboard input
```

There is **no WebView, noVNC, Python server, or HTTP/WebSocket bridge** in the
working display path. The Android host draws Cockatrice's original pixels.

The installer verifies the bundled files, extracts to a versioned runtime and
keeps player decks/settings in a separate directory. The runtime includes a
card/token database and generated initial preferences, so an already-bundled
database does not trigger an unnecessary upstream first-install wizard. Those
packaging defaults do not modify the executable or its game interface.

## Viewer controls

- Tap/drag: ordinary mouse input.
- Long press: right-click.
- Pinch: zoom; two fingers scroll or pan a zoomed display.
- **Keyboard**: show the Android keyboard.
- **Paste**: paste Android clipboard text into the focused game field.
- **Keys / Fit**: Escape, Tab, Enter, or reset display scaling.
- **Diagnostics**: native startup/display diagnostics. Reconnecting the viewer
  does not restart the game.

The current virtual display is 1280×720 in landscape. Audio forwarding and
Android Files import/export are not implemented. Uninstalling or clearing app
data removes saved player data; an ordinary signed APK update preserves it.

## Source layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/` | Android launcher/service, verified installer, native RFB viewer and authentication |
| `app/src/main/res/` | Native layouts and launcher/notification icons |
| `runtime/` | Session supervisor, window-manager configuration and game checksum |
| `tools/prepare_runtime.py` | Build a relocatable runtime from explicit external inputs |
| `tools/build.sh` | Build the Android release; handles Termux's AAPT2/JDK location |
| `tools/create_signing_key.py` | Generate a private signing identity locally |
| `tools/export_candidate.py` | Export a signed APK, checksums and runtime manifest |
| `dependencies/working-runtime.json` | Dependency inventory from the working runtime |

## Building

Build requirements are for **developers**, not players:

- Python 3.10+, JDK 17, Android SDK platform/build-tools 34, and Bash.
- A prepared **ARM64 Debian rootfs** containing the pinned Cockatrice build,
  matching Qt/protobuf libraries, Oracle updater, Xvfb, x11vnc, Fluxbox, fonts,
  keyboard maps, TLS roots, card/token databases and package metadata.
- An **ARM64 Termux binary prefix** containing PRoot `5.1.107.96`, its loader,
  talloc `2.5.0`, and libandroid-shmem `0.7`, or equivalent explicitly supplied
  build inputs compatible with this packaging recipe.

The packager consumes an existing unmodified game build; it is not a from-scratch
Debian or Cockatrice compiler. The working dependency snapshot is recorded in
`dependencies/working-runtime.json`; exact inputs are not vendored here.

Expected rootfs inputs include:

```text
/root/Cockatrice/LICENSE
/root/Cockatrice/.git/                  # source revision for the manifest
/root/Cockatrice/build/version_string.cpp
/root/Cockatrice/build/cockatrice/      # executable, themes, sounds, *.qm
/root/Cockatrice/build/oracle/oracle
/root/.local/share/Cockatrice/Cockatrice/cards.xml
/root/.local/share/Cockatrice/Cockatrice/tokens.xml
/usr/                                  # matching runtime libraries/data
/var/lib/dpkg/                          # exact package versions/notices
```

```sh
export COCKATRICE_ROOTFS=/path/to/prepared-arm64-debian
# On Termux, PREFIX already points at its native binary prefix.
# On another build host, provide an extracted ARM64 prefix explicitly:
export COCKATRICE_TERMUX_PREFIX=/path/to/arm64-termux-prefix

python3 tools/prepare_runtime.py
python3 tools/create_signing_key.py    # ONCE; preserve this private key for updates
bash tools/build.sh
python3 tools/export_candidate.py
```

Set `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or create an untracked `local.properties`
with `sdk.dir`. On Termux, `tools/build.sh` uses its installed AAPT2 and prefers
the installed JDK 17 when `JAVA_HOME` is unset. Other hosts use the normal Android
build tooling. The Gradle wrapper downloads the pinned Gradle distribution.

Outputs are under `app/build/outputs/apk/release/` and `dist/`. After Gradle
`clean`, regenerate the runtime. Player homes, decks, replays, cached artwork,
accounts, shell history and private keys are not copied into the runtime.

Keep `signing/` private and securely backed up. Forks should create their own
signing identity; never commit a keystore or its passwords. Hosted Actions are
not needed to build this project.

## License and attribution

Original Android host/viewer and packaging code: **GPL-2.0-only**, maintained by
TokenJunkieLabs contributors. Standard Gradle wrapper files retain Apache 2.0
terms. Cockatrice and runtime components keep their upstream licenses and
attribution; see [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Publishing host source is not a substitute for providing corresponding source
for GPL/LGPL binaries included in a public APK download. Players still need only
the APK; developer/source downloads may accompany it separately.
