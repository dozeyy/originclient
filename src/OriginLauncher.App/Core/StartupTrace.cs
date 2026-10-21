using System.Diagnostics;
using System.IO;

namespace OriginLauncher.App.Core;

// Cold-start stopwatch, OFF unless the launcher is started with `--startup-trace` or the
// environment variable ORIGIN_STARTUP_TRACE=1. Each Mark records milliseconds since the PROCESS
// started (so runtime + WPF boot before our first line is counted too); Flush writes one block to
// logs/startup-trace.log. Disabled, Mark is a single bool test - nothing to pay on a normal start.
public static class StartupTrace
{
    private static bool _enabled;
    private static DateTime _processStart;
    private static readonly List<string> Lines = new();
    private static readonly object Gate = new();

    public static void Init(string[] args)
    {
        if (Array.IndexOf(args, "--startup-trace") < 0
            && Environment.GetEnvironmentVariable("ORIGIN_STARTUP_TRACE") != "1")
            return;
        _processStart = Process.GetCurrentProcess().StartTime;
        _enabled = true;
        Mark("trace on");
    }

    public static void Mark(string what)
    {
        if (!_enabled) return;
        var ms = (DateTime.Now - _processStart).TotalMilliseconds;
        lock (Gate) Lines.Add($"{ms,8:0} ms  {what}");
    }

    /// <summary>Appends the marks so far (one block per run) to logs/startup-trace.log. Never throws.</summary>
    public static void Flush()
    {
        if (!_enabled) return;
        try
        {
            string[] lines;
            lock (Gate)
            {
                lines = Lines.ToArray();
                Lines.Clear();
            }
            if (lines.Length == 0) return;
            Directory.CreateDirectory(OriginPaths.Logs);
            File.AppendAllText(
                Path.Combine(OriginPaths.Logs, "startup-trace.log"),
                $"--- {DateTime.Now:yyyy-MM-dd HH:mm:ss}{Environment.NewLine}{string.Join(Environment.NewLine, lines)}{Environment.NewLine}");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // a trace is a courtesy
        }
    }
}
