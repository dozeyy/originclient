using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Social;

namespace OriginLauncher.App;

// The non-visual wiring of the import feature around the main window: closing while an import writes, the
// startup recovery's effect on the pages, a second `--import` launch handing its request to this window, the
// page behind the import panel being inert, and Home's bring-over button (its offer and its one-click transfer). Kept in its own file (a partial
// of MainWindow) so it merges cleanly with the panel's visual work in MainWindow.xaml(.cs): MainWindow.xaml.cs
// only calls the few methods named here (HookImportWiring, HookBringOver, BringOverBlocksImport, OnImportPanelOpenChanged,
// ShowRecoveryResultAsync, ComputeImportPromptSetupsAsync) and the Closing override below lives here.
public partial class MainWindow
{
    private readonly ImportCloseGuard _closeGuard = new();

    // What was switched off behind the import panel, so closing restores exactly those and nothing else.
    private readonly List<UIElement> _inertBehindImport = new();
    private IInputElement? _focusBeforeImport;

    /// <summary>Called once from the constructor; everything it subscribes to is static, so Closed unsubscribes it.</summary>
    private void HookImportWiring()
    {
        ImportRunState.Changed += OnImportRunChanged;
        LaunchRunState.Changed += OnImportRefusalChanged;
        UpdateRunState.Changed += OnImportRefusalChanged;
        Closed += (_, _) =>
        {
            ImportRunState.Changed -= OnImportRunChanged;
            LaunchRunState.Changed -= OnImportRefusalChanged;
            UpdateRunState.Changed -= OnImportRefusalChanged;
        };
        PushImportRefusal(); // once now: the state may already be set when the window opens
    }

    // The import (or the startup recovery) ended: a close the player asked for while it ran now goes through - unless
    // an update is running, which restarts (and so closes) the launcher itself once the import is done.
    private void OnImportRunChanged() => Dispatcher.BeginInvoke(CloseIfHeldCloseIsDue);

    private void CloseIfHeldCloseIsDue()
    {
        if (UpdateRunState.IsRunning)
        {
            if (!ImportRunState.IsApplying) _closeGuard.Forget();
            return;
        }
        if (_closeGuard.ShouldCloseNow(ImportRunState.IsApplying)) Close();
    }

    // Raised on whichever thread started/ended a launch or an update: marshal to the UI thread, then tell both
    // places that offer Import (the open drawer, Home's bring-over button) the one sentence, or null when Import can run.
    private void OnImportRefusalChanged() => Dispatcher.BeginInvoke(PushImportRefusal);

    private void PushImportRefusal()
    {
        var refusal = LaunchRunState.ImportRefusal();
        _importPanel?.SetRefusal(refusal);
        _homePage.BringOver.SetRefusal(refusal);
    }

    // ---- closing while an import writes ---------------------------------------------------------------

    protected override void OnClosing(CancelEventArgs e)
    {
        base.OnClosing(e);
        if (e.Cancel) return;
        // Closing mid-import would kill it halfway (the next start would roll it back, but the player should be
        // asked first): hold the close, show the panel's "Cancel the import?", and close when the result is back
        // (unless they answered "keep importing"). During the startup recovery there is no panel to ask: say why
        // the window is still there.
        var panelImporting = _importPanelOpen && _importPanel is { IsApplying: true };
        switch (_closeGuard.OnClosing(ImportRunState.IsApplying, panelImporting))
        {
            case CloseHold.ImportRunning:
                e.Cancel = true;
                _ = AskCancelImportBeforeCloseAsync();
                break;
            case CloseHold.RecoveryRunning:
                e.Cancel = true;
                // Home's one-click transfer is stopped, like the Import page's Stop: the engine puts everything back,
                // and the window closes as soon as nothing writes any more (OnImportRunChanged). Never a wait for the copy.
                if (_homePage.BringOver.IsRunning) _bringOverCts?.Cancel();
                Toasts.Show(new SocialToast(SocialToastKind.Notice, "Import", _homePage.BringOver.IsRunning
                    ? "Stopping the import, then Origin closes."
                    : "Finishing an interrupted import..."));
                break;
        }
    }

