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
// while an import is writing, and runs the first-run "bring your setups over?" card on Home. The page itself is
// ImportPage (Overlays/ImportPage*.cs); every rule it follows is the shared Core (Core/Import/**, UI/Controls/Import*).
public static class ImportView
{
    private static ImportPage? _page;
    private static MainWindow? _pageWindow;
    private static bool _promptMarked; // written once per run: opening the page again needs no second settings write

    /// <summary>The top bar's Import button (and the Home card's "Choose"): the page opens on the launcher list.</summary>
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
        if (window.DrawerOpen) window.CloseDrawer();
        if (window.Home.Card is ImportPromptCard) window.Home.ShowCard(null);
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

    // Any way in (the bar, the Home card): the first-run card never offers itself again.
    private static void MarkPromptSeen()
    {
        if (_promptMarked || Shots.ShotRunner.Active) return;
        _promptMarked = true;
        _ = Task.Run(() =>
        {
            try { AppState.Write(s => s.ImportPromptSeen = true); }
            catch (Exception) { /* the card may show once more; nothing else depends on it */ }
        });
    }

    // ---------------------------------------------------------------- first run

    /// <summary>
    /// Once the window is up (real runs only): tells the player when an interrupted import was undone, then, the
    /// first time (re-checked weekly while nothing qualified), scans the other launchers on the thread pool and offers
    /// the Home card when there is something to bring.
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
            var prompt = await Task.Run(QuestionAsync).ConfigureAwait(true);
            if (prompt is not { } p || window.SheetOpen || window.Home.Card != null) return;
            ShowCard(window, p.Question, p.Setups);
        }
        catch (Exception ex)
        {
            ImportLog.Write("first-run import check failed: " + ex.Message);
        }
    }

    private static async Task<(string Question, IReadOnlyList<DetectedInstance> Setups)?> QuestionAsync()
    {
        var settings = App.Core.SettingsStore.Load();
        var canImport = App.Core.GameFolders.Launcher.AccountFolders.SelectedKey() != null;
        if (!ImportPrompt.ShouldScan(settings.ImportPromptSeen, canImport, settings.ImportPromptCheckedUtc, DateTime.UtcNow)) return null;
        var backend = new LauncherImportBackend();
        var scan = await backend.ScanAsync(null, CancellationToken.None).ConfigureAwait(false);
        var checkedAt = DateTime.UtcNow;
        AppState.Write(s => s.ImportPromptCheckedUtc = checkedAt);
        var targets = await backend.GetSupportedTargetsAsync(CancellationToken.None).ConfigureAwait(false);
        var qualifying = ImportPrompt.Qualifying(scan.Instances, targets);
        return ImportPrompt.ShouldShow(alreadySeen: false, openedWithImportFlag: false, canImport, qualifying.Count)
            ? (ImportPrompt.Question(qualifying), qualifying) : null;
    }

    /// <summary>The Home card (render runs call it with made-up setups): Import everything, Choose, or Skip for now.</summary>
    internal static ImportPromptCard ShowCard(MainWindow window, string question, IReadOnlyList<DetectedInstance> setups,
        Func<ImportPage.Intent, ImportPage?>? open = null)
    {
        var card = new ImportPromptCard(question, setups);
        open ??= intent => OpenWith(window, intent, null);
        void PushRefusal() => Dispatcher.UIThread.Post(() => card.SetRefusal(ImportHooks.Refusal()));
        card.SetRefusal(ImportHooks.Refusal());
        LaunchRunState.Changed += PushRefusal;
        UpdateRunState.Changed += PushRefusal;
        void Remove()
        {
            LaunchRunState.Changed -= PushRefusal;
            UpdateRunState.Changed -= PushRefusal;
            if (window.Home.Card == card) window.Home.ShowCard(null);
        }
        card.ImportRequested += () => { Remove(); open(ImportPage.Intent.Everything); };
        card.ChooseRequested += () => { Remove(); open(ImportPage.Intent.Choose); };
        card.SkipRequested += () => { Remove(); MarkPromptSeen(); };
        window.Home.ShowCard(card);
        return card;
    }
}
