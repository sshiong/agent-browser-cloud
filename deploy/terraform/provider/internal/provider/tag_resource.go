package provider

import (
	"context"
	"fmt"

	"github.com/hashicorp/terraform-plugin-framework/attr"
	"github.com/hashicorp/terraform-plugin-framework/diag"
	"github.com/hashicorp/terraform-plugin-framework/path"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/setdefault"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/stringdefault"
	"github.com/hashicorp/terraform-plugin-framework/types"
	"github.com/sshiong/terraform-provider-browsercloud/internal/client"
)

type tagResource struct {
	client *client.Client
}

type tagResourceModel struct {
	ID           types.String `tfsdk:"id"`
	Name         types.String `tfsdk:"name"`
	Description  types.String `tfsdk:"description"`
	Color        types.String `tfsdk:"color"`
	SessionIDs   types.Set    `tfsdk:"session_ids"`
	SessionCount types.Int64  `tfsdk:"session_count"`
	CreatedBy    types.String `tfsdk:"created_by"`
	CreatedAt    types.String `tfsdk:"created_at"`
	UpdatedAt    types.String `tfsdk:"updated_at"`
}

func newTagResource() resource.Resource { return &tagResource{} }

func (tag *tagResource) Metadata(_ context.Context, request resource.MetadataRequest, response *resource.MetadataResponse) {
	response.TypeName = request.ProviderTypeName + "_tag"
}

func (tag *tagResource) Schema(_ context.Context, _ resource.SchemaRequest, response *resource.SchemaResponse) {
	response.Schema = schema.Schema{
		Description: "An audited reusable Workspace Tag with authoritative Session assignments.",
		Attributes: map[string]schema.Attribute{
			"id":            schema.StringAttribute{Computed: true, Description: "Workspace Tag ID."},
			"name":          schema.StringAttribute{Required: true, Description: "Unique tag name (1-32 characters)."},
			"description":   schema.StringAttribute{Optional: true, Description: "Optional tag description."},
			"color":         schema.StringAttribute{Optional: true, Computed: true, Default: stringdefault.StaticString("#26D9C7"), Description: "Six-digit hexadecimal display color."},
			"session_ids":   schema.SetAttribute{Optional: true, Computed: true, ElementType: types.StringType, Default: setdefault.StaticValue(types.SetValueMust(types.StringType, []attr.Value{})), Description: "Sessions assigned to this tag. Provider updates converge membership exactly."},
			"session_count": schema.Int64Attribute{Computed: true},
			"created_by":    schema.StringAttribute{Computed: true},
			"created_at":    schema.StringAttribute{Computed: true},
			"updated_at":    schema.StringAttribute{Computed: true},
		},
	}
}

func (tag *tagResource) Configure(_ context.Context, request resource.ConfigureRequest, response *resource.ConfigureResponse) {
	if request.ProviderData == nil {
		return
	}
	configured, ok := request.ProviderData.(*client.Client)
	if !ok {
		response.Diagnostics.AddError("Unexpected provider configuration", unexpectedProviderData(request.ProviderData))
		return
	}
	tag.client = configured
}

func (tag *tagResource) Create(ctx context.Context, request resource.CreateRequest, response *resource.CreateResponse) {
	var plan tagResourceModel
	response.Diagnostics.Append(request.Plan.Get(ctx, &plan)...)
	if response.Diagnostics.HasError() {
		return
	}
	desired, ok := stringSet(ctx, plan.SessionIDs, &response.Diagnostics)
	if !ok {
		return
	}
	created, err := tag.client.CreateTag(ctx, tagRequest(plan))
	if err != nil {
		response.Diagnostics.AddError("Unable to create BrowserCloud tag", err.Error())
		return
	}
	setTagModel(ctx, &plan, created, &response.Diagnostics)
	response.Diagnostics.Append(response.State.Set(ctx, &plan)...)
	if response.Diagnostics.HasError() {
		return
	}
	if err := tag.client.ReconcileTagSessions(ctx, created.TagID, created.Sessions, desired); err != nil {
		response.Diagnostics.AddError("Tag created but Session membership did not converge", err.Error())
		return
	}
	current, err := tag.client.FindTag(ctx, created.TagID)
	if err != nil {
		response.Diagnostics.AddError("Unable to refresh BrowserCloud tag", err.Error())
		return
	}
	setTagModel(ctx, &plan, *current, &response.Diagnostics)
	response.Diagnostics.Append(response.State.Set(ctx, &plan)...)
}

