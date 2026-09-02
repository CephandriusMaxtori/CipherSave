package dev.ciphersave.crypto;

import dev.ciphersave.CipherSaveConstants;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;

/** RFC 6238 TOTP implementation used to verify authenticator-app codes, plus base32 + otpauth URI helpers. */
public final class TotpAuth {
    public static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpAuth() {
    }

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int bits = 0;
        int value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((value >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((value << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    public static byte[] base32Decode(String input) throws IllegalArgumentException {
        String clean = input.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        ByteBuffer bb = ByteBuffer.allocate((clean.length() * 5) / 8);
        int bits = 0;
        int value = 0;
        for (int i = 0; i < clean.length(); i++) {
            int idx = BASE32_ALPHABET.indexOf(clean.charAt(i));
            if (idx < 0) {
                throw new IllegalArgumentException("invalid base32 char: " + clean.charAt(i));
            }
            value = (value << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                bb.put((byte) ((value >>> (bits - 8)) & 0xFF));
                bits -= 8;
            }
        }
        return bb.array();
    }

    public static byte[] randomSeedBytes() {
        byte[] seed = new byte[CipherSaveConstants.TOTP_SEED_LENGTH];
        new SecureRandom().nextBytes(seed);
        return seed;
    }

    public static String codeToString(long code) {
        String s = Long.toString(code);
        return String.format(Locale.ROOT, "%0" + CipherSaveConstants.TOTP_DIGITS + "d", Long.parseLong(s));
    }

    public static boolean verify(String seedBase32, String providedCode) throws GeneralSecurityException {
        if (providedCode == null) {
            return false;
        }
        long code = Long.parseLong(providedCode.trim());
        long[] counters = window();
        byte[] key = Sha.digest(base32Decode(seedBase32));
        for (long counter : counters) {
            if (hotp(key, counter) == code) {
                return true;
            }
        }
        return false;
    }

    private static long[] window() {
        long unix = System.currentTimeMillis() / 1000L;
        long step = unix / CipherSaveConstants.TOTP_PERIOD_SECONDS;
        return new long[]{step};
    }

    private static long hotp(byte[] key, long counter) throws GeneralSecurityException {
        byte[] text = ByteBuffer.allocate(8).putLong(counter).array();
        byte[] hash;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
            hash = mac.doFinal(text);
        } catch (GeneralSecurityException e) {
            throw new GeneralSecurityException("TOTP HMAC failure", e);
        }
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int pow = (int) Math.pow(10, CipherSaveConstants.TOTP_DIGITS);
        return binary % pow;
    }
}