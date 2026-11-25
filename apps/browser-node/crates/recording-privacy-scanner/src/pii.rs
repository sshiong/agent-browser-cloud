//! Deterministic ASCII PII & Luhn credit-card signal detector matching
//! `recording_privacy_scanner.py` rules with zero external regex dependencies.

pub fn luhn_valid(candidate: &str) -> bool {
    let digits: Vec<u32> = candidate.chars().filter_map(|c| c.to_digit(10)).collect();
    if !(13..=19).contains(&digits.len()) {
        return false;
    }
    if digits.iter().all(|&d| d == digits[0]) {
        return false;
    }
    let mut total = 0u32;
    let parity = digits.len() % 2;
    for (idx, &d) in digits.iter().enumerate() {
        let mut v = d;
        if idx % 2 == parity {
            v *= 2;
            if v > 9 {
                v -= 9;
            }
        }
        total += v;
    }
    total.is_multiple_of(10)
}

pub fn has_email(text: &str) -> bool {
    let bytes = text.as_bytes();
    for (i, &b) in bytes.iter().enumerate() {
        if b != b'@' {
            continue;
        }
        let mut l = i;
        while l > 0 && is_email_local(bytes[l - 1]) {
            l -= 1;
        }
        let mut r = i + 1;
        while r < bytes.len() && is_email_domain(bytes[r]) {
            r += 1;
        }
        if l < i && r > i + 1 {
            let domain = &text[i + 1..r];
            if let Some(dot) = domain.rfind('.') {
                let tld = &domain[dot + 1..];
                if dot > 0 && tld.len() >= 2 && tld.bytes().all(|c| c.is_ascii_alphabetic()) {
                    return true;
                }
            }
        }
    }
    false
}

fn is_email_local(b: u8) -> bool {
    b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'%' | b'+' | b'-')
}

fn is_email_domain(b: u8) -> bool {
    b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-')
}

pub fn has_phone(text: &str) -> bool {
    let bytes = text.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i].is_ascii_digit()
            || (bytes[i] == b'+' && i + 1 < bytes.len() && bytes[i + 1].is_ascii_digit())
        {
            let start_prev_digit = i > 0 && bytes[i - 1].is_ascii_digit();
            if !start_prev_digit {
                let mut j = i;
                let mut digits = 0usize;
                while j < bytes.len()
                    && (bytes[j].is_ascii_digit()
                        || matches!(bytes[j], b'+' | b' ' | b'(' | b')' | b'.' | b'-'))
                {
                    if bytes[j].is_ascii_digit() {
                        digits += 1;
                    }
                    j += 1;
                }
                if (10..=15).contains(&digits) {
                    return true;
                }
                i = j;
                continue;
            }
        }
        i += 1;
    }
    false
}

pub fn has_ssn(text: &str) -> bool {
    let b = text.as_bytes();
    if b.len() < 11 {
        return false;
    }
    for i in 0..=b.len() - 11 {
        let prev_digit = i > 0 && b[i - 1].is_ascii_digit();
        let next_digit = i + 11 < b.len() && b[i + 11].is_ascii_digit();
        if !prev_digit
            && !next_digit
            && b[i].is_ascii_digit()
            && b[i + 1].is_ascii_digit()
            && b[i + 2].is_ascii_digit()
            && b[i + 3] == b'-'
            && b[i + 4].is_ascii_digit()
            && b[i + 5].is_ascii_digit()
            && b[i + 6] == b'-'
            && b[i + 7].is_ascii_digit()
            && b[i + 8].is_ascii_digit()
            && b[i + 9].is_ascii_digit()
            && b[i + 10].is_ascii_digit()
        {
            return true;
        }
    }
    false
}

