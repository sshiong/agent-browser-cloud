//! Pure-Rust Fail-Closed Full-Frame Privacy Scanner for Recording Frames.
//!
//! Drop-in replacement for `recording_privacy_scanner.py` that eliminates `python3`,
//! `python3-numpy`, and `python3-opencv` from `browser-node:vision` while keeping 100% protocol
//! parity (`--self-test`, NDJSON stdin/stdout, Tesseract OCR PII masking, Haar Cascade frontal
//! face masking, QR finder-pattern masking, and zero-residual re-scan on the persisted JPEG).

pub mod haar;
pub mod pii;
pub mod qr;

use anyhow::{anyhow, ensure, Result};
use base64::Engine as _;
use haar::HaarCascade;
use jpeg_decoder::PixelFormat;
use jpeg_encoder::{ColorType, Encoder};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::Path;
use std::process::{Command, Stdio};

pub const MAX_INPUT_BYTES: usize = 8 * 1024 * 1024;
pub const MAX_OUTPUT_BYTES: usize = 8 * 1024 * 1024;
pub const MAX_OCR_BYTES: usize = 256 * 1024;
pub const MAX_REGIONS: usize = 1_000;
pub const SCAN_VERSION: &str = "tesseract-opencv-pii-face-qr-v2";
pub const TESSERACT_PATH: &str = "/usr/bin/tesseract";
pub const FACE_CASCADE_PATH: &str =
    "/usr/share/opencv4/haarcascades/haarcascade_frontalface_default.xml";
pub const FACE_SELF_TEST_IMAGE: &str =
    "/usr/local/share/browsercloud/recording-privacy/grace-hopper.jpg";
pub const FACE_SELF_TEST_SHA256: &str =
    "a8ca6d734765703b09728ab47fe59f473d93ae3967fc24c7c0288c3c7adb7130";
pub const FACE_SELF_TEST_REGION: (usize, usize, usize, usize) = (156, 140, 374, 345);

pub type Region = (i32, i32, i32, i32);

#[derive(Debug, Clone)]
pub struct RgbImage {
    pub width: usize,
    pub height: usize,
    pub rgb: Vec<u8>,
}

impl RgbImage {
    pub fn to_gray(&self) -> Vec<u8> {
        self.rgb
            .as_chunks::<3>()
            .0
            .iter()
            .map(|p| {
                // ITU-R BT.601 luma matching OpenCV COLOR_BGR2GRAY.
                ((299u32 * p[0] as u32 + 587u32 * p[1] as u32 + 114u32 * p[2] as u32 + 500) / 1000)
                    as u8
            })
            .collect()
    }

    pub fn mask_region(&mut self, left: usize, top: usize, right: usize, bottom: usize) {
        for y in top..bottom.min(self.height) {
            let row = y * self.width;
            for x in left..right.min(self.width) {
                let off = (row + x) * 3;
                self.rgb[off] = 5;
                self.rgb[off + 1] = 8;
                self.rgb[off + 2] = 13;
            }
        }
    }
}

pub struct ScannerRuntime {
    pub tesseract_path: String,
    pub languages: String,
    pub cascade: HaarCascade,
}

impl ScannerRuntime {
    pub fn from_env() -> Result<Self> {
        let languages =
            std::env::var("RECORDING_OCR_LANGUAGES").unwrap_or_else(|_| "eng+chi_sim".to_owned());
        ensure!(validate_languages(&languages), "OCR_LANGUAGES_INVALID");
        let cascade_path = std::env::var("RECORDING_FACE_CASCADE")
            .unwrap_or_else(|_| FACE_CASCADE_PATH.to_owned());
        let cascade = HaarCascade::load_xml(Path::new(&cascade_path))?;
        Ok(Self {
            tesseract_path: TESSERACT_PATH.to_owned(),
            languages,
            cascade,
        })
    }

