using System.Diagnostics;
using Avalonia;
using Avalonia.Headless;
using Avalonia.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.Mac.UI;

namespace OriginLauncher.Mac;

/// <summary>
/// `--play-plan &lt;version&gt; [--root &lt;dir&gt;]`: the Home screen's Play button, end to end, short of starting the game.
/// Unlike `--launch-plan` (which calls the Core launch path directly) this builds the real launcher window headless,
/// in a scratch data folder, picks the version the way the picker saves it, and presses Play exactly as a click does:
/// the update gate, the account/offline session, the heap plan, the install and the process build all run through
/// HomePage.LaunchAsync. HomePage hands the built process to the probe instead of starting it (honoured only in a
/// scratch root), then LaunchPlan.Inspect checks it. Prints every launch-screen stage and the final status line.
/// Exit 0 = Play reached "Starting Minecraft" and every check passed, 1 = it failed, 3 = needs Rosetta, none here.
/// No window opens and Minecraft never starts.
/// </summary>
internal static class PlayPlan
{
    public static int Run(string version, string? root)
    {
        if (!VersionCatalog.IsSupported(version))
        {
            Console.Error.WriteLine($"{version} is not a version the picker offers (see --list-versions).");
            return 2;
        }

        // Never the player's data: settings, accounts, instances all live under the scratch root.
        root = Path.GetFullPath(root ?? Path.Combine(Path.GetTempPath(), "origin-play-plan"));
        OriginPaths.UseScratchRoot(root);
        // Offline session (no account in a scratch folder) and the version picked, both written before Home loads them.
        SettingsStore.Update(s =>
        {
            s.OfflineTestMode = true;
            s.SelectedVersion = version;
        });

        Motion.Off = true;
        AppBuilder.Configure<App>()
            .UseSkia()
            .UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false })
            .SetupWithoutStarting();
        // Progress<T> and every await in LaunchAsync come back to the UI thread through this context.
        AvaloniaSynchronizationContext.InstallIfNeeded();

        Console.WriteLine($"Play plan: Minecraft {version} through the Home Play button, machine {PlatformLaunch.HostArch}");
        var window = new MainWindow { Width = 1044, Height = 740 };
        window.Show();
        var home = window.Home;

        Process? built = null;
        home.PlayPlanProbe = p => built = p;
        home.StageReported += stage => Console.WriteLine("  stage            " + stage);

        var watch = Stopwatch.StartNew();
        var press = home.PressPlayForPlan();
        // The launch runs its file work on worker threads; this thread only pumps the UI queue until Play settles.
        while (!press.IsCompleted)
        {
            Dispatcher.UIThread.RunJobs();
            Thread.Sleep(15);
        }
        Dispatcher.UIThread.RunJobs();
        if (press.Exception is { } thrown)
        {
            Console.WriteLine("FAIL  Play threw: " + thrown.GetBaseException());
            return 1;
        }

        Console.WriteLine($"  status line      {home.StatusLine}");
        Console.WriteLine($"  pressed to built {watch.Elapsed.TotalSeconds:0.0} s");
        if (built == null)
        {
            if (home.StatusLine.Contains("needs Rosetta", StringComparison.Ordinal))
            {
                Console.WriteLine($"ROSETTA {version}: Play stopped at the Rosetta offer, as it should on a Mac without Rosetta");
                return 3;
            }
            Console.WriteLine($"FAIL  {version}: Play never reached the game start");
            return 1;
        }
        return LaunchPlan.Inspect(built, version, root);
    }
}
