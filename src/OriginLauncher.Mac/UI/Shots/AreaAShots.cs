using System.Text;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Headless;
using Avalonia.Input;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.VisualTree;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Components;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Mac.UI.Controls;
using OriginLauncher.Mac.UI.Pages;
using OriginLauncher.Mac.UI.Windows;

namespace OriginLauncher.Mac.UI.Shots;

/// <summary>
/// Render shots for the version picker, Settings, the Play flow, the overlays and the crash windows: the Mac twins of
/// the WPF RenderShots launch / versions / settings / crash sections, SelectorShots, CompatShots (its Home half) and
/// SettingsStatesShots, under the SAME file names and with the same made-up data, through the pages' own code.
/// A step that throws is written to area-a-error.txt and counted as a failure; the run goes on.
/// Not pictured here, on purpose: 30/30b-compat-mods-* (the Mods page), 30/32-tooltip-* (the Import button and the
/// Profiles page) and 31-tooltip-close (the Mac has no Close button: macOS draws the traffic lights).
/// </summary>
internal static class AreaAShots
{
    private static string _dir = "";

    internal static void Run(MainWindow window, string dir)
    {
        _dir = dir;
        RenderShots.Resize(window, 1044, 740);
        var home = window.Home;
        var homeVersion = home.CurrentVersion;

        Step("launch", () => Launch(window, homeVersion));
        Step("play-running", () =>
        {
            window.TourNavigate(0);
            home.TourShowRunning("Steve");
            RenderShots.Snap(window, "07-play-running");
            home.TourShowReady();
        });
        Step("versions", () => Versions(window, "10-versions-grid", "11-versions-detail"));
        Step("selector", () => SelectorShots(window));
        Step("compat", () => Compat(window));
        Step("settings", () => Settings(window, "14-settings", "14b-settings-middle", "14c-settings-bottom", "14d-settings-sync", tour: true));
        Step("settings-states", () => SettingsStates(window));

        // The smallest window the launcher allows: the same screens again, to prove nothing clips.
        RenderShots.Resize(window, 980, 600);
        window.TourNavigate(0);
        Step("min-home", () =>
        {
            home.SetReady(false, "Sign in to play"); // the Windows twin is the signed-out tour state
            RenderShots.Snap(window, "20-min-home");
        });
        Step("min-versions", () => Versions(window, "21-min-versions-grid", "22-min-versions-detail"));
        Step("min-settings", () => Settings(window, "24-min-settings", "24b-min-settings-middle", "24c-min-settings-bottom", "24d-min-settings-sync", tour: false));
        window.TourNavigate(0);
        RenderShots.Resize(window, 1044, 740);

        Step("crash-windows", CrashWindows);
        Step("startup-intro", StartupIntroShot);
        Step("dpi", () => DpiMatrix(window, homeVersion));
        RenderShots.Resize(window, 1044, 740);
    }

