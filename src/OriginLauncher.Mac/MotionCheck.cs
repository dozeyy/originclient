using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.Mac.UI;
using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac;

/// <summary>
/// `--motion-check [--root &lt;dir&gt;]`: the launcher's animations, run for real (headless, in a scratch data folder,
/// no network, no window). Every other headless run (render-shots, play-plan) switches motion off, so a broken
/// animation could never fail CI: the v1.0.92 crash (Play while signed out threw "Unable to cast ScaleTransform to
/// Visual" from the sign-in card's scale animation) shipped that way. This run keeps motion ON and walks the paths a
/// player does: Play signed out (opens the sign-in card), close it, the Accounts drawer, the Friends drawer, a popup
/// menu, and each MotionFx shape the Mods page uses. Exit 0 = every animation ran (none fell back), nothing threw, and
/// every value settled where the screen expects it; 1 = something failed (each failure is printed).
/// </summary>
internal static class MotionCheck
{
    private static readonly List<string> Failures = new();
    private static int _checks;
    private static Window? _window;

    public static int Run(string? root)
    {
        root = Path.GetFullPath(root ?? Path.Combine(Path.GetTempPath(), "origin-motion-check"));
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
        OriginPaths.UseScratchRoot(root);
        // A player with no account who is not in the developer offline mode: Play opens the sign-in card (JL-7).
        SettingsStore.Update(s =>
        {
            s.OfflineTestMode = false;
            s.SelectedVersion = "1.21.1";
        });

        Motion.Off = false;
        // No network: Home's "N playing Origin" count stays off (TourShowPlayersOnline checks the pill instead).
        OriginLauncher.App.Core.Social.Launcher.SocialHub.PlayersOnlineOff = true;
        AppBuilder.Configure<App>()
            .UseSkia()
            .UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false })
            .SetupWithoutStarting();
        AvaloniaSynchronizationContext.InstallIfNeeded();
        Dispatcher.UIThread.UnhandledException += (_, e) =>
        {
            Fail("the UI thread threw: " + e.Exception.GetBaseException());
            e.Handled = true;
        };

        Console.WriteLine("Motion check: launcher animations with motion on (headless, scratch data, no network)");
        var window = new MainWindow { Width = 1044, Height = 740 };
        _window = window;
        window.Show();
        Pump(300);

        // 1. Play while signed out: the exact v1.0.92 crash path (HomePage.LaunchAsync -> SignInRequested ->
        //    OpenSignInPanel -> SetSignInPanelOpen -> MotionFx on the card's ScaleTransform).
        var press = window.Home.PressPlayForPlan();
        Pump(900, () => press.IsCompleted);
        if (press.Exception is { } thrown) Fail("Play signed out threw: " + thrown.GetBaseException());
        var openSamples = Sample(900, () => window.MotionSignIn.ScaleX);
        ExpectSoon("sign-in card shown after Play signed out", () => window.MotionSignIn.Visible);
        ExpectNear("sign-in card opacity after opening", () => window.MotionSignIn.Opacity, 1);
        ExpectNear("sign-in card scale X after opening", () => window.MotionSignIn.ScaleX, 1);
        ExpectNear("sign-in card scale Y after opening", () => window.MotionSignIn.ScaleY, 1);
        Moved("sign-in card scale", openSamples, 0.97, 1);

        window.TourCloseSignIn();
        Pump(900);
        ExpectSoon("sign-in card hidden after closing", () => !window.MotionSignIn.Visible);
        ExpectNear("sign-in card scale X after closing", () => window.MotionSignIn.ScaleX, 0.97);

        // 2. The Accounts drawer, open then closed (the same TranslateTransform shape as the Friends drawer).
        window.TourSetAccountOpen(true);
        var drawerSamples = Sample(900, () => window.MotionAccountPanel.ShiftX);
        ExpectSoon("Accounts drawer shown", () => window.MotionAccountPanel.Visible);
        ExpectNear("Accounts drawer slid fully in", () => window.MotionAccountPanel.ShiftX, 0);
        ExpectNear("scrim dimmed behind the Accounts drawer", () => window.MotionScrimOpacity, 0.5);
        Moved("Accounts drawer slide", drawerSamples, Res.D("Size.Drawer"), 0);
        window.TourSetAccountOpen(false);
        Pump(900);
        ExpectSoon("Accounts drawer hidden after closing", () => !window.MotionAccountPanel.Visible);

        // 3. The Friends drawer, open then closed, then open and closed again before it settles (a newer run must
        //    replace the older one, never fight it).
        window.TourSetFriendsOpen(true);
        Pump(900);
        ExpectSoon("Friends drawer shown", () => window.MotionFriendsDrawer.Visible);
        ExpectNear("Friends drawer slid fully in", () => window.MotionFriendsDrawer.ShiftX, 0);
        window.TourSetFriendsOpen(false);
        Pump(60);
        window.TourSetFriendsOpen(true);
        Pump(60);
        window.TourSetFriendsOpen(false);
        Pump(900);
        ExpectSoon("Friends drawer hidden after a quick open/close/open/close", () => !window.MotionFriendsDrawer.Visible);
        ExpectNear("Friends drawer slid fully out", () => window.MotionFriendsDrawer.ShiftX, Res.D("Size.Drawer"));
        ExpectNear("scrim cleared after the drawers closed", () => window.MotionScrimOpacity, 0);

        // 4. A popup menu (opacity + its card's TranslateTransform).
        var menu = new PopupMenu(window.MotionPopupHost);
        menu.AddItem("Check", "Icon.Check", () => { });
        menu.Open(window.MotionPopupHost);
        Pump(500);
        ExpectNear("popup menu faded in", () => menu.Card.Opacity, 1);
        ExpectNear("popup menu rose into place", () => (menu.Card.RenderTransform as TranslateTransform)?.Y ?? double.NaN, 0);
        menu.Close();
        Pump(200);

        // 5. The Mods page's shapes, on a stand-in control in the window: a row landing (its own TranslateTransform)
        //    and the drop icon's pulse (a Scale + Translate pair inside one TransformGroup).
        var host = window.MotionPopupHost;
        var row = new Border { Width = 40, Height = 10 };
        var shift = new TranslateTransform();
        row.RenderTransform = shift;
        host.Children.Add(row);
        // Every run is waited for to its end (a slow runner's clock can lag the wall clock), then its end value checked.
        var runs = new List<Task>
        {
            MotionFx.Run(row, Visual.OpacityProperty, 0, 1, 220, MotionFx.Out),
            MotionFx.Run(row, shift, TranslateTransform.YProperty, -14, 0, 420, MotionFx.Back),
        };
        var jar = new Border { Width = 20, Height = 20 };
        var scale = new ScaleTransform();
        var lift = new TranslateTransform();
        jar.RenderTransform = new TransformGroup { Children = { scale, lift } };
        host.Children.Add(jar);
        runs.Add(MotionFx.Run(jar, scale, ScaleTransform.ScaleXProperty, 1.14, 1, 420, MotionFx.Back));
        runs.Add(MotionFx.Run(jar, scale, ScaleTransform.ScaleYProperty, 1.14, 1, 420, MotionFx.Back));
        runs.Add(MotionFx.Run(jar, lift, TranslateTransform.YProperty, 0, -6, 260, MotionFx.Back));
        Pump(5000, () => runs.All(t => t.IsCompleted));
        Expect("the Mods page shapes finished", runs.All(t => t.IsCompletedSuccessfully));
        ExpectNear("Mods row faded in", row.Opacity, 1);
        ExpectNear("Mods row landed", shift.Y, 0);
        ExpectNear("drop icon pulse settled (scale)", scale.ScaleX, 1);
        ExpectNear("drop icon lifted", lift.Y, -6);
        host.Children.Remove(row);
        host.Children.Remove(jar);

        // 6. A drawer opened and closed faster than its slide (a double click on the pill): it settles closed and out
        //    of view, so the next open slides and fades again (MAC-7).
        window.TourSetAccountOpen(true);
        Pump(50);
        window.TourSetAccountOpen(false);
        Pump(3000, () => !window.MotionAccountPanel.Visible && window.MotionScrimOpacity < 0.001);
        Expect("Accounts drawer hidden after a quick open/close", !window.MotionAccountPanel.Visible);
        ExpectNear("Accounts drawer slid fully out after a quick open/close", window.MotionAccountPanel.ShiftX, Res.D("Size.Drawer"));
        ExpectNear("scrim cleared after a quick open/close", window.MotionScrimOpacity, 0);

        // 7. Tab switches glide the page in from the side of the tab (the WPF AnimatePageIn: 0.85 -> 1, 10 px -> 0).
        window.TourNavigate(1);
        // The entrance starts from its first values on the click (a page that just appeared would read 1 and 0 here).
        ExpectNear("page entrance starts at 0.85 opacity", window.MotionPage.Opacity, 0.85);
        ExpectNear("page entrance starts 10 px toward the clicked tab", window.MotionPage.ShiftX, 10);
        var pageSamples = Sample(500, () => window.MotionPage.Opacity);
        Pump(3000, () => window.MotionPage.Opacity > 0.999 && Math.Abs(window.MotionPage.ShiftX) < 0.001);
        Expect("Mods page shown by its tab", window.MotionCurrentPage == MainWindow.LauncherPage.Mods);
        ExpectNear("page faded fully in", window.MotionPage.Opacity, 1);
        ExpectNear("page slid into place", window.MotionPage.ShiftX, 0);
        Moved("page entrance fade", pageSamples, 0.85, 1);
        window.TourNavigate(0);
        Pump(3000, () => window.MotionPage.Opacity > 0.999);
        ExpectNear("Home faded fully in", window.MotionPage.Opacity, 1);

        // 8. The bar fades away under the launch screen and comes back with it (WPF SetTopBarHidden, 200 / 320 ms).
        window.TourSetTopBarHidden(true);
        var barSamples = Sample(500, () => window.MotionBar.Bar);
        Pump(3000, () => window.MotionBar.Bar < 0.001 && window.MotionBar.Glass < 0.001);
        ExpectNear("bar faded out under the launch screen", window.MotionBar.Bar, 0);
        ExpectNear("bar glass faded out under the launch screen", window.MotionBar.Glass, 0);
        Expect("bar takes no clicks under the launch screen", !window.MotionBar.TakesClicks);
        Moved("bar fade", barSamples, 1, 0);
        window.TourSetTopBarHidden(false);
        Pump(3000, () => window.MotionBar.Bar > 0.999 && window.MotionBar.Glass > 0.999);
        ExpectNear("bar back after the launch screen", window.MotionBar.Bar, 1);
        ExpectNear("bar glass back on Home", window.MotionBar.Glass, 1);

        // 9. The bar's links reach the browser (MAC-1: Website and Store did nothing on a Mac).
        var opened = new List<string>();
        var realOpen = MainWindow.OpenLink;
        MainWindow.OpenLink = u => opened.Add(u?.AbsoluteUri ?? "");
        window.TourWebsiteButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        window.TourStoreButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        MainWindow.OpenLink = realOpen;
        Expect($"Website and Store open their pages ({string.Join(", ", opened)})",
            opened.SequenceEqual(new[] { "https://originclient.org/", "https://originclient.org/store" }));

        // 10. Removing an account asks first; closing the drawer answers it with Cancel (PAR3-1).
        window.TourSetAccountOpen(true);
        Pump(400);
        window.TourAskRemoveAccount("Alex", true);
        Pump(50);
        Expect("Remove asks first", window.MotionRemoveQuestionShown);
        window.TourSetAccountOpen(false);
        Pump(400);
        Expect("closing the drawer closes the Remove question", !window.MotionRemoveQuestionShown);

        // 11. The menu bar: Edit, View and Window in the window's menu, Settings... in the app menu (MAC-11), and the
        //     Edit items reaching a focused text box (MAC-2's routing; the sign-in page half needs a real Mac).
        var menus = NativeMenu.GetMenu(window)?.Items.OfType<NativeMenuItem>().Select(i => i.Header).ToList() ?? new();
        Expect($"window menus Edit, View, Window ({string.Join(", ", menus)})", menus.SequenceEqual(new[] { "Edit", "View", "Window" }));
        var appMenu = Application.Current is { } app
            ? NativeMenu.GetMenu(app)?.Items.OfType<NativeMenuItem>().Select(i => i.Header).ToList() ?? new()
            : new List<string?>();
        Expect($"app menu has Settings... ({string.Join(", ", appMenu)})", appMenu.Contains("Settings..."));
        var box = new TextBox { Text = "edit me", Width = 120 };
        host.Children.Add(box);
        Pump(50);
        box.Focus();
        window.TourEditMenu("SelectAll");
        Expect($"Edit > Select All reached the focused text box (went to {window.EditRoutedTo})",
            window.EditRoutedTo == "textbox" && box.SelectionStart == 0 && box.SelectionEnd == box.Text.Length);
        host.Children.Remove(box);

        // 12. App menu > Settings... from an open drawer: the drawer closes and Settings opens (MAC-11).
        window.TourSetAccountOpen(true);
        Pump(400);
        window.OpenSettingsFromMenu();
        Pump(3000, () => !window.MotionAccountPanel.Visible);
        Expect("Settings... opened the Settings page", window.MotionCurrentPage == MainWindow.LauncherPage.Settings);
        Expect("Settings... closed the Accounts drawer", !window.MotionAccountPanel.Visible);
        window.TourNavigate(0);
        Pump(400);

        // 13. Full screen moves the brand to the WPF bar's inset; back to windowed, after the traffic lights (MAC-12).
        window.WindowState = WindowState.FullScreen;
        Pump(100);
        ExpectNear("brand inset in full screen", window.BrandInset, 18);
        window.WindowState = WindowState.Normal;
        Pump(100);
        ExpectNear("brand inset beside the traffic lights", window.BrandInset, 72);

        // 14. Moved to a Retina screen without a resize: the art picks the sharper tier (MAC-9).
        var tierAt1 = window.MotionArtTier;
        window.SetRenderScaling(2);
        Pump(300);
        var tierAt2 = window.MotionArtTier;
        window.SetRenderScaling(1);
        Pump(300);
        Expect($"art tier follows the screen's scale (1x {tierAt1}, 2x {tierAt2})", tierAt1 > 0 && tierAt2 > tierAt1);

        // 15. The window fits a small screen: a 1024 x 640 point MacBook with the Dock leaves 1024 x 615 (MAC-5).
        var (w0, h0, mw0, mh0) = (window.Width, window.Height, window.MinWidth, window.MinHeight);
        window.FitTo(new PixelRect(0, 25, 1024, 615), 1);
        Expect($"window fits a 1024 x 615 visible area ({window.Width:0} x {window.Height:0}, min {window.MinWidth:0} x {window.MinHeight:0})",
            window.Width <= 1024 && window.Height <= 615 && window.MinHeight <= 615);
        window.FitTo(new PixelRect(0, 25, 1512, 919), 1);
        Expect($"window keeps its designed size on a 1512 x 919 area ({window.Width:0} x {window.Height:0})",
            window.Width == 1180 && window.Height == 740 && window.MinWidth == 960 && window.MinHeight == 600);
        (window.Width, window.Height, window.MinWidth, window.MinHeight) = (w0, h0, mw0, mh0);

        // 16. Hover labels: the Windows timing and placement (PAR3-13), and Home's "N playing Origin" (PAR3-5).
        var labelled = window.TourImportButton; // an icon button with a hover label ("Import")
        Expect($"hover labels open after 300 ms ({ToolTip.GetShowDelay(labelled)})", ToolTip.GetShowDelay(labelled) == 300);
        Expect($"hover labels open at once for 700 ms after one closes ({ToolTip.GetBetweenShowDelay(labelled)})", ToolTip.GetBetweenShowDelay(labelled) == 700);
        Expect("hover labels follow the shared placement rule", ToolTip.GetPlacement(labelled) == PlacementMode.Custom);
        window.Home.TourShowPlayersOnline(1284);
        Expect($"Home shows the players-online count ({window.Home.TourPlayersText})", window.Home.TourPlayersText == "1,284 playing Origin");
        window.Home.TourShowPlayersOnline(null);

        // A transform property handed the transform itself (the v1.0.92 mistake) is refused at once, not at run time.
        _checks++;
        try
        {
            MotionFx.Run(scale, ScaleTransform.ScaleXProperty, 1, 1, 10);
            Fail("MotionFx accepted a transform as the animation target");
        }
        catch (ArgumentException) { }

        _checks++;
        if (MotionFx.Fallbacks != 0) Fail($"{MotionFx.Fallbacks} animation(s) could not run and jumped to their end value");

        window.Close();
        Pump(100);
        Console.WriteLine(Failures.Count == 0
            ? $"PASS  {_checks} motion checks"
            : $"FAIL  {Failures.Count} of {_checks} motion checks");
        return Failures.Count == 0 ? 0 : 1;
    }

    // Runs the UI queue and the render clock (which drives animations) for up to ms, or until done says stop. Each step
    // asks for an animation frame first: the headless render timer stops itself when the render loop reports nothing to
    // do, and a forced tick on a stopped timer does nothing, so an animation started at that moment never advanced (seen
    // on the Apple Silicon runner as a Mods row frozen 0.7 px short; reproduced on Linux with a tight pump). A real
    // window's timer is restarted by the next frame request, which every animation and input makes.
    private static void Pump(int ms, Func<bool>? done = null)
    {
        var until = DateTime.UtcNow.AddMilliseconds(ms);
        while (DateTime.UtcNow < until && !(done?.Invoke() ?? false))
        {
            _window?.RequestAnimationFrame(_ => { });
            AvaloniaHeadlessPlatform.ForceRenderTimerTick();
            Dispatcher.UIThread.RunJobs();
            Thread.Sleep(15);
        }
        Dispatcher.UIThread.RunJobs();
    }

    // Pumps for ms and returns every value read on the way (to prove the value moved through the middle, not jumped).
    private static List<double> Sample(int ms, Func<double> read)
    {
        var samples = new List<double>();
        var until = DateTime.UtcNow.AddMilliseconds(ms);
        while (DateTime.UtcNow < until)
        {
            _window?.RequestAnimationFrame(_ => { });
            AvaloniaHeadlessPlatform.ForceRenderTimerTick();
            Dispatcher.UIThread.RunJobs();
            samples.Add(read());
            Thread.Sleep(15);
        }
        Dispatcher.UIThread.RunJobs();
        return samples;
    }

    private static void Expect(string what, bool ok)
    {
        _checks++;
        if (ok) Console.WriteLine("  ok    " + what);
        else Fail(what);
    }

    // Waits (pumping the UI and the render clock) until the value is there or 5 s pass, then checks it: a busy CI runner's
    // headless clock can lag well behind wall time, so a fixed wait would fail a healthy animation.
    private static void ExpectNear(string what, Func<double> read, double expected)
    {
        Pump(5000, () => Math.Abs(read() - expected) < 0.01);
        var actual = read();
        Expect($"{what} ({actual:0.###}, expected {expected:0.###})", Math.Abs(actual - expected) < 0.01);
    }

    // A value already settled (its animation was waited for): checked as it is now.
    private static void ExpectNear(string what, double actual, double expected) =>
        Expect($"{what} ({actual:0.###}, expected {expected:0.###})", Math.Abs(actual - expected) < 0.01);

    private static void ExpectSoon(string what, Func<bool> ok)
    {
        Pump(5000, ok);
        Expect(what, ok());
    }

    // An animation that ran shows at least one value strictly between its ends. A headless clock that delivers no
    // frame between the two ends would not show one, so a missing middle is a warning, not a failure: the crash
    // itself is caught by Fallbacks and the thrown-exception checks.
    private static void Moved(string what, List<double> samples, double from, double to)
    {
        var lo = Math.Min(from, to) + 0.001;
        var hi = Math.Max(from, to) - 0.001;
        if (samples.Any(v => v > lo && v < hi)) Console.WriteLine($"  ok    {what} moved through the middle");
        else Console.WriteLine($"  WARN  {what}: no in-between frame seen ({samples.Count} samples)");
    }

    private static void Fail(string what)
    {
        Failures.Add(what);
        Console.WriteLine("  FAIL  " + what);
    }
}
