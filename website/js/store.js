// Origin Store: pick cosmetics, type a Minecraft name, pay through Stripe.
//
// This file owns the shop window and the bag; cosmetics.js owns how each item
// looks (same ids). Prices and payment belong to the cosmetics service
// (services/cosmetics/src/stripe.js): the page asks it for a Stripe Checkout
// for the bag and the typed player's UUID, and sends the buyer to Stripe's own
// checkout page. Stripe tells the service when the money arrives, and the
// service gives that player the items. Setup: docs/STORE.md.
import { createFittingRoom, createThumbnailer, loadSkin, loadCatalog, prepare, getSource, resolvedSource, loopOf, STILL_T } from './cosmetics.js';

// ---------------------------------------------------------------------------
// THE ONE SWITCH. While `live` is false the store is a showroom: everything
// can be browsed, tried on and bagged, but Checkout says "Soon" and the
// service is never asked for prices or a checkout. Turn it on only once the
// Stripe keys are in the service (docs/STORE.md). While the service has no
// key, its catalog says open: false and Checkout keeps saying "Soon".
const STORE = {
  live: true,   // Will, 2026-10-17: live now; Checkout stays "Soon" until the service reports open
  // Where the cosmetics service lives; the same pointer file the mod reads.
  service: 'cosmetics/service.json',
};

// WHAT the store shows comes from cosmetics/catalog.json (written by
// tools/cosmetics/build.mjs, one entry per drawn item: name, blurb, price, slot,
// animated). This literal list is the service's price list as the page last knew
// it: `price` is only shown until the service's own list loads (the service,
// stripe.js CATALOG, is what's charged), and the list stands in for the catalog
// if catalog.json can't be fetched. A service test keeps it identical to the
// for-sale part of services/cosmetics/src/catalog.gen.js, so it has to stay one
// entry per line in exactly this shape.
const PRICE_LIST = [
  { id: 'cape-orbit', name: 'Orbit Cape', blurb: 'Sparks race round the Origin rings while the glow breathes.', price: 9.99 },
  { id: 'cape-glitch', name: 'Glitch Cape', blurb: 'The Origin mark, split and glitching on a dark screen.', price: 9.99 },
  { id: 'cape-inferno', name: 'Inferno Cape', blurb: 'Living flames with embers rising off them.', price: 8.99 },
  { id: 'cape-aurora', name: 'Aurora Cape', blurb: 'Northern lights that ripple under twinkling stars.', price: 8.99 },
  { id: 'cape-starfall', name: 'Starfall Cape', blurb: 'Shooting stars across a moonlit sky.', price: 8.99 },
  { id: 'cape-origin', name: 'Origin Cape', blurb: 'The three rings, stitched in silver on dark woven cloth.', price: 5.99 },
  { id: 'cape-golden', name: 'Golden Hour Cape', blurb: 'The sunset over the Origin island.', price: 5.99 },
  { id: 'cape-frost', name: 'Frost Cape', blurb: 'Ice blue with falling snow.', price: 5.99 },
  { id: 'cape-void', name: 'Void Cape', blurb: 'An ender eye in the dark.', price: 5.99 },
  { id: 'wings-ember', name: 'Ember Wings', blurb: 'Obsidian wings with fire running through the veins.', price: 9.99 },
  { id: 'halo-orbit', name: 'Orbit Halo', blurb: 'Three rings orbiting a glowing core, each at its own speed.', price: 7.99 },
  { id: 'halo-gold', name: 'Golden Halo', blurb: 'A classic glowing ring.', price: 5.99 },
];
// Items the store shows: the catalog once loaded, else the price list. Each: { id, name, blurb, price, kind, slot, animated }.
let ITEMS = [];
const DEFAULT_CURRENCY = 'USD';
// Origin+ membership. Like `price` above, these only show until the service's
// catalog answers; the service (stripe.js PLUS) is what's charged, and it
// computes the member discount itself. The page only previews it.
const PLUS_FALLBACK = {
  month: 4.99, year: 39.99, percentOff: 20,
  manageUrl: 'https://billing.stripe.com/p/login/eVqfZi2uF97zdlEgr40co00',   // Stripe's sign-in page: cancel, change card, receipts
};
// Every price stays between $5 and $10 (Will, 2026-10-12): still items at the
// low end, animated capes at the top.
// ---------------------------------------------------------------------------

