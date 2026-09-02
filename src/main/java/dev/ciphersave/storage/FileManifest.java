package dev.ciphersave.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ciphersave.CipherSaveConstants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * ciphersave_manifest.json maps world-relative paths (e.g. "level.dat", "players/data/<uuid>.dat",
 * "dimensions/minecraft/overworld/region/r.0.0.mca") to the last-modified millis at which they were
 * encrypted. Re-encryption only touches files whose mtime changed since the recorded value, plus
 * files not yet listed.
 */
public final class FileManifest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path worldRoot;
    private final Map<String, Long> entries = new HashMap<>();

    private FileManifest(Path worldRoot) {
        this.worldRoot = worldRoot;
    }

    public static FileManifest load(Path worldRoot) {
        FileManifest manifest = new FileManifest(worldRoot);
        Path target = worldRoot.resolve(CipherSaveConstants.MANIFEST_FILE);
        if (Files.isRegularFile(target)) {
            try {
                Stored stored = GSON.fromJson(Files.readString(target, StandardCharsets.UTF_8), Stored.class);
                if (stored != null && stored.files != null) {
                    manifest.entries.putAll(stored.files);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.error("CipherSave: failed to load manifest, treating as empty", e);
            }
        }
        return manifest;
    }

    public boolean needsEncryption(Path worldRootFile) {
        Path rel = worldRoot.relativize(worldRootFile);
        String key = normalize(rel);
        if (!entries.containsKey(key)) {
            return true;
        }
        try {
            long current = Files.getLastModifiedTime(worldRoot.resolve(key)).toMillis();
            return current != entries.get(key);
        } catch (IOException e) {
            return true;
        }
    }

    public void markEncrypted(Path rel) {
        String key = normalize(rel);
        try {
            entries.put(key, Files.getLastModifiedTime(worldRoot.resolve(key)).toMillis());
        } catch (IOException e) {
            entries.put(key, -1L);
        }
    }

    public void save() throws IOException {
        Stored stored = new Stored();
        stored.files = entries;
        Files.writeString(worldRoot.resolve(CipherSaveConstants.MANIFEST_FILE), GSON.toJson(stored), StandardCharsets.UTF_8);
    }

    private static String normalize(Path rel) {
        return rel.toString().replace('\\', '/');
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(FileManifest.class);

    public static final class Stored {
        public Map<String, Long> files;
    }
}