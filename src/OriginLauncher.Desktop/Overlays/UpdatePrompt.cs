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
// shows the top bar's Update chip (LauncherState.SetUpdate) and the progress.
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
    private static MainWindow? _window;
    private static Version? _toldBlocked;
    private static DateTime _lastCheck = DateTime.MinValue;

    /// <summary>Starts the update check once the window is up (real runs only): now, every 2 minutes, and when the
    /// window comes back to the front (at most every 30 s).</summary>
    public static void StartChecks(MainWindow window)
    {
        if (Shots.ShotRunner.Active || _started) return;
        _started = true;
        _window = window;
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
        // A newer release this copy can't install where it sits (UpdateService.Blocked: a read-only AppImage folder, a
        // root-owned tar.gz folder): it is never offered and never gates Play, so say once per release how to get it
        // (the twin of the Mac's "Move to Applications" chip). Without this the copy stayed old without a word.
        if (UpdateService.Blocked is { } blocked && blocked.Version != _toldBlocked && _window != null)
        {
            _toldBlocked = blocked.Version;
            _window.Toast(blocked.Reason, Tone.Warning, sticky: true);
        }
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
        _ = RunAsync(window, update, playAfter);
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

    private static async Task RunAsync(MainWindow window, UpdateService.AvailableUpdate update, PlayAfterUpdate? playAfter = null)
    {
        _busy = true;
        var status = Ui.Caption("Starting the download");
        var rail = new Rail { Value = double.NaN, Margin = new Thickness(0, Space.S4, 0, Space.S2) };
        // Only while the swap waits for Minecraft to close (that can last hours): never a dialog the player can't leave.
        // Cancel stops the wait; the update stays downloaded and ready for the next click.
        var stopWaiting = new CancellationTokenSource(); // no timer: nothing to dispose, and a late click stays harmless
        var cancel = Ui.Button("Cancel", Look.Secondary);
        cancel.MinWidth = 96;
        cancel.IsVisible = false;
        cancel.HorizontalAlignment = HorizontalAlignment.Right;
        cancel.Margin = new Thickness(0, Space.S4, 0, 0);
        cancel.Click += (_, _) =>
        {
            cancel.IsEnabled = false;
            stopWaiting.Cancel();
        };
        var body = Ui.Column(0,
            Ui.Wrap(Ui.Title("Updating Origin to " + Short(update.Version))),
            Ui.Body("Origin closes, installs the update and opens again by itself.").Margin(0, 10, 0, 0),
            rail,
            status,
            cancel);
        window.OpenDialog(body, dismissable: false);

        var toldAboutImport = false;
        var progress = new Progress<UpdateService.UpdateProgress>(p =>
        {
            status.Text = p.Describe();
            rail.Value = p.Fraction ?? double.NaN;
            cancel.IsVisible = p.Stage == UpdateService.UpdateStage.WaitingForGame;
            if (p.Stage == UpdateService.UpdateStage.FinishingImport && !toldAboutImport)
            {
                toldAboutImport = true;
                window.Toast("Finishing your import first...");
            }
        });

        try
        {
            // Downloads, verifies (SHA-256), stages the new build, then shuts this process down; the apply script
            // swaps the files and opens the updated launcher.
            await UpdateService.DownloadAndRestartAsync(progress, stopWaiting.Token, playAfter);
            window.CloseDialog(); // only reached if the process was not shut down (nothing to restart into)
        }
        catch (OperationCanceledException) when (stopWaiting.IsCancellationRequested)
        {
            // Back to Home: the chip offers the same, already downloaded update again; Play stays as it was.
            window.CloseDialog();
            _busy = false;
            ShowChip();
            return;
        }
        catch (Exception ex) when (ex is UpdateVerificationException || UpdateService.Available == null)
        {
            // Failed its integrity check, or failed twice in a row: Core discarded it and released the Play gate.
            // The player launches what they have; nothing blocking is shown, the chip goes away.
            window.CloseDialog();
            _busy = false;
            ShowChip();
            return;
        }
        catch (Exception)
        {
            // Failed once (network, disk, antivirus): Play stays gated until the next failure, so offer the retry.
            _busy = false;
            ShowFailed(window, update);
            return;
        }
        _busy = false;
        ShowChip();
    }

    private static void ShowFailed(MainWindow window, UpdateService.AvailableUpdate update)
    {
        var close = Ui.Button("Close", Look.Secondary);
        close.MinWidth = 96;
        var retry = Ui.Button("Retry", Look.Primary);
        retry.MinWidth = 96;
        var footer = Ui.Row(Space.S2, close, retry);
        footer.HorizontalAlignment = HorizontalAlignment.Right;
        footer.Margin = new Thickness(0, Space.S6, 0, 0);
        close.Click += (_, _) => window.CloseDialog();
        retry.Click += (_, _) =>
        {
            window.CloseDialog();
            if (UpdateService.Available is { } again) _ = RunAsync(window, again);
            else ShowChip();
        };
        window.OpenDialog(Ui.Column(0,
            Ui.Wrap(Ui.Title("Update failed")),
            Ui.Body("Origin couldn't download " + Short(update.Version) + ". Check your connection and try again.").Margin(0, 10, 0, 0),
            footer), dismissable: true);
        Dispatcher.UIThread.Post(() => retry.Focus(), DispatcherPriority.Input);
    }
}