func (tag *tagResource) Read(ctx context.Context, request resource.ReadRequest, response *resource.ReadResponse) {
	var state tagResourceModel
	response.Diagnostics.Append(request.State.Get(ctx, &state)...)
	if response.Diagnostics.HasError() {
		return
	}
	current, err := tag.client.FindTag(ctx, state.ID.ValueString())
	if client.IsNotFound(err) {
		response.State.RemoveResource(ctx)
		return
	}
	if err != nil {
		response.Diagnostics.AddError("Unable to read BrowserCloud tag", err.Error())
		return
	}
	setTagModel(ctx, &state, *current, &response.Diagnostics)
	response.Diagnostics.Append(response.State.Set(ctx, &state)...)
}

func (tag *tagResource) Update(ctx context.Context, request resource.UpdateRequest, response *resource.UpdateResponse) {
	var plan tagResourceModel
	response.Diagnostics.Append(request.Plan.Get(ctx, &plan)...)
	if response.Diagnostics.HasError() {
		return
	}
	desired, ok := stringSet(ctx, plan.SessionIDs, &response.Diagnostics)
	if !ok {
		return
	}
	updated, err := tag.client.UpdateTag(ctx, plan.ID.ValueString(), tagRequest(plan))
	if err != nil {
		response.Diagnostics.AddError("Unable to update BrowserCloud tag", err.Error())
		return
	}
	if err := tag.client.ReconcileTagSessions(ctx, updated.TagID, updated.Sessions, desired); err != nil {
		response.Diagnostics.AddError("Tag updated but Session membership did not converge", err.Error())
		return
	}
	current, err := tag.client.FindTag(ctx, updated.TagID)
	if err != nil {
		response.Diagnostics.AddError("Unable to refresh BrowserCloud tag", err.Error())
		return
	}
	setTagModel(ctx, &plan, *current, &response.Diagnostics)
	response.Diagnostics.Append(response.State.Set(ctx, &plan)...)
}

func (tag *tagResource) Delete(ctx context.Context, request resource.DeleteRequest, response *resource.DeleteResponse) {
	var state tagResourceModel
	response.Diagnostics.Append(request.State.Get(ctx, &state)...)
	if response.Diagnostics.HasError() {
		return
	}
	if err := tag.client.DeleteTag(ctx, state.ID.ValueString()); err != nil && !client.IsNotFound(err) {
		response.Diagnostics.AddError("Unable to delete BrowserCloud tag", err.Error())
	}
}

func (tag *tagResource) ImportState(ctx context.Context, request resource.ImportStateRequest, response *resource.ImportStateResponse) {
	resource.ImportStatePassthroughID(ctx, path.Root("id"), request, response)
}

func tagRequest(model tagResourceModel) client.WorkspaceTagRequest {
	return client.WorkspaceTagRequest{Name: model.Name.ValueString(), Description: optionalString(model.Description), Color: model.Color.ValueString()}
}

func setTagModel(ctx context.Context, model *tagResourceModel, value client.WorkspaceTag, diagnostics *diag.Diagnostics) {
	model.ID = types.StringValue(value.TagID)
	model.Name = types.StringValue(value.Name)
	model.Description = nullableString(value.Description)
	model.Color = types.StringValue(value.Color)
	set, diags := types.SetValueFrom(ctx, types.StringType, sessionReferenceIDs(value.Sessions))
	if diags.HasError() {
		diagnostics.AddError("Unable to map tag membership", fmt.Sprintf("Terraform set conversion failed: %v", diags))
		return
	}
	model.SessionIDs = set
	model.SessionCount = types.Int64Value(value.SessionCount)
	model.CreatedBy = types.StringValue(value.CreatedBy)
	model.CreatedAt = types.StringValue(value.CreatedAt)
	model.UpdatedAt = types.StringValue(value.UpdatedAt)
}
