package moe.matsuri.nb4a.utils;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** Not a preference: never included in settings dumps, Room or app backups. */
public final class ClashApiSecretStore {
    private final File directory;
    private static final Gson JSON = new Gson();
    // Accommodate HTML-safe JSON escaping of the explicitly supported 4096
    // secret characters and 512 controller characters without a write/read
    // budget mismatch (each escaped character can occupy six bytes).
    private static final int MAX_RECORD_BYTES = 32768;

    public static final class Credentials {
        public final String controller;
        public final String secret;
        public final String panelOrigin;
        public Credentials(String controller, String secret) {
            this(controller, secret, newPanelOrigin());
        }
        public Credentials(String controller, String secret, String panelOrigin) {
            this.controller = controller;
            this.secret = secret;
            this.panelOrigin = panelOrigin;
        }
        // Deliberately no toString implementation containing the fields.
    }

    public ClashApiSecretStore(File noBackupDirectory) { directory = noBackupDirectory; }

    public static String newSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static String newPanelOrigin() {
        return "http://sakurabox-" + newSecret() + ".localhost";
    }

    public static boolean isPanelOrigin(String value) {
        return value != null && value.matches("http://sakurabox-[0-9a-f]{64}\\.localhost");
    }

    private RandomAccessFile open() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot open private API credential storage");
        return new RandomAccessFile(new File(directory, "clash-api-session.json"), "rw");
    }

    public void activate(String controller, String secret) throws IOException {
        if (controller == null || secret == null || controller.length() > 512 || secret.length() > 4096) {
            throw new IOException("Invalid API credential record");
        }
        // The JVM lock prevents overlapping locks from different objects in one
        // process; the file lock serializes foreground and background processes.
        synchronized (ClashApiSecretStore.class) {
            try (RandomAccessFile file = open(); FileLock lock = file.getChannel().lock()) {
                String origin = null;
                if (file.length() > 1 && file.length() <= MAX_RECORD_BYTES) {
                    byte[] previous = new byte[(int) file.length()];
                    file.readFully(previous);
                    try {
                        Credentials old = JSON.fromJson(new String(previous, StandardCharsets.UTF_8), Credentials.class);
                        if (old != null && isPanelOrigin(old.panelOrigin)) origin = old.panelOrigin;
                    } catch (RuntimeException ignored) { /* corrupt records are replaced under the lock */ }
                }
                if (origin == null) origin = newPanelOrigin();
                byte[] encoded = JSON.toJson(new Credentials(controller, secret, origin)).getBytes(StandardCharsets.UTF_8);
                file.seek(0);
                file.setLength(0);
                file.write(encoded);
                file.getFD().sync();
            }
        }
    }

    public Credentials active() throws IOException {
        synchronized (ClashApiSecretStore.class) {
            try (RandomAccessFile file = open(); FileLock lock = file.getChannel().lock()) {
                long length = file.length();
                if (length < 2 || length > MAX_RECORD_BYTES) throw unavailable();
                byte[] encoded = new byte[(int) length];
                file.readFully(encoded);
                Credentials value;
                try { value = JSON.fromJson(new String(encoded, StandardCharsets.UTF_8), Credentials.class); }
                catch (RuntimeException error) { throw unavailable(); }
                if (value == null || value.controller == null || value.secret == null ||
                        value.controller.isEmpty() || value.secret.isEmpty() || !isPanelOrigin(value.panelOrigin)) throw unavailable();
                return value;
            }
        }
    }

    private static IOException unavailable() {
        return new IOException("Management API is inactive; enable it and restart the connection");
    }
}
