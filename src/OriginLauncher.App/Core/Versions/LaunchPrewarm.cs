using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;

namespace OriginLauncher.App.Core.Versions;

// While the player sits on Home with a version picked, read that version's files once so the real
// launch finds them in the OS cache: NTFS metadata for the thousands of asset / library entries that
// the install check stats, and the jars the JVM is about to open (client jar, Origin + perf mods,
// libraries). On a hard drive or a slow laptop disk that moves seconds of cold reads out of the
// Play-to-window wait; on an SSD it costs a fraction of a second at background priority.
//
// Read-only and invisible: nothing is hashed, written, downloaded or marked verified - the real
// launch (InstallVerification: size-checked fast path, full hash weekly or after a crash) is exactly
// as strict as before. Files open with FileShare.ReadWrite | Delete so a mod swap (rename/delete) or
// another account's game is never blocked. Runs on its own thread in Windows' background mode (low
// CPU AND low disk priority), is capped, and is cancelled the moment a launch starts so provisioning
// gets the disk to itself.
public static class LaunchPrewarm
{
    /// <summary>Most file CONTENT read per warm-up (metadata is always walked in full).</summary>
    public const long ContentCapBytes = 384L * 1024 * 1024;

    private const int ReadBuffer = 256 * 1024;
    private static readonly object Gate = new();
    private static CancellationTokenSource? _current;
    private static string? _warmedVersion;

    public readonly record struct Result(int Entries, int FilesRead, long BytesRead, bool Cancelled);

    /// <summary>
    /// Warms <paramref name="version"/> once per launcher run (again after a different version was warmed).
    /// <paramref name="versionRoot"/> is the version's shared root (versions/, libraries/, assets/, runtime/),
    /// <paramref name="modsDir"/> its mods folder. Returns immediately.
    /// </summary>
    public static void Start(string version, string versionRoot, string modsDir)
    {
        lock (Gate)
        {
            if (_warmedVersion == version) return;
            _warmedVersion = version;
            _current?.Cancel();
            var cts = _current = new CancellationTokenSource();
            var thread = new Thread(() => Run(versionRoot, modsDir, version, cts.Token))
            {
                IsBackground = true,
                Name = "launch-prewarm",
                Priority = ThreadPriority.BelowNormal,
            };
            thread.Start();
        }
    }

    /// <summary>A launch is starting: give the disk back right now.</summary>
    public static void Stop()
    {
        lock (Gate)
        {
            _current?.Cancel();
            _current = null;
            _warmedVersion = null; // after a launch the files are warm anyway; a later pick may warm again
        }
    }

    private static void Run(string versionRoot, string modsDir, string version, CancellationToken ct)
    {
        // THREAD_MODE_BACKGROUND_BEGIN: the OS lowers this thread's disk and memory priority too.
        var background = SetThreadPriority(GetCurrentThread(), ThreadModeBackgroundBegin);
        var clock = Stopwatch.StartNew();
        try
        {
            var result = Warm(versionRoot, modsDir, version, ct);
            StartupTrace.Mark($"prewarm {version}: {result.Entries} entries, {result.FilesRead} files / {result.BytesRead / 1024 / 1024} MB read, {clock.ElapsedMilliseconds} ms{(result.Cancelled ? " (cancelled)" : "")}");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // a warm-up is a courtesy; the real launch reads the same files and reports any problem
        }
        finally
        {
            if (background) SetThreadPriority(GetCurrentThread(), ThreadModeBackgroundEnd);
        }
    }

    /// <summary>
    /// The walk itself (public for the tests): stats every entry under versions/, libraries/, assets/ and
    /// runtime/ (the listing already carries size and times, which is what primes the metadata cache),
    /// then reads, up to <see cref="ContentCapBytes"/>, the version's jar, the mods folder's jars and the
    /// library jars, in that order.
    /// </summary>
    public static Result Warm(string versionRoot, string modsDir, string version, CancellationToken ct)
    {
        var entries = 0;
        var jarsToRead = new List<string>();
        var cancelled = false;

        // 1. the version's own folder first: its jar is the file the JVM opens first.
        var versionFolder = Path.Combine(versionRoot, "versions");
        foreach (var file in Enumerate(Path.Combine(versionFolder, version), ct, ref entries))
            if (file.Extension.Equals(".jar", StringComparison.OrdinalIgnoreCase)) jarsToRead.Add(file.FullName);
        // 2. the mods Fabric loads.
        foreach (var file in Enumerate(modsDir, ct, ref entries))
            if (file.Extension.Equals(".jar", StringComparison.OrdinalIgnoreCase)) jarsToRead.Add(file.FullName);
        // 3. libraries (jars read, everything stat'ed), then the loader profiles, the asset objects and Java.
        foreach (var file in Enumerate(Path.Combine(versionRoot, "libraries"), ct, ref entries))
            if (file.Extension.Equals(".jar", StringComparison.OrdinalIgnoreCase)) jarsToRead.Add(file.FullName);
        foreach (var _ in Enumerate(versionFolder, ct, ref entries)) { }
        foreach (var _ in Enumerate(Path.Combine(versionRoot, "assets"), ct, ref entries)) { }
        foreach (var _ in Enumerate(Path.Combine(versionRoot, "runtime"), ct, ref entries)) { }

        var filesRead = 0;
        long bytes = 0;
        var buffer = new byte[ReadBuffer];
        foreach (var jar in jarsToRead)
        {
            if (ct.IsCancellationRequested) { cancelled = true; break; }
            if (bytes >= ContentCapBytes) break;
            try
            {
                using var stream = new FileStream(jar, FileMode.Open, FileAccess.Read,
                    FileShare.ReadWrite | FileShare.Delete, ReadBuffer, FileOptions.SequentialScan);
                int read;
                while ((read = stream.Read(buffer, 0, buffer.Length)) > 0)
                {
                    bytes += read;
                    if (ct.IsCancellationRequested || bytes >= ContentCapBytes) break;
                }
                filesRead++;
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // swapped or locked right now: skip it
            }
        }
        return new Result(entries, filesRead, bytes, cancelled || ct.IsCancellationRequested);
    }

    // Every file below `folder` (none when it doesn't exist), stopping at once when cancelled.
    // FileInfo.Length / LastWriteTime come from the directory listing itself - no extra disk hit.
    private static IEnumerable<FileInfo> Enumerate(string folder, CancellationToken ct, ref int entries)
    {
        var found = new List<FileInfo>();
        try
        {
            if (!Directory.Exists(folder)) return found;
            foreach (var file in new DirectoryInfo(folder).EnumerateFiles("*", SearchOption.AllDirectories))
            {
                if (ct.IsCancellationRequested) break;
                _ = file.Length;
                found.Add(file);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // a folder vanishing mid-walk is fine
        }
        entries += found.Count;
        return found;
    }

    private const uint ThreadModeBackgroundBegin = 0x00010000;
    private const uint ThreadModeBackgroundEnd = 0x00020000;

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentThread();

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetThreadPriority(IntPtr thread, uint priority);
}
