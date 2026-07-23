// @generated
/// 可验证、不可变的 Runtime 构建清单。
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct RuntimeManifest {
    #[prost(string, tag="1")]
    pub build_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub engine: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub engine_version: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub platform: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub artifact_digest: ::prost::alloc::string::String,
    #[prost(string, tag="6")]
    pub sbom_uri: ::prost::alloc::string::String,
    #[prost(string, tag="7")]
    pub signature: ::prost::alloc::string::String,
    #[prost(string, tag="8")]
    pub security_tier: ::prost::alloc::string::String,
    #[prost(string, repeated, tag="9")]
    pub capabilities: ::prost::alloc::vec::Vec<::prost::alloc::string::String>,
    #[prost(int64, tag="10")]
    pub created_at_ms: i64,
}
// @@protoc_insertion_point(module)
