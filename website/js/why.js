// "Why Origin" mini-game: six Minecraft blocks, each hiding one reason to pick
// Origin. Hit a block three times (click, tap, Enter/Space) to mine it: it
// cracks, shakes and bursts into particles coloured from its own texture,
// drops XP orbs into the XP bar, and turns into a card with the reason. Mine
// all six for a level-up + fireworks that point at the Download section.
//
// The plain comparison table in index.html stays as the no-JS / screen-reader
// fallback; this script hides it only once the game is built. Respects
// prefers-reduced-motion (no shake, no auto demo, fewer particles).

const REASONS = [
  { tex: 'glowstone', win: 'Shaders built in', them: 'Set it up yourself' },
  { tex: 'diamond_ore', win: 'Every account at once', them: 'One at a time' },
  { tex: 'crafting_table_front', win: 'Bring your own mods', them: 'Locked out' },
  { tex: 'emerald_ore', win: 'Settings follow you', them: 'Start over' },
  { tex: 'grass_side', win: 'FPS Coach fixes lag', them: 'Good luck' },
  { tex: 'oak_log', win: 'Code you can read', them: 'Closed' },
];
const HITS = 3;  // hits to break a block

const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
const section = document.getElementById('why');
if (section) build(section);

function build(sec) {
  const stage = sec.querySelector('[data-why-stage]');
  if (!stage) return;
  sec.classList.add('is-game');

  stage.innerHTML = `
    <canvas class="why__fx" aria-hidden="true"></canvas>
    <div class="why__grid" role="list"></div>
    <div class="why__hint" aria-hidden="true"><span class="why__pick"></span><span>Mine me</span></div>
    <div class="why__xp" aria-live="polite">
      <span class="why__lvl" data-lvl>0</span>
      <span class="why__bar"><span data-bar></span></span>
      <span class="why__count" data-count>0 / ${REASONS.length} mined</span>
    </div>
    <a class="btn btn--primary why__cta" href="#get" data-cta tabindex="-1">Get Origin</a>`;
  const grid = stage.querySelector('.why__grid');
  const canvas = stage.querySelector('.why__fx');
  const ctx = canvas.getContext('2d');
  const hint = stage.querySelector('.why__hint');
  const bar = stage.querySelector('[data-bar]');
  const lvl = stage.querySelector('[data-lvl]');
  const count = stage.querySelector('[data-count]');
  const cta = stage.querySelector('[data-cta]');

  let mined = 0, interacted = false, xpShown = 0, xpTarget = 0;
  const particles = [];

  // ---------------------------------------------------------------- blocks
  const cells = REASONS.map((r, i) => {
    const cell = document.createElement('div');
    cell.className = 'why__cell';
    cell.setAttribute('role', 'listitem');
    cell.style.setProperty('--i', i);
    cell.innerHTML = `
      <button class="why__block" type="button" aria-label="Mine block ${i + 1} of ${REASONS.length}: ${r.win}. Most clients: ${r.them}.">
        <span class="why__face" style="background-image:url(assets/blocks/${r.tex}.png)"></span>
        <canvas class="why__crack" width="16" height="16" aria-hidden="true"></canvas>
      </button>
      <div class="why__card" aria-hidden="true">
        <strong>${r.win}</strong>
        <span><em>Most clients</em> <s>${r.them}</s></span>
      </div>`;
    grid.appendChild(cell);
    const state = { r, cell, btn: cell.querySelector('.why__block'), crack: cell.querySelector('.why__crack'), hits: 0, colors: ['#888'] };
    sampleColors(r.tex).then(c => { state.colors = c; });
    state.btn.addEventListener('click', e => { interacted = true; hit(state, e); });
    // Reactive tilt toward the cursor (a block you want to poke).
    state.btn.addEventListener('pointermove', e => {
      if (reduced) return;
      const b = state.btn.getBoundingClientRect();
      const x = (e.clientX - b.left) / b.width - 0.5, y = (e.clientY - b.top) / b.height - 0.5;
      state.btn.style.setProperty('--rx', `${(-y * 18).toFixed(1)}deg`);
      state.btn.style.setProperty('--ry', `${(x * 18).toFixed(1)}deg`);
    });
    state.btn.addEventListener('pointerleave', () => {
      state.btn.style.setProperty('--rx', '0deg');
      state.btn.style.setProperty('--ry', '0deg');
    });
    return state;
  });

  function hit(s, e) {
    if (s.hits >= HITS) return;
    s.hits++;
    hint.classList.add('is-gone');
    drawCrack(s.crack, s.hits);
    const c = center(s.btn, e);
    if (s.hits < HITS) {
      burst(c.x, c.y, s.colors, reduced ? 4 : 10, 3.2);
      shake(s.btn);
      return;
    }
    // Broken.
    burst(c.x, c.y, s.colors, reduced ? 12 : 46, 6.5);
    xpOrbs(c.x, c.y, reduced ? 2 : 6);
    s.cell.classList.add('is-mined');
    s.btn.disabled = true;
    s.cell.querySelector('.why__card').removeAttribute('aria-hidden');
    mined++;
    count.textContent = `${mined} / ${REASONS.length} mined`;
    // The orbs fill the bar as they land; this backstop makes sure the bar and
    // level always end where the mined count says, whatever the orbs did.
    const settled = mined;
    setTimeout(() => {
      xpTarget = Math.max(xpTarget, settled / REASONS.length);
      lvl.textContent = String(mined);
    }, reduced ? 200 : 1300);
    if (mined === REASONS.length) setTimeout(levelUp, 650);
  }

  function levelUp() {
    sec.classList.add('is-done');
    count.textContent = 'All six. Convinced?';
    cta.removeAttribute('tabindex');
    const b = stage.getBoundingClientRect();
    const shots = reduced ? 1 : 5;
    for (let k = 0; k < shots; k++) {
      setTimeout(() => firework(b.width * (0.15 + Math.random() * 0.7), b.height * (0.12 + Math.random() * 0.35)), k * 260);
    }
  }

  // ---------------------------------------------------------------- particles
  function resize() {
    const dpr = Math.min(devicePixelRatio || 1, 2);
    canvas.width = stage.clientWidth * dpr;
    canvas.height = stage.clientHeight * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  resize();
  addEventListener('resize', resize);

  function center(el, e) {
    const s = stage.getBoundingClientRect(), b = el.getBoundingClientRect();
    // Pointer position when clicked with a mouse/touch, else the block centre.
    const px = e && e.clientX ? e.clientX : b.left + b.width / 2;
    const py = e && e.clientY ? e.clientY : b.top + b.height / 2;
    return { x: px - s.left, y: py - s.top };
  }

  function burst(x, y, colors, n, speed) {
    for (let k = 0; k < n; k++) {
      const a = Math.random() * Math.PI * 2, v = speed * (0.35 + Math.random());
      particles.push({ kind: 'chip', x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v - speed * 0.6,
        size: 3 + Math.random() * 5, color: colors[(Math.random() * colors.length) | 0], life: 1, decay: 0.012 + Math.random() * 0.012, spin: Math.random() * 6 });
    }
  }

  function xpOrbs(x, y, n) {
    const xp = bar.getBoundingClientRect(), s = stage.getBoundingClientRect();
    const tx = xp.left - s.left + xp.width * ((mined + 1) / REASONS.length), ty = xp.top - s.top + xp.height / 2;
    for (let k = 0; k < n; k++) {
      particles.push({ kind: 'orb', x, y, vx: (Math.random() - 0.5) * 7, vy: -3 - Math.random() * 4,
        tx, ty, size: 4 + Math.random() * 3, life: 1, age: 0, delay: k * 4 });
    }
  }

  function firework(x, y) {
    const hues = ['#83e2b5', '#f2b878', '#f1e9d2', '#5fd09b', '#ffd36e'];
    const color = hues[(Math.random() * hues.length) | 0];
    for (let k = 0; k < 60; k++) {
      const a = (k / 60) * Math.PI * 2, v = 2.5 + Math.random() * 3.5;
      particles.push({ kind: 'spark', x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v, size: 2.5, color, life: 1, decay: 0.011 + Math.random() * 0.01 });
    }
  }

  // Ambient motes so the stage feels alive even before anyone clicks.
  function mote() {
    if (reduced || particles.length > 260) return;
    particles.push({ kind: 'mote', x: Math.random() * stage.clientWidth, y: stage.clientHeight + 4,
      vx: (Math.random() - 0.5) * 0.3, vy: -(0.25 + Math.random() * 0.5), size: 1.5 + Math.random() * 2, life: 1, decay: 0.003 });
  }

  let running = false;
  function tick() {
    ctx.clearRect(0, 0, stage.clientWidth, stage.clientHeight);
    if (Math.random() < 0.18) mote();
    xpShown += (xpTarget - xpShown) * 0.12;
    bar.style.width = `${(xpShown * 100).toFixed(2)}%`;
    for (let k = particles.length - 1; k >= 0; k--) {
      const p = particles[k];
      if (p.kind === 'orb') {
        p.age++;
        if (p.age > p.delay) {
          // Fly out, then home into the XP bar.
          const dx = p.tx - p.x, dy = p.ty - p.y, d = Math.hypot(dx, dy);
          p.vx = p.vx * 0.86 + (dx / (d || 1)) * 2.2;
          p.vy = p.vy * 0.86 + (dy / (d || 1)) * 2.2;
          // Land when the next step would reach it (fast orbs used to circle the bar).
          if (d < Math.max(12, Math.hypot(p.vx, p.vy))) {
            particles.splice(k, 1);
            xpTarget = Math.min(1, xpTarget + 1 / (REASONS.length * Math.max(1, reduced ? 2 : 6)));
            lvl.textContent = String(mined);
            lvl.classList.remove('is-pop'); void lvl.offsetWidth; lvl.classList.add('is-pop');
            continue;
          }
        }
        p.x += p.vx; p.y += p.vy;
        const g = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, p.size * 2.4);
        g.addColorStop(0, 'rgba(230,255,140,1)'); g.addColorStop(0.45, 'rgba(160,240,90,.85)'); g.addColorStop(1, 'rgba(120,220,60,0)');
        ctx.fillStyle = g;
        ctx.beginPath(); ctx.arc(p.x, p.y, p.size * 2.4, 0, Math.PI * 2); ctx.fill();
        continue;
      }
      p.life -= p.decay;
      if (p.life <= 0 || p.y > stage.clientHeight + 40) { particles.splice(k, 1); continue; }
      p.x += p.vx; p.y += p.vy;
      if (p.kind === 'chip') { p.vy += 0.22; p.vx *= 0.985; }
      if (p.kind === 'spark') { p.vy += 0.05; p.vx *= 0.97; p.vy *= 0.97; }
      ctx.globalAlpha = Math.max(0, p.life);
      if (p.kind === 'mote') {
        ctx.fillStyle = 'rgba(131,226,181,.7)';
        ctx.fillRect(p.x, p.y, p.size, p.size);
      } else {
        ctx.fillStyle = p.color;
        ctx.save(); ctx.translate(p.x, p.y); ctx.rotate(p.spin ? p.spin * (1 - p.life) : 0);
        ctx.fillRect(-p.size / 2, -p.size / 2, p.size, p.size);
        ctx.restore();
      }
      ctx.globalAlpha = 1;
    }
    if (running) requestAnimationFrame(tick);
  }

  // Only animate while the section is on screen.
  const seen = new IntersectionObserver(es => es.forEach(e => {
    if (e.isIntersecting && !running) { running = true; requestAnimationFrame(tick); }
    if (!e.isIntersecting) running = false;
    if (e.isIntersecting && e.intersectionRatio > 0.35 && !sec.classList.contains('is-in-view')) arrive();
  }), { threshold: [0, 0.35] });
  seen.observe(stage);

  // Blocks drop in one by one; each landing puffs dust. Then, if nobody has
  // touched anything, the pickaxe takes the first swing to show how it works.
  function arrive() {
    sec.classList.add('is-in-view');
    cells.forEach((s, i) => setTimeout(() => {
      const c = center(s.btn);
      burst(c.x, c.y + s.btn.clientHeight / 2, ['#b8b5a6', '#7d8279', '#4e705c'], reduced ? 0 : 8, 1.6);
    }, reduced ? 0 : 380 + i * 110));
    if (!reduced) setTimeout(() => { if (!interacted && cells[0].hits === 0) hit(cells[0]); }, 3200);
  }

  // Keep the hint parked on the first unmined block.
  function placeHint() {
    const s = cells.find(c => c.hits < HITS);
    if (!s) { hint.classList.add('is-gone'); return; }
    const st = stage.getBoundingClientRect(), b = s.btn.getBoundingClientRect();
    hint.style.left = `${b.left - st.left + b.width * 0.62}px`;
    hint.style.top = `${b.top - st.top - 18}px`;
  }
  placeHint();
  addEventListener('resize', placeHint);
  setInterval(placeHint, 800);
}

