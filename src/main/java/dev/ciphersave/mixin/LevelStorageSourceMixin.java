package dev.ciphersave.mixin;

import com.mojang.serialization.Dynamic;
import dev.ciphersave.storage.PinMetaFile;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.world.level.storage.LevelVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A CipherSave-protected world has an encrypted level.dat, so the vanilla summary reader
 * would flag it corrupted. Intercept readLevelSummary and emit a synthetic, playable summary
 * so the world shows in the list and can be selected for unlocking.
 */
@Mixin(LevelStorageSource.class)
public abstract class LevelStorageSourceMixin {

    @Inject(method = "readLevelSummary", at = @At("HEAD"), cancellable = true)
    private void ciphersave$syntheticSummaryForLockedWorld(LevelStorageSource.LevelDirectory level, boolean locked, CallbackInfoReturnable<LevelSummary> cir) {
        Path worldRoot = level.path();
        if (!PinMetaFile.isPresent(worldRoot)) {
            return;
        }

        try {
            PinMetaFile.PinMeta meta = PinMetaFile.read(worldRoot);
            String levelId = level.directoryName();
            String displayName = (meta.displayName != null && !meta.displayName.isBlank())
                    ? meta.displayName
                    : levelId;

            LevelSettings settings = new LevelSettings(
                    displayName,
                    GameType.SURVIVAL,
                    LevelSettings.DifficultySettings.DEFAULT,
                    false,
                    WorldDataConfiguration.DEFAULT
            );

            CompoundTag versionContext = new CompoundTag();
            CompoundTag version = new CompoundTag();
            version.putString("Name", SharedConstants.getCurrentVersion().name());
            version.putInt("Id", SharedConstants.getCurrentVersion().dataVersion().version());
            version.putString("Series", "main");
            version.putBoolean("Snapshot", !SharedConstants.getCurrentVersion().stable());
            versionContext.put("Version", version);
            versionContext.putLong("LastPlayed", lastModifiedMillis(level));

            LevelVersion levelVersion = LevelVersion.parse(new Dynamic<>(NbtOps.INSTANCE, versionContext));
            LevelSummary summary = new LevelSummary(
                    settings, levelVersion, levelId, false, false, false, false, level.iconFile()
            );
            cir.setReturnValue(summary);
        } catch (IOException | RuntimeException e) {
            // Fall through to vanilla behavior (world will appear corrupted).
        }
    }

    private static long lastModifiedMillis(LevelStorageSource.LevelDirectory level) {
        try {
            Path data = level.dataFile();
            if (Files.isRegularFile(data)) {
                return Files.getLastModifiedTime(data).toMillis();
            }
            Path old = level.oldDataFile();
            if (Files.isRegularFile(old)) {
                return Files.getLastModifiedTime(old).toMillis();
            }
        } catch (IOException ignored) {
        }
        return -1L;
    }
}