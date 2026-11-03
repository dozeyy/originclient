using System.Diagnostics;
using System.Reflection;
using System.Text;
using Avalonia;
using Avalonia.Headless;
using Avalonia.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Shots;

// `--render-shots <dir> [--only <prefix,prefix>]`: every screen and state as PNGs, the Linux/Mac twin of the
// Windows launcher's UI/RenderShots.cs. Headless Avalonia with the real Skia renderer: no window, no display
// server, no clicks, no mouse. All data is made up (Shots/Demo.cs) in a fresh temp scratch root (the XDG
// folders point there too), the network is off, motion is off (every state renders settled), and the run
// exits by itself. Each shot gets a fresh MainWindow over freshly seeded data, so no shot leaks into the next.
// Writes <dir>/<name>.png per shot, <dir>/shots.txt (name, size, ms) and <dir>/render-error.txt on failures
// (exit code 1 then; every other shot is still rendered).
public static class ShotRunner
{
    /// <summary>True during a render run: controllers start no network work, timers or real launches.</summary>
    public static bool Active { get; private set; }

    public static int Run(string outDir, string[] args)
    {
        Directory.CreateDirectory(outDir);
        var scratch = Path.Combine(Path.GetTempPath(), "origin-shots-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(scratch);
        // Nothing may resolve a real per-user folder in a render run: the XDG roots go to the scratch folder too.
        foreach (var v in new[] { "XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME" })
            Environment.SetEnvironmentVariable(v, Path.Combine(scratch, v.ToLowerInvariant()));
        var errors = new StringBuilder();
        try
        {
            OriginPaths.UseScratchRoot(Path.Combine(scratch, "OriginLauncher"));
            OriginPaths.EnsureScaffold();
            App.Core.Skins.PlayerHeads.NetworkEnabled = false;
            Motion.Off = true;
            Active = true;

            DesktopEntry.Builder()
                .UseSkia()
                .UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false })
                .SetupWithoutStarting();

            var only = Arg(args, "--only")?.Split(',', StringSplitOptions.RemoveEmptyEntries) ?? [];
            var shots = Discover()
                .Where(s => only.Length == 0 || only.Any(p => s.Name.StartsWith(p, StringComparison.Ordinal)))
                .ToList();
            var log = new StringBuilder();
            foreach (var shot in shots)
            {
                var watch = Stopwatch.StartNew();
                try
                {
                    Render(shot, Path.Combine(outDir, shot.Name + ".png"));
                    log.AppendLine($"{shot.Name}\t{shot.Width}x{shot.Height}\t{watch.ElapsedMilliseconds} ms");
                }
                catch (Exception ex)
                {
                    errors.AppendLine($"== {shot.Name}\n{ex}\n");
                    log.AppendLine($"{shot.Name}\tFAILED");
                }
            }
            File.WriteAllText(Path.Combine(outDir, "shots.txt"), log.ToString());
            Console.WriteLine($"render-shots: {shots.Count} shots, {(errors.Length == 0 ? "no" : "some")} failures, in {outDir}");
        }
        catch (Exception ex)
        {
            errors.AppendLine(ex.ToString());
        }
        finally
        {
            try { Directory.Delete(scratch, recursive: true); } catch (Exception) { /* temp: the OS cleans it */ }
        }
        if (errors.Length == 0) return 0;
        File.WriteAllText(Path.Combine(outDir, "render-error.txt"), errors.ToString());
        Console.Error.WriteLine(errors.ToString());
        return 1;
    }

    private static string? Arg(string[] args, string name)
    {
        var i = Array.IndexOf(args, name);
        return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
    }

    /// <summary>Every [ShotSet] method's shots, in name order (names start with a number, like the Windows set).</summary>
    private static IEnumerable<Shot> Discover() =>
        typeof(ShotRunner).Assembly.GetTypes()
            .SelectMany(t => t.GetMethods(BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic))
            .Where(m => m.GetCustomAttribute<ShotSetAttribute>() != null)
            .SelectMany(m => (IEnumerable<Shot>)m.Invoke(null, null)!)
            .OrderBy(s => s.Name, StringComparer.Ordinal);

    private static void Render(Shot shot, string path)
    {
        Demo.Reset();
        shot.Seed?.Invoke();
        LauncherState.Reset();
        var window = new MainWindow { Width = shot.Width, Height = shot.Height };
        try
        {
            window.Show();
            Pump();
            shot.Setup(window);
            Pump();
            using var frame = window.CaptureRenderedFrame() ?? throw new InvalidOperationException("no frame rendered");
            frame.Save(path);
        }
        finally
        {
            window.Close();
            Pump();
        }
    }

    /// <summary>Let layout, bindings and posted work settle (motion is off, so nothing animates).</summary>
    public static void Pump(int rounds = 3)
    {
        for (var i = 0; i < rounds; i++)
        {
            Dispatcher.UIThread.RunJobs();
            AvaloniaHeadlessPlatform.ForceRenderTimerTick();
        }
        Dispatcher.UIThread.RunJobs();
    }
}
