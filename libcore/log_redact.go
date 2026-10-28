package libcore

import (
	"io"
	"os"
	"regexp"
	"strings"
	"sync"

	"github.com/matsuridayo/libneko/neko_log"
)

const logSecretKeys = "client_key_path|client-key-path|client_key|client-key|password|passwd|uuid|private_key|private-key|private_key_passphrase|private-key-passphrase|pre_shared_key|pre-shared-key|token|secret|authorization|cookie|username|auth|auth_str|auth-str"

var (
	secretField     = regexp.MustCompile(`(?i)(?:"(?:` + logSecretKeys + `)"|\b(?:` + logSecretKeys + `)\b)\s*[:=]\s*`)
	logURIScheme    = regexp.MustCompile(`[a-zA-Z][a-zA-Z0-9+.-]*://`)
	privatePEMBegin = regexp.MustCompile(`-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----`)
	privatePEMEnd   = regexp.MustCompile(`-----END [A-Z0-9 ]*PRIVATE KEY-----`)
)

const (
	logNormal = iota
	logExpect
	logString
	logStructure
	logScalar
	logURL
)

type logRedactor struct {
	mode, depth         int
	pem, quoted, escape bool
}

func (s *logRedactor) redact(text string) string {
	var out strings.Builder
	for i := 0; i < len(text); {
		if s.pem {
			end := privatePEMEnd.FindStringIndex(text[i:])
			stop := len(text)
			if end != nil {
				stop = i + end[1]
			}
			for _, c := range text[i:stop] {
				if c == '\n' {
					out.WriteByte('\n')
				}
			}
			s.pem = end == nil
			i = stop
			continue
		}
		c := text[i]
		if s.mode == logExpect {
			if c == ' ' || c == '\t' || c == '\r' || c == '\n' {
				out.WriteByte(c)
				i++
				continue
			}
			out.WriteString(`"[REDACTED]"`)
			s.escape, s.quoted = false, false
			switch c {
			case '"':
				s.mode = logString
				i++
			case '[', '{':
				s.mode, s.depth = logStructure, 1
				i++
			default:
				s.mode = logScalar
			}
			continue
		}
		if s.mode == logString || s.mode == logStructure {
			if c == '\n' {
				out.WriteByte(c)
			}
			if s.escape {
				s.escape = false
			} else if c == '\\' && (s.mode == logString || s.quoted) {
				s.escape = true
			} else if c == '"' {
				if s.mode == logString {
					s.mode = logNormal
				} else {
					s.quoted = !s.quoted
				}
			} else if s.mode == logStructure && !s.quoted {
				if c == '[' || c == '{' {
					s.depth++
				}
				if c == ']' || c == '}' {
					s.depth--
					if s.depth == 0 {
						s.mode = logNormal
					}
				}
			}
			i++
			continue
		}
		if s.mode == logScalar || s.mode == logURL {
			done := strings.ContainsRune(",;}\r\n", rune(c))
			if s.mode == logURL {
				done = strings.ContainsRune(" \t\r\n<>\"'", rune(c))
			}
			if done {
				s.mode = logNormal
			} else {
				i++
				continue
			}
		}
		f := secretField.FindStringIndex(text[i:])
		b := privatePEMBegin.FindStringIndex(text[i:])
		u := logURIScheme.FindStringIndex(text[i:])
		next, kind, end := len(text), logNormal, 0
		if b != nil {
			next, kind, end = i+b[0], -1, i+b[1]
		}
		if f != nil && i+f[0] < next {
			next, kind, end = i+f[0], logExpect, i+f[1]
		}
		if u != nil && i+u[0] < next {
			next, kind, end = i+u[0], logURL, i+u[1]
		}
		out.WriteString(text[i:next])
		if next == len(text) {
			break
		}
		switch kind {
		case -1:
			out.WriteString("[REDACTED PRIVATE KEY]")
			s.pem = true
		case logExpect:
			out.WriteString(text[next:end])
			s.mode = logExpect
		case logURL:
			out.WriteString(text[next:end])
			out.WriteString("[REDACTED]")
			s.mode = logURL
		}
		i = end
	}
	return out.String()
}

func redactLog(message string) string { return (&logRedactor{}).redact(message) }

type redactingLogWriter struct {
	writer  io.Writer
	mu      sync.Mutex
	state   logRedactor
	pending string
}

func (w *redactingLogWriter) Write(data []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.pending += string(data)
	for len(w.pending) > 0 {
		n := strings.IndexByte(w.pending, '\n') + 1
		if n == 0 || n > 64*1024 {
			if len(w.pending) <= 64*1024 {
				break
			}
			n = 64*1024 - 128 // Keep split field names and PEM markers for the next chunk.
			for _, pattern := range []*regexp.Regexp{secretField, privatePEMBegin, privatePEMEnd, logURIScheme} {
				for _, match := range pattern.FindAllStringIndex(w.pending, -1) {
					if match[0] < n && match[1] > n {
						if match[0] == 0 {
							n = match[1]
						} else {
							n = match[0]
						}
						break
					}
				}
			}
		}
		text := w.state.redact(w.pending[:n])
		w.pending = w.pending[n:]
		if _, err := io.WriteString(w.writer, text); err != nil {
			return 0, err
		}
	}
	return len(data), nil
}

func (w *redactingLogWriter) Flush() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	text := w.state.redact(w.pending)
	w.pending = ""
	_, err := io.WriteString(w.writer, text)
	return err
}

type nativeLogSink struct{}

func (nativeLogSink) Write(data []byte) (int, error) {
	if neko_log.LogWriter == nil {
		return os.Stderr.Write(data)
	}
	return neko_log.LogWriter.Write(data)
}

var safeNativeLog = &redactingLogWriter{writer: nativeLogSink{}}
