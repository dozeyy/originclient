using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Updates;
using OriginLauncher.Mac.UI;

namespace OriginLauncher.Mac;

// The launcher's self-update in the top bar: the Mac twin of the WPF MainWindow's UpdateBadge (MainWindow.xaml.cs), with
// the same timing and the same states. Every decision is UpdateService's (Core/Updates): this file only shows it.
//   timing   a check at start and every 2 minutes for the app's lifetime, plus one when the window regains focus
//            (at most every 30 s); Home's Play reads the last answer and never waits on the network.
//   ready    "Update" in gold (1100 and wider) or the compact glyph with a gold dot (below 1100). A click downloads,
//            verifies, unpacks and swaps the app (UpdateService.DownloadAndRestartAsync) on Home's loading scene,
//            then the app quits and the swap script reopens it.
//   failed   "Update failed - retry" on the chip; a second failure in a row (or a failed hash at once) releases Play
//            and hides the chip until a quiet retry verifies the release (UpdateGate).
//   blocked  "Move to Applications": this copy runs translocated or from a folder it can't write to, so the update is
//            never offered and Play is never gated (UpdateService.Blocked); a click says how to fix it.
// The headless runs (render shots, play plan) never poll: they run on a scratch root and never go online.
public sealed partial class MainWindow
{
    // DESIGN-SYSTEM 11.4: 1100 and wider the chip is the gold wash with "Update"; below 1100 it is an icon button 32.
    private const double UpdateChipMinWindow = 1100;
    private static readonly TimeSpan PollInterval = TimeSpan.FromMinutes(2);
    private static readonly TimeSpan FocusCheckThrottle = TimeSpan.FromSeconds(30);

    private bool _updateCompact;
    private DateTime _lastFocusCheck;

    private const string ReadyName = "Update ready: restart to install";
    private const string BlockedNote = "Move to Applications";

    private void HookUpdates()
    {
        UpdateBadge.Click += UpdateBadge_Click;
        Home.UpdateRequiredRefused += play => _ = AskToUpdateAsync(play);
        // A click whose setting couldn't be saved (settings.json busy): one plain toast, never a crash (WPF twin).
        SettingsStore.SaveRefused += () => Dispatcher.UIThread.Post(() =>
            Toasts.Show(new SocialToast(SocialToastKind.Notice, "Settings", SettingsStore.BusyLine)));
        // Raised from whichever thread finished the check.
        UpdateService.AvailableChanged += (_, _) => Dispatcher.UIThread.Post(ShowUpdateState);
        if (OriginPaths.IsScratchRoot) return;
        // The Intel build under Rosetta on an Apple Silicon Mac: once per start, offer the Apple Silicon download (its
        // sign-in web view crashes under Rosetta). The next self-update installs the Apple Silicon app by itself
        // (MacReleaseAssets.ZipPrefixForUpdate); this covers the time until a newer release exists.
        Opened += (_, _) => _ = OfferNativeBuildAsync();

        // "Update and play" (JL-5): the Play the required update refused starts once the window is up, no second click.
        Opened += (_, _) =>
        {
            if (UpdateService.TakePlayAfterUpdate() is { } play) _ = Home.ResumeAfterUpdateAsync(play);
        };
        _ = PollForUpdatesAsync();
        // Re-check the instant the launcher regains focus, so a release just published is caught at once instead of on
        // the next poll tick (throttled).
        Activated += async (_, _) =>
        {
            var now = DateTime.UtcNow;
            if (now - _lastFocusCheck < FocusCheckThrottle) return;
            _lastFocusCheck = now;
            await UpdateService.CheckAsync();
        };
    }

    private async Task OfferNativeBuildAsync()
    {
        var notice = MacReleaseAssets.NativeBuildNotice(System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture,
            OriginLauncher.App.Core.Launch.PlatformLaunch.HostArch);
        if (notice is not { } n) return;
        if (await Confirm.ShowAsync(n.Title, n.Message, n.Download, n.Later))
            OriginLauncher.App.Core.Promos.ExternalLink.Open(MacReleaseAssets.AppleSiliconDownload);
    }

    // Lives for the app's lifetime; quitting ends it. CheckAsync never throws (an unreachable feed fails open).
    private static async Task PollForUpdatesAsync()
    {
        while (true)
        {
            await UpdateService.CheckAsync();
            await Task.Delay(PollInterval);
        }
    }

