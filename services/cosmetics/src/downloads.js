// The website's live download count (Will, 2026-11-11: "how many people click
// download on the button on the website ... only real downloads").
//   POST /v1/downloads  a click on originclient.org's Download button
//   GET  /v1/downloads  { count }
// What keeps the number real:
//   - only clicks whose Origin is the live site count, so local previews and
//     Vercel preview builds never do (a browser can't change Origin; a script
//     can, which the next two limits are for);
//   - one count per address per day, IPv6 by its /64 (one home gets a whole
//     /64, so counting single v6 addresses would let one PC count millions);
//   - at most DAILY_CAP new counts a day, so a flood can't eat the database's
//     write budget, which Stripe and social share;
//   - the site itself counts each browser once and skips Will's own browser.
// A click that doesn't count still answers 200 with the total; the button
// never waits on this (site.js sends it with keepalive and moves on).
import { json } from './common.js';

const SITES = new Set(['https://originclient.org', 'https://www.originclient.org']);
const DAY = 86_400_000;
const DAILY_CAP = 5000;

async function sha256(text) {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, '0')).join('');
}

// IPv4 as is; IPv6 cut to its first four groups (the /64 one connection gets).
export function addressKey(ip) {
  if (!ip.includes(':')) return ip;
  const groups = ip.toLowerCase().split('::');
  const head = groups[0] ? groups[0].split(':') : [];
  const tail = groups.length > 1 && groups[1] ? groups[1].split(':') : [];
  const full = [...head, ...Array(Math.max(0, 8 - head.length - tail.length)).fill('0'), ...tail];
  return full.slice(0, 4).map(g => g.replace(/^0+(?=.)/, '')).join(':') + '::/64';
}

// Today's random salt, made on the day's first click. Deleting it the next day
// makes that day's codes impossible to reverse, even for us.
async function saltFor(env, day) {
  const fresh = [...crypto.getRandomValues(new Uint8Array(16))].map(b => b.toString(16).padStart(2, '0')).join('');
  await env.DB.prepare('INSERT OR IGNORE INTO download_salt (day, salt) VALUES (?, ?)').bind(day, fresh).run();
  return env.DB.prepare('SELECT salt FROM download_salt WHERE day = ?').bind(day).first('salt');
}

async function total(env) {
  return (await env.DB.prepare(`SELECT n FROM counters WHERE name = 'downloads'`).first('n')) ?? 0;
}

export async function downloadCount(env) {
  return json({ count: await total(env) });
}

export async function countDownload(request, env, now = Date.now()) {
  const ip = request.headers.get('CF-Connecting-IP');
  const skip = async () => json({ count: await total(env), counted: false });
  if (!SITES.has(request.headers.get('Origin')) || !ip) return skip();
  const day = Math.floor(now / DAY);
  const today = await env.DB.prepare('SELECT COUNT(*) AS n FROM download_clicks WHERE day = ?').bind(day).first('n');
  if (today >= DAILY_CAP) return skip();
  const who = await sha256(`${await saltFor(env, day)}|${addressKey(ip)}`);
  // The insert is the gate: only the first click of the day for this address
  // gets a row back, and only then does the total move.
  const fresh = await env.DB.prepare('INSERT OR IGNORE INTO download_clicks (who, day) VALUES (?, ?) RETURNING who').bind(who, day).first();
  if (!fresh) return skip();
  const n = await env.DB.prepare(`UPDATE counters SET n = n + 1 WHERE name = 'downloads' RETURNING n`).first('n');
  return json({ count: n ?? 0, counted: true });
}

// Cron: earlier days' click rows and salts are no longer needed.
export async function downloadCleanup(env, now = Date.now()) {
  const day = Math.floor(now / DAY);
  await env.DB.batch([
    env.DB.prepare('DELETE FROM download_clicks WHERE day < ?').bind(day),
    env.DB.prepare('DELETE FROM download_salt WHERE day < ?').bind(day),
  ]);
}
