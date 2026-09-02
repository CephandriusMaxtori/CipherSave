# CipherSave — CurseForge

**Tagline:** Lock singleplayer worlds behind a PIN. AES-256-GCM at rest. Fabric 26.2.

---

CipherSave is a client-side Fabric mod that encrypts your singleplayer worlds so nothing readable touches your disk until you unlock them.

### How it works

- On first open (or when creating a new world), you set a PIN. The world and every file it contains — `level.dat`, player data, and region files in every dimension — are sealed with AES-256-GCM before being written to disk (`CS1` magic header).
- To play, unlock from the world list with your **PIN or a TOTP code from your authenticator app** — both are first-class unlock methods, so losing one never locks you out.
- Inside a session everything runs decrypted in place; when you save-and-quit, the world is re-encrypted automatically.
- A crash mid-session (marker left behind) is detected on next launch and left-over plaintext is re-encrypted before the world opens — no unprotected copies are left lying around.
- Encrypted snapshots are backed up on unlock and the last 3 are kept, so a bad edit is always recoverable.

### Why it matters

- Thieves, dust-cleaners, and casual snoopers see only ciphertext — not your bases, coords, or inventories.
- Worlds are **portable**: copy the locked folder to another PC with the mod and the same PIN unlocks it there.
- 256-bit random master key, keyed per file, PBKDF2-HmacSHA256 key derivation (210 000 iterations) — the key never leaves memory during play.

### Requirements

- **Minecraft 26.2**
- **Fabric Loader 0.19.3+**
- **Fabric API** (added automatically when installing via CurseForge or a modpack platform)

ZXing (QR-code generation for TOTP enrollment) is bundled inside the mod.