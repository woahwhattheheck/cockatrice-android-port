package com.wrapper.cockatrice;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

final class VncAuth {
    private static final SecureRandom RANDOM = new SecureRandom();
    private VncAuth() { }

    static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        StringBuilder text = new StringBuilder();
        for (byte value : random) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    /** Standard VNC password-file format; authentication stays on loopback. */
    static byte[] passwordFile(String password) throws GeneralSecurityException {
        byte[] key = {(byte) 0xe8, 0x4a, (byte) 0xd6, 0x60, (byte) 0xc4, 0x72, 0x1a, (byte) 0xe0};
        Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
        return cipher.doFinal(Arrays.copyOf(password.getBytes(StandardCharsets.US_ASCII), 8));
    }
}
