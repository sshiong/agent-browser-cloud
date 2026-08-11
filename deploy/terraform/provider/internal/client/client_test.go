package client

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

func TestCreateGroupUsesBearerAndStableIdempotencyWithoutLocalHeaders(t *testing.T) {
	t.Parallel()
	var keys []string
	server := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodPost || request.URL.Path != "/api/v1/groups" {
			t.Errorf("unexpected request %s %s", request.Method, request.URL.Path)
		}
		if got := request.Header.Get("Authorization"); got != "Bearer secret-token" {
			t.Errorf("unexpected authorization %q", got)
		}
		if request.Header.Get("X-Tenant-Id") != "" || request.Header.Get("X-Roles") != "" {
			t.Error("production request leaked local-development identity headers")
		}
		keys = append(keys, request.Header.Get("Idempotency-Key"))
		response.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(response, `{"groupId":"grp_1234567890abcdef","name":"Operations","description":null,"color":"#26D9C7","defaultOnMaximumReached":"PAUSE_AGENT","defaultAllowMigration":true,"defaultAllowHibernate":true,"sessions":[],"sessionCount":0,"createdBy":"admin","createdAt":"2026-08-11T00:00:00Z","updatedAt":"2026-08-11T00:00:00Z"}`)
	}))
	defer server.Close()

	endpoint, _ := url.Parse(server.URL)
	api := New(Config{Endpoint: endpoint, Token: "secret-token", Version: "test"})
	api.httpClient = server.Client()
	api.httpClient.CheckRedirect = func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }
	request := WorkspaceGroupRequest{Name: "Operations", Color: "#26D9C7", DefaultOnMaximumReached: "PAUSE_AGENT", DefaultAllowMigration: true, DefaultAllowHibernate: true}
	for range 2 {
		created, err := api.CreateGroup(context.Background(), request)
		if err != nil {
			t.Fatal(err)
		}
		if created.GroupID != "grp_1234567890abcdef" {
			t.Fatalf("unexpected group id %q", created.GroupID)
		}
	}
	if len(keys) != 2 || keys[0] == "" || keys[0] != keys[1] || !strings.HasPrefix(keys[0], "tf-") {
		t.Fatalf("idempotency key is not stable: %#v", keys)
	}
}

func TestReconcileGroupSessionsRemovesThenAddsInStableOrder(t *testing.T) {
	t.Parallel()
	var mutex sync.Mutex
	var calls []string
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		mutex.Lock()
		calls = append(calls, request.Method+" "+request.URL.Path)
		mutex.Unlock()
		if request.Header.Get("X-Tenant-Id") != "tenant-local" || request.Header.Get("X-Actor-Id") != "terraform" || request.Header.Get("X-Roles") != "PLATFORM_ADMIN" {
			t.Error("local identity headers were not applied")
		}
		response.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(response, `{}`)
	}))
	defer server.Close()

	endpoint, _ := url.Parse(server.URL)
	api := New(Config{Endpoint: endpoint, LocalDevelopment: true, TenantID: "tenant-local", ActorID: "terraform", Version: "test"})
	err := api.ReconcileGroupSessions(
		context.Background(),
		"grp_1234567890abcdef",
		[]SessionReference{{SessionID: "ses_z000000000000000"}, {SessionID: "ses_a000000000000000"}},
		[]string{"ses_m000000000000000", "ses_b000000000000000"},
	)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"DELETE /api/v1/groups/grp_1234567890abcdef/sessions/ses_a000000000000000",
		"DELETE /api/v1/groups/grp_1234567890abcdef/sessions/ses_z000000000000000",
		"PUT /api/v1/groups/grp_1234567890abcdef/sessions/ses_b000000000000000",
		"PUT /api/v1/groups/grp_1234567890abcdef/sessions/ses_m000000000000000",
	}
	if strings.Join(calls, "\n") != strings.Join(want, "\n") {
		t.Fatalf("unexpected reconciliation order:\n%s", strings.Join(calls, "\n"))
	}
}

func TestAPIErrorExposesBoundedCodeAndRequestIDButNotResponseBody(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
		response.Header().Set("Content-Type", "application/json")
		response.WriteHeader(http.StatusConflict)
		_ = json.NewEncoder(response).Encode(map[string]any{
			"error": map[string]string{"code": "IDEMPOTENCY_CONFLICT", "requestId": "req-safe", "message": "secret=must-not-leak"},
		})
	}))
	defer server.Close()

	endpoint, _ := url.Parse(server.URL)
	api := New(Config{Endpoint: endpoint, LocalDevelopment: true, TenantID: "tenant", ActorID: "actor"})
	_, err := api.CreateTag(context.Background(), WorkspaceTagRequest{Name: "prod", Color: "#123456"})
	if err == nil || !strings.Contains(err.Error(), "IDEMPOTENCY_CONFLICT") || !strings.Contains(err.Error(), "req-safe") {
		t.Fatalf("unexpected API error %v", err)
	}
	if strings.Contains(err.Error(), "must-not-leak") {
		t.Fatal("API error leaked response body")
	}
}

func TestRedirectIsNotFollowed(t *testing.T) {
	t.Parallel()
	var targetCalled atomic.Bool
	target := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { targetCalled.Store(true) }))
	defer target.Close()
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
		http.Redirect(response, &http.Request{}, target.URL, http.StatusTemporaryRedirect)
	}))
	defer server.Close()

	endpoint, _ := url.Parse(server.URL)
	api := New(Config{Endpoint: endpoint, LocalDevelopment: true, TenantID: "tenant", ActorID: "actor"})
	_, err := api.GetWorkspaceSettings(context.Background())
	if err == nil {
		t.Fatal("redirect response unexpectedly succeeded")
	}
	if targetCalled.Load() {
		t.Fatal("client followed a redirect and could leak authorization")
	}
}