    // ---- dpi-<scene>-<w>x<h>-<pct>pct: this area's scenes of the WPF DpiMatrix (RenderShots 434-511) ----
    // The window laid out at each reference size in DIP (980x600 minimum, 1180x740 default, 1920x1080), rasterised at
    // 100 / 150 / 200 % (200 % is every Retina Mac), so a 1180x740 shot at 200 % is 2360x1480 like the Windows twin.
    // Headless Avalonia has one render scale, so the frame is drawn through a RenderTargetBitmap at that DPI.
    // Home-friends-toasts, mods-list, profiles and sign-in belong to the account/mods shots (area B).
    private static void DpiMatrix(MainWindow window, string? homeVersion)
    {
        var home = window.Home;
        // The Windows matrix runs on its tour's opening state: the newest version picked, signed out.
        window.SelectVersion("26.3");
        home.SetReady(false, "Sign in to play");
        void OpenPicker()
        {
            // The sheet sizes its cards when it opens for the window it is in: reopen it at every size (the WPF matrix
            // re-runs the picker's relayout per size for the same reason).
            home.TourHideVersions();
            home.TourShowVersions(-1);
            WaitArt(home.Versions);
        }
        var scenes = new (string Name, Action Setup, Action? PerSize, Action Cleanup)[]
        {
            ("home", () => window.TourNavigate(0), null, () => { }),
            ("versions-grid", () => window.TourNavigate(0), OpenPicker, home.TourHideVersions),
            ("settings", () => window.TourNavigate(3), null, () => window.TourNavigate(0)),
            ("launch-failed", () =>
            {
                window.TourNavigate(0);
                window.SelectVersion("1.8.9");
                RenderShots.PumpFor(300);
                home.TourShowLaunch("1.8.9");
                home.TourFailLaunch(new OfflineInstallException("1.8.9", new HttpRequestException("no route")), "1.8.9");
            }, null, () => { home.TourHideLaunch(); window.SelectVersion("26.3"); }),
        };
        foreach (var (name, setup, perSize, cleanup) in scenes)
        {
            setup();
            RenderShots.Pump();
            foreach (var percent in new[] { 100, 150, 200 })
                foreach (var (w, h) in new[] { (980, 600), (1180, 740), (1920, 1080) })
                {
                    RenderShots.Resize(window, w, h);
                    perSize?.Invoke();
                    RenderShots.SnapScaled(window, $"dpi-{name}-{w}x{h}-{percent}pct", percent / 100.0);
                }
            cleanup();
        }
        window.SelectVersion(homeVersion ?? "1.21.1");
    }

    // ---- 08: the launch screen, then Play on a version that isn't installed (WPF RenderShots 196-232) ----
    private static void Launch(MainWindow window, string? homeVersion)
    {
        var home = window.Home;
        window.TourNavigate(0);
        window.SelectVersion("1.21.1");
        home.TourShowLaunch("1.21.1");
        RenderShots.Snap(window, "08-launch-a-preparing");
        home.TourReportLaunch("Downloading libraries (240/600)");
        RenderShots.Snap(window, "08-launch-b-counted");
        home.TourHideLaunch();

        window.SelectVersion("1.8.9");
        home.TourShowLaunch("1.8.9");
        home.TourReportLaunch("Installing");
        RenderShots.Snap(window, "08-launch-c-installing");
        home.TourReportLaunch("Downloading libraries (96/240)");
        RenderShots.Snap(window, "08-launch-d-installing-counted");
        home.TourCancelLaunch();
        RenderShots.Snap(window, "08-launch-e-cancelling");
        home.TourFailLaunch(new OfflineInstallException("1.8.9", new HttpRequestException("no route")), "1.8.9");
        RenderShots.Snap(window, "08-launch-f-failed-offline");
        // ENOSPC is the Mac's "disk full"; a local shots run on Windows uses Windows' 0x80070070 (the same sentence).
        var diskFull = OperatingSystem.IsWindows() ? unchecked((int)0x80070070) : 28;
        home.TourFailLaunch(new IOException("No space left on device", diskFull), "1.8.9");
        RenderShots.Snap(window, "08-launch-g-failed-disk");
        home.TourHideLaunch();
        window.SelectVersion(homeVersion ?? "1.21.1");
    }

    // ---- 10/11 (21/22 at the smallest size): the picker's grid and a family opened ----
    private static void Versions(MainWindow window, string grid, string detail)
    {
        var home = window.Home;
        window.TourNavigate(0);
        home.TourShowVersions(-1);
        WaitArt(home.Versions);
        RenderShots.Snap(window, grid);
        home.TourShowVersions(1);
        RenderShots.Snap(window, detail);
        home.TourHideVersions();
    }

    private static void WaitArt(VersionSelectOverlay overlay)
    {
        var art = overlay.TourArtReady();
        var until = DateTime.UtcNow.AddSeconds(10);
        while (!art.IsCompleted && DateTime.UtcNow < until) RenderShots.PumpFor(50);
        RenderShots.Pump();
    }

