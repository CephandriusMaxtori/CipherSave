package dev.ciphersave.crypto;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import dev.ciphersave.CipherSaveConstants;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** otpauth:// URI generation and QR encoding (powered by ZXing). */
public final class QrEncoder {
    private QrEncoder() {
    }

    public static String otpauthUri(String seedBase32, String worldName) {
        String label = CipherSaveConstants.TOTP_ISSUER + ":" + sanitizeLabel(worldName);
        return "otpauth://totp/"
                + percentEncode(label)
                + "?secret=" + seedBase32
                + "&issuer=" + percentEncode(CipherSaveConstants.TOTP_ISSUER)
                + "&algorithm=SHA1&digits=" + CipherSaveConstants.TOTP_DIGITS
                + "&period=" + CipherSaveConstants.TOTP_PERIOD_SECONDS;
    }

    private static String sanitizeLabel(String s) {
        return (s == null || s.trim().isEmpty()) ? "CipherSave" : s.trim();
    }

    private static String percentEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    /** Render the given text to a boolean matrix (true = dark module). Size = module*height for a square. */
    public static boolean[][] encodeMatrix(String text, int desiredSize) throws WriterException {
        BitMatrix matrix = new QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                desiredSize,
                desiredSize,
                Map.of(
                        EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                        EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name(),
                        EncodeHintType.MARGIN, 2
                )
        );
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        boolean[][] out = new boolean[w][h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[x][y] = matrix.get(x, y);
            }
        }
        return out;
    }
}