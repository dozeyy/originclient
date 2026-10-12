// Origin Store: pick cosmetics, type a Minecraft name, pay through Tebex.
//
// This file owns WHAT is for sale and the checkout; cosmetics.js owns how each
// item looks (same ids). Payment, tax, receipts and refunds are Tebex's job:
// the page builds a Tebex basket with the typed Minecraft name on it, adds the
// packages and sends the player to Tebex's own checkout page. The name rides
// on the order, so the purchase belongs to that player. Setup: docs/STORE.md.
import { createFittingRoom, createThumbnailer, loadSkin, flatArt, kindOf, isAnimated, LOOP } from './cosmetics.js';

// ---------------------------------------------------------------------------
// THE ONE SWITCH. While `live` is false the store is a showroom: everything
// can be browsed, tried on and bagged, but Checkout says "Soon" and no Tebex
// call is made. Turn it on only once cosmetics actually show up in game.
const STORE = {
  live: false,
  // Tebex → Integrations → Headless API → the webstore's PUBLIC token.
  // Public by design (it only lets a browser build baskets); never put the
  // private key here.
  webstore: '',
};

// Everything for sale. `tebex` = that item's Tebex package id (0 = not set up
// in Tebex yet: the card says Soon). `price` is only shown until Tebex's own
// prices load; Tebex is the source of truth for what is charged.
const ITEMS = [
  // Animated capes: the art moves and they give off particles.
  { id: 'cape-orbit', name: 'Orbit Cape', blurb: 'Sparks race round the Origin rings while the glow breathes.', price: 9.99, tebex: 0 },
  { id: 'cape-glitch', name: 'Glitch Cape', blurb: 'The Origin mark, split and glitching on a dark screen.', price: 9.99, tebex: 0 },
  { id: 'cape-inferno', name: 'Inferno Cape', blurb: 'Living flames with embers rising off them.', price: 8.99, tebex: 0 },
  { id: 'cape-aurora', name: 'Aurora Cape', blurb: 'Northern lights that ripple under twinkling stars.', price: 8.99, tebex: 0 },
  { id: 'cape-starfall', name: 'Starfall Cape', blurb: 'Shooting stars across a moonlit sky.', price: 8.99, tebex: 0 },
  // Still capes.
  { id: 'cape-origin', name: 'Origin Cape', blurb: 'The three rings, glowing on deepslate.', price: 5.99, tebex: 0 },
  { id: 'cape-golden', name: 'Golden Hour Cape', blurb: 'The sunset over the Origin island.', price: 5.99, tebex: 0 },
  { id: 'cape-frost', name: 'Frost Cape', blurb: 'Ice blue with falling snow.', price: 5.99, tebex: 0 },
  { id: 'cape-void', name: 'Void Cape', blurb: 'An ender eye in the dark.', price: 5.99, tebex: 0 },
  // Head.
  { id: 'halo-orbit', name: 'Orbit Halo', blurb: 'The Origin mark, spinning over your head.', price: 7.99, tebex: 0 },
  { id: 'crown-pixel', name: 'Pixel Crown', blurb: 'Solid gold, three gems.', price: 6.99, tebex: 0 },
  { id: 'halo-gold', name: 'Golden Halo', blurb: 'A classic glowing ring.', price: 5.99, tebex: 0 },
];
const DEFAULT_CURRENCY = 'USD';
// Every price stays between $5 and $10 (Will, 2026-10-12): still items at the
// low end, animated capes at the top.
// ---------------------------------------------------------------------------

