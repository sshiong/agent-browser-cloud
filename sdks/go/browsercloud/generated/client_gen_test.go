package generated

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestGeneratedSurfaceAndRuntimeRequest(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodGet || request.URL.Path != "/api/v1/sessions/ses_1" {
			t.Fatalf("unexpected request: %s %s", request.Method, request.URL.Path)
		}
		if request.Header.Get("X-Tenant-Id") != "tenant-a" || request.Header.Get("X-Actor-Id") != "actor-a" {
			t.Fatalf("identity headers were not preserved")
		}
		writer.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(writer).Encode(map[string]any{"sessionId": "ses_1"})
	}))
	defer server.Close()

	client, err := New(Options{BaseURL: server.URL, TenantID: "tenant-a", ActorID: "actor-a"})
	if err != nil {
		t.Fatal(err)
	}
	result, _, err := client.GetSession(context.Background(), Request{Path: map[string]string{"sessionId": "ses_1"}})
	if err != nil {
		t.Fatal(err)
	}
	if len(Operations) != 185 || result.(map[string]any)["sessionId"] != "ses_1" {
		t.Fatalf("generated contract surface or response is incomplete")
	}
	var _ SessionView
	var _ ProxyRoutingDecision
	var _ RuntimeValidationJobClaim
}

func TestGeneratedQueryAllowlistAndStructuredError(t *testing.T) {
	client, err := New(Options{BaseURL: "https://browser.example", TenantID: "tenant-a"})
	if err != nil {
		t.Fatal(err)
	}
	_, _, err = client.ListSessions(context.Background(), Request{Query: map[string][]string{"notInContract": {"value"}}})
	if err == nil {
		t.Fatal("expected unknown query parameter rejection")
	}
	_, _, err = client.GetSession(context.Background(), Request{
		Path:    map[string]string{"sessionId": "ses_1"},
		Headers: http.Header{"X-Tenant-Id": {"tenant-b"}},
	})
	if err == nil {
		t.Fatal("expected identity-controlled header rejection")
	}
	_, _, err = client.CreateSession(context.Background(), Request{
		Headers: http.Header{"Idempotency-Key": {"idem-1"}},
	})
	if err == nil {
		t.Fatal("expected required request body rejection")
	}
}
