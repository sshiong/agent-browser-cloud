fn main() {
    let manifest = tauri_build::AppManifest::new().commands(&[
        "secure_get",
        "secure_set",
        "secure_remove",
        "check_local_runtime",
    ]);
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(manifest))
        .expect("failed to build Tauri command permissions");
}
