using System.Diagnostics;
using CmlLib.Core;
using CmlLib.Core.Files; // kept beside CmlLib.Core: RetryingGameInstaller names ByteProgress with both in scope
using CmlLib.Core.Installers;

namespace OriginLauncher.App.Core.Versions;

/// <summary>
/// Coalesces CmlLib's per-file and per-byte install events into at most a few UI updates per
/// second.
///
/// WHY THIS EXISTS: CmlLib fires FileProgress twice per file (once Queued, once
/// Done). A warm launch moves on the order of 7,000-15,000 files, so the old
/// handler produced tens of thousands of events, and each one did a string
/// interpolation, posted to the WPF dispatcher, and — because it chained into a
/// second Progress&lt;string&gt; — posted a SECOND time, then reassigned a TextBlock
/// and invalidated layout. That flood queued onto the very UI thread that was
/// also running the asset enumeration and the natives unzip, which is what made
/// the overlay stutter and the window feel hung.
///
/// Here the hot path is a counter update and a Stopwatch read: no allocation, no
/// dispatcher traffic. A string is built and forwarded only when the throttle
/// interval has elapsed, so UI cost is bounded by time instead of by file count.
///
/// WORDING (bug run 5, JL-8): while real bytes are coming down the line reads
/// "Downloading Minecraft (120/540 MB)" and the bar follows the megabytes; a first
/// download of 500 MB+ used to read "Verifying game files (412/3900)" and sat still
/// on the big client jar. Only a pass that downloads (almost) nothing keeps the
/// file count under the caller's label ("Verifying game files", "Preparing game files").
/// </summary>
public sealed class InstallProgressThrottle
{
    // ~8 updates/sec: fast enough to read as live movement, far below the rate
    // at which WPF text layout starts costing real time.
    private static readonly TimeSpan Interval = TimeSpan.FromMilliseconds(125);

    /// <summary>The words while bytes are downloading (the overlay's bar parses the "(done/total MB)" counter).</summary>
    public const string DownloadLabel = "Downloading Minecraft";

    // Below this a pass is a check with a few small repairs (a weekly verify fixing one asset), not a download:
    // the file count says more than "0/1 MB".
    private const long MinDownloadBytes = 2L * 1024 * 1024;

    private readonly IProgress<string>? _sink;
    private readonly string _label;
    private readonly Stopwatch _sinceLastReport = Stopwatch.StartNew();
    private readonly object _gate = new();

    private int _done = -1, _total;
    private long _bytesDone, _bytesTotal;
    private string? _lastMessage;

    public InstallProgressThrottle(IProgress<string>? sink, string label)
    {
        _sink = sink;
        _label = label;
    }

    public IProgress<InstallerProgressChangedEventArgs> Progress =>
        new Progress<InstallerProgressChangedEventArgs>(OnFiles);

    /// <summary>CmlLib's byte progress (the installer's second progress argument), summed over every file it downloads.</summary>
    public IProgress<ByteProgress> Bytes => new Progress<ByteProgress>(OnBytes);

    private void OnFiles(InstallerProgressChangedEventArgs e)
    {
        lock (_gate)
        {
            _done = e.ProgressedTasks;
            _total = e.TotalTasks;
        }
        // Always let the final "everything done" frame through, so the
        // overlay never freezes one file short of complete.
        Flush(force: e.TotalTasks > 0 && e.ProgressedTasks >= e.TotalTasks);
    }

    private void OnBytes(ByteProgress p)
    {
        lock (_gate)
        {
            _bytesDone = p.ProgressedBytes;
            _bytesTotal = p.TotalBytes;
        }
        Flush(force: false);
    }

    private void Flush(bool force)
    {
        if (_sink is null) return;
        string message;
        lock (_gate)
        {
            if (!force && _sinceLastReport.Elapsed < Interval) return;
            message = Format(_label, Math.Max(0, _done), _total, _bytesDone, _bytesTotal);
            if (message == _lastMessage) return;
            _lastMessage = message;
            _sinceLastReport.Restart();
        }
        _sink.Report(message);
    }

    /// <summary>
    /// The stage line. Bytes still to come (a real download, 2 MB or more): "Downloading Minecraft (120/540 MB)".
    /// Otherwise the file count under <paramref name="label"/>: "Verifying game files (412/3900)", or the bare label
    /// before the first count. Pure, so the wording is unit tested.
    /// </summary>
    public static string Format(string label, int filesDone, int filesTotal, long bytesDone, long bytesTotal)
    {
        if (bytesTotal >= MinDownloadBytes && bytesDone < bytesTotal)
            return $"{DownloadLabel} ({Megabytes(bytesDone)}/{Math.Max(1, Megabytes(bytesTotal))} MB)";
        return filesTotal > 0 ? $"{label} ({filesDone}/{filesTotal})" : label;
    }

    private static long Megabytes(long bytes) => Math.Max(0, bytes) / (1024 * 1024);
}
