package dev.ciphersave.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ciphersave.CipherSaveConstants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ciphersave_session.json records that a world is CURRENTLY DECRYPTED (plaintext session active).
 * It holds no keys/material; it exists purely so that after a crash we know leftover plaintext
 * must be re-encrypted at next unlock before the world is served. Written on successful unlock,
 * removed once the world is locked again (on save/close).
 */
public final class SessionMarker {
    private static final Gson GSON = new GsonBuilder().create();

    private SessionMarker() {
    }

    public static boolean isActive(Path worldRoot) {
        return Files.isRegularFile(worldRoot.resolve(CipherSaveConstants.SESSION_MARKER_FILE));
    }

    public static void activate(Path worldRoot) throws IOException {
        Marker marker = new Marker();
        marker.sessionId = Long.toHexString(System.nanoTime());
        marker.activatedAt = System.currentTimeMillis();
        Files.writeString(worldRoot.resolve(CipherSaveConstants.SESSION_MARKER_FILE), GSON.toJson(marker), StandardCharsets.UTF_8);
    }

    public static void deactivate(Path worldRoot) {
        try {
            Files.deleteIfExists(worldRoot.resolve(CipherSaveConstants.SESSION_MARKER_FILE));
        } catch (IOException e) {
            LOGGER.warn("Could not remove session marker", e);
        }
    }

    public static final class Marker {
        public String sessionId;
        public long activatedAt;
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(SessionMarker.class);
}