    pub fn scan_base64(&self, encoded: &str) -> Result<Value> {
        let (raw, mut img) = decode_jpeg_base64(encoded)?;
        let (ocr_text, ocr_rows) = run_tesseract_tsv(&self.tesseract_path, &self.languages, &raw)?;
        let (ocr_signals, ocr_regions) = ocr_sensitive_regions(&ocr_rows)?;
        ensure!(
            pii::sensitive_signal_count(&ocr_text) == ocr_signals,
            "OCR_REGION_MAPPING_INCOMPLETE"
        );
        let (visual_signals, visual_regions) = self.visual_sensitive_regions(&img)?;
        let mut all_regions = Vec::with_capacity(ocr_regions.len() + visual_regions.len());
        all_regions.extend(ocr_regions.iter().copied());
        all_regions.extend(visual_regions.iter().copied());
        ensure!(
            all_regions.len() <= MAX_REGIONS,
            "PRIVACY_REGION_LIMIT_EXCEEDED"
        );

        for region in all_regions {
            let (l, t, r, b) = bounded_region(region, img.width, img.height)?;
            img.mask_region(l, t, r, b);
        }

        let sanitized_bytes = encode_jpeg(&img, 60)?;
        ensure!(
            sanitized_bytes.starts_with(&[0xff, 0xd8, 0xff])
                && sanitized_bytes.len() <= MAX_OUTPUT_BYTES,
            "IMAGE_ENCODE_FAILED"
        );

        // Second-pass zero-residual verification on exact persisted JPEG bytes.
        let persisted = decode_jpeg_bytes(&sanitized_bytes)
            .map_err(|_| anyhow!("SANITIZED_IMAGE_DECODE_FAILED"))?;
        ensure!(
            persisted.width == img.width && persisted.height == img.height,
            "SANITIZED_IMAGE_DECODE_FAILED"
        );
        let (residual_text, _) =
            run_tesseract_tsv(&self.tesseract_path, &self.languages, &sanitized_bytes)?;
        let residual_ocr = pii::sensitive_signal_count(&residual_text);
        let (residual_visual, _) = self.visual_sensitive_regions(&persisted)?;
        ensure!(
            residual_ocr == 0 && residual_visual == 0,
            "PRIVACY_REDACTION_INCOMPLETE"
        );

        Ok(json!({
            "scanVersion": SCAN_VERSION,
            "sanitizedImageBase64": base64::engine::general_purpose::STANDARD.encode(&sanitized_bytes),
            "sanitizedImageSha256": sha256_hex(&sanitized_bytes),
            "ocrDetectedSignalCount": ocr_signals,
            "ocrMaskedRegionCount": ocr_regions.len(),
            "ocrResidualSignalCount": 0,
            "visualDetectedSignalCount": visual_signals,
            "visualMaskedRegionCount": visual_regions.len(),
            "visualResidualSignalCount": 0,
        }))
    }

    pub fn face_regions(&self, img: &RgbImage) -> Result<Vec<(i32, i32, i32, i32)>> {
        let gray = img.to_gray();
        let faces = self
            .cascade
            .detect_multi_scale(&gray, img.width, img.height, 1.1, 5, 24);
        let mut out = Vec::with_capacity(faces.len());
        for (x, y, w, h) in faces {
            let pad_x = (w / 10).max(4);
            let pad_y = (h / 10).max(4);
            let (l, t, r, b) = bounded_region(
                (x - pad_x, y - pad_y, x + w + pad_x, y + h + pad_y),
                img.width,
                img.height,
            )?;
            out.push((l as i32, t as i32, r as i32, b as i32));
        }
        Ok(out)
    }

    pub fn visual_sensitive_regions(&self, img: &RgbImage) -> Result<(usize, Vec<Region>)> {
        let mut regions = self.face_regions(img)?;
        let gray = img.to_gray();
        for qr_box in qr::detect_qr_regions(&gray, img.width, img.height) {
            let (l, t, r, b) = bounded_region(qr_box, img.width, img.height)?;
            regions.push((l as i32, t as i32, r as i32, b as i32));
        }
        Ok((regions.len(), regions))
    }

