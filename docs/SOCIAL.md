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

## Identity (no Microsoft/Mojang token ever reaches Origin)
Same proof as cosmetics `/v1/equip`: the client picks a random hex `serverId` (32 chars), tells Mojang
"I'm joining serverId" with the player's own session (game: `MinecraftSessionService.joinServer`; launcher:
`POST https://sessionserver.mojang.com/session/minecraft/join {accessToken, selectedProfile, serverId}`), then:

    POST /v1/auth            { name, serverId, client: "game" | "launcher" }
    200 { token, uuid, name, expiresAt }      // uuid undashed lowercase; token valid 24 h
    401 { error }                             // Mojang didn't confirm / serverId reused

Every social call sends `Authorization: Bearer <token>`. A 401 on any call → the client re-runs /v1/auth once,
then retries. Tokens are stored hashed (SHA-256) server side. Offline/cracked accounts can't pass, so they get
no social features (the launcher's OfflineTestMode included) — the UI says "Sign in with Microsoft to use
friends" instead of erroring.

## Players online (no auth)
    GET /v1/stats/online     200 { online: <int>, at: <ms> }
Distinct accounts with a sync from the launcher or the game in the last 5 minutes (invisible ones counted:
it's an anonymous number). Cached ~60 s server side. The launcher calls it on start and every 5 minutes.

## The one polling call
    POST /v1/sync
    { since: <cursor or 0>,
      presence: { client: "game"|"launcher",
                  activity: "menu" | "singleplayer" | "server",
                  server: "host[:port]" | null,          // only when activity == "server"
                  version: "1.21.1" },                   // Minecraft version being played / selected
      partyState: null | { server, dim: "minecraft:overworld", x, y, z, health, maxHealth } }  // game only, only in a party
    200 {
      cursor,                                            // pass back as `since`
      me: { uuid, name, appearOffline },
      friends: [ { uuid, name, online, client, activity, server, version, lastSeen } ],   // full list, online first
      requests: { incoming: [ { uuid, name, at } ], outgoing: [ { uuid, name, at } ] },
      messages: [ { id, from, to, text, at } ],          // DMs to/from me newer than `since`
      party: null | { id, leader, members: [ { uuid, name, online, state, stateAt } ] },   // state as partyState
      partyMessages: [ { id, from, name, text, at } ],   // newer than `since`
      pings: [ { id, from, name, server, dim, x, y, z, label, at, expiresAt } ],          // still active
      partyInvites: [ { partyId, from, name, at } ],
      warp: null | { server, version, at, from }         // leader asked the party to join them (last 2 min)
    }
- Presence is per (account, client): launcher and game both open = one online account. Online = synced in
  the last 90 s. A friend who set appearOffline shows `online:false`, no activity, to friends (party members
  still get its party state, since they're in a party together).
- `server` is only shown to friends (never to strangers), and `activity/server` are null while appearOffline.
- Party member `state` is returned only to members of that party, and only for members whose `partyState.server`
  equals the caller's own current server (positions are meaningless across servers).

## Actions (all POST, all Bearer, JSON body, 200 → { ok: true, ... } unless noted)
    /v1/settings              { appearOffline: bool }
    /v1/friends/request       { name }        // Mojang name→uuid; 404 "not on Origin" if that account never used it;
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

## Request budget (Cloudflare free: 100,000 Worker requests/day, D1 100,000 row writes/day)
Clients poll `/v1/sync` adaptively:
- every 4 s while: a chat/friends window is open, the player is in a party, or a DM/invite arrived < 2 min ago;
- every 30 s otherwise (game and launcher alike); never while the game window has been unfocused > 5 min
  (then 60 s).
At ~30 s idle that is ~2,900 requests per online player per day; ~35 players online all day fill the free plan.
When the count grows, the $5 Workers Paid plan (10 M requests/month) removes the ceiling with no code change.
Writes: presence is written at most once per 30 s per (account, client) even if synced faster.

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
- Party chat: shown in vanilla chat as "[Party] <name>: <text>" (client-side only, the server never sees it);
  sent from the Friends window or the client-only command `/pc <text>` (never `/p`: servers own it).
  DMs are sent from the Friends window only.
- B: party members' nametags tinted + a marker (diamond + name + distance) visible through walls, C: a movable
  Party HUD element (name, health, distance, direction arrow), D: party members on the minimap and locator bar,
  F: a "Ping party" key (unbound by default, set in Controls) that pings the looked-at block for the party
  (marker for 60 s), E: leader's "Warp party here" button; members get a toast
  with Join (connects if same Minecraft version, else "Join from the launcher").
- Fair-play guard: B, C, D and F positions are OFF on competitive networks where showing other players'
  positions is against the rules (start with hypixel.net and its subdomains; list in one place in the mod),
  and positions are only exchanged between members on the same server. Party chat, warp and the rest still
  work there.

## Service notes (what the Worker actually does; services/cosmetics/src/social.js + party.js)
Everything here is additive to the contract above; client authors rely on these details. Limits live in one
place: `L` in services/cosmetics/src/social-shared.js. Schema: migrations/0002_social.sql.

**Types.** uuids are 32 lowercase hex, no dashes (requests accept dashed/any case). Every time (`at`, `lastSeen`,
`expiresAt`, `stateAt`) is ms since epoch (JSON number). Ids (`message.id`, `ping.id`) are integers; `partyId` is a
hex string. `server` values come back normalised: lower case, no trailing dot, `:25565` dropped
(`Play.Hypixel.net:25565` → `play.hypixel.net`); compare servers in that form. Positions are rounded to 0.1 block,
health to half a heart.

**Status codes.** 200 success · 400 bad input or a rule ("You're already in a party.") · 401 no/expired/unknown token
(re-run /v1/auth once, retry) or a failed/reused Mojang check on /v1/auth · 403 not allowed (not friends, not the
leader) · 404 not found (no such player / not on Origin / invite gone / not in your party) · 405 GET on a POST route ·
429 more than one per second · 503 only /v1/friends/request when Mojang's name lookup is down and Origin doesn't know
the name. Every error body is `{ error: "<sentence>" }`; show it as is. 500 = server bug.

**Auth.** `serverId` must be 16–64 lowercase hex (32 recommended) and is single-use, shared with /v1/equip. The token is
64 hex characters; send exactly `Authorization: Bearer <token>`. The `name` returned is Mojang's spelling.

**Cursor.** `cursor` is an integer; treat it as opaque and send it back as `since`. `since: 0` (first sync after start)
returns recent history: the newest 100 DMs and the newest 50 party lines since you joined the party, oldest first.
After that each sync returns everything newer than `since`, oldest first, at most 200 DMs and 200 party lines; if a
page is cut short the cursor stops at the last line returned and the next sync continues from there. DMs and party
chat share one id sequence, so one cursor covers both; nothing is skipped or repeated, and `at` never goes
backwards in id order. A new party member never sees party chat from before they joined. `friends`, `requests`,
`party`, `pings`, `partyInvites` and `warp` are full current state on every sync, not deltas.

**Sync extras.** `blocked: [ { uuid, name } ]` (players you blocked, for an Unblock list) and `warp.name` (the leader's
name) are added. `presence` and `partyState` may be null/omitted (pure poll, no write). A malformed `partyState` or
presence field is ignored, never an error. The caller's own server (for party positions and pings) is
`partyState.server`, else `presence.server` when `activity == "server"`; the launcher therefore never gets positions.
Party member `state` is also dropped once it is older than 90 s. Pings are filtered the same way as positions: only
pings on the caller's current server are returned.

**Writes.** Presence is rewritten every 30 s per (account, client), or after 5 s when activity/server/version
changed. A party position is rewritten after 3 s when it changed, at once when the server changed, else every 30 s.

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
- Friend requests: someone who blocked you gets the same 404 as "not on Origin"; requesting someone you blocked is a
  400 ("Unblock them first"). At most 200 friends and 50 pending outgoing requests. `/v1/friends/remove` also cancels a
  pending request either way (use it for "Cancel request").
- `/v1/party/create` while in a party is a 400. `/v1/party/invite` while not in a party starts one (you lead), so
  "Invite to party" works straight from the friends list. Party invites expire after 10 minutes. Re-inviting refreshes
  the invite. The leader can't kick or promote themself (400).
- The cron (every 10 minutes) also takes anyone who has been seen by neither launcher nor game for an hour out of
  their party (leader passes on as with /v1/party/leave).
