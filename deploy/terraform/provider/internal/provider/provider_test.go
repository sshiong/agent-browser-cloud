package provider

import (
	"context"
	"testing"

	"github.com/hashicorp/terraform-plugin-framework/diag"
	"github.com/hashicorp/terraform-plugin-framework/providerserver"
	"github.com/hashicorp/terraform-plugin-go/tfprotov6"
)

func TestProtocolSchemaIsValidAndPublishesResources(t *testing.T) {
	t.Parallel()
	server := providerserver.NewProtocol6(New("test")())()
	response, err := server.GetProviderSchema(context.Background(), &tfprotov6.GetProviderSchemaRequest{})
	if err != nil {
		t.Fatal(err)
	}
	for _, diagnostic := range response.Diagnostics {
		if diagnostic.Severity == tfprotov6.DiagnosticSeverityError {
			t.Fatalf("invalid provider schema: %s: %s", diagnostic.Summary, diagnostic.Detail)
		}
	}
	for _, name := range []string{"browsercloud_group", "browsercloud_tag"} {
		if _, ok := response.ResourceSchemas[name]; !ok {
			t.Fatalf("missing resource schema %s", name)
		}
	}
	if _, ok := response.DataSourceSchemas["browsercloud_workspace_settings"]; !ok {
		t.Fatal("missing workspace settings data source")
	}
}

func TestConfigurationFailsClosedOutsideLoopback(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name     string
		endpoint string
		token    string
		tenant   string
		actor    string
		local    bool
	}{
		{name: "production-http", endpoint: "http://api.example.test", token: "token"},
		{name: "production-no-token", endpoint: "https://api.example.test"},
		{name: "local-remote-host", endpoint: "http://api.example.test", tenant: "tenant", actor: "actor", local: true},
		{name: "local-no-identity", endpoint: "http://127.0.0.1:8080", local: true},
		{name: "endpoint-user-info", endpoint: "https://user@example.test", token: "token"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			var diagnostics diag.Diagnostics
			if parsed := validateConfiguration(test.endpoint, test.token, test.tenant, test.actor, test.local, &diagnostics); parsed != nil || !diagnostics.HasError() {
				t.Fatalf("unsafe configuration was accepted: %#v", parsed)
			}
		})
	}
}

func TestConfigurationAcceptsHTTPSOrExplicitLoopbackDevelopment(t *testing.T) {
	t.Parallel()
	tests := []struct {
		endpoint string
		token    string
		tenant   string
		actor    string
		local    bool
	}{
		{endpoint: "https://api.example.test", token: "token"},
		{endpoint: "http://localhost:8080", tenant: "tenant", actor: "actor", local: true},
		{endpoint: "http://[::1]:8080", tenant: "tenant", actor: "actor", local: true},
	}
	for _, test := range tests {
		var diagnostics diag.Diagnostics
		if parsed := validateConfiguration(test.endpoint, test.token, test.tenant, test.actor, test.local, &diagnostics); parsed == nil || diagnostics.HasError() {
			t.Fatalf("valid configuration was rejected: %v", diagnostics)
		}
	}
}