    pub fn self_test(&self) -> Result<()> {
        ensure!(
            Path::new(&self.tesseract_path).is_file(),
            "TESSERACT_UNAVAILABLE"
        );
        let portrait_path = std::env::var("RECORDING_FACE_SELF_TEST_IMAGE")
            .unwrap_or_else(|_| FACE_SELF_TEST_IMAGE.to_owned());
        let portrait =
            fs::read(&portrait_path).map_err(|_| anyhow!("FACE_SELF_TEST_FIXTURE_UNAVAILABLE"))?;
        ensure!(
            sha256_hex(&portrait) == FACE_SELF_TEST_SHA256,
            "FACE_SELF_TEST_FIXTURE_INVALID"
        );
        let face_img = decode_jpeg_bytes(&portrait)?;
        let (fl, ft, fr, fb) = FACE_SELF_TEST_REGION;
        let detected_faces = self.face_regions(&face_img)?;
        ensure!(
            detected_faces.iter().any(|&(l, t, r, b)| {
                (l as usize) <= fl && (t as usize) <= ft && (r as usize) >= fr && (b as usize) >= fb
            }),
            "FACE_CLASSIFIER_SELF_TEST_FAILED"
        );
        let face_b64 = base64::engine::general_purpose::STANDARD.encode(&portrait);
        let face_res = self.scan_base64(&face_b64)?;
        ensure!(
            face_res["visualDetectedSignalCount"].as_u64().unwrap_or(0) >= 1
                && face_res["visualMaskedRegionCount"].as_u64().unwrap_or(0) >= 1
                && face_res["visualResidualSignalCount"].as_u64() == Some(0),
            "FACE_CLASSIFIER_SELF_TEST_FAILED"
        );
        let sanitized_face = decode_jpeg_base64(
            face_res["sanitizedImageBase64"]
                .as_str()
                .ok_or_else(|| anyhow!("FACE_CLASSIFIER_SELF_TEST_FAILED"))?,
        )?
        .1;
        ensure!(
            sanitized_face.width == face_img.width && sanitized_face.height == face_img.height,
            "FACE_CLASSIFIER_SELF_TEST_FAILED"
        );
        for y in ft..fb {
            for x in fl..fr {
                let off = (y * sanitized_face.width + x) * 3;
                ensure!(
                    sanitized_face.rgb[off] <= 24
                        && sanitized_face.rgb[off + 1] <= 24
                        && sanitized_face.rgb[off + 2] <= 24,
                    "FACE_CLASSIFIER_SELF_TEST_FAILED"
                );
            }
        }

        // Functional OCR + QR synthetic test image (800x360 white background).
        let (w, h) = (800usize, 360usize);
        let mut synth = RgbImage {
            width: w,
            height: h,
            rgb: vec![255u8; w * h * 3],
        };
        qr::draw_bitmap_text(&mut synth.rgb, w, h, "user@example.com", 24, 48, 3);
        qr::draw_bitmap_text(&mut synth.rgb, w, h, "123-45-6789", 24, 150, 4);
        qr::draw_synthetic_qr(&mut synth.rgb, w, h, 540, 110, 10);
        let synth_jpg = encode_jpeg(&synth, 95)?;
        let synth_b64 = base64::engine::general_purpose::STANDARD.encode(&synth_jpg);
        let res = self.scan_base64(&synth_b64)?;
        ensure!(
            res["ocrDetectedSignalCount"].as_u64().unwrap_or(0) >= 1
                && res["ocrMaskedRegionCount"].as_u64().unwrap_or(0) >= 1
                && res["visualDetectedSignalCount"].as_u64().unwrap_or(0) >= 1
                && res["visualMaskedRegionCount"].as_u64().unwrap_or(0) >= 1
                && res["ocrResidualSignalCount"].as_u64() == Some(0)
                && res["visualResidualSignalCount"].as_u64() == Some(0),
            "FUNCTIONAL_PRIVACY_SELF_TEST_FAILED"
        );
        Ok(())
    }
}

pub fn validate_languages(langs: &str) -> bool {
    let parts: Vec<&str> = langs.split('+').collect();
    (1..=4).contains(&parts.len())
        && parts.iter().all(|p| {
            !p.is_empty()
                && p.bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
        })
}