    // ---- p3sel-*: the two-step selector over fake installs (WPF SelectorShots) ----
    private static readonly Dictionary<string, InstallState> States = new()
    {
        ["26.3"] = InstallState.Installed,
        ["1.21.11"] = InstallState.Installed,
        ["1.21.1"] = InstallState.Installed,
        ["1.21.2"] = InstallState.UpdateReady,
        ["1.21"] = InstallState.Partial,
        ["1.20.4"] = InstallState.Installed,
        ["1.20.2"] = InstallState.Installed,
        ["1.20.1"] = InstallState.Partial,
        ["1.19.4"] = InstallState.UpdateReady,
        ["1.19.3"] = InstallState.Installed,
        ["1.18.2"] = InstallState.Installed,
        ["1.17.1"] = InstallState.UpdateReady,
        ["1.8.9"] = InstallState.Partial,
    };

    private static void SelectorShots(MainWindow window)
    {
        var overlay = window.Home.Versions;
        var scratch = Path.Combine(Path.GetTempPath(), "origin-selector-shots-" + Environment.ProcessId);
        var fake = SelectorFakeInstalls.Build(scratch, States);
        // Off the UI thread: the refresh's continuations must not wait for the dispatcher this thread is holding.
        Task.Run(() => fake.RefreshAllAsync()).GetAwaiter().GetResult();
        var real = overlay.Installs;
        overlay.Installs = fake;
        var (w0, h0) = (window.Width, window.Height);
        var report = new StringBuilder("what the selector says, off the production disk check over fake installs" + Environment.NewLine);
        try
        {
            foreach (var (w, h) in new[] { (960, 600), (1180, 740), (1920, 1080) })
            {
                var label = $"{w}x{h}";
                RenderShots.Resize(window, w, h);
                window.TourNavigate(0);

                overlay.TourOpen(-1, window.Home.CurrentVersion);
                WaitArt(overlay);
                RenderShots.Snap(window, $"p3sel-{label}-step1");
                if (label == "960x600")
                    foreach (var group in VersionCatalog.Groups)
                        report.AppendLine($"card {group.Key}: {overlay.BadgeTextOf(group.Key)}");

                overlay.TourFocus(4);
                RenderShots.Snap(window, $"p3sel-{label}-step1-focus");

                foreach (var key in new[] { "1.21", "1.20", "1.19" })
                {
                    overlay.TourOpen(IndexOf(key), window.Home.CurrentVersion);
                    RenderShots.Snap(window, $"p3sel-{label}-step2-{key}");
                    if (label == "960x600")
                        foreach (var (v, dot) in overlay.DropdownItems)
                            report.AppendLine($"release {v}: {(dot ? "dot" : "-")}{(v == overlay.DropdownChoice ? " (chosen)" : "")}");
                }

                overlay.TourOpen(IndexOf("1.21"), window.Home.CurrentVersion);
                overlay.TourFocus(0);
                RenderShots.Snap(window, $"p3sel-{label}-step2-1.21-focus");

                // The dropdown open. Its list is a popup (its own top level), so the picture draws it where it opens.
                overlay.TourDropdown(true);
                RenderShots.Pump();
                if (overlay.FindDescendantOfType<ComboBox>() is { } combo) SnapWithPopup(window, combo, $"p3sel-{label}-step2-1.21-open");
                overlay.TourDropdown(false);

                // Pick a release the way a player does (the dropdown's own selection), then Esc: Home shows it.
                var before = window.Home.CurrentVersion;
                overlay.TourOpen(IndexOf("1.20"), before);
                overlay.ChooseRelease(VersionCatalog.Groups[IndexOf("1.20")].Versions.ToList().IndexOf("1.20.4"));
                overlay.HandleKey(Key.Escape, null);
                RenderShots.Snap(window, $"p3sel-{label}-home-after");
                if (label == "960x600") report.AppendLine($"home after picking 1.20.4: {window.Home.CurrentVersion} (was {before})");
                // Put the run's version back for the shots after this one.
                if (before != null && VersionCatalog.IsSupported(before))
                {
                    var family = VersionCatalog.Groups.ToList().FindIndex(g => g.Versions.Contains(before));
                    overlay.TourOpen(family, before);
                    if (overlay.Nav.Step == SelectorStep.Releases)
                        overlay.ChooseRelease(VersionCatalog.Groups[family].Versions.ToList().IndexOf(before));
                    else
                        overlay.ChooseFamily(family);
                }
                window.Home.TourHideVersions();
            }
        }
        finally
        {
            overlay.Installs = real;
            RenderShots.Resize(window, (int)w0, (int)h0);
            File.WriteAllText(Path.Combine(_dir, "selector-states.txt"), report.ToString());
            try { Directory.Delete(scratch, true); } catch { /* best effort */ }
        }
    }

