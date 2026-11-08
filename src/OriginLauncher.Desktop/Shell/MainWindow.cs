using Avalonia;
using Avalonia.Animation;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.VisualTree;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Overlays;
using OriginLauncher.Desktop.Pages;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Shell;

public enum PageKey { Home, Mods, Profiles, Settings }

/// <summary>A page the window hosts. Shown runs every time it becomes the visible page (refresh cheaply there).</summary>
public interface IPage
{
    void Shown();
}

public enum Tone { Info, Success, Warning, Danger }

// The launcher window (DESIGN-SYSTEM.md 11.4, 12, 13): chromeless, the art behind everything, the pages under a
// fixed 48 px top bar, and the overlay layers in z order: sheet (the version picker, over Home under the bar),
// cover (the launch screen, over the whole window), drawer (right, 380, full height), dialog (centred on a
// scrim), toasts (bottom centre). Esc closes the topmost open layer. Pages are built once, on first visit, and
// kept (switching is instant: nothing reloads on open).
public sealed class MainWindow : Window
{
    public static MainWindow? Current { get; private set; }

    public ArtStage Art { get; } = new();
    public TopBar Bar { get; }
    public PageKey Page { get; private set; } = PageKey.Home;

    private readonly Panel _pageHost = new() { Margin = new Thickness(0, Sizes.Bar, 0, 0) };
    private readonly Dictionary<PageKey, Control> _pages = new();
    private readonly Panel _sheet = new() { Margin = new Thickness(0, Sizes.Bar, 0, 0), IsVisible = false };
    private readonly Panel _cover = new() { IsVisible = false };
    private readonly Panel _drawerLayer = new() { IsVisible = false };
    // The drawers' scrim: the Windows launcher's Scrim at 50 % (MainWindow.xaml.cs SetAccountPanelOpen / SetFriendsOpen).
    private readonly Border _drawerScrim = new() { Background = Palette.Scrim, Opacity = 0 };
    private readonly Border _drawer = new()
    {
        Width = Sizes.Drawer, HorizontalAlignment = HorizontalAlignment.Right, Background = Palette.Panel,
        BorderBrush = Palette.Line, BorderThickness = new Thickness(1, 0, 0, 0), BoxShadow = Palette.OverlayShadow,
    };
    private readonly Panel _dialogLayer = new() { IsVisible = false, Background = Palette.Scrim };
    private readonly Border _dialog = new()
    {
        MaxWidth = Sizes.Dialog, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        Background = Palette.Panel, BorderBrush = Palette.Line, BorderThickness = new Thickness(1),
        CornerRadius = new CornerRadius(Radius.Lg), Padding = new Thickness(Space.S6), BoxShadow = Palette.OverlayShadow,
        Margin = new Thickness(Space.S6),
    };
    private readonly StackPanel _toasts = new()
    {
        Spacing = Space.S2, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Bottom,
        Margin = new Thickness(0, 0, 0, Space.S8 + Sizes.Strip + Space.S2), IsHitTestVisible = true,
    };
    private bool _dialogDismissable = true;
    private Action? _onDrawerClosed, _onSheetClosed, _onDialogClosed;

    // Enter/exit motion of each layer (Kit/Tween), the Windows launcher's timings and easings token for token:
    //   drawer   slides in from the right over Motion.Scene, out over Motion.Exit, cubic ease-out; its scrim fades to 50 %
    //   dialog   scrim and card fade, the card grows 0.97 -> 1 (the WPF sign-in card's open), Scene in / Exit out, cubic
    //   cover    appears at once with its stage column rising (LaunchCover), fades out over Motion.Exit; the bar fades
    //            away under it (200 ms) and back (320 ms), quintic out (WPF SetTopBarHidden)
    //   page     opacity 0.85 -> 1 and 10 px from the clicked tab's side, Motion.Base, quintic out (WPF AnimatePageIn)
    //   toast    fades in and rises 4 px over Motion.Enter (WPF SocialToastHost)
    // The *Open flags are the logical state (true from the open call, false from the close call); a layer stays
    // visible only until its exit motion ends, and a re-open during it simply turns the motion around.
    private readonly Tween _drawerMotion, _drawerScrimMotion, _dialogLayerMotion, _dialogMotion, _coverMotion, _barMotion, _pageMotion;
    private bool _drawerOpen, _dialogOpen, _coverOpen, _coverUnderBar;
    private int _drawerGen, _dialogGen, _coverGen;

