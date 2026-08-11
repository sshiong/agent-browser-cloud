package client

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

const maximumResponseBytes = 8 << 20

type Client struct {
	endpoint   *url.URL
	token      string
	local      bool
	tenantID   string
	actorID    string
	version    string
	httpClient *http.Client
}

type Config struct {
	Endpoint         *url.URL
	Token            string
	LocalDevelopment bool
	TenantID         string
	ActorID          string
	Version          string
}

func New(config Config) *Client {
	return &Client{
		endpoint: config.Endpoint,
		token:    config.Token,
		local:    config.LocalDevelopment,
		tenantID: config.TenantID,
		actorID:  config.ActorID,
		version:  config.Version,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
			CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}
}

type APIError struct {
	StatusCode int
	Code       string
	RequestID  string
}

func (err *APIError) Error() string {
	return fmt.Sprintf("BrowserCloud API returned status %d code %s request %s", err.StatusCode, valueOrUnknown(err.Code), valueOrUnknown(err.RequestID))
}

func IsNotFound(err error) bool {
	var apiError *APIError
	return errors.As(err, &apiError) && apiError.StatusCode == http.StatusNotFound
}

type WorkspaceGroupRequest struct {
	Name                    string  `json:"name"`
	Description             *string `json:"description"`
	Color                   string  `json:"color"`
	DefaultOnMaximumReached string  `json:"defaultOnMaximumReached"`
	DefaultAllowMigration   bool    `json:"defaultAllowMigration"`
	DefaultAllowHibernate   bool    `json:"defaultAllowHibernate"`
}

type WorkspaceTagRequest struct {
	Name        string  `json:"name"`
	Description *string `json:"description"`
	Color       string  `json:"color"`
}

type SessionReference struct {
	SessionID string `json:"sessionId"`
}

type WorkspaceGroup struct {
	GroupID                 string             `json:"groupId"`
	Name                    string             `json:"name"`
	Description             *string            `json:"description"`
	Color                   string             `json:"color"`
	DefaultOnMaximumReached string             `json:"defaultOnMaximumReached"`
	DefaultAllowMigration   bool               `json:"defaultAllowMigration"`
	DefaultAllowHibernate   bool               `json:"defaultAllowHibernate"`
	Sessions                []SessionReference `json:"sessions"`
	SessionCount            int64              `json:"sessionCount"`
	CreatedBy               string             `json:"createdBy"`
	CreatedAt               string             `json:"createdAt"`
	UpdatedAt               string             `json:"updatedAt"`
}

type WorkspaceTag struct {
	TagID        string             `json:"tagId"`
	Name         string             `json:"name"`
	Description  *string            `json:"description"`
	Color        string             `json:"color"`
	Sessions     []SessionReference `json:"sessions"`
	SessionCount int64              `json:"sessionCount"`
	CreatedBy    string             `json:"createdBy"`
	CreatedAt    string             `json:"createdAt"`
	UpdatedAt    string             `json:"updatedAt"`
}

type WorkspaceSettings struct {
	WorkspaceName               string  `json:"workspaceName"`
	DefaultRuntimeBuildID       string  `json:"defaultRuntimeBuildId"`
	DefaultRegion               string  `json:"defaultRegion"`
	DefaultHumanTakeoverEnabled bool    `json:"defaultHumanTakeoverEnabled"`
	ResourcePolicyMode          string  `json:"resourcePolicyMode"`
	OnMaximumReached            string  `json:"onMaximumReached"`
	Source                      string  `json:"source"`
	UpdatedBy                   *string `json:"updatedBy"`
	UpdatedAt                   *string `json:"updatedAt"`
	Version                     int64   `json:"version"`
}

func (client *Client) CreateGroup(ctx context.Context, request WorkspaceGroupRequest) (WorkspaceGroup, error) {
	var result WorkspaceGroup
	err := client.do(ctx, http.MethodPost, "/api/v1/groups", request, &result, idempotencyKey("group-create", request))
	return result, err
}

