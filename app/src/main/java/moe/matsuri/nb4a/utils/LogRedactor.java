package moe.matsuri.nb4a.utils;

import java.util.regex.Pattern;

/** Also sanitize pre-upgrade logs and native stderr when displaying/exporting. */
public final class LogRedactor {
    private LogRedactor() {}
    private static final String KEYS = "password|passwd|uuid|private_key|private-key|private_key_passphrase|private-key-passphrase|pre_shared_key|pre-shared-key|token|secret|authorization|cookie|username|auth|auth_str|auth-str";
    private static final Pattern JSON = Pattern.compile("(\"(?:" + KEYS + ")\"\\s*:\\s*)(\"(?:\\\\.|[^\"\\\\])*\"|[^,}\\r\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSIGNMENT = Pattern.compile("(\\b(?:" + KEYS + ")\\s*[=:]\\s*)[^\\r\\n,;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern URI = Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^\\s<>\"']+");
    private static final Pattern PEM = Pattern.compile("-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----", Pattern.DOTALL);
    public static String redact(String message) {
        message = PEM.matcher(message).replaceAll("[REDACTED PRIVATE KEY]");
        message = URI.matcher(message).replaceAll("$1[REDACTED]");
        message = JSON.matcher(message).replaceAll("$1\"[REDACTED]\"");
        return ASSIGNMENT.matcher(message).replaceAll("$1[REDACTED]");
    }
}
