---
page_title: "browsercloud_workspace_settings Data Source"
---

# browsercloud_workspace_settings

Reads the effective PostgreSQL Workspace override or declared system default used by future Session
creation.

```hcl
data "browsercloud_workspace_settings" "current" {}
```

Exports workspace/runtime/region/HumanTakeover defaults, `resource_policy_mode`,
`on_maximum_reached`, source, version, and update metadata.
