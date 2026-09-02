package dev.ciphersave;

import dev.ciphersave.storage.PinMetaFile;
import dev.ciphersave.storage.WorldFileCipher;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the session state for each currently-handled world.
 * - isProtected(worldRoot): pin_meta.json present.
 * - isUnlocked(worldRoot): master key resident in memory (plaintext session active).
 * Only worlds with an ACTIVE SESSION hold a key here; the key is wiped on lock/save.
 * Never written to disk.
 */
public final class CipherSessions {
    private static final Map<Path, byte[]> UNLOCKED = new ConcurrentHashMap<>();

    private CipherSessions() {
    }

    public static boolean isProtected(Path worldRoot) {
        return PinMetaFile.isPresent(worldRoot);
    }

    public static boolean isUnlocked(Path worldRoot) {
        return UNLOCKED.containsKey(norm(worldRoot));
    }

    public static byte[] getMasterKey(Path worldRoot) {
        byte[] key = UNLOCKED.get(norm(worldRoot));
        return key == null ? null : key.clone();
    }

    public static void registerUnlocked(Path worldRoot, byte[] masterKey) {
        UNLOCKED.put(norm(worldRoot), masterKey.clone());
    }

    public static void lock(Path worldRoot) {
        UNLOCKED.remove(norm(worldRoot));
    }

    private static Path norm(Path p) {
        return p.toAbsolutePath().normalize();
    }
}