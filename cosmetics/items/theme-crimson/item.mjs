// Crimson menu theme: recolours the owner's Origin menus (see FORMAT.md "Themes"). texture.png is the
// store preview: a pixel-exact 480x300 mock of the real mod menu (cosmetics/FORMAT.md; layout from
// OriginDeck / OriginModMenuScreen: header with mark + tabs + Edit HUD, filter chips + search, an
// options column with toggle rows, a slider and the primary button, and a grid of mod cards). Every
// colour below is derived from the eight theme tokens exactly as OriginDeck.applyTheme derives it.
// Palette: cool plum-black shadows, warm cream lights, vivid crimson accent.
// Drawn at 1x integer pixels with a 5x7 bitmap font, so nothing is resampled or anti-aliased.
import { Canvas, mix, toHex, rgba } from '../../lib/px.mjs';

const THEME = { bg: "#130d0f", card: "#221619", line: "#43292f", text: "#f6e8dd", dim: "#c6aaa8", acc: "#f0384e", accHi: "#ff94a2", onAcc: "#1c0409" };

// ---- palette: the eight tokens plus what OriginDeck derives from them ----
const over = (top, bot, a) => mix(bot, top, a);                      // top at alpha a over bot
const P = (() => {
  const t = THEME;
  const dimEff = over(t.dim, t.card, 0xE0 / 255);                    // game draws DIM at alpha .88
  return {
    ...t, panel: t.bg, cardH: mix(t.card, t.text, 0.035), dimEff,
    mute: over(t.dim, t.card, 0x8C / 255),
    accD: mix(t.acc, t.bg, 0.5), onText: mix(t.accHi, '#ffffff', 0.6),
    line2: mix(t.line, t.bg, 0.2),
    gold: '#e9c46a', goldBg: '#3a3220',
  };
})();

// ---- 5x7 bitmap font (uppercase, digits, a little punctuation); proportional by trimming blank columns ----
const FONT_SRC = {
  A: '01110 10001 10001 11111 10001 10001 10001', B: '11110 10001 10001 11110 10001 10001 11110',
  C: '01110 10001 10000 10000 10000 10001 01110', D: '11110 10001 10001 10001 10001 10001 11110',
  E: '11111 10000 10000 11110 10000 10000 11111', F: '11111 10000 10000 11110 10000 10000 10000',
  G: '01110 10001 10000 10111 10001 10001 01111', H: '10001 10001 10001 11111 10001 10001 10001',
  I: '01110 00100 00100 00100 00100 00100 01110', J: '00111 00010 00010 00010 00010 10010 01100',
  K: '10001 10010 10100 11000 10100 10010 10001', L: '10000 10000 10000 10000 10000 10000 11111',
  M: '10001 11011 10101 10101 10001 10001 10001', N: '10001 11001 10101 10011 10001 10001 10001',
  O: '01110 10001 10001 10001 10001 10001 01110', P: '11110 10001 10001 11110 10000 10000 10000',
  Q: '01110 10001 10001 10001 10101 10010 01101', R: '11110 10001 10001 11110 10100 10010 10001',
  S: '01111 10000 10000 01110 00001 00001 11110', T: '11111 00100 00100 00100 00100 00100 00100',
  U: '10001 10001 10001 10001 10001 10001 01110', V: '10001 10001 10001 10001 10001 01010 00100',
  W: '10001 10001 10001 10101 10101 11011 10001', X: '10001 10001 01010 00100 01010 10001 10001',
  Y: '10001 10001 01010 00100 00100 00100 00100', Z: '11111 00001 00010 00100 01000 10000 11111',
  0: '01110 10001 10011 10101 11001 10001 01110', 1: '00100 01100 00100 00100 00100 00100 01110',
  2: '01110 10001 00001 00010 00100 01000 11111', 3: '11110 00001 00001 01110 00001 00001 11110',
  4: '00010 00110 01010 10010 11111 00010 00010', 5: '11111 10000 11110 00001 00001 10001 01110',
  6: '00110 01000 10000 11110 10001 10001 01110', 7: '11111 00001 00010 00100 01000 01000 01000',
  8: '01110 10001 10001 01110 10001 10001 01110', 9: '01110 10001 10001 01111 00001 00010 01100',
  '.': '00000 00000 00000 00000 00000 00000 00100', '/': '00001 00001 00010 00100 01000 10000 10000',
  '-': '00000 00000 00000 11111 00000 00000 00000',
};
const GLYPH = {};
for (const [ch, s] of Object.entries(FONT_SRC)) {
  const rows = s.split(' ');
  let lo = 4, hi = 0;
  for (const r of rows) for (let x = 0; x < 5; x++) if (r[x] === '1') { lo = Math.min(lo, x); hi = Math.max(hi, x); }
  GLYPH[ch] = { rows, lo, w: hi - lo + 1 };
}
const textW = (s, track = 0) => [...s].reduce((n, ch, i) => n + (ch === ' ' ? 3 : GLYPH[ch].w) + (i ? 1 + track : 0), 0);
function text(c, s, x, y, col, track = 0) {
  let cx = x;
  for (const ch of s) {
    if (ch === ' ') { cx += 3 + 1 + track; continue; }
    const g = GLYPH[ch];
    for (let j = 0; j < 7; j++) for (let i = 0; i < g.w; i++) if (g.rows[j][g.lo + i] === '1') c.set(cx + i, y + j, col);
    cx += g.w + 1 + track;
  }
  return cx - 1 - track;
}
const textMid = (c, s, cx, y, col, track = 0) => text(c, s, Math.round(cx - textW(s, track) / 2), y, col, track);