// 3 crack stages, drawn as pixel lines radiating from the middle, like
// Minecraft's destroy_stage overlay (deterministic so a re-hit never jumps).
function drawCrack(cv, stage) {
  const c = cv.getContext('2d');
  c.clearRect(0, 0, 16, 16);
  c.fillStyle = 'rgba(0,0,0,.72)';
  const arms = [[1, -1], [-1, -1], [1, 1], [-1, 1], [1, 0], [0, 1], [-1, 0], [0, -1]];
  const reach = [0, 4, 6, 8][stage];
  const count = [0, 3, 5, 8][stage];
  for (let a = 0; a < count; a++) {
    let x = 8, y = 8;
    const [dx, dy] = arms[a];
    for (let k = 0; k < reach; k++) {
      x += dx; y += dy;
      if ((k + a) % 2) x += a % 3 - 1;  // a little jag
      if (x >= 0 && x < 16 && y >= 0 && y < 16) c.fillRect(x, y, 1, 1);
    }
  }
}

function shake(el) {
  if (reduced) return;
  el.classList.remove('is-hit'); void el.offsetWidth; el.classList.add('is-hit');
}

// Particle colours = the block's own texture pixels (skipping transparent ones).
function sampleColors(tex) {
  return new Promise(resolve => {
    const img = new Image();
    img.onload = () => {
      try {
        const c = document.createElement('canvas');
        c.width = 16; c.height = 16;
        const g = c.getContext('2d');
        g.drawImage(img, 0, 0, 16, 16);
        const d = g.getImageData(0, 0, 16, 16).data, out = [];
        for (let k = 0; k < d.length; k += 4 * 7) if (d[k + 3] > 200) out.push(`rgb(${d[k]},${d[k + 1]},${d[k + 2]})`);
        resolve(out.length ? out : ['#888']);
      } catch (e) { resolve(['#888']); }
    };
    img.onerror = () => resolve(['#888']);
    img.src = `assets/blocks/${tex}.png`;
  });
}
