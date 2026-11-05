using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Mods.Icons;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.Desktop.Pages;
using OriginLauncher.Desktop.Shell;

namespace OriginLauncher.Desktop.Shots;

// The Mods and Profiles pages in every state, named like the Windows set (UI/RenderShots.cs, UI/ModsProfilesShots.cs,
// UI/CompatShots.cs) so the two sheets line up. Everything shown is made up: the rows, the icons, the profiles.
public static class ModsProfilesShots
{
    private static readonly ModEntry[] DemoMods =
    [
        new("XaerosWorldMap_1.39.2_Fabric_1.21.jar", "Xaero's World Map", true, false),
        new("litematica-fabric-1.21.1-0.19.50.jar", "Litematica", true, false),
        new("appleskin-fabric-mc1.21-3.0.5.jar", "AppleSkin", false, false),
        new("origin-client-1.21.1.jar", "Origin Client", true, true),
        new("sodium-fabric-0.6.13+mc1.21.1.jar", "Sodium", true, true),
        new("iris-fabric-1.8.8+mc1.21.1.jar", "Iris Shaders", true, true),
    ];

    private static readonly ModEntry[] DemoModsDropped =
        [new("zoomify-2.14.2+1.21.1.jar", "Zoomify", true, false), .. DemoMods];

    private static int DemoCount(ModProfile p) => p.Name.StartsWith("PvP", StringComparison.Ordinal) ? 1 : p.HasServer ? 6 : 3;

    [ShotSet]
    public static IEnumerable<Shot> All()
    {
        // ---- the main tour ----
        yield return Profiles("09-profiles", _ => { });
        yield return Profiles("10-profiles-form", p => p.TourOpenForm());
        yield return new Shot("13-mods", w => Mods(w), Seed: Seed);
        yield return ModsDemo("13b-mods-list", DemoMods, 0);
        yield return ModsDemo("13c-mods-dragover", DemoMods, 1);
        yield return ModsDemo("13d-mods-dropped", DemoModsDropped, -1);
        yield return new Shot("23-min-mods", w => Mods(w), 960, 600, Seed);
        yield return ModsDemo("23b-min-mods-list", DemoMods, 0, 960, 600);
        yield return ModsDemo("23c-min-mods-scrolled", DemoMods, 0, 960, 600, m =>
        {
            ShotRunner.Pump();
            m.Scroller.Offset = new Vector(0, Math.Max(0, m.Scroller.Extent.Height - m.Scroller.Viewport.Height));
        });
        yield return Profiles("25-min-profiles", _ => { }, 960, 600);

        // ---- every other state, at the normal window (28) and the smallest one (29-min) ----
        foreach (var shot in States("28", 1180, 740)) yield return shot;
        foreach (var shot in States("29-min", 960, 600)) yield return shot;

        // ---- a dropped jar that is the same mod as an installed one ----
        yield return ModsDemo("30-compat-mods-replace-row", DemoMods, 0, then: m => m.TourOfferReplace(new ReplaceOffer(
            @"C:\Downloads\litematica-fabric-1.21.1-0.21.0.jar", "litematica-fabric-1.21.1-0.21.0.jar", "Litematica", "0.21.0",
            "litematica-fabric-1.21.1-0.19.50.jar", "0.19.50")));
        yield return ModsDemo("30b-compat-mods-replace-row-long", DemoMods, 0, then: m => m.TourOfferReplace(new ReplaceOffer(
            @"C:\Downloads\x.jar", "x.jar", "Xaero's World Map (Fabric edition)", "1.39.2+build.14-fabric-1.21.1",
            "XaerosWorldMap_1.39.2_Fabric_1.21.jar", "1.38.0+build.9-fabric-1.21.1")));

        // ---- the DPI matrix's 100 % row (headless renders at one scale) ----
        yield return ModsDemo("dpi-mods-list-1180x740-100pct", DemoMods, 0);
        yield return Profiles("dpi-profiles-1180x740-100pct", _ => { });
    }

