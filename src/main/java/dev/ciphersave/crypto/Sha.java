package dev.ciphersave.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;

final class Sha {
    private Sha() {
    }

    static byte[] digest(byte[] input) throws GeneralSecurityException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (GeneralSecurityException e) {
            throw e;
        }
    }
}