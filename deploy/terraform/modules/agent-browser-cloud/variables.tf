variable "name" {
  description = "Deployment name prefix."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,31}$", var.name))
    error_message = "name must be a lowercase DNS label between 3 and 32 characters."
  }
}

variable "cluster_name" {
  description = "Existing EKS cluster that receives the isolated node groups."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnets spanning at least two availability zones."
  type        = list(string)

  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "At least two private subnets are required."
  }
}

variable "database_subnet_group_name" {
  description = "Existing DB subnet group in private subnets."
  type        = string
}

variable "cache_subnet_group_name" {
  description = "Existing ElastiCache subnet group in private subnets."
  type        = string
}

variable "database_security_group_ids" {
  description = "Security groups allowing PostgreSQL only from the control plane."
  type        = list(string)
}

variable "cache_security_group_ids" {
  description = "Security groups allowing Redis only from the control plane."
  type        = list(string)
}

variable "control_plane_node_role_arn" {
  description = "Least-privilege IAM role for the control-plane EKS node group."
  type        = string
}

variable "browser_node_role_arn" {
  description = "Least-privilege IAM role for the Browser Node EKS node group."
  type        = string
}

variable "control_plane_instance_types" {
  type    = list(string)
  default = ["m7g.large"]
}

variable "browser_node_instance_types" {
  type    = list(string)
  default = ["m7g.2xlarge"]
}

variable "control_plane_desired_size" {
  type    = number
  default = 3
}

variable "browser_node_desired_size" {
  type    = number
  default = 3
}

variable "database_instance_class" {
  type    = string
  default = "db.r7g.large"
}

variable "redis_node_type" {
  type    = string
  default = "cache.r7g.large"
}

variable "tags" {
  type    = map(string)
  default = {}
}
