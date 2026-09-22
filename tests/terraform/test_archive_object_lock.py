import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class ArchiveObjectLockModuleTest(unittest.TestCase):
    def test_archive_bucket_is_versioned_and_compliance_locked(self):
        main = (ROOT / "deploy/terraform/modules/agent-browser-cloud/main.tf").read_text()
        variables = (
            ROOT / "deploy/terraform/modules/agent-browser-cloud/variables.tf"
        ).read_text()

        self.assertRegex(
            main,
            r'resource "aws_s3_bucket" "archive" \{[\s\S]*?object_lock_enabled\s*=\s*true',
        )
        self.assertRegex(
            main,
            r'resource "aws_s3_bucket_versioning" "archive" \{[\s\S]*?status\s*=\s*"Enabled"',
        )
        self.assertRegex(
            main,
            r'resource "aws_s3_bucket_object_lock_configuration" "archive" \{[\s\S]*?default_retention[\s\S]*?mode\s*=\s*var\.archive_object_lock_mode[\s\S]*?days\s*=\s*var\.archive_object_lock_retention_days',
        )
        self.assertIn(
            "depends_on = [aws_s3_bucket_versioning.archive]", main
        )
        self.assertRegex(
            variables,
            r'variable "archive_object_lock_mode" \{[\s\S]*?default\s*=\s*"COMPLIANCE"',
        )
        self.assertRegex(
            variables,
            r'variable "archive_object_lock_retention_days" \{[\s\S]*?default\s*=\s*30',
        )

    def test_runtime_retention_floor_includes_upload_skew_day(self):
        outputs = (
            ROOT / "deploy/terraform/modules/agent-browser-cloud/outputs.tf"
        ).read_text()
        workload = (ROOT / "deploy/kubernetes/base/workloads.yaml").read_text()

        self.assertIn(
            "value       = var.archive_object_lock_retention_days + 1", outputs
        )
        self.assertRegex(
            workload,
            r"name: RECORDING_OBJECT_LOCK_POLICY_MINIMUM_RETENTION_DAYS\s+value: \"31\"",
        )

    def test_documentation_forbids_governance_bypass_in_browser_node_role(self):
        readme = (ROOT / "deploy/terraform/README.md").read_text()
        self.assertIn("s3:BypassGovernanceRetention", readme)
        self.assertIn("s3:PutObjectRetention", readme)
        self.assertIn("s3:ListBucketVersions", readme)
        self.assertIn("s3:DeleteObjectVersion", readme)
        self.assertIn("COMPLIANCE", readme)

    def test_integration_proves_versioned_physical_deletion(self):
        integration = (ROOT / "tests/integration/smoke.sh").read_text()
        self.assertIn("mc mb --with-lock integration/${minio_bucket}", integration)
        self.assertIn("mc ls --versions --recursive", integration)
        self.assertIn(
            "recording_retention_versioned_physical_deletion=true", integration
        )


if __name__ == "__main__":
    unittest.main()
