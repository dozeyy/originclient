using System.Diagnostics;
using CmlLib.Core.Installers;

namespace OriginLauncher.App.Core.Versions;

/// <summary>
/// Coalesces CmlLib's per-file install events into at most a few UI updates per
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
/// </summary>
public sealed class InstallProgressThrottle
{
    // ~8 updates/sec: fast enough to read as live movement, far below the rate
    // at which WPF text layout starts costing real time.
    private static readonly TimeSpan Interval = TimeSpan.FromMilliseconds(125);

    private readonly IProgress<string>? _sink;
    private readonly string _label;
    private readonly Stopwatch _sinceLastReport = Stopwatch.StartNew();
    private readonly object _gate = new();

    private int _lastReportedDone = -1;

    public InstallProgressThrottle(IProgress<string>? sink, string label)
    {
        _sink = sink;
        _label = label;
    }

    public IProgress<InstallerProgressChangedEventArgs> Progress =>
        new Progress<InstallerProgressChangedEventArgs>(OnEvent);

    private void OnEvent(InstallerProgressChangedEventArgs e)
    {
        if (_sink is null) return;

        string? message = null;
        lock (_gate)
        {
            // Always let the final "everything done" frame through, so the
            // overlay never freezes one file short of complete.
            var isFinal = e.TotalTasks > 0 && e.ProgressedTasks >= e.TotalTasks;
            if (!isFinal && _sinceLastReport.Elapsed < Interval) return;
            if (e.ProgressedTasks == _lastReportedDone && !isFinal) return;

            _lastReportedDone = e.ProgressedTasks;
            _sinceLastReport.Restart();
            message = e.TotalTasks > 0
                ? $"{_label} ({e.ProgressedTasks}/{e.TotalTasks})"
                : _label;
        }

        _sink.Report(message);
    }
}
