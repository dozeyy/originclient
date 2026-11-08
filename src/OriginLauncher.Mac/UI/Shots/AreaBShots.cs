using System.Text;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Input;
using Avalonia.VisualTree;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Social;

namespace OriginLauncher.Mac.UI.Shots;

/// <summary>
/// Render mode only (called from RenderShots): the Mac twins of the Windows Mods, Profiles, account and sign-in shots,
/// under the SAME file names (WPF UI/RenderShots.cs 06b/06c, 09-13, 23/25 min window, ModsProfilesShots 28/29-min,
/// AccountShots 16*). Every state comes from the page's own Tour* hook with the Windows run's made-up data: no store,
/// settings or folder of the player's is read, no sign-in runs and nothing goes online (the sign-in card skips its web
/// view in a scratch-root run). Mac-only extra: 06d, the sign-in-with-a-code card with a made-up code.
/// </summary>
internal static class AreaBShots
{
    // The Windows run's rows (WPF RenderShots.DemoMods / DemoModsDropped), so the twins show the same list.
    private static readonly ModEntry[] DemoMods =
    {
        new("XaerosWorldMap_1.39.2_Fabric_1.21.jar", "Xaero's World Map", true, false, Version: "1.39.2"),
        new("litematica-fabric-1.21.1-0.19.50.jar", "Litematica", true, false, Version: "0.19.50"),
        new("appleskin-fabric-mc1.21-3.0.5.jar", "AppleSkin", false, false, Version: "3.0.5"),
        new("origin-client-1.21.1.jar", "Origin Client", true, true),
        new("sodium-fabric-0.6.13+mc1.21.1.jar", "Sodium", true, true),
        new("iris-fabric-1.8.8+mc1.21.1.jar", "Iris Shaders", true, true),
    };

    private static readonly ModEntry[] DemoModsDropped =
        new[] { new ModEntry("zoomify-2.14.2+1.21.1.jar", "Zoomify", true, false, Version: "2.14.2+1.21.1") }.Concat(DemoMods).ToArray();

    // The widest letters a name can have, 16 of them (a Minecraft name is at most 16), and a 23-letter one (AccountShots).
    private const string Widest = "WWWWWWWWWWWWWWWW";
    private const string TwentyThree = "AVeryLongGamertagNameXX";

