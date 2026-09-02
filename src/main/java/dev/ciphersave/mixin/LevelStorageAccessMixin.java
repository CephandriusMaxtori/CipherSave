package dev.ciphersave.mixin;

import dev.ciphersave.CipherSessions;
import dev.ciphersave.storage.PinMetaFile;
import dev.ciphersave.storage.SessionMarker;
import dev.ciphersave.storage.WorldFileCipher;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/**
 * When a protected world's storage access is closed (server save/quit), the server has already
 * flushed all saves (level.dat, player data, region data are on disk and file handles released).
 * TAIL-hook close() to re-encrypt everything and drop the decrypted session key.
 */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStorageAccessMixin {

    @Shadow
    public abstract LevelStorageSource.LevelDirectory getLevelDirectory();

    @Inject(method = "close", at = @At("TAIL"))
    private void ciphersave$encryptOnClose(CallbackInfo ci) {
        try {
            Path worldRoot = this.getLevelDirectory().path();
            if (!PinMetaFile.isPresent(worldRoot)) {
                return;
            }
            if (!CipherSessions.isUnlocked(worldRoot)) {
                return;
            }
            if (!SessionMarker.isActive(worldRoot)) {
                CipherSessions.lock(worldRoot);
                return;
            }

            byte[] masterKey = CipherSessions.getMasterKey(worldRoot);
            if (masterKey == null) {
                SessionMarker.deactivate(worldRoot);
                return;
            }

            try {
                WorldFileCipher cipher = new WorldFileCipher(worldRoot, masterKey);
                int encrypted = cipher.encryptChanged().size();
                cipher.storeManifest();
                if (encrypted > 0) {
                    dev.ciphersave.CipherSave.LOGGER.info("CipherSave: re-encrypted {} files on world close", encrypted);
                }
            } catch (Exception e) {
                dev.ciphersave.CipherSave.LOGGER.error("CipherSave: failed to re-encrypt world on close", e);
            } finally {
                SessionMarker.deactivate(worldRoot);
                CipherSessions.lock(worldRoot);
            }
        } catch (Exception e) {
            dev.ciphersave.CipherSave.LOGGER.error("CipherSave: error in close-encryption hook", e);
        }
    }
}