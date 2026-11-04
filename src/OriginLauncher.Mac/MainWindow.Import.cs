using Avalonia;
using Avalonia.Animation;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Mac.UI;
using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac;

// The Import page around the main window: the Mac twin of the WPF MainWindow's import section (OpenImportPanel,
// CloseImportPanel, SetImportPanelOpen, the tour hooks) and MainWindow.ImportWiring.cs (closing mid-import, the
// startup recovery, the run-state refusals, the page behind the panel going inert, Home's bring-over button). Same engine
// (Core/Import), same rules. Kept in its own partial so it merges cleanly with the other Mac pages: the rest of the
// window only calls HookImport() from its constructor, and KeepOneTab skips while the page is open (no tab is lit).
// Not on the Mac: `--import` (the Windows installer's second-launch hand-off).
public sealed partial class MainWindow
{
    private ImportPanel? _importPanel;
    private bool _importOpen;
    private ContentControl ImportPageHost = null!;
    private readonly ImportCloseGuard _closeGuard = new();
    private readonly List<Control> _inertBehindImport = new();
    private IInputElement? _focusBeforeImport;
    private int _importFade; // the newest fade wins: a close's clean-up never runs after a newer open
    private bool _importPromptMarked;
    private bool _promptChecking;
    private CancellationTokenSource? _bringOverCts; // the running one-click transfer's Stop
    private ToggleButton? _tabBeforeImport; // lit again when the page closes, unless a tab press lit another

    /// <summary>Is the Import page up (the other pages' tab clicks ask this).</summary>
    internal bool ImportOpen => _importOpen;

    // The import wrote game files only (no launcher setting): Home re-reads its state, and a Profiles page sitting
    // behind the Import page rebuilds now (its mod counts may have changed).
    private void RefreshHomeAfterImport()
    {
        Home.RefreshProfile();
        if (_currentPage == LauncherPage.Profiles) ProfilesView.Show(Home.CurrentVersion);
    }

    // A one-line notice about the import (refused, undone, finishing): the window's toasts (WPF ImportWiring).
    private void ImportNotice(string title, string text) =>
        Toasts.Show(new OriginLauncher.App.Core.Social.SocialToast(OriginLauncher.App.Core.Social.SocialToastKind.Notice, title, text));

