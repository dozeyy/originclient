# Origin Launcher UI audit (read-only)

SCOPE CAVEAT: audited `origin/main` (tip de6b3eb6a, fetched 2026-10-21), extracted with `git archive` into
`...\scratchpad\audit\src\`. The local checkout is 480 commits behind and still shows the OLD left-rail UI
(MainWindow.xaml with a 56 px vertical rail, 340 px AccountPanel). All paths below are relative to
`src/OriginLauncher.App/` of origin/main unless stated. Nothing in the repo was modified. Not run, not built,
nothing reproduced live. Items marked "likely" are inferred.
Screenshots read from `origin/claude/dev-shots` (27f14043e) folder `launcher/` via git show: 01-home, 14-settings,
20-min-home, 10-versions-grid, 13-mods.

---------------------------------------------------------------------------
## 1. Screen inventory

Shell: `MainWindow.xaml` (243 lines) / `.xaml.cs` (926). Chromeless (WindowChrome CaptionHeight 0,
AllowsTransparency=False), 1180x740, min 960x600.
Layers (bottom to top): `ArtStage x:Name="Stage"` (:36), `PageHost` (:46-50), `ImportPageHost` (:54-55),
`TopBar` (:58-186), Scrim (:190), AccountPanel (:194), FriendsDrawer (:206), SignInPanel (:220),
SocialToastHost (:237), StartupIntro (:241).

| Surface | File | What it does / look |
|---|---|---|
| ArtStage | UI/Controls/ArtStage.cs (480) | Full-window poster backdrop: 2 greyscale art layers crossfading, colour "launch bloom", VersionNumeral, 140 px top scrim, 330 px bottom scrim, page dim (all pages but Home). |
| Home | UI/Pages/HomePage.xaml (171) + .cs (1537) | Bottom strip: VersionButton pill, ProfileRow pill, StatusRow, MyModsSwitch, JoinButton, 40 pt PLAY (Button.PlayHero). BannerSlot top right (380 wide, empty while HomeBannerOn false). |
| Mods | UI/Pages/ModsPage.xaml (182) | MODS poster title, MY MODS switch, drag-drop DropZone (168 px, dashed), user mod rows, collapsible BUILT IN 3-col grid. |
| Profiles | UI/Pages/ProfilesPage.xaml (197) | 3-col card grid ("No profile", profiles, dashed "New profile"); modal create/edit FormPanel (name, 21 emblems, colour, version, server). |
| Settings | UI/Pages/SettingsPage.xaml (342) + .cs (462) | One long scroll page, 200 px rail (RadioButtons = scroll-spy) + cards: Performance, Game, JVM, Origin Client, Startup, Developer. |
| Version picker | UI/Controls/VersionSelectOverlay.xaml (193) + .cs (298) | Window-below-bar overlay: 8 family tiles (4x2, grey thumb -> colour on hover/selected), chips for versions, "Play {version}", floating X. |
| Import | UI/Controls/ImportPanel (1233 xaml / 3084 cs) | Full page in the Profiles slot: detect/scan, pick, groups, progress, cancel-confirm, done, failed, empty. |
| Import prompt | ImportPromptCard | First-run "WELCOME TO ORIGIN": Import everything / Choose / Skip. |
| Account drawer | AccountSwitcherPanel | Right drawer 380 (`Size.Drawer`): accounts, ACTIVE/PENDING badges, Add Microsoft account. |
| Sign-in card | MicrosoftSignInPanel | 460x640 card, embedded WebView2, Shadow.Card, scale-in. |
| Friends drawer | FriendsPanel (839 xaml / 1112 cs) | Chat, party, context menus (Menus.xaml). |
| Toasts | SocialToastHost, ToastQueue.cs | Join-action toasts, "more waiting" chip. |
| Confirm | ConfirmOverlay | Modal confirm; only used by FriendsPanel and ImportPanel. |
| Launch cover | LaunchLoadingOverlay | Full window incl. bar: eyebrow, 112 pt version / % count, 4 px bar, Cancel. |
| Intro | StartupIntro | 3 rotating rings with jade glow, skippable. |
| Promo banner | PromoBanner, BannerAdHost.cs | Home banner (currently off); BlurEffect radius 28 on preview image (cs ~846). |
| Crash windows | CrashWindow (660x560), CrashReportWindow (720x380), CrashFallback.cs | Separate OS windows. CrashFallback builds the same UI in code with literal colours + Segoe UI. |
| Version dropdown / tooltips | Theme/Inputs.xaml (Popup :300, ComboBoxItem :334), Theme/ToolTips.xaml | |
| Installer | src/OriginSetup (separate .NET Fx 4.7.2 app) | Out of scope here; check_consistency.py guards launcher/installer copies. |

---------------------------------------------------------------------------
## 2. Token source and bypasses

Merged in App.xaml: Colors, Typography, Spacing, Buttons, Icons, Inputs, Brand, Menus, Social, Surfaces, ToolTips.

- Colors.xaml (207): Bg #111111, BgAlt #161616, Panel #1A1A1A, PanelElevated #262626, PanelGlass #F5181818, Card #222222,
  CardAlt #2C2C2C, Stroke #444444, SoftStroke #2A2A2A, Track #555555; Text #DCDCDC, TextDim #C4C4C4, TextMuted #ADADAD, OnPhoto #E6E6E6;
  Accent (tonal) #E0E0E0 / Hover #EDEDED / Press #C9C9C9 / TextOnAccent #141414; Acc (family hue, default #E8914F) + AccSoft/AccLine;
  Info #8FB8E8, Danger #E8786B, Success #7BCB98, Warning #E2C06A; NumeralInk #36ECECEC; Scrim #A80A0A0A; PageDim #D60E0E0E;
  Tint.* / TintInk.* (Mint, Sky, Violet, Gold, Coral) for Settings rail; legacy WindowBackdrop, PrimaryGradient.
- Typography.xaml: Inter static TTFs 400-800 (Assets/Fonts, family "Inter", Segoe UI fallback); Font.Display == Font.Primary.
  Sizes: Display 40, H1 26, H2 16, Body 13.5, Caption 12, Eyebrow 11, Poster 52, PlayWord 40, PillNumber 20, LaunchCount 112,
  LaunchPercent 42, DialogTitle 30, CardTitle 18. Styles Text.Poster/Display/H1/H2/Body/BodyDim/Label/Eyebrow/PillTitle/Status/CardTitle/Meta/FieldLabel/Caption.
- Spacing.xaml: Space 4/8/16/24/32/48; Size.Bar 56, Row 64, ControlSm 32, Control 40, ControlLg 48, IconButton 32, Drawer 380, Emblem 28.
  Radii: Sharp/Chip/NavItem/Row/Card/Swatch 4, Control 8, Play 10, Dialog 8, Focus 11, FocusCard 7, Bar 2, Knob 2, Switch 3, SwitchGlow 6.
  Motion: Base/Hover 0.14, Press 0.08, Release 0.14, Scene 0.26, Exit 0.12, Drawer 0.32, Drag 0.18, Intro 0.56, Settle 0.32; KeySplines Ease.Out/In/Expo.
  Shadows (static DropShadow): Raised (blur 10), Primary (18), Card (28, depth 8).
- Surfaces.xaml: Page.Column, Surface.Card/Row/Dialog/Chip, Drawer.*.
- Runtime hue: UI/FamilyTheme.cs publishes `Brush.Acc` via Motion.PublishColor (700 ms tween); consume with DynamicResource only.
  Per-family hexes are literals in code (26 #F2C94C, 1.21 #E8914F, 1.20 #F29BC0, 1.19 #3FD0D4, 1.18 #B48CF2, 1.17 #5ED6A0, 1.16 #F2545B, 1.8 #7BC043).

Bypasses (status: Partial - tokens exist and are mostly used; these leak):
- Brand.xaml: `Effect.OriginGlow` hardcoded jade #4BC8AE (blur 20) + OriginGlowSoft. Contradicts the CLAUDE.md "no hue even in the glow" brand note.
  OriginMark.xaml:18 carries it, so every OriginMark instance (top-bar brand, Home) has a DropShadow. StartupIntro.xaml:24 too.
- Hex outside Colors.xaml: Buttons.xaml:413 (`Stroke="#FFE6E6E6"` PlayHero arrow) + animations :427/:436; Inputs.xaml:158 (`BorderBrush="#33000000"`);
  PromoBanner.xaml:90-93 (4 stops #1D2420 family); VersionSelectOverlay.xaml:38 (`Background="#E6111111"`); ModsPage.xaml:79-80 (shadow gradient);
  Spacing.xaml shadow colours (3).
- Literal FontSize: ImportPanel (6), VersionSelectOverlay (3: 15, 11.5, 10), SettingsPage (1), HomePage (1), PromoBanner (1), ImportPromptCard (1).
- In code: ImportViewModels.cs:562-568 (launcher brand colours), HomePage.xaml.cs:1458 (Play arrow stroke 0E0E0E/E6E6E6),
  ModIcons.cs:47-51 (hex per icon kind), FamilyTheme table, CrashFallback.cs (all literals, intentional fail-safe).
- Magic numbers in ArtStage: scrim heights 140/330, art tiers 1280/2560/3840, 0.42, 0.9, 0.6, Narrow 0.7.
- Typography.xaml:5-10 comment says the horizontal squish was removed; ArtStage.cs still applies it (see 3).

---------------------------------------------------------------------------
## 3. Home version numeral: why it looks compressed (Completed+verified from code and screenshot)

ROOT CAUSE: a hard-coded 70 % horizontal squash.
- `UI/Controls/ArtStage.cs:348` `private const double Narrow = 0.7;`
- `:405-410` `Measure100` multiplies each glyph advance by Narrow (so layout width is also 70 %).
- `:448` `Width = _ft.WidthIncludingTrailingWhitespace * Narrow` (each digit is its own `Glyph : FrameworkElement`).
- `:461-467` `Glyph.OnRender`: `dc.PushTransform(new ScaleTransform(Narrow, 1)); dc.DrawText(...)`.
- `:453-459` Shape() uses Font.Display, ExtraBold, FontStretches.Normal, Brush.NumeralInk.
- The result is Inter ExtraBold at its true height but 70 % width: tall and narrow. Screenshot launcher_01-home.png confirms.
- It also contradicts Theme/Typography.xaml:5-10 (comment says the squish was deliberately removed 2026-10-19).

Positioning: NOT laid out between the top nav and the version button. Fixed fractions of the whole window.
- `:397-403` FontSize = min(w*0.9/at100*100, h*0.6): fills 90 % of window width, capped at 60 % of height.
- `:422` `var top = ActualHeight * 0.42 - (sample.Baseline - caps * fs / 2);` digit middle at 42 % of window height.
- `:418` x centred in the full-window Canvas (Children.Add at :87). Independent of TopBar (56 px) and the bottom strip.
- Layout re-creates FormattedText per glyph on every SizeChanged (likely cheap, unmeasured).
- Fix direction (not done): drop Narrow (or token it), size from the free band between bar bottom and strip top, e.g. a Grid row, not 0.42 * height.

---------------------------------------------------------------------------
## 4. Top nav (Partial - design options are inferred)

Build: `MainWindow.xaml:58-186` `TopBar` Grid, Height Size.Bar (56), VerticalAlignment Top, Margin 24,0,12,0, NO background.
- Brand (OriginMark + wordmark.png) :60-65. `NavTabs` StackPanel centred :69-94: 4 ToggleButtons Button.NavItem with TrackedText
  (HOME/MODS/PROFILES/SETTINGS, Tracking 0.14), shifted by `NavTabsShift` in `TopBar_LayoutChanged`.
- `BarActions` :96-185: UpdateBadge, WebsiteButton (:115-126), ImportButton (:128-136), FriendsButton + badge (:139-151),
  AccountButton chip (:153-169), Minimize, Close.
- The bar is a sibling of PageHost, not part of any page, so it is already FIXED while content scrolls. Pages scroll in their own
  ScrollViewer inside PageColumn (top inset 96, or 80 if the slot < 680). SetTopBarHidden hides it during launch.
- It is transparent; text sits straight over the art, protected only by the 140 px top scrim (ArtStage.cs:89-93).

Options for a fixed translucent surface (likely, nothing measured):
a) Semi-opaque solid/gradient Border behind the bar (e.g. PanelGlass #F5181818 ~96 % alpha, or a token). Cost ~0. Matches existing scrims. Recommended.
b) DWM backdrop (Mica/Acrylic via DwmSetWindowAttribute DWMWA_SYSTEMBACKDROP_TYPE or SetWindowCompositionAttribute). Whole window only, Mica Win11 only,
   needs a chrome change (AllowsTransparency=False + GlassFrameThickness=0 today). Cannot blur a region of the app's own art.
c) BlurEffect over a VisualBrush of the content: software re-rasterise every time the art drifts (15 fps) or a page scrolls. Expensive on the UI thread; avoid.
d) Pre-baked blurred strip of the art (decode a blurred top slice once per frame change) under the bar. Cheap at runtime, needs new asset pipeline.

---------------------------------------------------------------------------
## 5. Version selector (Completed+verified for structure; install state Not implemented)

- Catalog: `Core/Versions/VersionCatalog.cs` (99). `VersionGroup`: Key, Title, Tagline, Description, ImagePath `/Assets/Art/{key}-tc.jpg`, Versions (newest first).
  `Build()` :54-81, 8 groups: 26 (26.3, 26.2, 26.1.2, 26.1.1, 26.1), 1.21 (1.21.11 ... 1.21; 10 entries), 1.20 (1.20.4, .2, .1, .0), 1.19 (1.19.4, .3, .2),
  1.18 (1.18.2), 1.17 (1.17.1), 1.16 (1.16.5), 1.8 (1.8.9). `DefaultVersion => SupportedVersions[0]` (26.3).
- Must equal `Core/Versions/VersionManager.cs:59` `OriginBuilds`; `:278` filters Mojang manifest releases to OriginBuilds keys.
  CI gate: tools/release/check_consistency.py.
- Home: `VersionButton` (HomePage.xaml) opens `VersionSelectOverlay`. `InitVersionSelection` (HomePage.xaml.cs ~565) falls back to DefaultVersion when the saved value is unsupported.
- Persistence: `LauncherSettings.SelectedVersion` (`Core/Models/LauncherSettings.cs:20`), written ONLY via `SettingsStore.Update` in `OnVersionSelected` (~589),
  also `ProfilesPage.xaml.cs:511,733` and `Core/Install/SetupChoices.cs:39`. `OnVersionSelected` also kicks `PrewarmSelectedVersion` (LaunchPrewarm).
- NO installed-vs-downloadable state anywhere: catalog is static, no disk or network check; install happens at launch. Screenshot 10-versions-grid.png shows
  only "current" highlighting.

---------------------------------------------------------------------------
## 6. Settings sidebar bug (Completed+verified by STATIC TRACE, not reproduced live)

Symptom: first click on "Origin Client" selects "Developer"; second click selects Origin Client.

`UI/Pages/SettingsPage.xaml.cs:432-461`:
```
Rail_Checked: if (_railSyncing || sender is not RadioButton item || !IsLoaded) return;
              if (SectionOf(item) is { } section)
                  Scroller.ScrollToVerticalOffset(Math.Max(0, SectionTop(section) - 24));
