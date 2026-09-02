package dev.ciphersave.crypto;

import dev.ciphersave.CipherSaveConstants;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.HexFormat;

/** PBKDF2-with-HmacSHA256 key derivation for PIN -> KEK and seed -> seedKey. */
public final class KeyDerivation {
    private KeyDerivation() {
    }

    public static byte[] randomSalt(int length) {
        byte[] salt = new byte[length];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    public static byte[] derivePinKey(String pin, byte[] salt) throws GeneralSecurityException {
        return pbkdf2(pin.toCharArray(), salt, CipherSaveConstants.PBKDF2_ITERATIONS, CipherSaveConstants.GCM_KEY_BITS);
    }

    /**
     * TOTP seed is random 20 bytes base32-encoded; the key that wraps the master key is
     * SHA-256 of the raw seed bytes (so possession of the TOTP secret yields the key).
     */
    public static byte[] deriveSeedKey(byte[] rawSeed) throws GeneralSecurityException {
        return Sha.digest(rawSeed);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyBits) throws GeneralSecurityException {
        try {
            KeySpec spec = new PBEKeySpec(password, salt, iterations, keyBits);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    public static byte[] unhex(String s) {
        if (s == null || (s.length() & 1) != 0) {
            throw new IllegalArgumentException("bad hex");
        }
        return HexFormat.of().parseHex(s);
    }
}