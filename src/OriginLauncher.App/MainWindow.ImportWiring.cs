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
// page behind the import panel being inert, and the first-run card's question. Kept in its own file (a partial
// of MainWindow) so it merges cleanly with the panel's visual work in MainWindow.xaml(.cs): MainWindow.xaml.cs
// only calls the few methods named here (HookImportWiring, OnImportPanelOpenChanged, ShowRecoveryResultAsync,
// ComputeImportPromptQuestionAsync) and the Closing override below lives here.
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
    // places that offer Import (the open drawer, Home's one-time card) the one sentence, or null when Import can run.
    private void OnImportRefusalChanged() => Dispatcher.BeginInvoke(PushImportRefusal);

    private void PushImportRefusal()
    {
        var refusal = LaunchRunState.ImportRefusal();
        _importPanel?.SetRefusal(refusal);
        _homePage.ImportCard.SetRefusal(refusal);
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
                Toasts.Show(new SocialToast(SocialToastKind.Notice, "Import", "Finishing an interrupted import..."));
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
        if (_currentPage == LauncherPage.Profiles) _profilesPage.Show(_homePage.CurrentVersion);
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

    // ---- the first-run card ---------------------------------------------------------------------------

    private bool _importPromptMarked; // written once per run: opening the drawer again needs no second settings write

    /// <summary>
    /// The import drawer opened: the first-run card has done its job, so it never comes back (it could otherwise offer
    /// setups the player just imported). One settings write, off the UI thread; never in the CI render.
    /// </summary>
    private void MarkImportPromptSeen()
    {
        if (_importPromptMarked || App.IsRenderMode) return;
        _importPromptMarked = true;
        _ = Task.Run(() =>
        {
            try { Core.SettingsStore.Update(s => s.ImportPromptSeen = true); }
            catch (Exception) { /* the card may show once more; nothing else depends on it */ }
        });
    }

    // Off the UI thread: the settings read, the account check and the quick scan. The card's wording comes from
    // the QUALIFYING setups only (versions Origin supports); a machine with none gets no card. Null = no card.
    // The scan runs at most once a week (ImportPrompt.ShouldScan): every completed scan stamps ImportPromptCheckedUtc,
    // whatever it found, so a PC with no other launcher does not scan again after every start and account switch.
    // The question and the setups it is about (the welcome card names their launchers); null = no card.
    private static Task<(string Question, IReadOnlyList<DetectedInstance> Setups)?> ComputeImportPromptQuestionAsync() =>
        Task.Run<(string Question, IReadOnlyList<DetectedInstance> Setups)?>(async () =>
    {
        var settings = Core.SettingsStore.Load();
        // The card would only say "sign in first" without an account: keep the one-time card for later.
        var canImport = Core.GameFolders.Launcher.AccountFolders.SelectedKey() != null;
        if (!ImportPrompt.ShouldScan(settings.ImportPromptSeen, canImport, settings.ImportPromptCheckedUtc, DateTime.UtcNow)) return null;
        var backend = new LauncherImportBackend();
        var scan = await backend.ScanAsync(null, CancellationToken.None).ConfigureAwait(false);
        var checkedAt = DateTime.UtcNow;
        Core.SettingsStore.Update(s => s.ImportPromptCheckedUtc = checkedAt);
        var targets = await backend.GetSupportedTargetsAsync(CancellationToken.None).ConfigureAwait(false);
        var qualifying = ImportPrompt.Qualifying(scan.Instances, targets);
        return ImportPrompt.ShouldShow(alreadySeen: false, openedWithImportFlag: false, canImport, qualifying.Count)
            ? (ImportPrompt.Question(qualifying), qualifying) : null;
    });
}
