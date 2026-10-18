# Origin social: friends, chat, parties, players online

Will, 2026-10-12: "make an active players on the right side [of the launcher] ... updates every 5 minutes
showing the active players playing Origin, and come up with a friends aspect: you can add people, chat with
them and join their server, also add them to a party so in any server you can have a party aspect even if the
server doesn't have parties."

His choices:
- Friends live in BOTH the launcher and the game.
- Add friends by Minecraft username → friend request → accept. Both must use Origin. "Appear offline" option.
- Party: all of A party chat, B party names highlighted + marker through walls, C party HUD (health, distance,
  direction), D party on minimap + locator bar, E "join party" (one click puts the party on the leader's
  server), F share a ping/waypoint with the party.
- Cloudflare FREE plan → no WebSockets/Durable Objects: clients poll one endpoint (`/v1/sync`) every few
  seconds while something live is happening, slowly otherwise (budget below).
- "Players online" counts anyone with the launcher OR the game open, on Origin.

This file is the CONTRACT between the three parts. Change it only together with every side.

## Where it runs
The existing Worker `services/cosmetics` (live: https://origin-cosmetics.will-f11.workers.dev, D1 database
`origin-cosmetics`, deployed from `main` by .github/workflows/cosmetics-service.yml, which applies migrations
first). Social tables arrive in a new migration. Same base URL for every client; the mod already resolves it
(`-Dorigin.cosmetics.api=<url>` overrides it in dev); the launcher gets one constant next to its other URLs.

## Identity v2 (2026-10-12): Mojang-signed proofs, verified offline — no Mojang call from the service
v1 (Mojang hasJoined from the Worker) never worked live: Mojang answers every request from Cloudflare Workers
with 403 (proven by /v1/diag/mojang after deploy). The api.mojang.com name lookup is blocked the same way.
So the client brings two things Mojang already signs for every player, and the Worker checks Mojang's
signatures itself with Mojang's published keys. Still no Microsoft/Mojang token ever reaches Origin.

The client (launcher and game, every version) fetches, with the player's own Minecraft access token:
1. its PLAYER CERTIFICATE — `POST https://api.minecraftservices.com/player/certificates` (Bearer <MC access
   token>, empty body) → `{ keyPair: { privateKey, publicKey }, publicKeySignatureV2, expiresAt, ... }`.
   privateKey is PKCS#8 and publicKey is X.509 SubjectPublicKeyInfo, both base64 inside "-----BEGIN RSA ...
   KEY-----" PEM headers (Minecraft's own Crypt strips the headers the same way). The private key never
   leaves the client. Cache it until `refreshedAfter`/`expiresAt`.
2. its SIGNED PROFILE — `GET https://sessionserver.mojang.com/session/minecraft/profile/<uuid>?unsigned=false`
   → `properties[name=="textures"] { value, signature }` (value = base64 JSON with profileId, profileName,
   timestamp).
Then it signs `origin-auth|<uuid undashed>|<client>|<now ms>` (UTF-8) with the certificate's private key,
RSASSA-PKCS1-v1_5 + SHA-256, and sends:

    POST /v1/auth   { client: "game"|"launcher", uuid, name, at: <ms used in the signed string>,
                      sig: <base64>, publicKey: <base64 SPKI DER>, keyExpiresAt: <ms from certificate>,
                      keySignature: <base64 publicKeySignatureV2>,
                      textures: { value, signature } }
    200 { token, uuid, name, expiresAt }      // token valid 24 h, as before
    401 { error }                             // a check failed (player-facing sentence, no internals)
    400 { error }                             // malformed body: "Please update Origin and try again."
    503 { error }                             // the service holds no Mojang keys yet: try again later

The service verifies, all offline:
- the certificate: Mojang's `publicKeySignatureV2` (RSASSA-PKCS1-v1_5 + SHA-1) over the bytes
  uuid-most-significant-64 ‖ uuid-least-significant-64 ‖ keyExpiresAt (int64 big-endian) ‖ publicKey DER,
  against any of Mojang's `playerCertificateKeys`; keyExpiresAt in the future;