    public event Action<PageKey>? PageChanged;

    public MainWindow()
    {
        Current = this;
        Title = "Origin";
        Width = Sizes.WindowW;
        Height = Sizes.WindowH;
        MinWidth = Sizes.WindowMinW;
        MinHeight = Sizes.WindowMinH;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        WindowDecorations = WindowDecorations.None;
        Background = Palette.Bg;
        FontFamily = Assets.Inter;
        Foreground = Palette.Text;
        if (Assets.Logo is { } logo) Icon = new WindowIcon(logo);
        if (!Shots.ShotRunner.Active) FitToWorkArea();

        Bar = new TopBar(this) { VerticalAlignment = VerticalAlignment.Top };

        _drawerLayer.Children.Add(_drawerScrim); // click-out closes (below)
        _drawerLayer.Children.Add(_drawer);
        _drawerScrim.PointerPressed += (_, e) => { CloseDrawer(); e.Handled = true; };
        _drawerMotion = new Tween(_drawer, opacity: false, transform: true, transformEasing: Motion.CubicOut);
        _drawerMotion.Jump(transform: Offscreen(Sizes.Drawer));
        _drawerScrimMotion = new Tween(_drawerScrim);
        _dialogLayerMotion = new Tween(_dialogLayer);
        _dialogLayerMotion.Jump(opacity: 0);
        _dialog.RenderTransformOrigin = RelativePoint.Center;
        _dialogMotion = new Tween(_dialog, opacity: false, transform: true, transformEasing: Motion.CubicOut);
        _dialogMotion.Jump(transform: "scale(0.97)");
        _coverMotion = new Tween(_cover);
        _barMotion = new Tween(Bar, opacityEasing: Motion.Out);
        _pageMotion = new Tween(_pageHost, transform: true, opacityEasing: Motion.Out, transformEasing: Motion.Out);
        _dialogLayer.Children.Add(_dialog);
        _dialogLayer.PointerPressed += (_, e) =>
        {
            if (e.Source == _dialogLayer && _dialogDismissable) { CloseDialog(); e.Handled = true; }
        };

        var root = new Panel();
        root.Children.Add(Art);
        root.Children.Add(_pageHost);
        root.Children.Add(_sheet);
        root.Children.Add(Bar);
        root.Children.Add(_cover);
        root.Children.Add(_drawerLayer);
        root.Children.Add(_dialogLayer);
        root.Children.Add(_toasts);
        root.Children.Add(ResizeGrips());
        Content = root;

        Art.Show(LauncherState.SelectedVersion);
        Navigate(PageKey.Home);

        StartupIntro.Play(this); // Overlays/StartupIntro.cs: the opening animation over the built Home (none when Motion.Off)

        // The bar's right group: each opens its own overlay (Overlays/).
        Bar.AccountRequested += () => OpenDrawer(new AccountDrawer(this));
        Bar.FriendsRequested += () => OpenDrawer(new FriendsDrawer(this));
        Bar.ImportRequested += () => ImportView.Open(this);
        Bar.UpdateRequested += () => UpdatePrompt.Open(this);
        // A click whose setting couldn't be saved (settings.json busy): one plain toast, never a crash (WPF twin).
        OriginLauncher.App.Core.SettingsStore.SaveRefused += () => Avalonia.Threading.Dispatcher.UIThread.Post(() =>
            Toast(OriginLauncher.App.Core.SettingsStore.BusyLine, Tone.Warning));
        // Background work a real run starts once the window is up (never in a render run: no network there).
        Opened += (_, _) =>
        {
            if (Shots.ShotRunner.Active) return;
            DesktopEntry.ListenForSecondStart();
            ImportView.CheckFirstRun(this); // Home's bring-over button, until the player answers it
            ProfilesPage.IsProfileInUse = Home.Launches.IsProfileInUse;
            // The import report's "Fix Compatibility" on a profile that can't start (WPF MainWindow does the same):
            // Import closes, Home selects that profile and the same prompt Play opens shows what the fix would change.
            OriginLauncher.App.UI.Controls.ImportHooks.FixCompatibility = (version, profileId) =>
            {
                CloseSheet();
                LauncherState.Write(s => OriginLauncher.App.Core.Import.Launcher.ImportSettingsRules.SelectImported(s, profileId, version));
                Navigate(PageKey.Home);
                Home.Refresh();
                return FixCompatibility.Open(this, version, profileId);
            };
            UpdatePrompt.StartChecks(this);
            FriendsDrawer.StartSocial(this);
            // "Update and play" (JL-5): the Play the required update refused starts now, with no second click.
            if (OriginLauncher.App.Core.Updates.UpdateService.TakePlayAfterUpdate() is { } play)
                _ = Home.Launches.ResumeAfterUpdateAsync(play);
        };
        LauncherState.SettingsChanged += () => { if (Page == PageKey.Home) Art.Show(LauncherState.SelectedVersion); };
        // The Java arguments and resolution boxes save on focus loss, which closing the window never raises (WP-5).
        Closing += (_, _) => { if (_pages.TryGetValue(PageKey.Settings, out var settings)) (settings as SettingsPage)?.FlushEdits(); };
        SizeChanged += (_, _) => Bar.Fit(Bounds.Width);
    }

