//! Pure-Rust ISO/IEC 18004 QR finder-pattern (`1:1:3:1:1`) geometry detector and self-test
//! synthetic QR + text bitmap renderer.

/// Detects QR code bounding boxes `(left, top, right, bottom)` via `1:1:3:1:1` dark-light-dark-
/// light-dark finder pattern run-length scanning with vertical cross-check.
pub fn detect_qr_regions(gray: &[u8], width: usize, height: usize) -> Vec<(i32, i32, i32, i32)> {
    if width < 21 || height < 21 {
        return Vec::new();
    }
    let threshold = 128u8;
    let mut finder_centers: Vec<(f64, f64, f64)> = Vec::new();

    for y in (0..height).step_by(2) {
        let row = &gray[y * width..(y + 1) * width];
        let mut runs: Vec<(bool, usize, usize)> = Vec::new();
        let mut cur_dark = row[0] < threshold;
        let mut run_start = 0usize;
        for (x, &px) in row.iter().enumerate().skip(1) {
            let dark = px < threshold;
            if dark != cur_dark {
                runs.push((cur_dark, run_start, x - run_start));
                cur_dark = dark;
                run_start = x;
            }
        }
        runs.push((cur_dark, run_start, width - run_start));

        if runs.len() < 5 {
            continue;
        }
        for w in runs.windows(5) {
            if !(w[0].0 && !w[1].0 && w[2].0 && !w[3].0 && w[4].0) {
                continue;
            }
            let counts = [w[0].2, w[1].2, w[2].2, w[3].2, w[4].2];
            if !matches_finder_ratio(counts) {
                continue;
            }
            let total: usize = counts.iter().sum();
            let module = total as f64 / 7.0;
            if module < 1.5 {
                continue;
            }
            let cx = w[2].1 + w[2].2 / 2;
            if let Some(cy) = vertical_cross_check(gray, width, height, cx, y, threshold, total) {
                add_or_merge_center(&mut finder_centers, cx as f64, cy, module);
            }
        }
    }

    if finder_centers.len() < 3 {
        return Vec::new();
    }

    // Group finder centers that belong to the same QR symbol (distance <= 40 * module).
    let mut used = vec![false; finder_centers.len()];
    let mut regions = Vec::new();
    for i in 0..finder_centers.len() {
        if used[i] {
            continue;
        }
        let mut cluster = vec![finder_centers[i]];
        used[i] = true;
        let mut idx = 0;
        while idx < cluster.len() {
            let (ax, ay, am) = cluster[idx];
            for j in 0..finder_centers.len() {
                if used[j] {
                    continue;
                }
                let (bx, by, bm) = finder_centers[j];
                let dist = ((ax - bx).powi(2) + (ay - by).powi(2)).sqrt();
                let max_mod = am.max(bm);
                if (am - bm).abs() <= max_mod * 0.5 && dist <= max_mod * 60.0 {
                    used[j] = true;
                    cluster.push(finder_centers[j]);
                }
            }
            idx += 1;
        }
        if cluster.len() >= 3 {
            let avg_mod: f64 = cluster.iter().map(|c| c.2).sum::<f64>() / cluster.len() as f64;
            let pad = (avg_mod * 4.5).ceil() as i32;
            let min_x = cluster
                .iter()
                .map(|c| c.0)
                .fold(f64::INFINITY, f64::min)
                .floor() as i32;
            let max_x = cluster
                .iter()
                .map(|c| c.0)
                .fold(f64::NEG_INFINITY, f64::max)
                .ceil() as i32;
            let min_y = cluster
                .iter()
                .map(|c| c.1)
                .fold(f64::INFINITY, f64::min)
                .floor() as i32;
            let max_y = cluster
                .iter()
                .map(|c| c.1)
                .fold(f64::NEG_INFINITY, f64::max)
                .ceil() as i32;
            regions.push((min_x - pad, min_y - pad, max_x + pad, max_y + pad));
        }
    }
    regions
}

fn matches_finder_ratio(c: [usize; 5]) -> bool {
    let total: usize = c.iter().sum();
    if total < 7 {
        return false;
    }
    let unit = total as f64 / 7.0;
    let max_var = unit * 0.55;
    (c[0] as f64 - unit).abs() <= max_var
        && (c[1] as f64 - unit).abs() <= max_var
        && (c[2] as f64 - 3.0 * unit).abs() <= 3.0 * max_var
        && (c[3] as f64 - unit).abs() <= max_var
        && (c[4] as f64 - unit).abs() <= max_var
}

fn vertical_cross_check(
    gray: &[u8],
    width: usize,
    height: usize,
    cx: usize,
    start_y: usize,
    threshold: u8,
    expected_total: usize,
) -> Option<f64> {
    if gray[start_y * width + cx] >= threshold {
        return None;
    }
    let mut y = start_y;
    let mut c2_up = 0usize;
    while y > 0 && gray[(y - 1) * width + cx] < threshold {
        y -= 1;
        c2_up += 1;
    }
    let mut c1 = 0usize;
    while y > 0 && gray[(y - 1) * width + cx] >= threshold {
        y -= 1;
        c1 += 1;
    }
    let mut c0 = 0usize;
    while y > 0 && gray[(y - 1) * width + cx] < threshold {
        y -= 1;
        c0 += 1;
    }
    let top_y = y;

    let mut y2 = start_y;
    let mut c2_down = 1usize;
    while y2 + 1 < height && gray[(y2 + 1) * width + cx] < threshold {
        y2 += 1;
        c2_down += 1;
    }
    let mut c3 = 0usize;
    while y2 + 1 < height && gray[(y2 + 1) * width + cx] >= threshold {
        y2 += 1;
        c3 += 1;
    }
    let mut c4 = 0usize;
    while y2 + 1 < height && gray[(y2 + 1) * width + cx] < threshold {
        y2 += 1;
        c4 += 1;
    }
    let c2 = c2_up + c2_down;
    let counts = [c0, c1, c2, c3, c4];
    if !matches_finder_ratio(counts) {
        return None;
    }
    let total: usize = counts.iter().sum();
    if (total as i64 - expected_total as i64).abs() * 5 > expected_total as i64 * 2 {
        return None;
    }
    Some(top_y as f64 + (c0 + c1) as f64 + (c2 as f64) / 2.0)
}

