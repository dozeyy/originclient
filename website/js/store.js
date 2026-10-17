// Origin Store: pick cosmetics, type a Minecraft name, pay through Stripe.
//
// This file owns the shop window and the bag; cosmetics.js owns how each item
// looks (same ids). Prices and payment belong to the cosmetics service
// (services/cosmetics/src/stripe.js): the page asks it for a Stripe Checkout
// for the bag and the typed player's UUID, and sends the buyer to Stripe's own
// checkout page. Stripe tells the service when the money arrives, and the
// service gives that player the items. Setup: docs/STORE.md.
import { createFittingRoom, createThumbnailer, loadSkin, flatArt, kindOf, isAnimated, loopOf, LOOP } from './cosmetics.js';

// ---------------------------------------------------------------------------
// THE ONE SWITCH. While `live` is false the store is a showroom: everything
// can be browsed, tried on and bagged, but Checkout says "Soon" and the
// service is never asked for prices or a checkout. Turn it on only once the
// Stripe keys are in the service (docs/STORE.md). For testing before then,
// store.html?checkout=test opens checkout (while the service holds a Stripe
// TEST key, only the testers it lists can buy).
const STORE = {
  live: false || new URLSearchParams(location.search).get('checkout') === 'test',
  // Where the cosmetics service lives; the same pointer file the mod reads.
  service: 'cosmetics/service.json',
};

// Everything for sale. `price` is only shown until the service's price list
// loads; the service (stripe.js CATALOG, same ids, tested to match) is what's
// charged.
const ITEMS = [
  // Animated capes: the art moves and they give off particles.
  { id: 'cape-orbit', name: 'Orbit Cape', blurb: 'Sparks race round the Origin rings while the glow breathes.', price: 9.99 },
  { id: 'cape-glitch', name: 'Glitch Cape', blurb: 'The Origin mark, split and glitching on a dark screen.', price: 9.99 },
  { id: 'cape-inferno', name: 'Inferno Cape', blurb: 'Living flames with embers rising off them.', price: 8.99 },
  { id: 'cape-aurora', name: 'Aurora Cape', blurb: 'Northern lights that ripple under twinkling stars.', price: 8.99 },
  { id: 'cape-starfall', name: 'Starfall Cape', blurb: 'Shooting stars across a moonlit sky.', price: 8.99 },
  // Still capes.
  { id: 'cape-origin', name: 'Origin Cape', blurb: 'The three rings, glowing on deepslate.', price: 5.99 },
  { id: 'cape-golden', name: 'Golden Hour Cape', blurb: 'The sunset over the Origin island.', price: 5.99 },
  { id: 'cape-frost', name: 'Frost Cape', blurb: 'Ice blue with falling snow.', price: 5.99 },
  { id: 'cape-void', name: 'Void Cape', blurb: 'An ender eye in the dark.', price: 5.99 },
  // Head.
  { id: 'halo-orbit', name: 'Orbit Halo', blurb: 'Three rings orbiting a glowing core, each at its own speed.', price: 7.99 },
  { id: 'crown-pixel', name: 'Pixel Crown', blurb: 'Gold band, jewels, pearls and a velvet cap.', price: 6.99 },
  { id: 'halo-gold', name: 'Golden Halo', blurb: 'A classic glowing ring.', price: 5.99 },
];
const DEFAULT_CURRENCY = 'USD';
// Every price stays between $5 and $10 (Will, 2026-10-12): still items at the
// low end, animated capes at the top.
// ---------------------------------------------------------------------------

const KINDS = { cape: 'Cape', head: 'Hat & halo' };
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
  prices: new Map(),           // item id -> { amount, currency }, from the service
  catalog: 'loading',          // loading | ok | closed | error
  api: '',                     // the service's base URL once known
  trying: null,
  busy: false,
};
let room = null;

// ------------------------------------------------------------- persistence
// Bag and name survive a refresh / the trip to Stripe and back. Storage can be
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
  return state.prices.get(item.id) || { amount: item.price, currency: DEFAULT_CURRENCY };
}
function money(amount, currency) {
  try { return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount); } catch { return `${amount.toFixed(2)} ${currency}`; }
}
// On sale = the service's price list has it (checked once that list is in).
const forSale = item => STORE.live && state.catalog === 'ok' && state.prices.has(item.id);

// ----------------------------------------------------------------- service
async function loadPrices() {
  if (!STORE.live) return;
  try {
    state.api = (await (await fetch(STORE.service)).json()).api;
    const c = await (await fetch(`${state.api}/v1/store/catalog`)).json();
    for (const [id, item] of Object.entries(c.items || {})) state.prices.set(id, { amount: Number(item.price), currency: c.currency || DEFAULT_CURRENCY });
    state.catalog = c.open ? 'ok' : 'closed';
  } catch (err) {
    console.warn('Origin store: the cosmetics service can’t be reached.', err);
    state.catalog = 'error';
  }
  renderGrid();
  renderBag();
}

