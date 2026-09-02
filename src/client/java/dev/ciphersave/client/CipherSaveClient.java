package dev.ciphersave.client;

import net.fabricmc.api.ClientModInitializer;

public final class CipherSaveClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        dev.ciphersave.CipherSave.LOGGER.info("CipherSave client initialized");
    }
}