package dev.ciphersave.client.mixin;

import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(WorldSelectionList.WorldListEntry.class)
public interface WorldListEntryAccessor {
    @Invoker("editWorld")
    void ciphersave$callEditWorld();

    @Invoker("recreateWorld")
    void ciphersave$callRecreateWorld();
}
