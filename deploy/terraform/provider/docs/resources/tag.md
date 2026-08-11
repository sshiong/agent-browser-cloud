---
page_title: "browsercloud_tag Resource"
---

# browsercloud_tag

Creates an audited reusable Workspace Tag. `session_ids` converges exact Session assignments.

```hcl
resource "browsercloud_tag" "critical" {
  name        = "critical"
  color       = "#EF4444"
  session_ids = ["ses_1234567890abcdef"]
}
```

Import with `terraform import browsercloud_tag.critical tag_1234567890abcdef`.
