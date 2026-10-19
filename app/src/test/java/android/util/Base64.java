package android.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** JVM test adapter for Android's Base64 API. Never packaged in production APKs.
 * Exercises the real universal-link compression/serialization with JDK Base64;
 * it does not stand in for Android instrumentation or socket/network behavior.
 */
public final class Base64 {
    public static final int DEFAULT = 0, NO_PADDING = 1, NO_WRAP = 2, CRLF = 4, URL_SAFE = 8;
    public static byte[] encode(byte[] input, int flags) {
        return encode(input, 0, input.length, flags);
    }
    public static byte[] encode(byte[] input, int offset, int length, int flags) {
        java.util.Base64.Encoder encoder = (flags & URL_SAFE) != 0
                ? java.util.Base64.getUrlEncoder() : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) encoder = encoder.withoutPadding();
        String value = encoder.encodeToString(Arrays.copyOfRange(input, offset, offset + length));
        if ((flags & NO_WRAP) == 0 && !value.isEmpty()) {
            String separator = (flags & CRLF) != 0 ? "\r\n" : "\n";
            StringBuilder wrapped = new StringBuilder();
            for (int i = 0; i < value.length(); i += 76) {
                wrapped.append(value, i, Math.min(value.length(), i + 76)).append(separator);
            }
            value = wrapped.toString();
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }
    public static String encodeToString(byte[] input, int flags) {
        return new String(encode(input, flags), StandardCharsets.US_ASCII);
    }
    public static byte[] decode(String value, int flags) {
        String compact = value.replaceAll("[\\t\\r\\n ]", "");
        return ((flags & URL_SAFE) != 0 ? java.util.Base64.getUrlDecoder() : java.util.Base64.getDecoder()).decode(compact);
    }
    public static byte[] decode(byte[] value, int flags) {
        return decode(new String(value, StandardCharsets.US_ASCII), flags);
    }
}
