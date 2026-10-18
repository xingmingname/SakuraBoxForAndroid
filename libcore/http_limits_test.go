package libcore

import (
	"bytes"
	"compress/gzip"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type limitedHTTPPattern struct {
	left   int64
	read   int64
	closed bool
}

func (r *limitedHTTPPattern) Read(p []byte) (int, error) {
	if r.left == 0 {
		return 0, io.EOF
	}
	n := len(p)
	if int64(n) > r.left {
		n = int(r.left)
	}
	for i := 0; i < n; i++ {
		p[i] = 'x'
	}
	r.left -= int64(n)
	r.read += int64(n)
	return n, nil
}
func (r *limitedHTTPPattern) Close() error { r.closed = true; return nil }
func TestHTTPContentQuotaDuringRead(t *testing.T) {
	for _, known := range []bool{false, true} {
		t.Run(map[bool]string{false: "unknown-length", true: "declared-length"}[known], func(t *testing.T) {
			reader := &limitedHTTPPattern{left: maxHTTPContentBytes + 1000000}
			length := int64(-1)
			if known {
				length = reader.left
			}
			response := &httpResponse{Response: &http.Response{Body: reader, ContentLength: length}}
			for i := 0; i < 2; i++ {
				body, err := response.GetContent()
				if err == nil || body != nil || !strings.Contains(err.Error(), "limit") {
					t.Fatal("oversized content accepted")
				}
			}
			if !reader.closed || reader.read > maxHTTPContentBytes+1 {
				t.Fatal("read/close quota violated")
			}
			if known && reader.read != 0 {
				t.Fatal("known oversized body should be rejected before reading")
			}
		})
	}
	for _, size := range []int{32, 8 << 20, 32 << 20} {
		response := &httpResponse{Response: &http.Response{Body: io.NopCloser(io.LimitReader(&limitedHTTPPattern{left: int64(size)}, int64(size))), ContentLength: int64(size)}}
		content, err := response.GetContent()
		if err != nil || len(content) != size {
			t.Fatalf("valid subscription rejected: size=%d err=%v", size, err)
		}
	}
}

func TestHTTPDecompressedResponseQuotaAndRecovery(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/normal" {
			io.WriteString(w, "normal-after-error")
			return
		}
		w.Header().Set("Content-Encoding", "gzip")
		z := gzip.NewWriter(w)
		io.Copy(z, &limitedHTTPPattern{left: maxHTTPContentBytes + 1})
		z.Close()
	}))
	defer server.Close()
	client := NewHttpClient()
	defer client.Close()
	request := client.NewRequest()
	request.SetURL(server.URL + "/compressed")
	response, err := request.Execute()
	if err != nil {
		t.Fatal(err)
	}
	if body, e := response.GetContent(); e == nil || body != nil {
		t.Fatal("gzip expansion exceeded memory quota")
	}
	request = client.NewRequest()
	request.SetURL(server.URL + "/normal")
	response, err = request.Execute()
	if err != nil {
		t.Fatal(err)
	}
	body, err := response.GetContent()
	if err != nil || string(body) != "normal-after-error" {
		t.Fatal("client failed to recover")
	}
}

type failingHTTPBody struct{}

func (failingHTTPBody) Read([]byte) (int, error) { return 0, errors.New("synthetic body failure") }
func (failingHTTPBody) Close() error             { return nil }
func TestHTTPStreamingAssetFailurePreservesPrevious(t *testing.T) {
	path := filepath.Join(t.TempDir(), "asset.db")
	os.WriteFile(path, []byte("previous"), 0600)
	for _, response := range []*httpResponse{
		{Response: &http.Response{Body: failingHTTPBody{}}},
		{Response: &http.Response{Body: io.NopCloser(strings.NewReader("x")), ContentLength: maxHTTPFileBytes + 1}},
	} {
		if err := response.WriteTo(path); err == nil {
			t.Fatal("failed/oversized asset accepted")
		}
		got, _ := os.ReadFile(path)
		if !bytes.Equal(got, []byte("previous")) {
			t.Fatal("existing asset destroyed")
		}
	}
	response := &httpResponse{Response: &http.Response{Body: io.NopCloser(strings.NewReader("new"))}}
	if err := response.WriteTo(path); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(path)
	if string(got) != "new" {
		t.Fatal("normal asset replacement failed")
	}
	entries, _ := os.ReadDir(filepath.Dir(path))
	if len(entries) != 1 {
		t.Fatal("temporary asset leaked")
	}
}
