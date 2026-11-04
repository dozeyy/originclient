using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Threading;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Desktop.Shell;
using AppState = OriginLauncher.Desktop.Shell.LauncherState;

namespace OriginLauncher.Desktop.Overlays;

// The Import wiring (port of MainWindow.ImportWiring.cs + OpenImportPanel): opens the Import page in the window's sheet
// layer, routes its keys, pushes the refusal sentence (a game starting, an update running), holds the window's close
// while an import is writing, and runs Home's one-click bring-over button. The page itself is
// ImportPage (Overlays/ImportPage*.cs); every rule it follows is the shared Core (Core/Import/**, UI/Controls/Import*).
public static class ImportView
{
    private static ImportPage? _page;
    private static MainWindow? _pageWindow;
    private static bool _promptMarked; // written once per run: opening the page again needs no second settings write

    /// <summary>The top bar's Import button (and the bring-over choice's "Choose what comes over"): the launcher list.</summary>
    public static void Open(MainWindow window) => OpenWith(window, ImportPage.Intent.Detect, null);

    /// <summary>Opens the page with an intent; <paramref name="backend"/> is the render runs' made-up one (null: the real one).</summary>
    internal static ImportPage? OpenWith(MainWindow window, ImportPage.Intent intent, IImportBackend? backend)
    {
        if (_page != null && _pageWindow == window && window.SheetOpen) return _page;
        if (backend == null && UpdateRunState.IsRunning)
        {
            window.Toast(UpdateRunState.Refusal);
            return null;
        }
        if (backend == null && window.Home.BringOverIfBuilt is { IsRunning: true })
        {
            window.Toast("Wait for your setup to finish coming over.");
            return null;
        }
        if (window.DrawerOpen) window.CloseDrawer();
        window.Home.BringOverIfBuilt?.HideOffer();
        MarkPromptSeen();

        var page = new ImportPage(window, backend ?? new LauncherImportBackend());
        // The page takes the page slot: the page behind hides (still loaded) and the art dims like any non-Home page.
        var behind = window.PageOf<Control>(window.Page);
        behind.IsVisible = false;
        window.Art.SetPage(false);

        void PushRefusal() => Dispatcher.UIThread.Post(() => page.SetRefusal(ImportHooks.Refusal()));
        void OnKey(object? sender, KeyEventArgs e)
        {
            if (e.Handled || window.DrawerOpen) return;
            if (page.HandleKey(e.Key, e.KeyModifiers)) e.Handled = true;
        }
        void OnPage(PageKey key) => window.CloseSheet();
        void OnClosing(object? sender, WindowClosingEventArgs e)
        {
            if (!page.IsApplying || Shots.ShotRunner.Active) return; // render runs close mid-import on purpose
            e.Cancel = true; // an import that is writing is asked about first, never cut off
            _ = AskThenCloseAsync(window, page);
        }

        LaunchRunState.Changed += PushRefusal;
        UpdateRunState.Changed += PushRefusal;
        window.AddHandler(InputElement.KeyDownEvent, OnKey, RoutingStrategies.Tunnel);
        window.PageChanged += OnPage;
        window.Closing += OnClosing;

        page.CloseRequested += window.CloseSheet;
        // "Play" on the done screen: select the one profile the import made (the import itself never changes the Home
        // selection), show Home on its version and start it, so the game starts WITH the imported mods.
        page.PlayRequested += play =>
        {
            window.CloseSheet();
            if (play.ProfileId is { } id) AppState.Write(s => ImportSettingsRules.SelectImported(s, id, play.Version));
            window.Navigate(PageKey.Home);
            window.Home.Refresh();
            _ = window.Home.Launches.PlayOrFocusAsync(play.Version);
        };
        page.OpenProfilesRequested += () =>
        {
            window.CloseSheet();
            window.Navigate(PageKey.Profiles);
        };
        // The import wrote game files only (no launcher setting): Home re-reads its state.
        page.Imported += () => window.Home.Refresh();

        _page = page;
        _pageWindow = window;
        window.OpenSheet(page, onClosed: () =>
        {
            LaunchRunState.Changed -= PushRefusal;
            UpdateRunState.Changed -= PushRefusal;
            window.RemoveHandler(InputElement.KeyDownEvent, OnKey);
            window.PageChanged -= OnPage;
            window.Closing -= OnClosing;
            page.Dispose(); // cancels a scan or plan in flight; an import that is writing finishes on its own
            if (_page == page) { _page = null; _pageWindow = null; }
            var current = window.PageOf<Control>(window.Page);
            behind.IsVisible = behind == current;
            current.IsVisible = true;
            window.Art.SetPage(window.Page == PageKey.Home);
        });
        page.Start(intent);
        return page;
    }

    private static async Task AskThenCloseAsync(MainWindow window, ImportPage page)
    {
        if (!await page.AskCancelAsync()) return;
        while (page.IsApplying) await Task.Delay(100); // the engine puts everything back, then the window closes
        window.Close();
    }

    // Any way in (the bar, Home's button), No thanks, or a transfer that came over: the button never offers itself again.
    private static void MarkPromptSeen()
    {
        if (_promptMarked || Shots.ShotRunner.Active) return;
        _promptMarked = true;
        _ = Task.Run(() =>
        {
            try { AppState.Write(s => s.ImportPromptSeen = true); }
            catch (Exception) { /* the button may show once more; nothing else depends on it */ }
        });
    }

