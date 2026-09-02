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
        PinMetaFile.PinMeta meta = WorldFileCipher.createPinMeta(displayName, pin, masterKey, totpSeedOrNull);
        PinMetaFile.write(worldRoot, meta);
        unlockAndOpen(mc, backTo, levelId, worldRoot, masterKey);
    }

    private static Thread thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}