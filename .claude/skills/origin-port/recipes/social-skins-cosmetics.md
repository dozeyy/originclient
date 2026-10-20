# Recipe: account chip, skins, Friends / social, capes, cosmetics, nametags

## Rules that hold everywhere
- **Own skin:** use `mc.getGameProfile()` or `SocialSkins.skin(UUID)`. Never `new GameProfile(uuid, name)`: it has no textures,
  so you get a Steve/Alex head. Fixed in commit 83844df6. The bug had been copied into 1.19.x, so check every donor for it.
- Never allocate a GameProfile per frame. Resolve once and cache. This was the most common review Low.
- Networking (Friends, party pings) goes through the shared social client. 1.8.9 HTTPS must use `net.OriginHttp`
  (bundled trust roots + TLS 1.2), because jre-legacy lacks the modern CAs.

## Per-era facts

| Era | Facts (proven unless marked) |
|---|---|
| G9 1.8.9 | NetworkPlayerInfo / NetHandlerPlayClient `getPlayerInfoMap()`; RenderLivingEvent.Specials for nametags; RenderPlayerEvent. See legacy-forge-189.md |
| G8 1.16.5 | cosmetics layer added from the PlayerRenderer ctor |
| ≤1.18.2 | no PlayerFaceRenderer / `getInsecureSkinLocation`: the chip blits face (8,8) + hat (40,8) from the 64x64 skin; skin resolved via `SkinManager.registerSkins`. Pre-1.19 `registerSkins` refills the own profile |
| 1.19.1+ | PlayerFaceRenderer exists (1.19.2 calls `PlayerFaceRenderer.draw(pose, x, y, size)` after setShaderTexture) |
| <1.20.2 | capes via the AbstractClientPlayer getters |
| 1.20.2+ | PlayerSkin; `Minecraft.getGameProfile()` + `SkinManager.getInsecureSkin(profile)` (compiled, build-check 132) |
| <1.21 | `renderNameTag` has 5 params |
| 1.21.10+ | cosmetics submit custom geometry (extract/submit) |
| 1.21.11 | `submitNameTag`, `AvatarRenderState` (renames) |
| G6 26.1 | name tags: `NameTagFeatureRenderer.renderTranslucent(SubmitNodeCollection, BufferSource, Font)` makes the two `Font.drawInBatch` calls, so 1.21.11's redirect works and Text Shadow works. `collector.submitNameTag` keeps the distance arg. No FriendsButton |
| G6 26.2 | `NameTagFeatureRenderer$Submit` records → glyphs in static `prepareText(Font, Submit)`; NametagStyleMixin swaps the record there; no per-tag shadow flag (Text Shadow follows vanilla). `submitNameTag` → `submitNameDisplay`, lost the distance arg. Mojang FriendsButton kept in the dock. `PlayerTeam.getColor()` → `Optional<TeamColor>` |
| G6 26.3 | NameTagFeatureRenderer gone: tags are `TextFeatureRenderer.Submit(Content.Text)` from SubmitNodeCollection's private static `nameTag(...)`; restyle its return. Text has a dropShadow flag again |

## Proof
- `dev-shots set=design`: account chip and Friends screen.
- `set=world`: nametag and cosmetics in a world.
- Real skin fetches depend on network in CI. A Steve head in a shot means re-check the profile source before calling it flaky.
- Deep notes: PORTING-ERA-NOTES `## Social port (2026-10-12) — <modules>`, `### Findings from the feature port` (capes by era, halos, cosmetics preview).
