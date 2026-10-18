package libcore

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestHTTPProductionTimeoutAndResponseBody(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/slow" {
			<-r.Context().Done()
			return
		}
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		time.Sleep(20 * time.Millisecond)
		_, _ = io.WriteString(w, "response-body")
	}))
	defer server.Close()
	for _, fallback := range []bool{false, true} {
		client := NewHttpClient()
		client.SetTimeout(100)
		if fallback {
			client.TryH3Direct()
		}
		request := client.NewRequest()
		_ = request.SetURL(server.URL + "/slow")
		response, err := request.Execute()
		if response != nil || !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("timeout must return an error: %v %v", response, err)
		}
		request = client.NewRequest()
		_ = request.SetURL(server.URL)
		response, err = request.Execute()
		if err != nil {
			t.Fatal(err)
		}
		body, err := response.GetContent()
		if err != nil || string(body) != "response-body" {
			t.Fatalf("winner context cancelled before body consumption: %q %v", body, err)
		}
		client.Close()
	}
}

func TestAllowInsecureIsolationInProductionRequests(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, "ok") }))
	defer server.Close()
	client := NewHttpClient()
	defer client.Close()
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(insecure bool) {
			defer wg.Done()
			request := client.NewRequest()
			_ = request.SetURL(server.URL)
			if insecure {
				request.AllowInsecure()
			}
			response, err := request.Execute()
			if insecure {
				if err != nil {
					t.Errorf("opted-in TLS failed: %v", err)
				} else {
					_, _ = response.GetContent()
				}
			} else if err == nil {
				_, _ = response.GetContent()
				t.Error("secure request inherited AllowInsecure")
			}
		}(i%2 == 0)
	}
	wg.Wait()
	if client.(*httpClient).tls.InsecureSkipVerify {
		t.Fatal("shared TLS policy changed")
	}
}

func TestHTTPAttemptFailuresAndPanic(t *testing.T) {
	for _, attempt := range []httpAttempt{
		func(context.Context) (*http.Response, error, func()) { return nil, nil, nil },
		func(context.Context) (*http.Response, error, func()) { panic("test panic") },
		func(context.Context) (*http.Response, error, func()) {
			return &http.Response{StatusCode: 403, Status: "403 Forbidden", Body: io.NopCloser(strings.NewReader("denied"))}, nil, nil
		},
	} {
		response, err := raceHTTPAttempts(context.Background(), []httpAttempt{attempt})
		if response != nil || err == nil {
			t.Fatalf("attempt failure lost: %v %v", response, err)
		}
	}
	request := NewHttpClient().NewRequest()
	if response, err := request.Execute(); response != nil || err == nil {
		t.Fatal("missing URL accepted")
	}
}
