package moe.matsuri.nb4a.utils;
import org.junit.Test;
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
}
