package dev.ciphersave.client;

import dev.ciphersave.CipherSave;
import dev.ciphersave.CipherSessions;
import dev.ciphersave.storage.PinMetaFile;
import dev.ciphersave.storage.SessionMarker;
import dev.ciphersave.storage.WorldFileCipher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ErrorScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.security.GeneralSecurityException;

/** Runs the decrypt/open sequence after a PIN or TOTP unlock, and the setup+open sequence for first open. */
public final class CipherUnlock {
    private CipherUnlock() {
    }

    /**
     * Unlock an already-protected world:
     * 1. crash-rescue: if a session marker is present, any plaintext leftover is re-encrypted first.
     * 2. snapshot the encrypted state to ciphersave_backups.
     * 3. decrypt all protected files in place.
     * 4. activate the session marker, register the in-memory session key, and open the world.
     */
    public static void unlockAndOpen(Minecraft mc, Screen backTo, String levelId, Path worldRoot, byte[] masterKey) {
        ProgressScreen progress = new ProgressScreen(true);
        progress.progressStartNoAbort(Component.translatable("ciphersave.unlocking"));
        progress.progressStage(Component.translatable("ciphersave.decrypting"));
        mc.setScreenAndShow(progress);

        thread("CipherSave-Unlock", () -> {
            try {
                if (SessionMarker.isActive(worldRoot)) {
                    new WorldFileCipher(worldRoot, masterKey).encryptChanged();
                }

                WorldFileCipher cipher = new WorldFileCipher(worldRoot, masterKey);
                cipher.snapshotBackup();
                final int[] done = {0};
                cipher.decryptAll((name, i, total) -> {
                    if (total > 0) {
                        mc.execute(() -> progress.progressStagePercentage(done[0]++ * 100 / total));
                    }
                });
                mc.execute(() -> progress.progressStagePercentage(100));

                SessionMarker.activate(worldRoot);
                CipherSessions.registerUnlocked(worldRoot, masterKey);

                mc.execute(() -> {
                    progress.stop();
                    mc.createWorldOpenFlows().openWorld(levelId, () -> mc.gui.setScreen(backTo));
                });
            } catch (Exception e) {
                CipherSave.LOGGER.error("CipherSave: unlock failed for {}", levelId, e);
                mc.execute(() -> {
                    progress.stop();
                    mc.gui.setScreen(new ErrorScreen(Component.translatable("ciphersave.unlock.failed"), Component.literal(String.valueOf(e))));
                });
            }
        }).start();
    }

    /**
     * First-open setup:
     * 1. generate a fresh master key, write pin_meta.json (PIN + optional TOTP seed).
     * 2. encrypt the (currently plaintext) world files — the initial lock.
     * 3. snapshot the encrypted state to ciphersave_backups.
     * 4. decrypt in place for the live session, activate marker + session key, open world.
     */
    public static void setupAndOpen(
            Minecraft mc, Screen backTo, String levelId, Path worldRoot, byte[] masterKey, String displayName, String pin, String totpSeedOrNull
    ) throws GeneralSecurityException, java.io.IOException {
        prepareForCreate(mc, worldRoot, masterKey, displayName, pin, totpSeedOrNull);
        unlockAndOpen(mc, backTo, levelId, worldRoot, masterKey);
    }

    /**
     * Edit / Recreate gate: decrypt an already-protected world into a live session WITHOUT opening
     * it, then run the given action on the render thread. Used so the vanilla Edit World / Recreate
     * flows can read decrypted level data; the close hook re-encrypts afterwards for edit (for
     * recreate, the fresh world is left plaintext and gets its own setup on first open).
     */
    public static void unlockForMaintenance(Minecraft mc, Path worldRoot, byte[] masterKey, Screen backTo, Runnable after) {
        ProgressScreen progress = new ProgressScreen(true);
        progress.progressStartNoAbort(Component.translatable("ciphersave.unlocking"));
        progress.progressStage(Component.translatable("ciphersave.decrypting"));
        mc.setScreenAndShow(progress);

        thread("CipherSave-Maintenance", () -> {
            try {
                if (SessionMarker.isActive(worldRoot)) {
                    new WorldFileCipher(worldRoot, masterKey).encryptChanged();
                }
                WorldFileCipher cipher = new WorldFileCipher(worldRoot, masterKey);
                cipher.snapshotBackup();
                cipher.decryptAll(null);
                SessionMarker.activate(worldRoot);
                CipherSessions.registerUnlocked(worldRoot, masterKey);
                mc.execute(() -> {
                    progress.stop();
                    after.run();
                });
            } catch (Exception e) {
                CipherSave.LOGGER.error("CipherSave: maintenance unlock failed for {}", worldRoot, e);
                mc.execute(() -> {
                    progress.stop();
                    mc.gui.setScreen(new ErrorScreen(Component.translatable("ciphersave.unlock.failed"), Component.literal(String.valueOf(e))));
                });
            }
        }).start();
    }

    /**
     * World-creation variant: write pin_meta.json and register a (currently empty) plaintext
     * session BEFORE the world is generated, so the setup screen is part of the create flow.
     * No files exist to encrypt/decrypt yet; the close hook encrypts after first play.
     */
    public static void prepareForCreate(
            Minecraft mc, Path worldRoot, byte[] masterKey, String displayName, String pin, String totpSeedOrNull
    ) throws GeneralSecurityException, java.io.IOException {
        PinMetaFile.PinMeta meta = WorldFileCipher.createPinMeta(displayName, pin, masterKey, totpSeedOrNull);
        PinMetaFile.write(worldRoot, meta);
        WorldFileCipher cipher = new WorldFileCipher(worldRoot, masterKey);
        cipher.encryptChanged();
        cipher.decryptAll(null);
        SessionMarker.activate(worldRoot);
        CipherSessions.registerUnlocked(worldRoot, masterKey);
    }

    private static Thread thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}