const KINDS = { cape: 'Cape', wings: 'Wings', pack: 'Backpack', mask: 'Mask', bandana: 'Bandana', hat: 'Hat', halo: 'Halo', crown: 'Crown', theme: 'Menu theme' };
// The shop's filter tabs (store.html data-filter): which items each one shows.
const FILTERS = {
  all: () => true,
  animated: i => i.animated,
  cape: i => i.slot === 'cape',
  back: i => i.slot === 'back',
  wear: i => i.slot === 'head' || i.slot === 'face',
  theme: i => i.slot === 'theme',
};
const EMPTY_NOTE = { theme: 'Menu themes are on their way.', back: 'Wings and backpacks are on their way.', wear: 'More hats and masks are on their way.', animated: 'No animated items yet.', cape: 'No capes yet.' };
const SLOTS = ['cape', 'back', 'head', 'face', 'theme'];
const SAVE_KEY = 'origin.store.v1';
const NAME_RE = /^[A-Za-z0-9_]{3,16}$/;   // Java Edition usernames

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
let byId = new Map();
const reducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches;
const smoothScroll = () => (reducedMotion() ? 'auto' : 'smooth');
const esc = s => String(s).replace(/[&<>"']/g, c => `&#${c.charCodeAt(0)};`);

const state = {
  bag: [],                     // item ids
  name: '',                    // as typed
  player: null,                // { name, uuid } once verified
  nameState: 'empty',          // empty | bad | checking | ok | missing | unknown
  prices: new Map(),           // item id -> { amount, member, currency }, from the service
  plus: PLUS_FALLBACK,         // Origin+ prices + manage link (the service's, once loaded)
  plan: 'month',               // month | year, the Origin+ billing toggle
  member: null,                // is the typed player an Origin+ member? null until known
  plusBusy: false,
  plusError: '',
  plusPending: 0,              // membership re-checks left after returning from joining
  catalog: 'loading',          // loading | ok | closed | error
  api: '',                     // the service's base URL once known
  plusCape: false,             // is the members' cape drawn yet (cape-plus in catalog.json)?
  worn: {},                    // slot -> item id: what the fitting room wears (one item per slot)
  themeView: true,             // a worn theme shows its menu preview instead of the player
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
    state.bag = Array.isArray(s.bag) ? s.bag.filter(id => typeof id === 'string') : [];   // pruned against the catalog in boot()
    state.name = typeof s.name === 'string' ? s.name : '';
  } catch { /* fresh start */ }
}

// ------------------------------------------------------------------ prices
function priceOf(item) {
  return state.prices.get(item.id) || { amount: item.price, member: null, currency: DEFAULT_CURRENCY };
}
// What the buyer pays for an item: the member price while the typed player is a
// member (a preview; the service works out the real amount at checkout).
const memberDeal = item => state.member === true && priceOf(item).member != null;
const payOf = item => (memberDeal(item) ? priceOf(item).member : priceOf(item).amount);
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
    for (const [id, item] of Object.entries(c.items || {})) {
      state.prices.set(id, { amount: Number(item.price), member: item.memberPrice == null ? null : Number(item.memberPrice), currency: c.currency || DEFAULT_CURRENCY });
    }
    if (c.plus && Number(c.plus.month) > 0 && Number(c.plus.year) > 0) {
      state.plus = { ...PLUS_FALLBACK, ...c.plus, month: Number(c.plus.month), year: Number(c.plus.year) };
    }
    state.catalog = c.open ? 'ok' : 'closed';
  } catch (err) {
    console.warn('Origin store: the cosmetics service can’t be reached.', err);
    state.catalog = 'error';
  }
  renderGrid();
  renderBag();
  renderPlus();
  checkMember();     // a name may have been verified before the service answered
}

