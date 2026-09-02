# CipherSave

Lock your world saves behind a PIN.

CipherSave is a client-side+common Fabric mod for **Minecraft 26.2** that encrypts your singleplayer
world save at rest, and gates opening the world behind a **PIN** and/or an **authenticator-app (TOTP)**
code. Both methods are full, equal unlock paths — you can unlock with your PIN *or* with a code from
your authenticator app.

```
world/
  level.dat              <- encrypted (CS1 magic: 'C','S','1' + nonce + AES-256-GCM)
  level.dat_old          <- encrypted
  players/data/*.dat     <- encrypted (.dat and .dat_old)
  dimensions/.../region/*.mca  <- encrypted
  pin_meta.json          <- public metadata (no secrets, see Security below)
  ciphersave_session.json     <- session marker (plaintext-session flag, no secrets)
  ciphersave_manifest.json    <- encryption manifest (mtime tracking, no secrets)
  ciphersave_backups/    <- encrypted snapshots (last 3 kept)
```

Everything else in the world folder (resources, icon, session.lock) is left untouched.

## Features

- **PIN gate before world load** — a custom PIN screen (masked input, 4–8 printable characters)
  opens when you click "Play" on a world.
- **TOTP as a first-class unlock method** — unlock with either your PIN *or* a 6-digit code from a
  standard authenticator app (RFC 6238). TOTP seeds are enrolled during setup (QR code + test-code verification).
- **AES-256-GCM at rest** — `level.dat`, `level.dat_old`, `players/**/*.dat(.old)`,
  `dimensions/**/region/*.mca` are encrypted with a fresh random 256-bit master key.
- **Encrypts when you quit back to the menu** — data is re-encrypted at the world-close save point,
  after Minecraft has flushed everything to disk.
- **Crash resilient** — a session marker records that a world is currently plaintext; after a crash,
  the next unlock re-encrypts any leftover plaintext before decrypting again.
- **Encrypted-state backups** — on every unlock, a snapshot of the encrypted files is stored in
  `ciphersave_backups/<UTC-stamp>/` (keeps the last 3).
- **Locked worlds show in the world list** — a synthetic world summary is served while the world is
  encrypted, so it never shows as "corrupted".

## Requirements

| | |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | >= 0.19.3 |
| Fabric API | any (`*`) |
| Java | >= 25 |

## Building from source

```
./gradlew build
```

The mod jar is at `build/libs/ciphersave-<version>.jar` and bundles ZXing
(`com.google.zxing:core:3.5.3`, Apache-2.0) for QR generation.

## Usage

1. Install the jar into your `mods/` folder (Fabric).
2. Start a world as usual, or open an existing one.
3. **First open:** CipherSave asks you to choose and confirm a PIN, then to enroll (optional) an
   authenticator app — scan the QR and enter a 6-digit code to verify.
4. From then on, opening that world shows the CipherSave screen: enter your **PIN**, or switch to
   **TOTP** and enter a code from your authenticator app.
5. Quit to the menu (`Esc` → Save and Quit) and the world is re-encrypted on disk.

### Notes

- Removing the mod never breaks a world's *files* — they stay encrypted on disk. To decrypt a
  protected world back to plaintext, open it (unlock) with CipherSave installed, then play through a
  plaintext session and quit; but because re-encryption happens on close, expect the files to be
  encrypted again afterwards. **Copy the folder while the world is plaintext** (during a session) if you
  want a plaintext export.
- Deleting a locked world from the world list still works (whole-folder delete).
- Dedicated servers are not a supported target yet (singleplayer only).
- Lost your PIN *and* your TOTP app? There is no backdoor by design — see `docs/SECURITY.md`.

## Documentation

- [`docs/SECURITY.md`](docs/SECURITY.md) — threat model, cryptographic design, key handling, recovery.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — how the encryption lifecycle, mixins, and screens fit together.
- [`todo.md`](todo.md) — project status and checklist.

## License

Custom **Source-Available (No Redistribution)** license.
Copyright (c) 2026 Nolan Bragan. See [`LICENSE`](LICENSE).