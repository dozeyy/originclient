using OriginLauncher.App.Core;
using Xunit;

namespace OriginLauncher.Tests;

// The logs folder used to grow forever (one full game console copy per launch). Real files in a temp folder.
public class LogRetentionTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("log-retention").FullName;

    public void Dispose()
    {
        try { Directory.Delete(_dir, true); } catch { /* best effort */ }
    }

    private string Log(string name, int ageMinutes, int bytes = 10)
    {
        var path = Path.Combine(_dir, name);
        using (var file = File.Create(path)) file.SetLength(bytes); // sparse where the OS allows: size without the writes
        File.SetLastWriteTimeUtc(path, DateTime.UtcNow.AddMinutes(-ageMinutes));
        return path;
    }

    [Fact]
    public void KeepsTheNewestStampedLogsAndNeverTheFixedNameOnes()
    {
        for (var i = 0; i < 40; i++) Log($"1.21.1_Notch_20261103_{i:000000}.log", ageMinutes: i);
        Log("launcher_error_20250101_000000.log", ageMinutes: 100_000);
        Log("crash_20250101_000000.log", ageMinutes: 100_001);
        var update = Log("update.log", ageMinutes: 200_000);
        var sync = Log("settings-sync.log", ageMinutes: 200_000);

        var removed = LogRetention.Prune(_dir);

        Assert.Equal(40 + 2 - LogRetention.KeepNewest, removed.Count);
        Assert.True(File.Exists(Path.Combine(_dir, "1.21.1_Notch_20261103_000000.log"))); // newest
        Assert.False(File.Exists(Path.Combine(_dir, "1.21.1_Notch_20261103_000039.log"))); // oldest launch
        Assert.False(File.Exists(Path.Combine(_dir, "crash_20250101_000000.log")));
        Assert.True(File.Exists(update));
        Assert.True(File.Exists(sync));
    }

    [Fact]
    public void BigLogsAreCappedByTotalSizeButTheNewestFewAlwaysStay()
    {
        const int big = 80 * 1024 * 1024; // a mod spamming errors
        for (var i = 0; i < 5; i++) Log($"1.20.1_Alex_20261103_{i:000000}.log", ageMinutes: i, bytes: big);

        LogRetention.Prune(_dir);

        var left = Directory.GetFiles(_dir).Select(Path.GetFileName).OrderBy(n => n).ToArray();
        Assert.Equal(LogRetention.AlwaysKeep, left.Length); // 3 x 80 MB > 200 MB, but the newest three stay
        Assert.Equal("1.20.1_Alex_20261103_000000.log", left[0]);
    }

    [Fact]
    public void AMissingFolderIsFine() => Assert.Empty(LogRetention.Prune(Path.Combine(_dir, "nope")));
}
