using System.Net.Http;
using Avalonia;
using Avalonia.Controls;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Components;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI;
using OriginLauncher.Desktop.Overlays;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;
using OriginLauncher.Desktop.Windows;
using CoreFix = OriginLauncher.App.Core.Components.FixCompatibility;

namespace OriginLauncher.Desktop.Shots;

// The launch area, named like the Windows set (UI/RenderShots.cs, SelectorShots, CompatShots, the crash windows) so
// the two sheets line up: the running state, the launch cover's states, the version picker, Fix Compatibility over a
// refused Play, and the two crash windows (drawn as the shot window's content: a render run opens no second window).
public static class LaunchShots
{
    private const string Who = "OriginPlayer - Minecraft 1.21.1";

    [ShotSet]
    public static IEnumerable<Shot> All()
    {
        // ---- Running and the launch cover ----
        yield return new Shot("07-play-running", w => w.Home.Launches.TourRunning("Steve"), Seed: () => Demo.SignIn());
        yield return new Shot("07b-play-running-notice",
            w => w.Home.Launches.TourRunning("Steve", ["Shaders are off for this profile: no tested Iris for your Sodium 0.6.9. Add a matching Iris to turn them back on."]), Seed: () => Demo.SignIn());
        yield return new Shot("08-launch-a-preparing", w => Cover(w, "1.21.1"), Seed: Signed("1.21.1"));
        yield return new Shot("08-launch-b-counted", w => Cover(w, "1.21.1").ReportStage("Downloading libraries (240/600)"), Seed: Signed("1.21.1"));
        yield return new Shot("08-launch-c-installing", w => Cover(w, "1.8.9").ReportStage("Installing"), Seed: Signed("1.8.9"));
        yield return new Shot("08-launch-d-installing-counted", w => Cover(w, "1.8.9").ReportStage("Downloading libraries (96/240)"), Seed: Signed("1.8.9"));
        yield return new Shot("08-launch-e-cancelling", w => Cover(w, "1.21.1").CancelButton.RaiseClick(), Seed: Signed("1.21.1"));
        yield return new Shot("08-launch-f-failed-offline", w => Cover(w, "1.8.9").ShowFailure(
            PlayFailure.From(new OfflineInstallException("1.8.9", new HttpRequestException("no route")), "1.8.9")), Seed: Signed("1.8.9"));
        yield return new Shot("08-launch-g-failed-disk", w => Cover(w, "1.8.9").ShowFailure(
            PlayFailure.From(new IOException("disk", unchecked((int)0x80070070)), "1.8.9")), Seed: Signed("1.8.9"));

        // ---- The version picker ----
        yield return new Shot("10-versions-grid", w => Picker(w, InstalledVersions.Shared), Seed: Signed("1.21.1"));
        // DPI matrix scenes (ShotRunner draws each at 3 sizes x 3 scales, named like the Windows set).
        yield return new Shot("dpi-versions-grid-1180x740-100pct", w => Picker(w, InstalledVersions.Shared), Seed: Signed("1.21.1"));
        yield return new Shot("dpi-launch-failed-1180x740-100pct", w => Cover(w, "1.8.9").ShowFailure(
            PlayFailure.From(new OfflineInstallException("1.8.9", new HttpRequestException("no route")), "1.8.9")), Seed: Signed("1.8.9"));
        yield return new Shot("11-versions-detail", w => Picker(w, InstalledVersions.Shared).OpenFamily("1.21"), Seed: Signed("1.21.1"));
        yield return new Shot("21-min-versions-grid", w => Picker(w, InstalledVersions.Shared), 980, 600, Seed: Signed("1.21.1"));
        yield return new Shot("22-min-versions-detail", w => Picker(w, InstalledVersions.Shared).OpenFamily("1.21"), 980, 600, Seed: Signed("1.21.1"));
        const string p3 = "p3sel-1180x740-";
        yield return new Shot(p3 + "step1", w => Picker(w, Fake()), Seed: Signed("1.21.1"));
        yield return new Shot(p3 + "step1-focus", w => Picker(w, Fake()).Cards[4].ForceFocus = true, Seed: Signed("1.21.1"));
        foreach (var family in new[] { "1.21", "1.20", "1.19" })
            yield return new Shot(p3 + "step2-" + family, w => Picker(w, Fake()).OpenFamily(family), Seed: Signed("1.21.1"));
        yield return new Shot(p3 + "step2-1.21-focus", w =>
        {
            var p = Picker(w, Fake());
            p.OpenFamily("1.21");
            p.Releases.Trigger.ForceFocus = true;
        }, Seed: Signed("1.21.1"));
        yield return new Shot(p3 + "step2-1.21-open", w =>
        {
            var p = Picker(w, Fake());
            p.OpenFamily("1.21");
            ShotRunner.Pump();
            p.OpenReleaseList();
        }, Seed: Signed("1.21.1"));

        // ---- Fix Compatibility over a refused Play ----
        yield return new Shot("31-compat-home-blocked", w => w.Home.Launches.TourRefuse(Refusal(
            ["Old Mod", "Some Lib"], ["Old Mod: needs Minecraft 1.20.4, not 1.21.1.", "Some Lib: it needs Fabric API."])), Seed: Signed("1.21.1"));
        yield return new Shot("31b-compat-home-blocked-long", w => w.Home.Launches.TourRefuse(Refusal(
            ["Zoomify Extended Edition Pro", "Sodium Extra Optimized Renderer", "Three", "Four", "Five"], ["a", "b", "c", "d", "e"])), Seed: Signed("1.21.1"));
        yield return new Shot("32-compat-fix-prompt", w => Prompt(w, ["Old Mod", "Some Lib"],
            ["Old Mod: needs Minecraft 1.20.4, not 1.21.1.", "Some Lib: it needs Cloth Config."], Plan(OldMod, SomeLib)), Seed: Signed("1.21.1"));
        yield return new Shot("32b-compat-fix-prompt-nofix", w => Prompt(w, ["Old Mod"],
            ["Old Mod: needs Minecraft 1.20.4, not 1.21.1."], null), Seed: Signed("1.21.1"));
        yield return new Shot("32c-compat-fix-prompt-dependencies", w => Prompt(w, ["Voice Messages", "Some Lib"],
            ["Voice Messages: it needs Simple Voice Chat.", "Some Lib: it needs Cloth Config."], Plan(VoiceMessages, SomeLib, layered: true)),
            Seed: Signed("1.21.1"));

        // ---- The crash windows ----
        yield return new Shot("40-crash-window", w => ShowAsWindow(w, new CrashView(
            "Origin Launcher - crash report\nVersion : 1.0.0\nType    : fatal\n\n----- error -----\nSystem.InvalidOperationException: Example failure\n   at Origin.Example.Run()",
            "/home/player/.local/share/OriginLauncher/logs/crash-example.txt", fatal: true, () => { }, w), Sizes.CrashW, Sizes.CrashH));
        yield return new Shot("41-crash-confirmed", w => Report(w, Diagnose("[Render thread/ERROR]: java.lang.OutOfMemoryError: Java heap space")));
        yield return new Shot("41b-crash-java-args", w => Report(w, CrashEngine.Diagnose(new CrashEvidence(1, false, true,
            "Unrecognized VM option 'UseZGCx'\nError: Could not create the Java Virtual Machine.\nError: A fatal exception has occurred. Program will exit.",
            null, null, null, [], 4096, 16384) { CustomJvmArgs = "-XX:+UseZGCx -Xss2m" })));
        yield return new Shot("42-crash-strong", w => Report(w, Diagnose(MixinLine, whileStarting: true)));
        yield return new Shot("42-crash-strong-details", w => Report(w, Diagnose(MixinLine, whileStarting: true), openDetails: true));
        yield return new Shot("43-crash-possible", w => Report(w, Diagnose(null, hsErr:
            "# A fatal error has been detected by the Java Runtime Environment:\n#\n#  EXCEPTION_ACCESS_VIOLATION (0xc0000005)\n# Problematic frame:\n# C  [nvoglv64.dll+0x9a1f2c]\n")));
        yield return new Shot("44-crash-unknown", w => Report(w, Diagnose(null, crash:
            "---- Minecraft Crash Report ----\nDescription: Unexpected error\n\njava.lang.IllegalStateException: example\n\tat net.minecraft.client.Minecraft.run(Minecraft.java:1)\n")));
    }

