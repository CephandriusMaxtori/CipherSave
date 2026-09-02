package dev.ciphersave.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ciphersave.CipherSaveConstants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * pin_meta.json layout:
 * {
 *   "version": 1,
 *   "displayName": "...",      // world display name shown on the unlock screen
 *   "kdf": {"algorithm":"PBKDF2WithHmacSHA256","iterations":210000,"salt":"hex"},
 *   "pinWrappedKey": "hex",    // AES-GCM(MasterKey) with key derived from PIN
 *   "totp": {"seed":"BASE32"}  // optional; the TOTP seed (also the wrap key source)
 * }
 */
public final class PinMetaFile {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private PinMetaFile() {
    }

    public static boolean isPresent(Path worldRoot) {
        return Files.isRegularFile(worldRoot.resolve(CipherSaveConstants.PIN_META_FILE));
    }

    public static void write(Path worldRoot, PinMeta meta) throws IOException {
        Path target = worldRoot.resolve(CipherSaveConstants.PIN_META_FILE);
        Files.writeString(target, GSON.toJson(meta), StandardCharsets.UTF_8);
    }

    public static PinMeta read(Path worldRoot) throws IOException {
        Path target = worldRoot.resolve(CipherSaveConstants.PIN_META_FILE);
        if (!Files.isRegularFile(target)) {
            throw new IOException("World is not protected by CipherSave (no " + CipherSaveConstants.PIN_META_FILE + ")");
        }
        PinMeta meta = GSON.fromJson(Files.readString(target, StandardCharsets.UTF_8), PinMeta.class);
        if (meta == null || meta.pinWrappedKey == null) {
            throw new IOException("Malformed " + CipherSaveConstants.PIN_META_FILE);
        }
        return meta;
    }

    public static final class PinMeta {
        public int version = 1;
        public String displayName;
        public Kdf kdf = new Kdf();
        public String pinWrappedKey;
        public Totp totp;

        public static final class Kdf {
            public String algorithm = "PBKDF2WithHmacSHA256";
            public int iterations = CipherSaveConstants.PBKDF2_ITERATIONS;
            public String salt;
        }

        public static final class Totp {
            public String seed;
        }
    }
}