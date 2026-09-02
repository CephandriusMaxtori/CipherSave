package dev.ciphersave.client.mixin;

import dev.ciphersave.client.PinScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/**
 * Intercept every "play" click on a world in the world list. CipherSave guards ALL singleplayer
 * worlds: the first open of a world without pin_meta.json prompts PIN setup; a world with
 * pin_meta.json prompts the unlock screen before its (encrypted) files are opened.
 */
@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListEntryMixin {

    @Shadow
    @Final
    private WorldSelectionList list;

    @Shadow
    @Final
    private LevelSummary summary;

    @Inject(method = "joinWorld", at = @At("HEAD"), cancellable = true)
    private void ciphersave$interceptJoin(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        Path worldRoot = minecraft.getLevelSource().getLevelPath(this.summary.getLevelId());
        ci.cancel();
        Screen backTo = this.list.getScreen();
        minecraft.gui.setScreen(PinScreen.open(backTo, this.summary.getLevelId(), worldRoot, this.summary.getLevelName()));
    }
}