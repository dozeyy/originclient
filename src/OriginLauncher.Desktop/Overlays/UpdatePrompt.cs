using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Threading;
using OriginLauncher.App.Core.Updates;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// The launcher's own update (port of WPF MainWindow: PollForUpdatesAsync + UpdateBadge_Click). Core does the work
// (Core/Updates/UpdateService + the per-OS apply step, UpdateService.Linux.cs / .Mac.cs); this file only checks,
// shows the top bar's Update chip (LauncherState.SetUpdate) and the progress: on Home's full-window launch scene in
// update mode (LaunchCover.ShowUpdate, under the bar, which stays usable), like Windows and the Mac; when a launch is
// already on that scene, the chip's own "Updating..." carries it. A failure is said on the chip ("Update failed -
// retry", in the warning colour), and the click that retries keeps an "Update and play" going: the refused Play
// still starts after the restart.
//
// Rules it keeps:
// - Updates are mandatory: the chip never hides while one is waiting, and a click downloads it at once (Windows does
//   the same, no "later" step). The Play gate itself is UpdateService.UpdateRequired, read by the launch path.
// - A self-update whose SHA-256 is missing or wrong is refused by Core (UpdateVerificationException) and never blocks
//   Play: the chip goes away, nothing blocking is shown, and a quiet retry offers it again only once it verifies.
// - No network in a render run (ShotRunner.Active): StartChecks does nothing there, Open never downloads.
public static class UpdatePrompt
{
    private static readonly TimeSpan PollEvery = TimeSpan.FromMinutes(2);
    private static readonly TimeSpan ActivateThrottle = TimeSpan.FromSeconds(30);
    private static bool _started, _busy;
    private static DateTime _lastCheck = DateTime.MinValue;
    // The refused Play an "Update and play" carries, kept across a failed attempt so the chip's retry still plays it.
    private static PlayAfterUpdate? _pendingPlay;

    /// <summary>Starts the update check once the window is up (real runs only): now, every 2 minutes, and when the
    /// window comes back to the front (at most every 30 s).</summary>
    public static void StartChecks(MainWindow window)
    {
        if (Shots.ShotRunner.Active || _started) return;
        _started = true;
        // AvailableChanged may fire on a worker thread.
        UpdateService.AvailableChanged += (_, _) => Dispatcher.UIThread.Post(ShowChip);
        window.Activated += (_, _) =>
        {
            if (DateTime.UtcNow - _lastCheck >= ActivateThrottle) _ = CheckOnceAsync();
        };
        _ = PollAsync();
    }

    // The loop lives for the app's lifetime; process exit ends it.
    private static async Task PollAsync()
    {
        while (true)
        {
            await CheckOnceAsync();
            await Task.Delay(PollEvery);
        }
    }

    private static async Task CheckOnceAsync()
    {
        _lastCheck = DateTime.UtcNow;
        try { await UpdateService.CheckAsync(); } // never throws by contract; fails open
        catch (Exception) { /* a check that breaks anyway must never take the launcher down */ }
        Dispatcher.UIThread.Post(ShowChip);
    }

    /// <summary>The chip shows exactly what Core has waiting (left alone while a download is running).</summary>
    private static void ShowChip()
    {
        if (_busy) return;
        LauncherState.SetUpdate(UpdateService.Available is { } u ? Short(u.Version) : null);
    }

    private static string Short(Version v) => v.Build >= 0 ? v.ToString(3) : v.ToString();

    /// <summary>The chip was clicked: download, verify, install and restart, with the progress in a dialog.
    /// <paramref name="playAfter"/>: the refused Play "Update and play" continues after the restart (null for the chip).</summary>
    public static void Open(MainWindow window, PlayAfterUpdate? playAfter = null)
    {
        if (_busy) return;
        if (Shots.ShotRunner.Active || UpdateService.Available is not { } update)
        {
            // Nothing (verified) is waiting any more: say so and let the chip follow Core.
            ShowChip();
            window.Toast("Origin is up to date", Tone.Success);
            return;
        }
        if (playAfter != null) _pendingPlay = playAfter;
        _ = RunAsync(window, update, _pendingPlay);
    }

    /// <summary>Play was refused for a mandatory update: ask once (the WPF MainWindow's AskToUpdateAsync), and Update
    /// runs the chip's own install. Not while that install is running or when nothing is on offer any more.</summary>
    public static async Task AskAsync(MainWindow window, PlayAfterUpdate play)
    {
        if (_busy || Shots.ShotRunner.Active || UpdateService.Available == null) return;
        if (await Confirm.AskAsync(window, UpdateRequiredWords.PromptTitle, UpdateRequiredWords.PromptText,
                UpdateRequiredWords.UpdateButton, UpdateRequiredWords.LaterButton)
            && UpdateService.Available != null)
            Open(window, play);
    }