    private static LauncherSettings DemoProfiles() => new()
    {
        ModProfiles =
        {
            new ModProfile { Id = "0a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "Survival SMP", Version = "1.21.1", Icon = "Pickaxe", ServerName = "Survival SMP", ServerAddress = "play.example.net" },
            new ModProfile { Id = "1a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "PvP practice", Version = "1.8.9", Icon = "Sword", Tint = "Wine", ServerName = "Practice", ServerAddress = "pvp.example.org:25570" },
            new ModProfile { Id = "2a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "Singleplayer shaders", Version = "1.20.1", Tint = "Plum", Icon = "Camera" },
            new ModProfile { Id = "3a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "Skyblock with a much longer profile name", Version = "1.20.1", Tint = "Moss", Icon = "Map" },
        },
        SelectedProfileId = "0a1b2c3d4e5f60718293a4b5c6d7e8f9",
        SelectedVersion = "1.21.1",
    };

    private static int DemoCount(ModProfile p) => p.Name.StartsWith("PvP", StringComparison.Ordinal) ? 1 : p.HasServer ? 6 : 3;

    internal static void Run(MainWindow window, string outDir)
    {
        try
        {
            RunShots(window, outDir);
        }
        catch (Exception e)
        {
            RenderShots.Fail($"area-b ({e.GetType().Name}: {e.Message})");
            File.WriteAllText(Path.Combine(outDir, "area-b-error.txt"), e.ToString());
        }
        finally
        {
            window.TourCloseSignIn();
            window.TourSetAccountOpen(false);
            window.TourShowAccount(null, "", false);
            window.TourGoTo(MainWindow.LauncherPage.Home);
            RenderShots.Pump();
        }
    }

    private static void RunShots(MainWindow window, string outDir)
    {
        OriginLauncher.App.Core.Mods.Icons.ModIconSource.DemoIcons = BuildDemoIcons();
        var profiles = DemoProfiles();
        var focusLog = new StringBuilder();
        // The Windows tour's own window (its CI desktop caps it at 1044 wide); its sign-in shots come before it picks
        // 1.21.1, so they show the default version.
        RenderShots.Resize(window, 1044, 740);
        window.TourGoTo(MainWindow.LauncherPage.Home);
        window.SelectVersion(OriginLauncher.App.Core.Versions.VersionCatalog.DefaultVersion);

        // ---- 06b/06c/06d: the Microsoft sign-in card (no web view in a shots run) ----
        window.TourShowSignIn(null, null);
        Shot(window, "06b-sign-in");
        window.TourCloseSignIn();
        window.TourShowSignIn("Couldn't add this account", "Couldn't save this account. Please try again.");
        Shot(window, "06c-sign-in-message");
        window.TourCloseSignIn();
        // Mac only: sign in with a code (a made-up code, never one Microsoft issued).
        window.TourShowSignIn(null, null, demoCode: "ABCD-EFGH");
        Shot(window, "06d-sign-in-code");
        window.TourCloseSignIn();
        window.SelectVersion("1.21.1");

        // ---- 09/10: Profiles and its new-profile form ----
        window.TourShowProfiles(profiles, DemoCount, openForm: false);
        // The Windows run's pointer rests on the selected card, so its edit / duplicate / delete show: the same here.
        HoverSelectedCard(window, profiles);
        Shot(window, "09-profiles");
        window.MouseMove(new Point(4, 400));
        SnapScrolledPage(window, "09b-profiles-scrolled");
        window.TourShowProfiles(profiles, DemoCount, openForm: true);
        Shot(window, "10-profiles-form");
        // ---- 11: Home with that profile selected: its server's Join card between My mods and Play ----
        window.TourShowHomeWithProfile(profiles.ModProfiles[0]);
        Shot(window, "11-home-profile");
        window.TourShowHomeWithProfile(null);

        // ---- 12: the Accounts drawer as the tour opens it (signed out: no rows) ----
        window.TourGoTo(MainWindow.LauncherPage.Home);
        window.TourSetAccountOpen(true);
        Shot(window, "12-accounts");
        window.TourSetAccountOpen(false);
        RenderShots.Pump();

        // ---- 13: Mods ----
        window.TourGoTo(MainWindow.LauncherPage.Mods);
        Shot(window, "13-mods");
        window.TourShowModsDemo(DemoMods, 0);
        Shot(window, "13b-mods-list");
        SnapScrolledPage(window, "13e-mods-scrolled");
        window.TourShowModsDemo(DemoMods, 1);
        Shot(window, "13c-mods-dragover");
        window.TourShowModsDemo(DemoModsDropped, -1);
        Shot(window, "13d-mods-dropped");

        States(window, "28", profiles, focusLog);

        // ---- 23/25/29-min: the smallest window the Windows launcher allows (980x600) ----
        RenderShots.Resize(window, 980, 600);
        window.TourGoTo(MainWindow.LauncherPage.Home);
        window.TourGoTo(MainWindow.LauncherPage.Mods);
        window.TourShowModsDemo(Array.Empty<ModEntry>(), 0);
        Shot(window, "23-min-mods");
        window.TourShowModsDemo(DemoMods, 0);
        Shot(window, "23b-min-mods-list");
        SnapScrolledPage(window, "23c-min-mods-scrolled");
        window.TourShowProfiles(profiles, DemoCount, openForm: false);
        Shot(window, "25-min-profiles");
        SnapScrolledPage(window, "25b-min-profiles-scrolled");
        States(window, "29-min", profiles, focusLog);
        // ---- 26/27: Home with the profile at the smallest window, then with the toasts up: the Play card, Play and Join
        // must fit under the bar and above the bottom edge, and the toasts must leave them uncovered ----
        window.TourShowHomeWithProfile(profiles.ModProfiles[0]);
        Shot(window, "26-min-home-profile");
        foreach (var toast in SocialDemo.Toasts()) window.TourShowToast(toast);
        window.TourShowToast(new SocialToast(SocialToastKind.FriendRequest, "Friend request", "OjoTheCat wants to be friends", Uuid: SocialDemo.Ojo));
        Shot(window, "27-min-home-profile-toasts");
        window.TourClearToasts();
        window.TourShowHomeWithProfile(null);
        File.AppendAllText(Path.Combine(outDir, "focus-checks.txt"), focusLog.ToString());

        Accounts(window, outDir);
        DpiMatrix(window, profiles);
    }

    // A page switch or a state change starts the theme's short transitions (the tab dot, hover colours) and a real
    // icon decodes off the UI thread: let real time pass so each picture shows the settled state (motion is off for
    // Origin's own animations; the theme's transitions run on the clock).
    private static void Shot(MainWindow window, string name)
    {
        RenderShots.PumpFor(350);
        RenderShots.Snap(window, name);
    }

    // In-memory icons for some of the made-up rows, as on Windows (WPF RenderShots.BuildDemoIcons): never the network;
    // the rest keep the tinted generated icon, so one shot shows both. 16, 32 and 96 px exercise both scaling modes.
    private static IReadOnlyDictionary<string, byte[]> BuildDemoIcons() => new Dictionary<string, byte[]>
    {
        ["XaerosWorldMap_1.39.2_Fabric_1.21.jar"] = DemoPng(32, (0x2E, 0x7D, 0x4F), (0x9B, 0xE0, 0x6A)),
        ["appleskin-fabric-mc1.21-3.0.5.jar"] = DemoPng(16, (0x9E, 0x2B, 0x2B), (0xF2, 0x7A, 0x7A)),
        ["sodium-fabric-0.6.13+mc1.21.1.jar"] = DemoPng(96, (0x1F, 0x5F, 0x8F), (0x8F, 0xD0, 0xF5)),
    };

    // A diagonal-banded square with a bright inner block (the same picture the Windows run makes).
    private static byte[] DemoPng(int size, (byte R, byte G, byte B) dark, (byte R, byte G, byte B) light)
    {
        var px = new byte[size * size * 4];
        for (var y = 0; y < size; y++)
            for (var x = 0; x < size; x++)
            {
                var inner = x >= size / 4 && x < size * 3 / 4 && y >= size / 4 && y < size * 3 / 4;
                (byte R, byte G, byte B) half = ((byte)(dark.R / 2), (byte)(dark.G / 2), (byte)(dark.B / 2));
                var c = inner ? light : ((x + y) / Math.Max(2, size / 8) % 2 == 0 ? dark : half);
                var i = (y * size + x) * 4;
                px[i] = c.B; px[i + 1] = c.G; px[i + 2] = c.R; px[i + 3] = 255;
            }
        using var bmp = new Avalonia.Media.Imaging.WriteableBitmap(new PixelSize(size, size), new Vector(96, 96),
            Avalonia.Platform.PixelFormat.Bgra8888, Avalonia.Platform.AlphaFormat.Opaque);
        using (var fb = bmp.Lock())
            for (var row = 0; row < size; row++)
                System.Runtime.InteropServices.Marshal.Copy(px, row * size * 4, fb.Address + row * fb.RowBytes, size * 4);
        using var ms = new MemoryStream();
        bmp.Save(ms);
        return ms.ToArray();
    }

    // ModsProfilesShots twin: every Mods and Profiles state the main tour does not reach.
    private static void States(MainWindow window, string prefix, LauncherSettings profiles, StringBuilder log)
    {
        var mods = window.ModsView;
        var page = window.ProfilesView;

        window.TourShowModsDemo(Array.Empty<ModEntry>(), 0);
        Shot(window, $"{prefix}a-mods-empty");

        window.TourShowModsDemo(DemoMods, 0);
        mods.TourShowStatus("Adding 2 mods...", error: false);
        Shot(window, $"{prefix}b-mods-status-note");

        mods.TourShowStatus("Added 1 mod. " + "Shaders are off for this profile: no tested Iris for your Sodium 0.6.9. Add a matching Iris to turn them back on.", error: false);
        Shot(window, $"{prefix}b2-mods-status-override-notice");

        mods.TourShowStatus("Couldn't remove it. Close Minecraft first, then try again.", error: true);
        Shot(window, $"{prefix}c-mods-status-error");

        window.TourShowModsDemo(DemoMods, 0);
        mods.TourConfirmRemove("litematica-fabric-1.21.1-0.19.50.jar");
        Shot(window, $"{prefix}d-mods-remove-confirm");

        window.TourShowModsDemo(DemoMods, 0);
        mods.TourShowUpdate("litematica-fabric-1.21.1-0.19.50.jar", "0.21.0");
        Shot(window, $"{prefix}d2-mods-update");

        mods.TourNoVersion();
        Shot(window, $"{prefix}e-mods-no-version");

        window.TourShowProfiles(new LauncherSettings(), DemoCount, openForm: false);
        Shot(window, $"{prefix}f-profiles-empty");

        window.TourShowProfiles(profiles, DemoCount, openForm: false);
        page.TourShowStatus("Created \"Survival SMP\". Add its mods on the Mods page.", error: false);
        Shot(window, $"{prefix}g-profiles-status-note");

        page.TourShowStatus("\"Survival SMP\" is being used by a game that's running. Close Minecraft first.", error: true);
        Shot(window, $"{prefix}h-profiles-status-error");

        window.TourShowProfiles(profiles, DemoCount, openForm: false);
        page.TourAskDelete();
        Shot(window, $"{prefix}i-profiles-delete-confirm");

        window.TourShowProfiles(profiles, DemoCount, openForm: false);
        page.TourEditForm();
        Shot(window, $"{prefix}j-profiles-form-edit");

        // The form's error: the real rule's own sentence for a name that is taken (never a made-up one).
        window.TourShowProfiles(profiles, DemoCount, openForm: false);
        var taken = profiles.ModProfiles[0];
        var error = ProfileRules.ValidateName(taken.Name, taken.Version, profiles.ModProfiles) ?? "That name is taken.";
        page.TourFormError(taken.Name, error);
        Shot(window, $"{prefix}k-profiles-form-error");

        // Keyboard focus: the control's own focus frame (a Tab-style focus draws it, like a player's Tab).
        window.Activate();
        window.TourShowModsDemo(DemoMods, 0);
        RenderShots.Pump();
        FocusShot(window, $"{prefix}l-mods-focus-open-folder", mods.TourOpenFolderButton, "mods: Open folder", log);
        FocusShot(window, $"{prefix}m-mods-focus-remove", mods.TourFirstRowButton, "mods: first row's button", log);

        window.TourShowProfiles(profiles, DemoCount, openForm: false);
        RenderShots.Pump();
        var cards = page.RowsPanel.GetVisualDescendants().OfType<Button>()
            .Where(b => b.IsEffectivelyVisible && b.IsEffectivelyEnabled && b.Focusable).ToList();
        FocusShot(window, $"{prefix}n-profiles-focus-card",
            cards.FirstOrDefault(b => (AutomationProperties.GetName(b) ?? "").StartsWith("Use", StringComparison.Ordinal)),
            "profiles: a card's Use button", log);
        FocusShot(window, $"{prefix}o-profiles-focus-new", cards.LastOrDefault(), "profiles: New profile card", log);
        window.FocusManager?.Focus(null);
        window.TourShowProfiles(profiles, DemoCount, openForm: false);
    }

    private static void HoverSelectedCard(MainWindow window, LauncherSettings profiles)
    {
        RenderShots.Pump();
        var name = profiles.ModProfiles.First(p => p.Id == profiles.SelectedProfileId).Name;
        var card = window.ProfilesView.RowsPanel.GetVisualDescendants().OfType<Control>()
            .FirstOrDefault(c => (AutomationProperties.GetName(c) ?? "").StartsWith(name + ",", StringComparison.Ordinal));
        if (card == null) { RenderShots.Fail("09-profiles (selected card not found)"); return; }
        var at = card.TranslatePoint(new Point(card.Bounds.Width / 2, 12), window) ?? default;
        window.MouseMove(at);
    }

    // A keyboard-focused control's frame, snapped. The theme's frame (its FocusAdorner) is already in the adorner layer,
    // but the layer places it from the compositor's transformed bounds, which a headless run never fills in, so it sits
    // clipped at the layer's corner. For the picture a stand-in Border with the theme frame's own brush, thickness, radius
    // and offset is drawn over the control, then removed; the Windows run does the same for WPF (ImportShots.FocusFrame).
    private static string FocusSnap(MainWindow window, string name, Control target)
    {
        RenderShots.PumpFor(350);
        var layer = Avalonia.Controls.Primitives.AdornerLayer.GetAdornerLayer(target);
        var frame = layer?.Children.FirstOrDefault(c => ReferenceEquals(Avalonia.Controls.Primitives.AdornerLayer.GetAdornedElement(c), target));
        var look = frame as Border ?? frame?.GetVisualDescendants().OfType<Border>().FirstOrDefault();
        if (layer == null || frame == null || look == null || target.TranslatePoint(default, layer) is not { } at)
        {
            RenderShots.Snap(window, name);
            return "none";
        }
        var m = look.Margin;
        var standIn = new Border
        {
            BorderBrush = look.BorderBrush,
            BorderThickness = look.BorderThickness,
            CornerRadius = look.CornerRadius,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Top,
            Width = target.Bounds.Width - m.Left - m.Right,
            Height = target.Bounds.Height - m.Top - m.Bottom,
            Margin = new Thickness(at.X + m.Left, at.Y + m.Top, 0, 0),
            IsHitTestVisible = false,
        };
        frame.IsVisible = false;
        layer.Children.Add(standIn);
        RenderShots.Snap(window, name);
        layer.Children.Remove(standIn);
        frame.IsVisible = true;
        return $"margin={m} thickness={look.BorderThickness} at={at}";
    }

    // Keyboard focus the way a player gets it (Tab), so the theme's keyboard-only :focus-visible frame shows; a mouse
    // click or a programmatic focus draws none, as on a Mac.
    private static bool TabTo(MainWindow window, Control target)
    {
        window.Focus();
        RenderShots.Pump();
        return target.Focus(NavigationMethod.Tab);
    }

    private static void FocusShot(MainWindow window, string name, Control? target, string what, StringBuilder log)
    {
        if (target == null)
        {
            log.AppendLine($"{name}: {what}: NOT FOUND");
            RenderShots.Fail($"{name} (no {what})");
            return;
        }
        var focused = TabTo(window, target);
        var frame = FocusSnap(window, name, target);
        log.AppendLine($"{name}: {what}: focused={focused} focusVisible={target.Classes.Contains(":focus-visible")} frame={frame} {AutomationProperties.GetName(target)}");
    }

    // AccountShots twin: the pill in every state, hover and focus, the drawer with made-up rows, the narrowest window.
    private static void Accounts(MainWindow window, string outDir)
    {
        var log = new StringBuilder();
        RenderShots.Resize(window, 1180, 740);
        window.TourGoTo(MainWindow.LauncherPage.Home);

        void Pose(string name, string? player, string uuid, bool offline)
        {
            window.TourShowAccount(player, uuid, offline);
            RenderShots.Pump();
            log.AppendLine($"{name}: {window.TourAccountFit()}");
            Shot(window, name);
        }

        Pose("16a-pill-microsoft-short", "Steve", SocialDemo.Steve, false);
        Pose("16a2-pill-microsoft-alex", "Alex", SocialDemo.Alex, false);
        Pose("16b-pill-microsoft-long", Widest, SocialDemo.Steve, false);
        Pose("16c-pill-microsoft-23-letters", TwentyThree, SocialDemo.Alex, false);
        Pose("16d-pill-offline", null, "", true);
        Pose("16e-pill-signed-out", null, "", false);

        window.TourShowAccount("Steve", SocialDemo.Steve, false);
        foreach (var (button, name) in new[] { (window.TourAccountButton, "16f-pill-hover"), (window.TourStoreButton, "16g-store-hover") })
        {
            RenderShots.Pump();
            var centre = button.TranslatePoint(new Point(button.Bounds.Width / 2, button.Bounds.Height / 2), window) ?? default;
            window.MouseMove(centre);
            Shot(window, name);
            window.MouseMove(new Point(4, 400));
        }

        window.Activate();
        foreach (var (button, name) in new[] { (window.TourStoreButton, "16h-store-focus"), (window.TourAccountButton, "16i-pill-focus") })
        {
            TabTo(window, button);
            var frame = FocusSnap(window, name, button);
            log.AppendLine($"{name}: frame={frame} {window.TourAccountFit()}");
        }
        window.FocusManager?.Focus(null);

        // The drawer, with made-up rows: the selected one, a second player, a half-added account and a name that gets cut.
        window.TourShowAccount("Steve", SocialDemo.Steve, false);
        window.TourSetAccountOpen(true);
        window.TourShowAccountRows(
        [
            new MinecraftAccount { Id = SocialDemo.Steve, Gamertag = "Steve", LastUsedUtc = DateTimeOffset.UtcNow, IsSelected = true },
            new MinecraftAccount { Id = SocialDemo.Alex, Gamertag = "Alex", LastUsedUtc = DateTimeOffset.UtcNow.AddDays(-3) },
            new MinecraftAccount { Id = SocialDemo.Notch, Gamertag = "Notch", LastUsedUtc = DateTimeOffset.UtcNow.AddDays(-9), IsPending = true },
            new MinecraftAccount { Id = SocialDemo.Kai, Gamertag = Widest, LastUsedUtc = DateTimeOffset.UtcNow.AddDays(-30) },
        ]);
        Shot(window, "16j-drawer-accounts");
        // The trash button's question (Cancel holds the focus; nothing is removed in a render).
        window.TourAskRemoveAccount("Alex", true);
        RenderShots.Pump();
        Shot(window, "16j2-drawer-remove-confirm");
        window.TourAskRemoveAccount("Alex", false);
        window.TourSetAccountOpen(false);
        RenderShots.Pump();

        // The narrowest window: the bar gives up the Store word and the wordmark (the long name keeps its room).
        RenderShots.Resize(window, 960, 600);
        Pose("16k-bar-960-long-name", Widest, SocialDemo.Steve, false);
        RenderShots.Resize(window, 1180, 740);

        // 150 % scale: the head stays a whole number of screen pixels per face pixel.
        window.TourShowAccount("Steve", SocialDemo.Steve, false);
        AtScale(window, 1.5, () =>
        {
            log.AppendLine($"16m-pill-150pct: scaling={window.RenderScaling} {window.TourAccountFit()}");
            Shot(window, "16m-pill-150pct");
        });

        File.WriteAllText(Path.Combine(outDir, "account-fit.txt"), log.ToString());
    }

    // The headless window drawn at a display scale (1.5 = 150 %): layout rounds to that scale's device pixels and the
    // frame is rasterised at it, like a Retina or scaled display. Always set back to 100 %.
    private static void AtScale(MainWindow window, double scale, Action shots)
    {
        window.SetRenderScaling(scale);
        RenderShots.Pump();
        try { shots(); }
        finally
        {
            window.SetRenderScaling(1);
            RenderShots.Pump();
        }
    }

    // Twin of the Windows DpiMatrix (RenderShots.cs), the scenes in this area: Mods, Profiles and the sign-in card at
    // the launcher's minimum, default and a 1080p window, each at 100 / 150 / 200 % scale. Home, friends, the version
    // picker, Settings and the launch failure are the other area's scenes.
    private static void DpiMatrix(MainWindow window, LauncherSettings profiles)
    {
        var scenes = new (string Name, Action Setup, Action Cleanup)[]
        {
            ("mods-list", () => { window.TourGoTo(MainWindow.LauncherPage.Mods); window.TourShowModsDemo(DemoMods, 0); }, () => { }),
            ("profiles", () => window.TourShowProfiles(profiles, DemoCount, openForm: false), () => { }),
            ("sign-in-loading", () => { window.TourGoTo(MainWindow.LauncherPage.Home); window.TourShowSignIn(null, null); }, window.TourCloseSignIn),
            ("sign-in-message", () =>
            {
                window.TourGoTo(MainWindow.LauncherPage.Home);
                window.TourShowSignIn("Couldn't add this account", "Couldn't save this account. Please try again.");
            }, window.TourCloseSignIn),
        };
        window.TourShowAccount(null, "", false); // signed out, like the Windows matrix
        foreach (var (name, setup, cleanup) in scenes)
        {
            setup();
            foreach (var percent in new[] { 100, 150, 200 })
                AtScale(window, percent / 100.0, () =>
                {
                    foreach (var (w, h) in new[] { (980, 600), (1180, 740), (1920, 1080) })
                    {
                        RenderShots.Resize(window, w, h);
                        Shot(window, $"dpi-{name}-{w}x{h}-{percent}pct");
                    }
                });
            cleanup();
            RenderShots.Pump();
        }
        RenderShots.Resize(window, 1180, 740);
    }

    // The visible page scrolled well past the top bar (its longest scroller, to 60 %), so a shot proves content goes
    // UNDER the bar's surface. No scroller with room = no shot (as on Windows).
    private static void SnapScrolledPage(MainWindow window, string name)
    {
        RenderShots.Pump();
        var best = window.GetVisualDescendants().OfType<ScrollViewer>()
            .Where(s => s.IsEffectivelyVisible && s.Extent.Height - s.Viewport.Height > 1)
            .OrderByDescending(s => s.Extent.Height - s.Viewport.Height)
            .FirstOrDefault();
        if (best == null) return;
        best.Offset = new Vector(0, (best.Extent.Height - best.Viewport.Height) * 0.6);
        Shot(window, name);
        best.Offset = default;
        RenderShots.Pump();
    }
}