    /// <summary>
    /// THE hook into the panel: asks the player whether to cancel the running import (the panel as the wiring's
    /// <see cref="IImportCloseHost"/>) and learns the ANSWER. False = keep importing, which forgets the held close.
    /// True only STARTS the cancel: the engine rolls back, and the window closes when ImportRunState goes idle
    /// (OnImportRunChanged), never before the rollback is done. If it already went idle while the question was up,
    /// it closes now.
    /// </summary>
    private async Task AskCancelImportBeforeCloseAsync()
    {
        if (_importPanel is not IImportCloseHost { IsApplying: true } host) return;
        _closeGuard.OnConfirmAnswered(await host.ConfirmCancelAsync());
        CloseIfHeldCloseIsDue();
    }

    // ---- an update downloading or restarting ----------------------------------------------------------

    /// <summary>True (with a toast) while an update is downloading or restarting: no import opens until it is back.</summary>
    private bool ImportBlockedByUpdate()
    {
        if (!UpdateRunState.IsRunning) return false;
        Toasts.Show(new SocialToast(SocialToastKind.Notice, "Import", UpdateRunState.Refusal));
        return true;
    }

    /// <summary>The update finished downloading while an import still runs: it restarts once the import is done.</summary>
    private void ToastFinishingImportBeforeUpdate() =>
        Toasts.Show(new SocialToast(SocialToastKind.Notice, "Update", "Finishing your import first..."));

    // ---- the startup recovery -------------------------------------------------------------------------

    // After the first window is up: if recovery rolled an interrupted import back, say so once and make the pages
    // show what is on disk now (the rollback may have removed a profile and put the old selection back).
    private async Task ShowRecoveryResultAsync()
    {
        if (await ImportStartup.Recovery.ConfigureAwait(true) <= 0) return;
        Toasts.Show(new SocialToast(SocialToastKind.Notice, "Import", "An interrupted import was undone."));
        RefreshPagesAfterRollback();
    }

    private void RefreshPagesAfterRollback()
    {
        _homePage.RefreshProfile();
        // The Profiles page rebuilds from disk each time it is opened; when it is the one on screen, do it now.
        if (_currentPage == LauncherPage.Profiles) ProfilesView.Show(_homePage.CurrentVersion);
    }

    // ---- `--import` while a launcher is already running -----------------------------------------------

    /// <summary>The running launcher's side of the second launch: bring the window up and open the import panel.</summary>
    internal void OpenImportFromAnotherInstance()
    {
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Show();
        Activate();
        OpenImportPanel();
    }

    // ---- the page behind the Import page -------------------------------------------------------------

    // Everything the player could reach with the mouse or Tab behind the panel. The panel's own code puts focus
    // inside the panel; this keeps the rest of the window from taking it and restores it when the panel closes.
    private IEnumerable<UIElement> ElementsBehindImport()
    {
        // The four page tabs stay live: pressing one closes the Import page (or asks "Stop importing?" mid-import).
        yield return PageHost;
        yield return FriendsButton;
        yield return AccountButton;
    }

    /// <summary>Called from SetImportPanelOpen with the new state.</summary>
    private void OnImportPanelOpenChanged(bool open)
    {
        if (open)
        {
            if (_inertBehindImport.Count > 0) return; // already open
            _focusBeforeImport = Keyboard.FocusedElement;
            foreach (var element in ElementsBehindImport())
            {
                if (!element.IsEnabled) continue; // never "restore" something that was already off
                element.IsEnabled = false;
                _inertBehindImport.Add(element);
            }
            return;
        }

        foreach (var element in _inertBehindImport) element.IsEnabled = true;
        _inertBehindImport.Clear();

        // Back to where the player was (the Import button, usually), once the controls are live again.
        var back = _focusBeforeImport as UIElement;
        _focusBeforeImport = null;
        if (back != null)
            Dispatcher.BeginInvoke(DispatcherPriority.Input, () =>
            {
                if (!_importPanelOpen && back.IsVisible && back.IsEnabled && back.Focusable) back.Focus();
            });
    }

    // ---- Home's bring-over button (Core/Import/Launcher/ImportPrompt, BringOverModel, HomeImportRunner) -------

    private bool _importPromptMarked; // written once per run: opening the drawer again needs no second settings write
    private CancellationTokenSource? _bringOverCts; // the running one-click transfer's Stop

