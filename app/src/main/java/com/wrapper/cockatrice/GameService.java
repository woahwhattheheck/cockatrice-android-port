package com.wrapper.cockatrice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.system.Os;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GameService extends Service {
    private static final String CHANNEL = "cockatrice-session";
    private static final int NOTIFICATION = 1;
    private static final String STOP = "org.cockatrice.onetap.STOP";
    public interface Listener { void update(Snapshot snapshot); }
    public static final class DisplaySession {
        public final int port;
        public final String password;
        DisplaySession(int port, String password) { this.port = port; this.password = password; }
    }
    public static final class Snapshot {
        public final String message;
        public final int percent;
        public final DisplaySession display;
        public final boolean error;
        Snapshot(String message, int percent, DisplaySession display, boolean error) {
            this.message = message; this.percent = percent; this.display = display; this.error = error;
        }
    }
    public final class LocalBinder extends Binder { GameService getService() { return GameService.this; } }
    private final IBinder binder = new LocalBinder();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final StringBuilder log = new StringBuilder();
    private volatile Snapshot snapshot = new Snapshot("Starting…", -1, null, false);
    private volatile Process backend;
    private volatile boolean stopping;
    private volatile File session;
    private FileOutputStream logOutput;
    private long logBytes;
    private long lastNotification;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Cockatrice game", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            requestStop();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION, notification("Preparing game"));
        if (running.compareAndSet(false, true)) {
            stopping = false;
            worker.execute(this::runGame);
        }
        return START_NOT_STICKY;
    }

    public void addListener(Listener listener) { listeners.add(listener); listener.update(snapshot); }
    public void removeListener(Listener listener) { listeners.remove(listener); }
    public void recordHostEvent(String message) {
        // Session URLs carry a private token/password: never put those in logs.
        String safe = message.replaceAll("https?://127\\.0\\.0\\.1:[^\\s]+", "[private viewer URL]");
        record("Android display host: " + safe.substring(0, Math.min(safe.length(), 4000)));
    }
    public synchronized String diagnostics() {
        return "Cockatrice One Tap " + BuildConfig.VERSION_NAME + "\nAndroid " + Build.VERSION.RELEASE
                + " / API " + Build.VERSION.SDK_INT + " / " + Arrays.toString(Build.SUPPORTED_ABIS)
                + "\n" + snapshot.message + "\n\n" + log;
    }

    private synchronized void record(String message) {
        message = message.replaceAll("\u001B\\[[;\\d]*m", "");
        log.append(message).append('\n');
        if (log.length() > 48000) log.delete(0, log.length() - 48000);
        if (logOutput != null && logBytes < 1024 * 1024) {
            try {
                byte[] bytes = (message + "\n").getBytes(StandardCharsets.UTF_8);
                logOutput.write(bytes);
                logOutput.flush();
                logBytes += bytes.length;
            } catch (java.io.IOException ignored) { }
        }
    }

    private void publish(String message, int percent, DisplaySession display, boolean error) {
        snapshot = new Snapshot(message, percent, display, error);
        for (Listener listener : listeners) listener.update(snapshot);
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastNotification > 800 || display != null || error) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(message));
            lastNotification = now;
        }
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, GameService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setContentTitle("Cockatrice One Tap")
                .setContentText(text).setSmallIcon(R.drawable.ic_notification).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }

    private void runGame() {
        try {
            synchronized (this) {
                log.setLength(0);
                if (logOutput != null) logOutput.close();
                File directory = new File(getFilesDir(), "logs");
                RuntimeInstaller.mkdir(directory);
                logOutput = new FileOutputStream(new File(directory, "latest.log"));
                logBytes = 0;
            }
            record("Starting bundled ARM64 runtime. No Termux process or installation is used.");
            if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) {
                throw new java.io.IOException("This build requires a 64-bit ARM Android phone (arm64-v8a).");
            }
            publish("Checking bundled game…", 0, null, false);
            File root = RuntimeInstaller.prepare(this, (text, percent) -> publish(text, percent, null, false));
            if (stopping) throw new InterruptedException();
            publish("Preparing your card database…", 82, null, false);
            File player = new File(getFilesDir(), "player");
            RuntimeInstaller.preparePlayer(root, player);
            session = new File(getCacheDir(), "session");
            RuntimeInstaller.mkdir(session);
            RuntimeInstaller.mkdir(new File(session, "user"));
            for (String stale : new String[]{"session.ready", "stop"}) {
                File file = new File(session, stale);
                if (file.exists() && !file.delete()) throw new java.io.IOException("Cannot clear previous session marker");
            }
            String password = VncAuth.randomHex(4);
            File auth = new File(session, "vnc.pass");
            RuntimeInstaller.writeFile(auth, VncAuth.passwordFile(password));
            Os.chmod(auth.getPath(), 0600);
            File xauth = new File(session, "xauth");
            RuntimeInstaller.writeFile(xauth, DisplayAuth.xauthority());
            Os.chmod(xauth.getPath(), 0600);
            File resolv = new File(session, "resolv.conf");
            RuntimeInstaller.writeFile(resolv, dnsConfiguration().getBytes(StandardCharsets.US_ASCII));
            int vncPort;
            try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                vncPort = reservation.getLocalPort();
            }
            File natives = new File(getApplicationInfo().nativeLibraryDir);
            File temporary = new File(getCacheDir(), "proot");
            RuntimeInstaller.mkdir(temporary);
            List<String> command = new ArrayList<>(Arrays.asList(
                    new File(natives, "libproot.so").getPath(), "--kill-on-exit", "--link2symlink", "-0", "-r", root.getPath(),
                    "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/dev/urandom:/dev/random",
                    "-b", player.getPath() + ":/root", "-b", session.getPath() + ":/run",
                    "-b", resolv.getPath() + ":/etc/resolv.conf", "-w", "/root",
                    "/bin/sh", "/opt/cockatrice/start-session.sh"));
            ProcessBuilder process = new ProcessBuilder(command).directory(getFilesDir()).redirectErrorStream(true);
            Map<String, String> environment = process.environment();
            environment.clear(); // Inherited Termux LD_PRELOAD breaks glibc and must never leak in.
            environment.put("PATH", "/usr/bin:/bin");
            environment.put("HOME", "/root");
            environment.put("LANG", "C.UTF-8");
            environment.put("TMPDIR", temporary.getPath());
            environment.put("PROOT_TMP_DIR", temporary.getPath());
            environment.put("PROOT_LOADER", new File(natives, "libloader.so").getPath());
            environment.put("LD_LIBRARY_PATH", natives.getPath());
            environment.put("COCKATRICE_VNC_PORT", Integer.toString(vncPort));
            environment.put("COCKATRICE_GEOMETRY", "1280x720");
            if (stopping) throw new InterruptedException();
            publish("Starting Cockatrice and its private display…", 90, null, false);
            backend = process.start();
            Process current = backend;
            Thread output = new Thread(() -> {
                try (BufferedReader input = new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) record(line);
                } catch (java.io.IOException e) { if (!stopping) record("Log stream: " + e.getMessage()); }
            }, "cockatrice-backend-log");
            output.setDaemon(true);
            output.start();
            long deadline = android.os.SystemClock.elapsedRealtime() + 180000;
            File ready = new File(session, "session.ready");
            while (!ready.isFile()) {
                if (stopping) throw new InterruptedException();
                if (!current.isAlive()) throw new java.io.IOException("Bundled game exited during startup (code " + current.exitValue() + ").");
                if (android.os.SystemClock.elapsedRealtime() > deadline) throw new java.io.IOException("Game startup timed out. See diagnostics below.");
                Thread.sleep(200);
            }
            verifyVnc(vncPort);
            record("Cockatrice window and VNC handshake are ready.");
            publish("Game running", 100, new DisplaySession(vncPort, password), false);
            int exit = current.waitFor();
            output.join(1000);
            if (!stopping) publish("Cockatrice closed (code " + exit + "). Tap Try again to reopen.", 100, null, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            record("STARTUP ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (!stopping) publish(e.getMessage() == null ? "Game startup failed" : e.getMessage(), 0, null, true);
        } finally {
            Process current = backend;
            if (current != null && current.isAlive()) {
                signalStop();
                try {
                    if (!current.waitFor(3, TimeUnit.SECONDS)) {
                        current.destroy();
                        if (!current.waitFor(2, TimeUnit.SECONDS)) current.destroyForcibly();
                    }
                } catch (InterruptedException e) { current.destroy(); }
            }
            backend = null;
            running.set(false);
            if (stopping) publish("Game stopped. Tap Try again to reopen.", 0, null, true);
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
    }

    private String dnsConfiguration() {
        StringBuilder result = new StringBuilder("options timeout:2 attempts:2\n");
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        LinkProperties properties = connectivity.getLinkProperties(connectivity.getActiveNetwork());
        if (properties != null) {
            for (InetAddress address : properties.getDnsServers()) result.append("nameserver ").append(address.getHostAddress()).append('\n');
        }
        if (result.indexOf("nameserver") == -1) result.append("nameserver 1.1.1.1\n");
        return result.toString();
    }

    private static void verifyVnc(int port) throws java.io.IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(3000);
            byte[] banner = new byte[12];
            int offset = 0;
            while (offset < banner.length) {
                int count = socket.getInputStream().read(banner, offset, banner.length - offset);
                if (count == -1) throw new java.io.IOException("Display connection closed before handshake");
                offset += count;
            }
            if (!new String(banner, StandardCharsets.US_ASCII).startsWith("RFB ")) {
                throw new java.io.IOException("Unexpected display server handshake");
            }
        }
    }

    private void signalStop() {
        File directory = session;
        if (directory != null) try { RuntimeInstaller.writeFile(new File(directory, "stop"), new byte[0]); }
        catch (java.io.IOException ignored) { }
    }

    private void requestStop() {
        stopping = true;
        signalStop();
    }

    @Override public void onDestroy() {
        requestStop();
        // The guest supervisor observes /run/stop and cleans up only its own children.
        worker.shutdown();
        listeners.clear();
        super.onDestroy();
    }
}