// Is the verified player an Origin+ member? Only affects the preview prices and
// the membership card; a failed lookup just means "not shown as a member".
async function checkMember() {
  const player = state.player;
  if (!STORE.live || !state.api || !player?.uuid) return;
  let member = null;
  try {
    const r = await fetch(`${state.api}/v1/players/${encodeURIComponent(player.uuid)}`);
    if (r.ok) member = !!(await r.json()).plus;
  } catch { /* leave unknown */ }
  if (state.player !== player) return;   // the name changed meanwhile
  // Back from joining: Stripe's word can take a few seconds to reach the
  // service, so look again a few times before showing them as not a member.
  if (!member && state.plusPending > 0) { state.plusPending--; setTimeout(checkMember, 4000); }
  if (state.member === member) return;
  state.member = member;
  renderGrid();
  renderBag();
  renderPlus();
}

async function joinPlus() {
  if (!STORE.live || state.plusBusy || state.catalog !== 'ok' || state.member) return;
  if (!canCheckout()) {         // the name box lives with the bag, which is below the shop on phones
    state.plusError = 'Type the Minecraft name the membership is for.';
    renderPlus();
    $('#checkout').scrollIntoView({ behavior: smoothScroll(), block: 'start' });
    setTimeout(() => $('#mc-name').focus({ preventScroll: true }), reducedMotion() ? 0 : 400);
    return;
  }
  state.plusBusy = true;
  state.plusError = '';
  renderPlus();
  try {
    const r = await fetch(`${state.api}/v1/plus/checkout`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ uuid: state.player.uuid, name: state.player.name, plan: state.plan }),
    });
    const j = await r.json().catch(() => ({}));
    if (r.status === 409) {                          // already a member: show that instead of an error
      state.plusBusy = false;
      state.member = true;
      renderGrid(); renderBag(); renderPlus();
      return;
    }
    if (!r.ok || !j.url) throw new Error(j.error || 'Membership isn’t available right now. Nothing was charged; please try again in a minute.');
    location.href = j.url;
  } catch (err) {
    state.plusBusy = false;
    state.plusError = err instanceof TypeError ? 'Couldn’t reach checkout. Nothing was charged; please try again.' : err.message;
    renderPlus();
  }
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
  const wasMember = state.member;
  state.member = null;
  state.plusError = '';
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
      renderPlus();
      checkMember();
      const skin = state.player ? await loadSkin(state.player) : null;
      if (room && seq === lookupSeq) room.setSkin(skin);
    }, 450);
  }
  if (room && state.nameState !== 'checking') room.setSkin(null);
  renderName();
  if (wasMember) renderGrid();     // member prices belong to the old name
  renderBag();
  renderPlus();
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
const wornIds = () => SLOTS.map(sl => state.worn[sl]).filter(Boolean);
const isWorn = id => wornIds().includes(id);
function renderGrid() {
  const filter = $('[data-filter][aria-pressed="true"]')?.dataset.filter || 'all';
  const list = ITEMS.filter(FILTERS[filter] || FILTERS.all);
  $('[data-grid]').innerHTML = list.length ? list.map(i => {
    const inBag = state.bag.includes(i.id), p = priceOf(i), soon = STORE.live && state.catalog === 'ok' && !forSale(i), on = isWorn(i.id);
    return `<article class="item${on ? ' is-trying' : ''}" data-item="${i.id}">
      <button class="item__try" type="button" data-try aria-label="${on ? 'Take off' : 'Try on'} ${esc(i.name)}" aria-pressed="${on}">
        <span class="item__art" data-art></span><span class="item__kind">${KINDS[i.kind] || 'Cosmetic'}</span>${i.animated ? '<span class="item__anim">Animated</span>' : ''}<span class="item__tryon">${on ? 'Wearing' : 'Try on'}</span>
      </button>
      <div class="item__meta"><h3>${esc(i.name)}</h3><p>${esc(i.blurb)}</p></div>
      <div class="item__buy">${memberDeal(i)
        ? `<b class="item__deal"><s><span class="sr">Was </span>${money(p.amount, p.currency)}</s> <span class="sr">Member price </span>${money(p.member, p.currency)}</b>`
        : `<b>${money(p.amount, p.currency)}</b>`}
        <button class="btn btn--sm ${inBag ? 'btn--ghost' : 'btn--primary'}" type="button" data-add ${soon ? 'disabled data-soon' : ''} aria-pressed="${inBag}">${inBag ? 'In bag ✓' : 'Add to bag'}</button>
      </div>
    </article>`;
  }).join('') : `<p class="shop__empty">${state.catalog === 'loading' && !ITEMS.length ? 'Loading…' : esc(EMPTY_NOTE[filter] || 'Nothing here yet.')}</p>`;
  paintArt();
}

