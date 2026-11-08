using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Threading;
using Avalonia.VisualTree;
using OriginLauncher.App.Core;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Shots;

/// <summary>
/// `--motion-check [--root &lt;dir&gt;]` (CI only): the Linux twin of the Mac's --motion-check. Every other headless run
/// (render-shots) switches motion off and drives screens through Tour helpers, so an animation that throws, sticks
/// half-way or ends invisible can't fail CI, and neither can a real click path the shots skip (the v1.0.92 Mac crash
/// shipped exactly that way: Play while signed out). This run keeps motion ON (Motion.Off = false, the startup intro
/// included), is NOT a render run (ShotRunner.Active stays false, so Play takes its real path), and presses the real
/// buttons: Play signed out (the sign-in card), the account pill (Accounts drawer), Friends, quick open/close toggles,
/// every page tab, the version picker, a confirm dialog and a toast. After each step it pumps real frames and checks
/// the layer settled where the screen expects it: shown and fully opaque when open, gone when closed.
/// Headless (no display), scratch data (XDG folders too), no account. Exit 0 = nothing threw and every check held.
/// An open that showed no in-between frame is a WARN, not a failure: it means that layer has no entrance motion yet.
/// </summary>
public static class MotionCheck
{
    private static readonly List<string> Failures = [];
    private static int _checks;

