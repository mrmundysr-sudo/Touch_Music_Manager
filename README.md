# Touch Music Manager

**Mundy Morning Studios — Android V1**

A standalone, offline Android music organizer and player. It turns loose songs, ZIP albums,
big mixed ZIP archives and whole folders into a searchable personal library, and plays it with
background playback, lock-screen controls and playlists.

No account, no ads, no analytics, no network access: the app does not even request the
`INTERNET` permission.

## Install the debug APK

1. Open the repository's **Actions** tab and choose the latest successful **Android CI** run.
2. Under **Artifacts**, download **`touch-music-manager-debug-apk`** (you must be signed in to GitHub).
3. Unzip it; inside is `app-debug.apk`.
4. Copy the APK to the phone (or download it there directly) and open it. Allow
   "Install unknown apps" for the app you opened it with when Android asks.
   With a computer: `adb install -r app-debug.apk`.

Requires Android 10 (API 29) or newer. Every CI build is signed with the same debug key
(`keystore/debug.keystore`, a non-secret key for personal testing), so a newer build installs
over an older one without losing the library.

## What it does

**Import (Import tab)**

| Option | What happens |
| --- | --- |
| Scan Entire Phone | Indexes music Android already knows about (MediaStore). Files stay where they are. |
| Scan Downloads | Same, limited to `Download/`. Android does not let apps list ZIP files in Downloads, so pick those with *Import ZIP Files*. |
| Choose Folder | Recursively scans a folder you pick for audio files and ZIPs. Access is remembered for rescans. |
| Import ZIP Files | Select one or many archives; songs are extracted into the organised library. |
| Import Songs | Select individual audio files. |

For loose audio files you choose per import: **leave in place** (index only), **copy**, or
**move** (copy, verify, then delete the original). ZIP contents are always extracted. Source
ZIPs are kept unless you tick "Delete each ZIP after it imports without errors".

**Organisation.** Extracted and copied files are written to shared storage as
`Music/Artist/Album/NN - Title.ext` (album artist is used for the folder, so compilations stay
together; multi-disc tracks are named `D-NN - Title`). Embedded tags are read first; file and
folder names such as `Artist - Title.mp3` or `Artist - Album/01 - Title.mp3` are used as fallback
clues. Anything uncertain goes to `Music/Needs Review` with its original name and shows up in
**Library › Needs Review**; nothing is invented. Fix entries with **⋮ › Edit details**.

**Safety.** Archives are streamed entry by entry (never loaded into memory). Each song is
extracted to a temporary file, hashed (SHA-256), checked for being real audio, checked against
the library for exact duplicates, copied, re-read and verified, and only then made visible.
Existing files are never overwritten. Protections: ZIP-slip (path traversal), ZIP bombs
(compression ratio, entry count, per-file and total size limits), nesting depth, free-space
checks before and during extraction, corrupt archives and fake/unsupported audio files.

**Interruptions.** Import jobs are saved before work starts. You can cancel, retry failed
sources, and resume after the app is killed. Resuming restarts each unfinished *source* from its
beginning; songs that were already added are skipped as duplicates. Extraction is **not** resumed
in the middle of an individual ZIP.

**Player.** Media3/ExoPlayer: play/pause/next/previous, seek, shuffle, repeat, play queue,
background playback, media notification and lock-screen controls, Bluetooth/headset buttons,
audio focus and "becoming noisy" handling, favorites, playlists, recently played/added, search.
An unplayable or missing file is reported and skipped instead of stalling the queue.

**Library upkeep.** *Rescan Library* picks up new, changed, moved and deleted files without
creating duplicate entries. Missing files (deleted, or on storage that is disconnected) are
hidden, come back automatically when the file returns, and can be removed in one tap.

## Known limits of V1

- Editing details updates the library (and re-files songs the app itself organised); it does not
  rewrite the tags inside the audio file.
- "Remove from library" removes the entry only; the file is not deleted.
- WMA, AIFF, APE and similar formats Android cannot play are reported as unsupported and skipped.
- Password-protected ZIPs and non-ZIP archives (RAR, 7z) are not supported.
- If the app is uninstalled, organised files stay in `Music/`; after reinstalling, use
  *Scan Entire Phone* to index them again (playlists and favorites are not preserved).

## Project layout

```
core/   Pure Kotlin (no Android): import engine, ZIP safety, metadata resolution, path planning.
        Fully unit-tested on the JVM.
app/    Android app
  data/      Room database: tracks, albums, artists, playlists, import jobs/items/logs, folders
  importer/  MediaStore + folder scanners, import jobs, foreground import service
  playback/  Media3 MediaSessionService and controller connection
  ui/        Jetpack Compose screens (dark theme, temporary artwork)
```

The import engine talks to the platform through one small interface (`ImportEnvironment`), and
discovery, import, playback and UI are separate packages, so later sources (home server,
network libraries, sync, casting, metadata lookup) can be added without reshaping V1. None of
those are implemented.

## Build

```
./gradlew :core:test            # import engine unit tests (no Android SDK needed)
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17 and the Android SDK (platform 35). GitHub Actions (`.github/workflows/android.yml`)
runs both on every push and uploads the APK as an artifact.

## Test status

**Automated (run in CI on every push)** — `core` unit tests drive the real import engine with real
ZIP data: single-song ZIP, full album, mixed archive with nested folders and nested ZIPs,
exact duplicates within and across archives, untagged files and Needs Review, garbage and
truncated and bit-flipped archives, corrupt/fake audio entries, corrupt nested archives, ZIP-slip
names, hostile tags, ZIP bombs (ratio, entry count, size), nesting depth, low storage, commit
failures, cancellation and retry, a batch of 120 archives, a 3,000-song archive, legacy
(non-UTF-8) entry names, and loose audio files. In these tests the "library" is a temp directory
and tags come from a test stub, because Android's tag reader and MediaStore do not exist on a
build machine.

**Needs a real phone** — everything that touches Android itself has been compiled but not
executed: reading tags and artwork from real MP3/FLAC/M4A files, writing to `Music/` through
MediaStore, the permission prompts and folder picker, the import foreground service and its
notification, playback, lock-screen/Bluetooth controls, audio focus, and behaviour across
different Android versions and manufacturers.
