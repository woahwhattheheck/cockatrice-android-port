package com.wrapper.cockatrice;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** X11 authentication is independent of the viewer's VNC password. */
final class DisplayAuth {
    private DisplayAuth() { }
    static byte[] xauthority() throws IOException {
        byte[] cookie = new byte[16];
        new SecureRandom().nextBytes(cookie);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeShort(65535); // FamilyWild: private cookie, any local transport.
            field(output, new byte[0]);
            field(output, "83".getBytes(StandardCharsets.US_ASCII));
            field(output, "MIT-MAGIC-COOKIE-1".getBytes(StandardCharsets.US_ASCII));
            field(output, cookie);
        }
        return bytes.toByteArray();
    }
    private static void field(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeShort(bytes.length);
        output.write(bytes);
    }
}
