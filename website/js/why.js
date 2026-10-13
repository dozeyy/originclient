// "Why Origin" mini-game: six Minecraft blocks, each hiding one reason to pick
// Origin. Hit a block (click, tap, Enter/Space) until it breaks: it cracks,
// shakes and bursts into chips coloured from its own texture, drops XP orbs
// into the XP bar and flips into a card with the reason. Every block mined
// upgrades the pickaxe (wood -> netherite, fewer hits each tier). After two
// blocks a TNT block drops in: light it and it blows up whatever is left.
// Minecraft-style "Advancement Made!" toasts mark the milestones; sounds are
// synthesised with WebAudio (only ever after a click) and can be muted.
//
// The plain comparison table in index.html stays as the no-JS / screen-reader
// fallback; this script hides it only once the game is built. Respects
// prefers-reduced-motion (no shake, trail or auto demo, fewer particles).

// win = what Origin does (shown under each block from the start, so the
// reasons read even if nobody plays); them = most clients (revealed by mining).
// Keep both honest and short: no "every version", no promised results.
const REASONS = [
  { tex: 'glowstone', win: 'Shaders built in', them: 'Often DIY', icon: 'M12 3v2M12 19v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M3 12h2M19 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z' },
  { tex: 'diamond_ore', win: 'Every account at once', them: 'One at a time', icon: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8' },
  { tex: 'crafting_table_front', win: 'Bring your own mods', them: 'Often blocked', icon: 'M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM12 8v8M8 12h8' },
  { tex: 'emerald_ore', win: 'Settings can follow you', them: 'Start over', icon: 'M21 2v6h-6M3 22v-6h6M3.5 9a9 9 0 0 1 15-3.4L21 8M20.5 15a9 9 0 0 1-15 3.4L3 16' },
  { tex: 'grass_side', win: 'FPS Coach finds your lag', them: 'You guess', icon: 'M12 14l4-4M3.3 19a10 10 0 1 1 17.4 0' },
  { tex: 'oak_log', win: 'Code you can read', them: 'Closed', icon: 'M16 18l6-6-6-6M8 6l-6 6 6 6' },
];
// One tier per block mined: the pickaxe head colour and hits to break a block.
const PICKS = [
  { name: 'Wooden', head: '#a77b43', hits: 2 },
  { name: 'Stone', head: '#9a9a9a', hits: 2 },
  { name: 'Iron', head: '#e4e4e4', hits: 1 },
  { name: 'Golden', head: '#f6d94c', hits: 1 },
  { name: 'Diamond', head: '#5ff0dc', hits: 1 },
  { name: 'Netherite', head: '#5b4b55', hits: 1 },
];

const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
const section = document.getElementById('why');
if (section) build(section);

function pickUrl(head) {
  const svg = `<svg xmlns='http://www.w3.org/2000/svg' width='32' height='32' shape-rendering='crispEdges'>`
    + `<path fill='#5a3a1e' d='M14 14h4v4h-4zM11 17h4v4h-4zM8 20h4v4H8zM5 23h4v4H5z'/>`
    + `<path fill='${head}' d='M10 4h12v4h4v4h-4V8H10zM22 12h4v8h-4zM18 8h4v4h-4z'/>`
    + `<path fill='rgba(0,0,0,.35)' d='M22 16h4v4h-4zM10 6h4v2h-4z'/></svg>`;
  return `url("data:image/svg+xml,${encodeURIComponent(svg)}")`;
}

function build(sec) {
  const stage = sec.querySelector('[data-why-stage]');
  if (!stage) return;
  sec.classList.add('is-game');

  stage.innerHTML = `
    <canvas class="why__fx" aria-hidden="true"></canvas>
    <div class="why__toasts" aria-hidden="true"></div>
    <div class="why__grid" role="list" inert></div>
    <div class="why__hint" aria-hidden="true"><span class="why__pick"></span><span>Mine me</span></div>
    <div class="why__hud">
      <span class="why__lvl" data-lvl>0</span>
      <span class="why__bar"><span data-bar></span></span>
      <span class="why__count" data-count>0 / ${REASONS.length} mined</span>
      <span class="why__tnt-slot"><button class="why__tnt" type="button" hidden aria-label="Light the TNT: mine every block that's left">
        <span class="why__tnt-body"><span>TNT</span></span>
      </button></span>
      <span class="why__tool"><span class="why__tool-icon" data-tool></span><span data-tool-name>Wooden pickaxe</span></span>
      <button class="why__mute" type="button" data-mute aria-pressed="false" aria-label="Mute sound"></button>
    </div>
    <div class="why__end">
      <a class="btn btn--primary why__cta" href="#get" data-cta tabindex="-1">Get Origin</a>
      <button class="btn btn--ghost why__again" type="button" data-again tabindex="-1">Play again</button>
    </div>`;
  const $ = s => stage.querySelector(s);
  const grid = $('.why__grid'), canvas = $('.why__fx'), ctx = canvas.getContext('2d');
  const hint = $('.why__hint'), toasts = $('.why__toasts'), tnt = $('.why__tnt');
  const bar = $('[data-bar]'), lvl = $('[data-lvl]'), count = $('[data-count]');
  const tool = $('[data-tool]'), toolName = $('[data-tool-name]'), mute = $('[data-mute]');
  const cta = $('[data-cta]'), again = $('[data-again]');

  let mined = 0, interacted = false, xpShown = 0, xpTarget = 0, tntState = 'hidden', exploding = false, round = 0;
  const particles = [];
  const sound = makeSound();
  // Phones get half the particles: the end burst buried the card text there.
  const dense = n => Math.round(n * (innerWidth < 640 ? 0.5 : 1));

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
      <span class="why__label" aria-hidden="true">${r.win}</span>
      <div class="why__card" aria-hidden="true">
        <svg class="why__icon" viewBox="0 0 24 24" aria-hidden="true"><path d="${r.icon}"/></svg>
        <strong>${r.win}</strong>
        <span class="why__them"><span>Most clients</span><s>${r.them}</s></span>
      </div>`;
    grid.appendChild(cell);
    const s = { r, cell, btn: cell.querySelector('.why__block'), crack: cell.querySelector('.why__crack'), progress: 0, broken: false, colors: ['#888'] };
    sampleColors(r.tex).then(c => { s.colors = c; });
    s.btn.addEventListener('click', e => { interacted = true; sound.unlock(); hit(s, e); });
    // Reactive tilt toward the cursor.
    s.btn.addEventListener('pointermove', e => {
      if (reduced) return;
      const b = s.btn.getBoundingClientRect();
      const x = (e.clientX - b.left) / b.width - 0.5, y = (e.clientY - b.top) / b.height - 0.5;
      s.btn.style.setProperty('--rx', `${(-y * 22).toFixed(1)}deg`);
      s.btn.style.setProperty('--ry', `${(x * 22).toFixed(1)}deg`);
    });
    s.btn.addEventListener('pointerleave', () => { s.btn.style.setProperty('--rx', '0deg'); s.btn.style.setProperty('--ry', '0deg'); });
    // Mined cards tilt toward the cursor too, so they stay alive after the fun.
    const card = cell.querySelector('.why__card');
    card.addEventListener('pointermove', e => {
      if (reduced) return;
      const b = card.getBoundingClientRect();
      card.style.setProperty('--rx', `${(-((e.clientY - b.top) / b.height - 0.5) * 10).toFixed(1)}deg`);
      card.style.setProperty('--ry', `${(((e.clientX - b.left) / b.width - 0.5) * 10).toFixed(1)}deg`);
    });
    card.addEventListener('pointerleave', () => { card.style.setProperty('--rx', '0deg'); card.style.setProperty('--ry', '0deg'); });
    return s;
  });

  // The bar eases toward xpTarget inside the animation loop; with reduced
  // motion that loop sleeps, so the bar is set straight away instead.
  function setXp(v) {
    xpTarget = v;
    if (reduced) { xpShown = v; bar.style.width = `${(v * 100).toFixed(2)}%`; } else wake();
  }

  function tier() { return PICKS[Math.min(mined, PICKS.length - 1)]; }
  function paintTool() {
    const t = tier();
    stage.style.setProperty('--pick', `${pickUrl(t.head)} 8 24, pointer`);
    tool.style.backgroundImage = pickUrl(t.head);
    toolName.textContent = `${t.name} pickaxe`;
    hint.querySelector('.why__pick').style.backgroundImage = pickUrl(t.head);
  }
  paintTool();

  // ---------------------------------------------------------------- mining
  function hit(s, e, demo) {
    if (s.broken || exploding) return;
    if (!demo) hint.classList.add('is-gone');
    // A crit (1 in 6) counts double: a little luck makes every click a gamble.
    const crit = !demo && Math.random() < 1 / 6;
    s.progress += crit ? 2 : 1;
    const need = tier().hits;
    const c = center(s.btn, e);
    if (s.progress < need) {
      drawCrack(s.crack, Math.min(3, Math.ceil((s.progress / need) * 3)));
      burst(c.x, c.y, s.colors, reduced ? 4 : 12, 3.4);
      pop(c.x, c.y - 10, crit ? 'CRIT!' : '+1', crit ? 'crit' : '');
      shake(s.btn, 'is-hit');
      sound.hit(crit);
      return;
    }
    breakBlock(s, c, crit);
  }

  function breakBlock(s, c, crit) {
    s.broken = true;
    drawCrack(s.crack, 3);
    burst(c.x, c.y, s.colors, reduced ? 12 : 54, 7);
    xpOrbs(c.x, c.y, reduced ? 2 : 7);
    pop(c.x, c.y - 14, crit ? 'CRIT! +XP' : '+XP', 'xp');
    shake(stage, 'is-quake');
    sound.brk();
    s.cell.classList.add('is-mined');
    s.btn.disabled = true;
    s.cell.querySelector('.why__card').removeAttribute('aria-hidden');
    mined++;
    count.textContent = `${mined} / ${REASONS.length} mined`;
    const settled = mined, myRound = round;
    // The orbs fill the bar as they land; this backstop makes sure the bar and
    // level always end where the mined count says, whatever the orbs did.
    // (Skipped if "Play again" started a new round meanwhile.)
    setTimeout(() => {
      if (myRound !== round) return;
      setXp(Math.max(xpTarget, settled / REASONS.length)); lvl.textContent = String(mined);
    }, reduced ? 200 : 1300);
    placeHint();

    if (mined === 1) toast('First Find', 'Mine your first block');
    if (mined < REASONS.length) {
      paintTool();
      tool.classList.remove('is-up'); void tool.offsetWidth; tool.classList.add('is-up');
      if (mined === 2) toast('Isn’t It Iron Pick', 'Upgrade to an iron pickaxe');
      if (mined === 4) toast('Diamonds!', 'Get a diamond pickaxe');
    }
    if (mined === 2 && tntState === 'hidden') spawnTnt();
    if (mined === REASONS.length) {
      if (tntState === 'ready') hideTnt();
      setTimeout(levelUp, 650);
    }
  }

  function levelUp() {
    sec.classList.add('is-done');
    count.textContent = 'All six. Convinced?';
    cta.removeAttribute('tabindex');
    again.removeAttribute('tabindex');
    toast('Convinced?', 'Mine every reason');
    sound.level();
    const w = stage.clientWidth, h = stage.clientHeight;
    const shots = reduced ? 1 : 6;
    for (let k = 0; k < shots; k++) {
      const myRound = round;
      setTimeout(() => { if (myRound !== round) return; firework(w * (0.12 + Math.random() * 0.76), h * (0.1 + Math.random() * 0.4)); sound.pop(); }, k * 240);
    }
  }

  // ---------------------------------------------------------------- TNT
  function spawnTnt() {
    if (cells.filter(c => !c.broken).length < 2) return;
    tntState = 'ready';
    tnt.hidden = false;
    void tnt.offsetWidth;  // flush, so the drop starts from its hidden pose
    tnt.classList.add('is-in');
    toast('Something dropped', 'A TNT block appeared');
  }
  function hideTnt() { tntState = 'gone'; tnt.classList.remove('is-in', 'is-lit'); setTimeout(() => { if (tntState !== 'ready') tnt.hidden = true; }, 400); }
  tnt.addEventListener('click', () => {
    if (tntState !== 'ready') return;
    interacted = true;
    sound.unlock();
    tntState = 'lit';
    exploding = true;
    tnt.classList.add('is-lit');
    sound.fuse();
    const myRound = round;
    setTimeout(() => { if (myRound === round) explode(); }, reduced ? 300 : 1700);
  });
  function explode() {
    const c = center(tnt);
    hideTnt();
    burst(c.x, c.y, ['#d33a2c', '#f2f2f2', '#9a9a9a', '#ffb347', '#3b3b3b'], reduced ? 20 : 120, 11);
    if (!reduced) for (let k = 0; k < 3; k++) firework(c.x + (Math.random() - 0.5) * 60, c.y + (Math.random() - 0.5) * 40, '#ffb347');
    shake(stage, 'is-boom');
    sound.boom();
    const left = cells.filter(s => !s.broken)
      .sort((a, b) => dist(center(a.btn), c) - dist(center(b.btn), c));
    const myRound = round;
    left.forEach((s, k) => setTimeout(() => {
      if (myRound !== round || s.broken) return;
      exploding = k < left.length - 1;
      breakBlock(s, center(s.btn), false);
    }, 160 + k * 140));
    if (!left.length) exploding = false;
    toast('Boom!', 'Mine everything with one TNT');
  }

  again.addEventListener('click', () => {
    round++;
    for (let k = particles.length - 1; k >= 0; k--) if (particles[k].kind === 'orb') particles.splice(k, 1);
    mined = 0; xpShown = 0; setXp(0); tntState = 'hidden'; exploding = false;
    sec.classList.remove('is-done', 'is-in-view');
    cta.setAttribute('tabindex', '-1');
    again.setAttribute('tabindex', '-1');
    lvl.textContent = '0';
    count.textContent = `0 / ${REASONS.length} mined`;
    cells.forEach(s => {
      s.broken = false; s.progress = 0; s.btn.disabled = false;
      s.cell.classList.remove('is-mined');
      s.cell.querySelector('.why__card').setAttribute('aria-hidden', 'true');
      drawCrack(s.crack, 0);
    });
    hideTnt(); tntState = 'hidden';
    hint.classList.remove('is-gone');
    paintTool();
    grid.inert = true;
    requestAnimationFrame(() => requestAnimationFrame(() => { arrive(); cells[0].btn.focus({ preventScroll: true }); }));
  });

  // ---------------------------------------------------------------- toasts
  // Minecraft's advancement pop-up: slides in at the top right, then away.
  // At most one waiting: a burst of milestones (TNT clears four blocks at
  // once) skips straight to the newest instead of trailing seconds behind.
  let toastChain = Promise.resolve(), waiting = 0;
  function toast(title, desc) {
    if (waiting >= 1) toasts.dataset.skip = String(Number(toasts.dataset.skip || 0) + 1);
    waiting++;
    toastChain = toastChain.then(() => new Promise(done => {
      waiting--;
      if (Number(toasts.dataset.skip || 0) > 0 && waiting > 0) { toasts.dataset.skip = String(Number(toasts.dataset.skip) - 1); done(); return; }
      const t = document.createElement('div');
      t.className = 'why__toast';
      t.innerHTML = `<span class="why__toast-icon"></span><span><b>Advancement Made!</b><span>${title}</span></span>`;
      toasts.appendChild(t);
      sound.toast();
      setTimeout(() => t.classList.add('is-out'), 1500);
      setTimeout(() => { t.remove(); done(); }, 1850);
    }));
  }

  // ---------------------------------------------------------------- effects
  function resize() {
    const dpr = Math.min(devicePixelRatio || 1, 2);
    canvas.width = stage.clientWidth * dpr;
    canvas.height = stage.clientHeight * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  resize();
  new ResizeObserver(() => { resize(); placeHint(); }).observe(stage);

  function center(el, e) {
    const s = stage.getBoundingClientRect(), b = el.getBoundingClientRect();
    const px = e && e.clientX ? e.clientX : b.left + b.width / 2;
    const py = e && e.clientY ? e.clientY : b.top + b.height / 2;
    return { x: px - s.left, y: py - s.top };
  }
  function dist(a, b) { return Math.hypot(a.x - b.x, a.y - b.y); }

  function pop(x, y, text, kind) {
    const p = document.createElement('span');
    p.className = `why__pop ${kind ? 'why__pop--' + kind : ''}`;
    p.textContent = text;
    p.style.left = `${x}px`;
    p.style.top = `${y}px`;
    p.style.setProperty('--dx', `${((Math.random() - 0.5) * 40).toFixed(0)}px`);
    stage.appendChild(p);
    setTimeout(() => p.remove(), 900);
  }

  function burst(x, y, colors, n, speed) {
    if (reduced) return;
    wake();
    for (let k = 0, m = dense(n); k < m; k++) {
      const a = Math.random() * Math.PI * 2, v = speed * (0.35 + Math.random());
      particles.push({ kind: 'chip', x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v - speed * 0.6,
        size: 3 + Math.random() * 5, color: colors[(Math.random() * colors.length) | 0], life: 1, decay: 0.011 + Math.random() * 0.012, spin: Math.random() * 6 });
    }
  }

  function xpOrbs(x, y, n) {
    if (reduced) return;
    wake();
    const xp = bar.getBoundingClientRect(), s = stage.getBoundingClientRect();
    const tx = xp.left - s.left + xp.width * (Math.min(mined + 1, REASONS.length) / REASONS.length), ty = xp.top - s.top + xp.height / 2;
    for (let k = 0; k < n; k++) {
      particles.push({ kind: 'orb', x, y, vx: (Math.random() - 0.5) * 8, vy: -3 - Math.random() * 5, tx, ty, size: 4 + Math.random() * 3, age: 0, delay: k * 4, round });
    }
  }

  function firework(x, y, color) {
    if (reduced) return;
    wake();
    const hues = ['#83e2b5', '#f2b878', '#f1e9d2', '#5fd09b', '#ffd36e', '#7fd6ff'];
    const col = color || hues[(Math.random() * hues.length) | 0];
    for (let k = 0, m = dense(64); k < m; k++) {
      const a = (k / m) * Math.PI * 2, v = 2.5 + Math.random() * 3.8;
      particles.push({ kind: 'spark', x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v, size: 2.6, color: col, life: 1, decay: 0.01 + Math.random() * 0.01 });
    }
  }

  function mote(x, y, trail) {
    if (reduced || particles.length > 320) return;
    particles.push({ kind: 'mote', x: x ?? Math.random() * stage.clientWidth, y: y ?? stage.clientHeight + 4,
      vx: (Math.random() - 0.5) * (trail ? 1.2 : 0.3), vy: trail ? -(0.4 + Math.random() * 0.8) : -(0.25 + Math.random() * 0.5),
      size: 1.5 + Math.random() * 2, life: 1, decay: trail ? 0.03 : 0.003 });
  }
  // A sparkle trail follows the pickaxe around the stage.
  let lastTrail = 0;
  stage.addEventListener('pointermove', e => {
    const now = performance.now();
    if (now - lastTrail < 28) return;
    lastTrail = now;
    const s = stage.getBoundingClientRect();
    mote(e.clientX - s.left, e.clientY - s.top, true);
  });

  let running = false;
  function tick() {
    ctx.clearRect(0, 0, stage.clientWidth, stage.clientHeight);
    if (!reduced && Math.random() < 0.18) mote();
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
          if (d < Math.max(12, Math.hypot(p.vx, p.vy))) {
            particles.splice(k, 1);
            if (p.round !== round) continue;
            xpTarget = Math.min(1, xpTarget + 1 / (REASONS.length * (reduced ? 2 : 7)));
            lvl.textContent = String(mined);
            lvl.classList.remove('is-pop'); void lvl.offsetWidth; lvl.classList.add('is-pop');
            sound.xp();
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
      if (p.kind === 'chip') { p.vy += 0.24; p.vx *= 0.985; }
      if (p.kind === 'spark') { p.vy += 0.05; p.vx *= 0.97; p.vy *= 0.97; }
      ctx.globalAlpha = Math.max(0, p.life);
      if (p.kind === 'mote') {
        ctx.fillStyle = 'rgba(131,226,181,.75)';
        ctx.fillRect(p.x, p.y, p.size, p.size);
      } else {
        ctx.fillStyle = p.color;
        ctx.save(); ctx.translate(p.x, p.y); ctx.rotate(p.spin ? p.spin * (1 - p.life) : 0);
        ctx.fillRect(-p.size / 2, -p.size / 2, p.size, p.size);
        ctx.restore();
      }
      ctx.globalAlpha = 1;
    }
    // Reduced motion: no ambient motes, so the loop sleeps once nothing moves.
    if (running && (!reduced || particles.length)) requestAnimationFrame(tick);
    else if (running) running = false;
  }
  function wake() { if (!running && visible) { running = true; requestAnimationFrame(tick); } }

  // Only animate while the section is on screen.
  let visible = false;
  const seen = new IntersectionObserver(es => es.forEach(e => {
    visible = e.isIntersecting;
    if (visible) wake(); else running = false;
  }));
  seen.observe(stage);
  // Arrival = the stage's top edge passing 70% down the viewport, so it works
  // however tall the stage is (a ratio threshold never fires on tall stages).
  const arrival = new IntersectionObserver(es => es.forEach(e => {
    if (e.isIntersecting && !sec.classList.contains('is-in-view')) arrive();
  }), { rootMargin: '0px 0px -30% 0px' });
  arrival.observe(stage);

  // Blocks drop in one by one; each landing puffs dust. Then, if nobody has
  // touched anything, the pickaxe takes the first swing to show how it works.
  function arrive() {
    sec.classList.add('is-in-view');
    grid.inert = false;
    setTimeout(placeHint, reduced ? 0 : 1300);
    cells.forEach((s, i) => setTimeout(() => {
      const c = center(s.btn);
      burst(c.x, c.y + s.btn.clientHeight / 2, ['#b8b5a6', '#7d8279', '#4e705c'], reduced ? 0 : 9, 1.7);
    }, reduced ? 0 : 1080 + i * 110));
    const myRound = round;
    if (!reduced) setTimeout(() => { if (myRound === round && !interacted && mined === 0 && cells[0].progress === 0) hit(cells[0], null, true); }, 3200);
  }

  // Until someone clicks, the pickaxe "taps" the next block every few seconds:
  // a wobble and a few chips, no progress. Stops for good after the first click.
  function attract() {
    if (reduced || interacted || !visible || !sec.classList.contains('is-in-view')) return;
    const s = cells.find(c => !c.broken);
    if (!s) return;
    const c = center(s.btn);
    burst(c.x, c.y - s.btn.clientHeight * 0.2, s.colors, 6, 2.4);
    shake(s.btn, 'is-hit');
    hint.classList.remove('is-tap'); void hint.offsetWidth; hint.classList.add('is-tap');
  }
  const attractTimer = setInterval(() => { if (interacted) clearInterval(attractTimer); else attract(); }, 3600);

  // Keep the hint parked on the first unmined block.
  function placeHint() {
    const s = cells.find(c => !c.broken);
    if (!s) { hint.classList.add('is-gone'); return; }
    const st = stage.getBoundingClientRect(), b = s.btn.getBoundingClientRect();
    hint.style.left = `${b.left - st.left + b.width * 0.62}px`;
    hint.style.top = `${b.top - st.top - 18}px`;
  }
  placeHint();

  // Sound toggle (remembered per visitor; the game works the same muted).
  function paintMute() {
    mute.setAttribute('aria-pressed', String(sound.muted));
    mute.classList.toggle('is-muted', sound.muted);
  }
  mute.addEventListener('click', () => { sound.setMuted(!sound.muted); paintMute(); });
  paintMute();
}

// 3 crack stages, drawn as pixel lines radiating from the middle, like
// Minecraft's destroy_stage overlay (deterministic so a re-hit never jumps).
function drawCrack(cv, stage) {
  const c = cv.getContext('2d');
  c.clearRect(0, 0, 16, 16);
  if (!stage) return;
  c.fillStyle = 'rgba(0,0,0,.72)';
  const arms = [[1, -1], [-1, -1], [1, 1], [-1, 1], [1, 0], [0, 1], [-1, 0], [0, -1]];
  const reach = [0, 4, 6, 8][stage];
  const count = [0, 3, 5, 8][stage];
  for (let a = 0; a < count; a++) {
    let x = 8, y = 8;
    const [dx, dy] = arms[a];
    for (let k = 0; k < reach; k++) {
      x += dx; y += dy;
      if ((k + a) % 2) x += a % 3 - 1;
      if (x >= 0 && x < 16 && y >= 0 && y < 16) c.fillRect(x, y, 1, 1);
    }
  }
}

function shake(el, cls) {
  if (reduced) return;
  el.classList.remove(cls); void el.offsetWidth; el.classList.add(cls);
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

// Tiny synthesised sound kit (no audio files). The AudioContext is created on
// the first click, so nothing ever plays before the visitor interacts.
function makeSound() {
  let ac = null, muted = false;
  try { muted = localStorage.getItem('origin-why-muted') === '1'; } catch (e) { /* storage blocked: default on */ }
  const unlock = () => {
    if (ac || muted) return;
    try { ac = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) { ac = null; }
  };
  const tone = (freq, dur, type = 'square', vol = 0.06, slide = 0) => {
    if (!ac || muted) return;
    const t = ac.currentTime, o = ac.createOscillator(), g = ac.createGain();
    o.type = type; o.frequency.setValueAtTime(freq, t);
    if (slide) o.frequency.exponentialRampToValueAtTime(Math.max(30, freq + slide), t + dur);
    g.gain.setValueAtTime(vol, t); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    o.connect(g).connect(ac.destination); o.start(t); o.stop(t + dur);
  };
  const noise = (dur, vol = 0.12, cutoff = 1200) => {
    if (!ac || muted) return;
    const t = ac.currentTime, len = Math.floor(ac.sampleRate * dur), buf = ac.createBuffer(1, len, ac.sampleRate);
    const d = buf.getChannelData(0);
    for (let k = 0; k < len; k++) d[k] = (Math.random() * 2 - 1) * (1 - k / len);
    const src = ac.createBufferSource(), f = ac.createBiquadFilter(), g = ac.createGain();
    src.buffer = buf; f.type = 'lowpass'; f.frequency.value = cutoff;
    g.gain.setValueAtTime(vol, t); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    src.connect(f).connect(g).connect(ac.destination); src.start(t);
  };
  let xpPitch = 0;
  return {
    get muted() { return muted; },
    setMuted(m) {
      muted = m;
      try { localStorage.setItem('origin-why-muted', m ? '1' : '0'); } catch (e) { /* fine */ }
      if (!m) unlock();
    },
    unlock,
    hit: crit => { noise(0.07, 0.16, crit ? 2600 : 1400); tone(crit ? 520 : 180, 0.06, 'square', 0.03); },
    brk: () => { noise(0.18, 0.2, 900); tone(140, 0.16, 'triangle', 0.08, -60); },
    xp: () => { xpPitch = (xpPitch + 1) % 8; tone(880 + xpPitch * 90, 0.09, 'sine', 0.05); },
    toast: () => { tone(660, 0.08, 'triangle', 0.04); setTimeout(() => tone(990, 0.12, 'triangle', 0.04), 90); },
    fuse: () => { noise(1.6, 0.05, 5000); },
    boom: () => { noise(0.9, 0.35, 500); tone(70, 0.6, 'sine', 0.2, -40); },
    pop: () => { noise(0.12, 0.08, 3000); },
    level: () => [523, 659, 784, 1046].forEach((f, k) => setTimeout(() => tone(f, 0.14, 'square', 0.04), k * 110)),
  };
}
