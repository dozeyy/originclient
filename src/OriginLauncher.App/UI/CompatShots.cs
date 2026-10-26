using System.Reflection;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Components;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Profiles;

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
        var report = new System.Text.StringBuilder();
        Measure(window, home, "31 (two short names)", report);
        RenderShots.Snap(window, outDir, "31-compat-home-blocked");

        // 2b. The same with long names and the "(and N more)" tail: the longest message the page will ever show.
        var many = new IncompatibleModsException(version,
            new[] { "Zoomify Extended Edition Pro", "Sodium Extra Optimized Renderer", "Three", "Four", "Five" },
            new[] { "a", "b", "c", "d", "e" });
        Call(home, "SetStatus", new[] { many.Message, tone });
        RenderShots.Pump(500);
        Measure(window, home, "31b (long names, 3 more)", report);
        RenderShots.Snap(window, outDir, "31b-compat-home-blocked-long");

        // The same two messages for 1 and 2 mods with the longest names, so every shape of the line is measured.
        foreach (var (label, names) in new[]
        {
            ("one long name", new[] { "Zoomify Extended Edition Pro" }),
            ("two long names", new[] { "Zoomify Extended Edition Pro", "Sodium Extra Optimized Renderer" }),
            ("wide capitals", new[] { new string('W', 30), new string('W', 30), "x" }),
        })
        {
            var e = new IncompatibleModsException(version, names, names);
            Call(home, "SetStatus", new[] { e.Message, tone });
            RenderShots.Pump(300);
            Measure(window, home, label, report);
        }
        System.IO.File.WriteAllText(System.IO.Path.Combine(outDir, "31-measure.txt"), report.ToString());
        Console.WriteLine(report.ToString());
        if (report.ToString().Contains("FAIL")) throw new InvalidOperationException("Home error line out of bounds:\n" + report);

        FixPrompt(window, outDir, home, version, tone);
    }

    // 3. Phase 5.3: the Fix Compatibility prompt over the refused Play, through the page's own OpenFixPrompt and
    // ShowFixPlan, with proposals the real planner (FixCompatibility.Choose -> RepairPlanner) makes from model mods.
    private static void FixPrompt(MainWindow window, string outDir, object home, string version, object tone)
    {
        var platform = LaunchPreflight.PlatformFor(version);
        var profile = new ModProfile { Id = ProfileRules.NewId(), Name = "Hypixel PvP", Version = version };
        var source = new FixSource(version, profile, @"C:\demo\mods", @"C:\demo\profile\mods", _ => false, _ => false);
        var shipped = ConfigurationCatalog.For(version).First(c => c.IsDefault);

        ModFile Mod(string file, string id, string name, ModRole role, params ModDependency[] depends) =>
            new(@"C:\demo\profile\mods\" + file, file, true, role, new ModInfo
            {
                FileName = file, Loader = ModLoader.Fabric, Id = id, Name = name, Version = "1.0.0", Depends = depends,
            });
        var minecraft = new ModDependency("minecraft", new[] { "1.20.4" });
        var entries = new[]
        {
            Mod("oldmod-1.0.0.jar", "oldmod", "Old Mod", ModRole.Profile, minecraft),
            Mod("somelib-2.1.jar", "somelib", "Some Lib", ModRole.Profile, new ModDependency("cloth-config", new[] { ">=15.0.0" })),
        };
        var proposal = FixCompatibility.Choose(source, new[] { (shipped, CompatEngine.Check(entries, platform)) },
            new DemoCatalog(), platform);

        // The status line behind the prompt is the refusal Play just set: the same mods the prompt names.
        Call(home, "SetStatus", new[] { new IncompatibleModsException(version, new[] { "Old Mod", "Some Lib" },
            new[] { $"Old Mod: needs Minecraft 1.20.4, not {version}.", "Some Lib: it needs Cloth Config." }).Message, tone });
        Call(home, "OpenFixPrompt", new object[] { new[] { "Old Mod", "Some Lib" }, version });
        Call(home, "ShowFixPlan", new object?[] { proposal, profile });
        RenderShots.Pump(500);
        RenderShots.Snap(window, outDir, "32-compat-fix-prompt");

        // 3b. No plan could be made (Modrinth too slow, a jar unreadable): the prompt says so plainly and keeps
        // Launch anyway, with no Fix button.
        Call(home, "SetStatus", new[] { new IncompatibleModsException(version, new[] { "Old Mod" },
            new[] { $"Old Mod: needs Minecraft 1.20.4, not {version}." }).Message, tone });
        Call(home, "OpenFixPrompt", new object[] { new[] { "Old Mod" }, version });
        Call(home, "ShowFixPlan", new object?[] { null, profile });
        RenderShots.Pump(500);
        RenderShots.Snap(window, outDir, "32b-compat-fix-prompt-nofix");
        ((Controls.FixCompatibilityOverlay)Field(home, "FixPrompt")).Dismiss();
        RenderShots.Pump(200);
    }

    // What Modrinth would answer for the demo's missing library (no network in render mode).
    private sealed class DemoCatalog : IDependencyCatalog
    {
        public DependencyOffer? Find(string id, VersionPredicate range, CompatPlatform platform) =>
            id != "cloth-config" ? null
                : new DependencyOffer(id, "15.0.140", "cloth-config-15.0.140-fabric.jar",
                    new ModInfo { FileName = "cloth-config-15.0.140-fabric.jar", Loader = ModLoader.Fabric, Id = id, Version = "15.0.140" },
                    "https://cdn.modrinth.com/data/demo/cloth-config-15.0.140-fabric.jar", Sha512: new string('0', 128));
    }

    // Measures the status line the page really shows: each row's real width against the text box's own limit, the rows
    // it wrapped to, and how far the bottom of the text is from the bottom of the window (at least 32 px, Space.5).
    private static void Measure(MainWindow window, object home, string label, System.Text.StringBuilder report)
    {
        var text = (System.Windows.Controls.TextBlock)Field(home, "StatusText");
        var rows = text.Text.Split('\n');
        var widest = 0.0;
        foreach (var row in rows)
        {
            var probe = new System.Windows.Controls.TextBlock
            {
                Text = row, FontFamily = text.FontFamily, FontSize = text.FontSize, FontWeight = text.FontWeight,
            };
            probe.Measure(new System.Windows.Size(double.PositiveInfinity, double.PositiveInfinity));
            widest = Math.Max(widest, probe.DesiredSize.Width);
        }
        var bottom = text.TransformToAncestor(window).Transform(new System.Windows.Point(0, text.ActualHeight)).Y;
        var gutter = window.ActualHeight - bottom;
        var line = (double)text.FindResource("Size.StatusLine");
        var wrapped = Math.Round(text.ActualHeight / line);
        var ok = widest <= text.MaxWidth && gutter >= 32 && wrapped <= 2;
        report.Append(ok ? "ok   " : "FAIL ").Append(label).Append(": widest row ").Append(Math.Round(widest))
            .Append(" px of ").Append(text.MaxWidth).Append(", box ").Append(Math.Round(text.ActualHeight)).Append(" px tall, text bottom gutter ")
            .Append(Math.Round(gutter, 1)).Append(" px\n");
    }

    private static object Get(object o, string name) =>
        o.GetType().GetProperty(name, Any)!.GetValue(o)!;

    private static object Field(object o, string name) =>
        o.GetType().GetField(name, Any)!.GetValue(o)!;

    private static object? Call(object o, string name, object?[] args) =>
        o.GetType().GetMethod(name, Any)!.Invoke(o, args);
}
