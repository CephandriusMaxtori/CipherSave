package dev.ciphersave.client;

import dev.ciphersave.CipherSave;
import dev.ciphersave.CipherSaveConstants;
import dev.ciphersave.crypto.QrEncoder;
import dev.ciphersave.crypto.TotpAuth;
import dev.ciphersave.storage.PinMetaFile;
import dev.ciphersave.storage.WorldFileCipher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.List;

/**
 * CipherSave gate screen. PIN and TOTP (authenticator app) are BOTH primary unlock methods:
 * on the unlock screen pick either and enter it. Setup creates the PIN and optionally enrolls a
 * TOTP seed (QR + test-code verification); whichever exists can be used to open the world.
 *
 * <p>Dark blurred overlay, masked input, audio cues (error vs success).
 */
public final class PinScreen extends Screen {
    private static final String DOTS = "\u2022";

    private final Screen backTo;
    private final String levelId;
    private final Path worldRoot;
    private final String displayName;
    private final boolean setup;
    private final boolean forCreation;
    private final Runnable onCreate;

    // setup stages
    private static final int STAGE_PIN_FIRST = 0;
    private static final int STAGE_PIN_CONFIRM = 1;
    private static final int STAGE_TOTP_CHOICE = 2;
    private static final int STAGE_TOTP_VERIFY = 3;
    private int stage = STAGE_PIN_FIRST;

    private final StringBuilder pin = new StringBuilder();
    private final StringBuilder totpCode = new StringBuilder();
    private String pinFirst;
    private boolean useTotp;                    // unlock: PIN input vs TOTP input (either is primary)
    private boolean totpEnabled = true;         // setup: enroll authenticator recovery
    private String seedBase32;
    private byte[] pendingMasterKey;
    private String error;

    private PinMetaFile.PinMeta meta;           // unlock mode (lazy)
    private boolean[][] qrMatrix;               // setup TOTP verify (lazy)

    private PinScreen(Screen backTo, String levelId, Path worldRoot, String displayName, boolean setup, boolean forCreation, Runnable onCreate) {
        super(Component.translatable("ciphersave.title"));
        this.backTo = backTo;
        this.levelId = levelId;
        this.worldRoot = worldRoot;
        this.displayName = displayName;
        this.setup = setup;
        this.forCreation = forCreation;
        this.onCreate = onCreate;
    }

    /** World with pin_meta.json → unlock; without → first-open setup. */
    public static PinScreen open(Screen backTo, String levelId, Path worldRoot, String displayName) {
        return new PinScreen(backTo, levelId, worldRoot, displayName, !PinMetaFile.isPresent(worldRoot), false, null);
    }

    /** Fresh-world creation gate: the world has not been generated yet; onCreate fires it after setup. */
    public static PinScreen openForCreate(Screen backTo, String levelId, Path worldRoot, String displayName, Runnable onCreate) {
        return new PinScreen(backTo, levelId, worldRoot, displayName, true, true, onCreate);
    }

    @Override
    public void onClose() {
        if (forCreation && !PinMetaFile.isPresent(worldRoot)) {
            try {
                java.nio.file.Files.deleteIfExists(worldRoot);
            } catch (IOException ignored) {
            }
        }
        this.minecraft.gui.setScreen(this.backTo);
    }

    // ------------------------------------------------------------------ input

