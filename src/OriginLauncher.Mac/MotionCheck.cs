using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
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
        _ = MotionFx.Run(row, Visual.OpacityProperty, 0, 1, 220, MotionFx.Out);
        _ = MotionFx.Run(row, shift, TranslateTransform.YProperty, -14, 0, 420, MotionFx.Back);
        var jar = new Border { Width = 20, Height = 20 };
        var scale = new ScaleTransform();
        var lift = new TranslateTransform();
        jar.RenderTransform = new TransformGroup { Children = { scale, lift } };
        host.Children.Add(jar);
        _ = MotionFx.Run(jar, scale, ScaleTransform.ScaleXProperty, 1.14, 1, 420, MotionFx.Back);
        _ = MotionFx.Run(jar, scale, ScaleTransform.ScaleYProperty, 1.14, 1, 420, MotionFx.Back);
        _ = MotionFx.Run(jar, lift, TranslateTransform.YProperty, 0, -6, 260, MotionFx.Back);
        Pump(700);
        ExpectNear("Mods row faded in", () => row.Opacity, 1);
        ExpectNear("Mods row landed", () => shift.Y, 0);
        ExpectNear("drop icon pulse settled (scale)", () => scale.ScaleX, 1);
        ExpectNear("drop icon lifted", () => lift.Y, -6);
        host.Children.Remove(row);
        host.Children.Remove(jar);

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

    // Runs the UI queue and the render clock (which drives animations) for up to ms, or until done says stop.
    private static void Pump(int ms, Func<bool>? done = null)
    {
        var until = DateTime.UtcNow.AddMilliseconds(ms);
        while (DateTime.UtcNow < until && !(done?.Invoke() ?? false))
        {
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
