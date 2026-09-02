package dev.ciphersave;

public final class CipherSaveConstants {
    public static final String PIN_META_FILE = "pin_meta.json";
    public static final String SESSION_MARKER_FILE = "ciphersave_session.json";
    public static final String MANIFEST_FILE = "ciphersave_manifest.json";
    public static final String BACKUP_DIR = "ciphersave_backups";

    public static final byte[] FILE_MAGIC = {'C', 'S', '1'};
    public static final int NONCE_LENGTH = 12;
    public static final int TAG_LENGTH = 16;
    public static final int GCM_KEY_BITS = 256;
    public static final int MASTER_KEY_LENGTH = 32;
    public static final int PBKDF2_ITERATIONS = 210000;
    public static final int SALT_LENGTH = 16;
    public static final int TOTP_SEED_LENGTH = 20;

    public static final String TOTP_ISSUER = "CipherSave";
    public static final int TOTP_PERIOD_SECONDS = 30;
    public static final int TOTP_DIGITS = 6;

    public static final int PIN_MIN = 4;
    public static final int PIN_MAX = 8;

    private CipherSaveConstants() {
    }
}