    private static IEnumerable<Shot> States(string prefix, double w, double h)
    {
        yield return ModsDemo($"{prefix}a-mods-empty", [], 0, w, h);
        yield return ModsDemo($"{prefix}b-mods-status-note", DemoMods, 0, w, h, m => m.TourShowStatus("Adding 2 mods...", false));
        yield return ModsDemo($"{prefix}b2-mods-status-override-notice", DemoMods, 0, w, h,
            m => m.TourShowStatus("Added 1 mod. " + "Shaders are off for this profile: no tested Iris for your Sodium 0.6.9. Add a matching Iris to turn them back on.", false));
        yield return ModsDemo($"{prefix}c-mods-status-error", DemoMods, 0, w, h,
            m => m.TourShowStatus("Couldn't remove it. Close Minecraft first, then try again.", true));
        yield return ModsDemo($"{prefix}d-mods-remove-confirm", DemoMods, 0, w, h, m => m.TourConfirmRemove("litematica-fabric-1.21.1-0.19.50.jar"));
        yield return ModsDemo($"{prefix}d2-mods-update", DemoMods, 0, w, h, m => m.TourShowUpdate("litematica-fabric-1.21.1-0.19.50.jar", "0.21.0"));
        yield return ModsDemo($"{prefix}e-mods-no-version", DemoMods, 0, w, h, m => m.TourNoVersion());

        yield return new Shot($"{prefix}f-profiles-empty", win => ProfilesPage(win).TourDemo(Demo.PlayerName, DemoCount), w, h);
        yield return Profiles($"{prefix}g-profiles-status-note",
            p => p.TourShowStatus("Created \"Survival SMP\". Add its mods on the Mods page.", false), w, h);
        yield return Profiles($"{prefix}h-profiles-status-error",
            p => p.TourShowStatus("\"Survival SMP\" is being used by a game that's running. Close Minecraft first.", true), w, h);
        yield return Profiles($"{prefix}i-profiles-delete-confirm", p => p.TourAskDelete(), w, h);
        yield return Profiles($"{prefix}j-profiles-form-edit", p => p.TourEditForm(), w, h);
        // The form's error: the real rule's own sentence for a taken name (never a made-up one).
        yield return Profiles($"{prefix}k-profiles-form-error", p =>
        {
            var profiles = LauncherState.Settings.ModProfiles;
            var taken = profiles[0];
            p.TourFormError(taken.Name, ProfileRules.ValidateName(taken.Name, taken.Version, profiles) ?? "That name is taken.");
        }, w, h);

        yield return ModsDemo($"{prefix}l-mods-focus-open-folder", DemoMods, 0, w, h, m => m.OpenFolderButton.ForceFocus = true);
        yield return ModsDemo($"{prefix}m-mods-focus-remove", DemoMods, 0, w, h, m =>
        {
            if (m.FirstRowButton is { } b) b.ForceFocus = true;
        });
        yield return Profiles($"{prefix}n-profiles-focus-card", p => { if (p.FirstUseButton is { } b) b.ForceFocus = true; }, w, h);
        yield return Profiles($"{prefix}o-profiles-focus-new", p => { if (p.NewCard is { } b) b.ForceFocus = true; }, w, h);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    // Mods shots look at 1.21.1, the design truth (the Windows set's version).
    private static void Seed() => Demo.Version("1.21.1");

    private static ModsPage Mods(MainWindow w)
    {
        w.Navigate(PageKey.Mods);
        return w.PageOf<ModsPage>(PageKey.Mods);
    }

    private static Shot ModsDemo(string name, ModEntry[] mods, int carrying, double w = 1180, double h = 740, Action<ModsPage>? then = null) =>
        new(name, win =>
        {
            ModIconSource.DemoIcons ??= BuildDemoIcons();
            var page = Mods(win);
            page.TourShowDemo(mods, carrying);
            then?.Invoke(page);
        }, w, h, Seed);

    private static ProfilesPage ProfilesPage(MainWindow w)
    {
        w.Navigate(PageKey.Profiles);
        return w.PageOf<ProfilesPage>(PageKey.Profiles);
    }

    // The profile shots: the four made-up profiles (one selected, with a server), the player shown as OriginPlayer
    // and made-up mod counts, like the Windows set.
    private static Shot Profiles(string name, Action<ProfilesPage> then, double w = 1180, double h = 740) =>
        new(name, win =>
        {
            var page = ProfilesPage(win);
            page.TourDemo(Demo.PlayerName, DemoCount);
            then(page);
        }, w, h, () => Demo.Profiles());

    // In-memory icons for some rows (never the network); the rest keep the drawn glyph, so one shot shows both.
    // Pixel-art sizes (16, 32) and a big one (96) exercise both scaling modes. Made-up shot data, not UI colours.
    private static IReadOnlyDictionary<string, byte[]> BuildDemoIcons() => new Dictionary<string, byte[]>
    {
        ["XaerosWorldMap_1.39.2_Fabric_1.21.jar"] = DemoPng(32, (0x2E, 0x7D, 0x4F), (0x9B, 0xE0, 0x6A)),
        ["appleskin-fabric-mc1.21-3.0.5.jar"] = DemoPng(16, (0x9E, 0x2B, 0x2B), (0xF2, 0x7A, 0x7A)),
        ["sodium-fabric-0.6.13+mc1.21.1.jar"] = DemoPng(96, (0x1F, 0x5F, 0x8F), (0x8F, 0xD0, 0xF5)),
    };

    private static byte[] DemoPng(int size, (byte R, byte G, byte B) dark, (byte R, byte G, byte B) light)
    {
        var px = new byte[size * size * 4];
        for (var y = 0; y < size; y++)
            for (var x = 0; x < size; x++)
            {
                var inner = x >= size / 4 && x < size * 3 / 4 && y >= size / 4 && y < size * 3 / 4;
                (byte R, byte G, byte B) c = inner ? light : (x + y) / Math.Max(2, size / 8) % 2 == 0 ? dark : ((byte)(dark.R / 2), (byte)(dark.G / 2), (byte)(dark.B / 2));
                var i = (y * size + x) * 4;
                px[i] = c.B; px[i + 1] = c.G; px[i + 2] = c.R; px[i + 3] = 255;
            }
        using var bmp = new WriteableBitmap(new PixelSize(size, size), new Vector(96, 96), PixelFormat.Bgra8888, AlphaFormat.Premul);
        using (var fb = bmp.Lock())
            for (var row = 0; row < size; row++)
                Marshal.Copy(px, row * size * 4, fb.Address + row * fb.RowBytes, size * 4);
        using var ms = new MemoryStream();
        bmp.Save(ms);
        return ms.ToArray();
    }
}
