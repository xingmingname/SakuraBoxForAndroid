package io.nekohasekai.sagernet.utils

import org.junit.Assert.*
import org.junit.Test

class CrashLogRedactionTest {
    @Test fun crashFormattingKeepsCausesAndFramesButRemovesClientSecrets() {
        val cause = IllegalArgumentException("client_key_path=private-path; parse client x509 key pair failed")
        cause.stackTrace = arrayOf(StackTraceElement("TlsClient", "connect", "TlsClient.kt", 42))
        val error = IllegalStateException("client_key: private-value; TLS handshake failed", cause)
        error.stackTrace = arrayOf(StackTraceElement("VpnService", "start", "VpnService.kt", 7))
        val result = CrashHandler.formatThrowable(error)
        assertFalse(result.contains("private-path")); assertFalse(result.contains("private-value"))
        assertTrue(result.contains("TLS handshake failed")); assertTrue(result.contains("parse client x509 key pair failed"))
        assertTrue(result.contains("Caused by: java.lang.IllegalArgumentException"))
        assertTrue(result.contains("TlsClient.connect(TlsClient.kt:42)"))
        assertTrue(result.contains("VpnService.start(VpnService.kt:7)"))
    }
}