    private static int IndexOf(string key)
    {
        for (var i = 0; i < VersionCatalog.Groups.Count; i++)
            if (VersionCatalog.Groups[i].Key == key) return i;
        return 0;
    }

    // ---- 31/32: Home's blocked launch and the Fix Compatibility prompt (WPF CompatShots, its Home half) ----
    private static void Compat(MainWindow window)
    {
        var home = window.Home;
        window.TourNavigate(0);
        var version = home.CurrentVersion ?? "1.21.1";
        home.TourShowError(new IncompatibleModsException(version, new[] { "Old Mod", "Some Lib" },
            new[] { $"Old Mod: needs Minecraft 1.20.4, not {version}.", "Some Lib: it needs Fabric API." }).Message);
        RenderShots.Snap(window, "31-compat-home-blocked");
        home.TourShowError(new IncompatibleModsException(version,
            new[] { "Zoomify Extended Edition Pro", "Sodium Extra Optimized Renderer", "Three", "Four", "Five" },
            new[] { "a", "b", "c", "d", "e" }).Message);
        RenderShots.Snap(window, "31b-compat-home-blocked-long");

        // The planner's own proposals from model mods (FixCompatibility.Choose -> RepairPlanner), no network.
        var platform = LaunchPreflight.PlatformFor(version);
        var profile = new ModProfile { Id = ProfileRules.NewId(), Name = "Hypixel PvP", Version = version };
        var source = new FixSource(version, profile, "/demo/mods", "/demo/profile/mods", _ => false, _ => false);
        var shipped = ConfigurationCatalog.For(version).First(c => c.IsDefault);
        ModFile Mod(string file, string id, string name, params ModDependency[] depends) =>
            new("/demo/profile/mods/" + file, file, true, ModRole.Profile, new ModInfo
            {
                FileName = file, Loader = ModLoader.Fabric, Id = id, Name = name, Version = "1.0.0", Depends = depends,
            });
        var entries = new[]
        {
            Mod("oldmod-1.0.0.jar", "oldmod", "Old Mod", new ModDependency("minecraft", new[] { "1.20.4" })),
            Mod("somelib-2.1.jar", "somelib", "Some Lib", new ModDependency("cloth-config", new[] { ">=15.0.0" })),
        };
        var proposal = FixCompatibility.Choose(source, new[] { (shipped, CompatEngine.Check(entries, platform)) },
            new DemoCatalog(), platform);
        home.TourShowError(new IncompatibleModsException(version, new[] { "Old Mod", "Some Lib" },
            new[] { $"Old Mod: needs Minecraft 1.20.4, not {version}.", "Some Lib: it needs Cloth Config." }).Message);
        home.TourOpenFix(new[] { "Old Mod", "Some Lib" }, version);
        home.TourShowFixPlan(proposal, profile);
        RenderShots.Snap(window, "32-compat-fix-prompt");

        var voiceEntries = new[]
        {
            Mod("voicemessages-1.4.jar", "voicemessages", "Voice Messages", new ModDependency("voicechat", new[] { "*" })),
            entries[1],
        };
        var layered = FixCompatibility.Choose(source, new[] { (shipped, CompatEngine.Check(voiceEntries, platform)) },
            new LayeredDependencyCatalog(OriginDependencyCatalog.Instance, new DemoCatalog()), platform);
        home.TourShowError(new IncompatibleModsException(version, new[] { "Voice Messages", "Some Lib" },
            new[] { "Voice Messages: it needs Simple Voice Chat.", "Some Lib: it needs Cloth Config." }).Message);
        home.TourOpenFix(new[] { "Voice Messages", "Some Lib" }, version);
        home.TourShowFixPlan(layered, profile);
        RenderShots.Snap(window, "32c-compat-fix-prompt-dependencies");

        home.TourShowError(new IncompatibleModsException(version, new[] { "Old Mod" },
            new[] { $"Old Mod: needs Minecraft 1.20.4, not {version}." }).Message);
        home.TourOpenFix(new[] { "Old Mod" }, version);
        home.TourShowFixPlan(null, profile);
        RenderShots.Snap(window, "32b-compat-fix-prompt-nofix");
        home.TourCloseFix();
        home.TourShowReady();
    }