    // What UpdateService now says: an update to offer, or one this copy can't install where it sits.
    private void ShowUpdateState()
    {
        if (UpdateService.Available != null)
        {
            // An earlier note (a failure) stays until the next click, like the WPF badge.
            UpdateBadge.IsVisible = true;
            if (UpdateNote.Text == BlockedNote)
            {
                HideUpdateNote();
                AutomationProperties.SetName(UpdateBadge, ReadyName);
            }
        }
        else if (UpdateService.Blocked != null)
        {
            UpdateBadge.IsVisible = true;
            ShowUpdateNote(BlockedNote, "Brush.Warning");
            AutomationProperties.SetName(UpdateBadge, "Update can't install here: move Origin to Applications");
        }
        else if (UpdateNote.Text == BlockedNote)
        {
            // No longer blocked and nothing offered yet: the move-to-Applications chip goes away.
            HideUpdateNote();
            AutomationProperties.SetName(UpdateBadge, ReadyName);
            UpdateBadge.IsVisible = false;
        }
        Home.RefreshUpdateGate();
    }

    /// <summary>The one short line on the chip: "Updating..." off the loading scene, a failure, or "Move to Applications".</summary>
    private void ShowUpdateNote(string text, string brushKey)
    {
        UpdateNote.Text = text;
        UpdateNote.Foreground = (IBrush)this.FindResource(brushKey)!;
        UpdateNote.IsVisible = true;
        UpdateLabel.IsVisible = false; // the note takes the label's place, at every width
    }

    private void HideUpdateNote()
    {
        UpdateNote.Text = "";
        UpdateNote.IsVisible = false;
        UpdateLabel.IsVisible = !_updateCompact;
    }

    private void SetUpdateCompact(bool compact)
    {
        _updateCompact = compact;
        UpdateBadge.Theme = (Avalonia.Styling.ControlTheme)this.FindResource(compact ? "Button.Chrome" : "Button.UpdateChip")!;
        UpdateBadge.Padding = compact ? new Thickness(8) : new Thickness(10, 0, 10, 0);
        UpdateGlyph.Stroke = (IBrush)this.FindResource(compact ? "Brush.TextDim" : "Brush.Hue.Gold")!;
        UpdateDot.IsVisible = compact;
        if (!UpdateNote.IsVisible) UpdateLabel.IsVisible = !compact;
    }

    // The Play the prompt's "Update and play" continues after the restart; null for a plain chip click.
    private PlayAfterUpdate? _playAfterUpdate;

    // Play was refused for a mandatory update: ask once, and Update runs the chip's own install (the WPF MainWindow's
    // AskToUpdateAsync). Not while that install is already running (the chip is disabled then) or when nothing is on
    // offer any more.
    private async Task AskToUpdateAsync(PlayAfterUpdate play)
    {
        if (UpdateService.Available == null || !UpdateBadge.IsEnabled) return;
        if (await Confirm.ShowAsync(UpdateRequiredWords.PromptTitle, UpdateRequiredWords.PromptText,
                UpdateRequiredWords.UpdateButton, UpdateRequiredWords.LaterButton)
            && UpdateService.Available != null && UpdateBadge.IsEnabled)
        {
            _playAfterUpdate = play; // "Update and play": the next start launches it (JL-5)
            UpdateBadge_Click(UpdateBadge, new RoutedEventArgs());
        }
    }

