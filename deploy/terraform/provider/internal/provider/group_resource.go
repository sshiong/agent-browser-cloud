package provider

import (
	"context"
	"fmt"

	"github.com/hashicorp/terraform-plugin-framework/attr"
	"github.com/hashicorp/terraform-plugin-framework/diag"
	"github.com/hashicorp/terraform-plugin-framework/path"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/booldefault"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/setdefault"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/stringdefault"
	"github.com/hashicorp/terraform-plugin-framework/types"
	"github.com/sshiong/terraform-provider-browsercloud/internal/client"
)

type groupResource struct {
	client *client.Client
}

type groupResourceModel struct {
	ID                      types.String `tfsdk:"id"`
	Name                    types.String `tfsdk:"name"`
	Description             types.String `tfsdk:"description"`
	Color                   types.String `tfsdk:"color"`
	DefaultOnMaximumReached types.String `tfsdk:"default_on_maximum_reached"`
	DefaultAllowMigration   types.Bool   `tfsdk:"default_allow_migration"`
	DefaultAllowHibernate   types.Bool   `tfsdk:"default_allow_hibernate"`
	SessionIDs              types.Set    `tfsdk:"session_ids"`
	SessionCount            types.Int64  `tfsdk:"session_count"`
	CreatedBy               types.String `tfsdk:"created_by"`
	CreatedAt               types.String `tfsdk:"created_at"`
	UpdatedAt               types.String `tfsdk:"updated_at"`
}

func newGroupResource() resource.Resource { return &groupResource{} }

func (group *groupResource) Metadata(_ context.Context, request resource.MetadataRequest, response *resource.MetadataResponse) {
	response.TypeName = request.ProviderTypeName + "_group"
}

func (group *groupResource) Schema(_ context.Context, _ resource.SchemaRequest, response *resource.SchemaResponse) {
	response.Schema = schema.Schema{
		Description: "An audited Workspace Group with authoritative Session membership and inherited AUTO defaults.",
		Attributes: map[string]schema.Attribute{
			"id":                         schema.StringAttribute{Computed: true, Description: "Workspace Group ID."},
			"name":                       schema.StringAttribute{Required: true, Description: "Unique group name (1-96 characters)."},
			"description":                schema.StringAttribute{Optional: true, Description: "Optional group description."},
			"color":                      schema.StringAttribute{Optional: true, Computed: true, Default: stringdefault.StaticString("#26D9C7"), Description: "Six-digit hexadecimal display color."},
			"default_on_maximum_reached": schema.StringAttribute{Optional: true, Computed: true, Default: stringdefault.StaticString("PAUSE_AGENT"), Description: "Inherited AUTO maximum policy."},
			"default_allow_migration":    schema.BoolAttribute{Optional: true, Computed: true, Default: booldefault.StaticBool(true), Description: "Allow safe-point migration by default."},
			"default_allow_hibernate":    schema.BoolAttribute{Optional: true, Computed: true, Default: booldefault.StaticBool(true), Description: "Allow hibernation by default."},
			"session_ids":                schema.SetAttribute{Optional: true, Computed: true, ElementType: types.StringType, Default: setdefault.StaticValue(types.SetValueMust(types.StringType, []attr.Value{})), Description: "Sessions assigned to this group. Provider updates converge membership exactly."},
			"session_count":              schema.Int64Attribute{Computed: true},
			"created_by":                 schema.StringAttribute{Computed: true},
			"created_at":                 schema.StringAttribute{Computed: true},
			"updated_at":                 schema.StringAttribute{Computed: true},
		},
	}
}

func (group *groupResource) Configure(_ context.Context, request resource.ConfigureRequest, response *resource.ConfigureResponse) {
	if request.ProviderData == nil {
		return
	}
	configured, ok := request.ProviderData.(*client.Client)
	if !ok {
		response.Diagnostics.AddError("Unexpected provider configuration", unexpectedProviderData(request.ProviderData))
		return
	}
	group.client = configured
}

func (group *groupResource) Create(ctx context.Context, request resource.CreateRequest, response *resource.CreateResponse) {
	var plan groupResourceModel
	response.Diagnostics.Append(request.Plan.Get(ctx, &plan)...)
	if response.Diagnostics.HasError() {
		return
	}
	desired, ok := stringSet(ctx, plan.SessionIDs, &response.Diagnostics)
	if !ok {
		return
	}
	created, err := group.client.CreateGroup(ctx, groupRequest(plan))
	if err != nil {
		response.Diagnostics.AddError("Unable to create BrowserCloud group", err.Error())
		return
	}
	setGroupModel(ctx, &plan, created, &response.Diagnostics)
	response.Diagnostics.Append(response.State.Set(ctx, &plan)...)
	if response.Diagnostics.HasError() {
		return
	}
	if err := group.client.ReconcileGroupSessions(ctx, created.GroupID, created.Sessions, desired); err != nil {
		response.Diagnostics.AddError("Group created but Session membership did not converge", err.Error())
		return
	}
	current, err := group.client.FindGroup(ctx, created.GroupID)
	if err != nil {
		response.Diagnostics.AddError("Unable to refresh BrowserCloud group", err.Error())
		return
	}
	setGroupModel(ctx, &plan, *current, &response.Diagnostics)
	response.Diagnostics.Append(response.State.Set(ctx, &plan)...)
}

