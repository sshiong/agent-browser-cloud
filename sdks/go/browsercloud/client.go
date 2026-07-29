package browsercloud

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Client struct {
	baseURL     string
	tenantID    string
	accessToken string
	actorID     string
	httpClient  *http.Client
}

type Options struct {
	BaseURL     string
	TenantID    string
	AccessToken string
	ActorID     string
	HTTPClient  *http.Client
}

type APIError struct {
	Status    int
	Code      string
	Message   string
	RequestID string
}

func (e *APIError) Error() string {
	return fmt.Sprintf("%d %s: %s request_id=%s", e.Status, e.Code, e.Message, e.RequestID)
}

type CreateSessionInput struct {
	ProfileID             string            `json:"profileId"`
	Region                string            `json:"region"`
	ResourcePolicy        map[string]any    `json:"resourcePolicy"`
	RequestedTabs         int               `json:"requestedTabs"`
	AgentActionsPerMinute int               `json:"agentActionsPerMinute"`
	ExtensionIDs          []string          `json:"extensionIds"`
	RemoteDesktop         bool              `json:"remoteDesktop"`
	Web3Workload          bool              `json:"web3Workload"`
	MediaWorkload         bool              `json:"mediaWorkload"`
	RequestedMediaStreams int               `json:"requestedMediaStreams"`
	MediaBitrateKbps      int               `json:"mediaBitrateKbps"`
	VideoRecording        bool              `json:"videoRecording"`
	Metadata              map[string]string `json:"metadata"`
	IdempotencyKey        string            `json:"-"`
}

func New(options Options) (*Client, error) {
	parsed, err := url.Parse(options.BaseURL)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return nil, errors.New("base URL must be an absolute HTTP(S) URL")
	}
	if options.TenantID == "" {
		return nil, errors.New("tenant ID is required")
	}
	httpClient := options.HTTPClient
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 30 * time.Second}
	}
	return &Client{
		baseURL:  strings.TrimRight(options.BaseURL, "/") + "/api/v1",
		tenantID: options.TenantID, accessToken: options.AccessToken,
		actorID: options.ActorID, httpClient: httpClient,
	}, nil
}

func (c *Client) ListSessions(ctx context.Context, limit, offset int) (map[string]any, error) {
	return c.request(ctx, http.MethodGet, fmt.Sprintf("/sessions?limit=%d&offset=%d", limit, offset), nil, "")
}

func (c *Client) CreateSession(ctx context.Context, input CreateSessionInput) (map[string]any, error) {
	if input.ResourcePolicy == nil {
		input.ResourcePolicy = map[string]any{"mode": "AUTO"}
	}
	if input.RequestedTabs == 0 {
		input.RequestedTabs = 1
	}
	payload := map[string]any{
		"tenantId": c.tenantID, "profileId": input.ProfileID, "region": input.Region,
		"resourcePolicy": input.ResourcePolicy, "requestedTabs": input.RequestedTabs,
		"agentActionsPerMinute": input.AgentActionsPerMinute, "extensionIds": input.ExtensionIDs,
		"remoteDesktop": input.RemoteDesktop, "web3Workload": input.Web3Workload,
		"mediaWorkload": input.MediaWorkload, "requestedMediaStreams": input.RequestedMediaStreams,
		"mediaBitrateKbps": input.MediaBitrateKbps, "metadata": input.Metadata,
		"videoRecording": input.VideoRecording,
	}
	return c.request(ctx, http.MethodPost, "/sessions", payload, input.IdempotencyKey)
}

func (c *Client) StartSession(ctx context.Context, sessionID string) (map[string]any, error) {
	return c.request(ctx, http.MethodPost, "/sessions/"+sessionID+":start", nil, "")
}

func (c *Client) TerminateSession(ctx context.Context, sessionID string) (map[string]any, error) {
	return c.request(ctx, http.MethodPost, "/sessions/"+sessionID+":terminate", nil, "")
}

func (c *Client) request(ctx context.Context, method, path string, body any, idempotencyKey string) (map[string]any, error) {
	var encoded io.Reader
	if body != nil {
		payload, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		encoded = bytes.NewReader(payload)
	}
	request, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, encoded)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Content-Type", "application/json")
	if c.accessToken != "" {
		request.Header.Set("Authorization", "Bearer "+c.accessToken)
	} else {
		request.Header.Set("X-Tenant-Id", c.tenantID)
		if c.actorID != "" {
			request.Header.Set("X-Actor-Id", c.actorID)
		}
	}
	if idempotencyKey != "" {
		request.Header.Set("Idempotency-Key", idempotencyKey)
	}
	response, err := c.httpClient.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	var payload map[string]any
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil && !errors.Is(err, io.EOF) {
		return nil, err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		apiError := &APIError{Status: response.StatusCode}
		apiError.Code, _ = payload["code"].(string)
		apiError.Message, _ = payload["message"].(string)
		apiError.RequestID, _ = payload["requestId"].(string)
		return nil, apiError
	}
	return payload, nil
}