    // What Modrinth would answer for the demo's missing library (no network in a shots run).
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

    // ---- 14 (24 at the smallest size): Settings, scrolled, the Sync row, rail clicks, End ----
    private static void Settings(MainWindow window, string top, string middle, string bottom, string sync, bool tour)
    {
        window.TourNavigate(3);
        var page = window.Settings;
        RenderShots.Snap(window, top);
        var max = page.Scroller.Extent.Height - page.Scroller.Viewport.Height;
        if (max >= 1)
        {
            page.Scroller.Offset = new Vector(0, max / 2);
            RenderShots.Snap(window, middle);
            page.Scroller.Offset = new Vector(0, max);
            RenderShots.Snap(window, bottom);
            page.Scroller.Offset = default;
        }
        page.TourScrollToSync();
        RenderShots.Snap(window, sync);
        page.TourScrollToTop();
        if (!tour) return;

        foreach (var (section, name) in new[] { ("SecClient", "14f-settings-click-client"), ("SecStartup", "14g-settings-click-startup") })
        {
            page.TourClickRail(section);
            RenderShots.Snap(window, name);
            page.ShowFromTop();
        }
        // A rail item pressed, then End on the keyboard: the lit item must follow the page, not the old click.
        page.TourClickRail("SecStartup");
        RenderShots.Pump();
        page.TourPressEnd();
        RenderShots.Snap(window, "14h-settings-key-end");
        page.ShowFromTop();
    }

    // ---- 14i-14u: each section, Java-args Undo, field states, the dropdown, confirms, toasts (SettingsStatesShots) ----
    private static void SettingsStates(MainWindow window)
    {
        window.TourNavigate(3);
        var page = window.Settings;
        foreach (var (section, name) in new[]
                 {
                     ("SecSync", "14i-settings-sec-sync"), ("SecPerformance", "14j-settings-sec-performance"),
                     ("SecGame", "14k-settings-sec-game"), ("SecJvm", "14l-settings-sec-jvm"),
                     ("SecDeveloper", "14m-settings-sec-developer"),
                 })
            Step(name, () =>
            {
                page.TourClickRail(section);
                RenderShots.Snap(window, name);
            });

        Step("jvm-undo", () => JvmUndo(window, page));
        Step("inputs", () => Inputs(window, page));
        Step("dropdown", () => Dropdown(window, page));
        Step("confirm", () => Confirms(window));
        Step("toasts", () => Toasts(window));
        page.ShowFromTop();
    }