    private static Action Signed(string version) => () => { Demo.SignIn(); Demo.Version(version); };

    private static LaunchCover Cover(MainWindow w, string version) =>
        w.Home.Launches.TourCover(version, $"{Demo.PlayerName} - Survival SMP");

    // ---- Picker ----

    internal static VersionPicker Picker(MainWindow w, InstalledVersions installs)
    {
        if (ReferenceEquals(installs, InstalledVersions.Shared)) installs.RefreshAllAsync().GetAwaiter().GetResult();
        var picker = w.Home.OpenPicker(installs);
        ShotRunner.Pump();
        return picker;
    }

    // Three multi-release families with a mix of every state, plus single-release families showing their own state
    // (the Windows SelectorShots set), off the production disk check over made-up install folders.
    internal static InstalledVersions Fake()
    {
        var states = new Dictionary<string, InstallState>
        {
            ["26.3"] = InstallState.Installed, ["1.21.11"] = InstallState.Installed, ["1.21.1"] = InstallState.Installed,
            ["1.21.2"] = InstallState.UpdateReady, ["1.21"] = InstallState.Partial, ["1.20.4"] = InstallState.Installed,
            ["1.20.2"] = InstallState.Installed, ["1.20.1"] = InstallState.Partial, ["1.19.4"] = InstallState.UpdateReady,
            ["1.19.3"] = InstallState.Installed, ["1.18.2"] = InstallState.Installed, ["1.17.1"] = InstallState.UpdateReady,
            ["1.8.9"] = InstallState.Partial,
        };
        var fake = SelectorFakeInstalls.Build(Path.Combine(Path.GetTempPath(), "origin-selector-shots-" + Guid.NewGuid().ToString("N")), states);
        fake.RefreshAllAsync().GetAwaiter().GetResult();
        return fake;
    }