    // ---------------------------------------------------------------- Home's bring-over button

    /// <summary>
    /// Once the window is up (real runs only): tells the player when an interrupted import was undone, then scans the
    /// other launchers on the thread pool and offers Home's bring-over button when there is something to bring (every
    /// start until the player answers it; weekly while nothing qualified: ImportPrompt.ShouldScan / StampCheck).
    /// </summary>
    public static void CheckFirstRun(MainWindow window)
    {
        if (Shots.ShotRunner.Active) return;
        _ = RunFirstRunAsync(window);
    }

    private static async Task RunFirstRunAsync(MainWindow window)
    {
        try
        {
            if (await ImportStartup.Recovery.ConfigureAwait(true) > 0)
            {
                window.Toast("An interrupted import was undone.");
                window.Home.Refresh();
            }
            var qualifying = await Task.Run(SetupsAsync).ConfigureAwait(true);
            if (qualifying is not { Count: > 0 } found || window.SheetOpen || _promptMarked) return;
            var button = BringOverOf(window);
            button.SetRefusal(ImportHooks.Refusal());
            button.Offer(found); // NOT marked answered here: it comes back next start until the player answers it
        }
        catch (Exception ex)
        {
            ImportLog.Write("bring-over check failed: " + ex.Message);
        }
    }

    // Off the UI thread (the WPF wiring's twin, line for line). Only a check that found nothing is stamped. Null = no button.
    private static async Task<IReadOnlyList<DetectedInstance>?> SetupsAsync()
    {
        var settings = App.Core.SettingsStore.Load();
        var canImport = App.Core.GameFolders.Launcher.AccountFolders.SelectedKey() != null;
        if (!ImportPrompt.ShouldScan(settings.ImportPromptSeen, canImport, settings.ImportPromptCheckedUtc, DateTime.UtcNow)) return null;
        var backend = new LauncherImportBackend();
        var scan = await backend.ScanAsync(null, CancellationToken.None).ConfigureAwait(false);
        var targets = await backend.GetSupportedTargetsAsync(CancellationToken.None).ConfigureAwait(false);
        var qualifying = ImportPrompt.Qualifying(scan.Instances, targets);
        if (ImportPrompt.StampCheck(qualifying.Count))
        {
            var checkedAt = DateTime.UtcNow;
            AppState.Write(s => s.ImportPromptCheckedUtc = checkedAt);
        }
        return ImportPrompt.ShouldShow(answered: false, openedWithImportFlag: false, canImport, qualifying.Count) ? qualifying : null;
    }

    /// <summary>Home's button, built and wired on first use (render runs ask for it too; their presses never start a transfer).</summary>
    internal static BringOverButton BringOverOf(MainWindow window)
    {
        if (window.Home.BringOverIfBuilt is { } built) return built;
        var button = window.Home.BringOver;
        void PushRefusal() => Dispatcher.UIThread.Post(() => button.SetRefusal(ImportHooks.Refusal()));
        LaunchRunState.Changed += PushRefusal;
        UpdateRunState.Changed += PushRefusal;
        window.Closed += (_, _) =>
        {
            LaunchRunState.Changed -= PushRefusal;
            UpdateRunState.Changed -= PushRefusal;
        };
        CancellationTokenSource? stop = null;
        button.RunRequested += async source =>
        {
            var cts = new CancellationTokenSource();
            stop = cts;
            await RunBringOverAsync(window, button, source, cts.Token);
            if (ReferenceEquals(stop, cts)) stop = null;
            cts.Dispose();
        };
        button.StopRequested += () => stop?.Cancel();
        button.Answered += MarkPromptSeen;              // No thanks, or it came over: never offered again
        button.MoreRequested += () => Open(window);     // the full Import page (marks it answered too)
        return button;
    }

    // One click: scan, plan with the defaults and copy, on the thread pool (HomeImportRunner), the button counting up.
    // ImportRunState is busy meanwhile, so Play waits; closing the window waits for it too, then closes.
    private static async Task RunBringOverAsync(MainWindow window, BringOverButton button, SourceKind? source, CancellationToken ct)
    {
        var closeAfter = false;
        void OnClosing(object? sender, WindowClosingEventArgs e)
        {
            if (!button.IsRunning || Shots.ShotRunner.Active) return;
            e.Cancel = true; // a transfer that is writing finishes (or rolls back) first, never cut off
            closeAfter = true;
            window.Toast("Finishing bringing your setup over, then Origin closes.");
        }
        window.Closing += OnClosing;
        HomeImportOutcome outcome;
        try
        {
            outcome = await HomeImportRunner.RunAsync(new LauncherImportBackend(), source,
                new Progress<HomeImportProgress>(button.Report), ct);
        }
        catch (Exception ex)
        {
            outcome = HomeImport.Crashed(ImportErrors.Plain(ex));
        }
        finally
        {
            window.Closing -= OnClosing;
        }
        button.Finish(outcome); // a success records the answer (Answered)
        if (outcome.Succeeded) window.Home.Refresh();
        if (closeAfter) window.Close();
    }
}
