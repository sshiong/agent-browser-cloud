locals {
  tags = merge(var.tags, {
    "browsercloud.io/managed-by" = "terraform"
    "browsercloud.io/stack"      = var.name
  })
}

resource "aws_kms_key" "data" {
  description             = "${var.name} envelope encryption"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  multi_region            = true
  tags                    = local.tags
}

resource "aws_kms_alias" "data" {
  name          = "alias/${var.name}-data"
  target_key_id = aws_kms_key.data.key_id
}

resource "aws_s3_bucket" "archive" {
  bucket_prefix = "${var.name}-archive-"
  force_destroy = false
  tags          = local.tags
}

resource "aws_s3_bucket_versioning" "archive" {
  bucket = aws_s3_bucket.archive.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "archive" {
  bucket = aws_s3_bucket.archive.id
  rule {
    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.data.arn
      sse_algorithm     = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "archive" {
  bucket                  = aws_s3_bucket.archive.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "archive" {
  bucket = aws_s3_bucket.archive.id
  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"
    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
    noncurrent_version_expiration {
      noncurrent_days = 365
    }
  }
}

resource "aws_rds_cluster" "postgres" {
  cluster_identifier              = "${var.name}-postgres"
  engine                          = "aurora-postgresql"
  engine_mode                     = "provisioned"
  engine_version                  = "16.4"
  database_name                   = "browsercloud"
  master_username                 = "browsercloud_admin"
  manage_master_user_password     = true
  master_user_secret_kms_key_id   = aws_kms_key.data.arn
  db_subnet_group_name            = var.database_subnet_group_name
  vpc_security_group_ids          = var.database_security_group_ids
  storage_encrypted               = true
  kms_key_id                      = aws_kms_key.data.arn
  backup_retention_period         = 35
  preferred_backup_window         = "02:00-03:00"
  preferred_maintenance_window    = "sun:03:00-sun:04:00"
  deletion_protection             = true
  copy_tags_to_snapshot           = true
  enabled_cloudwatch_logs_exports = ["postgresql"]
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${var.name}-postgres-final"
  tags                            = local.tags
}

resource "aws_rds_cluster_instance" "postgres" {
  count                      = 2
  identifier                 = "${var.name}-postgres-${count.index}"
  cluster_identifier         = aws_rds_cluster.postgres.id
  instance_class             = var.database_instance_class
  engine                     = aws_rds_cluster.postgres.engine
  engine_version             = aws_rds_cluster.postgres.engine_version
  publicly_accessible        = false
  auto_minor_version_upgrade = true
  tags                       = local.tags
}

resource "random_password" "redis" {
  length           = 48
  special          = true
  override_special = "!&#$^<>-"
}

resource "aws_secretsmanager_secret" "redis" {
  name_prefix             = "${var.name}/redis-auth-"
  kms_key_id              = aws_kms_key.data.arn
  recovery_window_in_days = 30
  tags                    = local.tags
}

resource "aws_secretsmanager_secret_version" "redis" {
  secret_id     = aws_secretsmanager_secret.redis.id
  secret_string = jsonencode({ auth_token = random_password.redis.result })
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id       = "${var.name}-redis"
  description                = "${var.name} rebuildable cache"
  node_type                  = var.redis_node_type
  port                       = 6379
  parameter_group_name       = "default.redis7.cluster.on"
  subnet_group_name          = var.cache_subnet_group_name
  security_group_ids         = var.cache_security_group_ids
  automatic_failover_enabled = true
  multi_az_enabled           = true
  num_node_groups            = 2
  replicas_per_node_group    = 1
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = random_password.redis.result
  kms_key_id                 = aws_kms_key.data.arn
  auth_token_update_strategy = "ROTATE"
  snapshot_retention_limit   = 7
  apply_immediately          = false
  tags                       = local.tags
}

resource "aws_launch_template" "browser_node" {
  name_prefix            = "${var.name}-browser-node-"
  update_default_version = true

  user_data = base64encode(<<-EOT
    #!/usr/bin/env bash
    set -euo pipefail
    install -d -m 0750 -o 11001 -g 11000 /sys/fs/cgroup/browsercloud
    test -f /sys/fs/cgroup/cgroup.controllers
    EOT
  )

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
    instance_metadata_tags      = "disabled"
  }

  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      encrypted   = true
      kms_key_id  = aws_kms_key.data.arn
      volume_size = 100
      volume_type = "gp3"
    }
  }

  tag_specifications {
    resource_type = "instance"
    tags          = local.tags
  }
}

resource "aws_eks_node_group" "control_plane" {
  cluster_name    = var.cluster_name
  node_group_name = "${var.name}-control-plane"
  node_role_arn   = var.control_plane_node_role_arn
  subnet_ids      = var.private_subnet_ids
  instance_types  = var.control_plane_instance_types
  capacity_type   = "ON_DEMAND"

  labels = {
    "browsercloud.io/workload" = "control-plane"
  }

  scaling_config {
    desired_size = var.control_plane_desired_size
    min_size     = 3
    max_size     = max(3, var.control_plane_desired_size * 2)
  }

  update_config {
    max_unavailable = 1
  }

  tags = local.tags
}

resource "aws_eks_node_group" "browser_nodes" {
  cluster_name    = var.cluster_name
  node_group_name = "${var.name}-browser-nodes"
  node_role_arn   = var.browser_node_role_arn
  subnet_ids      = var.private_subnet_ids
  instance_types  = var.browser_node_instance_types
  capacity_type   = "ON_DEMAND"

  launch_template {
    id      = aws_launch_template.browser_node.id
    version = aws_launch_template.browser_node.latest_version
  }

  labels = {
    "browsercloud.io/workload" = "browser-node"
  }

  taint {
    key    = "browsercloud.io/workload"
    value  = "browser-node"
    effect = "NO_SCHEDULE"
  }

  scaling_config {
    desired_size = var.browser_node_desired_size
    min_size     = 3
    max_size     = max(3, var.browser_node_desired_size * 2)
  }

  update_config {
    max_unavailable = 1
  }

  tags = local.tags
}