// Card pictures. A theme's card IS its menu preview; everything else is a render of the
// item on the mannequin (a still, plus a looping set of frames for animated ones).
let thumbs = null;          // id -> still picture (data URL), or null when WebGL is out
const frames = new Map();   // id -> looping frames for animated items, filled in the background
const base = () => new URL('cosmetics', document.baseURI).href;
function paintArt() {
  for (const card of $$('[data-item]')) {
    const id = card.dataset.item, item = byId.get(id), slot = $('[data-art]', card);
    if (item.slot === 'theme') slot.innerHTML = `<img class="item__theme" src="${base()}/${id}/texture.png" alt="" width="320" height="256">`;
    else if (thumbs?.has(id)) slot.innerHTML = `<img src="${thumbs.get(id)}" alt="" width="320" height="320"${item.animated ? ` data-anim="${id}"` : ''}>`;
    else slot.replaceChildren(flatFallback(item));
  }
}

// Animated items play on their cards at 10 frames a second. The frames are rendered a few at
// a time after the page is up, so the store never stalls; until an item's loop is ready its
// card shows the still.
const CARD_FPS = 10, CARD_LOOP_MAX = 3.6;
const cardFrames = id => Math.max(2, Math.round(Math.min(loopOf(id), CARD_LOOP_MAX) * CARD_FPS));
function animateCards(thumber) {
  const todo = ITEMS.filter(i => i.animated && i.slot !== 'theme').flatMap(i => Array.from({ length: cardFrames(i.id) }, (_, n) => [i.id, n]));
  const step = () => {
    const until = performance.now() + 12;
    while (todo.length && performance.now() < until) {
      const [id, n] = todo.shift();
      if (!frames.has(id)) frames.set(id, []);
      frames.get(id)[n] = thumber.frame(id, n, cardFrames(id));
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
      if (f?.length === cardFrames(id)) img.src = f[tick % f.length];
    }
  }, 1000 / CARD_FPS);
}
// No 3D (or not rendered yet): a cape shows its own outside face cut from its texture, anything
// else the Origin mark.
function flatFallback(item) {
  const src = item.cape && resolvedSource(item.id);
  if (src) {
    const S = src.item.cape.scale, c = document.createElement('canvas');
    c.width = 10 * S; c.height = 16 * S;
    c.getContext('2d').drawImage(src.image, S, S, 10 * S, 16 * S, 0, 0, c.width, c.height);
    const img = new Image();
    img.src = c.toDataURL();
    img.alt = '';
    img.className = 'item__flat item__flat--cape';
    return img;
  }
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('class', 'item__mark');
  s.innerHTML = '<use href="#mark"/>';
  return s;
}

