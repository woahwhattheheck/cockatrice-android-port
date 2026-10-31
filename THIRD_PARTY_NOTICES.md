# Third-party components

This repository contains the Android host, native framebuffer viewer, build
configuration, and runtime packaging scripts. Original host/packaging code is
licensed under GPL-2.0-only. Upstream components retain their own licenses; this
repository does not relicense them or claim their authorship.

## Cockatrice

- Upstream: <https://github.com/Cockatrice/Cockatrice>
- Working source revision: `63143f941643dc2f46f658cb51ea3b5727da3e56`
- Version reported by the pinned executable: `3.0.0 (2026-05-08)`
- License: GNU GPL version 2; retain upstream notices and source license terms.
- The packager does not patch or compile the game. Its exact executable identity
  is pinned in `runtime/COCKATRICE.sha256`.
- The Android app and notification icons use the upstream artwork
  `cockatrice/resources/cockatrice.svg` and `cockatrice/resources/cockatrice.png`
  from the pinned source revision.

Cockatrice, its logo and its upstream resources belong to their respective
rights holders. This is an unofficial Android packaging project, not an official
Cockatrice release or a native rewrite of its game/UI.

## Bundled Linux and native runtime

Generated APKs include selected Debian libraries/data and native PRoot support:

- PRoot: <https://github.com/termux/proot>, working version `5.1.107.96`.
- talloc: <https://talloc.samba.org/>, working version `2.5.0`.
- Android shared-memory support: <https://github.com/termux/libandroid-shmem>,
  working version `0.7`.
- Debian runtime components, including Qt, Xvfb, x11vnc, Fluxbox, fonts, TLS
  roots and supporting libraries: <https://www.debian.org/>.

`dependencies/working-runtime.json` records the working dependency versions and
binary hashes. The packager copies Debian copyright files into the runtime.
It changes ELF search paths/one library filename in copied native support
binaries so they can live in Android's installed native-library directory; it
does not change their installed Termux originals.

Card/token databases are upstream data, not original Android-host code. Card
names, rules text, trademarks and artwork have their own rights/provenance. The
packager does not include the developer's cached card pictures, decks, replays,
accounts, shell history or private settings.

## Gradle wrapper

The standard Gradle wrapper files retain their original notices and Apache
License 2.0 terms. See `LICENSES/Apache-2.0.txt`. Android SDK/build tools and the
Android Gradle plugin are external build dependencies, not vendored source here.

## Published RC7 source

The single-APK `v0.1.0-rc7` release is at
<https://github.com/woahwhattheheck/cockatrice-android-port/releases/tag/v0.1.0-rc7>.
Its Android-host source snapshot is in the companion
<https://github.com/woahwhattheheck/cockatrice-android-port/releases/tag/v0.1.0-rc7-sources>.
RC7's runtime ZIP is unchanged from RC6. Its version-matched Debian, Termux,
Cockatrice, and card/token data sources remain in
<https://github.com/woahwhattheheck/cockatrice-android-port/releases/tag/v0.1.0-rc6-sources>.
Verify source archives with their `Corresponding-Sources.sha256` files.

## Before distributing an APK

A source-only repository is not automatically a complete corresponding-source
release for every component of a generated APK. Distributors must provide the
applicable notices/licenses and the matching corresponding sources for bundled
GPL/LGPL components, including local changes/build scripts where required. An
inventory or a generic upstream homepage alone does not establish compliance.

Keep the APK, its checksum, the generated runtime manifest, notices, and matching
source delivery together. Those developer/source downloads do not add any setup
requirement for players: the installed game remains a single self-contained APK.
