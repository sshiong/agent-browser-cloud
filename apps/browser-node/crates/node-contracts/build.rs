use std::path::PathBuf;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?);
    let proto_root = manifest_dir.join("../../../../packages/contracts/proto");
    let node_proto = proto_root.join("node/v1/node_command.proto");

    let protoc = protoc_bin_vendored::protoc_bin_path()?;
    std::env::set_var("PROTOC", protoc);

    tonic_build::configure()
        .build_client(true)
        .build_server(true)
        .compile_protos(&[node_proto], &[proto_root])?;

    println!(
        "cargo:rerun-if-changed=../../../../packages/contracts/proto/node/v1/node_command.proto"
    );
    Ok(())
}
