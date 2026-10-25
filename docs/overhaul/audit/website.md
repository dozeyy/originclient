# Website AI audit (read-only), 2026-10-25

## KEY FINDING
The AI helper is LIVE on origin/main (pushed 2026-10-21) and on https://originclient.org/assistant (HTTP 200;
nav link "AI helper" and terms section 10 confirmed in the live HTML). Local `main` is 480 commits BEHIND
origin/main (0 ahead). Will's uncommitted/untracked assistant files and the 4 modified files in the working
tree are an OLDER copy of the same feature (pre-feedback, 311f4fc72 shape). HEAD itself has none of it.
So "live" = origin/main; the working tree is stale and must NOT be the source for removal.
Line numbers below are origin/main (git show origin/main:<path>), as last fetched (I did not fetch).

Commits on origin/main (all website-only): 311f4fc72 launch, 564441d4e attachments, 45d60ed03, eb2f64026,
5136b6b71, 247c2bb3f, d15969e58 markdown, 133f465a5 feedback, de6b3eb6a composer clear.

## 1. Removal list
LIVE (origin/main):
- website/assistant.html (118 lines) - delete whole page. Title "Origin AI - Minecraft help".
- website/css/assistant.css (186 lines) - delete. Only assistant.html links it.
- website/js/assistant.js (617 lines) - delete. Endpoints at lines 15-19:
  https://origin-assistant-api.will-f11.workers.dev/v1/{chat,feedback,feedback/stats,usage,owner/unlock}.
  Also Mojang manifest fetch (piston-meta.mojang.com/mc/game/version_manifest_v2.json), sessionStorage owner token.
- website/index.html:48  nav link `<a href="assistant" class="nav-assistant">AI helper</a>` - delete.
- website/index.html:217-218  "Minecraft helper" bento card. It REPLACED the old "Public code / Read every line."
  card (GitHub link https://github.com/dozeyy/originclient). Restore from 311f4fc72^:website/index.html or drop it.
- website/store.html:43  nav link `AI helper` - delete.
- website/css/site.css:306 (comment) and :309 selector
  `.top__nav a:not(.nav-store):not(.nav-assistant) { display: none; }`. SHARED RISK: before launch this was
  `:not(.nav-store):not(.nav-home)`. The launch swapped nav-home out, so on phones (<=900px) the store.html "Home"
  link is currently hidden. Revert to `:not(.nav-home)` (and comment "Phones keep only the Store link...").
- website/terms.html:33 "Last updated: October 21, 2026" (was July 6, 2026) and :123 to end of section
  "10. Origin AI helper" (Anthropic data flow, daily feedback counts, hashed-IP usage counters). Remove section, fix date.
- OUTSIDE THE REPO: Cloudflare Worker `origin-assistant-api` (will-f11.workers.dev). Holds the Anthropic API key,
  system prompt, usage/feedback storage (type unknown), owner-unlock code. Source is NOT in git (no file on any
  branch). Removing site files leaves the endpoint live and billable: delete the Worker + storage in the Cloudflare
  dashboard and revoke the Anthropic key. GET /v1/usage returned 403 without an allowed Origin (so it is up).

UNCOMMITTED ONLY (working tree): website/assistant.html (113 lines), css/assistant.css (173), js/assistant.js (530,
no feedback endpoints), and diffs to index.html, store.html, terms.html, site.css (same edits, older text).
Discard/delete; nothing in them is missing from origin/main. HEAD (local main) has no AI content.

Not found: no AI in website/launcher/promos.json, website/ad/banner.html, store.css/js, cosmetics.js, site.js, nav.js,
world.js, rig.js; none in services/cosmetics (store Worker has no Anthropic code); no workflow deploys the assistant
Worker (cosmetics-service.yml deploys only origin-cosmetics); launcher/installer do not link /assistant.
Other repo hits for "anthropic" are tooling only (.claude/, tools/lean/report.py).

Deploy: deploy-website.yml publishes all of website/ to GitHub Pages on push to main touching website/**.
Per MEMORY 2026-10-18 the live originclient.org is actually served by Vercel (vercel.json at root and in website/,
cleanUrls true, only `main` deploys). Deleting from main removes it from both.

## 2. Shared code risks
- site.css:306/309 selector (above) is the one real coupling. assistant.css is standalone but uses site.css classes
  (.brand, .eyebrow, .skip, .sr) - deleting assistant.* does not affect site.css.
- index.html bento grid: restoring the Public code card keeps the grid count; dropping it leaves one card fewer, so
  recheck layout at 375 and desktop.
- terms.html: section 10 is last, no renumbering. assistant.html links to `terms`; irrelevant after deletion.
- Nothing imports assistant.js/css (grep clean). /assistant will 404 after removal (cleanUrls).
- Make the change in a worktree off origin/main (MEMORY 2026-10-18), not in this stale checkout.

## 3. Store
- Page: website/store.html, live at https://originclient.org/store (200). docs/STORE.md heading
  "The Origin Store (originclient.org/store)"; docs/store-setup-phone.html links https://originclient.org/store.
- Backend: Cloudflare Worker https://origin-cosmetics.will-f11.workers.dev (services/cosmetics; website/cosmetics/service.json
  "api"; launcher SocialEndpoints.cs:10 DefaultBaseUrl). Stripe Checkout; STORE.live switch in js/store.js.
- Launcher links to the site only via PromoFeedLoader.cs:29 LiveUrl https://originclient.org/launcher/promos.json,
  PromoFeedLoader.cs:46-48 default promo clickUrl https://originclient.org/, promos.json:10-12, ad page
  https://originclient.org/ad/banner.html (network disabled), RenderShots.cs:222 (screenshots). I found NO launcher
  code that opens originclient.org/store directly (grep of origin/main src/).

## 4. Health
- No build step (static). deploy-website.yml only validates launcher/promos.json schema == 1, then uploads website/.
  No link checker or lint for the website in CI.
- tools/website/build_assets.py has no --check flag and WRITES files (fonts, textures, three.js), so I did not run it.
- Ran an ad hoc read-only resolver over origin/main website HTML href/src and CSS url(): 0 missing refs
  (clean-URL hrefs like store/assistant/terms resolve). JS-built texture paths (world.js) not checked.
- Live /assistant in the browser pane: 0 console messages, site.css/assistant.css/assistant.js/fonts all 200.
  Chat/usage calls not exercised (Worker rejects without allowed Origin). Console risk: Mojang manifest failure ->
  console.warn at assistant.js:344 (CORS on manifest is Access-Control-Allow-Origin *, fine).
- Issue: store.html phone nav hides Home (see site.css above) - existing bug from the AI launch.
- Content honesty: assistant shows plan tiers ($5-$100) with disabled "Coming soon" buttons and "paid plans coming later"
  copy for plans that don't exist; goes away with removal.
- Homepage not console-checked this run (only /assistant); store/home Playwright at 375 + desktop still to do for
  rule 5 before any website push.