    private boolean enteringTotp() {
        return setup ? stage == STAGE_TOTP_VERIFY : useTotp;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isSelection()) {              // ENTER / SPACE / NUMPAD_ENTER
            submit();
            return true;
        }
        if (event.key() == 259) {               // BACKSPACE
            if (enteringTotp()) {
                if (!totpCode.isEmpty()) {
                    totpCode.deleteCharAt(totpCode.length() - 1);
                }
            } else if (!pin.isEmpty()) {
                pin.deleteCharAt(pin.length() - 1);
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        String s = event.codepointAsString();
        if (s == null || s.isEmpty()) {
            return false;
        }
        char c = s.charAt(0);
        if (enteringTotp()) {
            if (Character.isDigit(c) && totpCode.length() < CipherSaveConstants.TOTP_DIGITS) {
                totpCode.append(c);
                return true;
            }
            return false;
        }
        if (c >= 0x21 && c <= 0x7E && pin.length() < CipherSaveConstants.PIN_MAX) {
            pin.append(c);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int x = (int) event.x();
        int y = (int) event.y();
        List<int[]> buttons = currentButtons();
        for (int i = 0; i < buttons.size(); i++) {
            int[] b = buttons.get(i);
            if (x >= b[0] && x < b[0] + b[2] && y >= b[1] && y < b[1] + b[3]) {
                onButton(i);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    // ------------------------------------------------------------------ flows

    private void submit() {
        if (enteringTotp()) {
            submitTotp();
        } else if (setup) {
            submitSetupPin();
        } else {
            submitUnlockPin();
        }
    }

    private void submitUnlockPin() {
        try {
            PinMetaFile.PinMeta m = meta();
            byte[] masterKey = WorldFileCipher.unwrapWithPin(m, pin.toString());
            success();
            CipherUnlock.unlockAndOpen(minecraft, backTo, levelId, worldRoot, masterKey);
        } catch (GeneralSecurityException | IOException e) {
            fail(Component.translatable("ciphersave.error.wrongPin"));
        }
    }

    private void submitTotp() {
        try {
            if (setup) {
                if (!TotpAuth.verify(seedBase32, totpCode.toString())) {
                    fail(Component.translatable("ciphersave.error.badCode"));
                    return;
                }
                finishSetup(seedBase32);
                return;
            }
            byte[] masterKey = WorldFileCipher.unwrapWithTotp(meta(), totpCode.toString());
            success();
            CipherUnlock.unlockAndOpen(minecraft, backTo, levelId, worldRoot, masterKey);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            fail(Component.translatable("ciphersave.error.badCode"));
        }
    }

    private void submitSetupPin() {
        if (stage == STAGE_PIN_FIRST) {
            if (pin.length() < CipherSaveConstants.PIN_MIN) {
                fail(Component.translatable("ciphersave.error.tooShort"));
                return;
            }
            pinFirst = pin.toString();
            pin.setLength(0);
            stage = STAGE_PIN_CONFIRM;
        } else if (stage == STAGE_PIN_CONFIRM) {
            if (!pinFirst.equals(pin.toString())) {
                fail(Component.translatable("ciphersave.error.mismatch"));
                return;
            }
            stage = STAGE_TOTP_CHOICE;
            pin.setLength(0);
        }
    }

    private byte[] pendingMasterKey() {
        if (pendingMasterKey == null) {
            pendingMasterKey = new byte[CipherSaveConstants.MASTER_KEY_LENGTH];
            new SecureRandom().nextBytes(pendingMasterKey);
        }
        return pendingMasterKey;
    }

    private PinMetaFile.PinMeta meta() throws IOException, GeneralSecurityException {
        if (meta == null) {
            meta = PinMetaFile.read(worldRoot);
        }
        return meta;
    }

    /** Setup done — existing world: encrypt+decrypt and open. Fresh world: register session, then run creation. */
    private void finishSetup(String seed) {
        try {
            if (forCreation) {
                CipherUnlock.prepareForCreate(minecraft, worldRoot, pendingMasterKey(), displayName, pinFirst, seed);
                success();
                onCreate.run();
            } else {
                CipherUnlock.setupAndOpen(minecraft, backTo, levelId, worldRoot, pendingMasterKey(), displayName, pinFirst, seed);
            }
        } catch (GeneralSecurityException | IOException e) {
            fail(Component.translatable("ciphersave.error.setupFailed"));
        }
    }

    private void success() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0F));
        error = null;
    }

    private void fail(Component message) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.VILLAGER_NO, 1.0F));
        error = message.getString();
        if (enteringTotp()) {
            totpCode.setLength(0);
        } else {
            pin.setLength(0);
        }
    }

    /** Unlock: [0] = submit current method, [1] = switch between PIN and TOTP (both primary). */
    private void onUnlockButton(int index) {
        if (index == 0) {
            submit();
            return;
        }
        useTotp = !useTotp;
        totpCode.setLength(0);
        pin.setLength(0);
        error = null;
    }

    private void onSetupButton(int index) {
        switch (stage) {
            case STAGE_PIN_FIRST, STAGE_PIN_CONFIRM -> {
                if (index == 0) {
                    submitSetupPin();
                }
            }
            case STAGE_TOTP_CHOICE -> {
                if (index == 0) {
                    totpEnabled = true;
                    if (seedBase32 == null) {
                        seedBase32 = TotpAuth.base32Encode(TotpAuth.randomSeedBytes());
                    }
                    stage = STAGE_TOTP_VERIFY;
                } else if (index == 1) {
                    totpEnabled = false;
                    finishSetup(null);
                }
            }
            case STAGE_TOTP_VERIFY -> {
                if (index == 0) {
                    submitTotp();
                } else if (index == 1) {
                    stage = STAGE_TOTP_CHOICE;
                    totpCode.setLength(0);
                    error = null;
                }
            }
            default -> {
            }
        }
    }

    private void onButton(int index) {
        if (setup) {
            onSetupButton(index);
        } else {
            onUnlockButton(index);
        }
    }

    // ------------------------------------------------------------------ buttons + layout

    private static final int BTN_W = 116;
    private static final int BTN_H = 20;

    private List<int[]> currentButtons() {
        int cx = this.width / 2;
        int by = buttonY();
        if (setup) {
            boolean wide = stage == STAGE_TOTP_CHOICE;
            int w = wide ? BTN_W + 24 : BTN_W;
            return List.of(
                    new int[]{cx - w - 4, by, w, BTN_H},
                    new int[]{cx + 4, by, BTN_W, BTN_H}
            );
        }
        return List.of(
                new int[]{cx - BTN_W - 4, by, BTN_W, BTN_H},
                new int[]{cx + 4, by, BTN_W, BTN_H}
        );
    }

    private List<String> currentLabels() {
        if (setup) {
            return switch (stage) {
                case STAGE_TOTP_CHOICE -> List.of(
                        text("ciphersave.totp.enable"),
                        text("ciphersave.totp.skip"));
                case STAGE_TOTP_VERIFY -> List.of(
                        text("ciphersave.totp.verify"),
                        text("ciphersave.btn.back"));
                default -> List.of(
                        text("ciphersave.btn.next"),
                        text("ciphersave.btn.cancel"));
            };
        }
        return useTotp
                ? List.of(text("ciphersave.btn.unlock"), text("ciphersave.btn.usePin"))
                : List.of(text("ciphersave.btn.unlock"), text("ciphersave.btn.useCode"));
    }

    private String text(String key) {
        return Component.translatable(key).getString();
    }

    private static final int DOT_Y = 64;
    private static final int UNDERLINE_Y = 76;
    private static final int ERROR_Y = 86;

    private int buttonY() {
        return Math.min(setup && stage >= STAGE_TOTP_VERIFY ? qrButtonY() : ERROR_Y + 14, this.height - 28);
    }

    private int qrButtonY() {
        int qrBottom = QR_TOP + QR_SIZE + QR_GAP;
        return Math.min(qrBottom + QR_AFTER, this.height - 28);
    }

    private void drawMasked(GuiGraphicsExtractor g, int cx, int y, int len) {
        String dots = DOTS.repeat(len);
        g.centeredText(this.font, dots, cx, y, 0xFFFFFFFF);
        g.fill(cx - 60, y + 12, cx + 60, y + 13, 0xFF666666);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // Background (panorama + blur + menu texture) is already extracted by the framework via
        // extractRenderStateWithTooltipAndSubtitles -> extractBackground. Darken it further here.
        graphics.fill(0, 0, this.width, this.height, 0x99202028);
        int cx = this.width / 2;

        graphics.centeredText(this.font, Component.translatable("ciphersave.title"), cx, 16, 0xFFFFFFFF);
        Component name = Component.literal(displayName == null ? levelId : displayName);
        graphics.centeredText(this.font, name, cx, 30, 0xFFB0B0B0);

        if (setup && stage >= STAGE_TOTP_VERIFY) {
            renderTotpSetup(graphics);
            return;
        }

        Component hint;
        if (setup) {
            hint = stage == STAGE_PIN_CONFIRM
                    ? Component.translatable("ciphersave.pin.confirm")
                    : Component.translatable("ciphersave.pin.choose");
        } else if (useTotp) {
            hint = Component.translatable("ciphersave.totp.enterLabel");
        } else {
            hint = Component.translatable("ciphersave.pin.enter");
        }
        graphics.centeredText(this.font, hint, cx, 46, 0xFF9A9A9A);

        int len = enteringTotp() ? Math.min(totpCode.length(), CipherSaveConstants.TOTP_DIGITS) : pin.length();
        drawMasked(graphics, cx, DOT_Y, len);

        if (error != null) {
            graphics.centeredText(this.font, Component.literal(error), cx, ERROR_Y, 0xFFFF5555);
        }

        List<int[]> buttons = currentButtons();
        List<String> labels = currentLabels();
        for (int i = 0; i < buttons.size(); i++) {
            drawButton(graphics, buttons.get(i), labels.get(i));
        }
    }

    private void drawButton(GuiGraphicsExtractor g, int[] b, String label) {
        g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], 0xFF3A3F59);
        g.fill(b[0], b[1], b[0] + b[2], b[1] + 1, 0xFF666C8C);
        g.fill(b[0], b[1] + b[3] - 1, b[0] + b[2], b[1] + b[3], 0xFF2A2E44);
        g.centeredText(this.font, Component.literal(label), b[0] + b[2] / 2, b[1] + 6, 0xFFFFFFFF);
    }

    // ---- TOTP verify stage (setup) ----

    private static final int QR_SIZE = 100;
    private static final int QR_TOP = 42;
    private static final int QR_GAP = 12;
    private static final int QR_AFTER = 78;

    private void renderTotpSetup(GuiGraphicsExtractor g) {
        if (qrMatrix == null) {
            try {
                String uri = QrEncoder.otpauthUri(seedBase32, displayName);
                qrMatrix = QrEncoder.encodeMatrix(uri, QR_SIZE);
            } catch (Exception e) {
                CipherSave.LOGGER.error("CipherSave: QR generation failed", e);
                qrMatrix = new boolean[0][0];
            }
        }
        int cx = this.width / 2;
        int size = qrMatrix.length;
        int ox = cx - size / 2;
        int oy = QR_TOP;
        g.fill(ox - 4, oy - 4, ox + size + 4, oy + size + 4, 0xFF101010);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (qrMatrix[x][y]) {
                    g.fill(ox + x, oy + y, ox + x + 1, oy + y + 1, 0xFF000000);
                }
            }
        }
        g.centeredText(this.font, Component.translatable("ciphersave.totp.scan"), cx, oy + size + 8, 0xFFFFFFFF);
        g.centeredText(this.font, Component.literal(formatSeed(seedBase32)), cx, oy + size + 20, 0xFF00E5FF);
        g.centeredText(this.font, Component.translatable("ciphersave.totp.enterLabel"), cx, oy + size + 40, 0xFF9A9A9A);
        drawMasked(g, cx, oy + size + 52, Math.min(totpCode.length(), CipherSaveConstants.TOTP_DIGITS));

        int by = buttonY();
        List<int[]> buttons = currentButtons();
        List<String> labels = currentLabels();
        for (int i = 0; i < buttons.size(); i++) {
            drawButton(g, buttons.get(i), labels.get(i));
        }
    }

    private static String formatSeed(String seed) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seed.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append(' ');
            }
            sb.append(seed.charAt(i));
        }
        return sb.toString();
    }
}