    private async void UpdateBadge_Click(object? sender, RoutedEventArgs e)
    {
        if (UpdateService.Available is not { } update)
        {
            // Nothing to install from here: say what fixes it, in the player's words (UpdateService.EnsureCanReplace).
            if (UpdateService.Blocked is { } blocked)
                Toasts.Show(new SocialToast(SocialToastKind.Notice, "Move Origin to Applications", blocked.Reason));
            return;
        }
        // An import running now (or started while this downloads) is waited for before the restart, and no new import can
        // open or apply once the download is under way (Core/Import/Launcher/UpdateRunState).

        UpdateBadge.IsEnabled = false;
        HideUpdateNote();
        AutomationProperties.SetName(UpdateBadge, ReadyName);

        // The download on Home's full-page loading scene (a real percentage fill), unless a game launch is mid-flight
        // there; then the chip's own "Updating..." carries it.
        if (_accountPanelOpen) SetAccountPanelOpen(false);
        ShowPage(LauncherPage.Home);
        var onScene = _currentPage == LauncherPage.Home && Home.BeginUpdateScene(update.Version.ToString());
        if (!onScene) ShowUpdateNote("Updating...", "Brush.TextDim");
        var toldAboutImport = false;
        var progress = new Progress<UpdateService.UpdateProgress>(p =>
        {
            if (p.Stage == UpdateService.UpdateStage.FinishingImport && !toldAboutImport)
            {
                toldAboutImport = true;
                Toasts.Show(new SocialToast(SocialToastKind.Notice, "Update", "Finishing your import first..."));
            }
            if (onScene) Home.ReportUpdate(p);
            else if (p.Stage == UpdateService.UpdateStage.WaitingForGame)
                ShowUpdateNote("Updates when Minecraft closes", "Brush.TextDim");
        });

        // Cancel on the scene's "Waiting for Minecraft to close" stops the wait; the update stays ready (staged).
        var stopWaiting = new CancellationTokenSource();
        Action cancelWait = () => stopWaiting.Cancel();
        Home.UpdateWaitCancelRequested += cancelWait;
        // Taken here so only the click the prompt made carries it; the chip on its own never starts a game. A failed
        // download puts it back (below), so "Update failed - retry" still plays afterwards, as on Linux (PAR3-7).
        var playAfter = _playAfterUpdate;
        _playAfterUpdate = null;
        try
        {
            // Downloads, verifies and unpacks the new app, starts the swap script, then quits; the script reopens it.
            await UpdateService.DownloadAndRestartAsync(progress, stopWaiting.Token, playAfter);
        }
        catch (OperationCanceledException) when (stopWaiting.IsCancellationRequested)
        {
            // Back to Home: the chip offers the same, already downloaded update again; Play stays as it was.
            if (onScene) Home.EndUpdateScene();
            UpdateBadge.IsEnabled = true;
            HideUpdateNote();
            AutomationProperties.SetName(UpdateBadge, ReadyName);
        }
        catch (Exception ex) when (ex is UpdateVerificationException || UpdateService.Available == null)
        {
            // A failed integrity check, or a second failure in a row: the update was discarded and is out of the gate, so
            // the player launches what they have; a quiet retry offers it again only once it verifies. Nothing blocking.
            if (onScene) Home.EndUpdateScene();
            UpdateBadge.IsEnabled = true;
            UpdateBadge.IsVisible = false;
            HideUpdateNote();
            Home.RefreshUpdateGate();
        }
        catch (Exception)
        {
            if (onScene) Home.EndUpdateScene();
            UpdateBadge.IsEnabled = true;
            // Visible, not a hover label: the chip says it failed and that a click retries.
            ShowUpdateNote("Update failed - retry", "Brush.Warning");
            _playAfterUpdate ??= playAfter; // the retry is still the "Update and play" the player asked for
            AutomationProperties.SetName(UpdateBadge, "Update failed, retry");
        }
        finally
        {
            Home.UpdateWaitCancelRequested -= cancelWait;
            stopWaiting.Dispose();
        }
    }

    // ---- Render shots (UI/Shots/UpdateShots.cs) ----

    internal enum TourUpdate { Hidden, Ready, Updating, Failed, Blocked }

    /// <summary>Render mode: the chip in one of its states, as the click flow and ShowUpdateState leave it.</summary>
    internal void TourShowUpdate(TourUpdate state)
    {
        UpdateBadge.IsVisible = state != TourUpdate.Hidden;
        UpdateBadge.IsEnabled = state != TourUpdate.Updating;
        HideUpdateNote();
        switch (state)
        {
            case TourUpdate.Updating: ShowUpdateNote("Updating...", "Brush.TextDim"); break;
            case TourUpdate.Failed: ShowUpdateNote("Update failed - retry", "Brush.Warning"); break;
            case TourUpdate.Blocked: ShowUpdateNote(BlockedNote, "Brush.Warning"); break;
        }
    }

    internal Button TourUpdateBadge => UpdateBadge;
}
