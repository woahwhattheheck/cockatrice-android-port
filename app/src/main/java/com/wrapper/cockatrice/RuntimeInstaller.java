package com.wrapper.cockatrice;

import android.content.Context;
import android.os.StatFs;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Streaming, checked, atomic first-run extraction. User data lives elsewhere. */
final class RuntimeInstaller {
    interface Progress { void update(String message, int percent); }
    private RuntimeInstaller() { }

    static File prepare(Context context, Progress progress) throws Exception {
        JSONObject metadata;
        try (InputStream input = context.getAssets().open("runtime.json")) {
            metadata = new JSONObject(new String(readAll(input), StandardCharsets.UTF_8));
        }
        verifyNatives(context, metadata.getJSONObject("nativeFiles"));
        String checksum = metadata.getString("sha256");
        if (!checksum.matches("[a-f0-9]{64}")) throw new IOException("Invalid runtime checksum");
        File root = new File(context.getFilesDir(), "runtime-" + checksum.substring(0, 16));
        File marker = new File(root, ".complete");
        if (marker.isFile() && checksum.equals(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8))) {
            return root;
        }
        long required = metadata.getLong("unpackedBytes") + 160L * 1024 * 1024;
        if (new StatFs(context.getFilesDir().getPath()).getAvailableBytes() < required) {
            throw new IOException("Not enough free space. Free at least " + (required / 1024 / 1024) + " MB and tap Try again.");
        }
        File staging = new File(root.getPath() + ".staging");
        removeTree(staging);
        mkdir(staging);
        JSONObject expected = metadata.getJSONObject("files");
        Set<String> seen = new HashSet<>();
        MessageDigest archiveDigest = MessageDigest.getInstance("SHA-256");
        long written = 0;
        int lastPercent = -1;
        byte[] buffer = new byte[65536];
        try (InputStream asset = new BufferedInputStream(context.getAssets().open("runtime.zip"), 65536);
             DigestInputStream checked = new DigestInputStream(asset, archiveDigest);
             ZipInputStream zip = new ZipInputStream(checked)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Installation cancelled");
                String name = entry.getName();
                File output = child(staging, name);
                if (entry.isDirectory() || !expected.has(name) || !seen.add(name)) {
                    throw new IOException("Unexpected or duplicate runtime entry: " + name);
                }
                JSONObject record = expected.getJSONObject(name);
                long size = record.getLong("size");
                int mode = record.getInt("mode");
                if (mode != 0644 && mode != 0755) throw new IOException("Invalid runtime file mode");
                mkdir(output.getParentFile());
                MessageDigest fileDigest = MessageDigest.getInstance("SHA-256");
                long fileBytes = 0;
                try (FileOutputStream target = new FileOutputStream(output)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        fileBytes += count;
                        if (fileBytes > size) throw new IOException("Runtime file is larger than expected: " + name);
                        target.write(buffer, 0, count);
                        fileDigest.update(buffer, 0, count);
                        written += count;
                        int percent = (int) (written * 80 / metadata.getLong("unpackedBytes"));
                        if (percent != lastPercent) {
                            progress.update("Preparing bundled game… " + (percent * 100 / 80) + "%", percent);
                            lastPercent = percent;
                        }
                    }
                }
                if (fileBytes != size || !hex(fileDigest.digest()).equals(record.getString("sha256"))) {
                    throw new IOException("Runtime file verification failed: " + name);
                }
                Os.chmod(output.getPath(), mode);
                zip.closeEntry();
            }
            // ZipInputStream stops at the central directory. Hash the remainder too.
            while (checked.read(buffer) != -1) { }
        }
        if (seen.size() != metadata.getInt("fileCount") || seen.size() != expected.length()
                || !hex(archiveDigest.digest()).equals(checksum)) {
            throw new IOException("Bundled runtime verification failed");
        }
        JSONArray directories = metadata.getJSONArray("directories");
        for (int i = 0; i < directories.length(); i++) mkdir(child(staging, directories.getString(i)));
        JSONObject symlinks = metadata.getJSONObject("symlinks");
        for (Iterator<String> keys = symlinks.keys(); keys.hasNext();) {
            String name = keys.next();
            String target = symlinks.getString(name);
            File link = child(staging, name);
            if (target.startsWith("/")) throw new IOException("Absolute runtime symlink");
            File resolved = new File(link.getParentFile(), target).getCanonicalFile();
            if (!resolved.getPath().startsWith(staging.getCanonicalPath() + File.separator)) {
                throw new IOException("Runtime symlink escapes installation");
            }
            mkdir(link.getParentFile());
            Os.symlink(target, link.getPath());
        }
        writeFile(new File(staging, ".complete"), checksum.getBytes(StandardCharsets.UTF_8));
        removeTree(root); // Only this checksum-named, incomplete generated runtime.
        if (!staging.renameTo(root)) throw new IOException("Cannot activate prepared runtime");
        return root;
    }

    static File child(File root, String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("Invalid runtime path");
        }
        for (String part : name.split("/", -1)) {
            if (part.equals("..") || part.equals(".") || part.isEmpty()) throw new IOException("Invalid runtime path");
        }
        File result = new File(root, name).getCanonicalFile();
        if (!result.getPath().startsWith(root.getCanonicalPath() + File.separator)) {
            throw new IOException("Runtime path escapes installation");
        }
        return result;
    }

    private static void verifyNatives(Context context, JSONObject files) throws Exception {
        for (Iterator<String> keys = files.keys(); keys.hasNext();) {
            String name = keys.next();
            File path = child(new File(context.getApplicationInfo().nativeLibraryDir), name);
            if (!path.isFile() || !path.canExecute()) throw new IOException("Android did not install the bundled engine: " + name);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(path)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            if (!hex(digest.digest()).equals(files.getString(name))) throw new IOException("Engine verification failed: " + name);
        }
    }

    static void preparePlayer(File root, File player) throws Exception {
        File data = new File(player, ".local/share/Cockatrice/Cockatrice");
        mkdir(data);
        mkdir(new File(player, ".config"));
        for (String name : new String[]{"cards.xml", "tokens.xml"}) {
            File target = new File(data, name);
            if (!target.exists()) {
                File staging = new File(data, name + ".installing");
                try (InputStream input = new FileInputStream(new File(root, "opt/cockatrice/seed/" + name));
                     FileOutputStream output = new FileOutputStream(staging)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.getFD().sync();
                }
                if (!staging.renameTo(target)) throw new IOException("Cannot initialize card database");
            }
        }
        // These are generated defaults, not copied developer settings. In
        // particular clientversion avoids the upstream first-run Oracle wizard.
        File settings = new File(data, "settings");
        mkdir(settings);
        for (String name : new String[]{"global.ini", "cardDatabase.ini", "downloads.ini"}) {
            File target = new File(settings, name);
            if (!target.exists()) {
                try (InputStream input = new FileInputStream(new File(root, "opt/cockatrice/seed/settings/" + name))) {
                    writeFile(target, readAll(input));
                }
            }
        }
    }

    static void writeFile(File file, byte[] data) throws IOException {
        mkdir(file.getParentFile());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data);
            output.getFD().sync();
        }
    }

    static void mkdir(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Cannot create " + file.getName());
    }

    private static void removeTree(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) {
            Files.delete(file.toPath());
            return;
        }
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot list incomplete runtime");
            for (File child : children) removeTree(child);
        }
        if (!file.delete()) throw new IOException("Cannot remove incomplete runtime");
    }

    static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