    // Java arguments after the crash window reset them: the "Reset after a crash" row with Undo, put in the scratch
    // settings through the real repair (RepairExecutor.ApplySettings), then removed again.
    private static void JvmUndo(MainWindow window, SettingsPage page)
    {
        if (!OriginPaths.IsScratchRoot) return;
        var combo = page.JvmVersionComboBox;
        var added = false;
        if (combo.SelectedItem is not string)
        {
            // A fresh scratch profile has no installed versions: give the list one, the way an install would.
            combo.ItemsSource = new[] { "1.21.1" };
            combo.SelectedIndex = 0;
            added = true;
        }
        var version = (string)combo.SelectedItem!;
        SettingsStore.Update(s => s.JvmArgsByVersion[version] = new global::OriginLauncher.App.Core.Models.VersionJvmOptions { Arguments = "-XX:+UseZGCx -Xss2m" });
        RepairExecutor.ApplySettings(RepairPlanner.ResetJavaArgs(version, SettingsStore.Load().JvmArgsFor(version),
            "Java refused to start the game with them."), SettingsStore.Update);
        page.TourReloadJvm();
        page.TourClickRail("SecJvm");
        RenderShots.Snap(window, "14lb-settings-jvm-undo");

        SettingsStore.Update(s => s.JvmArgsBeforeReset[version] = new global::OriginLauncher.App.Core.Models.VersionJvmOptions
        {
            Arguments = "-XX:+UseZGCx -Xss2m -XX:MaxGCPauseMillis=37 -XX:+UnlockExperimentalVMOptions "
                        + "-Djava.library.path=/Users/Player/Library/Application Support/natives/very/deep/folder/for/testing "
                        + "-XX:G1NewSizePercent=20 -XX:G1ReservePercent=20",
        });
        page.TourReloadJvm();
        RenderShots.Snap(window, "14lc-settings-jvm-undo-long");
        SettingsStore.Update(s => s.JvmArgsBeforeReset.Remove(version));
        if (added) SettingsStore.Update(s => s.JvmArgsByVersion.Remove(version));
        page.TourReloadJvm();
    }

    private static void Inputs(MainWindow window, SettingsPage page)
    {
        page.TourClickRail("SecGame");
        page.InstallPathTextBox.Focus(NavigationMethod.Tab);
        RenderShots.Snap(window, "14n-input-focus");
        window.Focus();
        page.ResolutionWidthTextBox.IsEnabled = false;
        RenderShots.Snap(window, "14o-input-disabled");
        page.ResolutionWidthTextBox.IsEnabled = true;
        page.TourShowFieldErrors();
        RenderShots.Snap(window, "14p-input-error");
        page.TourClearFieldErrors();
    }

    private static void Dropdown(MainWindow window, SettingsPage page)
    {
        var combo = page.JvmVersionComboBox;
        page.TourClickRail("SecJvm");
        var before = combo.ItemsSource;
        var added = combo.ItemCount == 0;
        if (added) combo.ItemsSource = new[] { "1.21.1", "1.20.4", "1.19.4" };
        try
        {
            combo.IsDropDownOpen = true;
            RenderShots.Pump();
            RenderShots.Snap(window, "14q-dropdown-open");
            if (PopupTop(combo) is { } popup && !ReferenceEquals(popup, window)) RenderShots.Snap(popup, "14q-dropdown-open-popup");
            else SnapListCrop(window, combo, "14q-dropdown-open-popup");
        }
        finally
        {
            combo.IsDropDownOpen = false;
            if (added) combo.ItemsSource = before;
            RenderShots.Pump();
        }
    }

    private static void Confirms(MainWindow window)
    {
        var confirm = window.Confirm;
        _ = confirm.ShowAsync("Remove friend?", "Steve will no longer see when you are online.", "Remove", "Cancel", destructive: true);
        RenderShots.Snap(window, "14r-confirm");
        confirm.Dismiss();
        _ = confirm.ShowAsync("Switch to a version that is not installed on this computer yet?",
            "Origin will download the game files for Minecraft 1.21.1, then restart the launch. This can take a few minutes on a slow connection, and you can keep using the launcher while it runs.",
            "Download and switch", "Not now");
        RenderShots.Snap(window, "14s-confirm-long");
        confirm.Dismiss();
    }