pub fn bounded_region(
    region: (i32, i32, i32, i32),
    width: usize,
    height: usize,
) -> Result<(usize, usize, usize, usize)> {
    let left = (region.0 - 4).max(0) as usize;
    let top = (region.1 - 4).max(0) as usize;
    let right = ((region.2 + 4).max(0) as usize).min(width);
    let bottom = ((region.3 + 4).max(0) as usize).min(height);
    ensure!(left < right && top < bottom, "VISUAL_REGION_INVALID");
    Ok((left, top, right, bottom))
}

pub fn decode_jpeg_base64(encoded: &str) -> Result<(Vec<u8>, RgbImage)> {
    let raw = base64::engine::general_purpose::STANDARD
        .decode(encoded)
        .map_err(|_| anyhow!("IMAGE_BASE64_INVALID"))?;
    let img = decode_jpeg_bytes(&raw)?;
    Ok((raw, img))
}

pub fn decode_jpeg_bytes(raw: &[u8]) -> Result<RgbImage> {
    ensure!(
        !raw.is_empty() && raw.len() <= MAX_INPUT_BYTES && raw.starts_with(&[0xff, 0xd8, 0xff]),
        "IMAGE_JPEG_INVALID"
    );
    let mut decoder = jpeg_decoder::Decoder::new(raw);
    let pixels = decoder
        .decode()
        .map_err(|_| anyhow!("IMAGE_DECODE_FAILED"))?;
    let info = decoder
        .info()
        .ok_or_else(|| anyhow!("IMAGE_DECODE_FAILED"))?;
    let width = info.width as usize;
    let height = info.height as usize;
    ensure!(
        width > 0 && height > 0 && width <= 1920 && height <= 1080,
        "IMAGE_DIMENSIONS_INVALID"
    );
    let rgb = match info.pixel_format {
        PixelFormat::RGB24 => pixels,
        PixelFormat::L8 => {
            let mut out = Vec::with_capacity(width * height * 3);
            for g in pixels {
                out.extend_from_slice(&[g, g, g]);
            }
            out
        }
        _ => return Err(anyhow!("IMAGE_DECODE_FAILED")),
    };
    Ok(RgbImage { width, height, rgb })
}

pub fn encode_jpeg(img: &RgbImage, quality: u8) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    let encoder = Encoder::new(&mut out, quality);
    encoder
        .encode(
            &img.rgb,
            img.width as u16,
            img.height as u16,
            ColorType::Rgb,
        )
        .map_err(|_| anyhow!("IMAGE_ENCODE_FAILED"))?;
    Ok(out)
}

#[derive(Debug)]
struct OcrRow {
    page_num: String,
    block_num: String,
    par_num: String,
    line_num: String,
    left: i32,
    top: i32,
    width: i32,
    height: i32,
    text: String,
}

