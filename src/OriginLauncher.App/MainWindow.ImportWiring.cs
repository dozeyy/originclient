using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
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

    /// <summary>Called once from the constructor.</summary>
    private void HookImportWiring()
    {
        // The import (or the startup recovery) ended: a close the player asked for while it ran now goes through.
        ImportRunState.Changed += () => Dispatcher.BeginInvoke(() =>
        {
            if (_closeGuard.ShouldCloseNow(ImportRunState.IsApplying)) Close();
        });
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
    /// THE hook into the panel: asks the player whether to cancel the running import and learns the ANSWER. A panel
    /// that implements <see cref="IImportCloseHost"/> answers (true = cancel, false = keep importing, which forgets
    /// the held close). The current ImportPanel does not implement it yet: its RequestClose() asks the question but
    /// cannot report the answer, so the held close is forgotten at once (the player closes again when they want
    /// to) and the window never closes on its own after "Keep importing".
    /// </summary>
    private async Task AskCancelImportBeforeCloseAsync()
    {
        if (_importPanel is not { IsApplying: true } panel) return;
        if (panel is IImportCloseHost host)
        {
            _closeGuard.OnConfirmAnswered(await host.ConfirmCancelAsync());
            return;
        }
        panel.RequestClose();
        _closeGuard.Forget();
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

    // ---- the page behind the import panel -------------------------------------------------------------

    // Everything the player could reach with the mouse or Tab behind the panel. The panel's own code puts focus
    // inside the panel; this keeps the rest of the window from taking it and restores it when the panel closes.
    private IEnumerable<UIElement> ElementsBehindImport()
    {
        yield return PageHost;
        yield return NavHome;
        yield return NavMods;
        yield return NavProfiles;
        yield return NavSettings;
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

    // Off the UI thread: the settings read, the account check and the quick scan. The card's wording comes from
    // the QUALIFYING setups only (versions Origin supports); a machine with none gets no card. Null = no card.
    private static Task<string?> ComputeImportPromptQuestionAsync() => Task.Run(async () =>
    {
        if (Core.SettingsStore.Load().ImportPromptSeen) return null;
        var canImport = Core.GameFolders.Launcher.AccountFolders.SelectedKey() != null;
        if (!canImport) return null; // the import would only say "sign in first": keep the one-time card for later
        var backend = new LauncherImportBackend();
        var scan = await backend.ScanAsync(null, CancellationToken.None).ConfigureAwait(false);
        var targets = await backend.GetSupportedTargetsAsync(CancellationToken.None).ConfigureAwait(false);
        var qualifying = ImportPrompt.Qualifying(scan.Instances, targets);
        return ImportPrompt.ShouldShow(alreadySeen: false, openedWithImportFlag: false, canImport, qualifying.Count)
            ? ImportPrompt.Question(qualifying) : null;
    });
}
