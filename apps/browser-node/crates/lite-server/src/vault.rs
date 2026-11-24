//! L0 Encrypted Credential Vault using AES-256-GCM (`ring::aead`) with AAD binding to
//! `(owner_agent_id, credential_id, allowed_domain)`.
//!
//! Plaintext secrets are encrypted immediately at rest in SQLite and only decrypted in memory
//! right before CDP input after verifying session ownership, expiry, revocation, and exact/suffix
//! domain match. Plaintext is never returned by APIs or written to audit logs.

use anyhow::{anyhow, ensure, Result};
use base64::Engine as _;
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, AES_256_GCM, NONCE_LEN};
use ring::rand::{SecureRandom, SystemRandom};
use std::fs;
use std::path::Path;

pub struct CredentialVault {
    key: LessSafeKey,
    rng: SystemRandom,
}

impl CredentialVault {
    pub fn open_or_create(key_path: &Path) -> Result<Self> {
        let rng = SystemRandom::new();
        let key_bytes = if key_path.exists() {
            let raw = fs::read(key_path)?;
            ensure!(raw.len() == 32, "VAULT_KEY_CORRUPTED");
            raw
        } else {
            if let Some(parent) = key_path.parent() {
                fs::create_dir_all(parent)?;
            }
            let mut buf = vec![0u8; 32];
            rng.fill(&mut buf)
                .map_err(|_| anyhow!("VAULT_KEY_ENTROPY_FAILED"))?;
            fs::write(key_path, &buf)?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let _ = fs::set_permissions(key_path, fs::Permissions::from_mode(0o600));
            }
            buf
        };
        let unbound = UnboundKey::new(&AES_256_GCM, &key_bytes)
            .map_err(|_| anyhow!("VAULT_KEY_INIT_FAILED"))?;
        Ok(Self {
            key: LessSafeKey::new(unbound),
            rng,
        })
    }

    pub fn encrypt(
        &self,
        owner_agent_id: &str,
        credential_id: &str,
        allowed_domain: &str,
        plaintext: &str,
    ) -> Result<String> {
        ensure!(
            !plaintext.is_empty() && plaintext.len() <= 4096,
            "CREDENTIAL_VALUE_INVALID"
        );
        let mut nonce_bytes = [0u8; NONCE_LEN];
        self.rng
            .fill(&mut nonce_bytes)
            .map_err(|_| anyhow!("VAULT_NONCE_ENTROPY_FAILED"))?;
        let nonce = Nonce::assume_unique_for_key(nonce_bytes);
        let aad_str = format!("{owner_agent_id}|{credential_id}|{allowed_domain}");
        let mut in_out = plaintext.as_bytes().to_vec();
        self.key
            .seal_in_place_append_tag(nonce, Aad::from(aad_str.as_bytes()), &mut in_out)
            .map_err(|_| anyhow!("VAULT_ENCRYPT_FAILED"))?;
        let mut combined = Vec::with_capacity(NONCE_LEN + in_out.len());
        combined.extend_from_slice(&nonce_bytes);
        combined.extend_from_slice(&in_out);
        Ok(base64::engine::general_purpose::STANDARD.encode(combined))
    }

    pub fn decrypt(
        &self,
        owner_agent_id: &str,
        credential_id: &str,
        allowed_domain: &str,
        ciphertext_b64: &str,
    ) -> Result<String> {
        let mut raw = base64::engine::general_purpose::STANDARD
            .decode(ciphertext_b64)
            .map_err(|_| anyhow!("VAULT_CIPHERTEXT_INVALID"))?;
        ensure!(raw.len() > NONCE_LEN, "VAULT_CIPHERTEXT_INVALID");
        let nonce_bytes: [u8; NONCE_LEN] = raw[..NONCE_LEN]
            .try_into()
            .map_err(|_| anyhow!("VAULT_CIPHERTEXT_INVALID"))?;
        let nonce = Nonce::assume_unique_for_key(nonce_bytes);
        let aad_str = format!("{owner_agent_id}|{credential_id}|{allowed_domain}");
        let plain = self
            .key
            .open_in_place(nonce, Aad::from(aad_str.as_bytes()), &mut raw[NONCE_LEN..])
            .map_err(|_| anyhow!("VAULT_DECRYPT_FAILED"))?;
        let text = String::from_utf8(plain.to_vec())
            .map_err(|_| anyhow!("VAULT_PLAINTEXT_UTF8_INVALID"))?;
        Ok(text)
    }
}

pub fn normalize_allowed_domain(raw: &str) -> Result<String> {
    let trimmed = raw.trim().to_ascii_lowercase();
    ensure!(
        !trimmed.is_empty() && trimmed.len() <= 253,
        "CREDENTIAL_DOMAIN_INVALID"
    );
    let body = trimmed.strip_prefix("*.").unwrap_or(&trimmed);
    ensure!(
        !body.is_empty()
            && !body.contains('/')
            && !body.contains(':')
            && !body.contains(' ')
            && !body.starts_with('.')
            && !body.ends_with('.'),
        "CREDENTIAL_DOMAIN_INVALID"
    );
    Ok(trimmed)
}

pub fn url_matches_allowed_domain(page_url: &str, allowed_domain: &str) -> bool {
    let Ok(uri) = page_url.parse::<http::Uri>() else {
        return false;
    };
    let Some(host) = uri.host() else {
        return false;
    };
    let host_lc = host.trim_matches(['[', ']']).to_ascii_lowercase();
    let pattern = allowed_domain.trim().to_ascii_lowercase();
    if let Some(suffix) = pattern.strip_prefix("*.") {
        host_lc == suffix || host_lc.ends_with(&format!(".{suffix}"))
    } else {
        host_lc == pattern
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use uuid::Uuid;

    #[test]
    fn encrypts_with_aad_binding_and_enforces_domain_matching() {
        let path = std::env::temp_dir().join(format!("lite-vault-{}.key", Uuid::new_v4().simple()));
        let vault = CredentialVault::open_or_create(&path).unwrap();
        let ct = vault
            .encrypt("agent_a", "cred_001", "login.example.com", "s3cr3t!")
            .unwrap();
        assert!(!ct.contains("s3cr3t!"));

        // Correct AAD decrypts cleanly.
        assert_eq!(
            vault
                .decrypt("agent_a", "cred_001", "login.example.com", &ct)
                .unwrap(),
            "s3cr3t!"
        );
        // Wrong agent or wrong domain fails closed.
        assert!(vault
            .decrypt("agent_b", "cred_001", "login.example.com", &ct)
            .is_err());
        assert!(vault
            .decrypt("agent_a", "cred_001", "evil.example.com", &ct)
            .is_err());

        // Domain matcher checks exact & wildcard rules.
        assert!(url_matches_allowed_domain(
            "https://login.example.com/auth",
            "login.example.com"
        ));
        assert!(!url_matches_allowed_domain(
            "https://evil-login.example.com/auth",
            "login.example.com"
        ));
        assert!(url_matches_allowed_domain(
            "https://app.example.com/home",
            "*.example.com"
        ));
        assert!(!url_matches_allowed_domain(
            "https://notexample.com/home",
            "*.example.com"
        ));

        let _ = fs::remove_file(&path);
    }
}