fn add_or_merge_center(centers: &mut Vec<(f64, f64, f64)>, cx: f64, cy: f64, module: f64) {
    for (ex, ey, em) in centers.iter_mut() {
        if (*ex - cx).abs() <= *em * 2.5 && (*ey - cy).abs() <= *em * 2.5 {
            *ex = (*ex + cx) * 0.5;
            *ey = (*ey + cy) * 0.5;
            *em = (*em + module) * 0.5;
            return;
        }
    }
    centers.push((cx, cy, module));
}

/// Draws a standard 21x21 Version-1 QR pattern (3 finder patterns + timing + data modules)
/// scaled onto an RGB buffer at `(origin_x, origin_y)` for self-testing without OpenCV.
pub fn draw_synthetic_qr(
    rgb: &mut [u8],
    img_w: usize,
    img_h: usize,
    origin_x: usize,
    origin_y: usize,
    module_px: usize,
) {
    let mut grid = [[false; 21]; 21];
    let draw_finder = |g: &mut [[bool; 21]; 21], ox: usize, oy: usize| {
        for r in 0..7 {
            for c in 0..7 {
                let border = r == 0 || r == 6 || c == 0 || c == 6;
                let core = (2..=4).contains(&r) && (2..=4).contains(&c);
                g[oy + r][ox + c] = border || core;
            }
        }
    };
    draw_finder(&mut grid, 0, 0);
    draw_finder(&mut grid, 14, 0);
    draw_finder(&mut grid, 0, 14);
    for (i, row) in grid.iter_mut().enumerate().take(13).skip(8) {
        row[6] = i % 2 == 0;
    }
    for (i, cell) in grid[6].iter_mut().enumerate().take(13).skip(8) {
        *cell = i % 2 == 0;
    }
    // Deterministic checkerboard data payload inside data area.
    for (r, row) in grid.iter_mut().enumerate().take(20).skip(9) {
        for (c, cell) in row.iter_mut().enumerate().take(20).skip(9) {
            *cell = (r * 3 + c * 5) % 2 == 0;
        }
    }
    for (r, row) in grid.iter().enumerate() {
        for (c, &dark) in row.iter().enumerate() {
            if !dark {
                continue;
            }
            for dy in 0..module_px {
                for dx in 0..module_px {
                    let px = origin_x + c * module_px + dx;
                    let py = origin_y + r * module_px + dy;
                    if px < img_w && py < img_h {
                        let off = (py * img_w + px) * 3;
                        rgb[off] = 0;
                        rgb[off + 1] = 0;
                        rgb[off + 2] = 0;
                    }
                }
            }
        }
    }
}

/// Renders crisp uppercase/lowercase ASCII text using a built-in 5x7 bitmap font for self-test
/// OCR verification without OpenCV `putText`.
pub fn draw_bitmap_text(
    rgb: &mut [u8],
    img_w: usize,
    img_h: usize,
    text: &str,
    origin_x: usize,
    origin_y: usize,
    scale: usize,
) {
    let mut cursor_x = origin_x;
    for ch in text.chars() {
        let glyph = glyph_5x7(ch);
        for (row, &bits) in glyph.iter().enumerate() {
            for col in 0..5 {
                if (bits >> (4 - col)) & 1 == 1 {
                    for sy in 0..scale {
                        for sx in 0..scale {
                            let px = cursor_x + col * scale + sx;
                            let py = origin_y + row * scale + sy;
                            if px < img_w && py < img_h {
                                let off = (py * img_w + px) * 3;
                                rgb[off] = 0;
                                rgb[off + 1] = 0;
                                rgb[off + 2] = 0;
                            }
                        }
                    }
                }
            }
        }
        cursor_x += 6 * scale;
    }
}

fn glyph_5x7(ch: char) -> [u8; 7] {
    match ch.to_ascii_lowercase() {
        'a' => [
            0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001,
        ],
        'c' => [
            0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110,
        ],
        'e' => [
            0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111,
        ],
        'l' => [
            0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111,
        ],
        'm' => [
            0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001,
        ],
        'o' => [
            0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110,
        ],
        'p' => [
            0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000,
        ],
        'r' => [
            0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001,
        ],
        's' => [
            0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110,
        ],
        'u' => [
            0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110,
        ],
        'x' => [
            0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001,
        ],
        '@' => [
            0b01110, 0b10001, 0b10111, 0b10101, 0b10111, 0b10000, 0b01110,
        ],
        '.' => [
            0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100,
        ],
        _ => [0; 7],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_synthetic_qr_bounding_box() {
        let (w, h) = (800, 360);
        let mut rgb = vec![255u8; w * h * 3];
        draw_synthetic_qr(&mut rgb, w, h, 540, 110, 10);
        let gray: Vec<u8> = rgb.chunks_exact(3).map(|p| p[0]).collect();
        let boxes = detect_qr_regions(&gray, w, h);
        assert_eq!(boxes.len(), 1, "expected 1 QR region, got {boxes:?}");
        let (l, t, r, b) = boxes[0];
        assert!(
            l <= 550 && t <= 120 && r >= 740 && b >= 310,
            "box={boxes:?}"
        );
    }
}
