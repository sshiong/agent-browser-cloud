---
page_title: "browsercloud_group Resource"
---

# browsercloud_group

Creates an audited Workspace Group. `session_ids` is authoritative: updates assign missing Sessions
and unassign removed Sessions using deterministic idempotency keys. Deleting a group does not stop
its Sessions.

```hcl
resource "browsercloud_group" "operations" {
  name                       = "Operations"
  color                      = "#26D9C7"
  default_on_maximum_reached = "PAUSE_AGENT"
  default_allow_migration    = true
  default_allow_hibernate    = true
  session_ids                = ["ses_1234567890abcdef"]
}
```

Import with `terraform import browsercloud_group.operations grp_1234567890abcdef`.
