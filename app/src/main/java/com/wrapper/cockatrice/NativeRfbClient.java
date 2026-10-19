package com.wrapper.cockatrice;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Embedded RFB display transport. It does not modify the game or need WebView. */
public final class NativeRfbClient implements AutoCloseable {
    public interface Listener {
        void connected(int width, int height);
        void frame(int[] pixels, int width, int height);
        void clipboard(String text);
        void failed(String message);
    }
    private interface Write { void send(DataOutputStream output) throws IOException; }
    private final Listener listener;
    private final int port;
    private final String password;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object outputLock = new Object();
    private final ExecutorService input = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cockatrice-native-input");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Socket socket;
    private volatile DataOutputStream output;
    private volatile boolean ready;
    private int width;
    private int height;
    private int[] pixels;

    public NativeRfbClient(int port, String password, Listener listener) {
        this.port = port;
        this.password = password;
        this.listener = listener;
    }

    public void start() {
        Thread reader = new Thread(this::readDisplay, "cockatrice-native-display");
        reader.setDaemon(true);
        reader.start();
    }

    private void readDisplay() {
        try {
            Socket connection = new Socket();
            socket = connection;
            connection.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            connection.setTcpNoDelay(true);
            connection.setSoTimeout(15000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(connection.getInputStream(), 65536));
            output = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream(), 8192));
            byte[] banner = new byte[12];
            in.readFully(banner);
            String version = new String(banner, StandardCharsets.US_ASCII);
            if (!version.matches("RFB 003\\.00[3-9]\\n")) throw new IOException("Unsupported display protocol");
            int minor = Math.min(8, Integer.parseInt(version.substring(8, 11)));
            output.write((minor >= 8 ? "RFB 003.008\n" : minor >= 7 ? "RFB 003.007\n" : "RFB 003.003\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            int security;
            if (minor >= 7) {
                int count = in.readUnsignedByte();
                if (count == 0) throw new IOException("Display rejected connection: " + readText(in, 65536));
                byte[] types = new byte[count];
                in.readFully(types);
                security = 0;
                for (byte type : types) if (type == 2) security = 2;
                if (security == 0) throw new IOException("Display did not offer password authentication");
                output.writeByte(security);
                output.flush();
            } else {
                security = in.readInt();
                if (security == 0) throw new IOException(readText(in, 65536));
            }
            if (security != 2) throw new IOException("Unsupported display authentication");
            byte[] challenge = new byte[16];
            in.readFully(challenge);
            byte[] key = new byte[8];
            byte[] ascii = password.getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < Math.min(ascii.length, 8); i++) key[i] = (byte) (Integer.reverse(ascii[i] & 255) >>> 24);
            Cipher des = Cipher.getInstance("DES/ECB/NoPadding");
            des.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
            output.write(des.doFinal(challenge));
            output.flush();
            if (in.readInt() != 0) throw new IOException("Display authentication failed" + (minor >= 8 ? ": " + readText(in, 65536) : ""));
            output.writeByte(1); // Shared connection; a viewer reload never kills the game.
            output.flush();
            resize(in.readUnsignedShort(), in.readUnsignedShort());
            byte[] serverFormat = new byte[16];
            in.readFully(serverFormat);
            readText(in, 65536); // Desktop name is not needed for transport.
            // Request predictable little-endian BGRX pixels and only encodings
            // this reader implements. Loopback requires no image recompression.
            output.write(new byte[]{0, 0, 0, 0, 32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255,
                    0, (byte) 255, 16, 8, 0, 0, 0, 0});
            output.writeByte(2);
            output.writeByte(0);
            output.writeShort(3);
            output.writeInt(0); // Raw framebuffer rectangles.
            output.writeInt(1); // CopyRect.
            output.writeInt(-223); // DesktopSize.
            output.flush();
            connection.setSoTimeout(0);
            ready = true;
            listener.connected(width, height);
            requestFrame(false);
            while (running.get()) {
                int type = in.readUnsignedByte();
                if (type == 0) {
                    in.readUnsignedByte();
                    int rectangles = in.readUnsignedShort();
                    for (int i = 0; i < rectangles; i++) {
                        int x = in.readUnsignedShort(), y = in.readUnsignedShort();
                        int w = in.readUnsignedShort(), h = in.readUnsignedShort();
                        int encoding = in.readInt();
                        if (encoding == -223) { resize(w, h); continue; }
                        if (encoding == -224) break;
                        bounds(x, y, w, h);
                        if (encoding == 0) {
                            byte[] row = new byte[w * 4];
                            for (int yy = 0; yy < h; yy++) {
                                in.readFully(row);
                                int offset = (y + yy) * width + x;
                                for (int xx = 0; xx < w; xx++) {
                                    int at = xx * 4;
                                    pixels[offset + xx] = 0xff000000 | ((row[at + 2] & 255) << 16)
                                            | ((row[at + 1] & 255) << 8) | (row[at] & 255);
                                }
                            }
                        } else if (encoding == 1) {
                            int sx = in.readUnsignedShort(), sy = in.readUnsignedShort();
                            bounds(sx, sy, w, h);
                            if (y > sy) for (int yy = h - 1; yy >= 0; yy--) System.arraycopy(pixels, (sy + yy) * width + sx, pixels, (y + yy) * width + x, w);
                            else for (int yy = 0; yy < h; yy++) System.arraycopy(pixels, (sy + yy) * width + sx, pixels, (y + yy) * width + x, w);
                        } else throw new IOException("Unexpected display encoding: " + encoding);
                    }
                    listener.frame(pixels, width, height);
                    requestFrame(true);
                } else if (type == 1) {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    int count = in.readUnsignedShort();
                    byte[] colours = new byte[count * 6];
                    in.readFully(colours);
                } else if (type == 2) {
                    // Bell: no audio forwarding is needed to render the game.
                } else if (type == 3) {
                    in.skipBytes(3);
                    listener.clipboard(readText(in, 1024 * 1024));
                } else throw new IOException("Unexpected display message: " + type);
            }
        } catch (Exception error) {
            if (running.get()) listener.failed(error instanceof EOFException ? "The display connection closed" : String.valueOf(error.getMessage()));
        } finally { close(); }
    }

    private void resize(int w, int h) throws IOException {
        if (w < 1 || h < 1 || (long) w * h > 16777216) throw new IOException("Invalid display dimensions");
        width = w;
        height = h;
        pixels = new int[w * h];
    }

    private void bounds(int x, int y, int w, int h) throws IOException {
        if (x + w > width || y + h > height) throw new IOException("Display rectangle is outside the framebuffer");
    }

    private static String readText(DataInputStream input, int limit) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > limit) throw new IOException("Invalid display text length");
        byte[] bytes = new byte[count];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void requestFrame(boolean incremental) throws IOException {
        synchronized (outputLock) {
            output.writeByte(3);
            output.writeByte(incremental ? 1 : 0);
            output.writeShort(0); output.writeShort(0);
            output.writeShort(width); output.writeShort(height);
            output.flush();
        }
    }

    private void enqueue(Write action) {
        if (!ready || !running.get()) return;
        try {
            input.execute(() -> {
                if (!running.get()) return;
                try {
                    synchronized (outputLock) { action.send(output); output.flush(); }
                } catch (IOException error) {
                    if (running.get()) listener.failed("Display input failed: " + error.getMessage());
                    close();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    public void pointer(int x, int y, int buttons) {
        enqueue(out -> { out.writeByte(5); out.writeByte(buttons); out.writeShort(x); out.writeShort(y); });
    }

    public void key(int keysym, boolean down) {
        enqueue(out -> writeKey(out, keysym, down));
    }

    private static void writeKey(DataOutputStream out, int keysym, boolean down) throws IOException {
        out.writeByte(4); out.writeByte(down ? 1 : 0); out.writeShort(0); out.writeInt(keysym);
    }

    public void tapKey(int keysym) {
        enqueue(out -> { writeKey(out, keysym, true); writeKey(out, keysym, false); });
    }

    public void paste(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024 * 1024) { listener.failed("Clipboard text is larger than 1 MB"); return; }
        enqueue(out -> {
            out.writeByte(6); out.write(new byte[3]); out.writeInt(bytes.length); out.write(bytes);
            writeKey(out, 0xffe3, true); writeKey(out, 'v', true); writeKey(out, 'v', false); writeKey(out, 0xffe3, false);
        });
    }

    @Override public void close() {
        running.set(false);
        ready = false;
        input.shutdownNow();
        Socket current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
    }
}
