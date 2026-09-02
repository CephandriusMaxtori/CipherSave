# CipherSave — TODO

Fabric mod for Minecraft **26.2** that locks world saves behind a PIN.
AES-256-GCM at rest; decrypt-in-place during a session; **PIN and TOTP (authenticator app) are both primary unlock methods** — either opens the world.

## Legend
`[x]` done · `[~]` in progress · `[ ]` not started

---

## 1. Project foundation
- [x] Gradle scaffold (`settings.gradle`, `gradle.properties`, `build.gradle`) with Loom `1.17.20`
- [x] Gradle wrapper 9.5.1 (bootstrapped in temp dir, copied into project)
- [x] `LICENSE` — Custom Source-Available (No Redistribution), Copyright (c) 2026 Nolan Bragan
- [x] `.gitignore`
- [x] Git init + **public** GitHub repo `CephandriusMaxtori/CipherSave`; scaffold commit pushed
- [x] Build fixed for 26.2: plugin id `net.fabricmc.fabric-loom`, `implementation` deps, no mappings config, `options.release = 25`
- [x] `genSources` successful

## 2. 26.2 source verification (decompiled)
- [x] `Screen` input/render API (`keyPressed(KeyEvent)`, `charTyped(CharacterEvent)`, `extractBackground`, `shouldCloseOnEsc`, `onClose`)
- [x] `GuiGraphicsExtractor` drawing API (`fill`, `centeredText`)
- [x] `WorldSelectionList.WorldListEntry#joinWorld()` — the gate to intercept (fields `list`, `summary` confirmed)
- [x] `LevelStorageSource#readLevelSummary(LevelDirectory, boolean)` private — synthetic summary mixin target
- [x] `LevelSummary` 8-arg ctor `(LevelSettings, LevelVersion, String, bool, bool, bool, bool, Path)`; `LevelVersion.parse(Dynamic)`; `LevelSettings` record; `DifficultySettings.DEFAULT`; `WorldDataConfiguration.DEFAULT`
- [x] 26.2 storage layout (**major change**): `playerdata/` → `players/` (`players/data`, `players/stats`, `players/advancements`); regions → `dimensions/<ns>/<id>/region/r.*.mca` (all dims, no DIM1/DIM-1)
- [x] `LevelStorageAccess` is public nested class `LevelStorageSource$LevelStorageAccess`; state: `getLevelDirectory()`, `getLevelId()`, `close()` (public, no args), `getLevelPath(LevelResource)`
- [x] `MinecraftServer.storageSource` IS a `LevelStorageAccess`; shutdown order: `saveAllChunks(true)` → each `level.close()` → `savedDataStorage.close()` → `storageSource.close()` — confirmed the safe encryption point
- [x] `Minecraft` accessors: `gui` (public final), `getLevelSource()` (→ `getLevelPath(String)` = world root), `createWorldOpenFlows().openWorld(String, Runnable)`, `setScreenAndShow(Screen)`, `getSoundManager()`
- [x] `ProgressScreen(boolean clearScreenAfterStop)`; `progressStartNoAbort(Component)`/`progressStage(Component)`/`progressStagePercentage(int)`/`stop()`; `ErrorScreen(Component, Component)`
- [x] `SoundEvents.VILLAGER_NO` / `PLAYER_LEVELUP` are `SoundEvent`; `SimpleSoundInstance.forUI(SoundEvent, float)`
- [x] ZXing `com.google.zxing:core:3.5.3` chosen for QR (bundled via `include` in build.gradle)

## 3. Crypto core (`src/main/java/dev/ciphersave/crypto/`)
- [x] `CipherSaveConstants` — magic `CS1`, nonce 12, tag 16, AES-256-GCM, PBKDF2 210k, TOTP params
- [x] `AesGcmFile` — CS1 wire format: magic(3)+nonce(12)+ciphertext+tag(16); AEAD failure on wrong key
- [x] `KeyDerivation` — PBKDF2-HmacSHA256 (PIN→KEK), SHA-256 (seed→seedKey), hex helpers
- [x] `Sha` — SHA-256 wrapper (package-private)
- [x] `TotpAuth` — RFC 6238 TOTP compute/verify, base32 encode/decode, **`randomSeedBytes()`**, 6-digit window
- [x] `QrEncoder` — otpauth:// URI builder + ZXing `BitMatrix` → `boolean[][]` matrix (size 100)

