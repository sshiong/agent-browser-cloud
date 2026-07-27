use serde::Serialize;
use std::path::PathBuf;
use tauri::{
    menu::{Menu, MenuItem},
    tray::TrayIconBuilder,
    Manager,
};

const CREDENTIAL_SERVICE: &str = "io.browsercloud.desktop";

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LocalRuntimeStatus {
    available: bool,
    executable_path: Option<String>,
    reason: String,
}

#[tauri::command]
async fn secure_get(key: String) -> Result<Option<String>, String> {
    validate_credential_key(&key)?;
    tauri::async_runtime::spawn_blocking(move || read_credential(&key))
        .await
        .map_err(|_| "secure credential task failed".to_string())?
}

#[tauri::command]
async fn secure_set(key: String, value: String) -> Result<(), String> {
    validate_credential_key(&key)?;
    if value.len() > 256 * 1024 {
        return Err("secure credential value exceeds 256 KiB".to_string());
    }
    tauri::async_runtime::spawn_blocking(move || write_credential(&key, &value))
        .await
        .map_err(|_| "secure credential task failed".to_string())?
}

#[tauri::command]
async fn secure_remove(key: String) -> Result<(), String> {
    validate_credential_key(&key)?;
    tauri::async_runtime::spawn_blocking(move || delete_credential(&key))
        .await
        .map_err(|_| "secure credential task failed".to_string())?
}

#[tauri::command]
fn check_local_runtime(app: tauri::AppHandle) -> LocalRuntimeStatus {
    let executable_name = if cfg!(target_os = "windows") {
        "node-agent.exe"
    } else {
        "node-agent"
    };
    let executable_path = app
        .path()
        .resource_dir()
        .ok()
        .map(|root| root.join("runtime").join(executable_name));
    match executable_path {
        Some(path) if path.is_file() => LocalRuntimeStatus {
            available: true,
            executable_path: Some(display_path(path)),
            reason: "Packaged local Runtime is available".to_string(),
        },
        Some(_) => LocalRuntimeStatus {
            available: false,
            executable_path: None,
            reason: "No packaged local Runtime was found".to_string(),
        },
        None => LocalRuntimeStatus {
            available: false,
            executable_path: None,
            reason: "Desktop resource directory is unavailable".to_string(),
        },
    }
}

fn validate_credential_key(key: &str) -> Result<(), String> {
    if key.is_empty()
        || key.len() > 512
        || !key.starts_with("oidc.")
        || !key
            .bytes()
            .all(|value| value.is_ascii_alphanumeric() || matches!(value, b'.' | b'_' | b'-'))
    {
        return Err("invalid secure credential key".to_string());
    }
    Ok(())
}

#[cfg(any(target_os = "macos", target_os = "windows"))]
fn credential_entry(key: &str) -> Result<keyring::Entry, String> {
    keyring::Entry::new(CREDENTIAL_SERVICE, key)
        .map_err(|_| "OS credential store is unavailable".to_string())
}

#[cfg(any(target_os = "macos", target_os = "windows"))]
fn read_credential(key: &str) -> Result<Option<String>, String> {
    match credential_entry(key)?.get_password() {
        Ok(value) => Ok(Some(value)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(_) => Err("failed to read OS credential store".to_string()),
    }
}

#[cfg(any(target_os = "macos", target_os = "windows"))]
fn write_credential(key: &str, value: &str) -> Result<(), String> {
    credential_entry(key)?
        .set_password(value)
        .map_err(|_| "failed to write OS credential store".to_string())
}

#[cfg(any(target_os = "macos", target_os = "windows"))]
fn delete_credential(key: &str) -> Result<(), String> {
    match credential_entry(key)?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(_) => Err("failed to delete OS credential".to_string()),
    }
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
fn read_credential(_key: &str) -> Result<Option<String>, String> {
    Err("OS credential store is unsupported on this platform".to_string())
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
fn write_credential(_key: &str, _value: &str) -> Result<(), String> {
    Err("OS credential store is unsupported on this platform".to_string())
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
fn delete_credential(_key: &str) -> Result<(), String> {
    Err("OS credential store is unsupported on this platform".to_string())
}

fn display_path(path: PathBuf) -> String {
    path.to_string_lossy().into_owned()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let builder = tauri::Builder::default();
    #[cfg(any(target_os = "macos", target_os = "windows"))]
    let builder = builder.plugin(tauri_plugin_single_instance::init(
        |app, _arguments, _working_directory| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.set_focus();
            }
        },
    ));

    builder
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_os::init())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .invoke_handler(tauri::generate_handler![
            secure_get,
            secure_set,
            secure_remove,
            check_local_runtime
        ])
        .setup(|app| {
            let show =
                MenuItem::with_id(app, "show", "Show Agent Browser Cloud", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show, &quit])?;
            let mut tray = TrayIconBuilder::new()
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                    "quit" => app.exit(0),
                    _ => {}
                });
            if let Some(icon) = app.default_window_icon() {
                tray = tray.icon(icon.clone());
            }
            tray.build(app)?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running Agent Browser Cloud desktop");
}

#[cfg(test)]
mod tests {
    use super::validate_credential_key;

    #[test]
    fn accepts_only_namespaced_oidc_keys() {
        assert!(validate_credential_key("oidc.index.state").is_ok());
        assert!(validate_credential_key("oidc.ab_cd-123").is_ok());
        assert!(validate_credential_key("profile.secret").is_err());
        assert!(validate_credential_key("oidc.path/traversal").is_err());
        assert!(validate_credential_key("oidc.租户").is_err());
    }

    #[test]
    fn rejects_empty_and_oversized_credential_keys() {
        assert!(validate_credential_key("").is_err());
        assert!(validate_credential_key(&format!("oidc.{}", "x".repeat(508))).is_err());
    }
}