fn run_tesseract_tsv(
    tesseract_path: &str,
    languages: &str,
    image: &[u8],
) -> Result<(String, Vec<OcrRow>)> {
    let mut child = Command::new(tesseract_path)
        .args(["stdin", "stdout", "-l", languages, "--psm", "11", "tsv"])
        .env_clear()
        .env("PATH", "/usr/bin:/bin")
        .env("LANG", "C.UTF-8")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|_| anyhow!("OCR_EXECUTION_FAILED"))?;
    if let Some(mut stdin) = child.stdin.take() {
        stdin
            .write_all(image)
            .map_err(|_| anyhow!("OCR_EXECUTION_FAILED"))?;
    }
    let output = child
        .wait_with_output()
        .map_err(|_| anyhow!("OCR_EXECUTION_FAILED"))?;
    ensure!(
        output.status.success() && output.stdout.len() <= MAX_OCR_BYTES,
        "OCR_EXECUTION_FAILED"
    );
    let doc = String::from_utf8(output.stdout).map_err(|_| anyhow!("OCR_TSV_INVALID"))?;
    let mut lines = doc.lines();
    let header = lines.next().ok_or_else(|| anyhow!("OCR_TSV_INVALID"))?;
    let cols: HashMap<&str, usize> = header
        .split('\t')
        .enumerate()
        .map(|(idx, name)| (name.trim(), idx))
        .collect();
    let req = |name: &str| -> Result<usize> {
        cols.get(name)
            .copied()
            .ok_or_else(|| anyhow!("OCR_TSV_INVALID"))
    };
    let i_page = req("page_num")?;
    let i_block = req("block_num")?;
    let i_par = req("par_num")?;
    let i_line = req("line_num")?;
    let i_left = req("left")?;
    let i_top = req("top")?;
    let i_w = req("width")?;
    let i_h = req("height")?;
    let i_text = req("text")?;

    let mut rows = Vec::new();
    let mut words = Vec::new();
    for line in lines {
        let fields: Vec<&str> = line.split('\t').collect();
        let text = fields.get(i_text).copied().unwrap_or("").trim();
        if text.is_empty() {
            continue;
        }
        let parse_i32 = |idx: usize| -> Result<i32> {
            fields
                .get(idx)
                .ok_or_else(|| anyhow!("OCR_REGION_INVALID"))?
                .trim()
                .parse::<i32>()
                .map_err(|_| anyhow!("OCR_REGION_INVALID"))
        };
        words.push(text.to_owned());
        rows.push(OcrRow {
            page_num: fields.get(i_page).copied().unwrap_or("").to_owned(),
            block_num: fields.get(i_block).copied().unwrap_or("").to_owned(),
            par_num: fields.get(i_par).copied().unwrap_or("").to_owned(),
            line_num: fields.get(i_line).copied().unwrap_or("").to_owned(),
            left: parse_i32(i_left)?,
            top: parse_i32(i_top)?,
            width: parse_i32(i_w)?,
            height: parse_i32(i_h)?,
            text: text.to_owned(),
        });
    }
    let normalized = words.join(" ");
    Ok((normalized, rows))
}

fn ocr_sensitive_regions(rows: &[OcrRow]) -> Result<(usize, Vec<Region>)> {
    let mut grouped: HashMap<(&str, &str, &str, &str), Vec<&OcrRow>> = HashMap::new();
    for row in rows {
        grouped
            .entry((&row.page_num, &row.block_num, &row.par_num, &row.line_num))
            .or_default()
            .push(row);
    }
    let mut detected = 0usize;
    let mut regions = Vec::new();
    for words in grouped.into_values() {
        let line_text = words
            .iter()
            .map(|r| r.text.as_str())
            .collect::<Vec<_>>()
            .join(" ");
        let signals = pii::sensitive_signal_count(&line_text);
        if signals == 0 {
            continue;
        }
        detected += signals;
        let left = words.iter().map(|r| r.left).min().unwrap_or(0);
        let top = words.iter().map(|r| r.top).min().unwrap_or(0);
        let right = words.iter().map(|r| r.left + r.width).max().unwrap_or(0);
        let bottom = words.iter().map(|r| r.top + r.height).max().unwrap_or(0);
        regions.push((left, top, right, bottom));
    }
    Ok((detected, regions))
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    let digest = hasher.finalize();
    let mut out = String::with_capacity(digest.len() * 2);
    for b in digest {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jpeg_roundtrip_and_blackout_masking_work_in_pure_rust() {
        let mut img = RgbImage {
            width: 120,
            height: 80,
            rgb: vec![240u8; 120 * 80 * 3],
        };
        img.mask_region(20, 20, 60, 50);
        let encoded = encode_jpeg(&img, 60).unwrap();
        assert!(encoded.starts_with(&[0xff, 0xd8, 0xff]));
        let decoded = decode_jpeg_bytes(&encoded).unwrap();
        assert_eq!(decoded.width, 120);
        assert_eq!(decoded.height, 80);
        // Center of masked region must stay dark (<= 24).
        let center = (35 * 120 + 40) * 3;
        assert!(decoded.rgb[center] <= 24);
        assert!(decoded.rgb[center + 1] <= 24);
        assert!(decoded.rgb[center + 2] <= 24);
    }
}
