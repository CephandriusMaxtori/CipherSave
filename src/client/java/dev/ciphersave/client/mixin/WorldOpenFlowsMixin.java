package dev.ciphersave.client.mixin;

import dev.ciphersave.client.PinScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Gate WORLD CREATION with the CipherSave setup screen: creating a fresh world goes through
 * CreateWorldScreen -> createFreshLevel(...) (NOT the world-list "Play" path), so it would
 * otherwise never get a PIN. HEAD-cancel the creation, run the PIN setup, and on success
 * re-invoke createFreshLevel with the session already registered (the world is then written to
 * disk as plaintext during play and re-encrypted by the close hook when you leave).
 */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
    private static boolean ciphersave$creating = false;

    @Inject(method = "createFreshLevel", at = @At("HEAD"), cancellable = true)
    private void ciphersave$gateCreate(
            String levelId,
            LevelSettings levelSettings,
            WorldOptions options,
            Function<HolderLookup.Provider, WorldDimensions> dimensionsProvider,
            Screen parentScreen,
            CallbackInfo ci
    ) {
        if (ciphersave$creating) {
            return; // re-invocation after setup: let the original run
        }

        Minecraft minecraft = Minecraft.getInstance();
        Path worldRoot = minecraft.getLevelSource().getLevelPath(levelId);
        try {
            Files.createDirectories(worldRoot);
        } catch (IOException e) {
            dev.ciphersave.CipherSave.LOGGER.warn("CipherSave: could not prepare world folder, skipping setup", e);
            return;
        }

        ci.cancel();
        WorldOpenFlows self = (WorldOpenFlows) (Object) this;
        PinScreen.openForCreate(
                parentScreen,
                levelId,
                worldRoot,
                levelSettings.levelName(),
                () -> {
                    ciphersave$creating = true;
                    try {
                        self.createFreshLevel(levelId, levelSettings, options, dimensionsProvider, parentScreen);
                    } finally {
                        ciphersave$creating = false;
                    }
                }
        );
    }
}