Scroller_ScrollChanged: RailShift.Y = Scroller.VerticalOffset;
   var atBottom = Scroller.ScrollableHeight > 0 && Scroller.VerticalOffset >= Scroller.ScrollableHeight - 1;
   foreach rail item: if (atBottom || SectionTop(section) <= Scroller.VerticalOffset + 60) current = item;   // last wins
   if (current is null || current.IsChecked == true) return;
   _railSyncing = true; current.IsChecked = true; _railSyncing = false;
```
Rail = 6 RadioButtons, GroupName "SettingsRail" (SettingsPage.xaml:83-106; order Performance, Game, JVM, Client, Startup, Developer).
Chain: the Client, Startup and Developer sections are shorter than the viewport, so SectionTop(Client) - 24 is past ScrollableHeight.
ScrollToVerticalOffset clamps to the bottom, ScrollChanged fires with atBottom = true, and the `atBottom ||` clause makes EVERY item "current";
the loop ends on the last one (Developer). Developer.IsChecked = true unchecks Client through the GroupName.
Second click: Client is checked again, the same clamped offset is requested, the offset does not change, no ScrollChanged fires, so Client stays. That matches "second click works".
Likely also hits Startup (and JVM in a short window). Fix direction (not done): when a rail click is in flight, ignore the spy until the scroll settles
(or set `current` = clicked item), and do not let atBottom override an explicit click.

---------------------------------------------------------------------------
## 7. Developer mode (Broken: ungated, with a "remove before public release" comment)

- SettingsPage.xaml:315-331: `<!-- Developer (temporary: remove before public release) -->`, `SecDeveloper`, "Offline test mode" `OfflineTestToggle`
  (handlers SettingsPage.xaml.cs:249-260, load :57), always visible; LauncherVersionText caption follows (:336-337). Rail entry "Developer" always present.
- `LauncherSettings.OfflineTestMode` (`Core/Models/LauncherSettings.cs:38`). Consumers: `Core/GameFolders/AccountFolders.cs:26` (offline key),
  `MainWindow.xaml.cs:585-586` (account chip shows "Offline"), `UI/Pages/HomePage.xaml.cs:471-478, 690-716, 806-811` (launch with
  `MSession.CreateOfflineSession("OriginTester")`; status "Ready to play offline" at :504), `LaunchTimingHarness.cs:52` (CI sets it).
- Also exposed: CLI `--render-shots`, `--import-plan`, `--launch-timing`, `--startup-trace`, `--import`; env `ORIGIN_STARTUP_TRACE=1`, `ORIGIN_COSMETICS_API`.
- Existing gates: `#if DEBUG` only for single-instance mutex isolation (App.xaml.cs:105-113). `UpdateService.IsDevBuild` (`Core/Updates/UpdateService.cs:56`,
  CurrentVersion == DevVersion) is a runtime dev-build check already used for the registry write and update gate.
