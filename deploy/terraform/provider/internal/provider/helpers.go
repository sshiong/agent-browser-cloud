package provider

import (
	"context"

	"github.com/hashicorp/terraform-plugin-framework/diag"
	"github.com/hashicorp/terraform-plugin-framework/types"
	"github.com/sshiong/terraform-provider-browsercloud/internal/client"
)

func optionalString(value types.String) *string {
	if value.IsNull() || value.IsUnknown() {
		return nil
	}
	result := value.ValueString()
	return &result
}

func nullableString(value *string) types.String {
	if value == nil {
		return types.StringNull()
	}
	return types.StringValue(*value)
}

func stringSet(ctx context.Context, value types.Set, diagnostics *diag.Diagnostics) ([]string, bool) {
	if value.IsNull() || value.IsUnknown() {
		return []string{}, true
	}
	var result []string
	diagnostics.Append(value.ElementsAs(ctx, &result, false)...)
	return result, !diagnostics.HasError()
}

func sessionReferenceIDs(values []client.SessionReference) []string {
	result := make([]string, 0, len(values))
	for _, value := range values {
		result = append(result, value.SessionID)
	}
	return result
}
