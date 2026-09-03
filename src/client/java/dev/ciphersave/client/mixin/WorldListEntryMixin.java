package dev.ciphersave.client.mixin;

import dev.ciphersave.client.PinScreen;
import dev.ciphersave.storage.PinMetaFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/**
 * Intercept every "play" click on a world in the world list. CipherSave guards ALL singleplayer
 * worlds: the first open of a world without pin_meta.json prompts PIN setup; a world with
 * pin_meta.json prompts the unlock screen before its (encrypted) files are opened.
 *
 * <p>Also gates Edit World and Recreate World: both read level.dat directly and would fail with an
 * NbtException on an encrypted world, so they are routed through the unlock screen first
 * (decrypting into a live session), then the vanilla flow re-runs on the now-decrypted data.
 * Because editWorld/recreateWorld each read the summary synchronously, we shadow nothing extra
 * here — the re-invocation happens from the PinScreen callback on the render thread.
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

    @Inject(method = "editWorld", at = @At("HEAD"), cancellable = true)
    private void ciphersave$interceptEdit(CallbackInfo ci) {
        this.ciphersave$gateMaintenance(ci, true);
    }

    @Inject(method = "recreateWorld", at = @At("HEAD"), cancellable = true)
    private void ciphersave$interceptRecreate(CallbackInfo ci) {
        this.ciphersave$gateMaintenance(ci, false);
    }

    @Unique
    private void ciphersave$gateMaintenance(CallbackInfo ci, boolean isEdit) {
        Minecraft minecraft = Minecraft.getInstance();
        Path worldRoot = minecraft.getLevelSource().getLevelPath(this.summary.getLevelId());
        // Unprotected worlds are plaintext — the vanilla flow already works untouched.
        if (!PinMetaFile.isPresent(worldRoot)) {
            return;
        }
        ci.cancel();
        final WorldListEntryMixin self = this;
        minecraft.gui.setScreen(PinScreen.openForMaintenance(
                this.list.getScreen(),
                this.summary.getLevelId(),
                worldRoot,
                this.summary.getLevelName(),
                () -> {
                    if (isEdit) {
                        self.ciphersave$invokeEdit();
                    } else {
                        self.ciphersave$invokeRecreate();
                    }
                }));
    }

    @Unique
    private void ciphersave$invokeEdit() {
        ((WorldListEntryAccessor) (Object) this).ciphersave$callEditWorld();
    }

    @Unique
    private void ciphersave$invokeRecreate() {
        ((WorldListEntryAccessor) (Object) this).ciphersave$callRecreateWorld();
    }
}
