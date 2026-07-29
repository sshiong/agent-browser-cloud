use std::fs::File;
use std::io::Cursor;
use std::path::PathBuf;

fn main() -> anyhow::Result<()> {
    let output = std::env::args_os()
        .nth(1)
        .map(PathBuf::from)
        .ok_or_else(|| anyhow::anyhow!("usage: create_profile_import <output.tar.zst>"))?;
    let file = File::create(output)?;
    let encoder = zstd::Encoder::new(file, 3)?;
    let mut archive = tar::Builder::new(encoder);
    let content = br#"{"tenant":"integration","session":"preserved"}"#;
    let mut header = tar::Header::new_gnu();
    header.set_entry_type(tar::EntryType::Regular);
    header.set_mode(0o600);
    header.set_size(content.len() as u64);
    header.set_cksum();
    archive.append_data(&mut header, "core/Preferences", Cursor::new(content))?;
    let encoder = archive.into_inner()?;
    encoder.finish()?;
    Ok(())
}