pub fn has_secret_assignment(text: &str) -> bool {
    let lower = text.to_ascii_lowercase();
    for kw in ["api_key", "api-key", "api key", "apikey", "secret", "token"] {
        let mut start = 0;
        while let Some(pos) = lower[start..].find(kw) {
            let abs = start + pos;
            let after = &lower[abs + kw.len()..];
            let trimmed = after.trim_start();
            if let Some(rest) = trimmed
                .strip_prefix(':')
                .or_else(|| trimmed.strip_prefix('='))
            {
                let val = rest.trim_start();
                let val_len = val
                    .bytes()
                    .take_while(|c| {
                        c.is_ascii_alphanumeric() || matches!(c, b'.' | b'_' | b'/' | b'-')
                    })
                    .count();
                if val_len >= 8 {
                    return true;
                }
            }
            start = abs + kw.len();
        }
    }
    false
}

pub fn has_otp(text: &str) -> bool {
    let lower = text.to_ascii_lowercase();
    for kw in ["otp", "one-time", "one time", "onetime", "verification"] {
        let mut start = 0;
        while let Some(pos) = lower[start..].find(kw) {
            let abs = start + pos + kw.len();
            let window = &lower[abs..lower.len().min(abs + 40)];
            let bytes = window.as_bytes();
            let mut non_digit = 0usize;
            let mut i = 0;
            while i < bytes.len() && non_digit <= 24 {
                if bytes[i] == b'\n' {
                    break;
                }
                if bytes[i].is_ascii_digit() {
                    let mut d = 0;
                    while i + d < bytes.len() && bytes[i + d].is_ascii_digit() {
                        d += 1;
                    }
                    if (4..=10).contains(&d) {
                        return true;
                    }
                    break;
                }
                non_digit += 1;
                i += 1;
            }
            start = abs;
        }
    }
    false
}

pub fn has_jwt(text: &str) -> bool {
    let mut start = 0;
    while let Some(pos) = text[start..].find("eyJ") {
        let abs = start + pos;
        let cand = &text[abs..];
        let seg_len = cand
            .bytes()
            .take_while(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b'.'))
            .count();
        let token = &cand[..seg_len];
        let parts: Vec<&str> = token.split('.').collect();
        if parts.len() == 3 && parts[0].len() >= 15 && parts[1].len() >= 8 && parts[2].len() >= 8 {
            return true;
        }
        start = abs + 3;
    }
    false
}

pub fn has_luhn_card(text: &str) -> bool {
    let bytes = text.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i].is_ascii_digit() && (i == 0 || !bytes[i - 1].is_ascii_digit()) {
            let mut j = i;
            let mut digits = 0usize;
            while j < bytes.len() && (bytes[j].is_ascii_digit() || matches!(bytes[j], b' ' | b'-'))
            {
                if bytes[j].is_ascii_digit() {
                    digits += 1;
                }
                j += 1;
            }
            if (13..=19).contains(&digits) && luhn_valid(&text[i..j]) {
                return true;
            }
            i = j;
        } else {
            i += 1;
        }
    }
    false
}

pub fn sensitive_signal_count(text: &str) -> usize {
    usize::from(has_email(text))
        + usize::from(has_phone(text))
        + usize::from(has_ssn(text))
        + usize::from(has_secret_assignment(text))
        + usize::from(has_otp(text))
        + usize::from(has_jwt(text))
        + usize::from(has_luhn_card(text))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_all_pii_categories_and_ignores_benign_text() {
        assert_eq!(sensitive_signal_count("hello world dashboard 42"), 0);
        assert!(has_email("contact user@example.com now"));
        assert!(has_phone("call +1 (415) 555-2671"));
        assert!(has_ssn("ssn 123-45-6789"));
        assert!(has_secret_assignment("api_key=sk_live_1234567890"));
        assert!(has_otp("Verification code: 482910"));
        assert!(has_jwt(
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0In0.abcdefghij"
        ));
        assert!(has_luhn_card("card 4532 0151 1283 0366"));
        assert!(!has_luhn_card("card 4532 0151 1283 0367"));
    }
}
