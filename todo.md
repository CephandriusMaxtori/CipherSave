# CipherSave — TODO

Fabric mod for Minecraft **26.2** that locks world saves behind a PIN.
AES-256-GCM at rest; decrypt-in-place during a session; PIN + TOTP (authenticator app) unlock.

## Legend
`[x]` done · `[~]` in progress · `[ ]` not started

---

## 1. Project foundation
- [x] Gradle scaffold (`settings.gradle`, `gradle.properties`, `build.gradle`) with Loom `1.17.20`
- [x] Gradle wrapper 9.5.1 (bootstrapped in temp dir, copied into project)
- [x] `LICENSE` — Custom Source-Available (No Redistribution), Copyright (c) 2026 Nolan Bragan
- [x] `.gitignore`
- [x] Git init + **public** GitHub repo `CephandriusMaxtori/CipherSave`; initial scaffold commit pushed
- [x] Build fixed for 26.2: plugin id `net.fabricmc.fabric-loom`, `implementation` deps, no mappings config, `options.release = 25`
- [x] `genSources` successful (3m45s)

## 2. 26.2 source verification (decompiled)
- [x] `Screen` input/render API (`keyPressed(KeyEvent)`, `charTyped(CharacterEvent)`, `extractBackground`, `extractBlurredBackground`)
- [x] `GuiGraphicsExtractor` drawing API (`fill`, `centeredText`, `text`, `blitSprite`)
- [x] `WorldSelectionList.WorldListEntry#joinWorld()` — the gate to intercept
- [x] `LevelStorageSource.readLevelSummary(LevelDirectory, boolean)` private — synthetic summary mixin target
- [x] `LevelStorageAccess.saveDataTag/saveLevelData/close`, `getLevelDirectory()`, `getLevelPath(LevelResource)`
- [x] `LevelSummary` 8-arg ctor; `LevelVersion.parse(Dynamic)`; `LevelSettings` record; `WorldDataConfiguration.DEFAULT`
- [x] 26.2 storage layout (**major change**): `playerdata/` → `players/` (`players/data`, `players/stats`, `players/advancements`); regions → `dimensions/<ns>/<id>/region/r.*.mca` (all dims, no DIM1/DIM-1)
- [x] `PlayerDataStorage.save(Player)` writes `players/data/<uuid>.dat` + `.dat_old`
- [x] `ServerLevel.save/ServerChunkCache.save/ChunkMap.saveAllChunks` async region writes via `IOWorker`
- [x] `MinecraftServer.stopServer` order: `saveAllChunks(flush=true)` → levels closed → `storageSource.close()` — confirmed **`LevelStorageAccess.close()`** is the safe "everything is durable & handles released" point
- [x] `Minecraft.getInstance()` accessors: `gui` (public final field), `getLevelSource()`, `createWorldOpenFlows().openWorld(String, Runnable)`, `getSoundManager()`, `setScreenAndShow(Screen)`
- [x] `SoundEvents.VILLAGER_NO` / `PLAYER_LEVELUP` are `SoundEvent`; `SimpleSoundInstance.forUI(SoundEvent, float)`
- [x] ZXing `com.google.zxing:core:3.5.3` chosen for QR (bundled via `include`)

## 3. Crypto core (`src/main/java/dev/ciphersave/crypto/`)
- [x] `CipherSaveConstants` — magic `CS1`, nonce 12, tag 16, AES-256-GCM, PBKDF2 210k, TOTP params
- [x] `AesGcmFile` — CS1 wire format encrypt/decrypt (reads ok on wrong key → AEAD failure)
- [x] `KeyDerivation` — PBKDF2-HmacSHA256 (PIN→KEK), SHA-256 (seed→seedKey), hex helpers
- [x] `Sha` — SHA-256 wrapper (package-private)
- [x] `TotpAuth` — RFC 6238 TOTP compute/verify, base32 encode/decode, 6-digit window
- [ ] `TotpAuth.randomSeedBytes()` — **MISSING**, called by PinScreen; add 20-byte SecureRandom helper
- [x] `QrEncoder` — otpauth:// URI builder + ZXing `BitMatrix` → `boolean[][]` matrix

