package libcore

import (
	"io"
	"regexp"
)

var (
	secretJSON       = regexp.MustCompile(`(?i)("(?:password|passwd|uuid|private_key|private-key|private_key_passphrase|private-key-passphrase|pre_shared_key|pre-shared-key|token|secret|authorization|cookie|username|auth|auth_str|auth-str)"\s*:\s*)("(?:\\.|[^"\\])*"|[^,}\r\n]+)`)
	secretAssignment = regexp.MustCompile(`(?i)(\b(?:password|passwd|uuid|private_key|private_key_passphrase|pre_shared_key|token|secret|authorization|cookie|auth|auth_str|auth-str)\s*[=:]\s*)[^\r\n,;]+`)
	logURI           = regexp.MustCompile(`[a-zA-Z][a-zA-Z0-9+.-]*://[^\s<>"']+`)
	privatePEM       = regexp.MustCompile(`(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----`)
)

func redactLog(message string) string {
	message = privatePEM.ReplaceAllString(message, "[REDACTED PRIVATE KEY]")
	message = logURI.ReplaceAllStringFunc(message, func(uri string) string {
		for i := 0; i < len(uri); i++ {
			if uri[i] == ':' {
				return uri[:i] + "://[REDACTED]"
			}
		}
		return "[REDACTED URI]"
	})
	message = secretJSON.ReplaceAllString(message, `${1}"[REDACTED]"`)
	return secretAssignment.ReplaceAllString(message, `${1}[REDACTED]`)
}

type redactingLogWriter struct{ writer io.Writer }

func (w redactingLogWriter) Write(data []byte) (int, error) {
	_, err := w.writer.Write([]byte(redactLog(string(data))))
	if err != nil {
		return 0, err
	}
	return len(data), nil
}
