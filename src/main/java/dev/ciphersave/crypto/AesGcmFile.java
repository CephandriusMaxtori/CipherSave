package dev.ciphersave.crypto;

import dev.ciphersave.CipherSaveConstants;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/** AES-256-GCM single-file helper using the CS1 wire format: magic(3)+nonce(12)+ciphertext+tag(16). */
public final class AesGcmFile {
    private static final byte[] MAGIC = CipherSaveConstants.FILE_MAGIC;

    private final SecretKeySpec key;
    private final SecureRandom random;

    public AesGcmFile(byte[] key32) {
        this.key = new SecretKeySpec(key32, "AES");
        this.random = new SecureRandom();
    }

    public byte[] encrypt(byte[] plaintext) throws GeneralSecurityException, IOException {
        byte[] nonce = new byte[CipherSaveConstants.NONCE_LENGTH];
        random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(CipherSaveConstants.TAG_LENGTH * 8, nonce));
        return assemble(MAGIC, nonce, cipher.doFinal(plaintext));
    }

    public byte[] decrypt(byte[] data) throws GeneralSecurityException, IOException {
        int headerLen = MAGIC.length + CipherSaveConstants.NONCE_LENGTH;
        if (data.length < headerLen + CipherSaveConstants.TAG_LENGTH) {
            throw new IOException("CipherSave: truncated data");
        }
        if (!Arrays.equals(data, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new IOException("CipherSave: bad magic");
        }
        byte[] nonce = Arrays.copyOfRange(data, MAGIC.length, headerLen);
        byte[] ciphertextAndTag = Arrays.copyOfRange(data, headerLen, data.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(CipherSaveConstants.TAG_LENGTH * 8, nonce));
        try {
            return cipher.doFinal(ciphertextAndTag);
        } catch (AEADBadTagException e) {
            throw new GeneralSecurityException("CipherSave: decryption failed (wrong key)", e);
        }
    }

    private byte[] assemble(byte[] magic, byte[] nonce, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(magic.length + nonce.length + body.length);
        out.write(magic);
        out.write(nonce);
        out.write(body);
        return out.toByteArray();
    }
}