- Must stay for dev builds: OfflineTestMode (Auth login_with_xbox is still 403, so it is the test path), the CI harness paths (render-shots, launch-timing set it
  programmatically, so gate only the UI entry, not the setting/consumers), startup trace.
- Gate options: `UpdateService.IsDevBuild` || env `ORIGIN_DEV=1` || `#if DEBUG` for the Settings section + rail item. Hide, do not delete the setting.

---------------------------------------------------------------------------
## 8. Website button (Completed+verified)

- Button: `MainWindow.xaml:115-126` (`WebsiteButton`, Icon.Emblem.Globe + "Website", in BarActions).
- Handler: `MainWindow.xaml.cs:601-602` `Core.Promos.ExternalLink.Open(new Uri("https://originclient.org/"));` hardcoded. ExternalLink only allows http/https.
- Other URLs: `website/launcher/promos.json` promo `"clickUrl": "https://originclient.org/"`, Discord `inviteCode: "REPLACE_ME"`, ad network disabled
  (`https://originclient.org/ad/banner.html`); `PromoFeedLoader.cs:29` LiveUrl `https://originclient.org/launcher/promos.json`.
- Store: `https://originclient.org/store` (`services/cosmetics/src/stripe.js:31` STORE_PAGE; `docs/STORE.md:1`). `website/store.html` title "Origin Store",
  `website/js/store.js:18` `live: true`.
