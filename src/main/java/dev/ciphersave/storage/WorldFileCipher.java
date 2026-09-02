package dev.ciphersave.storage;

import dev.ciphersave.CipherSaveConstants;
import dev.ciphersave.crypto.AesGcmFile;
import dev.ciphersave.crypto.KeyDerivation;
import dev.ciphersave.crypto.TotpAuth;
import dev.ciphersave.storage.PinMetaFile.PinMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Encrypts/decrypts the world's protected files in place using the (in-memory) master key.
 * Protected scope:
 *   - <root>/level.dat, level.dat_old
 *   - <root>/players/data/<uuid>.dat(.old), players/stats/*.dat, players/advancements/*.dat
 *   - <root>/dimensions/.../region/*.mca
 * Excludes: pin_meta.json, session.lock, icon.png, ciphersave_* internal files, resources.
 */
public final class WorldFileCipher {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(WorldFileCipher.class);
    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path worldRoot;
    private final byte[] masterKey;
    private final FileManifest manifest;

    public WorldFileCipher(Path worldRoot, byte[] masterKey32) {
        this.worldRoot = worldRoot;
        this.masterKey = masterKey32;
        this.manifest = FileManifest.load(worldRoot);
    }

    /** Wrap the master key with a new pin (replaces pinWrappedKey). */
    public static PinMeta createPinMeta(String displayName, String pin, byte[] masterKey, String totpSeedBase32)
            throws GeneralSecurityException, IOException {
        PinMeta meta = new PinMeta();
        meta.displayName = displayName;
        meta.kdf.salt = KeyDerivation.hex(KeyDerivation.randomSalt(CipherSaveConstants.SALT_LENGTH));
        byte[] pinKek = KeyDerivation.derivePinKey(pin, KeyDerivation.unhex(meta.kdf.salt));
        meta.pinWrappedKey = KeyDerivation.hex(new AesGcmFile(pinKek).encrypt(masterKey));
        if (totpSeedBase32 != null) {
            PinMeta.Totp totp = new PinMeta.Totp();
            totp.seed = totpSeedBase32;
            meta.totp = totp;
        }
        return meta;
    }

    /** Verify a PIN and return the wrapped master key from pin meta. */
    public static byte[] unwrapWithPin(PinMeta meta, String pin) throws GeneralSecurityException, IOException {
        byte[] pinKek = KeyDerivation.derivePinKey(pin, KeyDerivation.unhex(meta.kdf.salt));
        return new AesGcmFile(pinKek).decrypt(KeyDerivation.unhex(meta.pinWrappedKey));
    }

    /** Verify a TOTP code against the stored seed and return the wrapped master key. */
    public static byte[] unwrapWithTotp(PinMeta meta, String code) throws GeneralSecurityException, IOException {
        if (meta.totp == null || meta.totp.seed == null) {
            throw new GeneralSecurityException("CipherSave: TOTP not configured for this world");
        }
        if (!TotpAuth.verify(meta.totp.seed, code)) {
            throw new GeneralSecurityException("CipherSave: invalid TOTP code");
        }
        byte[] seedKey = KeyDerivation.deriveSeedKey(TotpAuth.base32Decode(meta.totp.seed));
        return new AesGcmFile(seedKey).decrypt(KeyDerivation.unhex(meta.pinWrappedKey));
    }

    /**
     * Encrypt all protected files that are currently plaintext (or changed since last encryption).
     * Returns list of files encrypted (relative paths).
     */
    public List<String> encryptChanged() throws IOException, GeneralSecurityException {
        List<String> encrypted = new ArrayList<>();
        AesGcmFile cipher = new AesGcmFile(masterKey);
        for (Path file : enumerateProtectedFiles()) {
            String relKey = worldRoot.relativize(file).toString().replace('\\', '/');
            if (isEncrypted(file)) {
                continue;
            }
            byte[] plain = Files.readAllBytes(file);
            byte[] enc = cipher.encrypt(plain);
            Files.write(file, enc);
            manifest.markEncrypted(file);
            encrypted.add(relKey);
        }
        return encrypted;
    }

    /**
     * Decrypt all protected files in place (assumes they're currently encrypted). Returns count of files decrypted.
     * Must run while the world access is closed / before vanilla reads them.
     */
    public int decryptAll(ProgressCallback callback) throws IOException, GeneralSecurityException {
        AesGcmFile cipher = new AesGcmFile(masterKey);
        int count = 0;
        List<Path> files = enumerateProtectedFiles();
        for (Path file : files) {
            if (!isEncrypted(file)) {
                continue;
            }
            byte[] enc = Files.readAllBytes(file);
            byte[] plain = cipher.decrypt(enc);
            Files.write(file, plain);
            manifest.markEncrypted(file);
            count++;
            if (callback != null) {
                callback.onProgress(file.getFileName().toString(), count, files.size());
            }
        }
        return count;
    }

    /** True if the given protected file currently starts with the CS1 magic (i.e. encrypted). */
    public boolean isEncrypted(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            byte[] head = Files.readAllBytes(file);
            return head.length >= 3 && head[0] == 'C' && head[1] == 'S' && head[2] == '1';
        } catch (IOException e) {
            return false;
        }
    }

    /** Snapshot the current encrypted files to ciphersave_backups/<utc-stamp>/. */
    public Path snapshotBackup() throws IOException {
        Path backupRoot = worldRoot.resolve(CipherSaveConstants.BACKUP_DIR);
        Files.createDirectories(backupRoot);
        Path stamp = backupRoot.resolve(BACKUP_STAMP.format(Instant.now()));
        Files.createDirectories(stamp);
        int copied = 0;
        for (Path file : enumerateProtectedFiles()) {
            if (!isEncrypted(file)) {
                continue;
            }
            Path rel = worldRoot.relativize(file);
            Path dest = stamp.resolve(rel);
            Files.createDirectories(dest.getParent());
            Files.copy(file, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            copied++;
        }
        trimBackups(backupRoot);
        return stamp;
    }

    private void trimBackups(Path backupRoot) throws IOException {
        List<Path> dirs;
        try (Stream<Path> walk = Files.list(backupRoot)) {
            dirs = walk.filter(Files::isDirectory).sorted().toList();
        }
        while (dirs.size() > 3) {
            Files.deleteIfExists(dirs.get(0));
            dirs = dirs.subList(1, dirs.size());
        }
    }

    public List<Path> enumerateProtectedFiles() throws IOException {
        List<Path> result = new ArrayList<>();
        addIfExists(result, worldRoot.resolve("level.dat"));
        addIfExists(result, worldRoot.resolve("level.dat_old"));
        collectWalk(result, worldRoot.resolve("players"));
        Path dims = worldRoot.resolve("dimensions");
        if (Files.isDirectory(dims)) {
            try (Stream<Path> walk = Files.walk(dims)) {
                walk.filter(path -> path.getFileName() != null && path.getFileName().toString().endsWith(".mca"))
                        .forEach(result::add);
            }
        }
        return result;
    }

    public void storeManifest() throws IOException {
        manifest.save();
    }

    private static void addIfExists(List<Path> list, Path p) {
        if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            list.add(p);
        }
    }

    private void collectWalk(List<Path> result, Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(path -> {
                String name = path.getFileName().toString();
                Path rel = dir.getParent() == null ? path.getFileName() : worldRoot.relativize(path);
                boolean underPlayers = rel.startsWith("players");
                return underPlayers && (name.endsWith(".dat") || name.endsWith(".dat_old"));
            }).forEach(result::add);
        }
    }

    public interface ProgressCallback {
        void onProgress(String fileName, int done, int total);
    }
}