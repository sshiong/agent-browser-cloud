//! Pure-Rust OpenCV Haar Cascade XML parser + Viola-Jones integral-image frontal face detector.
//!
//! Reads standard OpenCV `haarcascade_frontalface_default.xml` (modern `<cascade>` format) with
//! zero `libopencv` or Python dependencies.

use anyhow::{anyhow, ensure, Result};
use std::fs;
use std::path::Path;

#[derive(Debug, Clone, Copy)]
pub struct HaarRect {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
    pub weight: f64,
}

#[derive(Debug, Clone)]
pub struct HaarFeature {
    pub rects: Vec<HaarRect>,
}

#[derive(Debug, Clone, Copy)]
pub struct WeakClassifier {
    pub feature_idx: usize,
    pub threshold: f64,
    pub left_val: f64,
    pub right_val: f64,
}

#[derive(Debug, Clone)]
pub struct HaarStage {
    pub stage_threshold: f64,
    pub weak_classifiers: Vec<WeakClassifier>,
}

#[derive(Debug, Clone)]
pub struct HaarCascade {
    pub width: usize,
    pub height: usize,
    pub stages: Vec<HaarStage>,
    pub features: Vec<HaarFeature>,
}

impl HaarCascade {
    pub fn load_xml(path: &Path) -> Result<Self> {
        let xml = fs::read_to_string(path).map_err(|_| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?;
        Self::parse_xml(&xml)
    }

    pub fn parse_xml(xml: &str) -> Result<Self> {
        let width: usize = extract_tag_text(xml, "width")
            .ok_or_else(|| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?
            .trim()
            .parse()
            .map_err(|_| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?;
        let height: usize = extract_tag_text(xml, "height")
            .ok_or_else(|| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?
            .trim()
            .parse()
            .map_err(|_| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?;

        let stages_block = extract_tag_text(xml, "stages")
            .ok_or_else(|| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?;
        let features_block = extract_tag_text(xml, "features")
            .ok_or_else(|| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?;

        let mut stages = Vec::new();
        for stage_xml in split_underscore_blocks(stages_block) {
            let stage_threshold: f64 = extract_tag_text(stage_xml, "stageThreshold")
                .ok_or_else(|| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?
                .trim()
                .parse()
                .map_err(|_| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?;
            let weak_block = extract_tag_text(stage_xml, "weakClassifiers")
                .ok_or_else(|| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?;
            let mut weak_classifiers = Vec::new();
            for wc_xml in split_underscore_blocks(weak_block) {
                let nodes_str = extract_tag_text(wc_xml, "internalNodes")
                    .ok_or_else(|| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?;
                let leaves_str = extract_tag_text(wc_xml, "leafValues")
                    .ok_or_else(|| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?;
                let nodes: Vec<&str> = nodes_str.split_whitespace().collect();
                let leaves: Vec<&str> = leaves_str.split_whitespace().collect();
                ensure!(
                    nodes.len() >= 4 && leaves.len() >= 2,
                    "FACE_CLASSIFIER_UNAVAILABLE"
                );
                let feature_idx: usize = nodes[2]
                    .parse()
                    .map_err(|_| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?;
                let threshold: f64 = nodes[3]
                    .parse()
                    .map_err(|_| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?;
                let left_val: f64 = leaves[0]
                    .parse()
                    .map_err(|_| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?;
                let right_val: f64 = leaves[1]
                    .parse()
                    .map_err(|_| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?;
                weak_classifiers.push(WeakClassifier {
                    feature_idx,
                    threshold,
                    left_val,
                    right_val,
                });
            }
            stages.push(HaarStage {
                stage_threshold,
                weak_classifiers,
            });
        }

        let mut features = Vec::new();
        for feat_xml in split_underscore_blocks(features_block) {
            let rects_block = extract_tag_text(feat_xml, "rects")
                .ok_or_else(|| anyhow!("FACE_CLASSIFIER_UNAVAILABLE"))?;
            let mut rects = Vec::new();
            for rect_xml in split_underscore_blocks(rects_block) {
                let parts: Vec<&str> = rect_xml.split_whitespace().collect();
                ensure!(parts.len() >= 5, "FACE_CLASSIFIER_UNAVAILABLE");
                let x: i32 = parts[0].parse()?;
                let y: i32 = parts[1].parse()?;
                let w: i32 = parts[2].parse()?;
                let h: i32 = parts[3].parse()?;
                let weight_str = parts[4].trim_end_matches('.');
                let weight: f64 = weight_str.parse()?;
                rects.push(HaarRect { x, y, w, h, weight });
            }
            features.push(HaarFeature { rects });
        }

        ensure!(
            !stages.is_empty() && !features.is_empty(),
            "FACE_CLASSIFIER_UNAVAILABLE"
        );
        Ok(Self {
            width,
            height,
            stages,
            features,
        })
    }

    pub fn detect_multi_scale(
        &self,
        gray: &[u8],
        img_w: usize,
        img_h: usize,
        scale_factor: f64,
        min_neighbors: usize,
        min_size: usize,
    ) -> Vec<(i32, i32, i32, i32)> {
        if img_w < min_size || img_h < min_size {
            return Vec::new();
        }
        let stride = img_w + 1;
        let mut ii = vec![0u64; stride * (img_h + 1)];
        let mut sq = vec![0u64; stride * (img_h + 1)];
        for y in 0..img_h {
            let mut row_sum = 0u64;
            let mut row_sq = 0u64;
            let src_row = y * img_w;
            let dst_row = (y + 1) * stride;
            let prev_row = y * stride;
            for x in 0..img_w {
                let v = gray[src_row + x] as u64;
                row_sum += v;
                row_sq += v * v;
                ii[dst_row + x + 1] = ii[prev_row + x + 1] + row_sum;
                sq[dst_row + x + 1] = sq[prev_row + x + 1] + row_sq;
            }
        }

        let rect_sum = |table: &[u64], x: usize, y: usize, w: usize, h: usize| -> u64 {
            let a = table[y * stride + x];
            let b = table[y * stride + (x + w)];
            let c = table[(y + h) * stride + x];
            let d = table[(y + h) * stride + (x + w)];
            d + a - b - c
        };

        let mut raw_hits: Vec<(i32, i32, i32, i32)> = Vec::new();
        let mut scale = (min_size as f64 / self.width as f64).max(1.0);

        loop {
            let win_w = (self.width as f64 * scale).round() as usize;
            let win_h = (self.height as f64 * scale).round() as usize;
            if win_w > img_w || win_h > img_h {
                break;
            }
            if win_w < min_size || win_h < min_size {
                scale *= scale_factor;
                continue;
            }

            let norm_w = win_w.saturating_sub(2).max(1);
            let norm_h = win_h.saturating_sub(2).max(1);
            let inv_area = 1.0 / ((norm_w * norm_h) as f64);

            type ScaledRect = (usize, usize, usize, usize, f64);
            // Pre-scale all feature rectangles once per scale level.
            let scaled_features: Vec<Vec<ScaledRect>> = self
                .features
                .iter()
                .map(|feat| {
                    let mut srects: Vec<(usize, usize, usize, usize, f64)> = feat
                        .rects
                        .iter()
                        .map(|r| {
                            let rx = ((r.x as f64 * scale).round() as usize)
                                .min(win_w.saturating_sub(1));
                            let ry = ((r.y as f64 * scale).round() as usize)
                                .min(win_h.saturating_sub(1));
                            let rw = ((r.w as f64 * scale).round() as usize).clamp(1, win_w - rx);
                            let rh = ((r.h as f64 * scale).round() as usize).clamp(1, win_h - ry);
                            (rx, ry, rw, rh, r.weight)
                        })
                        .collect();
                    if srects.len() >= 2 {
                        let sum_other: f64 = srects[1..]
                            .iter()
                            .map(|(_, _, rw, rh, w)| (*rw * *rh) as f64 * w)
                            .sum();
                        let area0 = (srects[0].2 * srects[0].3) as f64;
                        if area0 > 0.0 {
                            srects[0].4 = -sum_other / area0;
                        }
                    }
                    srects
                })
                .collect();

            let step = if scale > 2.0 {
                (scale.round() as usize).max(1)
            } else {
                ((2.0 * scale).round() as usize).max(1)
            };

            let max_y = img_h - win_h;
            let max_x = img_w - win_w;
            let mut y = 0usize;
            while y <= max_y {
                let mut x = 0usize;
                while x <= max_x {
                    let sum = rect_sum(&ii, x + 1, y + 1, norm_w, norm_h) as f64;
                    let sq_sum = rect_sum(&sq, x + 1, y + 1, norm_w, norm_h) as f64;
                    let mean = sum * inv_area;
                    let variance = sq_sum * inv_area - mean * mean;
                    let std_dev = if variance > 0.0 { variance.sqrt() } else { 1.0 };

                    let mut passed = true;
                    for stage in &self.stages {
                        let mut stage_sum = 0.0f64;
                        for wc in &stage.weak_classifiers {
                            let mut fsum = 0.0f64;
                            for &(rx, ry, rw, rh, weight) in &scaled_features[wc.feature_idx] {
                                fsum += rect_sum(&ii, x + rx, y + ry, rw, rh) as f64 * weight;
                            }
                            if fsum * inv_area < wc.threshold * std_dev {
                                stage_sum += wc.left_val;
                            } else {
                                stage_sum += wc.right_val;
                            }
                        }
                        if stage_sum < stage.stage_threshold - 1e-4 {
                            passed = false;
                            break;
                        }
                    }
                    if passed {
                        raw_hits.push((x as i32, y as i32, win_w as i32, win_h as i32));
                    }
                    x += step;
                }
                y += step;
            }
            scale *= scale_factor;
        }

        group_rectangles(&raw_hits, min_neighbors, 0.2)
    }
}

fn rects_similar(a: (i32, i32, i32, i32), b: (i32, i32, i32, i32), eps: f64) -> bool {
    let delta = eps * ((a.2.min(b.2) + a.3.min(b.3)) as f64) * 0.5;
    (a.0 - b.0).abs() as f64 <= delta
        && (a.1 - b.1).abs() as f64 <= delta
        && ((a.0 + a.2) - (b.0 + b.2)).abs() as f64 <= delta
        && ((a.1 + a.3) - (b.1 + b.3)).abs() as f64 <= delta
}

pub fn group_rectangles(
    rects: &[(i32, i32, i32, i32)],
    min_neighbors: usize,
    eps: f64,
) -> Vec<(i32, i32, i32, i32)> {
    let n = rects.len();
    if n == 0 {
        return Vec::new();
    }
    let mut parent: Vec<usize> = (0..n).collect();
    fn find(parent: &mut [usize], mut x: usize) -> usize {
        while parent[x] != x {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        x
    }
    for i in 0..n {
        for j in (i + 1)..n {
            if rects_similar(rects[i], rects[j], eps) {
                let ri = find(&mut parent, i);
                let rj = find(&mut parent, j);
                if ri != rj {
                    parent[rj] = ri;
                }
            }
        }
    }
    let mut clusters: std::collections::HashMap<usize, Vec<(i32, i32, i32, i32)>> =
        std::collections::HashMap::new();
    for (i, &rect) in rects.iter().enumerate() {
        let root = find(&mut parent, i);
        clusters.entry(root).or_default().push(rect);
    }
    let mut out = Vec::new();
    for group in clusters.into_values() {
        if group.len() < min_neighbors {
            continue;
        }
        let cnt = group.len() as i64;
        let sx: i64 = group.iter().map(|r| r.0 as i64).sum();
        let sy: i64 = group.iter().map(|r| r.1 as i64).sum();
        let sw: i64 = group.iter().map(|r| r.2 as i64).sum();
        let sh: i64 = group.iter().map(|r| r.3 as i64).sum();
        out.push((
            (sx / cnt) as i32,
            (sy / cnt) as i32,
            (sw / cnt) as i32,
            (sh / cnt) as i32,
        ));
    }
    out
}

fn extract_tag_text<'a>(xml: &'a str, tag: &str) -> Option<&'a str> {
    let open = format!("<{tag}");
    let start_idx = xml.find(&open)?;
    let after_open = &xml[start_idx + open.len()..];
    let gt = after_open.find('>')?;
    let content_start = start_idx + open.len() + gt + 1;
    let close = format!("</{tag}>");
    let end_rel = xml[content_start..].find(&close)?;
    Some(&xml[content_start..content_start + end_rel])
}

/// Splits top-level `<_>...</_>` blocks cleanly even when nested `<_>` tags exist inside.
fn split_underscore_blocks(xml: &str) -> Vec<&str> {
    let mut blocks = Vec::new();
    let bytes = xml.as_bytes();
    let mut i = 0;
    let mut depth = 0usize;
    let mut content_start = 0usize;
    while i < bytes.len() {
        if bytes[i..].starts_with(b"<_>") {
            if depth == 0 {
                content_start = i + 3;
            }
            depth += 1;
            i += 3;
        } else if bytes[i..].starts_with(b"</_>") {
            if depth > 0 {
                depth -= 1;
                if depth == 0 {
                    blocks.push(&xml[content_start..i]);
                }
            }
            i += 4;
        } else {
            i += 1;
        }
    }
    blocks
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_nested_opencv_cascade_xml() {
        let sample = r#"
        <opencv_storage>
        <cascade>
          <height>24</height>
          <width>24</width>
          <stages>
            <_>
              <stageThreshold>0.5</stageThreshold>
              <weakClassifiers>
                <_>
                  <internalNodes>0 -1 0 0.01</internalNodes>
                  <leafValues>-0.2 0.8</leafValues>
                </_>
              </weakClassifiers>
            </_>
          </stages>
          <features>
            <_>
              <rects>
                <_>0 0 12 24 -1.</_>
                <_>12 0 12 24 2.</_>
              </rects>
            </_>
          </features>
        </cascade>
        </opencv_storage>
        "#;
        let cascade = HaarCascade::parse_xml(sample).unwrap();
        assert_eq!(cascade.width, 24);
        assert_eq!(cascade.stages.len(), 1);
        assert_eq!(cascade.features.len(), 1);
        assert_eq!(cascade.features[0].rects.len(), 2);
    }
}