- The launcher has NO direct store link; the Website button goes to the site root. Open question for Will.

---------------------------------------------------------------------------
## 9. Performance notes (Partial: reads are from code, nothing measured)

Cold start (`App.xaml.cs`, `MainWindow.xaml.cs`):
- OnStartup is synchronous: EnsureScaffold, AccountFolders.EnsureMigrated, SetupChoices.ApplyIfPresent, then `ImportStartup.StartRecovery` (pool thread),
  `DeleteLegacySignInProfile` (Task.Run), `new MainWindow().Show()`. Home is built in the MainWindow field initialiser; Settings/Mods/Profiles are lazy
  and warmed at ApplicationIdle (`WarmPagesAtIdle` ~:128), then `AfterFirstRenderAsync` (:813) waits 1500 ms, warms pages, 1500 ms more, then the import prompt.
- StartupTrace marks via `--startup-trace` or ORIGIN_STARTUP_TRACE=1. csproj: PublishReadyToRun when a RuntimeIdentifier is given, TieredPGO off.
UI-thread / GPU cost (likely):
- ArtStage: full-window animated layers; 44 s drift at 15 fps with scale 1.05-1.10; launch push-in to 1.08 over 12 s; BitmapScalingMode.HighQuality (:68);
  art decoded off-thread, up to 3840 wide, 6-frame LRU (could be a few hundred MB at 3840, per its own comment). Numeral FormattedText rebuilt each SizeChanged.
- Effects: static DropShadow on Primary buttons (Buttons.xaml:238), OriginMark glow (every instance, incl. the always-visible top-bar brand), SignInPanel,
  Surface.Dialog, toasts, Home card. BlurEffect radius 28 in PromoBanner. No BitmapCache anywhere (grep found none). Drop shadows on a moving/animating
  parent re-rasterise in software in WPF; the full-window drift at 15 fps keeps the compositor busy even when idle (likely).
- Motion: Motion.cs + Duration tokens; App.DisableMotion zeroes them. Brush.Acc tween = 700 ms.

---------------------------------------------------------------------------
## Open questions for Will
1. Gate the Developer section by `UpdateService.IsDevBuild`, env var, or `#if DEBUG`? (CI harnesses set OfflineTestMode in code, so they are unaffected.)
2. Should the Website button go to /store (or add a separate Store entry)? The launcher has no store link today.
3. Keep or remove the squashed numeral? (Typography.xaml says the squish was removed; ArtStage still does it.)
4. Jade glow vs the "no hue" brand note: which wins?
