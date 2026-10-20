# Launcher asset sources

Every image the launcher ships that was not drawn in this repo, with where it came from.
Third-party marks and art stay their owners' property; they are shown only to name or
picture the thing they belong to (a launcher's logo on its import tile, a version's own
key art behind that version).

## Version art (`Art/`)

Official Minecraft wallpaper packs from minecraft.net. Every pack is under
`https://www.minecraft.net/content/dam/minecraftnet/games/minecraft/software/`.

Each family ships six files, all made from the same original:
- `<family>-g1280.jpg` and `-g2560.jpg`: the greyscale, darkened Home layer.
- `-c1280.jpg` and `-c2560.jpg`: the colour layer the launch bloom fades to.
- `-tg.jpg` and `-tc.jpg`: the picker thumbnails, grey and colour.

| Family | File prefix | Wallpaper pack | Notes |
|---|---|---|---|
| 26.x | `26-` | `wallpapers_tiny_takeover_drop.zip` | |
| 1.21 | `1.21-` | `wallpapers_tricky_trials_update2.zip` | |
| 1.20 | `1.20-` | `Minecraft_Trails_and_Tales_.Net.zip` | The largest source is 2058x1440. The logo is cropped out (104,400 to 1954,1440), so the 2560 file is an upscale. |
| 1.19 | `1.19-` | `wallpaper_minecraft_wild_update.zip` | The zip is nested inside another zip. |
| 1.18 | `1.18-` | `wallpapers_minecraft_caves_cliffs(part2).zip` | |
| 1.17 | `1.17-` | `wallpapers_minecraft_caves_cliffs(part1).zip` | |
| 1.16 | `1.16-` | `wallpapers_minecraft_nether_update.zip` | |
| 1.8.9 | `1.8-` | `wallpapers_minecraft_java_edition.zip` | Generic Java Edition art. The series has no official 1.8 key art. |

The family accents in `UI/FamilyTheme.cs` were sampled from these same images.

## Launcher logos (`Logos/`, the Import drawer)

All are 128x128 PNGs, rasterised from these sources.

| File | Launcher | Source |
|---|---|---|
| `curseforge.png` | CurseForge | https://cdn.jsdelivr.net/npm/simple-icons@13.21.0/icons/curseforge.svg, filled with the simple-icons brand hex #F16436 |
| `modrinth.png` | Modrinth App | https://cdn.jsdelivr.net/npm/simple-icons@13.21.0/icons/modrinth.svg, filled with #00AF5C (simple-icons cites github.com/modrinth/art) |
| `prism.png` | Prism Launcher | https://raw.githubusercontent.com/PrismLauncher/PrismLauncher/develop/program_info/org.prismlauncher.PrismLauncher.svg |
| `multimc.png` | MultiMC | https://raw.githubusercontent.com/MultiMC/Launcher/develop/branding/logo.svg |
| `lunar.png` | Lunar Client | https://www.lunarclient.com/assets/img/logo.webp (their white emblem) |
| `feather.png` | Feather Client | The icon embedded as a base64 PNG in https://feathermc.com/logo.svg |
| `minecraft.png` | Minecraft Launcher | https://www.minecraft.net/etc.clientlibs/minecraftnet/clientlibs/clientlib-site/resources/apple-icon-180x180.png. This is minecraft.net's site icon (the creeper face). The launcher's own grass-block icon had no public direct link. |

Prism Launcher and MultiMC share one import tile ("Prism / MultiMC"), which shows Prism's logo.
Badlion, LabyMod and picked folders have no logo file, so their tiles show an initial; no logo was invented.

## Origin brand (`Brand/`)

`wordmark.png` is the mod's own wordmark,
`src/mods/shared/src/client/resources/assets/originclient/textures/ui/wordmark.png`.
It is cropped to its ink box from `wordmark.json`, plus a 40 px margin.
The three-ring mark is drawn in XAML (`UI/Controls/OriginMark.xaml`) using the geometry of
`website/assets/favicon.svg`.

## Promo images (`Promos/`)

Rendered by `tools/launcher-previews` from Origin's own UI, for the Home banner's tips slot (`Core/Promos/PromoTips.cs`).

## Profile emblem icons (code, no files)

The line icons in the profile emblem picker are `Theme/Icons.xaml` geometries (Feather-style strokes, like the rest of the launcher).
The Minecraft-style pixel icons (sword, pickaxe, diamond, heart, creeper, potion, torch, chest) are Origin's own 16x16 pixel art, drawn as masks in `UI/ProfileLook.cs`.
No Minecraft texture is copied or bundled.
