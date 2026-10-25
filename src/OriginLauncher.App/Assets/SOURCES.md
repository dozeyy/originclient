# Launcher asset sources

Every image the launcher ships that was not drawn in this repo, with where it came from.
Third-party marks and art stay their owners' property; they are shown only to name or
picture the thing they belong to (a launcher's logo on its import tile, a version's own
key art behind that version).

## Version art (`Art/`)

Official Minecraft wallpaper packs from minecraft.net. Every pack is under
`https://www.minecraft.net/content/dam/minecraftnet/games/minecraft/software/`.

Each family ships these files, all rebuilt from the same lossless PNG by
`tools/launcher-art/build_art.py --upscale <realesrgan-ncnn-vulkan.exe> --q3840 88` (it downloads the
packs below, checks each pack's SHA-256, and records the recipe):
- `<family>-g<W>.jpg`: the greyscale Home layer (Rec.601 luma x 0.40 + 3.5/255).
- `-c<W>.jpg`: the colour layer the launch bloom fades to (source x 0.55).
- `-tg.jpg` and `-tc.jpg`: the picker thumbnails, grey and colour (480x270).
- `<W>` is 1280, 2560 or 3840. JPEG 4:4:4; quality 92, except the 3840 tier at 88 to keep the folder near 25 MB.
  The colour and grey formulas are applied after every resize and after the upscale.

Mojang's wallpaper packs top out at 2560x1440 (checked 2026-10-20 across every pack and the
minecraft.net update pages, whose widest key art is a 2880x1080 banner crop). No original is
3840 wide, so which tiers are real pixels and which are AI-upscaled:

| Tier | Source | |
|---|---|---|
| 1280 and thumbnails | Lanczos downscale of the original | native |
| 2560, every family except 1.20 | the original 2560x1440 PNG | native |
| 3840, every family | the original run through Real-ESRGAN, Lanczos to exactly 3840x2160 | **AI-upscaled** (x2) |
| 2560, 1.20 only | the 1842x1036 logo-free crop, upscaled x3, Lanczos to 2560x1440 | **AI-upscaled** |
| 3840, 1.20 | same upscale, Lanczos to 3840x2160 | **AI-upscaled** (x3) |

Upscaler: Real-ESRGAN ncnn-vulkan `realesrgan-ncnn-vulkan-20220424-windows.zip`
(https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0, 45474481 bytes, SHA-256
`abc02804e17982a3be33675e4d471e91ea374e65b70167abc09e31acb412802d`), model `realesr-animevideov3`
at the smallest of x2/x3/x4 that reaches 3840 px. It was chosen over `realesrgan-x4plus`, which
smeared the stone textures on 1.8; the animevideov3 output kept block edges and texture detail
with no halos. Real-ESRGAN is BSD-3-Clause; only the images it produced are shipped.

Pack SHA-256 (as downloaded 2026-10-20; `build_art.py` refuses a pack that no longer matches):

| Family | File prefix | Wallpaper pack | Notes |
|---|---|---|---|
| 26.x | `26-` | `wallpapers_tiny_takeover_drop.zip` | |
| 1.21 | `1.21-` | `wallpapers_tricky_trials_update2.zip` | |
| 1.20 | `1.20-` | `Minecraft_Trails_and_Tales_.Net.zip` | Every size has the logo; the largest is 2058x1440. The logo is cropped out (108,404 to 1950,1440), leaving 1842x1036 of native art, so this is the one family whose 2560 tier is AI-upscaled. |
| 1.19 | `1.19-` | `wallpaper_minecraft_wild_update.zip` | The zip is nested inside another zip. |
| 1.18 | `1.18-` | `wallpapers_minecraft_caves_cliffs(part2).zip` | |
| 1.17 | `1.17-` | `wallpapers_minecraft_caves_cliffs(part1).zip` | |
| 1.16 | `1.16-` | `wallpapers_minecraft_nether_update.zip` | |
| 1.8.9 | `1.8-` | `wallpapers_minecraft_java_edition.zip` | Generic Java Edition art. The series has no official 1.8 key art. |

```
c3c3c28065ef868e70cfeec16343b81449757518f70e8238079945b545087526  wallpapers_tiny_takeover_drop.zip
45b9ebcad3ee4784f6a198116ccf20c76bfe49afa1030bf393fad8c285695003  wallpapers_tricky_trials_update2.zip
aaf762a8c5ca31e3d9c862f1a86cb2d4923a28b68471ce39c702ddb555220857  Minecraft_Trails_and_Tales_.Net.zip
853713c99b2f14a2f761928f27b0da07c216f799cba183da48910a3523f15242  wallpaper_minecraft_wild_update.zip
4eb81a2a9a2659626ce900307f99cfb92a1c6413ed7ef03c7ef6003b949dbe67  wallpapers_minecraft_caves_cliffs(part2).zip
0d0d87d354238994730c27553c06b20db289c2aca29f7d65cbc632092c4067f3  wallpapers_minecraft_caves_cliffs(part1).zip
d963067c264ed2eb332cf6f943d1a9568fbbd97a26ca5de868cb6decd5198775  wallpapers_minecraft_nether_update.zip
084c1cbefe0a224f13cdfcfdcf039508fef5e417e370944bc7ed61bc5765c929  wallpapers_minecraft_java_edition.zip
```

The family accents in `UI/FamilyTheme.cs` were sampled from these same images.

## Launcher logos (`Logos/`, the Import page)

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
The three-ring mark is the official logo image, `origin_logo.png` (the mod's `textures/ui/origin_logo.png`, 256 x 256, the same art as
`Assets/origin.ico`), shown through `UI/Controls/BrandLogo.cs` (`Brand.Logo` in `Theme/Brand.xaml`). It is never redrawn in XAML, SVG, CSS or code;
`tools/design/check_logo.py` fails the build if a ring drawing or a copied logo appears.

## Promo images (`Promos/`)

Rendered by `tools/launcher-previews` from Origin's own UI, for the Home banner's tips slot (`Core/Promos/PromoTips.cs`).

## Profile emblem icons (code, no files)

The line icons in the profile emblem picker are `Theme/Icons.xaml` geometries (Feather-style strokes, like the rest of the launcher).
The Minecraft-style pixel icons (sword, pickaxe, diamond, heart, creeper, potion, torch, chest) are Origin's own 16x16 pixel art, drawn as masks in `UI/ProfileLook.cs`.
No Minecraft texture is copied or bundled.