func (client *Client) UpdateGroup(ctx context.Context, groupID string, request WorkspaceGroupRequest) (WorkspaceGroup, error) {
	var result WorkspaceGroup
	err := client.do(ctx, http.MethodPut, "/api/v1/groups/"+url.PathEscape(groupID), request, &result, idempotencyKey("group-update:"+groupID, request))
	return result, err
}

func (client *Client) DeleteGroup(ctx context.Context, groupID string) error {
	return client.do(ctx, http.MethodDelete, "/api/v1/groups/"+url.PathEscape(groupID), nil, nil, idempotencyKey("group-delete", groupID))
}

func (client *Client) FindGroup(ctx context.Context, groupID string) (*WorkspaceGroup, error) {
	var result struct {
		Items []WorkspaceGroup `json:"items"`
	}
	if err := client.do(ctx, http.MethodGet, "/api/v1/groups", nil, &result, ""); err != nil {
		return nil, err
	}
	for index := range result.Items {
		if result.Items[index].GroupID == groupID {
			return &result.Items[index], nil
		}
	}
	return nil, &APIError{StatusCode: http.StatusNotFound, Code: "GROUP_NOT_FOUND"}
}

func (client *Client) ReconcileGroupSessions(ctx context.Context, groupID string, current []SessionReference, desired []string) error {
	return client.reconcileSessions(ctx, "/api/v1/groups/"+url.PathEscape(groupID)+"/sessions/", "group:"+groupID, current, desired)
}

func (client *Client) CreateTag(ctx context.Context, request WorkspaceTagRequest) (WorkspaceTag, error) {
	var result WorkspaceTag
	err := client.do(ctx, http.MethodPost, "/api/v1/tags", request, &result, idempotencyKey("tag-create", request))
	return result, err
}

func (client *Client) UpdateTag(ctx context.Context, tagID string, request WorkspaceTagRequest) (WorkspaceTag, error) {
	var result WorkspaceTag
	err := client.do(ctx, http.MethodPut, "/api/v1/tags/"+url.PathEscape(tagID), request, &result, idempotencyKey("tag-update:"+tagID, request))
	return result, err
}

func (client *Client) DeleteTag(ctx context.Context, tagID string) error {
	return client.do(ctx, http.MethodDelete, "/api/v1/tags/"+url.PathEscape(tagID), nil, nil, idempotencyKey("tag-delete", tagID))
}

func (client *Client) FindTag(ctx context.Context, tagID string) (*WorkspaceTag, error) {
	var result struct {
		Items []WorkspaceTag `json:"items"`
	}
	if err := client.do(ctx, http.MethodGet, "/api/v1/tags", nil, &result, ""); err != nil {
		return nil, err
	}
	for index := range result.Items {
		if result.Items[index].TagID == tagID {
			return &result.Items[index], nil
		}
	}
	return nil, &APIError{StatusCode: http.StatusNotFound, Code: "TAG_NOT_FOUND"}
}

func (client *Client) ReconcileTagSessions(ctx context.Context, tagID string, current []SessionReference, desired []string) error {
	return client.reconcileSessions(ctx, "/api/v1/tags/"+url.PathEscape(tagID)+"/sessions/", "tag:"+tagID, current, desired)
}

func (client *Client) GetWorkspaceSettings(ctx context.Context) (WorkspaceSettings, error) {
	var result WorkspaceSettings
	err := client.do(ctx, http.MethodGet, "/api/v1/workspace-settings", nil, &result, "")
	return result, err
}