    private static async Task RunAsync(MainWindow window, UpdateService.AvailableUpdate update, PlayAfterUpdate? playAfter)
    {
        _busy = true;
        // An import running now (or started while this downloads) is waited for before the restart (Core's UpdateRunState).
        window.Bar.SetUpdateEnabled(false);
        window.Bar.SetUpdateNote(null);
        // Home's full-window scene (a real percentage fill), unless a game launch is mid-flight there: then the chip's
        // own "Updating..." carries it.
        window.CloseDrawer();
        window.Navigate(PageKey.Home);
        var scene = BeginScene(window, Short(update.Version));
        if (scene == null) window.Bar.SetUpdateNote("Updating...");

        // Cancel on the scene's "waiting for Minecraft to close" (that can last hours: never a screen the player can't
        // leave) stops the wait; the update stays downloaded and ready for the next click.
        var stopWaiting = new CancellationTokenSource(); // no timer: nothing to dispose, and a late click stays harmless
        if (scene != null) scene.UpdateWaitCancelRequested += () => stopWaiting.Cancel();
        var toldAboutImport = false;
        var progress = new Progress<UpdateService.UpdateProgress>(p =>
        {
            if (p.Stage == UpdateService.UpdateStage.FinishingImport && !toldAboutImport)
            {
                toldAboutImport = true;
                window.Toast("Finishing your import first...");
            }
            if (scene != null) scene.ReportUpdate(p);
            else if (p.Stage == UpdateService.UpdateStage.WaitingForGame) window.Bar.SetUpdateNote("Updates when Minecraft closes");
        });

        try
        {
            // Downloads, verifies (SHA-256), stages the new build, then shuts this process down; the apply script
            // swaps the files and opens the updated launcher.
            await UpdateService.DownloadAndRestartAsync(progress, stopWaiting.Token, playAfter);
            EndScene(window, scene); // only reached if the process was not shut down (nothing to restart into)
            window.Bar.SetUpdateNote(null);
        }
        catch (OperationCanceledException) when (stopWaiting.IsCancellationRequested)
        {
            // Back to Home: the chip offers the same, already downloaded update again; Play stays as it was.
            EndScene(window, scene);
            window.Bar.SetUpdateNote(null);
        }
        catch (Exception ex) when (ex is UpdateVerificationException || UpdateService.Available == null)
        {
            // Failed its integrity check, or failed twice in a row: Core discarded it and released the Play gate.
            // The player launches what they have; nothing blocking is shown, the chip goes away.
            EndScene(window, scene);
            window.Bar.SetUpdateNote(null);
            _pendingPlay = null;
        }
        catch (Exception)
        {
            // Failed once (network, disk, antivirus): Play stays gated until the next failure. Visible, not a hover
            // label: the chip says it failed and that a click retries (with the same "Update and play", if any).
            EndScene(window, scene);
            window.Bar.SetUpdateNote("Update failed - retry", Palette.Warning);
        }
        finally
        {
            stopWaiting.Dispose();
            window.Bar.SetUpdateEnabled(true);
            _busy = false;
        }
        ShowChip();
    }

    /// <summary>The update scene on Home, or null when the launch screen is already up for a game launch.</summary>
    private static LaunchCover? BeginScene(MainWindow window, string newVersion)
    {
        if (window.CoverOpen) return null;
        var scene = new LaunchCover();
        window.OpenCover(scene, underBar: true);
        scene.ShowUpdate(newVersion);
        return scene;
    }

    private static void EndScene(MainWindow window, LaunchCover? scene)
    {
        if (scene != null && window.CoverIsUpdateScene) window.CloseCover();
    }

    /// <summary>Render runs: the update scene, optionally at "waiting for Minecraft to close".</summary>
    public static LaunchCover TourScene(MainWindow window, string newVersion, bool waitingForGame)
    {
        var scene = BeginScene(window, newVersion)!;
        if (waitingForGame)
            scene.ReportUpdate(new UpdateService.UpdateProgress(UpdateService.UpdateStage.WaitingForGame, 0, null));
        else scene.ReportUpdate(new UpdateService.UpdateProgress(UpdateService.UpdateStage.Downloading, 24_600_000, 41_000_000));
        return scene;
    }
}