function renderBag(error = '') {
  const items = state.bag.map(id => byId.get(id));
  $('[data-bag]').innerHTML = items.length
    ? items.map(i => `<li><span>${esc(i.name)}</span><b>${money(payOf(i), priceOf(i).currency)}</b><button type="button" data-remove="${i.id}" aria-label="Remove ${esc(i.name)}">×</button></li>`).join('')
    : '<li class="bag__empty">Your bag is empty. Add something from the store.</li>';
  const currency = items.length ? priceOf(items[0]).currency : DEFAULT_CURRENCY;
  const total = items.reduce((sum, i) => sum + payOf(i), 0);
  $('[data-total]').textContent = money(total, currency);
  $('[data-bag-member]').hidden = !(state.member === true && items.some(memberDeal));

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
    : 'Secure checkout by Stripe: card, plus Apple Pay or Google Pay on devices that support them. Sales tax is added at checkout where it applies.');

  const bar = $('[data-bagbar]');
  bar.hidden = !items.length;
  $('[data-bagbar-text]').textContent = `${items.length} ${items.length === 1 ? 'item' : 'items'} · ${money(total, currency)}`;
  $('[data-bag-count]').textContent = items.length ? String(items.length) : '';
}

// ------------------------------------------------------------------ Origin+
// The members-only cape (cape-plus) isn't sold, so it isn't in the shop grid; its art, once
// drawn (cosmetics/items/cape-plus), is only the picture on the membership card. Until then
// the picture stays hidden.
async function paintPlusCape() {
  if (!state.plusCape) return;
  try {
    const { item, image } = await getSource('cape-plus');
    const S = item.cape.scale, c = document.createElement('canvas'), img = $('[data-plus-cape]');
    c.width = 10 * S; c.height = 16 * S;
    c.getContext('2d').drawImage(image, S, S, 10 * S, 16 * S, 0, 0, c.width, c.height);
    img.src = c.toDataURL();
    img.hidden = false;
  } catch { /* no cape-plus art yet: the card has no cape picture */ }
}
const percentSaved = () => Math.round((1 - state.plus.year / (state.plus.month * 12)) * 100);