## 4. Storage layer (`src/main/java/dev/ciphersave/storage/`)
- [x] `PinMetaFile` — `pin_meta.json` read/write/`isPresent`, schema (version, displayName, kdf, pinWrappedKey, totp.seed)
- [x] `SessionMarker` — `ciphersave_session.json` (activates on unlock, removed on lock; crash-rescue flag)
- [x] `FileManifest` — `ciphersave_manifest.json` file→mtime map (skip unmodified files)
- [x] `WorldFileCipher` — protected-file enumeration (`level.dat*`, `players/**/*.dat(.old)`, `dimensions/**/*.mca`), `encryptChanged`, `decryptAll` w/ progress, `isEncrypted`, `snapshotBackup` (keep 3), `storeManifest`

## 5. Common entry + sessions
- [x] `CipherSave` (ModInitializer, logger)
- [x] `CipherSessions` — in-memory unlocked-session master keys (per world root), never persisted
- [ ] Remove accidental unused import in `WorldListEntryMixin` (`CipherSessions`)

## 6. Mixins (common — `src/main/resources/ciphersave.mixins.json`)
- [x] `LevelStorageSourceMixin` — `readLevelSummary` HEAD: synthetic playable `LevelSummary` for protected worlds (displayName from pin_meta, current version, icon/mtime)
- [x] `LevelStorageAccessMixin` — `close` TAIL: if protected + unlocked + marker → `encryptChanged` + `storeManifest` + deactivate marker + wipe key
- [ ] Build/verify mixin targeting of private inner class `LevelStorageSource$LevelStorageAccess`

## 7. Client (— `src/client/...` + `ciphersave.client.mixins.json`)
- [x] `CipherSaveClient` (ClientModInitializer)
- [x] `WorldListEntryMixin` — `joinWorld` HEAD cancel on ALL worlds → `PinScreen.open(...)` (unlock or setup)
- [x] `CipherUnlock` — `unlockAndOpen` (crash-rescue encrypt → snapshot → decrypt → marker+session → openWorld) and `setupAndOpen` (write pin_meta → unlock sequence)
- [~] `PinScreen` — unlock/setup modes, masked PIN, TOTP recovery field, QR verify, buttons, audio cues
  - [ ] Verify `buttonY()`/verify layout math (remove dead branches, unused `currentButtons()` index)
  - [ ] Simplify unlock button labels (unlock + toggle-to-code; there is currently no "back to PIN")
- [ ] Lang file `assets/ciphersave/lang/en_us.json` for all `ciphersave.*` keys

## 8. Build & test
- [ ] `./gradlew build` — fix compile errors against decompiled sources
- [ ] `runClient` smoke test:
  - [ ] First open of fresh world → PIN setup prompt (choose + confirm; optional TOTP QR verify)
  - [ ] After setup: files encrypted on disk at rest; session plays fine
  - [ ] Quit to title → verify level.dat/regions/players encrypted (CS1 magic)
  - [ ] Re-open world → PIN screen, wrong PIN → `VILLAGER_NO` + cleared field
  - [ ] Correct PIN → `PLAYER_LEVELUP`, decrypt, world opens; session marker present
  - [ ] TOTP recovery code unlocks
  - [ ] Crash simulation: kill client with marker active → next unlock re-encrypts leftover plaintext first
  - [ ] `ciphersave_backups/<utc>` snapshot created on unlock; only last 3 kept
- [ ] Commit + push milestones (per user request)

## 9. Notes / deferred
- [ ] Edit/Recreate buttons on a locked world will error `NbtException` (out of scope for v1) — acceptable toasts
- [ ] Deletion of a locked world allowed (whole-folder delete)
- [ ] Dedicated-server: common mixins apply; client screens N/A; singleplayer is primary target