    // ---- Fix Compatibility ----

    private static IncompatibleModsException Refusal(string[] names, string[] reasons) =>
        new(LauncherState.SelectedVersion, names, reasons);

    private static void Prompt(MainWindow w, string[] names, string[] reasons, FixProposal? proposal)
    {
        // The status line behind the prompt is the refusal Play just set: the same mods the prompt names.
        w.Home.Launches.TourRefuse(Refusal(names, reasons));
        Overlays.FixCompatibility.TourPrompt(w, names, LauncherState.SelectedVersion, proposal, DemoProfile);
    }

    private static readonly ModProfile DemoProfile = new() { Id = ProfileRules.NewId(), Name = "Hypixel PvP", Version = "1.21.1" };

    private static ModFile Mod(string file, string id, string name, params ModDependency[] depends) =>
        new("/demo/profile/mods/" + file, file, true, ModRole.Profile, new ModInfo
        {
            FileName = file, Loader = ModLoader.Fabric, Id = id, Name = name, Version = "1.0.0", Depends = depends,
        });

    private static ModFile OldMod => Mod("oldmod-1.0.0.jar", "oldmod", "Old Mod", new ModDependency("minecraft", ["1.20.4"]));
    private static ModFile SomeLib => Mod("somelib-2.1.jar", "somelib", "Some Lib", new ModDependency("cloth-config", [">=15.0.0"]));
    private static ModFile VoiceMessages => Mod("voicemessages-1.4.jar", "voicemessages", "Voice Messages", new ModDependency("voicechat", ["*"]));

    // A proposal the real planner (FixCompatibility.Choose -> RepairPlanner) makes from model mods; no network.
    private static FixProposal Plan(ModFile a, ModFile b, bool layered = false)
    {
        const string version = "1.21.1";
        var platform = LaunchPreflight.PlatformFor(version);
        var source = new FixSource(version, DemoProfile, "/demo/mods", "/demo/profile/mods", _ => false, _ => false);
        var shipped = ConfigurationCatalog.For(version).First(c => c.IsDefault);
        IDependencyCatalog catalog = layered ? new LayeredDependencyCatalog(OriginDependencyCatalog.Instance, new DemoCatalog()) : new DemoCatalog();
        return CoreFix.Choose(source, [(shipped, CompatEngine.Check([a, b], platform))], catalog, platform);
    }

    // What Modrinth would answer for the demo's missing library (no network in render mode).
    private sealed class DemoCatalog : IDependencyCatalog
    {
        public DependencyOffer? Find(string id, VersionPredicate range, CompatPlatform platform) =>
            id != "cloth-config" ? null
                : new DependencyOffer(id, "15.0.140", "cloth-config-15.0.140-fabric.jar",
                    new ModInfo
                    {
                        FileName = "cloth-config-15.0.140-fabric.jar", Loader = ModLoader.Fabric, Id = id, Version = "15.0.140",
                        Name = "Cloth Config",
                    },
                    "https://cdn.modrinth.com/data/demo/cloth-config-15.0.140-fabric.jar", Sha512: new string('0', 128));
    }

    // ---- Crash windows ----

    private const string MixinLine =
        "[main/ERROR]: Mixin apply for mod examplemod failed examplemod.mixins.json:ExampleMixin from mod examplemod -> net.minecraft.class_310: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: Critical injection failure";

    private static CrashVerdict Diagnose(string? log, bool whileStarting = false, string? crash = null, string? hsErr = null) =>
        CrashEngine.Diagnose(new CrashEvidence(1, false, whileStarting, log, null, crash, hsErr,
            [new CrashModEntry("examplemod", "examplemod-1.2.3.jar", true, ["com.example.examplemod"]),
             new CrashModEntry("originclient", "originclient.jar", false)], 4096, 16384));

    private static void Report(MainWindow w, CrashVerdict verdict, bool openDetails = false)
    {
        var view = new CrashReportView(verdict, null, Who, () => "report", new CrashReportActions(_ => { }, () => { }, () => { }, w));
        if (openDetails) view.DetailsButton.RaiseClick();
        view.Measure(new Size(Sizes.CrashReportW, double.PositiveInfinity));
        ShowAsWindow(w, view, Sizes.CrashReportW, Math.Ceiling(view.DesiredSize.Height));
    }

    // The crash window as it opens: the shot window becomes that window (its size, its content, no launcher behind).
    private static void ShowAsWindow(MainWindow w, Control view, double width, double height)
    {
        w.MinWidth = 0;
        w.MinHeight = 0;
        w.Background = Palette.Panel;
        w.Content = view;
        w.Width = width;
        w.Height = height;
    }
}
