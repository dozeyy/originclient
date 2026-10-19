using System.Diagnostics;
using OriginLauncher.App.Core.Sync;
using Xunit;

namespace OriginLauncher.Tests;

// AtomicFile waits out a reader that has the target open (Windows refuses the rename meanwhile), and ONLY that:
// an error waiting can't fix must fail at once, and the UI thread's no-wait write must never stall.
// The rename-over-an-open-file refusal is Windows behaviour; elsewhere these tests only check the write lands.
public class AtomicFileTests : IDisposable
{
    // The whole wait budget is ~1.46 s; a write that fails at once takes milliseconds. 1 s leaves a loaded CI box room.
    private static readonly TimeSpan FailFast = TimeSpan.FromMilliseconds(1000);

    private readonly string _dir = Path.Combine(Path.GetTempPath(), "origin-atomicfile-" + Guid.NewGuid().ToString("N"));
    private string Target => Path.Combine(_dir, "options.txt");

    public AtomicFileTests()
    {
        Directory.CreateDirectory(_dir);
        File.WriteAllText(Target, "old");
    }

    public void Dispose()
    {
        try
        {
            File.SetAttributes(Target, FileAttributes.Normal);
            Directory.Delete(_dir, recursive: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
    }

    private string[] TempFiles() => Directory.GetFiles(_dir, "*.originsync.tmp");

    [Fact]
    public void AReadOnlyTargetFailsAtOnceAndStaysAsItWas()
    {
        if (!OperatingSystem.IsWindows()) return; // a read-only file can be renamed over on Linux
        File.SetAttributes(Target, FileAttributes.ReadOnly);

        var clock = Stopwatch.StartNew();
        Assert.Throws<UnauthorizedAccessException>(() => AtomicFile.WriteAllText(Target, "new"));
        clock.Stop();

        Assert.True(clock.Elapsed < FailFast, $"a read-only target took {clock.ElapsedMilliseconds} ms to fail: it was waited on");
        Assert.Equal("old", File.ReadAllText(Target));
        Assert.Empty(TempFiles());
    }

    [Fact]
    public async Task ABriefReaderIsWaitedOut()
    {
        var reader = new FileStream(Target, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        var release = Task.Run(async () =>
        {
            await Task.Delay(100);
            reader.Dispose();
        });

        AtomicFile.WriteAllText(Target, "new");
        await release;

        Assert.Equal("new", File.ReadAllText(Target));
        Assert.Empty(TempFiles());
    }

    [Fact]
    public void TheNoWaitWriteFailsAtOnceWhileAReaderHoldsTheFile()
    {
        if (!OperatingSystem.IsWindows()) return; // Linux renames over an open file
        using var reader = new FileStream(Target, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);

        var clock = Stopwatch.StartNew();
        Assert.ThrowsAny<Exception>(() => AtomicFile.WriteAllText(Target, "new", waitOutReaders: false));
        clock.Stop();

        Assert.True(clock.Elapsed < FailFast, $"the no-wait write took {clock.ElapsedMilliseconds} ms to fail");
        Assert.Empty(TempFiles());
    }
}