    public static int Run(string? root)
    {
        root = Path.GetFullPath(root ?? Path.Combine(Path.GetTempPath(), "origin-motion-check"));
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
        Directory.CreateDirectory(root);
        foreach (var v in new[] { "XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME" })
            Environment.SetEnvironmentVariable(v, Path.Combine(root, v.ToLowerInvariant()));
        OriginPaths.UseScratchRoot(Path.Combine(root, "OriginLauncher"));
        OriginPaths.EnsureScaffold();
        App.Core.Skins.PlayerHeads.NetworkEnabled = false;
        // A player with no account who is not in the developer offline mode: Play opens the sign-in card.
        SettingsStore.Update(s =>
        {
            s.OfflineTestMode = false;
            s.SelectedVersion = "1.21.1";
        });

        Motion.Off = false;
        DesktopEntry.Builder()
            .UseSkia()
            .UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false })
            .SetupWithoutStarting();
        Dispatcher.UIThread.UnhandledException += (_, e) =>
        {
            Fail("the UI thread threw: " + e.Exception.GetBaseException());
            e.Handled = true;
        };

        Console.WriteLine("Motion check (Linux): launcher animations with motion on, real click paths (headless, scratch data)");
        LauncherState.Reset();
        var window = new MainWindow { Width = 1180, Height = 740 };
        window.Show();
        Pump(1500); // the startup intro runs with motion on and removes itself
        Expect("Home is up after the startup intro", window.Page == PageKey.Home);

        // 1. Play while signed out: the Mac's v1.0.92 crash path, here through the real Play button and LaunchController.
        window.Home.PlayButton.RaiseClick();
        var samples = Sample(900, () => Opacity(window.DialogContent));
        Expect("Play signed out opens the sign-in card", window.DialogOpen);
        Settled("sign-in card", window.DialogContent);
        Moved("sign-in card fade", window.DialogContent, samples);
        window.CloseDialog();
        Pump(900);
        Expect("sign-in card closed", !window.DialogOpen);

        // 2. The account pill: the Accounts drawer, then a quick open/close/open/close that must end closed.
        Drawer(window, "Accounts drawer", () => window.Bar.AccountButton.RaiseClick());
        // 3. Friends, the same.
        Drawer(window, "Friends drawer", () => window.Bar.FriendsButton.RaiseClick());

        // 4. Every page, the way the tabs (and Ctrl+1-4) switch them, and back Home.
        foreach (var page in new[] { PageKey.Mods, PageKey.Profiles, PageKey.Settings, PageKey.Home })
        {
            window.Navigate(page);
            Pump(500);
            Expect($"{page} page shown", window.Page == page);
            Settled($"{page} page", PageControl(window, page));
        }

        // 5. The version picker from Home's version card (the sheet), then closed.
        window.Home.VersionCard.RaiseClick();
        Pump(900);
        Expect("version picker opened from the version card", window.SheetOpen);
        window.CloseSheet();
        Pump(900);
        Expect("version picker closed", !window.SheetOpen);

        // 6. The launcher's confirm dialog, answered by closing it.
        var ask = Confirm.AskAsync(window, "Motion check", "A question with motion on.", "Yes", "No");
        Pump(900);
        Expect("confirm dialog shown", window.DialogOpen);
        Settled("confirm dialog", window.DialogContent);
        window.CloseDialog();
        Pump(900, () => ask.IsCompleted);
        Expect("confirm dialog closed and answered no", !window.DialogOpen && ask is { IsCompletedSuccessfully: true, Result: false });

        // 7. A toast (its timers and any motion run for real here, unlike the shots).
        window.Toast("Motion check toast", Tone.Success);
        Pump(600);
        window.ClearToasts();
        Pump(300);

        window.Close();
        Pump(200);
        Console.WriteLine(Failures.Count == 0
            ? $"PASS  {_checks} motion checks"
            : $"FAIL  {Failures.Count} of {_checks} motion checks");
        return Failures.Count == 0 ? 0 : 1;
    }

    private static void Drawer(MainWindow window, string name, Action open)
    {
        var samples = new List<double>();
        open();
        samples.AddRange(Sample(900, () => Opacity(window.DrawerContent)));
        Expect($"{name} opened", window.DrawerOpen);
        Settled(name, window.DrawerContent);
        Moved($"{name} entrance", window.DrawerContent, samples);
        window.CloseDrawer();
        Pump(900);
        Expect($"{name} closed", !window.DrawerOpen);
        // A newer action replaces an older one, never fights it: four quick toggles end closed.
        open();
        Pump(40);
        window.CloseDrawer();
        Pump(40);
        open();
        Pump(40);
        window.CloseDrawer();
        Pump(900);
        Expect($"{name} closed after a quick open/close/open/close", !window.DrawerOpen);
    }

    private static Control? PageControl(MainWindow window, PageKey key) => key switch
    {
        PageKey.Home => window.PageOf<Pages.HomePage>(key),
        PageKey.Mods => window.PageOf<Pages.ModsPage>(key),
        PageKey.Profiles => window.PageOf<Pages.ProfilesPage>(key),
        _ => window.PageOf<Pages.SettingsPage>(key),
    };

    // What the player sees of a control: its own opacity times every ancestor's, 0 when it or an ancestor is hidden.
    private static double Opacity(Control? control)
    {
        if (control == null) return 0;
        var value = 1.0;
        for (Visual? v = control; v != null; v = v.GetVisualParent())
        {
            if (!v.IsVisible) return 0;
            value *= v.Opacity;
        }
        return value;
    }

    // Open and finished animating: visible, fully opaque, and no render offset left over from an entrance.
    private static void Settled(string what, Control? control)
    {
        Expect($"{what} fully visible ({Opacity(control):0.###})", control != null && Math.Abs(Opacity(control) - 1) < 0.01);
        if (control?.RenderTransform is { } transform)
        {
            var m = transform.Value;
            Expect($"{what} settled in place (offset {m.M31:0.#},{m.M32:0.#}, scale {m.M11:0.###})",
                Math.Abs(m.M31) < 0.5 && Math.Abs(m.M32) < 0.5 && Math.Abs(m.M11 - 1) < 0.01);
        }
    }

    // An entrance either shows an in-between frame, or (Avalonia's headless clock lets Transitions jump straight to their
    // end value, so a sample may never land mid-way) the layer the player sees must carry a running-length transition on
    // its opacity or render transform: the Kit/Tween that moves it. No transition anywhere on the way up = no motion = FAIL.
    private static void Moved(string what, Control? control, List<double> samples)
    {
        if (samples.Any(v => v > 0.01 && v < 0.99))
        {
            Expect($"{what} moved through the middle", true);
            return;
        }
        var longest = TimeSpan.Zero;
        for (Visual? v = control; v != null; v = v.GetVisualParent())
        {
            if (v is not Avalonia.Animation.Animatable { Transitions: { } transitions }) continue;
            foreach (var t in transitions)
                if (t is Avalonia.Animation.TransitionBase tb
                    && (tb.Property == Visual.OpacityProperty || tb.Property == Visual.RenderTransformProperty)
                    && tb.Duration > longest)
                    longest = tb.Duration;
        }
        Expect($"{what}: the layer carries its entrance transition ({longest.TotalMilliseconds:0} ms)", longest > TimeSpan.Zero);
    }

    // Runs the UI queue and the render clock (which drives transitions and animations) for up to ms, or until done.
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

    private static void Fail(string what)
    {
        Failures.Add(what);
        Console.WriteLine("  FAIL  " + what);
    }
}