    /// <summary>Called once, at the end of the constructor.</summary>
    private void HookImport()
    {
        // The Import page: a full page in the page slot, under the bar (the WPF ImportPageHost, right after PageHost).
        ImportPageHost = new ContentControl { IsVisible = false, Focusable = false };
        KeyboardNavigation.SetTabNavigation(ImportPageHost, KeyboardNavigationMode.Cycle);
        Root.Children.Insert(Root.Children.IndexOf(PageHost) + 1, ImportPageHost);

        ImportButton.Click += (_, _) => OpenImportPanel();
        Home.BringOverBuilt += HookBringOver;
        // The four page tabs stay live: ShowPage (MainWindow.Pages.cs) closes the Import page first, or asks
        // "Stop importing?" mid-import (LeaveImportForTab).

        AddHandler(KeyDownEvent, Import_KeyDown, RoutingStrategies.Tunnel);
        Closing += Import_Closing;

        ImportRunState.Changed += OnImportRunChanged;
        LaunchRunState.Changed += OnImportRefusalChanged;
        UpdateRunState.Changed += OnImportRefusalChanged;
        Closed += (_, _) =>
        {
            ImportRunState.Changed -= OnImportRunChanged;
            LaunchRunState.Changed -= OnImportRefusalChanged;
            UpdateRunState.Changed -= OnImportRefusalChanged;
        };
        PushImportRefusal();

        // A real start only (render shots have no desktop lifetime and never touch the player's data): roll back an
        // import a crash interrupted, then, after the first frame, say so or offer Home's bring-over button.
        if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime)
        {
            ImportStartup.StartRecovery();
            global::OriginLauncher.App.Core.LogRetention.StartPrune(); // old per-launch game logs, launcher errors and crash logs
            Opened += async (_, _) => await AfterOpenedAsync();
        }
    }

    // ---- open / close ----

    // Opens the Import page (the bar's Import button, Home's bring-over choice, Profiles' Import). The scan starts here,
    // when the page opens - never at launcher startup. The backend is the real one unless render shots pass one.
    private void OpenImportPanel(IImportBackend? backend = null, ImportPanel.Intent intent = ImportPanel.Intent.Detect)
    {
        if (_importOpen) return;
        if (UpdateRunState.IsRunning)
        {
            ImportNotice("Import", UpdateRunState.Refusal);
            return;
        }
        if (Home.BringOverIfBuilt is { IsRunning: true })
        {
            ImportNotice("Import", "Wait for your setup to finish coming over.");
            return;
        }
        if (_friendsOpen) SetFriendsOpen(false);
        Home.BringOverIfBuilt?.HideOffer();
        MarkImportPromptSeen(); // any way in: the button never offers it again

        // The report's "Fix Compatibility" on a profile that can't start: the real flow (HomePage.Fix.cs). The page
        // closes, Home selects that profile, and the same prompt Play opens shows what the fix would change; nothing is
        // written before the player presses Fix. Render shots (a tour backend) set their own stand-in (AreaDShots.cs).
        if (backend == null && TourBackendFactory == null)
            ImportHooks.FixCompatibility = (version, profileId) =>
            {
                CloseImportPanel();
                SettingsStore.Update(s => ImportSettingsRules.SelectImported(s, profileId, version));
                ShowPage(LauncherPage.Home);
                Home.RefreshProfile();
                return Home.FixImportedProfileAsync(version, profileId);
            };
        var panel = new ImportPanel(backend ?? TourBackendFactory?.Invoke() ?? new LauncherImportBackend());
        panel.SetRefusal(ImportHooks.Refusal());
        panel.CloseRequested += (_, _) => CloseImportPanel();
        // "Play" on the done screen: close the page, select the one profile the import made (the import itself never
        // changes the Home selection), then show Home on its version and start it, so the game starts WITH the mods.
        panel.PlayRequested += (_, play) =>
        {
            CloseImportPanel();
            if (play.ProfileId is { } id)
                SettingsStore.Update(s => ImportSettingsRules.SelectImported(s, id, play.Version));
            ShowPage(LauncherPage.Home);
            Home.RefreshProfile();
            Home.PlayVersion(play.Version);
        };
        panel.OpenProfilesRequested += (_, _) =>
        {
            CloseImportPanel();
            ShowPage(LauncherPage.Profiles);
        };
        panel.Imported += (_, _) => RefreshHomeAfterImport();
        _importPanel = panel;
        ImportPageHost.Content = panel;
        SetImportOpen(true);
        panel.Start(intent);
    }

    private void CloseImportPanel()
    {
        if (!_importOpen) return;
        SetImportOpen(false);
        _importPanel?.Dispose(); // cancels a scan or plan in flight; the page stays until it has faded out
        _importPanel = null;
    }

    // A tab was pressed while the page is open (ShowPage asks first): a running import asks "Stop importing?" and the
    // page stays (false: don't navigate); otherwise the page closes and the tab's page shows (true).
    private bool LeaveImportForTab()
    {
        if (!_importOpen) return true;
        if (_importPanel is { IsApplying: true })
        {
            ShowImportChrome();
            _ = _importPanel.RequestClose();
            return false;
        }
        CloseImportPanel();
        return true;
    }

    // Shows / hides the Import page: it takes the page slot (the page behind is hidden, still loaded), the art behind
    // dims like on every non-Home page, the bar goes opaque, no tab is lit, and the account and friends buttons are
    // inert (the import goes into the selected account). Fades in over Motion.Scene, out over Motion.Exit.
    private void SetImportOpen(bool open)
    {
        _importOpen = open;
        SetBehindImportInert(open);
        var fade = ++_importFade;
        if (open)
        {
            _tabBeforeImport = new[] { NavHome, NavMods, NavProfiles, NavSettings }.FirstOrDefault(t => t.IsChecked == true);
            PageHost.IsVisible = false;
            ImportPageHost.IsVisible = true;
            ShowImportChrome();
        }
        else
        {
            PageHost.IsVisible = true;
            var home = ReferenceEquals(PageHost.Content, Home);
            if (NavHome.IsChecked != true && NavMods.IsChecked != true && NavProfiles.IsChecked != true && NavSettings.IsChecked != true)
                (_tabBeforeImport ?? NavHome).IsChecked = true;
            Stage.SetPage(home);
            BarSurface.Opacity = home ? 0 : 1;
            BarGlass.Opacity = home ? 1 : 0;
        }

        if (Motion.Off)
        {
            ImportPageHost.Transitions = null;
            ImportPageHost.Opacity = open ? 1 : 0;
            if (!open) EndImportFade();
        }
        else
        {
            var duration = Res.Get<TimeSpan>(open ? "Motion.Scene" : "Motion.Exit");
            ImportPageHost.Transitions = new Transitions { new DoubleTransition { Property = OpacityProperty, Duration = duration } };
            if (open)
            {
                ImportPageHost.Opacity = 0;
                Dispatcher.UIThread.Post(() => { if (fade == _importFade) ImportPageHost.Opacity = 1; }, DispatcherPriority.Render);
            }
            else
            {
                ImportPageHost.Opacity = 0;
                DispatcherTimer.RunOnce(() => { if (fade == _importFade) EndImportFade(); }, duration);
            }
        }
    }

    private void EndImportFade()
    {
        if (_importOpen) return;
        ImportPageHost.IsVisible = false;
        ImportPageHost.Content = null;
    }

    // The page's chrome while it is up: no tab lit, the art dimmed, the opaque bar.
    private void ShowImportChrome()
    {
        foreach (var t in new[] { NavHome, NavMods, NavProfiles, NavSettings }) t.IsChecked = false;
        Stage.SetPage(home: false);
        BarSurface.Opacity = 1;
        BarGlass.Opacity = 0;
    }

    // Everything the player could reach with the mouse or Tab behind the page. The page puts focus inside itself; this
    // keeps the rest of the window from taking it and puts it back when the page closes.
    private void SetBehindImportInert(bool open)
    {
        if (open)
        {
            if (_inertBehindImport.Count > 0) return;
            _focusBeforeImport = FocusManager?.GetFocusedElement();
            foreach (var element in new Control[] { PageHost, FriendsButton, AccountButton })
            {
                if (!element.IsEnabled) continue; // never "restore" something that was already off
                element.IsEnabled = false;
                _inertBehindImport.Add(element);
            }
            return;
        }
        foreach (var element in _inertBehindImport) element.IsEnabled = true;
        _inertBehindImport.Clear();
        var back = _focusBeforeImport as Control;
        _focusBeforeImport = null;
        if (back != null)
            Dispatcher.UIThread.Post(() =>
            {
                if (!_importOpen && back.IsEffectivelyVisible && back.IsEffectivelyEnabled && back.Focusable) back.Focus();
            }, DispatcherPriority.Input);
    }

    // Esc on the Import page: a prompt closes first, a running import asks, a page with a back arrow goes back,
    // otherwise the page closes (ImportPanel.HandleEscape).
    private void Import_KeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key != Key.Escape || !_importOpen || _importPanel == null) return;
        e.Handled = _importPanel.HandleEscape();
    }

    // ---- the run states ----

    // The import (or the startup recovery) ended: a close the player asked for while it ran now goes through - unless
    // an update is running, which restarts (and so closes) the launcher itself once the import is done.
    private void OnImportRunChanged() => Dispatcher.UIThread.Post(CloseIfHeldCloseIsDue);

    private void CloseIfHeldCloseIsDue()
    {
        if (UpdateRunState.IsRunning)
        {
            if (!ImportRunState.IsApplying) _closeGuard.Forget();
            return;
        }
        if (_closeGuard.ShouldCloseNow(ImportRunState.IsApplying)) Close();
    }

    // Raised on whichever thread started or ended a launch or an update: tell both places that offer Import (the open
    // page, Home's bring-over button) the one sentence, or null when Import can run.
    private void OnImportRefusalChanged() => Dispatcher.UIThread.Post(PushImportRefusal);

    private void PushImportRefusal()
    {
        var refusal = LaunchRunState.ImportRefusal();
        _importPanel?.SetRefusal(refusal);
        Home.BringOverIfBuilt?.SetRefusal(refusal);
    }

    // Closing mid-import would kill it halfway (the next start would roll it back, but the player is asked first):
    // hold the close, show the page's "Cancel the import?", and close when the result is back.
    private void Import_Closing(object? sender, WindowClosingEventArgs e)
    {
        if (e.Cancel) return;
        var panelImporting = _importOpen && _importPanel is { IsApplying: true };
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
                var bringing = Home.BringOverIfBuilt is { IsRunning: true };
                if (bringing) _bringOverCts?.Cancel();
                ImportNotice("Import", bringing ? "Stopping the import, then Origin closes." : "Finishing an interrupted import...");
                break;
        }
    }

    private async Task AskCancelImportBeforeCloseAsync()
    {
        if (_importPanel is not IImportCloseHost { IsApplying: true } host) return;
        _closeGuard.OnConfirmAnswered(await host.ConfirmCancelAsync());
        CloseIfHeldCloseIsDue();
    }

    // ---- after the first frame: the recovery result, then Home's bring-over button ----

    private async Task AfterOpenedAsync()
    {
        if (await ImportStartup.Recovery.ConfigureAwait(true) > 0)
        {
            ImportNotice("Import", "An interrupted import was undone.");
            RefreshHomeAfterImport();
        }
        await Task.Delay(3000); // past the opening: never compete with startup
        await MaybeShowImportPromptAsync();
    }

    private async Task MaybeShowImportPromptAsync()
    {
        if (_promptChecking) return;
        _promptChecking = true;
        try
        {
            var qualifying = await ComputeImportPromptSetupsAsync();
            if (qualifying is not { Count: > 0 } found || _importOpen || _importPromptMarked) return;
            // The button sits on Home (it shows when Home does) and stays out while Import would be refused. It is NOT
            // marked answered here: it comes back at the next start until the player says no or brings it over.
            var button = Home.BringOver;
            button.SetRefusal(ImportHooks.Refusal());
            button.Offer(found);
        }
        catch (Exception)
        {
            // a failed look for other launchers just means no button
        }
        finally
        {
            _promptChecking = false;
        }
    }

    // The button's presses (hooked once, when Home builds it).
    private void HookBringOver(BringOverButton button)
    {
        button.RunRequested += async (_, source) => await RunBringOverAsync(button, source);
        button.StopRequested += (_, _) => _bringOverCts?.Cancel();
        button.Answered += (_, _) => MarkImportPromptSeen(); // No thanks, or it came over: never offered again
        button.MoreRequested += (_, _) => OpenImportPanel();  // the full Import page (marks it answered too)
    }

    // One click: scan, plan with the defaults and copy, on the thread pool (HomeImportRunner), the button counting up.
    // ImportRunState is busy meanwhile, so Play waits and closing the window waits for it (Import_Closing).
    private async Task RunBringOverAsync(BringOverButton button, SourceKind? source)
    {
        var cts = new CancellationTokenSource();
        _bringOverCts = cts;
        var progress = new Progress<HomeImportProgress>(button.Report);
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
        button.Finish(outcome); // a success records the answer (Answered)
        if (outcome.Succeeded) RefreshHomeAfterImport();
    }

    // The player answered the button (No thanks, a transfer came over) or the Import page opened: it never offers it
    // again (one settings write, off the UI thread; never in render shots, which have no desktop lifetime).
    private void MarkImportPromptSeen()
    {
        if (_importPromptMarked || Application.Current?.ApplicationLifetime is not IClassicDesktopStyleApplicationLifetime) return;
        _importPromptMarked = true;
        _ = Task.Run(() =>
        {
            try { SettingsStore.Update(s => s.ImportPromptSeen = true); }
            catch (Exception) { /* the button may show once more; nothing else depends on it */ }
        });
    }

    // Off the UI thread: the settings read, the account check and the quick scan (the WPF wiring's twin, line for
    // line). Only a check that found nothing is stamped (ImportPrompt.StampCheck). Null = no button.
    private static Task<IReadOnlyList<DetectedInstance>?> ComputeImportPromptSetupsAsync() =>
        Task.Run<IReadOnlyList<DetectedInstance>?>(async () =>
        {
            var settings = SettingsStore.Load();
            var canImport = OriginLauncher.App.Core.GameFolders.Launcher.AccountFolders.SelectedKey() != null;
            if (!ImportPrompt.ShouldScan(settings.ImportPromptSeen, canImport, settings.ImportPromptCheckedUtc, DateTime.UtcNow)) return null;
            var backend = new LauncherImportBackend();
            var scan = await backend.ScanAsync(null, CancellationToken.None).ConfigureAwait(false);
            var targets = await backend.GetSupportedTargetsAsync(CancellationToken.None).ConfigureAwait(false);
            var qualifying = ImportPrompt.Qualifying(scan.Instances, targets);
            if (ImportPrompt.StampCheck(qualifying.Count))
            {
                var checkedAt = DateTime.UtcNow;
                SettingsStore.Update(s => s.ImportPromptCheckedUtc = checkedAt);
            }
            return ImportPrompt.ShouldShow(answered: false, openedWithImportFlag: false, canImport, qualifying.Count) ? qualifying : null;
        });

    // ---- render shots: the Import page with a made-up backend, never the real machine ----

    internal ImportPanel TourOpenImport(IImportBackend backend, ImportPanel.Intent intent = ImportPanel.Intent.Detect)
    {
        if (_importOpen) CloseImportPanel();
        OpenImportPanel(backend, intent);
        return _importPanel!;
    }

    internal void TourCloseImport() => CloseImportPanel();

    // The backend a real press of Import gets during render shots (never the real machine).
    internal Func<IImportBackend>? TourBackendFactory { get; set; }

    /// <summary>Render shots: what the bar's Import button does when pressed.</summary>
    internal void TourClickImport() => ImportButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));

    internal BringOverButton TourBringOver => Home.BringOver;
}