async function checkout() {
  if (!STORE.live || state.busy || !state.bag.length || !canCheckout() || state.catalog !== 'ok' || notForSale().length) return;
  state.busy = true;
  renderBag();
  try {
    const r = await fetch(`${state.api}/v1/store/checkout`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ uuid: state.player.uuid, name: state.player.name, items: state.bag }),
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok || !j.url) throw new Error(j.error || 'Checkout isn’t available right now. Nothing was charged; please try again in a minute.');
    location.href = j.url;
  } catch (err) {
    state.busy = false;
    // Service errors are already sentences for the buyer; a network failure isn't.
    renderBag(err instanceof TypeError ? 'Couldn’t reach checkout. Nothing was charged; please try again.' : err.message);
  }
}

// -------------------------------------------------------- minecraft names
// Looks the name up so the buyer sees whose head it goes on before paying.
// A lookup that fails to answer (offline, blocked) doesn't block the sale:
// Checkout itself waits for a found name: the cosmetics go to its UUID.
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
      const skin = state.player ? await loadSkin(state.player) : null;
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
    if (j?.success && p) {
      const uuid = (p.raw_id || p.id || '').replace(/-/g, '');
      return { state: 'ok', player: { name: p.username, uuid, avatar: p.avatar, ...skinOf(p) } };
    }
    return { state: 'missing' };
  } catch {
    return { state: 'unknown' };
  }
}

// The skin file and arm model straight from the Mojang profile, when the
// lookup includes it (either as skin_texture or the raw textures property).
function skinOf(p) {
  const out = { skinUrl: p.skin_texture || null, slim: undefined };
  const prop = (p.properties || []).find(x => x.name === 'textures');
  if (prop) {
    try {
      const skin = JSON.parse(atob(prop.value)).textures?.SKIN;
      if (skin?.url) out.skinUrl = skin.url;
      if (skin) out.slim = skin.metadata?.model === 'slim';
    } catch { /* keep what we have */ }
  }
  return out;
}

const canCheckout = () => state.nameState === 'ok' && !!state.player?.uuid;
// Bagged before the service sold it (bags are saved), so it can't be bought yet.
const notForSale = () => state.bag.map(id => byId.get(id)).filter(i => !forSale(i));

function renderName() {
  const head = $('[data-name-head]'), note = $('[data-name-note]'), box = $('[data-name]');
  box.dataset.state = state.nameState;
  if (state.player) {
    head.src = state.player.avatar || `https://crafthead.net/avatar/${state.player.uuid}/48`;
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
    unknown: 'Couldn’t check that name right now. Try again in a moment.',
  }[state.nameState];
}

// ------------------------------------------------------------------ render
function renderGrid() {
  const filter = $('[data-filter][aria-pressed="true"]')?.dataset.filter || 'all';
  const shown = i => filter === 'all' || (filter === 'animated' ? isAnimated(i.id) : kindOf(i.id) === filter);
  $('[data-grid]').innerHTML = ITEMS.filter(shown).map(i => {
    const inBag = state.bag.includes(i.id), p = priceOf(i), soon = STORE.live && state.catalog === 'ok' && !forSale(i);
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
  // A slower item (the Orbit Halo loops over 3x LOOP) gets 3x the frames, so
  // every card plays at the same frame rate.
  const count = id => Math.round((FRAMES * loopOf(id)) / LOOP);
  const todo = ITEMS.filter(i => isAnimated(i.id)).flatMap(i => Array.from({ length: count(i.id) }, (_, n) => [i.id, n]));
  const step = () => {
    const until = performance.now() + 12;
    while (todo.length && performance.now() < until) {
      const [id, n] = todo.shift();
      if (!frames.has(id)) frames.set(id, []);
      frames.get(id)[n] = thumber.frame(id, n, count(id));
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
      const id = img.dataset.anim, f = frames.get(id);
      if (f?.length === count(id)) img.src = f[tick % f.length];
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
  const open = STORE.live && state.catalog !== 'closed';
  btn.toggleAttribute('data-soon', !open);
  const blocked = state.catalog === 'ok' ? notForSale() : [];
  btn.disabled = open && (state.busy || !items.length || !canCheckout() || state.catalog !== 'ok' || blocked.length > 0);
  btn.setAttribute('aria-disabled', String(!open || btn.disabled));
  btn.textContent = state.busy ? 'Opening checkout…' : `Checkout${open && items.length ? ` · ${money(total, currency)}` : ''}`;
  const failed = error || (open && state.catalog === 'error');
  note.classList.toggle('is-error', !!failed);
  note.textContent = error || (!open
    ? 'The store opens soon. Try everything on in the meantime.'
    : state.catalog === 'error' ? 'Checkout can\u2019t be reached right now. Please try again in a minute.'
    : !items.length ? 'Add a cosmetic to get started.'
    : blocked.length ? `${blocked[0].name} isn\u2019t on sale yet. Remove it to check out.`
    : !canCheckout() ? 'Type the Minecraft name these are for.'
    : state.catalog === 'loading' ? 'Loading prices…'
    : 'Secure checkout by Stripe: card, Apple Pay or Google Pay. Sales tax is added at checkout where it applies.');

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
// Stripe sends the buyer back with ?purchase=done or ?purchase=cancelled.
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
