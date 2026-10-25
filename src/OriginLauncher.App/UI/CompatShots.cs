using System.Reflection;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.GameFolders;

namespace OriginLauncher.App.UI;

/// <summary>
/// Render mode only (called from RenderShots): the two Phase 5.1 states the normal tour cannot reach because their
/// setters are private. Reflection drives the REAL page code (ModsPage.Refresh + BuildReplaceRow, HomePage.Note +
/// SetStatus) with real model objects (ReplaceOffer, IncompatibleModsException); nothing is drawn by the harness.
/// </summary>
internal static class CompatShots
{
    private const BindingFlags Any = BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public;

    internal static void Run(MainWindow window, string outDir, Core.Mods.ModEntry[] demoMods)
    {
        // 1. Mods page: a dropped jar that is the same mod as an installed one asks Replace / Keep old.
        window.TourShowModsDemo(demoMods, 0);
        RenderShots.Pump(400);
        var modsPage = Get(window, "ModsView");
        var pending = (System.Collections.IList)Field(modsPage, "_pendingReplace");
        pending.Add(new ReplaceOffer(@"C:\Downloads\litematica-fabric-1.21.1-0.21.0.jar", "litematica-fabric-1.21.1-0.21.0.jar",
            "Litematica", "0.21.0", "litematica-fabric-1.21.1-0.19.50.jar", "0.19.50"));
        Call(modsPage, "Refresh", new object?[] { null });
        RenderShots.Pump(600);
        RenderShots.Snap(window, outDir, "30-compat-mods-replace-row");

        // 1b. A long mod name / long versions, to prove the row wraps instead of clipping.
        pending.Clear();
        pending.Add(new ReplaceOffer(@"C:\Downloads\x.jar", "x.jar", "Xaero's World Map (Fabric edition)", "1.39.2+build.14-fabric-1.21.1",
            "XaerosWorldMap_1.39.2_Fabric_1.21.jar", "1.38.0+build.9-fabric-1.21.1"));
        Call(modsPage, "Refresh", new object?[] { null });
        RenderShots.Pump(600);
        RenderShots.Snap(window, outDir, "30b-compat-mods-replace-row-long");
        pending.Clear();
        Call(modsPage, "Refresh", new object?[] { null });

        // 2. Home: the pre-launch check blocked the launch; the exception's own sentence lands as the error line.
        window.TourGoTo(0);
        RenderShots.Pump(500);
        var home = Field(window, "_homePage");
        // Render mode is signed out (no selected account), so Note() would only store the text; SetStatus is what
        // Note() calls for the selected account, with the tone Note() maps LaunchTone.Error to.
        var tone = home.GetType().GetMethod("ToneOf", Any | BindingFlags.Static)!.Invoke(null, new object[] { LaunchTone.Error })!;
        // The page's own selected version, so the sentence names the version the picture shows.
        var version = (string?)Field(home, "_selectedVersion") ?? "1.21.1";
        var ex = new IncompatibleModsException(version,
            new[] { "Old Mod", "Some Lib" },
            new[] { $"Old Mod: needs Minecraft 1.20.4, not {version}.", "Some Lib: it needs Fabric API." });
        Call(home, "SetStatus", new[] { ex.Message, tone });
        RenderShots.Pump(500);
        RenderShots.Snap(window, outDir, "31-compat-home-blocked");

        // 2b. The same with long names and the "(and N more)" tail: the longest message the page will ever show.
        var many = new IncompatibleModsException(version,
            new[] { "Zoomify Extended Edition Pro", "Sodium Extra Optimized Renderer", "Three", "Four", "Five" },
            new[] { "a", "b", "c", "d", "e" });
        Call(home, "SetStatus", new[] { many.Message, tone });
        RenderShots.Pump(500);
        RenderShots.Snap(window, outDir, "31b-compat-home-blocked-long");
    }

    private static object Get(object o, string name) =>
        o.GetType().GetProperty(name, Any)!.GetValue(o)!;

    private static object Field(object o, string name) =>
        o.GetType().GetField(name, Any)!.GetValue(o)!;

    private static object? Call(object o, string name, object?[] args) =>
        o.GetType().GetMethod(name, Any)!.Invoke(o, args);
}