## 4. Storage layer (`src/main/java/dev/ciphersave/storage/`)
- [x] `PinMetaFile` — `pin_meta.json` read/write/`isPresent`, schema (version, displayName, kdf, pinWrappedKey, totp.seed)
- [x] `SessionMarker` — `ciphersave_session.json` (activates on unlock, removed on lock; crash-rescue flag)
- [x] `FileManifest` — `ciphersave_manifest.json` file→mtime map (merge duplicate methods fixed)
- [x] `WorldFileCipher` — protected-file enumeration (`level.dat*`, `players/**/*.dat(.old)`, `dimensions/.../region/*.mca`), `encryptChanged`, `decryptAll` w/ progress, `isEncrypted` (header-only read), `snapshotBackup` (keep 3), `storeManifest`, `createPinMeta`/`unwrapWithPin`/`unwrapWithTotp`

## 5. Common entry + sessions
- [x] `CipherSave` (ModInitializer, logger)
- [x] `CipherSessions` — in-memory unlocked-session master keys (per world root), never persisted
- [x] Cleaned stray `dev.ciphersave.client.CipherSessions` import

## 6. Mixins (common — `src/main/resources/ciphersave.mixins.json`)
- [x] `LevelStorageSourceMixin` — `readLevelSummary` HEAD: synthetic playable `LevelSummary` for protected worlds (displayName from pin_meta, current version, icon/mtime) — **matches real ctor/method verified from source**
- [x] `LevelStorageAccessMixin` — `close` TAIL: if protected + unlocked + marker → `encryptChanged` + `storeManifest` + deactivate marker + wipe key; `getLevelDirectory()` @Shadow added; **target inner class verified**
- [x] Both configs compile + deploy (jar built)

## 7. Client (— `src/client/...` + `ciphersave.client.mixins.json`)
- [x] `CipherSaveClient` (ClientModInitializer)
- [x] `WorldListEntryMixin` — `joinWorld` HEAD cancel on ALL worlds → `PinScreen.open(...)` (unlock or first-open setup; passes display name too)
- [x] `WorldOpenFlowsMixin` — **world-creation gate**: HEAD-cancel `createFreshLevel` → PIN setup → `CipherUnlock.prepareForCreate` (writes pin_meta + empty-session) → re-invoke original `createFreshLevel` (guard flag to avoid recursion)
- [x] `CipherUnlock` — `unlockAndOpen` (crash-rescue encrypt → snapshot → decrypt → marker+session → openWorld on ProgressScreen, daemon thread), `setupAndOpen` (write pin_meta → unlock sequence), `prepareForCreate` (creation variant, no openWorld)
- [x] `PinScreen` — **PIN and TOTP are EQUAL primary unlock methods** (toggle buttons switch input); setup: choose+confirm PIN → optional TOTP (QR + seed text + 6-digit test)
- [x] Lang file `assets/ciphersave/lang/en_us.json` for all `ciphersave.*` keys
- [ ] **Build verified; runtime UX untested** — layout sanity (QR stage spacing on small heights), glyph/width checks, sound triggers

## 8. Build & test
- [x] `./gradlew build` green (main + client + jar, zxing included) after fixes:
  - `**/` in javadoc terminated comment early (rewrote path text)
  - `dev.ciphersave.client.CipherSessions` import
  - duplicated `needsEncryption(Path)` (merged)
  - `createPinMeta` missing `throws IOException`
  - mixin `getLevelDirectory()` needs `@Shadow abstract`
  - PinScreen double-blur crash (`extractBackground` already run by framework)
- [~] `runClient` test round 1 (user):
  - [x] Found: **crash — "Can only blur once per frame"** in `PinScreen.extractRenderState` → fixed
  - [x] Found: **no PIN setup on fresh world creation** (creation bypasses `joinWorld`) → fixed via `WorldOpenFlowsMixin` gate
  - [ ] Re-test: new-world creation shows setup screen
  - [ ] Re-test: settings saved, quit → files encrypted (CS1 magic)
  - [ ] Re-test: reopen → unlock screen; wrong PIN → `VILLAGER_NO` + cleared field
  - [ ] Re-test: **unlock with TOTP code** (without PIN) → world opens
  - [ ] Re-test: unlock with PIN → world opens
  - [ ] Re-test: crash simulation (kill client, marker active) → next unlock re-encrypts leftovers first
  - [ ] Re-test: `ciphersave_backups/<utc>` snapshot on unlock; keeps last 3
- [ ] Commit + push milestones (per user request)

## 9. Notes / deferred
- [ ] Edit/Recreate buttons on a locked world will error `NbtException` (out of scope for v1) — acceptable toasts
- [ ] Deletion of a locked world allowed (whole-folder delete)
- [ ] Dedicated-server: common mixins apply; client screens N/A; singleplayer is primary target