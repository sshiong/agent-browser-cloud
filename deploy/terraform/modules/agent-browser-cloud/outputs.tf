output "archive_bucket" {
  value = aws_s3_bucket.archive.bucket
}

output "data_kms_key_arn" {
  value = aws_kms_key.data.arn
}

output "postgres_writer_endpoint" {
  value = aws_rds_cluster.postgres.endpoint
}

output "postgres_reader_endpoint" {
  value = aws_rds_cluster.postgres.reader_endpoint
}

output "postgres_master_secret_arn" {
  value     = aws_rds_cluster.postgres.master_user_secret[0].secret_arn
  sensitive = true
}

output "redis_configuration_endpoint" {
  value = aws_elasticache_replication_group.redis.configuration_endpoint_address
}

output "redis_auth_secret_arn" {
  value     = aws_secretsmanager_secret.redis.arn
  sensitive = true
}

output "control_plane_node_group" {
  value = aws_eks_node_group.control_plane.node_group_name
}

output "browser_node_group" {
  value = aws_eks_node_group.browser_nodes.node_group_name
}