function renderPlus() {
  const plus = state.plus, cur = DEFAULT_CURRENCY, year = state.plan === 'year';
  const open = STORE.live && state.catalog !== 'closed';
  const member = state.member === true;

  $$('[data-plan]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.plan === state.plan)));
  $('[data-plan-save]').textContent = `Save ${percentSaved()}%`;
  $('[data-plus-off]').textContent = `${plus.percentOff}%`;
  $('[data-plus-price]').textContent = money(year ? plus.year : plus.month, cur);
  $('[data-plus-per]').textContent = year ? 'per year' : 'per month';
  $('[data-plus-sub]').textContent = year
    ? `About ${money(plus.year / 12, cur)} a month, ${percentSaved()}% less than paying monthly.`
    : 'Switch to yearly to save.';
  $('[data-plus-fine]').textContent = `Renews automatically every ${year ? 'year' : 'month'} until you cancel. Cancel any time: your perks last until the end of the period you’ve paid for. Taxes are added at checkout where they apply.`;

  // A member sees their status instead of the Join controls.
  $('[data-plus-join]').hidden = member;
  const box = $('[data-plus-member]');
  box.hidden = !member;
  if (member) box.innerHTML = `<b>${esc(state.player?.name || 'This player')} is an Origin+ member.</b> Member prices are shown in the store and applied at checkout.`;

  const manage = $('[data-plus-manage]');
  if (/^https:\/\//.test(plus.manageUrl)) manage.href = plus.manageUrl;

  const btn = $('[data-plus-go]'), note = $('[data-plus-note]');
  const ready = state.catalog === 'ok';
  btn.toggleAttribute('data-soon', !open);
  btn.disabled = open && (state.plusBusy || !ready);
  btn.setAttribute('aria-disabled', String(!open || btn.disabled));
  btn.textContent = state.plusBusy ? 'Opening checkout…' : canCheckout() || !open
    ? `Join Origin+${open && canCheckout() ? ` · ${money(year ? plus.year : plus.month, cur)}` : ''}`
    : 'Choose a Minecraft name';
  note.classList.toggle('is-error', !!state.plusError || (open && state.catalog === 'error'));
  note.textContent = state.plusError || (!open
    ? 'Memberships open soon.'
    : state.catalog === 'error' ? 'Membership can’t be reached right now. Please try again in a minute.'
    : state.catalog === 'loading' ? 'Loading…'
    : canCheckout() ? `Membership goes to ${state.player.name}.`
    : 'It goes to the Minecraft name you type. Gifts welcome.');
}

// One item per slot: trying a second one in the same slot swaps it, trying a worn one takes it off.
function tryOn(id) {
  const item = byId.get(id);
  if (!item) return;
  const worn = state.worn[item.slot] === id;
  if (worn) delete state.worn[item.slot]; else state.worn[item.slot] = id;
  if (item.slot === 'theme') state.themeView = !worn;
  syncFit(worn ? null : id);
}
function clearWorn() {
  state.worn = {};
  syncFit(null);
}
// Push what's worn to the 3D room (or the flat fallback), the label, the chips and the cards.
function syncFit(focus) {
  const items = wornIds().map(id => byId.get(id));
  const theme = items.find(i => i.slot === 'theme'), gear = items.filter(i => i.slot !== 'theme');
  room?.wear(gear.map(i => i.id), gear.some(i => i.id === focus) ? focus : gear[gear.length - 1]?.id);
  const names = items.map(i => i.name).join(' + ');
  const hidden = gear.some(i => i.hidesCape) && gear.some(i => i.slot === 'cape');
  $('[data-fit-label]').textContent = items.length ? `Wearing: ${names}${hidden ? ' (wings hide the cape)' : ''}` : 'Pick something to try it on';
  const showTheme = !!theme && state.themeView;
  const themeImg = $('[data-fit-theme-img]');
  themeImg.hidden = !showTheme;
  if (showTheme) { themeImg.src = `${base()}/${theme.id}/texture.png`; themeImg.alt = `Preview of the Origin menus in the ${theme.name} theme`; }
  $('.fit__stage').classList.toggle('is-theme', showTheme);
  const toggle = $('[data-fit-themeview]');
  toggle.hidden = !theme;
  toggle.textContent = state.themeView ? 'Show player' : 'Show menu';
  $('[data-fit-clear]').hidden = !items.length;
  if (!room) {
    const flat = $('[data-fit-flat]');
    const pick = gear[gear.length - 1];
    flat.replaceChildren(...(pick ? [flatFallback(pick)] : []));
  }
  for (const card of $$('[data-item]')) {
    const on = isWorn(card.dataset.item);
    card.classList.toggle('is-trying', on);
    const b = $('[data-try]', card);
    b.setAttribute('aria-pressed', String(on));
    b.setAttribute('aria-label', `${on ? 'Take off' : 'Try on'} ${byId.get(card.dataset.item).name}`);
    $('.item__tryon', b).textContent = on ? 'Wearing' : 'Try on';
  }
}

function toggleBag(id) {
  const at = state.bag.indexOf(id);
  if (at >= 0) state.bag.splice(at, 1);
  else {
    state.bag.push(id);
    if (!isWorn(id)) tryOn(id);
  }
  save();
  renderGrid();
  renderBag();
}

// --------------------------------------------------------------- returning
// Stripe sends the buyer back with ?purchase=done or ?purchase=cancelled.
function handleReturn() {
  const q = new URLSearchParams(location.search), result = q.get('purchase'), plus = q.get('plus');
  if (!result && !plus) return;
  const notice = $('[data-notice]');
  if (plus === 'done') {
    notice.innerHTML = '<b>Welcome to Origin+.</b> It can take a minute to show as active. A receipt is on its way to your email.';
    state.plusPending = 5;
  } else if (plus) {
    notice.innerHTML = '<b>No charge made.</b> You can join Origin+ any time.';
  } else if (result === 'done') {
    notice.innerHTML = `<b>Thank you!</b> Your cosmetics will show up on <b>${esc(state.player?.name || state.name || 'your player')}</b> within a minute. A receipt is on its way to your email.`;
    state.bag = [];
    save();
  } else {
    notice.innerHTML = '<b>Checkout cancelled.</b> Nothing was charged, and your bag is still here.';
  }
  notice.hidden = false;
  history.replaceState(null, '', location.pathname);
}

// -------------------------------------------------------------------- boot
const SLOT_OF = { cape: 'cape', wings: 'back', pack: 'back', mask: 'face', bandana: 'face', hat: 'head', halo: 'head', crown: 'head', theme: 'theme' };
// What the shop shows: cosmetics/catalog.json (the drawn items), else the literal price list.
async function loadItems() {
  let list = null;
  try {
    const raw = await loadCatalog(base());
    state.plusCape = raw.some(i => i.id === 'cape-plus');
    list = raw.filter(i => i && i.id && i.onSale !== false && !i.plusOnly);
  } catch (err) {
    console.warn('Origin store: catalog.json can\u2019t be read, using the built-in list.', err);
  }
  if (!list?.length) {
    list = PRICE_LIST.map(p => {
      const kind = p.id.split('-')[0];
      return { ...p, kind, slot: SLOT_OF[kind] || 'cape', cents: Math.round(p.price * 100), animated: false };
    });
  }
  ITEMS = list.map(i => ({ ...i, price: i.cents / 100 }));
  byId = new Map(ITEMS.map(i => [i.id, i]));
  state.bag = state.bag.filter(id => byId.has(id));     // a saved bag may name an item that is gone
  save();
}

async function boot() {
  restore();
  handleReturn();
  await loadItems();

  const input = $('#mc-name');
  input.value = state.name;
  input.addEventListener('input', () => onName(input.value));

  $$('[data-plan]').forEach(b => b.addEventListener('click', () => {
    state.plan = b.dataset.plan;
    renderPlus();
  }));
  $('[data-plus-go]').addEventListener('click', joinPlus);
  paintPlusCape();

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
      if (isWorn(card.dataset.item) && stage.getBoundingClientRect().bottom < 60) stage.scrollIntoView({ behavior: smoothScroll(), block: 'start' });
    }
  });
  $('[data-fit-clear]').addEventListener('click', clearWorn);
  $('[data-fit-themeview]').addEventListener('click', () => {
    state.themeView = !state.themeView;
    syncFit(null);
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

  // Draw the grid right away (flat pictures), then swap in renders once the art is loaded.
  renderGrid();
  renderBag();
  renderName();
  renderPlus();
  if (state.name) onName(state.name);
  loadPrices();

  let loaded = true;
  try {
    await prepare(ITEMS.filter(i => i.slot !== 'theme').map(i => i.id));
  } catch (err) {
    console.warn('Origin store: item art can\u2019t be loaded.', err);
    loaded = false;
  }
  let thumber = null;
  if (loaded) {
    try {
      room = createFittingRoom($('[data-fit]'), { reducedMotion: reducedMotion() });
    } catch (err) {
      console.warn('Origin store: 3D preview unavailable, showing flat art.', err);
      document.body.classList.add('no-webgl');
    }
    try {
      thumber = createThumbnailer();
      thumbs = new Map(ITEMS.filter(i => i.slot !== 'theme').map(i => [i.id, thumber.still(i.id)]));
    } catch (err) {
      console.warn('Origin store: card pictures unavailable, showing flat art.', err);
      thumbs = null;
      thumber = null;
    }
  } else document.body.classList.add('no-webgl');

  renderGrid();
  const first = ITEMS.find(i => i.slot !== 'theme') || ITEMS[0];
  if (first) tryOn(first.id);
  if (thumber && !reducedMotion()) animateCards(thumber);
  else thumber?.dispose();
}
boot();