const KINDS = { cape: 'Cape', head: 'Hat & halo' };
const TEBEX = 'https://headless.tebex.io/api';
const SAVE_KEY = 'origin.store.v1';
const NAME_RE = /^[A-Za-z0-9_]{3,16}$/;   // Java Edition usernames

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const byId = new Map(ITEMS.map(i => [i.id, i]));
const reducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches;
const smoothScroll = () => (reducedMotion() ? 'auto' : 'smooth');
const esc = s => String(s).replace(/[&<>"']/g, c => `&#${c.charCodeAt(0)};`);

const state = {
  bag: [],                     // item ids
  name: '',                    // as typed
  player: null,                // { name, uuid } once verified
  nameState: 'empty',          // empty | bad | checking | ok | missing | unknown
  prices: new Map(),           // tebex package id -> { amount, currency }
  trying: null,
  busy: false,
};
let room = null;

// ------------------------------------------------------------- persistence
// Bag and name survive a refresh / the trip to Tebex and back. Storage can be
// blocked (private windows); the store works the same without it.
function save() {
  try { localStorage.setItem(SAVE_KEY, JSON.stringify({ bag: state.bag, name: state.name })); } catch { /* not saved */ }
}
function restore() {
  try {
    const s = JSON.parse(localStorage.getItem(SAVE_KEY) || '{}');
    state.bag = (s.bag || []).filter(id => byId.has(id));
    state.name = typeof s.name === 'string' ? s.name : '';
  } catch { /* fresh start */ }
}

// ------------------------------------------------------------------ prices
function priceOf(item) {
  const p = item.tebex && state.prices.get(item.tebex);
  return p || { amount: item.price, currency: DEFAULT_CURRENCY };
}
function money(amount, currency) {
  try { return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount); } catch { return `${amount.toFixed(2)} ${currency}`; }
}
const forSale = item => STORE.live && item.tebex > 0;

// ------------------------------------------------------------------- tebex
async function tebex(method, path, body) {
  const r = await fetch(TEBEX + path, {
    method,
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.detail || j.message || j.title || `Tebex said ${r.status}`);
  return j.data;
}

async function loadPrices() {
  if (!STORE.live || !STORE.webstore) return;
  try {
    const cats = await tebex('GET', `/accounts/${STORE.webstore}/categories?includePackages=1`);
    for (const c of cats || []) {
      for (const p of c.packages || []) state.prices.set(p.id, { amount: Number(p.total_price ?? p.base_price), currency: p.currency || DEFAULT_CURRENCY });
    }
    renderGrid();
    renderBag();
  } catch (err) {
    console.warn('Origin store: live prices unavailable, showing list prices.', err);
  }
}

async function checkout() {
  const items = state.bag.map(id => byId.get(id));
  if (!STORE.live || state.busy || !items.length || !canCheckout() || notForSale().length) return;
  state.busy = true;
  renderBag();
  const here = new URL('store.html', location.href);
  try {
    const basket = await tebex('POST', `/accounts/${STORE.webstore}/baskets`, {
      username: state.player?.name || state.name.trim(),
      complete_url: `${here}?purchase=done`,
      cancel_url: `${here}?purchase=cancelled`,
      complete_auto_redirect: true,
      custom: { source: 'originclient.org/store', items: state.bag.join(',') },
    });
    for (const item of items) await tebex('POST', `/baskets/${basket.ident}/packages`, { package_id: item.tebex, quantity: 1 });
    const full = await tebex('GET', `/accounts/${STORE.webstore}/baskets/${basket.ident}`);
    const url = full?.links?.checkout;
    if (!url) throw new Error('Tebex did not return a checkout link');
    location.href = url;
  } catch (err) {
    state.busy = false;
    renderBag(`Couldn't open checkout: ${err.message}. Nothing was charged; please try again.`);
  }
}

// -------------------------------------------------------- minecraft names
// Looks the name up so the buyer sees whose head it goes on before paying.
// A lookup that fails to answer (offline, blocked) doesn't block the sale:
// Tebex checks the name again when the basket is made.
let lookupSeq = 0, lookupTimer = 0;
function onName(value) {
  state.name = value;
  state.player = null;
  save();
  clearTimeout(lookupTimer);
  const name = value.trim();
  const seq = ++lookupSeq;
  if (!name) state.nameState = 'empty';
  else if (!NAME_RE.test(name)) state.nameState = 'bad';
  else {
    state.nameState = 'checking';
    lookupTimer = setTimeout(async () => {
      const result = await lookupName(name);
      if (seq !== lookupSeq) return;          // a newer keystroke won
      state.nameState = result.state;
      state.player = result.player || null;
      renderName();
      renderBag();
      const skin = state.player ? await loadSkin(state.player.uuid) : null;
      if (room && seq === lookupSeq) room.setSkin(skin);
    }, 450);
  }
  if (room && state.nameState !== 'checking') room.setSkin(null);
  renderName();
  renderBag();
}

async function lookupName(name) {
  try {
    const r = await fetch(`https://playerdb.co/api/player/minecraft/${encodeURIComponent(name)}`);
    const j = await r.json();
    const p = j?.data?.player;
    if (j?.success && p) return { state: 'ok', player: { name: p.username, uuid: (p.raw_id || p.id || '').replace(/-/g, '') } };
    return { state: 'missing' };
  } catch {
    return { state: 'unknown' };
  }
}

const canCheckout = () => ['ok', 'unknown'].includes(state.nameState);
// Bagged before it was in Tebex (bags are saved), so it can't be bought yet.
const notForSale = () => state.bag.map(id => byId.get(id)).filter(i => !forSale(i));

function renderName() {
  const head = $('[data-name-head]'), note = $('[data-name-note]'), box = $('[data-name]');
  box.dataset.state = state.nameState;
  if (state.player) {
    head.src = `https://mc-heads.net/avatar/${state.player.uuid}/48`;
    head.hidden = false;
  } else {
    head.hidden = true;
    head.removeAttribute('src');
  }
  note.textContent = {
    empty: 'Your cosmetics go to this player. Buying a gift? Type your friend’s name.',
    bad: 'Minecraft names are 3–16 letters, numbers or underscores.',
    checking: 'Checking that name…',
    ok: `Found ${state.player?.name}. Everything in your bag goes to this player.`,
    missing: 'No Minecraft account has that name. Check the spelling.',
    unknown: 'Couldn’t check that name right now. It’s checked again at checkout.',
  }[state.nameState];
}

// ------------------------------------------------------------------ render
function renderGrid() {
  const filter = $('[data-filter][aria-pressed="true"]')?.dataset.filter || 'all';
  const shown = i => filter === 'all' || (filter === 'animated' ? isAnimated(i.id) : kindOf(i.id) === filter);
  $('[data-grid]').innerHTML = ITEMS.filter(shown).map(i => {
    const inBag = state.bag.includes(i.id), p = priceOf(i), soon = STORE.live && !forSale(i);
    return `<article class="item${state.trying === i.id ? ' is-trying' : ''}" data-item="${i.id}">
      <button class="item__try" type="button" data-try aria-label="Try on ${esc(i.name)}" aria-pressed="${state.trying === i.id}">
        <span class="item__art" data-art></span><span class="item__kind">${KINDS[kindOf(i.id)]}</span>${isAnimated(i.id) ? '<span class="item__anim">Animated</span>' : ''}<span class="item__tryon">${state.trying === i.id ? 'Wearing' : 'Try on'}</span>
      </button>
      <div class="item__meta"><h3>${esc(i.name)}</h3><p>${esc(i.blurb)}</p></div>
      <div class="item__buy"><b>${money(p.amount, p.currency)}</b>
        <button class="btn btn--sm ${inBag ? 'btn--ghost' : 'btn--primary'}" type="button" data-add ${soon ? 'disabled data-soon' : ''} aria-pressed="${inBag}">${inBag ? 'In bag ✓' : 'Add to bag'}</button>
      </div>
    </article>`;
  }).join('');
  paintArt();
}

let thumbs = null;          // id -> still picture (data URL), or null when WebGL is out
const frames = new Map();   // id -> looping frames for animated items, filled in the background
function paintArt() {
  for (const card of $$('[data-item]')) {
    const id = card.dataset.item, slot = $('[data-art]', card);
    if (thumbs?.has(id)) slot.innerHTML = `<img src="${thumbs.get(id)}" alt="" width="320" height="320"${isAnimated(id) ? ` data-anim="${id}"` : ''}>`;
    else slot.replaceChildren(flatFallback(id));
  }
}

// Animated items play on their cards. The frames are rendered a few at a time
// after the page is up, so the store never stalls; until an item's loop is
// ready its card shows the still.
const FRAMES = 20;
function animateCards(thumber) {
  const todo = ITEMS.filter(i => isAnimated(i.id)).flatMap(i => Array.from({ length: FRAMES }, (_, n) => [i.id, n]));
  const step = () => {
    const until = performance.now() + 12;
    while (todo.length && performance.now() < until) {
      const [id, n] = todo.shift();
      if (!frames.has(id)) frames.set(id, []);
      frames.get(id)[n] = thumber.frame(id, n, FRAMES);
    }
    if (todo.length) setTimeout(step, 16);
    else thumber.dispose();
  };
  setTimeout(step, 300);
  let tick = 0;
  setInterval(() => {
    if (document.hidden) return;
    tick++;
    for (const img of $$('img[data-anim]')) {
      const f = frames.get(img.dataset.anim);
      if (f?.length === FRAMES) img.src = f[tick % FRAMES];
    }
  }, (LOOP * 1000) / FRAMES);
}
// No 3D: show the item's own 2D art (capes, wings) or the Origin mark.
function flatFallback(id) {
  const art = flatArt(id);
  if (!art) {
    const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    s.setAttribute('class', 'item__mark');
    s.innerHTML = '<use href="#mark"/>';
    return s;
  }
  const img = new Image();
  img.src = art.toDataURL();
  img.alt = '';
  img.className = `item__flat item__flat--${kindOf(id)}`;
  return img;
}

function renderBag(error = '') {
  const items = state.bag.map(id => byId.get(id));
  $('[data-bag]').innerHTML = items.length
    ? items.map(i => `<li><span>${esc(i.name)}</span><b>${money(priceOf(i).amount, priceOf(i).currency)}</b><button type="button" data-remove="${i.id}" aria-label="Remove ${esc(i.name)}">×</button></li>`).join('')
    : '<li class="bag__empty">Your bag is empty. Add something from the store.</li>';
  const currency = items.length ? priceOf(items[0]).currency : DEFAULT_CURRENCY;
  const total = items.reduce((sum, i) => sum + priceOf(i).amount, 0);
  $('[data-total]').textContent = money(total, currency);

  const btn = $('[data-checkout]'), note = $('[data-checkout-note]');
  btn.toggleAttribute('data-soon', !STORE.live);
  const blocked = notForSale();
  btn.disabled = STORE.live && (state.busy || !items.length || !canCheckout() || blocked.length > 0);
  btn.setAttribute('aria-disabled', String(!STORE.live || btn.disabled));
  btn.textContent = state.busy ? 'Opening checkout…' : `Checkout${STORE.live && items.length ? ` · ${money(total, currency)}` : ''}`;
  note.classList.toggle('is-error', !!error);
  note.textContent = error || (!STORE.live
    ? 'The store opens soon. Try everything on in the meantime.'
    : !items.length ? 'Add a cosmetic to get started.'
    : blocked.length ? `${blocked[0].name} isn\u2019t on sale yet. Remove it to check out.`
    : !canCheckout() ? 'Type the Minecraft name these are for.'
    : 'Secure checkout by Tebex: card, PayPal, Apple Pay or Google Pay.');

  const bar = $('[data-bagbar]');
  bar.hidden = !items.length;
  $('[data-bagbar-text]').textContent = `${items.length} ${items.length === 1 ? 'item' : 'items'} · ${money(total, currency)}`;
  $('[data-bag-count]').textContent = items.length ? String(items.length) : '';
}

function tryOn(id) {
  state.trying = state.trying === id ? null : id;
  room?.wear(state.trying);
  $('[data-fit-label]').textContent = state.trying ? `Wearing: ${byId.get(state.trying).name}` : 'Pick something to try it on';
  if (!room) {
    const flat = $('[data-fit-flat]');
    flat.replaceChildren(...(state.trying ? [flatFallback(state.trying)] : []));
  }
  for (const card of $$('[data-item]')) {
    const on = card.dataset.item === state.trying;
    card.classList.toggle('is-trying', on);
    const b = $('[data-try]', card);
    b.setAttribute('aria-pressed', String(on));
    $('.item__tryon', b).textContent = on ? 'Wearing' : 'Try on';
  }
}

function toggleBag(id) {
  const at = state.bag.indexOf(id);
  if (at >= 0) state.bag.splice(at, 1);
  else {
    state.bag.push(id);
    if (state.trying !== id) tryOn(id);
  }
  save();
  renderGrid();
  renderBag();
}

// --------------------------------------------------------------- returning
// Tebex sends the buyer back with ?purchase=done or ?purchase=cancelled.
function handleReturn() {
  const q = new URLSearchParams(location.search), result = q.get('purchase');
  if (!result) return;
  const notice = $('[data-notice]');
  if (result === 'done') {
    notice.innerHTML = `<b>Thank you!</b> Your cosmetics are on <b>${esc(state.player?.name || state.name || 'your player')}</b>. A receipt is on its way to your email.`;
    state.bag = [];
    save();
  } else {
    notice.innerHTML = '<b>Checkout cancelled.</b> Nothing was charged, and your bag is still here.';
  }
  notice.hidden = false;
  history.replaceState(null, '', location.pathname);
}

// -------------------------------------------------------------------- boot
function boot() {
  restore();
  handleReturn();

  const input = $('#mc-name');
  input.value = state.name;
  input.addEventListener('input', () => onName(input.value));

  $$('[data-filter]').forEach(b => b.addEventListener('click', () => {
    $$('[data-filter]').forEach(o => o.setAttribute('aria-pressed', String(o === b)));
    renderGrid();
  }));
  $('[data-grid]').addEventListener('click', e => {
    const card = e.target.closest('[data-item]');
    if (!card) return;
    if (e.target.closest('[data-add]')) toggleBag(card.dataset.item);
    else if (e.target.closest('[data-try]')) {
      tryOn(card.dataset.item);
      // On phones the preview sits above the grid: bring it into view.
      const stage = $('#preview');
      if (state.trying && stage.getBoundingClientRect().bottom < 60) stage.scrollIntoView({ behavior: smoothScroll(), block: 'start' });
    }
  });
  $('[data-bag]').addEventListener('click', e => {
    const b = e.target.closest('[data-remove]');
    if (b) toggleBag(b.dataset.remove);
  });
  $('[data-checkout]').addEventListener('click', checkout);
  $('[data-bagbar] button').addEventListener('click', () => {
    $('#checkout').scrollIntoView({ behavior: smoothScroll(), block: 'start' });
    if (!state.name) setTimeout(() => input.focus({ preventScroll: true }), 400);
  });

  try {
    room = createFittingRoom($('[data-fit]'), { reducedMotion: reducedMotion() });
  } catch (err) {
    console.warn('Origin store: 3D preview unavailable, showing flat art.', err);
    document.body.classList.add('no-webgl');
  }
  let thumber = null;
  try {
    thumber = createThumbnailer();
    thumbs = new Map(ITEMS.map(i => [i.id, thumber.still(i.id)]));
  } catch (err) {
    console.warn('Origin store: card pictures unavailable, showing flat art.', err);
    thumbs = null;
    thumber = null;
  }

  renderGrid();
  renderBag();
  renderName();
  if (state.name) onName(state.name);
  tryOn(ITEMS[0].id);
  loadPrices();
  if (thumber && !reducedMotion()) animateCards(thumber);
  else thumber?.dispose();
}
boot();