- possession: `sig` over the signed string with that publicKey; `at` within ±5 min of now; each sig used once
  (reuse the `joins` table keyed by the sig's hash);
- the name: Mojang's `textures.signature` (RSASSA-PKCS1-v1_5 + SHA-1) over the raw `textures.value` string,
  against any of Mojang's `profilePropertyKeys`; decoded profileId == uuid; profileName == name (exact);
  timestamp no older than 24 h.
Mojang's public keys come from https://api.minecraftservices.com/publickeys, fetched by GitHub Actions (not
the Worker — blocked) on every deploy and by a weekly scheduled workflow, and stored where the Worker reads them
(D1 `meta`). Accounts that can't get a certificate (multiplayer disabled on the Microsoft account) get "Your
Microsoft account doesn't allow online play, so friends can't be used."

Every social call sends `Authorization: Bearer <token>`; a 401 → re-run /v1/auth once, then retry. Tokens
stored hashed. Offline accounts (and OfflineTestMode) get no social features — the UI says so.
Friend requests by name look the name up in Origin's own `names` table (only verified Origin players, kept
current by every /v1/auth) — never api.mojang.com. `/v1/equip` (cosmetics) takes the same Bearer token instead
of its old serverId proof (the old body is refused with an "update Origin" sentence).

## Players online (no auth)
    GET /v1/stats/online     200 { online: <int>, at: <ms> }