    // ---- Pages ----

    public T PageOf<T>(PageKey key) where T : Control => (T)GetPage(key);

    private Control GetPage(PageKey key)
    {
        if (_pages.TryGetValue(key, out var page)) return page;
        page = key switch
        {
            PageKey.Home => new HomePage(this),
            PageKey.Mods => new ModsPage(this),
            PageKey.Profiles => new ProfilesPage(this),
            _ => new SettingsPage(this),
        };
        _pages[key] = page;
        _pageHost.Children.Add(page);
        return page;
    }

    public void Navigate(PageKey key)
    {
        var from = Page;
        var page = GetPage(key);
        foreach (var (k, p) in _pages) p.IsVisible = k == key;
        Page = key;
        if (_coverUnderBar) _cover.IsVisible = key == PageKey.Home; // the update scene belongs to Home, like WPF's
        if (key != from) AnimatePageIn(key > from ? 1 : -1);
        Art.SetPage(key == PageKey.Home);
        Bar.Select(key);
        if (key != PageKey.Home) CloseSheet();
        (page as IPage)?.Shown();
        PageChanged?.Invoke(key);
    }

    public HomePage Home => PageOf<HomePage>(PageKey.Home);

    // The new page glides in from the clicked tab's side (tab order Home, Mods, Profiles, Settings; +1 = from the
    // right). Short and front-loaded so it is readable on its first frames; a click during it restarts it.
    private void AnimatePageIn(int direction)
    {
        if (Motion.Off) return;
        _pageMotion.Jump(opacity: 0.85, transform: $"translateX({Fmt.N(direction * 10)}px)");
        _pageMotion.To(Motion.Base, opacity: 1, transform: "none");
    }

    private static string Offscreen(double width) => $"translateX({Fmt.N(width)}px)";

    // Runs `done` once the exit motion has had its time, unless the layer opened again meanwhile (its gen moved on).
    private static void AfterExit(TimeSpan exit, Func<int> gen, Action done)
    {
        if (exit <= TimeSpan.Zero) { done(); return; }
        var mine = gen();
        Avalonia.Threading.DispatcherTimer.RunOnce(() => { if (gen() == mine) done(); }, exit);
    }

    // ---- Layers ----

    /// <summary>The sheet layer (the version picker): over the pages, under the bar.</summary>
    public void OpenSheet(Control content, Action? onClosed = null)
    {
        _sheet.Children.Clear();
        _sheet.Children.Add(content);
        _sheet.IsVisible = true;
        _onSheetClosed = onClosed;
    }

    public bool SheetOpen => _sheet.IsVisible;

    public void CloseSheet()
    {
        if (!_sheet.IsVisible) return;
        _sheet.IsVisible = false;
        _sheet.Children.Clear();
        var cb = _onSheetClosed;
        _onSheetClosed = null;
        cb?.Invoke();
    }

