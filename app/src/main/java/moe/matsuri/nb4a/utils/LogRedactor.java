package moe.matsuri.nb4a.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Also sanitize pre-upgrade logs and native stderr when displaying/exporting. */
public final class LogRedactor {
    private LogRedactor() {}
    private static final String KEYS = "client_key_path|client-key-path|client_key|client-key|password|passwd|uuid|private_key|private-key|private_key_passphrase|private-key-passphrase|pre_shared_key|pre-shared-key|token|secret|authorization|cookie|username|auth|auth_str|auth-str";
    private static final Pattern FIELD = Pattern.compile("(?:\"(?:" + KEYS + ")\"|\\b(?:" + KEYS + ")\\b)\\s*[:=]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern URI = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://");
    private static final Pattern BEGIN = Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----");
    private static final Pattern END = Pattern.compile("-----END [A-Z0-9 ]*PRIVATE KEY-----");

    public static String redact(String message) {
        return new State().apply(message);
    }

    public static void printStackTrace(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        System.err.print(redact(writer.toString()));
    }

    public static void redact(Reader source, Writer destination) throws IOException {
        BufferedReader reader = source instanceof BufferedReader ? (BufferedReader) source : new BufferedReader(source);
        State state = new State();
        String line;
        while ((line = reader.readLine()) != null) destination.write(state.apply(line + "\n"));
    }

    public static byte[] redactTail(Reader source, long maxBytes) throws IOException {
        StringWriter writer = new StringWriter();
        redact(source, writer);
        byte[] safe = writer.toString().getBytes(StandardCharsets.UTF_8);
        if (maxBytes > 0 && maxBytes < safe.length) {
            int start = safe.length - (int) maxBytes;
            while (start < safe.length && (safe[start] & 0xc0) == 0x80) start++;
            return java.util.Arrays.copyOfRange(safe, start, safe.length);
        }
        return safe;
    }

    private static final class State {
        private static final int NORMAL = 0, EXPECT = 1, STRING = 2, STRUCTURE = 3, SCALAR = 4, URL = 5;
        int mode, depth;
        boolean pem, quoted, escape;

        String apply(String text) {
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < text.length()) {
                if (pem) {
                    Matcher end = END.matcher(text);
                    boolean foundEnd = end.find(i);
                    int stop = foundEnd ? end.end() : text.length();
                    for (int n = i; n < stop; n++) if (text.charAt(n) == '\n') out.append('\n');
                    pem = !foundEnd;
                    i = stop;
                    continue;
                }
                char c = text.charAt(i);
                if (mode == EXPECT) {
                    if (Character.isWhitespace(c)) { out.append(c); i++; continue; }
                    out.append("\"[REDACTED]\"");
                    escape = false;
                    quoted = false;
                    if (c == '"') { mode = STRING; i++; }
                    else if (c == '[' || c == '{') { mode = STRUCTURE; depth = 1; i++; }
                    else mode = SCALAR;
                    continue;
                }
                if (mode == STRING || mode == STRUCTURE) {
                    if (c == '\n') out.append('\n');
                    if (escape) escape = false;
                    else if (c == '\\' && (mode == STRING || quoted)) escape = true;
                    else if (c == '"') {
                        if (mode == STRING) mode = NORMAL;
                        else quoted = !quoted;
                    } else if (mode == STRUCTURE && !quoted) {
                        if (c == '[' || c == '{') depth++;
                        if ((c == ']' || c == '}') && --depth == 0) mode = NORMAL;
                    }
                    i++;
                    continue;
                }
                if (mode == SCALAR || mode == URL) {
                    boolean done = mode == URL ? Character.isWhitespace(c) || "<>\"'".indexOf(c) >= 0 : ",;}\r\n".indexOf(c) >= 0;
                    if (done) mode = NORMAL;
                    else { i++; continue; }
                }
                Matcher field = FIELD.matcher(text), begin = BEGIN.matcher(text), uri = URI.matcher(text);
                int f = field.find(i) ? field.start() : text.length();
                int b = begin.find(i) ? begin.start() : text.length();
                int u = uri.find(i) ? uri.start() : text.length();
                int next = Math.min(f, Math.min(b, u));
                out.append(text, i, next);
                if (next == text.length()) break;
                if (next == b) {
                    out.append("[REDACTED PRIVATE KEY]"); pem = true; i = begin.end();
                } else if (next == f) {
                    out.append(text, next, field.end()); mode = EXPECT; i = field.end();
                } else {
                    out.append(text, next, uri.end()).append("[REDACTED]"); mode = URL; i = uri.end();
                }
            }
            return out.toString();
        }
    }
}
