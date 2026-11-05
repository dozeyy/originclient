using Avalonia.Controls.Primitives;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Overlays;
using OriginLauncher.Desktop.Pages;
using OriginLauncher.Desktop.Shell;

namespace OriginLauncher.Desktop.Shots;

// Settings, its states, the confirm dialog, toasts and the startup intro, named like the Windows set
// (UI/RenderShots.cs + UI/SettingsStatesShots.cs) so the two sheets line up. Windows takes these one after another in
// one window; here every shot is a fresh window, so each one repeats the steps that led to it there.
public static class SettingsShots
{
    private const string LongArgs = "-XX:+UseZGCx -Xss2m -XX:MaxGCPauseMillis=37 -XX:+UnlockExperimentalVMOptions "
        + "-Djava.library.path=C:\\Users\\Player\\AppData\\Roaming\\natives\\very\\deep\\folder\\for\\testing "
        + "-XX:G1NewSizePercent=20 -XX:G1ReservePercent=20";

    private static SettingsPage Open(MainWindow w)
    {
        w.Navigate(PageKey.Settings);
        ShotRunner.Pump();
        return w.PageOf<SettingsPage>(PageKey.Settings);
    }

    private static Action<MainWindow> At(Action<SettingsPage> step) => w =>
    {
        var page = Open(w);
        step(page);
        ShotRunner.Pump();
    };

    private static void Rail(SettingsPage p, string section)
    {
        p.ClickRail(section);
        ShotRunner.Pump();
    }

    // The crash window's reset of this version's Java arguments, through the real repair (as Windows seeds it).
    private static void SeedJvmReset()
    {
        var version = JvmShown();
        SettingsStore.Update(s => s.JvmArgsByVersion[version] = new VersionJvmOptions { Arguments = "-XX:+UseZGCx -Xss2m" });
        RepairExecutor.ApplySettings(RepairPlanner.ResetJavaArgs(version,
            SettingsStore.Load().JvmArgsFor(version), "Java refused to start the game with them."), SettingsStore.Update);
    }

    // The version the JVM editor opens on (SettingsPage: the selected one when supported, else the first).
    private static string JvmShown()
    {
        var selected = SettingsStore.Load().SelectedVersion;
        return VersionCatalog.IsSupported(selected) ? selected! : VersionCatalog.SupportedVersions.First();
    }

    // Demo.Reset leaves the per-version Java arguments alone, so the undo row seeded for 14lb/14lc would show on every
    // later shot; every shot here starts from no Java arguments (as the Windows set does once it clears them).
    private static Shot Make(string name, Action<MainWindow> setup, double width = 1180, double height = 740, Action? Seed = null) =>
        new(name, setup, width, height, () =>
        {
            SettingsStore.Update(s => { s.JvmArgsByVersion.Clear(); s.JvmArgsBeforeReset.Clear(); });
            Seed?.Invoke();
        });

    [ShotSet]
    public static IEnumerable<Shot> All()
    {
        yield return Make("00-startup-intro", StartupIntro.ShowStill);

        // The full window, then the smallest one (960 x 600): top, middle, bottom, and the Settings Sync row.
        foreach (var (n, name, w, h) in new[] { ("14", "settings", 1180.0, 740.0), ("24", "min-settings", 960.0, 600.0) })
        {
            yield return Make($"{n}-{name}", At(_ => { }), w, h);
            yield return Make($"{n}b-{name}-middle", At(p => p.ScrollToFraction(0.5)), w, h);
            yield return Make($"{n}c-{name}-bottom", At(p => p.ScrollToFraction(1)), w, h);
            yield return Make($"{n}d-{name}-sync", At(p => p.ScrollToSync()), w, h);
        }

        yield return Make("14g-settings-click-startup", At(p => Rail(p, "SecStartup")));
        yield return Make("14h-settings-key-end", At(p => { Rail(p, "SecStartup"); p.PressEnd(); }));
        yield return Make("14i-settings-sec-sync", At(p => Rail(p, "SecSync")));
        yield return Make("14j-settings-sec-performance", At(p => Rail(p, "SecPerformance")));
        yield return Make("14k-settings-sec-game", At(p => Rail(p, "SecGame")));
        yield return Make("14l-settings-sec-jvm", At(p => Rail(p, "SecJvm")));
        // Release builds have no Developer section, so on Windows this shot keeps the view before it (JVM).
        yield return Make("14m-settings-sec-developer", At(p => { if (!p.ClickRail("SecDeveloper")) Rail(p, "SecJvm"); }));

        yield return Make("14lb-settings-jvm-undo", At(p => Rail(p, "SecJvm")), Seed: SeedJvmReset);
        yield return Make("14lc-settings-jvm-undo-long", At(p => Rail(p, "SecJvm")), Seed: () =>
        {
            SeedJvmReset();
            var version = JvmShown();
            SettingsStore.Update(s => s.JvmArgsBeforeReset[version] = new VersionJvmOptions { Arguments = LongArgs });
        });

        yield return Make("14n-input-focus", At(p =>
        {
            Rail(p, "SecGame");
            p.WidthBox.Focus(Avalonia.Input.NavigationMethod.Tab);
            p.WidthBox.CaretIndex = p.WidthBox.Text?.Length ?? 0;
        }));
        yield return Make("14o-input-disabled", At(p => { Rail(p, "SecGame"); p.HeightBox.IsEnabled = false; }));
        yield return Make("14p-input-error", At(p => { Rail(p, "SecGame"); p.ShowFieldErrors(); }));
        yield return Make("14q-dropdown-open", At(p =>
        {
            Rail(p, "SecJvm");
            // The menu is a popup; drawn in the window's overlay layer it is part of the captured frame.
            foreach (var popup in p.JvmVersions.Children.OfType<Popup>()) popup.ShouldUseOverlayLayer = true;
            p.JvmVersions.SetOpen(true);
        }));

        yield return Make("14r-confirm", w =>
        {
            Rail(Open(w), "SecJvm");
            _ = Confirm.AskAsync(w, "Remove friend?", "Steve will no longer see when you are online.", "Remove", "Cancel", danger: true);
        });
        yield return Make("14s-confirm-long", w =>
        {
            Rail(Open(w), "SecJvm");
            _ = Confirm.AskAsync(w, "Switch to a version that is not installed on this computer yet?",
                "Origin will download the game files for Minecraft 1.21.1, then restart the launch. This can take a few minutes on a slow connection, and you can keep using the launcher while it runs.",
                "Download and switch", "Not now");
        });
        yield return Make("14t-toasts", w =>
        {
            Rail(Open(w), "SecJvm");
            TwoToasts(w);
        });
        yield return Make("14u-toasts-more", w =>
        {
            Rail(Open(w), "SecJvm");
            TwoToasts(w);
            w.Toast("Kai_Builds invited you", Tone.Info, "Join", sticky: true);
        });

        yield return Make("dpi-settings-1180x740-100pct", At(_ => { }));
    }

    private static void TwoToasts(MainWindow w)
    {
        w.ClearToasts();
        w.Toast("OjoTheCat wants to be friends", Tone.Info, "Accept", sticky: true);
        w.Toast("Could not join Kai_Builds: That party is on a Minecraft version you do not have installed, so Origin cannot start it from here until you install it.",
            Tone.Warning, sticky: true);
    }
}
