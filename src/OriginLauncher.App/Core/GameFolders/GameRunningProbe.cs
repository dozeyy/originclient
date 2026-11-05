using System.Diagnostics;
using System.IO;
using System.Text;

namespace OriginLauncher.App.Core.GameFolders;

/// <summary>
/// "Is a Minecraft open in this game folder right now?" for a game this launcher run did not start
/// (the launcher was restarted, crashed or self-updated while playing, or another launcher's game).
///
/// Windows: a running game keeps logs/latest.log open, and opening it with FileShare.None fails while
/// any other process holds it. That is a Windows sharing-mode rule. On macOS and Linux .NET emulates
/// FileShare.None with an advisory lock that Java/log4j never takes, so the same open always succeeds
/// and the probe would always say "not running". There the probe looks at the running processes
/// instead: a game whose arguments carry <c>--gameDir &lt;folder&gt;</c> (every launcher that passes
/// Mojang's arguments, Origin included), and also a Java process whose working folder is the folder
/// (Prism and MultiMC start Java there and pass the folder over stdin, not as an argument): Linux reads
/// it from /proc, macOS from one <c>lsof</c> run.
///
/// The process list is read at most once per <see cref="SnapshotTtl"/>: callers ask about many folders
/// in a row (one per version on Play), and one scan answers them all. Any failure reads as "not
/// running", which is what every caller already did before this probe existed.
/// </summary>
public static class GameRunningProbe
{
    internal static readonly TimeSpan SnapshotTtl = TimeSpan.FromSeconds(2);

    private static readonly object Gate = new();
    private static ProcessSnapshot? _snapshot;
    private static DateTime _snapshotAt;

    public static bool IsRunningIn(string gameDir)
    {
        if (string.IsNullOrEmpty(gameDir) || !Directory.Exists(gameDir)) return false;
        return OperatingSystem.IsWindows() ? LatestLogIsLocked(gameDir) : Snapshot().Names(gameDir);
    }

    /// <summary>Forgets the cached process list (tests start and stop a process between asks).</summary>
    internal static void Refresh()
    {
        lock (Gate) _snapshot = null;
    }

    // ---- Windows: the lock probe ---------------------------------------------------------------

    private static bool LatestLogIsLocked(string gameDir)
    {
        var latestLog = Path.Combine(gameDir, "logs", "latest.log");
        if (!File.Exists(latestLog)) return false;
        try
        {
            using var probe = new FileStream(latestLog, FileMode.Open, FileAccess.Read, FileShare.None);
            return false;
        }
        catch (IOException)
        {
            return true;
        }
        catch (UnauthorizedAccessException)
        {
            return false;
        }
    }

    // ---- macOS / Linux: the process list -------------------------------------------------------

    private static ProcessSnapshot Snapshot()
    {
        lock (Gate)
        {
            var now = DateTime.UtcNow;
            if (_snapshot == null || now - _snapshotAt > SnapshotTtl)
            {
                _snapshot = OperatingSystem.IsLinux() ? ReadProc("/proc") : ReadPs();
                _snapshotAt = now;
            }
            return _snapshot;
        }
    }

    /// <summary>
    /// Linux: every process's exact argv from /proc/&lt;pid&gt;/cmdline (NUL-separated, so a folder
    /// with spaces is one argument), and the working folder of the Java ones. Processes that vanish
    /// or belong to another user are skipped.
    /// </summary>
    internal static ProcessSnapshot ReadProc(string procRoot)
    {
        var processes = new List<ProcessInfo>();
        IEnumerable<string> dirs;
        try { dirs = Directory.EnumerateDirectories(procRoot); }
        catch (Exception) { return ProcessSnapshot.Empty; }
        foreach (var dir in dirs)
        {
            var name = Path.GetFileName(dir);
            if (name.Length == 0 || !name.All(char.IsAsciiDigit)) continue;
            try
            {
                var raw = File.ReadAllBytes(Path.Combine(dir, "cmdline"));
                if (raw.Length == 0) continue; // kernel thread or a zombie
                var argv = Encoding.UTF8.GetString(raw).TrimEnd('\0').Split('\0');
                string? cwd = null;
                if (IsJava(argv[0]))
                {
                    try { cwd = new DirectoryInfo(Path.Combine(dir, "cwd")).LinkTarget; }
                    catch (Exception) { /* another user's process: no cwd, argv still counts */ }
                }
                processes.Add(new ProcessInfo(argv, null, cwd));
            }
            catch (Exception)
            {
                // exited between the listing and the read, or not readable: skip it
            }
        }
        return new ProcessSnapshot(processes, StringComparison.Ordinal);
    }

