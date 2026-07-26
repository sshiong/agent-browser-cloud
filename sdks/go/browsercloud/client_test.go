package browsercloud

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestCreateMediaSessionPreservesIdentityAndIdempotency(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.Header.Get("X-Tenant-Id") != "tenant-a" {
			t.Fatal("tenant identity was not sent")
		}
		if request.Header.Get("Idempotency-Key") != "idem-1" {
			t.Fatal("idempotency key was not sent")
		}
		var body map[string]any
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		if body["mediaWorkload"] != true || body["requestedMediaStreams"] != float64(1) {
			t.Fatal("media resource demand was not encoded")
		}
		response.Header().Set("Content-Type", "application/json")
		_, _ = response.Write([]byte(`{"sessionId":"ses_1234567890abcdef"}`))
	}))
	defer server.Close()
	client, err := New(Options{BaseURL: server.URL, TenantID: "tenant-a"})
	if err != nil {
		t.Fatal(err)
	}
	result, err := client.CreateSession(context.Background(), CreateSessionInput{
		ProfileID: "profile-a", Region: "local", MediaWorkload: true,
		RequestedMediaStreams: 1, MediaBitrateKbps: 4000, IdempotencyKey: "idem-1",
	})
	if err != nil || result["sessionId"] != "ses_1234567890abcdef" {
		t.Fatalf("unexpected result=%v error=%v", result, err)
	}
}

func TestStructuredAPIError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		response.Header().Set("Content-Type", "application/json")
		response.WriteHeader(http.StatusServiceUnavailable)
		_, _ = response.Write([]byte(`{"code":"MEDIA_QUOTA_REJECTED","message":"rejected","requestId":"req-1"}`))
	}))
	defer server.Close()
	client, _ := New(Options{BaseURL: server.URL, TenantID: "tenant-a"})
	_, err := client.StartSession(context.Background(), "ses_1234567890abcdef")
	apiError, ok := err.(*APIError)
	if !ok || apiError.Code != "MEDIA_QUOTA_REJECTED" || apiError.RequestID != "req-1" {
		t.Fatalf("structured error was not preserved: %#v", err)
	}
}