// ---- shapes: corner radius r cuts the pixels with i + j < r (a crisp 1/2/3 px rounding) ----
function rr(c, x, y, w, h, r, col) {
  for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) {
    const ci = Math.min(i, w - 1 - i), cj = Math.min(j, h - 1 - j);
    if (ci < r && cj < r && ci + cj < r) continue;
    c.set(x + i, y + j, col);
  }
}
function rrBorder(c, x, y, w, h, r, fill, border) {
  rr(c, x, y, w, h, r, border);
  rr(c, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
}
function icon(c, rows, cx, y, col) {
  const w = rows[0].length, x0 = cx - Math.floor(w / 2);
  rows.forEach((r, j) => [...r].forEach((ch, i) => { if (ch === '#') c.set(x0 + i, y + j, col); }));
}
const ICONS = {
  shield: ['.#######.', '##.....##', '#.......#', '#.......#', '#.......#', '#.......#', '##.....##', '.#.....#.', '..#...#..', '...#.#...', '....#....'],
  cup: ['#########', '#.......#', '##.....##', '.#.....#.', '..#...#..', '...###...', '....#....', '....#....', '..#####..'],
  square: ['.#######.', '#.......#', '#.......#', '#.......#', '#.......#', '#.......#', '#.......#', '#.......#', '.#######.'],
  medal: ['..#####..', '.#.....#.', '#.......#', '#.......#', '#.......#', '.#.....#.', '..#####..', '...#.#...', '...#.#...', '....#....'],
  chat: ['.#########.', '#.........#', '#.........#', '#.........#', '#.........#', '.####.####.', '...##......', '..#........'],
  clock: ['..#####..', '.#..#..#.', '#...#...#', '#...#...#', '#...###.#', '#.......#', '#.......#', '.#.....#.', '..#####..'],
  gear: ['...###...', '.#.###.#.', '.#######.', '###...###', '###...###', '###...###', '.#######.', '.#.###.#.', '...###...'],
};

// ---- the Origin mark, 1 px stroke, rasterised at its on-screen size (3 rings 0/60/120 deg, real ring ratios) ----
function mark(c, cx, cy, R, col) {
  for (const [rx, ry, deg] of [[90, 32, 0], [75, 27, 60], [60, 22, 120]]) {
    const a = (R * rx) / 90, b = (R * ry) / 90 * 1.1, th = (deg * Math.PI) / 180, co = Math.cos(th), si = Math.sin(th);
    for (let i = 0; i < 6000; i++) {
      const t = (i / 6000) * 2 * Math.PI, u = a * Math.cos(t), v = b * Math.sin(t);
      c.set(cx + Math.round(u * co - v * si), cy + Math.round(u * si + v * co), col);
    }
  }
}

// ---- layout (px, 480x300) ----
const W = 480, H = 300;
const PX = 24, PY = 20, PW = 432, PH = 260;            // the menu panel
const HEAD = 29;                                       // header height

function paint() {
  const c = new Canvas(W, H);

  // World behind the menu: darkened and cool, a plain vertical gradient in whole-pixel bands.
  const top = mix(P.bg, '#000000', 0.62), bot = mix(P.bg, '#000000', 0.38);
  for (let y = 0; y < H; y++) c.rect(0, y, W, 1, mix(top, bot, y / (H - 1)));

  // Panel: cool drop shadow, border, body.
  rr(c, PX - 1, PY + 3, PW + 2, PH, 3, mix(P.bg, '#000000', 0.75));
  rrBorder(c, PX, PY, PW, PH, 3, P.panel, P.line2);
  c.rect(PX + 1, PY + HEAD, PW - 2, 1, P.line);                 // header rule

  // -- header: mark + wordmark, tabs, Edit HUD, close
  mark(c, PX + 21, PY + 15, 10, P.text);
  const bx = PX + 37;
  const e1 = text(c, 'ORIGIN', bx, PY + 11, P.text);
  text(c, 'CLIENT', e1 + 5, PY + 11, P.dimEff);

  const tabs = ['MODS', 'SETTINGS', 'COSMETICS'];
  const tw = tabs.map(t => textW(t, 1) + 12);
  const tabsW = tw.reduce((a, b) => a + b, 0) + 3 * (tabs.length - 1);
  let tx = PX + 124;
  tabs.forEach((t, i) => {
    if (i === 0) rr(c, tx, PY + 7, tw[i], 15, 2, mix(P.card, P.text, 0.06));
    textMid(c, t, tx + tw[i] / 2, PY + 11, i === 0 ? P.text : P.dimEff, 1);
    tx += tw[i] + 3;
  });
  const closeX = PX + PW - 10 - 15, ehW = textW('EDIT HUD', 1) + 22, ehX = closeX - 5 - ehW;
  if (tx - 3 > ehX - 6) throw new Error('header overflow: tabs reach ' + (tx - 3) + ' past ' + (ehX - 6));
  rr(c, ehX, PY + 7, ehW, 15, 2, P.acc);
  icon(c, ['#######', '#.....#', '#######', '#..#..#', '#..#..#', '#######'].map(r => r), ehX + 9, PY + 11, P.onAcc);
  textMid(c, 'EDIT HUD', ehX + 14 + (ehW - 14) / 2, PY + 11, P.onAcc, 1);
  rr(c, closeX, PY + 7, 15, 15, 2, mix(P.card, P.text, 0.05));
  c.line(closeX + 5, PY + 11, closeX + 9, PY + 15, P.dimEff); c.line(closeX + 9, PY + 11, closeX + 5, PY + 15, P.dimEff);

  // -- toolbar: profile, filter chips, view toggles, search
  const ty = PY + HEAD + 7;
  rrBorder(c, PX + 10, ty, 58, 15, 2, mix(P.card, P.text, 0.03), P.line);
  text(c, 'DEFAULT', PX + 17, ty + 4, P.text);
  let cx = PX + 77;
  const chips = [['ALL 63', 'on'], ['NEW', 'gold'], ['HUD', ''], ['VISUAL', '']];
  chips.forEach(([t, k]) => {
    const w = textW(t, 1) + 12;
    rr(c, cx, ty, w, 15, 2, k === 'on' ? mix(P.card, P.acc, 0.2) : k === 'gold' ? P.goldBg : mix(P.card, P.text, 0.05));
    text(c, t, cx + 6, ty + 4, k === 'on' ? P.acc : k === 'gold' ? P.gold : P.dimEff, 1);
    cx += w + 3;
  });
  const sw = 104, sx = PX + PW - 10 - sw;
  rrBorder(c, sx, ty, sw, 15, 2, mix(P.bg, '#000000', 0.28), P.line);
  c.ellipse(sx + 9, ty + 7, 3, 3, P.mute); c.line(sx + 11, ty + 9, sx + 13, ty + 11, P.mute);
  text(c, 'SEARCH MODS', sx + 18, ty + 4, P.mute);

  // -- left column: options with toggle rows, a slider, the primary button
  const lx = PX + 10, lw = 136, by = ty + 24;
  text(c, 'GENERAL', lx + 1, by, P.dimEff, 1);
  const rows = [['SHOW FPS', true], ['SMOOTH ZOOM', false], ['SOLID MENU', true], ['SHOW PING', false]];
  rows.forEach(([label, on], i) => {
    const y = by + 12 + i * 31;
    rrBorder(c, lx, y, lw, 26, 3, P.card, P.line);
    text(c, label, lx + 9, y + 9, P.text);
    const sx2 = lx + lw - 9 - 20, sy = y + 8;
    rr(c, sx2, sy, 20, 10, 5, on ? P.acc : mix(P.card, P.text, 0.16));
    c.disc(sx2 + (on ? 15 : 5), sy + 5, 3, on ? P.onText : P.dimEff);
  });
  const sy0 = by + 12 + rows.length * 31;
  rrBorder(c, lx, sy0, lw, 30, 3, P.card, P.line);
  text(c, 'UI SCALE', lx + 9, sy0 + 6, P.text);
  const val = '100', vx = lx + lw - 9 - textW(val);
  text(c, val, vx, sy0 + 6, P.dimEff);
  c.rect(lx + 9, sy0 + 21, lw - 18, 2, mix(P.card, P.text, 0.16));
  c.rect(lx + 9, sy0 + 21, 70, 2, P.acc);
  c.disc(lx + 9 + 70, sy0 + 22, 4, P.text);
  const pbY = PY + PH - 10 - 20;

  // -- right: mod card grid (the third row peeks out and is clipped by the panel, like the real menu)
  const gx = lx + lw + 12, cw = 82, ch = 73, gy = by - 4;
  const cards = [
    ['ARMOR STATUS', 'shield', true], ['AUTO GG', 'cup', false], ['BLOCK', 'square', false],
    ['BOSS BAR', 'medal', true], ['CHAT', 'chat', false], ['CLOCK', 'clock', false],
    ['', 'cup', false], ['', 'shield', false], ['', 'medal', false],
  ];
  // A window onto the panel body: the third row of cards is clipped at its bottom edge.
  const bodyTop = PY + HEAD + 1, bodyH = PY + PH - 9 - bodyTop;
  const grid = c.view(PX + 1, bodyTop, PW - 2, bodyH);
  const ox = PX + 1, oy = bodyTop;
  cards.forEach(([name, ic, on], i) => {
    const col = i % 3, row = (i / 3) | 0, x = gx - ox + col * (cw + 10), y = gy - oy + row * (ch + 8);
    const hover = i === 4;                                           // one hovered card shows the hover tone
    const fill = hover ? P.cardH : P.card;
    rrBorder(grid, x, y, cw, ch, 3, fill, hover ? P.line2 : P.line);
    icon(grid, ICONS[ic], x + cw / 2, y + 9, on ? P.text : P.dimEff);
    if (!name) return;
    textMid(grid, name, x + cw / 2, y + 27, on ? P.text : P.dimEff);
    rr(grid, x + 4, y + 41, cw - 8, 13, 2, mix(fill, P.text, 0.05));
    icon(grid, ICONS.gear, x + 17, y + 43, P.dimEff);
    textMid(grid, 'OPTIONS', x + cw / 2 + 7, y + 44, P.dimEff, 1);
    const barFill = on ? (hover ? mix(P.acc, P.bg, 0.42) : P.accD) : over(P.text, fill, hover ? 0x24 / 255 : 0x14 / 255);
    rr(grid, x + 4, y + 57, cw - 8, 11, 2, barFill);
    textMid(grid, on ? 'ENABLED' : 'DISABLED', x + cw / 2, y + 59, on ? P.onText : over(P.dim, barFill, 0xE0 / 255), 1);
  });
  // primary button
  rr(c, lx, pbY, lw, 20, 3, P.acc);
  c.rect(lx + 3, pbY, lw - 6, 1, mix(P.acc, P.accHi, 0.55));           // lit top edge
  icon(c, ICONS.gear, lx + 24, pbY + 5, P.onAcc);
  textMid(c, 'EDIT POSITION', lx + lw / 2 + 8, pbY + 6, P.onAcc, 1);
  // scrollbar
  c.rect(PX + PW - 6, gy + 2, 2, 60, mix(P.bg, P.text, 0.22));
  return c;
}

export default {
  meta: { id: 'theme-crimson', name: 'Crimson', kind: 'theme', cents: 599, blurb: 'Dark wine surfaces with a warm cream voice and a vivid crimson accent.' },
  theme: THEME,
  paint,
};