    private static void Toasts(MainWindow window)
    {
        window.TourClearToasts();
        try
        {
            window.TourShowToast(new SocialToast(SocialToastKind.FriendRequest, "Friend request", "OjoTheCat wants to be friends", Uuid: SocialDemo.Ojo));
            window.TourShowToast(new SocialToast(SocialToastKind.Notice, "Could not join Kai_Builds",
                "That party is on a Minecraft version you do not have installed, so Origin cannot start it from here until you install it."));
            RenderShots.Snap(window, "14t-toasts");
            window.TourShowToast(new SocialToast(SocialToastKind.PartyInvite, "Party invite", "Kai_Builds invited you", Uuid: SocialDemo.Kai, PartyId: "p2"));
            RenderShots.Snap(window, "14u-toasts-more");
        }
        finally
        {
            window.TourClearToasts();
        }
    }

    // ---- 40-44: the launcher's own crash dialog, then the game crash report once per confidence level ----
    private static void CrashWindows()
    {
        var error = new CrashWindow(
            "Origin Launcher - crash report\nVersion : 1.0.0\nType    : fatal\n\n----- error -----\nSystem.InvalidOperationException: Example failure\n   at Origin.Example.Run()",
            "/Users/Player/Library/Application Support/OriginLauncher/logs/crash-example.txt", fatal: true);
        error.Show();
        RenderShots.Snap(error, "40-crash-window");
        error.Close();

        CrashVerdict Diagnose(string? log, string? crash, string? hsErr, bool whileStarting) =>
            CrashEngine.Diagnose(new CrashEvidence(1, false, whileStarting, log, null, crash, hsErr,
            [
                new CrashModEntry("examplemod", "examplemod-1.2.3.jar", true, ["com.example.examplemod"]),
                new CrashModEntry("originclient", "originclient.jar", false),
            ], 4096, 16384));
        void Shot(CrashVerdict verdict, string name, bool unfold = false)
        {
            var w = new CrashReportWindow(verdict, null, "OriginPlayer - Minecraft 1.21.1", () => "report");
            w.Show();
            RenderShots.Snap(w, name);
            if (unfold)
            {
                w.ToggleDetails();
                RenderShots.Snap(w, name + "-details");
            }
            w.Close();
        }

        Shot(Diagnose("[Render thread/ERROR]: java.lang.OutOfMemoryError: Java heap space", null, null, false), "41-crash-confirmed");
        Shot(CrashEngine.Diagnose(new CrashEvidence(1, false, true,
                "Unrecognized VM option 'UseZGCx'\nError: Could not create the Java Virtual Machine.\nError: A fatal exception has occurred. Program will exit.",
                null, null, null, [], 4096, 16384) { CustomJvmArgs = "-XX:+UseZGCx -Xss2m" }),
            "41b-crash-java-args");
        Shot(Diagnose(
            "[main/ERROR]: Mixin apply for mod examplemod failed examplemod.mixins.json:ExampleMixin from mod examplemod -> net.minecraft.class_310: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: Critical injection failure",
            null, null, true), "42-crash-strong", unfold: true);
        Shot(Diagnose(null, null,
            // A Mac hs_err: a signal, not a Windows exception, inside Apple's OpenGL-over-Metal driver.
            "# A fatal error has been detected by the Java Runtime Environment:\n#\n#  SIGSEGV (0xb) at pc=0x000000019a1f2c40, pid=4312, tid=259\n#\n# Problematic frame:\n# C  [AppleMetalOpenGLRenderer+0x2f1a4]\n",
            false), "43-crash-possible");
        Shot(Diagnose(null,
            "---- Minecraft Crash Report ----\nDescription: Unexpected error\n\njava.lang.IllegalStateException: example\n\tat net.minecraft.client.Minecraft.run(Minecraft.java:1)\n",
            null, false), "44-crash-unknown");
    }