    /// <summary>
    /// macOS (no /proc): one <c>ps</c> run lists every process's whole command line. ps joins the
    /// arguments with spaces, so the match is on the text "--gameDir &lt;folder&gt;" followed by a
    /// space or the end (a folder name may itself hold spaces, e.g. "Application Support"). One
    /// <c>lsof</c> run adds the working folder of every Java process, the Linux /proc cwd check's
    /// twin: without it a Prism or MultiMC game was never seen, and an import copied its worlds while
    /// they were being saved (bug run 11 PROF-10).
    /// </summary>
    private static ProcessSnapshot ReadPs()
    {
        var processes = new List<ProcessInfo>();
        if (RunTool("/bin/ps", ["-axww", "-o", "command="]) is { } commands)
            processes.AddRange(commands.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .Select(line => new ProcessInfo(null, line, null)));
        // -a: both filters at once (a "java*" command AND its cwd entry); -w: no warnings; -F n: one "n<path>" line each.
        if (RunTool("/usr/sbin/lsof", ["-a", "-c", "java", "-d", "cwd", "-w", "-Fn"]) is { } cwds)
            processes.AddRange(LsofCwds(cwds).Select(cwd => new ProcessInfo(null, null, cwd)));
        // APFS is case-insensitive by default: the same folder may be spelled with other casing.
        return new ProcessSnapshot(processes, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>The paths of <c>lsof -F n</c> output: each "n/folder" line (p and f lines name the process and descriptor).</summary>
    internal static IReadOnlyList<string> LsofCwds(string output) =>
        output.Split('\n', StringSplitOptions.RemoveEmptyEntries)
            .Select(line => line.TrimEnd('\r'))
            .Where(line => line.Length > 1 && line[0] == 'n' && line[1] == '/')
            .Select(line => line[1..])
            .ToList();

    // A tool's standard output, or null when it couldn't run or took over 3 s. lsof exits 1 when nothing
    // matched, so the exit code is not read: an empty output simply adds nothing.
    private static string? RunTool(string path, string[] args)
    {
        try
        {
            if (!File.Exists(path)) return null;
            var start = new ProcessStartInfo(path)
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            foreach (var arg in args) start.ArgumentList.Add(arg);
            using var tool = Process.Start(start);
            if (tool == null) return null;
            var read = tool.StandardOutput.ReadToEndAsync();
            _ = tool.StandardError.ReadToEndAsync(); // drained, so a chatty tool never blocks on a full pipe
            if (!tool.WaitForExit(3000))
            {
                try { tool.Kill(); } catch (Exception) { }
                return null;
            }
            return read.Result;
        }
        catch (Exception)
        {
            return null;
        }
    }

    internal static bool IsJava(string argv0)
    {
        var name = Path.GetFileName(argv0);
        return name.StartsWith("java", StringComparison.OrdinalIgnoreCase);
    }

    internal static string Normalize(string dir)
    {
        try { dir = Path.GetFullPath(dir); }
        catch (Exception) { }
        return Path.TrimEndingDirectorySeparator(dir);
    }

    /// <summary>One process: its exact argv (Linux), its joined command line (macOS ps), its working folder.</summary>
    internal sealed record ProcessInfo(IReadOnlyList<string>? Argv, string? CommandLine, string? Cwd);

    internal sealed class ProcessSnapshot(IReadOnlyList<ProcessInfo> processes, StringComparison comparison)
    {
        public static readonly ProcessSnapshot Empty = new(Array.Empty<ProcessInfo>(), StringComparison.Ordinal);

        public bool Names(string gameDir)
        {
            var dir = Normalize(gameDir);
            foreach (var p in processes)
            {
                if (p.Argv != null && ArgvNamesGameDir(p.Argv, dir, comparison)) return true;
                if (p.CommandLine != null && CommandLineNamesGameDir(p.CommandLine, dir, comparison)) return true;
                if (p.Cwd != null && string.Equals(Normalize(p.Cwd), dir, comparison)) return true;
            }
            return false;
        }
    }

    /// <summary>True when the argv holds "--gameDir &lt;dir&gt;" (or "--gameDir=&lt;dir&gt;").</summary>
    internal static bool ArgvNamesGameDir(IReadOnlyList<string> argv, string dir, StringComparison comparison)
    {
        for (var i = 0; i < argv.Count; i++)
        {
            if (argv[i] == "--gameDir" && i + 1 < argv.Count && string.Equals(Normalize(argv[i + 1]), dir, comparison)) return true;
            if (argv[i].StartsWith("--gameDir=", StringComparison.Ordinal)
                && string.Equals(Normalize(argv[i]["--gameDir=".Length..]), dir, comparison)) return true;
        }
        return false;
    }

    /// <summary>
    /// True when a space-joined command line holds "--gameDir &lt;dir&gt;" with the folder ending at a
    /// space or the end of the line, so "…/accounts/abc" never matches a game in "…/accounts/abc-2".
    /// A trailing separator on the folder in the command line is accepted too.
    /// </summary>
    internal static bool CommandLineNamesGameDir(string commandLine, string dir, StringComparison comparison)
    {
        var needle = "--gameDir " + dir;
        for (var at = commandLine.IndexOf(needle, comparison); at >= 0; at = commandLine.IndexOf(needle, at + 1, comparison))
        {
            var end = at + needle.Length;
            if (end < commandLine.Length && (commandLine[end] == '/' || commandLine[end] == '\\')) end++;
            if (end == commandLine.Length || commandLine[end] == ' ') return true;
        }
        return false;
    }
}