func (client *Client) reconcileSessions(ctx context.Context, prefix, scope string, current []SessionReference, desired []string) error {
	currentSet := make(map[string]bool, len(current))
	for _, session := range current {
		currentSet[session.SessionID] = true
	}
	desiredSet := make(map[string]bool, len(desired))
	for _, sessionID := range desired {
		desiredSet[sessionID] = true
	}

	removals := make([]string, 0)
	additions := make([]string, 0)
	for sessionID := range currentSet {
		if !desiredSet[sessionID] {
			removals = append(removals, sessionID)
		}
	}
	for sessionID := range desiredSet {
		if !currentSet[sessionID] {
			additions = append(additions, sessionID)
		}
	}
	sort.Strings(removals)
	sort.Strings(additions)
	for _, sessionID := range removals {
		if err := client.do(ctx, http.MethodDelete, prefix+url.PathEscape(sessionID), nil, nil, idempotencyKey(scope+":unassign", sessionID)); err != nil {
			return err
		}
	}
	for _, sessionID := range additions {
		if err := client.do(ctx, http.MethodPut, prefix+url.PathEscape(sessionID), nil, nil, idempotencyKey(scope+":assign", sessionID)); err != nil {
			return err
		}
	}
	return nil
}

func (client *Client) do(ctx context.Context, method, path string, body, result any, key string) error {
	requestURL := *client.endpoint
	requestURL.Path = strings.TrimRight(requestURL.Path, "/") + path
	requestURL.RawQuery = ""
	requestURL.Fragment = ""

	var encoded io.Reader
	if body != nil {
		payload, err := json.Marshal(body)
		if err != nil {
			return fmt.Errorf("encode BrowserCloud request: %w", err)
		}
		encoded = bytes.NewReader(payload)
	}
	request, err := http.NewRequestWithContext(ctx, method, requestURL.String(), encoded)
	if err != nil {
		return fmt.Errorf("create BrowserCloud request: %w", err)
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "terraform-provider-browsercloud/"+client.version)
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if client.token != "" {
		request.Header.Set("Authorization", "Bearer "+client.token)
	}
	if client.local {
		request.Header.Set("X-Tenant-Id", client.tenantID)
		request.Header.Set("X-Actor-Id", client.actorID)
		request.Header.Set("X-Roles", "PLATFORM_ADMIN")
	}
	if key != "" {
		request.Header.Set("Idempotency-Key", key)
	}

	response, err := client.httpClient.Do(request)
	if err != nil {
		return fmt.Errorf("call BrowserCloud API: %w", err)
	}
	defer response.Body.Close()
	limited := io.LimitReader(response.Body, maximumResponseBytes+1)
	payload, err := io.ReadAll(limited)
	if err != nil {
		return fmt.Errorf("read BrowserCloud response: %w", err)
	}
	if len(payload) > maximumResponseBytes {
		return errors.New("BrowserCloud response exceeded 8 MiB safety limit")
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return decodeAPIError(response, payload)
	}
	if result != nil && len(payload) > 0 {
		if err := json.Unmarshal(payload, result); err != nil {
			return fmt.Errorf("decode BrowserCloud response: %w", err)
		}
	}
	return nil
}

func decodeAPIError(response *http.Response, payload []byte) error {
	var envelope struct {
		Error struct {
			Code      string `json:"code"`
			RequestID string `json:"requestId"`
		} `json:"error"`
		Code      string `json:"code"`
		RequestID string `json:"requestId"`
	}
	_ = json.Unmarshal(payload, &envelope)
	code := envelope.Error.Code
	if code == "" {
		code = envelope.Code
	}
	requestID := envelope.Error.RequestID
	if requestID == "" {
		requestID = envelope.RequestID
	}
	if requestID == "" {
		requestID = response.Header.Get("X-Request-Id")
	}
	return &APIError{StatusCode: response.StatusCode, Code: code, RequestID: requestID}
}

func idempotencyKey(scope string, value any) string {
	payload, _ := json.Marshal(value)
	digest := sha256.Sum256(append([]byte(scope+":"), payload...))
	return "tf-" + hex.EncodeToString(digest[:])[:48]
}

func valueOrUnknown(value string) string {
	if value == "" {
		return "UNKNOWN"
	}
	return value
}
