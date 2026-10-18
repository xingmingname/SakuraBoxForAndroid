package moe.matsuri.nb4a.utils;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Shared input budgets. Limits are checked before allocation/write/parse. */
public final class ImportLimits {
    public static final int MAX_TEXT_BYTES = 32 * 1024 * 1024;
    public static final int MAX_BACKUP_BYTES = 128 * 1024 * 1024;
    public static final int MAX_ZIP_EXPANDED_BYTES = 64 * 1024 * 1024;
    public static final int MAX_ZIP_ENTRIES = 1024;
    public static final int MAX_NODES = 50000;
    public static final int MAX_CANDIDATES = 200000;
    public static final int MAX_JSON_DEPTH = 128;

    private ImportLimits() {}
    public static final class InputLimitException extends IOException {
        public InputLimitException(String message) { super(message); }
    }
    public static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Configuration import cancelled");
    }
    public static void checkNodeCount(long count) throws InputLimitException {
        if (count > MAX_NODES) throw new InputLimitException("Configuration exceeds 50000 node limit");
    }
    public static void checkEncodedLength(String encoded) throws InputLimitException {
        if (encoded.length() > ((MAX_TEXT_BYTES + 2L) / 3) * 4 + 65536) {
            throw new InputLimitException("Base64 configuration exceeds input limit");
        }
    }
    public static void checkText(String text) throws IOException {
        if (text.length() > MAX_TEXT_BYTES) throw new InputLimitException("Configuration exceeds 32 MiB text limit");
        long bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            if ((i & 4095) == 0) checkCancelled();
            char c = text.charAt(i);
            if (c < 128) bytes++;
            else if (c < 2048) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) { bytes += 4; i++; }
            else bytes += 3;
            if (bytes > MAX_TEXT_BYTES) throw new InputLimitException("Configuration exceeds 32 MiB UTF-8 limit");
        }
        checkJsonStructure(text);
    }
    // Preflight before recursive JSON parsers; braces inside strings do not count.
    public static void checkJsonStructure(String text) throws IOException {
        int first = 0;
        while (first < text.length() && Character.isWhitespace(text.charAt(first))) first++;
        if (first == text.length() || (text.charAt(first) != '{' && text.charAt(first) != '[')) return;
        if (text.startsWith("[Interface]", first)) return;
        int depth = 0, containers = 0, items = 0;
        boolean quoted = false, escaped = false;
        for (int i = first; i < text.length(); i++) {
            if ((i & 4095) == 0) checkCancelled();
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > MAX_JSON_DEPTH || ++containers > 500000) throw new InputLimitException("JSON nesting/object count exceeds import limit");
            } else if (c == ',' || c == ':') {
                if (++items > 2000000) throw new InputLimitException("JSON value count exceeds import limit");
            } else if (c == '}' || c == ']') {
                if (--depth < 0) throw new IOException("Invalid JSON container boundary");
            }
        }
    }
    private static byte[] readBytes(InputStream input, int limit) throws IOException {
        if (limit < 0) throw new InputLimitException("ZIP expansion exceeds 64 MiB limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            checkCancelled();
            int n = input.read(buffer, 0, (int) Math.min(buffer.length, (long) limit - output.size() + 1));
            if (n < 0) break;
            if (n == 0) {
                int one = input.read(); if (one < 0) break;
                if (output.size() == limit) throw new InputLimitException("Configuration input exceeds size limit");
                output.write(one); continue;
            }
            if ((long) output.size() + n > limit) throw new InputLimitException("Configuration input exceeds size limit");
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }
    public static String readUtf8(InputStream input) throws IOException { return readUtf8(input, MAX_TEXT_BYTES); }
    public static String readUtf8(InputStream input, int limit) throws IOException {
        return new String(readBytes(input, limit), StandardCharsets.UTF_8);
    }
    public static InputStream limitedInput(InputStream source, long limit) {
        return new FilterInputStream(source) {
            long count;
            private void added(int n) throws IOException {
                if (n > 0 && (count += n) > limit) throw new InputLimitException("Compressed ZIP exceeds 32 MiB input limit");
            }
            @Override public int read() throws IOException { checkCancelled(); int value = in.read(); if (value >= 0) added(1); return value; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                checkCancelled(); if (len == 0) return 0;
                int n = in.read(b, off, (int) Math.min(len, limit - count + 1)); added(n); return n;
            }
        };
    }
    public static final class Entry {
        public final String name, text;
        Entry(String name, String text) { this.name = name; this.text = text; }
    }
    /** Same duplicate-name sequence as RawUpdater, resuming at its last suffix
     * instead of rescanning all earlier names for each duplicate. */
    public static final class Names {
        private static final class State {
            int index; String name;
            State(String name) { this.name = name; }
        }
        private final java.util.Set<String> used = new java.util.HashSet<>();
        private final java.util.Map<String, State> previous = new java.util.HashMap<>();
        public String allocate(String base) throws IOException {
            checkNodeCount((long) used.size() + 1);
            State state = previous.get(base);
            if (state == null) state = new State(base);
            while (used.contains(state.name)) {
                checkCancelled();
                state.index++;
                state.name = state.name.replace(" (" + (state.index - 1) + ")", "") + " (" + state.index + ")";
            }
            previous.put(base, state); used.add(state.name); return state.name;
        }
    }
    public static final class ZipReader implements Closeable {
        private final ZipInputStream zip;
        private int count, expanded;
        public ZipReader(InputStream source) { zip = new ZipInputStream(limitedInput(source, MAX_TEXT_BYTES)); }
        public Entry next() throws IOException {
            while (true) {
                checkCancelled(); ZipEntry entry = zip.getNextEntry(); if (entry == null) return null;
                if (++count > MAX_ZIP_ENTRIES) throw new InputLimitException("ZIP exceeds 1024 entry limit");
                if (entry.getName().length() > 4096) throw new InputLimitException("ZIP entry name exceeds limit");
                int budget = entry.isDirectory() ? 0 : Math.min(MAX_TEXT_BYTES, MAX_ZIP_EXPANDED_BYTES - expanded);
                if (entry.getSize() > budget) throw new InputLimitException("ZIP entry/expansion exceeds size limit");
                byte[] bytes = readBytes(zip, budget); expanded += bytes.length;
                zip.closeEntry();
                if (!entry.isDirectory()) return new Entry(entry.getName(), new String(bytes, StandardCharsets.UTF_8));
            }
        }
        @Override public void close() throws IOException { zip.close(); }
    }
    public static byte[] inflate(byte[] input) throws IOException {
        if (input.length > MAX_TEXT_BYTES) throw new InputLimitException("Compressed configuration exceeds 32 MiB limit");
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                checkCancelled();
                int n;
                try { n = inflater.inflate(buffer); } catch (DataFormatException error) { throw new IOException("Invalid compressed configuration"); }
                if ((long) output.size() + n > MAX_TEXT_BYTES) throw new InputLimitException("Expanded configuration exceeds 32 MiB limit");
                output.write(buffer, 0, n);
                if (n == 0 && !inflater.finished()) throw new IOException("Truncated, dictionary-dependent or stalled compressed configuration");
            }
            if (inflater.getRemaining() != 0) throw new IOException("Trailing data in compressed configuration");
            return output.toByteArray();
        } finally { inflater.end(); }
    }
}
