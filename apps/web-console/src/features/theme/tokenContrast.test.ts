import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/**
 * WCAG 1.4.3 regression gate for the design tokens in src/index.css.
 *
 * Every text token must keep at least a 4.5:1 contrast ratio against every
 * background surface it can appear on, in both the dark and the light theme.
 * The values are parsed from the real stylesheet so any token edit that
 * degrades contrast fails this test instead of shipping silently.
 */

const CSS_PATH = new URL('../../index.css', import.meta.url);

const TEXT_TOKENS = ['text-primary', 'text-secondary', 'text-muted'] as const;
const SURFACE_TOKENS = [
  'canvas',
  'sidebar',
  'surface-1',
  'surface-2',
  'surface-3',
] as const;

const MINIMUM_CONTRAST = 4.5;

type LinearRgb = readonly [number, number, number];

function extractBlock(css: string, opener: RegExp): string {
  const match = opener.exec(css);
  if (!match) {
    throw new Error(`theme block not found: ${opener.source}`);
  }
  let depth = 0;
  for (let i = match.index; i < css.length; i += 1) {
    const character = css.charAt(i);
    if (character === '{') depth += 1;
    if (character === '}') {
      depth -= 1;
      if (depth === 0) {
        return css.slice(match.index, i + 1);
      }
    }
  }
  throw new Error(`unterminated theme block: ${opener.source}`);
}

function parseTokens(block: string): Map<string, string> {
  const tokens = new Map<string, string>();
  for (const match of block.matchAll(/--color-([a-z0-9-]+):\s*([^;]+);/g)) {
    const [, name, value] = match;
    if (name && value) {
      tokens.set(name, value.trim());
    }
  }
  return tokens;
}

function srgbChannelToLinear(channel: number): number {
  return channel <= 0.04045
    ? channel / 12.92
    : ((channel + 0.055) / 1.055) ** 2.4;
}

function hexToLinearRgb(value: string): LinearRgb {
  const hex = value.slice(1);
  if (hex.length !== 6) {
    throw new Error(`unsupported hex color syntax: ${value}`);
  }
  const red = parseInt(hex.slice(0, 2), 16) / 255;
  const green = parseInt(hex.slice(2, 4), 16) / 255;
  const blue = parseInt(hex.slice(4, 6), 16) / 255;
  return [
    srgbChannelToLinear(red),
    srgbChannelToLinear(green),
    srgbChannelToLinear(blue),
  ];
}

function clampChannel(channel: number): number {
  return Math.min(1, Math.max(0, channel));
}

/** OKLCH -> OKLab -> linear sRGB (Björn Ottosson's reference matrices). */
function oklchToLinearRgb(value: string): LinearRgb {
  const match = /^oklch\(\s*([\d.]+)(%?)\s+([\d.]+)\s+([\d.]+)\s*\)$/.exec(
    value
  );
  if (!match) {
    throw new Error(`unsupported oklch() syntax: ${value}`);
  }
  const lightness = Number(match[1] ?? '') / (match[2] === '%' ? 100 : 1);
  const chroma = Number(match[3] ?? '');
  const hueRadians = (Number(match[4] ?? '') * Math.PI) / 180;
  if (
    !Number.isFinite(lightness) ||
    !Number.isFinite(chroma) ||
    !Number.isFinite(hueRadians)
  ) {
    throw new Error(`unsupported oklch() components: ${value}`);
  }
  const a = chroma * Math.cos(hueRadians);
  const b = chroma * Math.sin(hueRadians);

  const l = (lightness + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m = (lightness - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (lightness - 0.0894841775 * a - 1.291485548 * b) ** 3;

  return [
    clampChannel(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
    clampChannel(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
    clampChannel(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s),
  ];
}

function toLinearRgb(value: string): LinearRgb {
  if (value.startsWith('#')) return hexToLinearRgb(value);
  if (value.startsWith('oklch(')) return oklchToLinearRgb(value);
  throw new Error(
    `unsupported color syntax for contrast gate: ${value} ` +
      '(extend toLinearRgb before changing token formats)'
  );
}

function relativeLuminance([r, g, b]: LinearRgb): number {
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrastRatio(foreground: LinearRgb, background: LinearRgb): number {
  const lighter = Math.max(
    relativeLuminance(foreground),
    relativeLuminance(background)
  );
  const darker = Math.min(
    relativeLuminance(foreground),
    relativeLuminance(background)
  );
  return (lighter + 0.05) / (darker + 0.05);
}

function requireToken(tokens: Map<string, string>, name: string): string {
  const value = tokens.get(name);
  if (!value) {
    throw new Error(`missing --color-${name} token`);
  }
  return value;
}

const css = readFileSync(CSS_PATH, 'utf8');
const darkTokens = parseTokens(extractBlock(css, /@theme\s*\{/));
const lightOverrides = parseTokens(
  extractBlock(css, /html\[data-theme='light'\]\s*\{/)
);
// The light theme only overrides tokens; anything missing falls back to dark.
const lightTokens = new Map([...darkTokens, ...lightOverrides]);

const themes = [
  ['dark', darkTokens],
  ['light', lightTokens],
] as const;

describe('theme token contrast gate', () => {
  it('defines every audited text and surface token in both themes', () => {
    for (const [theme, tokens] of themes) {
      for (const token of [...TEXT_TOKENS, ...SURFACE_TOKENS]) {
        expect(tokens.get(token), `${theme} --color-${token}`).toBeTruthy();
      }
    }
  });

  for (const [theme, tokens] of themes) {
    for (const textToken of TEXT_TOKENS) {
      for (const surfaceToken of SURFACE_TOKENS) {
        it(`keeps ${theme} ${textToken} on ${surfaceToken} at >= ${MINIMUM_CONTRAST}:1`, () => {
          const ratio = contrastRatio(
            toLinearRgb(requireToken(tokens, textToken)),
            toLinearRgb(requireToken(tokens, surfaceToken))
          );
          expect(
            ratio,
            `${theme} --color-${textToken} on --color-${surfaceToken} ` +
              `is ${ratio.toFixed(2)}:1, below WCAG AA ${MINIMUM_CONTRAST}:1`
          ).toBeGreaterThanOrEqual(MINIMUM_CONTRAST);
        });
      }
    }
  }
});
