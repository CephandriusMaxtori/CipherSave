# CipherSave — Architecture

How the pieces fit together for Minecraft 26.2 (Fabric, Loom).

## Source layout

```
src/main/java/dev/ciphersave/
  CipherSave.java            ModInitializer (entry point, logger)
  CipherSaveConstants.java   all tunable constants (file names, crypto params, PIN limits)
  CipherSessions.java        in-memory map worldRoot -> master key (plaintext-session registry)
  crypto/
    AesGcmFile.java          AES-256-GCM single-file helper, CS1 wire format
    KeyDerivation.java       PBKDF2 (PIN -> KEK), SHA-256 (seed -> seedKey), hex
    Sha.java                 SHA-256 wrapper
    TotpAuth.java            RFC 6238 compute/verify + base32 (+ randomSeedBytes)
    QrEncoder.java           otpauth:// URI + ZXing QR matrix
  storage/
    PinMetaFile.java         pin_meta.json read/write/isPresent (schema)
    SessionMarker.java       ciphersave_session.json activate/deactivate/isActive
    FileManifest.java        ciphersave_manifest.json mtime -> last-encrypted tracking
    WorldFileCipher.java     protected-file enumeration, encryptChanged, decryptAll,
                             isEncrypted, snapshotBackup, unwrapWithPin, unwrapWithTotp
  mixin/
    LevelStorageSourceMixin.java   synthetic LevelSummary for protected/locked worlds
    LevelStorageAccessMixin.java   re-encrypt on LevelStorageAccess.close()

src/client/java/dev/ciphersave/client/
  CipherSaveClient.java       ClientModInitializer (stub)
  PinScreen.java             the gate screen (unlock/setup, PIN + TOTP modes)
  CipherUnlock.java          decrypt-then-open sequence on a daemon thread (ProgressScreen)
  mixin/WorldListEntryMixin.java  intercept "Play" -> PinScreen

src/main/resources/
  fabric.mod.json            entrypoints, mixin configs, depends
  ciphersave.mixins.json     common mixins (also active on dedicated server)
  assets/ciphersave/lang/en_us.json
src/client/resources/
  ciphersave.client.mixins.json  client-only mixin
```

## Data flow

### World list / open gate

1. `WorldSelectionList.WorldListEntry.joinWorld()` is the single entry point for "Play" (used by
   double-click and the Play button). `WorldListEntryMixin` cancels it and opens
   `PinScreen.open(...)`, always.
2. `PinScreen` chooses mode:
   - `pin_meta.json` exists → **unlock** (PIN entry or TOTP toggle).
   - no `pin_meta.json` → **first-open setup**: choose+confirm PIN, then optional TOTP
     enrollment (QR + test code).

### Synthetic world summary

`LevelStorageSourceMixin` HEAD-injects `readLevelSummary(LevelDirectory, boolean)`. For a world with
`pin_meta.json` it returns a synthetic `LevelSummary` (display name from `pin_meta.json`, current
client data version, `lastPlayed` from file mtimes, no "conversion"/"fix" flags, icon from the world
folder). Without this the world would be marked corrupted because `level.dat` is encrypted.

### Unlock sequence (`CipherUnlock.unlockAndOpen`)

Runs on a daemon thread under a `ProgressScreen(true)`:

1. `SessionMarker.isActive()` → crash-rescue: `encryptChanged()` re-encrypts leftover plaintext.
2. `snapshotBackup()` → copies the (encrypted) protected files to `ciphersave_backups/<UTC>/`
   (keeps last 3).
3. `decryptAll(callback)` → AES-GCM decrypt every protected file in place, drives progress %.
4. `SessionMarker.activate()` + `CipherSessions.registerUnlocked(root, key)`.
5. On the client thread: `progress.stop()` then
   `mc.createWorldOpenFlows().openWorld(levelId, () -> setScreen(backToScreen))`.

### Setup sequence (`CipherUnlock.setupAndOpen`)

1. Generate random 256-bit master key.
2. `WorldFileCipher.createPinMeta(...)` wraps it with the PIN (and optionally the TOTP seed) →
   `PinMetaFile.write(...)`.
3. Continue as unlock (files are plaintext → initial lock happens inside `encryptChanged` during
   crash-rescue step, then snapshot, then decrypt for the session).

### Re-encryption on quit ("save time")

`MinecraftServer` shutdown order (verified against 26.2 sources):

```
saveAllChunks(false, true, false)          # flush chunks
for each level: level.close()
savedDataStorage.close()
storageSource.close()                      # LevelStorageAccess.close()
```

`LevelStorageAccessMixin` TAIL-injects `close()`:

- if the world is protected AND a session is unlocked AND the session marker is active,
  `encryptChanged()` re-encrypts changed/plaintext files, `storeManifest()` persists the mtime
  manifest, then the marker is removed and the in-memory key is wiped.

Because this runs *after* all vanilla saves have been flushed to disk, it is safe to rewrite the
files — matches the requirement of "encrypts only at save time".

## 26.2 storage layout (was verified from decompiled sources)

- `playerdata/` no longer exists → `players/data/<uuid>.dat` (plus `players/stats`,
  `players/advancements`).
- Region files for **all** dimensions live in `dimensions/<namespace>/<id>/region/r.*.mca`
  (`DimensionType#getStorageFolder` → `base.resolve("dimensions").resolve(ns).resolve(id)`).
  There are no `DIM1`/`DIM-1` folders.
- `LevelStorageSource.getLevelPath(String levelId)` returns the world root directory.
- `LevelStorageAccess` is a public nested class of `LevelStorageSource`
  (`LevelStorageSource$LevelStorageAccess` at runtime); `getLevelDirectory()` gives a
  `LevelDirectory` record whose `path()` is the world root.
- `MinecraftServer.storageSource` is a `LevelStorageAccess`.

## Threading

- Crypto/IO work for unlock/setup runs on a **daemon thread** (`CipherSave-Unlock`) so the UI stays
  responsive.
- `ProgressScreen` updates are marshalled back to the client thread via `mc.execute(...)`.
- Modern MC region writes are asynchronous (`IOWorker`); hence encryption happens only at world
  close (never mid-session) to avoid racing in-flight writes.
- Re-encryption at close runs on the server thread inside the `close()` call — safe because all
  vanilla save work for that world has already completed.

## Dependencies

- `com.google.zxing:core:3.5.3` (Apache-2.0) for QR rendering; declared with `implementation` and
  `include` in `build.gradle` so it is bundled inside the mod jar.
- Fabric API (required by the environment; the mod itself uses Fabric Loader + auto-mixin configs).

## Testing checklist

See `todo.md`, section 8. The core suites are: setup, PIN unlock, TOTP unlock, wrong-PIN rejection,
re-encryption-after-quit (CS1 magic on disk), crash-rescue, and backup retention.