    /// <summary>Called once from the constructor: the button's presses.</summary>
    private void HookBringOver()
    {
        var button = _homePage.BringOver;
        button.RunRequested += async (_, source) => await RunBringOverAsync(source);
        button.StopRequested += (_, _) => _bringOverCts?.Cancel();
        button.Answered += (_, _) => MarkImportPromptSeen(); // No thanks, or it came over: never offered again
        button.MoreRequested += (_, _) => OpenImportPanel();  // the full Import page (marks it answered too)
    }

    // One click: scan, plan with the defaults and copy, on the thread pool (HomeImportRunner), the button counting up.
    // ImportRunState is busy meanwhile, so Play waits and closing the window waits for it (OnClosing above).
    private async Task RunBringOverAsync(SourceKind? source)
    {
        var cts = new CancellationTokenSource();
        _bringOverCts = cts;
        var progress = new Progress<HomeImportProgress>(p => _homePage.BringOver.Report(p));
        HomeImportOutcome outcome;
        try
        {
            outcome = await HomeImportRunner.RunAsync(new LauncherImportBackend(), source, progress, cts.Token);
        }
        catch (Exception ex)
        {
            outcome = HomeImport.Crashed(ImportErrors.Plain(ex));
        }
        finally
        {
            if (ReferenceEquals(_bringOverCts, cts)) _bringOverCts = null;
            cts.Dispose();
        }
        _homePage.BringOver.Finish(outcome); // a success records the answer (Answered)
        // The import wrote profiles and game files: Home re-reads them, and a Profiles page on screen rebuilds.
        if (outcome.Succeeded) RefreshPagesAfterRollback();
    }

    /// <summary>True (with a toast) while Home's one-click transfer runs: the Import page waits for it.</summary>
    private bool BringOverBlocksImport()
    {
        if (!_homePage.BringOver.IsRunning) return false;
        Toasts.Show(new SocialToast(SocialToastKind.Notice, "Import", "Wait for your setup to finish coming over."));
        return true;
    }

    /// <summary>
    /// The player answered the button (No thanks, a transfer came over) or the Import page opened: it never offers it
    /// again (it could otherwise offer setups the player just imported). One settings write, off the UI thread; never in
    /// the CI render.
    /// </summary>
    private void MarkImportPromptSeen()
    {
        if (_importPromptMarked || App.IsRenderMode) return;
        _importPromptMarked = true;
        _ = Task.Run(() =>
        {
            try { Core.SettingsStore.TryUpdate(s => s.ImportPromptSeen = true, quiet: true); }
            catch (Exception) { /* the button may show once more; nothing else depends on it */ }
        });
    }

    // Off the UI thread: the settings read, the account check and the quick scan. The button is about the QUALIFYING
    // setups only (versions Origin supports); null = no button. A check that found nothing is stamped
    // (ImportPrompt.StampCheck) so a PC with no other launcher scans at most once a week; one that found something is not,
    // so the button comes back at the next start until the player answers it.
    private static Task<IReadOnlyList<DetectedInstance>?> ComputeImportPromptSetupsAsync() =>
        Task.Run<IReadOnlyList<DetectedInstance>?>(async () =>
    {
        var settings = Core.SettingsStore.Load();
        // The transfer would only say "sign in first" without an account: keep the offer for later.
        var canImport = Core.GameFolders.Launcher.AccountFolders.SelectedKey() != null;
        if (!ImportPrompt.ShouldScan(settings.ImportPromptSeen, canImport, settings.ImportPromptCheckedUtc, DateTime.UtcNow)) return null;
        var backend = new LauncherImportBackend();
        var scan = await backend.ScanAsync(null, CancellationToken.None).ConfigureAwait(false);
        var targets = await backend.GetSupportedTargetsAsync(CancellationToken.None).ConfigureAwait(false);
        var qualifying = ImportPrompt.Qualifying(scan.Instances, targets);
        if (ImportPrompt.StampCheck(qualifying.Count))
        {
            var checkedAt = DateTime.UtcNow;
            // Off the UI thread and nothing for the player to do: a busy file only means the scan runs again next start.
            Core.SettingsStore.TryUpdate(s => s.ImportPromptCheckedUtc = checkedAt, quiet: true);
        }
        return ImportPrompt.ShouldShow(answered: false, openedWithImportFlag: false, canImport, qualifying.Count) ? qualifying : null;
    });
}