    // ---- 00: the opening frame at its end state (a shots run has motion off, which skips the intro) ----
    private static void StartupIntroShot()
    {
        var intro = new StartupIntro();
        intro.Mark.Opacity = 1;
        intro.MarkScale.ScaleX = intro.MarkScale.ScaleY = 1;
        var host = new Window { Width = 1180, Height = 740, Content = intro };
        host.Show();
        RenderShots.Snap(host, "00-startup-intro");
        host.Close();
    }

    // ---- helpers ----
    private static TopLevel? PopupTop(Control owner) =>
        owner.GetVisualDescendants().OfType<Popup>().FirstOrDefault() is { Child: { } child } ? TopLevel.GetTopLevel(child) : null;

    // The window's picture with the open dropdown's own picture drawn where it opens (under the box), as the WPF
    // shot does: a popup is its own top level, which the window's frame does not contain.
    private static void SnapWithPopup(Window window, ComboBox combo, string name)
    {
        var popupTop = PopupTop(combo);
        if (popupTop == null || ReferenceEquals(popupTop, window))
        {
            RenderShots.Snap(window, name);
            return;
        }
        RenderShots.Pump();
        using var back = window.CaptureRenderedFrame();
        using var list = popupTop.CaptureRenderedFrame();
        var at = combo.TranslatePoint(new Point(0, combo.Bounds.Height), window);
        if (back == null || list == null || at == null)
        {
            RenderShots.Fail(name + " (no frame)");
            return;
        }
        var popup = combo.GetVisualDescendants().OfType<Popup>().First();
        using var target = new RenderTargetBitmap(back.PixelSize, back.Dpi);
        using (var dc = target.CreateDrawingContext())
        {
            dc.DrawImage(back, new Rect(0, 0, back.Size.Width, back.Size.Height));
            dc.DrawImage(list, new Rect(at.Value.X + popup.HorizontalOffset, at.Value.Y + popup.VerticalOffset,
                list.Size.Width, list.Size.Height));
        }
        target.Save(Path.Combine(_dir, name + ".png"));
        RenderShots.Count();
    }

    // The Mac draws the list in the window's own overlay layer, so the WPF "-popup" picture (the list on its own, its
    // shadow around it, over the window colour) is cut from the window's frame: the list's bounds plus 24 px each side.
    private static void SnapListCrop(Window window, ComboBox combo, string name)
    {
        var child = combo.GetVisualDescendants().OfType<Popup>().FirstOrDefault()?.Child;
        RenderShots.Pump();
        using var back = window.CaptureRenderedFrame();
        if (child == null || back == null || child.TranslatePoint(default, window) is not { } at)
        {
            RenderShots.Fail(name + " (the list is not open)");
            return;
        }
        const double pad = 24;
        var src = new Rect(at.X - pad, at.Y - pad, child.Bounds.Width + 2 * pad, child.Bounds.Height + 2 * pad)
            .Intersect(new Rect(back.Size));
        using var target = new RenderTargetBitmap(new PixelSize((int)Math.Ceiling(src.Width), (int)Math.Ceiling(src.Height)));
        using (var dc = target.CreateDrawingContext())
            dc.DrawImage(back, src, new Rect(src.Size));
        target.Save(Path.Combine(_dir, name + ".png"));
        RenderShots.Count();
    }

    private static void Step(string what, Action body)
    {
        try
        {
            body();
        }
        catch (Exception ex)
        {
            File.AppendAllText(Path.Combine(_dir, "area-a-error.txt"), $"{what}: {ex}{Environment.NewLine}");
            RenderShots.Fail($"{what} ({ex.GetType().Name}: {ex.Message})");
        }
    }
}
