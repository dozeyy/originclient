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
