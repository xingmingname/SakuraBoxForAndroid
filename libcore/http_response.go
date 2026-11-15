package libcore

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"
)

// Full in-memory content is used by subscriptions/configuration. Asset writes
// remain streaming and have a separate, much larger disk quota.
const maxHTTPContentBytes int64 = 32 << 20
const maxHTTPFileBytes int64 = 512 << 20

func checkedHTTPResponse(response *http.Response) (HTTPResponse, error) {
	if response == nil {
		return nil, errors.New("HTTP transport produced no response")
	}
	result := &httpResponse{Response: response}
	if response.StatusCode != http.StatusOK {
		return nil, errors.New(result.errorString())
	}
	return result, nil
}

type httpResponse struct {
	*http.Response

	getContentOnce sync.Once
	content        []byte
	contentError   error
}

func (h *httpResponse) errorString() string {
	content, err := h.getContentString()
	if err != nil {
		return fmt.Sprint("HTTP ", h.Status)
	}
	if len(content) > 100 {
		content = content[:100] + " ..."
	}
	return fmt.Sprint("HTTP ", h.Status, ": ", content)
}

func (h *httpResponse) GetHeader(key string) *StringBox {
	return wrapString(h.Header.Get(key))
}

func (h *httpResponse) GetContent() ([]byte, error) {
	h.getContentOnce.Do(func() {
		defer h.Body.Close()
		if h.ContentLength > maxHTTPContentBytes {
			h.contentError = errors.New("HTTP response exceeds 32 MiB in-memory limit")
			return
		}
		h.content, h.contentError = io.ReadAll(io.LimitReader(h.Body, maxHTTPContentBytes+1))
		if h.contentError == nil && int64(len(h.content)) > maxHTTPContentBytes {
			h.contentError = errors.New("HTTP response exceeds 32 MiB in-memory limit")
		}
		if h.contentError != nil {
			h.content = nil
		}
	})
	return h.content, h.contentError
}

func (h *httpResponse) GetContentString() (*StringBox, error) {
	content, err := h.getContentString()
	if err != nil {
		return nil, err
	}
	return wrapString(content), nil
}

func (h *httpResponse) getContentString() (string, error) {
	content, err := h.GetContent()
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteTo(path string) error {
	defer h.Body.Close()
	if h.ContentLength > maxHTTPFileBytes {
		return errors.New("HTTP asset exceeds 512 MiB download limit")
	}
	// A failed or over-quota update must not truncate the existing asset.
	file, err := os.CreateTemp(filepath.Dir(path), ".http-download-*")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	defer file.Close()
	n, err := io.Copy(file, io.LimitReader(h.Body, maxHTTPFileBytes+1))
	if err != nil {
		return err
	}
	if n > maxHTTPFileBytes {
		return errors.New("HTTP asset exceeds 512 MiB download limit")
	}
	if err = file.Sync(); err != nil {
		return err
	}
	if err = file.Close(); err != nil {
		return err
	}
	return os.Rename(file.Name(), path)
}
