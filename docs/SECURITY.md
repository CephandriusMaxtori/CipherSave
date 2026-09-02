# CipherSave — Security Design

This document describes CipherSave's threat model, cryptographic construction, and key handling so
that the design can be reviewed and re-verified against the implementation.

## Threat model

CipherSave protects **data at rest**: an attacker with read access to the world folder
(`saves/<name>/...`) but *without* your PIN or authenticator app must not be able to read
`level.dat`, player data, or region files.

What CipherSave does **not** defend against:

- A compromised PC / keylogger / someone watching you type your PIN.
- An attacker who can run Minecraft with your account and installed mods — they can read the
  master key out of process memory during a session, or patch the unlock check.
- Loss of credentials (PIN is the only secret humans back up; see *Recovery*).
- The world being played honestly (files are plaintext **while you play** — that is the
  decrypt-in-place model).

## What gets encrypted

| Path (relative to world root) | Status |
|---|---|
| `level.dat`, `level.dat_old` | encrypted |
| `players/**/*.dat`, `*.dat_old` | encrypted |
| `dimensions/**/region/*.mca` | encrypted |

Not encrypted (by design): `icon.png`, `session.lock`, `resources/`, CipherSave's own
`pin_meta.json`, `ciphersave_session.json`, `ciphersave_manifest.json`, `ciphersave_backups/`.

## Cryptographic construction

### Master key

- One random **256-bit master key** per world is generated at first setup
  (`SecureRandom`, Java CSPRNG).
- It encrypts (AES-256-GCM) all protected files.
- It is **only** kept in heap memory for the duration of a session and is wiped when the world is
  locked again. It is never written to disk in plaintext.
- The only way the key is held outside the game is as ciphers: wrapped by the PIN-derived key and
  the TOTP-derived key below.

### Key wrapping (stored in `pin_meta.json`)

```
pinWrappedKey = AES-256-GCM( MasterKey ;  key = PBKDF2-HmacSHA256( PIN, salt, 210000 iters, 256 bits ) )
seedKey       = SHA-256( rawSeed )
                 where rawSeed = 20 random bytes, base32 encoded into pin_meta.totp.seed
pinWrappedKey (TOTP path) = AES-256-GCM( MasterKey ;  key = seedKey )   <- same ciphertext, second wrap key
```

Notes:

- `pin_meta.json` therefore contains **no usable secret by itself**: an attacker needs the PIN (to
  derive the PBKDF2 key) or the TOTP seed (to derive `seedKey` via SHA-256).
- The TOTP seed is *secret* — do not share the QR or the seed text. Anyone with the seed can decrypt
  the world (they can also generate codes for the app account).
- PBKDF2 with 210,000 iterations is the OWASP-recommended minimum for HMAC-SHA256. Brute-forcing a
  4–8 character PIN against it is the weak link in the system; longer/complex PINs are better.

### File encryption (wire format)

Every protected file is stored as:

```
magic (3 bytes: 'C','S','1') + nonce (12 bytes) + AEAD ciphertext + GCM tag (16 bytes)
```

- One fresh random 12-byte nonce per file per encryption run.
- A 16-byte GCM auth tag over the whole plaintext. A wrong key or any corruption fails with an AEAD
  error instead of silently decrypting garbage.
- Files are re-encrypted in place whenever their mtime changed since the last encryption (tracked in
  `ciphersave_manifest.json`), or if they are plaintext.

### TOTP (RFC 6238)

- Standard 6-digit, 30-second-period TOTP, HMAC-SHA1, as supported by Google Authenticator,
  Authy, 1Password, Bitwarden, etc.
- Verified against the current time step only (no extra tolerance window).

## Session lifecycle

```
SETUP         key = random(32B)
              write pin_meta.json (wrap key by PIN (+TOTP) as above)
              encrypt all files            (initial lock)
              snapshot encrypted state
              decrypt all files            (open plaintext session)
              write ciphersave_session.json
              open world

UNLOCK        crash-rescue: if ciphersave_session.json present, re-encrypt leftovers first
              snapshot encrypted state
              decrypt all files
              write ciphersave_session.json
              register key in memory
              open world

CLOSE/QUIT    (MinecraftServer stop: all saves flushed, levels closed)
              encrypt changed files back      (re-encrypt plaintext session)
              update manifest
              delete ciphersave_session.json  (no longer plaintext)
              wipe session key from memory
```

If the game crashes mid-session, `ciphersave_session.json` survives (it holds **no secrets**). On
the next unlock, the crash-rescue step re-encrypts any plaintext still on disk *before* the world is
served, so no plaintext is ever left behind.

## Recovery

- **Credentials you can use to unlock:** the PIN, or a TOTP code from an app that enrolled the seed
  (still valid even after you lose the game/PIN). Re-enrollment is not yet implemented; back up the
  seed text shown during setup.
- If you lose **both** the PIN and every copy of the TOTP seed, the world is unrecoverable. This is
  intentional — there is no backdoor.
- Locked worlds have encrypted files, so a fresh install of any tooling (including vanilla) cannot
  read them without both unlock paths unavailable.

## Review checklist

- [x] Master key generated by Java `SecureRandom`.
- [x] PBKDF2-HmacSHA256, 210k iterations, random 16-byte salt stored with the metadata.
- [x] AES/GCM/NoPadding, 256-bit key, 12-byte random nonce, 16-byte tag, authenticated wire format.
- [x] No plaintext master key, PIN, or seed persisted; session marker and manifest contain no secrets.
- [x] Session key zeroed on lock (reference: `CipherSessions` — in-memory map; a full zeroization
      pass is not guaranteed by the JVM, but the key is never placed outside the map).
- [x] Re-encryption happens only after all save buffers have been flushed (world close hook).
- [x] Leftover plaintext after a crash is re-encrypted at next unlock.