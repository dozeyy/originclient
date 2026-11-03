using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.VisualTree;
using Avalonia.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.Mac.UI;

/// <summary>
/// `--render-shots &lt;dir&gt;`: every screen the Mac launcher has, drawn headless to PNGs under the SAME file names as the
/// WPF UI/RenderShots.cs, so CI can put each Mac shot next to its Windows twin and list the Windows shots that have no
/// Mac twin yet. No window opens (Avalonia's headless platform with the real Skia renderer), motion is off, the data
/// is made up (version 26.3, signed out, My mods on, a friend's dot lit, like the Windows fixture), nothing is read
/// from or written to the player's data folder, and it exits by itself: 0 when every shot was written, 1 otherwise.
/// Mac differences in the pictures: the traffic lights are drawn by macOS and so are not in a headless frame (the
/// bar's brand starts after their space), and the WPF bar's Minimize / Close are not on the Mac.
/// </summary>
internal static class RenderShots
{
    // The fixture: the Windows run's own default (VersionCatalog.DefaultVersion, 26.3 today).
    private static readonly string Version = VersionCatalog.DefaultVersion;

    public static int Run(string outDir)
    {
        Directory.CreateDirectory(outDir);
        Motion.Off = true;
        AppBuilder.Configure<App>()
            .UseSkia()
            .UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false })
            .SetupWithoutStarting();

        _outDir = outDir;

        WriteFontReport(outDir);
        // Home reads settings, accounts and installs as it is built: all of it from a throwaway folder, never the
        // player's data (OriginPaths stays the one path authority; a scratch root also arms the play-plan probe).
        OriginPaths.UseScratchRoot(Path.Combine(Path.GetTempPath(), "origin-render-shots-" + Guid.NewGuid().ToString("N")));
        // Made-up players' heads stay on the drawn fallback: a shot run never goes online.
        OriginLauncher.App.Core.Skins.PlayerHeads.NetworkEnabled = false;
        // Made-up friends (the WPF run's fixture): the social service in demo mode never signs in or syncs.
        SocialHub.PlayersOnlineOff = true;
        SocialHub.Service.ShowDemo(SocialDemo.Online(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
        var window = new MainWindow { Width = 1044, Height = 740 };
        window.Show();
        window.SetFriendsBadge(true);
        window.Home.MyMods = true;
        window.Home.SetReady(false, "Sign in to play");
        Pump();

        // `--social` (WPF UI/SocialShots.cs): only the Friends drawer, party and chat, in every state (UI/Shots/SocialShots.cs).
        if (Environment.GetCommandLineArgs().Contains("--social"))
        {
            Shots.SocialShots.Run(window, outDir);
            return Finish(window, outDir);
        }

        // 01: Home as the Windows CI run shows it (its desktop caps a real window at 1044 wide).
        Snap(window, "01-home");
        window.Home.SetReady(true, "Ready to play");
        Snap(window, "01b-home-ready");
        var play = window.Home.Play;
        var centre = play.TranslatePoint(new Point(play.Bounds.Width / 2, play.Bounds.Height / 2), window) ?? default;
        window.MouseMove(centre);
        Snap(window, "01c-home-play-hover");
        window.MouseMove(new Point(4, 400));
        window.Home.SetReady(false, "Sign in to play");
        window.Home.MyMods = false;
        Snap(window, "01d-home-my-mods-off");
        window.Home.MyMods = true;
        Snap(window, "01e-home-my-mods-on");

        // 02-06: the Friends drawer, a chat, the party chat, the toasts, then signed out (UI/Shots/SocialShots.cs).
        Shots.SocialShots.MainRun(window, outDir);

        // From here on the Windows run has 1.21.1 picked (its tour selects it before the launch shots), so the 15e/15f
        // twins show the same numeral and art; a different version would make the ink widths incomparable.
        window.SelectVersion("1.21.1");

        // 15e: the top bar's fit rule at three window sizes (the Windows run adds "-update" twins; the Mac has no
        // update chip yet).
        foreach (var (w, h) in new[] { (1440, 900), (1280, 800), (1180, 740) })
        {
            Resize(window, w, h);
            var shot = $"15e-topbar-{w}x{h}";
            File.AppendAllText(Path.Combine(outDir, "topbar-fit.txt"), $"{shot}: {window.TopBarFit()}{Environment.NewLine}");
            Snap(window, shot);
        }

        // 15f: the numeral in its band at the three reference sizes, with the measured ink against the band.
        foreach (var (w, h) in new[] { (960, 600), (1180, 740), (1920, 1080) })
        {
            Resize(window, w, h);
            File.AppendAllText(Path.Combine(outDir, "numeral-fit.txt"), $"{w}x{h}: {window.NumeralFit()}{Environment.NewLine}");
            Snap(window, $"15f-home-numeral-{w}x{h}");
        }

        // The version picker, Settings, the launch flow, the overlays and the crash windows (UI/Shots/AreaAShots.cs).
        Shots.AreaAShots.Run(window, outDir);
        // Mods, Profiles, the account pill and drawer, and the sign-in card (UI/Shots/AreaBShots.cs).
        Shots.AreaBShots.Run(window, outDir);
        // Import from other launchers: the Home welcome card and every Import page screen (UI/Shots/AreaDShots.cs).
        Shots.AreaDShots.Run(window, outDir);
        return Finish(window, outDir);
    }

    private static int Finish(Window window, string outDir)
    {
        window.Close();
        Console.WriteLine($"render-shots: {_written} PNGs in {outDir}");
        foreach (var f in _failed) Console.Error.WriteLine("render-shots: FAILED " + f);
        return _failed.Count == 0 && _written > 0 ? 0 : 1;
    }

    private static string _outDir = "";
    private static int _written;
    private static readonly List<string> _failed = new();

    /// <summary>Saves what <paramref name="w"/> (a window, or a popup's own top level) last drew as name.png.</summary>
    internal static void Snap(TopLevel w, string name)
    {
        try
        {
            Pump();
            using var frame = w.CaptureRenderedFrame();
            if (frame == null) { _failed.Add(name + " (no frame)"); return; }
            frame.Save(Path.Combine(_outDir, name + ".png"));
            _written++;
        }
        catch (Exception ex)
        {
            _failed.Add($"{name} ({ex.GetType().Name}: {ex.Message})");
        }
    }

    /// <summary>
    /// Saves <paramref name="w"/> drawn at <paramref name="scale"/> (1.5 = 150 %): the same layout, rasterised at
    /// 96 x scale DPI, so the PNG is the window's DIP size times scale (the DPI-matrix shots).
    /// </summary>
    internal static void SnapScaled(Window w, string name, double scale)
    {
        // Avalonia's RenderTargetBitmap at a DPI other than 96 draws a Border's child at the scale twice when that
        // Border has a BoxShadow (proven: the picker sheet's content came out 1.5x at 150 %, right with the shadow off).
        // The live window renders through the compositor, not this path, so only the capture needs it: shadows are
        // lifted for the frame and put back. The DPI shots therefore show no drop shadows.
        var lifted = new List<(Border Border, BoxShadows Shadow)>();
        if (scale != 1)
            foreach (var b in w.GetVisualDescendants().OfType<Border>().Where(b => b.BoxShadow.Count > 0))
            {
                lifted.Add((b, b.BoxShadow));
                b.BoxShadow = default;
            }
        try
        {
            Pump();
            var size = new PixelSize((int)Math.Round(w.Bounds.Width * scale), (int)Math.Round(w.Bounds.Height * scale));
            using var target = new RenderTargetBitmap(size, new Vector(96 * scale, 96 * scale));
            target.Render(w);
            target.Save(Path.Combine(_outDir, name + ".png"));
            _written++;
        }
        catch (Exception ex)
        {
            _failed.Add($"{name} ({ex.GetType().Name}: {ex.Message})");
        }
        finally
        {
            foreach (var (b, shadow) in lifted) b.BoxShadow = shadow;
        }
    }

    /// <summary>A picture saved by a shot file itself (a composite).</summary>
    internal static void Count() => _written++;

    /// <summary>A step that could not be pictured: listed as a failure, so the run exits 1.</summary>
    internal static void Fail(string what) => _failed.Add(what);

    /// <summary>
    /// Pump for real time: lets work started off the UI thread (art decodes, a fix plan) finish and post back. Motion
    /// is off, so nothing is ever caught mid-move; this only waits for results.
    /// </summary>
    internal static void PumpFor(int ms)
    {
        var until = DateTime.UtcNow.AddMilliseconds(ms);
        do
        {
            Pump();
            Thread.Sleep(15);
        } while (DateTime.UtcNow < until);
        Pump();
    }

    // fonts.txt: the face each Inter weight the theme uses really resolves to. A weight that falls back to another face
    // gets drawn synthetically (wrong widths, the numeral off-size), and nothing else would say so.
    private static void WriteFontReport(string outDir)
    {
        var lines = new List<string>();
        var family = Application.Current!.TryGetResource("Font.Primary", null, out var f) && f is Avalonia.Media.FontFamily ff
            ? ff : Avalonia.Media.FontFamily.Default;
        foreach (var weight in new[] { 400, 500, 600, 700, 800 })
        {
            var typeface = new Avalonia.Media.Typeface(family, Avalonia.Media.FontStyle.Normal, (Avalonia.Media.FontWeight)weight);
            lines.Add(Avalonia.Media.FontManager.Current.TryGetGlyphTypeface(typeface, out var face)
                ? $"Inter {weight}: {face.FamilyName} weight={(int)face.Weight} simulations={face.FontSimulations}"
                : $"Inter {weight}: NOT FOUND");
        }
        File.WriteAllLines(Path.Combine(outDir, "fonts.txt"), lines);
    }

    internal static void Resize(Window window, int w, int h)
    {
        window.Width = w;
        window.Height = h;
        Pump();
    }

    // Layout, the size-driven follow-ups (fit rule, strip top, art tier) and a render, a few rounds so a change that
    // causes another layout settles before the frame is taken.
    internal static void Pump()
    {
        for (var i = 0; i < 6; i++)
        {
            Dispatcher.UIThread.RunJobs();
            AvaloniaHeadlessPlatform.ForceRenderTimerTick();
        }
    }
}
