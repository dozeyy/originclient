// Menu themes, done the way Origin's own menus are coloured.
//
// The main (default) theme IS the DEF_ colour tokens in OriginDeck.java (the mod menu,
// Cosmetics screen and every deck-drawn screen) and OriginTheme.java (the rest of Origin's
// screens). Those tokens were lifted from the design mock the menu was built from
// (design/menu-redesign/mock/mock4.tpl.html: --panel, --card, --acc ...). A theme item is
// that same token set re-tinted, never a separate palette with its own rules:
//
//   - accent tokens (ACC, ACC_D, ACCENT_2, the switch, hover strokes ...) move from the
//     main accent #5FD09B to the theme's `accent`,
//   - surface tokens (PANEL, CARD, BG, BOX_FILL ...) move from the main card #1D2320 to the
//     theme's `surface`,
//   - type tokens (TEXT, DIM, MUTE ...) move from #F1E9D2 to the theme's `text` (if given),
//
// each by the same OKLCH shift (hue rotated, chroma and lightness scaled; OKLCH is
// perceptual, so a violet ENABLED bar is exactly as calm as the main green one), so every
// relationship the main theme has (how much darker the ENABLED bar is than the accent,
// how far the card sits above the panel, every alpha) carries over unchanged. White
// hairlines (LINE, LINE2) and the gold / red / info colours are not themed. Feeding the
// main theme's own anchors back in returns the DEF_ values exactly (build.mjs checks it).
//
// The result is written into item.json as { deck: {TOKEN: hex}, workbench: {TOKEN: hex} };
// the mod (ThemeColors / OriginDeck.applyTheme / OriginTheme.applyTheme) only assigns
// those values, so the game and the store preview can never derive differently.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const GUI = 'src/mods/versions/1.21.1/src/client/java/com/origin/client/client';
const SRC = { deck: `${GUI}/gui/OriginDeck.java`, workbench: `${GUI}/theme/OriginTheme.java` };

// Which DEF_ tokens follow which anchor. Anything not listed keeps its main-theme value.
const FAMILY = {
  deck: {
    surface: ['PANEL', 'CARD', 'CARD_H'],
    text: ['TEXT', 'DIM', 'MUTE'],
    accent: ['ACC', 'ACC_D', 'ACC_SOFT', 'ON_ACC', 'ON_TEXT', 'ACC_D_H'],
  },
  workbench: {
    surface: ['BG', 'BG_ALT', 'PANEL', 'PANEL_TRANSLUCENT', 'PANEL_ALT', 'STROKE', 'STROKE_STRONG',
      'BOX_FILL', 'BOX_FILL_HOVER', 'BOX_BORDER', 'SWITCH_OFF'],
    text: ['TEXT', 'TEXT_DIM', 'MUTED', 'SWITCH_KNOB'],
    accent: ['ACCENT', 'ACCENT_2', 'ACCENT_GLOW', 'ACCENT_DIM', 'ACCENT_SOFT', 'ACCENT_BORDER',
      'STROKE_HOVER', 'BOX_BORDER_HOVER', 'SWITCH_ON'],
  },
};
export const MAIN = { surface: '#1d2320', text: '#f1e9d2', accent: '#5fd09b' };

// 0xAARRGGBB ints keyed by token name, read from `private static final int DEF_X = ...;`
// (a value may name another constant, e.g. DEF_SWITCH_ON = SUCCESS).
function readDefaults(file) {
  const src = fs.readFileSync(path.join(ROOT, file), 'utf8');
  const all = new Map();
  for (const m of src.matchAll(/static final int (\w+)\s*=\s*([^;]+);/g)) all.set(m[1], m[2].trim());
  const val = name => {
    const v = all.get(name);
    if (v === undefined) throw new Error(`${file}: no constant ${name}`);
    return /^0x[0-9a-f]+$/i.test(v) ? Number.parseInt(v.slice(2), 16) >>> 0 : val(v);
  };
  const out = {};
  for (const name of all.keys()) if (name.startsWith('DEF_')) out[name.slice(4)] = val(name);
  return out;
}
let defaults;
export function mainTokens() {
  defaults ??= { deck: readDefaults(SRC.deck), workbench: readDefaults(SRC.workbench) };
  return defaults;
}