    /// <summary>
    /// The cover layer (the launch screen): over the whole window; the bar fades away under it like the WPF top bar
    /// under the launch scene. underBar (the self-update scene): below the bar, which stays usable (minimise, close, the
    /// other pages: WPF keeps its bar over the update scene), and shown only while Home is the page.
    /// </summary>
    public void OpenCover(Control content, bool underBar = false)
    {
        _coverGen++;
        _coverOpen = true;
        _coverUnderBar = underBar;
        _cover.Children.Clear();
        _cover.Children.Add(content);
        _cover.Margin = new Thickness(0, underBar ? Sizes.Bar : 0, 0, 0);
        _cover.IsHitTestVisible = true;
        _coverMotion.Jump(opacity: 1); // the screen appears at once; its stage column rises (LaunchCover)
        _cover.IsVisible = !underBar || Page == PageKey.Home;
        Art.SetLaunching(true);
        SetBarHidden(!underBar);
    }

    public bool CoverOpen => _coverOpen;

    /// <summary>The self-update scene is up: the bar stays usable over it and the other pages can open.</summary>
    public bool CoverIsUpdateScene => _coverOpen && _coverUnderBar;

    /// <summary>Fades the screen out over Motion.Exit; Home takes clicks from the moment it starts leaving.</summary>
    public void CloseCover()
    {
        if (!_coverOpen) return;
        _coverOpen = false;
        _coverUnderBar = false;
        _cover.IsHitTestVisible = false;
        Art.SetLaunching(false);
        SetBarHidden(false);
        _coverMotion.To(Motion.Exit, opacity: 0);
        AfterExit(Motion.Exit, () => _coverGen, () =>
        {
            _cover.IsVisible = false;
            _cover.Children.Clear();
        });
    }

    // Under the launch screen the bar fades away and stops taking clicks; it returns with the screen's exit.
    private void SetBarHidden(bool hidden)
    {
        Bar.IsHitTestVisible = !hidden;
        _barMotion.To(Motion.Ms(hidden ? 200 : 320), opacity: hidden ? 0 : 1);
    }

    /// <summary>The right drawer (account, friends): 380 wide, full height, click outside or Esc closes it.</summary>
    public void OpenDrawer(Control content, Action? onClosed = null)
    {
        _drawerGen++;
        _drawer.Child = content;
        _drawerOpen = true;
        _drawerLayer.IsVisible = true;
        _drawerLayer.IsHitTestVisible = true;
        _onDrawerClosed = onClosed;
        _drawerMotion.To(Motion.Scene, transform: "none");
        _drawerScrimMotion.To(Motion.Scene, opacity: 0.5);
    }

    public bool DrawerOpen => _drawerOpen;
    public Control? DrawerContent => _drawerOpen ? _drawer.Child : null;

    public void CloseDrawer()
    {
        if (!_drawerOpen) return;
        _drawerOpen = false;
        // Clicks reach the page at once (the WPF drawer stops hit-testing as it starts leaving) while it slides out.
        _drawerLayer.IsHitTestVisible = false;
        _drawerMotion.To(Motion.Exit, transform: Offscreen(Sizes.Drawer));
        _drawerScrimMotion.To(Motion.Exit, opacity: 0);
        AfterExit(Motion.Exit, () => _drawerGen, () =>
        {
            _drawerLayer.IsVisible = false;
            _drawer.Child = null;
        });
        var cb = _onDrawerClosed;
        _onDrawerClosed = null;
        cb?.Invoke();
    }

    /// <summary>A dialog (11.10) centred on the scrim. Not dismissable = only its own buttons close it.</summary>
    public void OpenDialog(Control content, bool dismissable = true, Action? onClosed = null, double? width = null)
    {
        _dialogGen++;
        _dialog.Child = content;
        _dialog.MaxWidth = width ?? Sizes.Dialog;
        _dialog.Width = width ?? double.NaN;
        _dialogDismissable = dismissable;
        _dialogOpen = true;
        _dialogLayer.IsVisible = true;
        _dialogLayer.IsHitTestVisible = true;
        _onDialogClosed = onClosed;
        _dialogLayerMotion.To(Motion.Scene, opacity: 1);
        _dialogMotion.To(Motion.Scene, transform: "none");
    }

    public bool DialogOpen => _dialogOpen;
    public Control? DialogContent => _dialogOpen ? _dialog.Child : null;

    public void CloseDialog()
    {
        if (!_dialogOpen) return;
        _dialogOpen = false;
        _dialogLayer.IsHitTestVisible = false;
        _dialogLayerMotion.To(Motion.Exit, opacity: 0);
        _dialogMotion.To(Motion.Exit, transform: "scale(0.97)");
        AfterExit(Motion.Exit, () => _dialogGen, () =>
        {
            _dialogLayer.IsVisible = false;
            _dialog.Child = null;
        });
        var cb = _onDialogClosed;
        _onDialogClosed = null;
        cb?.Invoke();
    }

