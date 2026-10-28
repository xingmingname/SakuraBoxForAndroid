package moe.matsuri.nb4a.utils;
import org.junit.Test;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class LogRedactorTest {
    @Test public void oldLogsAndLogcatAreRedactedBeforeSharing() {
        for(String text : new String[]{"{\"password\":\"test-secret\",\"private_key_passphrase\":\"test-secret\"}","vless://test-secret@host:443?token=test-secret", "Authorization: Basic test-secret", "auth_str=test-secret", "-----BEGIN PRIVATE KEY-----\ntest-secret\n-----END PRIVATE KEY-----"}) {
            String result=LogRedactor.redact(text);
            assertFalse(result,result.contains("test-secret"));
            assertEquals(result,LogRedactor.redact(result));
        }
        assertEquals("DNS timeout; port 53",LogRedactor.redact("DNS timeout; port 53"));
    }

    @Test public void clientKeysArraysPathsAndNestedValuesAreRedacted() {
        for (String text : new String[] {
            "{\"client_key\":[\"secret-one\",\"secret-two\"],\"reason\":\"TLS handshake failed\",\"port\":443}",
            "{\n\"CLIENT_KEY\": [\n\"secret-one\",\n\"secret-two\"\n],\"reason\":\"TLS handshake failed\",\"port\":443}",
            "{\"client_key_path\":\"secret-one\",\"private_key\":{\"nested\":[\"secret-two\"]},\"reason\":\"TLS handshake failed\",\"port\":443}",
            "client_key=secret-one; TLS handshake failed; port=443",
            "client-key-path: secret-two\nTLS handshake failed; port=443"
        }) {
            String safe = LogRedactor.redact(text);
            assertFalse(safe.contains("secret-one")); assertFalse(safe.contains("secret-two"));
            assertTrue(safe.contains("TLS handshake failed")); assertTrue(safe.contains("443"));
            assertEquals(safe, LogRedactor.redact(safe));
        }
    }

    @Test public void privatePemAcrossLinesAndTruncationIsHidden() throws Exception {
        String input = "prefix -----BEGIN EC PRIVATE KEY-----\nsecret-one\nsecret-two\n-----END EC PRIVATE KEY----- suffix\nTLS handshake failed\n";
        StringWriter result = new StringWriter();
        LogRedactor.redact(new StringReader(input), result);
        assertFalse(result.toString().contains("secret-"));
        assertTrue(result.toString().contains("prefix")); assertTrue(result.toString().contains("suffix"));
        assertTrue(result.toString().contains("TLS handshake failed"));
        assertFalse(LogRedactor.redact("-----BEGIN PRIVATE KEY-----\nsecret-one\n").contains("secret-one"));
    }

    @Test public void tailIsAppliedAfterRedactionAndOnUtf8Boundaries() throws Exception {
        String source = "-----BEGIN PRIVATE KEY-----\n" + "secret-one\n".repeat(80) + "-----END PRIVATE KEY-----\nTLS 连接失败\n";
        String safe = new String(LogRedactor.redactTail(new StringReader(source), 50), StandardCharsets.UTF_8);
        assertFalse(safe.contains("secret-one")); assertTrue(safe.endsWith("TLS 连接失败\n"));
        assertFalse(safe.contains("\ufffd"));
        assertEquals("失败\n", new String(LogRedactor.redactTail(new StringReader("连接失败\n"), 7), StandardCharsets.UTF_8));
    }

    @Test public void technicalErrorsLevelsAndStackTracesAreRetained() {
        String safe = "[Debug] AnyTLS: x509: certificate signed by unknown authority\nCaused by: handshake failure\n at service.connect(file.kt:42)\nSNI=example.invalid; ALPN=h2; version=TLS1.3; delay=25ms\n";
        assertEquals(safe, LogRedactor.redact(safe));
        assertEquals("[Error] client_key: \"[REDACTED]\"; parse client x509 key pair failed\n", LogRedactor.redact("[Error] client_key: secret-one; parse client x509 key pair failed\n"));
    }

    @Test public void readerAndDirectRedactionAgreeAcrossArrayLines() throws Exception {
        String source = "{\n\"client_key\":[\n\"one\",\n\"two\"\n],\n\"reason\":\"certificate mismatch\"\n}\n";
        StringWriter writer = new StringWriter();
        LogRedactor.redact(new StringReader(source), writer);
        assertEquals(LogRedactor.redact(source), writer.toString());
    }

    @Test public void stderrStackTraceKeepsItsDestinationAndFrames() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.err;
        try {
            System.setErr(new java.io.PrintStream(bytes, true, "UTF-8"));
            LogRedactor.printStackTrace(new IllegalArgumentException("client_key: secret-value; certificate mismatch"));
        } finally { System.setErr(original); }
        String result = bytes.toString("UTF-8");
        assertFalse(result.contains("secret-value")); assertTrue(result.contains("certificate mismatch"));
        assertTrue(result.contains("java.lang.IllegalArgumentException")); assertTrue(result.contains("at moe.matsuri"));
    }
}
