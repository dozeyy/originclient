using OriginLauncher.App.Core.Launch;
using Xunit;

namespace OriginLauncher.Tests;

// A mod spamming errors used to grow one launch's log without limit. The cap is measured in UTF-8 bytes,
// newline included; a small cap stands in for the real 50 MB.
public class GameLogFileTests
{
    private static (GameLogFile log, StringWriter sink) Open(long cap)
    {
        var sink = new StringWriter { NewLine = "\n" };
        return (new GameLogFile(sink, cap), sink);
    }

    [Fact]
    public void WritesEveryLineUnderTheCap()
    {
        var (log, sink) = Open(cap: 100);
        log.WriteLine("hello");
        log.WriteLine("world");
        Assert.False(log.IsCutOff);
        Assert.Equal("hello\nworld\n", sink.ToString());
    }

    [Fact]
    public void StopsWithOneCutOffLineAndDropsTheRest()
    {
        var (log, sink) = Open(cap: 12); // "0123456789\n" = 11 bytes fits, the next line would cross 12
        log.WriteLine("0123456789");
        log.WriteLine("spam");
        log.WriteLine("more spam");
        Assert.True(log.IsCutOff);
        var lines = sink.ToString().Split('\n', StringSplitOptions.RemoveEmptyEntries);
        Assert.Equal(new[] { "0123456789", log.CutOffLine }, lines);
    }

    [Fact]
    public void ALineThatExactlyReachesTheCapStillFits()
    {
        var (log, sink) = Open(cap: 11);
        log.WriteLine("0123456789");
        Assert.False(log.IsCutOff);
        Assert.Equal("0123456789\n", sink.ToString());
    }

    [Fact]
    public void CountsBytesNotCharacters()
    {
        var (log, _) = Open(cap: 6); // "ééé\n" is 4 chars but 7 UTF-8 bytes
        log.WriteLine("ééé");
        Assert.True(log.IsCutOff);
    }

    [Fact]
    public void TheClosingExitCodeLineIsWrittenEvenPastTheCap()
    {
        var (log, sink) = Open(cap: 4);
        log.WriteLine("too long for the cap");
        log.WriteClosingLine("[Origin] Minecraft exited with code 1");
        Assert.EndsWith(log.CutOffLine + "\n[Origin] Minecraft exited with code 1\n", sink.ToString());
    }

    [Fact]
    public void TheRealCapIsFiftyMegabytesAndTheNoticeSaysSo()
    {
        Assert.Equal(50L * 1024 * 1024, GameLogFile.DefaultCapBytes);
        var log = new GameLogFile(new StringWriter());
        Assert.Contains("50 MB", log.CutOffLine);
        Assert.Contains("cut off", log.CutOffLine);
    }

    [Fact]
    public void AFolderThatCantHoldTheLogGivesNoLogInsteadOfThrowing()
    {
        var missing = Path.Combine(Path.GetTempPath(), "origin-no-such-dir-" + Guid.NewGuid(), "x.log");
        Assert.Null(GameLogFile.TryOpen(missing));
    }
}