Distinct accounts with a sync from the launcher or the game in the last 5 minutes (invisible ones counted:
it's an anonymous number). Cached ~60 s server side. The launcher calls it on start and every 5 minutes.

## The one polling call
    POST /v1/sync
    { since: <cursor or 0>,
      friendsEtag: "<last friendsEtag>" | absent,         // optional (2026-10-12): skip an unchanged friends list
      presence: { client: "game"|"launcher",
                  activity: "menu" | "singleplayer" | "server",
                  server: "host[:port]" | null,          // only when activity == "server"
                  version: "1.21.1" },                   // Minecraft version being played / selected
      partyState: null | { server, dim: "minecraft:overworld", x, y, z, health, maxHealth },  // game only, only in a party,
                                                         // only while the last answer said wantState: true
      stateServer: "host[:port]" | absent }              // optional: where partyState WOULD be shared while held back
    200 {
      cursor,                                            // pass back as `since`
      me: { uuid, name, appearOffline },
      friendsEtag: "<string>",                           // always; send back as `friendsEtag`
      friends: [ { uuid, name, online, client, activity, server, version, lastSeen } ],   // full list, online first
      requests: { incoming: [ { uuid, name, at } ], outgoing: [ { uuid, name, at } ] },
      blocked: [ { uuid, name } ],
                                   // friends, requests and blocked are OMITTED when the sent friendsEtag is current:
                                   // keep the copy you have. Clients that never send friendsEtag always get them.
      wantState: true | false,     // true only when you're in a party and another online member is on your server
      messages: [ { id, from, to, text, at } ],          // DMs to/from me newer than `since`
      party: null | { id, leader, members: [ { uuid, name, online, state, stateAt } ] },   // state as partyState
      partyMessages: [ { id, from, name, text, at } ],   // newer than `since`
      pings: [ { id, from, name, server, dim, x, y, z, label, at, expiresAt } ],          // still active
      partyInvites: [ { partyId, from, name, at } ],
      warp: null | { server, version, at, from }         // leader asked the party to join them (last 2 min)
    }
- Presence is per (account, client): launcher and game both open = one online account. Online = synced in
  the last 150 s. A friend who set appearOffline shows `online:false`, no activity, to friends (party members
  still get its party state, since they're in a party together).
- `server` is only shown to friends (never to strangers), and `activity/server` are null while appearOffline.
- Party member `state` is returned only to members of that party, and only for members whose `partyState.server`
  equals the caller's own current server (positions are meaningless across servers).
- `wantState` (free-plan saving): the game sends `partyState` only while the previous sync answered
  `wantState: true`; while it is false it sends `stateServer` instead (the same normalised key it would put in
  `partyState.server`, e.g. the LAN world's address) so the service can tell it when someone joined. Every party
  member must use the identical key for the same world. A `partyState` sent anyway is still accepted.
- `friendsEtag` (free-plan saving): opaque string; it changes whenever anything in `friends`, `requests` or
  `blocked` changes, and at least every 10 minutes. With it, a friend going offline shows after 150–210 s (the
  service notices on its once-a-minute sweep) instead of exactly 150 s.

## Actions (all POST, all Bearer, JSON body, 200 → { ok: true, ... } unless noted)
    /v1/settings              { appearOffline: bool }
    /v1/friends/request       { name }        // Origin's own names table, any case; 404 "not on Origin" if nobody of that name signed in;
                                              // an opposite pending request auto-accepts; blocked → same 404
    /v1/friends/respond       { uuid, accept }
    /v1/friends/remove        { uuid }
    /v1/friends/block         { uuid }        // also removes friendship/requests; /v1/friends/unblock { uuid }
    /v1/messages/send         { to, text }    // friends only, 1..256 chars, max 1 per second per sender
    /v1/party/create          {}              → { party }        // you lead; max 8 members
    /v1/party/invite          { uuid }        // friends only, leader or member
    /v1/party/accept          { partyId }     // leaves your old party first
    /v1/party/decline         { partyId }
    /v1/party/leave           {}              // leader leaving promotes the longest member; last one out deletes it
    /v1/party/kick            { uuid }        // leader only
    /v1/party/promote         { uuid }        // leader only
    /v1/party/chat            { text }        // 1..256 chars, 1/s
    /v1/party/ping            { server, dim, x, y, z, label }   // label optional ≤ 32; lives 60 s
    /v1/party/warp            { server, version }               // leader only; members get `warp` for 2 min
Errors: `{ error: "<player-facing sentence>" }` with 400/401/403/404/429. Clients show the sentence as is.

Retention: DMs and party chat kept 30 days, pings 60 s, joins/sessions pruned by the existing cron.

## Request budget (Cloudflare free plan, per day: 100,000 Worker requests, D1 5,000,000 rows read, 100,000 rows written)
Clients poll `/v1/sync` adaptively:
- every 4 s while: a chat/friends window is open, the last sync said `wantState: true`, or a DM/invite arrived
  < 2 min ago;
- every 20 s while in a party otherwise;
- every 30 s otherwise (game and launcher alike); every 60 s once the game window has been unfocused > 5 min.
(Will chose the free plan to stay free; the 2026-10-12 cadence revision above cuts party polling 4-5x.)
Writes: presence is written at most once per 60 s per (account, client) even if synced faster (a change of
server/activity/version still writes after 5 s); "online" = seen in the last 150 s. A moving party position at
most every 5 s (only in LAN worlds, see Fair-play guard).

Measured cost per call (D1's own rows_read / rows_written on a local D1, re-measured 2026-10-12 with the 60 s /
150 s rules; D1 counts each index entry touched, so a write is usually 2+ rows):

| call | rows read | rows written |
|---|---|---|
| sync, friendsEtag current, not in a party | 12 | 0 |
| ...plus the 60 s presence heartbeat | +0 | +2 |
| sync with the full list (no/old etag), 20 friends | ~80 | 0 |
| a visible presence change (online, server switch): every friend's etag bumped | ~5 per friend | 1 per friend |
| sync in a party of 4 | ~46 | 0 |
| ...plus a moved position | +1 | +1 |
| DM / party chat line | 8 / 12 | 9 |
| /v1/stats/online (recount once a minute, else cached) | ~19 + online players / 1 | 2 / 0 |
| cron each minute (offline sweep) | ~4 + 5 per friend of anyone who went offline | same per friend |

**N idle players, ~20 friends each, one client open all day (30 s polls + the launcher's 5-minute count):**
- requests: 2,880 syncs + 288 counts ≈ 3,170 per player → **~31 players** fill the free plan (the tightest limit);
- rows read: 2,880 × 12 + full-list refreshes (every 10 min: 144 × 80; friends' changes: ~80 × 80) ≈ 53,000
  per player → ~94 players (it was ~230,000 per player, ~21 players, before friendsEtag);
- rows written: a heartbeat every 60 s, 1,440 × 2 + own visible changes ≈ 3,000 per player → ~33 players
  (it was ~5,800, ~17 players, with the 30 s heartbeat).
- Unfocused game (60 s polls): ~1,440 + 288 requests, the same ~3,000 rows written.

**A party of 4 on an ordinary multiplayer server all day, no chat window open (20 s polls, wantState false):**
- requests: 4 × 4,320 = **~17,300** (17% of the free plan);
- rows read: 4 × 4,320 × ~46 ≈ **~800,000** (16%);
- rows written: positions 0 (Will's rule: positions never leave the game on multiplayer servers), heartbeats
  4 × 2,880 ≈ **~11,500** (12%).

**A party of 4 in one LAN world, moving all day (wantState true, so 4 s polls):**
- requests: 4 × 21,600 = **86,400** — almost the whole free plan on its own;
- rows read: 4 × 21,600 × ~46 ≈ **4.0 M** (80%);
- rows written: positions 4 × 17,280 (every 5 s) ≈ 69,000 + heartbeats ≈ 11,500 → **~80,500** (81%).
  This is the extreme case; a few LAN hours a day cost a few hours' share of it.

For scale: ~25 players idle all day plus one 4-player server party all day ≈ 96,000 requests, ~2.1 M rows read,
~87,000 rows written. Players who play a few hours a day cost that fraction, so the daily-active count the free
plan carries is several times the all-day figures.

So the free plan carries a few dozen all-day players (more who play a few hours), not a big community: the $5 Workers Paid plan (10 M requests and
50 M rows written per month, 25 B rows read) removes every ceiling above with no code change.

## Clients
### Launcher (src/OriginLauncher.App)
- Home: the status pill ("Ready", "Sign in to play", ...) moves to the LEFT; a matching pill on the RIGHT shows
  "<n> playing Origin" (green dot), refreshed every 5 minutes (/v1/stats/online; hidden if it fails).
- Friends: a floating corner control opens a Friends drawer in the launcher's look: online/offline lists with
  status lines ("Playing on hypixel.net · 1.21.1", "In the launcher", "Offline"), requests (accept/decline),
  "Add friend" by username, a chat pane per friend, Invite to party, Remove, Block; Party section (members,
  leave, kick/promote, party chat); "Appear offline" toggle. Toast for new messages / requests / invites.
- Join: on a friend playing a server → Launch that friend's Minecraft version (if Origin supports it) straight
  into their server (quick-play multiplayer argument where the version supports it, else --server/--port).
  `warp` from the party leader → a prompt "Join <leader> on <server>" with a Join button.
- Presence: client "launcher", activity "menu" (the game's own sync takes over while it runs).

### Game (Origin mod, 1.21.1 first, then every version)
- A Friends window (gui/OriginWindow) opened from a FRIENDS tab in the mod menu header and a button in the
  Right Shift centre group; same content as the launcher drawer (list, requests, add, chat, party, appear offline).
- Toasts (Origin style) for DMs, friend requests, party invites, warps; click opens the Friends window.
- 2026-10-18 redesign (1.21.1 first; other versions port it): one search-or-add box (typing filters; no match and a valid
  name -> an "Add <name>" row, Enter sends), one list ordered REQUESTS (friend requests + party invites, labelled
  ACCEPT / JOIN / DECLINE), PARTY (one row), ONLINE, OFFLINE, SENT, BLOCKED. Rows carry their job: JOIN (friend on a server) always
  visible, INVITE on hover. The friend pane has JOIN / INVITE TO PARTY and a "..." menu (Remove / Block, each confirms); chats
  are grouped by sender with times and day breaks; the party pane has member tiles (click = message / make leader / remove),
  WARP / INVITE FRIEND / LEAVE. Request and party-invite toasts carry ACCEPT / JOIN. Ctrl+F searches, Up/Down walk the list,
  Esc clears the search then closes. UI only: nothing here changes what is sent or how often.
- Party chat: shown in vanilla chat as "[Party] <name>: <text>" (client-side only, the server never sees it);
  sent from the Friends window or the client-only command `/originpc <text>` (Will, 2026-10-12: a name no
  server uses — `/pc` and `/p` belong to server plugins).
  DMs are sent from the Friends window only.
- B: party members' nametags tinted + a marker (diamond + name + distance) visible through walls, C: a movable
  Party HUD element (name, health, distance, direction arrow), D: party members on the minimap and locator bar,
  F: a "Ping party" key (unbound by default, set in Controls) that pings the looked-at block for the party
  (marker for 60 s), E: leader's "Warp party here" button; members get a toast
  with Join (connects if same Minecraft version, else "Join from the launcher").
- Fair-play guard (Will, 2026-10-12: "ban them overall on all multiplayer as a good baseline"): everything
  that reveals where a party member is or how they're doing — B markers/tinted nametags, C's health/distance/
  direction, D minimap/locator dots, F pings — works ONLY in a world hosted by a party member's own game
  (singleplayer opened to LAN, the host and players joined over a private LAN address: 10.x, 172.16-31.x,
  192.168.x, 127.x, or the integrated server). On every other multiplayer server none of it is sent
  (`partyState` is null) or drawn; the Party HUD shows names + online status only. One central check in the mod
  (`social/FairPlay`). Party chat, warp, friends, DMs and join still work everywhere.

## Service notes (what the Worker actually does; services/cosmetics/src/social.js + party.js)
Everything here is additive to the contract above; client authors rely on these details. Limits live in one
place: `L` in services/cosmetics/src/social-shared.js. Schema: migrations/0002_social.sql.

**Types.** uuids are 32 lowercase hex, no dashes (requests accept dashed/any case). Every time (`at`, `lastSeen`,
`expiresAt`, `stateAt`) is ms since epoch (JSON number). Ids (`message.id`, `ping.id`) are integers; `partyId` is a
hex string. `server` values come back normalised: lower case, no trailing dot, `:25565` dropped
(`Play.Hypixel.net:25565` → `play.hypixel.net`); compare servers in that form. Positions are rounded to 0.1 block,
health to half a heart.

**Status codes.** 200 success · 400 bad input or a rule ("You're already in a party.") · 401 no/expired/unknown token
(re-run /v1/auth once, retry) or a failed/reused proof on /v1/auth · 403 not allowed (not friends, not the
leader) · 404 not found (not on Origin / invite gone / not in your party) · 405 GET on a POST route ·
429 more than one per second · 503 only /v1/auth while the service holds no Mojang keys yet ("try again in a few
minutes"). Every error body is `{ error: "<sentence>" }`; show it as is. 500 = server bug.

**Auth.** The token is 64 hex characters; send exactly `Authorization: Bearer <token>` (social calls and /v1/equip).
The `name` returned is the Mojang-signed `profileName`. Byte-exact v2 formats: "Identity v2: exact formats" below.

**Cursor.** `cursor` is an integer; treat it as opaque and send it back as `since`. `since: 0` (first sync after start)
returns recent history: the newest 100 DMs and the newest 50 party lines since you joined the party, oldest first.
After that each sync returns everything newer than `since`, oldest first, at most 200 DMs and 200 party lines; if a
page is cut short the cursor stops at the last line returned and the next sync continues from there. DMs and party
chat share one id sequence, so one cursor covers both; nothing is skipped or repeated, and `at` never goes
backwards in id order. A new party member never sees party chat from before they joined. `friends`, `requests`,
`party`, `pings`, `partyInvites` and `warp` are full current state on every sync, not deltas.

**Sync extras.** `blocked: [ { uuid, name } ]` (players you blocked, for an Unblock list) and `warp.name` (the leader's
name) are added. `friendsEtag` is a string like `"12.2933568"`: compare it only for equality, never parse it. When
it matches, the `friends`, `requests` and `blocked` keys are absent (not empty): keep your copy. `wantState` is
always a boolean; the caller's server for it is `partyState.server`, else `stateServer`, else `presence.server`. `presence` and `partyState` may be null/omitted (pure poll, no write). A malformed `partyState` or
presence field is ignored, never an error. The caller's own server (for party positions and pings) is
`partyState.server`, else `stateServer`, else `presence.server` when `activity == "server"`; the launcher therefore
never gets positions.
Party member `state` is also dropped once it is older than 90 s. Pings are filtered the same way as positions: only
pings on the caller's current server are returned.

**Writes.** Presence is rewritten every 60 s per (account, client), or after 5 s when activity/server/version
changed; "online" = seen in the last 150 s. A party position is rewritten after 5 s when it changed, at once when the server changed, else every 30 s.

**Actions.** Responses: `/v1/friends/request` → `{ ok, uuid, name, friends }` (`friends: true` = an opposite request
was waiting and you are now friends; repeating a pending request is a harmless 200). `/v1/friends/respond` →
`{ ok, friends }`. `/v1/messages/send` → `{ ok, message: { id, from, to, text, at } }`. `/v1/party/create|invite|accept|
kick|promote` → `{ ok, party }` (positions left out: `state` null). `/v1/party/chat` → `{ ok, message: { id, from, name,
text, at } }`. `/v1/party/ping` → `{ ok, ping }`. `/v1/settings` → `{ ok, appearOffline }`.
Rules beyond the list above:
- Text (DMs, party chat, ping labels) is cleaned before the length check: line breaks become spaces; control
  characters, `§` colour codes and invisible direction marks are removed; length counts UTF-16 units (Java/C#
  `String.length`). The text in the response is the cleaned text.
- One per second per account applies to DMs, party chat, pings, party invites and friend requests (each its own
  counter).
- Friend requests: the name is looked up in `names` case-insensitively (no Mojang call); if a renamed player's old
  name was taken by someone else, the account that signed in with it most recently wins. Anyone who never signed in
  to Origin gets `404 "<name as typed> isn't on Origin yet."`. Someone who blocked you gets the same 404; requesting someone you blocked is a
  400 ("Unblock them first"). At most 200 friends and 50 pending outgoing requests. `/v1/friends/remove` also cancels a
  pending request either way (use it for "Cancel request").
- `/v1/party/create` while in a party is a 400. `/v1/party/invite` while not in a party starts one (you lead), so
  "Invite to party" works straight from the friends list. Party invites expire after 10 minutes. Re-inviting refreshes
  the invite. The leader can't kick or promote themself (400).
- The cron (every 10 minutes) also takes anyone who has been seen by neither launcher nor game for an hour out of
  their party (leader passes on as with /v1/party/leave).

**Identity v2: exact formats (services/cosmetics/src/mojang.js; tests: test/mojang.mjs signs like Mojang).**
- Body field types: `client` "game"|"launcher"; `uuid` 32 hex, dashes and case optional (normalised to lowercase,
  no dashes); `name` 1–16 of `[A-Za-z0-9_]`, exactly Mojang's spelling; `at` and `keyExpiresAt` JSON integers (ms
  since epoch, not strings); `sig`, `publicKey`, `keySignature`, `textures.value`, `textures.signature` strings.
  Anything missing or of the wrong type (including a v1 `{ name, serverId, client }` body) → 400 "Couldn't sign in
  to Origin. Please update Origin and try again."
- Base64: every binary field accepts standard (`+/`) or URL-safe (`-_`) alphabet, with or without `=` padding, line
  breaks ignored. Send standard base64 as Mojang gives it.
- `publicKey`: X.509 SubjectPublicKeyInfo DER, as base64 or as Mojang's PEM exactly as received
  (`-----BEGIN RSA PUBLIC KEY-----` ... `-----END RSA PUBLIC KEY-----` with `\n` line breaks; any BEGIN/END label is
  stripped). The decoded bytes are used as-is in the certificate check, so never re-encode the key (no PKCS#1
  conversion): send the bytes Mojang signed.
- `keyExpiresAt`: the certificate's `expiresAt` (ISO-8601, often with microseconds, e.g.
  `2026-10-14T09:12:33.123456Z`) as epoch ms TRUNCATED, exactly Java's `Instant.toEpochMilli()` (C#:
  `DateTimeOffset.Parse(s).ToUnixTimeMilliseconds()`; Java: `Instant.parse(s).toEpochMilli()`). Rounding up breaks
  the check.
- `keySignature`: the certificate's `publicKeySignatureV2` (NOT the older `publicKeySignature`). The service checks
  SHA1withRSA over 16 bytes of uuid (the 32 hex digits as raw bytes = msb then lsb, big-endian) ‖ keyExpiresAt as
  8-byte big-endian signed int ‖ the decoded publicKey bytes, against any `playerCertificateKeys` entry.
- `sig`: SHA256withRSA (RSASSA-PKCS1-v1_5; Java `Signature.getInstance("SHA256withRSA")`, C#
  `RSA.SignData(data, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1)`) with the certificate's private key over
  the UTF-8 bytes of `origin-auth|<uuid>|<client>|<at>`: uuid 32 lowercase hex without dashes (whatever form the body
  uses), client exactly as in the body, at = the same integer as the body's `at` in plain decimal (no exponent,
  no padding). `at` must be within ±5 minutes of the service's clock (401 "...your computer's clock looks wrong...").
  Each `sig` works once (401 "That sign-in check was already used..."): sign afresh for every /v1/auth.
- `textures`: the `textures` property of `GET https://sessionserver.mojang.com/session/minecraft/profile/<uuid>
  ?unsigned=false`, `value` and `signature` copied byte for byte (do not decode/re-encode `value`: the signature
  covers that exact string). The service checks SHA1withRSA over the UTF-8 bytes of `value` against any
  `profilePropertyKeys` entry, then base64-decodes it: `profileId` must equal the uuid (dashes/case ignored),
  `profileName` must equal `name` exactly, `timestamp` (ms) must be at most 24 h old and at most 5 min in the
  future. Mojang rate-limits this endpoint: fetch it once per sign-in (not per retry loop).
- Checks run in this order: body shape (400) → keys present (503) → `at` window → certificate signature → key not
  expired (`keyExpiresAt` > now; 401 "Your Minecraft sign-in ran out...": fetch a new certificate) → `sig` → textures
  → single use. 401 sentences never name the failing check beyond that; for a failing build, compare against
  test/mojang.mjs `authBody()`, which is the reference encoder.
- Mojang's keys live in D1 `meta` row `mojang_keys` = `{ profilePropertyKeys: [base64 SPKI], playerCertificateKeys:
  [base64 SPKI], at: <ms fetched> }`, written by services/cosmetics/scripts/store-mojang-keys.sh (every deploy +
  weekly .github/workflows/mojang-keys.yml), re-read by each Worker machine every 10 minutes.
  `GET /v1/diag/keys` → `{ profile: <usable keys>, certificate: <usable keys>, at }` (counts only; checked by the
  post-deploy smoke test). /v1/diag/mojang is gone: the Worker never calls Mojang.
- `/v1/equip`: `Authorization: Bearer <token>` + `{ cape, head }` (item id or null each). A body containing
  `serverId` → 400 "Please update Origin to change cosmetics."; no/expired token → 401 (sign in again, retry once).
  GET /v1/players/<uuid> and POST /v1/players/equipped are unchanged (no auth).
