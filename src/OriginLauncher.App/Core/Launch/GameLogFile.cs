using System.IO;
using System.Text;

namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// The launcher's copy of one game's console (logs/&lt;version&gt;_&lt;account&gt;_&lt;time&gt;.log). Every stdout/stderr
/// line is written as it arrives, so a mod that spams errors used to grow one session's file without limit
/// (LogRetention only prunes at the NEXT start). Past <see cref="CapBytes"/> the copy stops with one
/// <see cref="CutOffLine"/>; the game itself keeps running and its output keeps flowing to the launcher's
/// readers (handoff, server detection), only this file stops growing.
/// Not thread-safe: the callers already serialise stdout and stderr under one lock.
/// Shared by the Windows, Mac and Linux launchers (Core is linked into each).
/// </summary>
public sealed class GameLogFile : IDisposable
{
    public const long DefaultCapBytes = 50L * 1024 * 1024;

    private readonly TextWriter _writer;
    private readonly long _capBytes;
    private readonly int _newlineBytes;
    private long _written;

    public GameLogFile(TextWriter writer, long capBytes = DefaultCapBytes)
    {
        _writer = writer;
        _capBytes = capBytes;
        _newlineBytes = Encoding.UTF8.GetByteCount(writer.NewLine);
    }

    /// <summary>Opens a fresh log at <paramref name="path"/>, or null when it can't be created (logging is
    /// best-effort: a failed log file must never block a launch).</summary>
    public static GameLogFile? TryOpen(string path)
    {
        try { return new GameLogFile(new StreamWriter(path, append: false) { AutoFlush = true }); }
        catch { return null; }
    }

    /// <summary>True once the cap was reached and the cut-off line written.</summary>
    public bool IsCutOff { get; private set; }

    /// <summary>The last line of a log that reached the cap. Bug reports quote it, so it says what happened.</summary>
    public string CutOffLine =>
        $"[Origin] This log reached {_capBytes / (1024 * 1024)} MB and was cut off here to save disk space. The game kept running.";

    /// <summary>Writes one console line, unless the cap is reached; the line that would cross it writes the
    /// cut-off notice instead, and every later line is dropped.</summary>
    public void WriteLine(string line)
    {
        if (IsCutOff) return;
        var bytes = Encoding.UTF8.GetByteCount(line) + _newlineBytes;
        if (_written + bytes > _capBytes)
        {
            IsCutOff = true;
            _writer.WriteLine(CutOffLine);
            return;
        }
        _writer.WriteLine(line);
        _written += bytes;
    }

    /// <summary>Writes the launcher's own closing line (the exit code) even past the cap: one short line, and
    /// the crash window reads the end of the file.</summary>
    public void WriteClosingLine(string line) => _writer.WriteLine(line);

    public void Dispose() => _writer.Dispose();
}