const argbToHex = v => {
  const a = (v >>> 24) & 255, rgb = (v & 0xFFFFFF).toString(16).padStart(6, '0');
  return '#' + rgb + (a === 255 ? '' : a.toString(16).padStart(2, '0'));
};
const hexRgb = h => { const n = Number.parseInt(h.slice(1, 7), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };

// sRGB <-> OKLCH (Björn Ottosson's OKLab). L 0..1, C >= 0, h degrees.
const lin = v => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
const gam = v => (v <= 0.0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - 0.055);
function toOklch([r8, g8, b8]) {
  const [r, g, b] = [r8, g8, b8].map(v => lin(v / 255));
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  const L = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s;
  const A = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s;
  const B = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s;
  return [L, Math.hypot(A, B), ((Math.atan2(B, A) * 180) / Math.PI + 360) % 360];
}
function oklchRgb(L, C, h) {
  const A = C * Math.cos((h * Math.PI) / 180), B = C * Math.sin((h * Math.PI) / 180);
  const l = (L + 0.3963377774 * A + 0.2158037573 * B) ** 3;
  const m = (L - 0.1055613458 * A - 0.0638541728 * B) ** 3;
  const s = (L - 0.0894841775 * A - 1.291485548 * B) ** 3;
  return [4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s];
}
// The colour at L/h with the most chroma up to C that sRGB can show (binary search).
function fit(L, C, h) {
  const ok = c => oklchRgb(L, c, h).every(v => v >= -1e-4 && v <= 1 + 1e-4);
  let lo = 0, hi = C;
  if (!ok(hi)) { for (let i = 0; i < 24; i++) { const mid = (lo + hi) / 2; if (ok(mid)) lo = mid; else hi = mid; } hi = lo; }
  return oklchRgb(L, hi, h).map(v => Math.round(gam(Math.min(1, Math.max(0, v))) * 255));
}

// Move colour v the way anchor `from` moves to `to`: hue rotated, chroma and lightness
// scaled by the anchors' ratios (OKLCH). Alpha is kept. Identity when from == to.
function retint(v, from, to) {
  if (from.toLowerCase() === to.toLowerCase()) return v >>> 0;
  const [fL, fC, fh] = toOklch(hexRgb(from)), [tL, tC, th] = toOklch(hexRgb(to));
  const a = (v >>> 24) & 255;
  const [L, C, h] = toOklch([(v >> 16) & 255, (v >> 8) & 255, v & 255]);
  // Lightness above the anchor scales toward white instead of past it, so a light
  // token (ON_TEXT) stays light when the accent itself gets lighter.
  const nL = L <= fL ? L * tL / fL : tL + (L - fL) * (1 - tL) / Math.max(1e-4, 1 - fL);
  const nC = fC > 1e-4 ? C * tC / fC : C;
  const [r, g, b] = fit(Math.min(1, Math.max(0, nL)), nC, (h + th - fh + 360) % 360);
  return ((a << 24) | (r << 16) | (g << 8) | b) >>> 0;
}

/**
 * spec: { accent, surface, text?, off? } (hex). off: 'red' keeps the main theme's red
 * DISABLED bar; 'neutral' (for red/pink accents, where red DISABLED would read as the
 * accent) makes it a quiet text-tinted bar. Returns { deck, workbench } of '#rrggbb[aa]'.
 */
export function themeTokens(spec) {
  const main = mainTokens();
  const to = { surface: spec.surface ?? MAIN.surface, text: spec.text ?? MAIN.text, accent: spec.accent ?? MAIN.accent };
  const out = {};
  for (const set of ['deck', 'workbench']) {
    const t = {};
    for (const [fam, names] of Object.entries(FAMILY[set])) {
      for (const n of names) {
        if (main[set][n] === undefined) throw new Error(`${SRC[set]}: no DEF_${n}`);
        t[n] = retint(main[set][n], MAIN[fam], to[fam]);
      }
    }
    out[set] = t;
  }
  if ((spec.off ?? 'red') === 'neutral') {
    const text = out.deck.TEXT & 0xFFFFFF;
    out.deck.OFF = ((0xAD << 24) | text) >>> 0;        // = DIM: text at .68
    out.deck.OFF_SOFT = ((0x14 << 24) | text) >>> 0;   // text .08
    out.deck.OFF_SOFT_H = ((0x24 << 24) | text) >>> 0; // text .14
  }
  for (const set of ['deck', 'workbench']) for (const n in out[set]) out[set][n] = argbToHex(out[set][n]);
  return out;
}

/** The main theme expressed like a theme item (build.mjs asserts it equals the DEF_ values). */
export function checkIdentity() {
  const got = themeTokens(MAIN), main = mainTokens(), bad = [];
  for (const set of ['deck', 'workbench']) {
    for (const [n, hex] of Object.entries(got[set])) if (hex !== argbToHex(main[set][n])) bad.push(`${set}.${n} ${hex} != ${argbToHex(main[set][n])}`);
  }
  return bad;
}

// The design mock's CSS variables for a token set (preview rendering; see theme-preview.mjs).
export function mockCss(tokens) {
  const d = tokens.deck;
  const css = h => {
    const [r, g, b] = hexRgb(h), a = h.length > 7 ? Number.parseInt(h.slice(7, 9), 16) / 255 : 1;
    return a >= 1 ? h : `rgba(${r},${g},${b},${+a.toFixed(3)})`;
  };
  return {
    '--panel': css(d.PANEL), '--card': css(d.CARD), '--card-h': css(d.CARD_H),
    '--text': css(d.TEXT), '--dim': css(d.DIM), '--mute': css(d.MUTE),
    '--acc': css(d.ACC), '--acc-d': css(d.ACC_D), '--acc-soft': css(d.ACC_SOFT),
    '--on-acc': css(d.ON_ACC), '--on-text': css(d.ON_TEXT),
    '--off': css(d.OFF ?? '#d9675a'), '--off-soft': css(d.OFF_SOFT ?? '#d9675a29'),
  };
}
