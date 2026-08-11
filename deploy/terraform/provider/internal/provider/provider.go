package provider

import (
	"context"
	"fmt"
	"net"
	"net/url"
	"os"
	"strings"

	"github.com/hashicorp/terraform-plugin-framework/datasource"
	"github.com/hashicorp/terraform-plugin-framework/diag"
	"github.com/hashicorp/terraform-plugin-framework/provider"
	"github.com/hashicorp/terraform-plugin-framework/provider/schema"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/types"
	"github.com/sshiong/terraform-provider-browsercloud/internal/client"
)

type browserCloudProvider struct {
	version string
}

type providerModel struct {
	Endpoint         types.String `tfsdk:"endpoint"`
	Token            types.String `tfsdk:"token"`
	LocalDevelopment types.Bool   `tfsdk:"local_development"`
	TenantID         types.String `tfsdk:"tenant_id"`
	ActorID          types.String `tfsdk:"actor_id"`
}

func New(version string) func() provider.Provider {
	return func() provider.Provider { return &browserCloudProvider{version: version} }
}

func (provider *browserCloudProvider) Metadata(_ context.Context, _ provider.MetadataRequest, response *provider.MetadataResponse) {
	response.TypeName = "browsercloud"
	response.Version = provider.version
}

func (provider *browserCloudProvider) Schema(_ context.Context, _ provider.SchemaRequest, response *provider.SchemaResponse) {
	response.Schema = schema.Schema{
		Description: "Manage Agent Browser Cloud workspace resources through the authoritative Control Plane API.",
		Attributes: map[string]schema.Attribute{
			"endpoint":          schema.StringAttribute{Optional: true, Description: "Control Plane origin. May also be set with BROWSERCLOUD_ENDPOINT."},
			"token":             schema.StringAttribute{Optional: true, Sensitive: true, Description: "OIDC bearer token. May also be set with BROWSERCLOUD_TOKEN."},
			"local_development": schema.BoolAttribute{Optional: true, Description: "Allow loopback HTTP and local identity headers. Never enable in production."},
			"tenant_id":         schema.StringAttribute{Optional: true, Description: "Local-development tenant. Production tenant identity comes from the bearer token."},
			"actor_id":          schema.StringAttribute{Optional: true, Description: "Local-development actor. Production actor identity comes from the bearer token."},
		},
	}
}

func (provider *browserCloudProvider) Configure(ctx context.Context, request provider.ConfigureRequest, response *provider.ConfigureResponse) {
	var model providerModel
	response.Diagnostics.Append(request.Config.Get(ctx, &model)...)
	if response.Diagnostics.HasError() {
		return
	}

	endpoint := configured(model.Endpoint, "BROWSERCLOUD_ENDPOINT")
	token := configured(model.Token, "BROWSERCLOUD_TOKEN")
	tenantID := configured(model.TenantID, "BROWSERCLOUD_TENANT_ID")
	actorID := configured(model.ActorID, "BROWSERCLOUD_ACTOR_ID")
	local := !model.LocalDevelopment.IsNull() && !model.LocalDevelopment.IsUnknown() && model.LocalDevelopment.ValueBool()
	parsed := validateConfiguration(endpoint, token, tenantID, actorID, local, &response.Diagnostics)
	if response.Diagnostics.HasError() {
		return
	}

	configuredClient := client.New(client.Config{
		Endpoint: parsed, Token: token, LocalDevelopment: local,
		TenantID: tenantID, ActorID: actorID, Version: provider.version,
	})
	response.DataSourceData = configuredClient
	response.ResourceData = configuredClient
}

func (provider *browserCloudProvider) Resources(_ context.Context) []func() resource.Resource {
	return []func() resource.Resource{newGroupResource, newTagResource}
}

func (provider *browserCloudProvider) DataSources(_ context.Context) []func() datasource.DataSource {
	return []func() datasource.DataSource{newWorkspaceSettingsDataSource}
}

func configured(value types.String, environment string) string {
	if !value.IsNull() && !value.IsUnknown() && strings.TrimSpace(value.ValueString()) != "" {
		return strings.TrimSpace(value.ValueString())
	}
	return strings.TrimSpace(os.Getenv(environment))
}

func validateConfiguration(endpoint, token, tenantID, actorID string, local bool, diagnostics *diag.Diagnostics) *url.URL {
	if endpoint == "" {
		diagnostics.AddError("Missing BrowserCloud endpoint", "Set provider endpoint or BROWSERCLOUD_ENDPOINT.")
		return nil
	}
	parsed, err := url.Parse(endpoint)
	if err != nil || parsed.Host == "" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		diagnostics.AddError("Invalid BrowserCloud endpoint", "Endpoint must be an absolute origin without user-info, query, or fragment.")
		return nil
	}
	if local {
		if parsed.Scheme != "http" && parsed.Scheme != "https" {
			diagnostics.AddError("Invalid local endpoint", "Local-development endpoint must use HTTP or HTTPS.")
			return nil
		}
		if !isLoopback(parsed.Hostname()) {
			diagnostics.AddError("Unsafe local-development endpoint", "local_development may only target localhost or a loopback IP.")
		}
		if tenantID == "" || actorID == "" {
			diagnostics.AddError("Missing local identity", "local_development requires tenant_id and actor_id.")
		}
	} else {
		if parsed.Scheme != "https" {
			diagnostics.AddError("Insecure production endpoint", "Production BrowserCloud endpoints must use HTTPS.")
		}
		if token == "" {
			diagnostics.AddError("Missing bearer token", "Production mode requires token or BROWSERCLOUD_TOKEN.")
		}
	}
	if diagnostics.HasError() {
		return nil
	}
	parsed.Path = strings.TrimRight(parsed.Path, "/")
	return parsed
}

func isLoopback(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	address := net.ParseIP(host)
	return address != nil && address.IsLoopback()
}

func unexpectedProviderData(value any) string {
	return fmt.Sprintf("Expected configured *client.Client, got %T. This is a provider defect.", value)
}
