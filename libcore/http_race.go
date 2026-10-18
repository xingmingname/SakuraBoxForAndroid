package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
)

type cleanupBody struct {
	io.ReadCloser
	once    sync.Once
	cleanup func()
}

func (b *cleanupBody) Close() error { err := b.ReadCloser.Close(); b.once.Do(b.cleanup); return err }

type httpAttempt func(context.Context) (*http.Response, error, func())
type httpAttemptResult struct {
	response *http.Response
	err      error
	cleanup  func()
	retain   func()
}

func callHTTPAttempt(ctx context.Context, attempt httpAttempt) (result httpAttemptResult) {
	defer func() {
		if value := recover(); value != nil {
			result.err = fmt.Errorf("HTTP attempt panic: %v", value)
		}
	}()
	result.response, result.err, result.cleanup = attempt(ctx)
	if result.err == nil && result.response == nil {
		result.err = errors.New("HTTP attempt produced no response")
	}
	if result.err == nil && result.response.StatusCode != http.StatusOK {
		result.err = errors.New((&httpResponse{Response: result.response}).errorString())
		result.response = nil
	}
	return
}

func raceHTTPAttempts(ctx context.Context, attempts []httpAttempt) (*http.Response, error) {
	if len(attempts) == 0 {
		return nil, errors.New("no HTTP attempts")
	}
	results := make(chan httpAttemptResult)
	done := make(chan struct{})
	defer close(done)
	for _, attempt := range attempts {
		attemptCtx, cancel := context.WithCancel(ctx)
		winner := make(chan struct{})
		go func(attempt httpAttempt) {
			result := callHTTPAttempt(attemptCtx, attempt)
			cleanup := result.cleanup
			result.retain = func() { close(winner) }
			result.cleanup = func() {
				cancel()
				if cleanup != nil {
					cleanup()
				}
			}
			select {
			case results <- result:
			case <-done:
				if result.response != nil && result.response.Body != nil {
					result.response.Body.Close()
				}
				result.cleanup()
			}
		}(attempt)
		// Cancel a losing attempt promptly, while preserving the winner's body.
		go func() {
			select {
			case <-done:
				select {
				case <-winner:
				default:
					cancel()
				}
			case <-winner:
			case <-attemptCtx.Done():
			}
		}()
	}
	var failures error
	for range attempts {
		select {
		case result := <-results:
			if result.err == nil {
				result.retain()
				// The winner needs a separate context lifetime: done closes after
				// returning, so transfer ownership before waking cancellation.
				result.response.Body = &cleanupBody{ReadCloser: result.response.Body, cleanup: result.cleanup}
				return result.response, nil
			}
			if result.response != nil && result.response.Body != nil {
				result.response.Body.Close()
			}
			result.cleanup()
			failures = errors.Join(failures, result.err)
		case <-ctx.Done():
			return nil, errors.Join(ctx.Err(), failures)
		}
	}
	return nil, failures
}
