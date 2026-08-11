package provider

import (
	"context"

	"github.com/hashicorp/terraform-plugin-framework/datasource"
	"github.com/hashicorp/terraform-plugin-framework/datasource/schema"
	"github.com/hashicorp/terraform-plugin-framework/types"
	"github.com/sshiong/terraform-provider-browsercloud/internal/client"
)

type workspaceSettingsDataSource struct {
	client *client.Client
}

type workspaceSettingsModel struct {
	ID                          types.String `tfsdk:"id"`
	WorkspaceName               types.String `tfsdk:"workspace_name"`
	DefaultRuntimeBuildID       types.String `tfsdk:"default_runtime_build_id"`
	DefaultRegion               types.String `tfsdk:"default_region"`
	DefaultHumanTakeoverEnabled types.Bool   `tfsdk:"default_human_takeover_enabled"`
	ResourcePolicyMode          types.String `tfsdk:"resource_policy_mode"`
	OnMaximumReached            types.String `tfsdk:"on_maximum_reached"`
	Source                      types.String `tfsdk:"source"`
	UpdatedBy                   types.String `tfsdk:"updated_by"`
	UpdatedAt                   types.String `tfsdk:"updated_at"`
	Version                     types.Int64  `tfsdk:"version"`
}

func newWorkspaceSettingsDataSource() datasource.DataSource { return &workspaceSettingsDataSource{} }

func (settings *workspaceSettingsDataSource) Metadata(_ context.Context, request datasource.MetadataRequest, response *datasource.MetadataResponse) {
	response.TypeName = request.ProviderTypeName + "_workspace_settings"
}

func (settings *workspaceSettingsDataSource) Schema(_ context.Context, _ datasource.SchemaRequest, response *datasource.SchemaResponse) {
	response.Schema = schema.Schema{
		Description: "Read effective PostgreSQL-backed Workspace defaults used for future Sessions.",
		Attributes: map[string]schema.Attribute{
			"id":                             schema.StringAttribute{Computed: true},
			"workspace_name":                 schema.StringAttribute{Computed: true},
			"default_runtime_build_id":       schema.StringAttribute{Computed: true},
			"default_region":                 schema.StringAttribute{Computed: true},
			"default_human_takeover_enabled": schema.BoolAttribute{Computed: true},
			"resource_policy_mode":           schema.StringAttribute{Computed: true},
			"on_maximum_reached":             schema.StringAttribute{Computed: true},
			"source":                         schema.StringAttribute{Computed: true},
			"updated_by":                     schema.StringAttribute{Computed: true},
			"updated_at":                     schema.StringAttribute{Computed: true},
			"version":                        schema.Int64Attribute{Computed: true},
		},
	}
}

func (settings *workspaceSettingsDataSource) Configure(_ context.Context, request datasource.ConfigureRequest, response *datasource.ConfigureResponse) {
	if request.ProviderData == nil {
		return
	}
	configured, ok := request.ProviderData.(*client.Client)
	if !ok {
		response.Diagnostics.AddError("Unexpected provider configuration", unexpectedProviderData(request.ProviderData))
		return
	}
	settings.client = configured
}

func (settings *workspaceSettingsDataSource) Read(ctx context.Context, _ datasource.ReadRequest, response *datasource.ReadResponse) {
	current, err := settings.client.GetWorkspaceSettings(ctx)
	if err != nil {
		response.Diagnostics.AddError("Unable to read BrowserCloud Workspace Settings", err.Error())
		return
	}
	state := workspaceSettingsModel{
		ID: types.StringValue("workspace-settings"), WorkspaceName: types.StringValue(current.WorkspaceName),
		DefaultRuntimeBuildID: types.StringValue(current.DefaultRuntimeBuildID), DefaultRegion: types.StringValue(current.DefaultRegion),
		DefaultHumanTakeoverEnabled: types.BoolValue(current.DefaultHumanTakeoverEnabled),
		ResourcePolicyMode:          types.StringValue(current.ResourcePolicyMode), OnMaximumReached: types.StringValue(current.OnMaximumReached),
		Source: types.StringValue(current.Source), UpdatedBy: nullableString(current.UpdatedBy), UpdatedAt: nullableString(current.UpdatedAt), Version: types.Int64Value(current.Version),
	}
	response.Diagnostics.Append(response.State.Set(ctx, &state)...)
}