func (group *groupResource) Read(ctx context.Context, request resource.ReadRequest, response *resource.ReadResponse) {
	var state groupResourceModel
	response.Diagnostics.Append(request.State.Get(ctx, &state)...)
	if response.Diagnostics.HasError() {
		return
	}
	current, err := group.client.FindGroup(ctx, state.ID.ValueString())
	if client.IsNotFound(err) {
		response.State.RemoveResource(ctx)
		return
	}
	if err != nil {
		response.Diagnostics.AddError("Unable to read BrowserCloud group", err.Error())
		return
	}
	setGroupModel(ctx, &state, *current, &response.Diagnostics)
	response.Diagnostics.Append(response.State.Set(ctx, &state)...)
}

func (group *groupResource) Update(ctx context.Context, request resource.UpdateRequest, response *resource.UpdateResponse) {
	var plan groupResourceModel
	response.Diagnostics.Append(request.Plan.Get(ctx, &plan)...)
	if response.Diagnostics.HasError() {
		return
	}
	desired, ok := stringSet(ctx, plan.SessionIDs, &response.Diagnostics)
	if !ok {
		return
	}
	updated, err := group.client.UpdateGroup(ctx, plan.ID.ValueString(), groupRequest(plan))
	if err != nil {
		response.Diagnostics.AddError("Unable to update BrowserCloud group", err.Error())
		return
	}
	if err := group.client.ReconcileGroupSessions(ctx, updated.GroupID, updated.Sessions, desired); err != nil {
		response.Diagnostics.AddError("Group updated but Session membership did not converge", err.Error())
		return
	}
	current, err := group.client.FindGroup(ctx, updated.GroupID)
	if err != nil {
		response.Diagnostics.AddError("Unable to refresh BrowserCloud group", err.Error())
		return
	}
	setGroupModel(ctx, &plan, *current, &response.Diagnostics)
	response.Diagnostics.Append(response.State.Set(ctx, &plan)...)
}

func (group *groupResource) Delete(ctx context.Context, request resource.DeleteRequest, response *resource.DeleteResponse) {
	var state groupResourceModel
	response.Diagnostics.Append(request.State.Get(ctx, &state)...)
	if response.Diagnostics.HasError() {
		return
	}
	if err := group.client.DeleteGroup(ctx, state.ID.ValueString()); err != nil && !client.IsNotFound(err) {
		response.Diagnostics.AddError("Unable to delete BrowserCloud group", err.Error())
	}
}

func (group *groupResource) ImportState(ctx context.Context, request resource.ImportStateRequest, response *resource.ImportStateResponse) {
	resource.ImportStatePassthroughID(ctx, path.Root("id"), request, response)
}

func groupRequest(model groupResourceModel) client.WorkspaceGroupRequest {
	return client.WorkspaceGroupRequest{
		Name: model.Name.ValueString(), Description: optionalString(model.Description), Color: model.Color.ValueString(),
		DefaultOnMaximumReached: model.DefaultOnMaximumReached.ValueString(),
		DefaultAllowMigration:   model.DefaultAllowMigration.ValueBool(), DefaultAllowHibernate: model.DefaultAllowHibernate.ValueBool(),
	}
}

func setGroupModel(ctx context.Context, model *groupResourceModel, value client.WorkspaceGroup, diagnostics *diag.Diagnostics) {
	model.ID = types.StringValue(value.GroupID)
	model.Name = types.StringValue(value.Name)
	model.Description = nullableString(value.Description)
	model.Color = types.StringValue(value.Color)
	model.DefaultOnMaximumReached = types.StringValue(value.DefaultOnMaximumReached)
	model.DefaultAllowMigration = types.BoolValue(value.DefaultAllowMigration)
	model.DefaultAllowHibernate = types.BoolValue(value.DefaultAllowHibernate)
	sessions := sessionReferenceIDs(value.Sessions)
	set, diags := types.SetValueFrom(ctx, types.StringType, sessions)
	if diags.HasError() {
		diagnostics.AddError("Unable to map group membership", fmt.Sprintf("Terraform set conversion failed: %v", diags))
		return
	}
	model.SessionIDs = set
	model.SessionCount = types.Int64Value(value.SessionCount)
	model.CreatedBy = types.StringValue(value.CreatedBy)
	model.CreatedAt = types.StringValue(value.CreatedAt)
	model.UpdatedAt = types.StringValue(value.UpdatedAt)
}