    /// <summary>A toast (11.10): bottom centre above the strip, caption text, optional action, 5 s.</summary>
    public void Toast(string text, Tone tone = Tone.Info, string? action = null, Action? onAction = null, bool sticky = false)
    {
        var dot = tone switch
        {
            Tone.Success => Palette.Success,
            Tone.Warning => Palette.Warning,
            Tone.Danger => Palette.Danger,
            _ => Palette.Info,
        };
        var row = Ui.Row(Space.S2, Ui.Dot(dot, 8), Ui.Caption(text, Palette.Text));
        Border? toast = null;
        if (action != null)
        {
            var b = Ui.Button(action, Look.Ghost, height: Sizes.ControlSm);
            b.Click += (_, _) => { onAction?.Invoke(); if (toast != null) _toasts.Children.Remove(toast); };
            row.Children.Add(b);
        }
        toast = new Border
        {
            MinHeight = Sizes.ControlMd + Space.S2, Padding = new Thickness(Space.S3, Space.S1),
            Background = Palette.Card, BorderBrush = Palette.LineStrong, BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(Radius.Md), BoxShadow = Palette.OverlayShadow, Child = row,
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        EnterToast(toast);
        _toasts.Children.Add(toast);
        while (_toasts.Children.Count > 3) _toasts.Children.RemoveAt(0);
        if (!sticky && !Motion.Off)
            Avalonia.Threading.DispatcherTimer.RunOnce(() => _toasts.Children.Remove(toast), TimeSpan.FromSeconds(5));
    }

    public void ClearToasts() => _toasts.Children.Clear();

    /// <summary>A toast's entrance (WPF SocialToastHost): opacity 0 -> 1 and 4 px up over Motion.Enter, the rise cubic
    /// ease-out. Call before the card is added; it leaves at once, as Windows' does.</summary>
    public static void EnterToast(Control card)
    {
        if (Motion.Off) return;
        var t = new Tween(card, transform: true, transformEasing: Motion.CubicOut);
        t.Jump(opacity: 0, transform: "translateY(4px)");
        card.AttachedToVisualTree += Enter;
        void Enter(object? s, VisualTreeAttachmentEventArgs e)
        {
            card.AttachedToVisualTree -= Enter;
            t.To(Motion.Enter, opacity: 1, transform: "none");
        }
    }

    /// <summary>A second start of Origin asked for this window (Shell/SingleInstance): bring it back and to the front,
    /// like the Windows launcher's FocusExistingInstance.</summary>
    public void BringForward()
    {
        if (!IsVisible) Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
        App.Core.StartupTrace.Mark("activated by a second start");
    }

    // Where the window opens (UI/WindowFit, shared with Windows and tested there): the designed 1180 x 740 shrunk into
    // the work area of the screen it opens on (the screen minus panels/docks) and centred. Avalonia never shrinks a
    // requested size to the screen, so on a 1366 x 768 laptop with a panel, or 1920 x 1080 at 150 % (1280 x 720 logical),
    // the Play strip opened under the panel and the bar (the only drag handle) could sit off-screen. The minimum size
    // gives way too when the work area is smaller than it. Render runs size the window themselves.
    private void FitToWorkArea()
    {
        try
        {
            if ((Screens.ScreenFromWindow(this) ?? Screens.Primary) is not { } screen) return;
            var scale = screen.Scaling > 0 ? screen.Scaling : 1;
            var wa = screen.WorkingArea;
            var work = new App.UI.WindowFit.Box(wa.X / scale, wa.Y / scale, wa.Width / scale, wa.Height / scale);
            if (App.UI.WindowFit.Centre(work, Width, Height, MinWidth, MinHeight) is not { } fit) return;
            MinWidth = fit.MinWidth;
            MinHeight = fit.MinHeight;
            Width = fit.Window.Width;
            Height = fit.Window.Height;
            WindowStartupLocation = WindowStartupLocation.Manual;
            Position = new PixelPoint((int)Math.Round(fit.Window.Left * scale), (int)Math.Round(fit.Window.Top * scale));
        }
        catch (Exception ex)
        {
            // Placement is cosmetic: on any failure Avalonia's own CenterScreen stands.
            App.Core.StartupTrace.Mark("work-area fit skipped: " + ex.Message);
        }
    }

    // ---- Keys and window chrome ----

    protected override void OnKeyDown(KeyEventArgs e)
    {
        base.OnKeyDown(e);
        if (e.Handled) return;
        if (e.Key != Key.Escape)
        {
            PageKeys(e);
            return;
        }
        if (DialogOpen && _dialogDismissable) CloseDialog();
        else if (DrawerOpen) CloseDrawer();
        else if (SheetOpen) CloseSheet();
        else return;
        e.Handled = true;
    }

    // The Windows launcher's keyboard (WPF MainWindow_PreviewKeyDown; WP-6): Ctrl+1/2/3/4 open Home, Mods, Profiles,
    // Settings and Enter on Home plays. This runs after the focused control had the key (a text box, a dropdown and
    // every Pressable use Enter themselves); the opening intro takes every key before it gets here.
    private void PageKeys(KeyEventArgs e)
    {
        // A dialog or drawer owns the keyboard; the launch cover is modal within Home (no page switch, no second Play).
        if (DialogOpen || DrawerOpen || (CoverOpen && !CoverIsUpdateScene)) return;
        if (e.KeyModifiers == KeyModifiers.Control)
        {
            PageKey? target = e.Key switch
            {
                Key.D1 or Key.NumPad1 => PageKey.Home,
                Key.D2 or Key.NumPad2 => PageKey.Mods,
                Key.D3 or Key.NumPad3 => PageKey.Profiles,
                Key.D4 or Key.NumPad4 => PageKey.Settings,
                _ => null,
            };
            if (target is { } page)
            {
                if (SheetOpen) CloseSheet(); // the version picker closes, like a tab click
                Navigate(page);
                e.Handled = true;
            }
            return;
        }
        if (e.Key == Key.Enter && e.KeyModifiers == KeyModifiers.None && Page == PageKey.Home && !SheetOpen
            && !(e.Source is Avalonia.Visual v && v.GetSelfAndVisualAncestors().Any(a => a is TextBox or Pressable or Dropdown)))
        {
            e.Handled = Home.TryLaunchFromKeyboard();
        }
    }

    /// <summary>Drag the window from the bar's empty space (the bar's controls handle their own presses).</summary>
    public void DragFrom(PointerPressedEventArgs e)
    {
        if (!e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) return;
        if (e.ClickCount == 2)
        {
            WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
            return;
        }
        BeginMoveDrag(e);
    }

    // A chromeless window has no frame to resize from: 6 px invisible grips on every edge and corner.
    private Control ResizeGrips()
    {
        var g = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("6,*,6"),
            RowDefinitions = new RowDefinitions("6,*,6"),
        };
        void Grip(int col, int row, WindowEdge edge, StandardCursorType cursor)
        {
            var b = new Border { Background = Brushes.Transparent, Cursor = new Cursor(cursor) };
            Grid.SetColumn(b, col);
            Grid.SetRow(b, row);
            b.PointerPressed += (_, e) =>
            {
                if (WindowState == WindowState.Normal && e.GetCurrentPoint(this).Properties.IsLeftButtonPressed)
                {
                    BeginResizeDrag(edge, e);
                    e.Handled = true;
                }
            };
            g.Children.Add(b);
        }
        Grip(0, 0, WindowEdge.NorthWest, StandardCursorType.TopLeftCorner);
        Grip(1, 0, WindowEdge.North, StandardCursorType.TopSide);
        Grip(2, 0, WindowEdge.NorthEast, StandardCursorType.TopRightCorner);
        Grip(0, 1, WindowEdge.West, StandardCursorType.LeftSide);
        Grip(2, 1, WindowEdge.East, StandardCursorType.RightSide);
        Grip(0, 2, WindowEdge.SouthWest, StandardCursorType.BottomLeftCorner);
        Grip(1, 2, WindowEdge.South, StandardCursorType.BottomSide);
        Grip(2, 2, WindowEdge.SouthEast, StandardCursorType.BottomRightCorner);
        // The middle cell passes everything through to the window.
        var middle = new Border { IsHitTestVisible = false };
        Grid.SetColumn(middle, 1);
        Grid.SetRow(middle, 1);
        g.Children.Add(middle);
        return g;
    }
}
