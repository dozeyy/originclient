using OriginLauncher.App.Core.Launch;
using Xunit;

namespace OriginLauncher.Tests;

public class GameHandoffTests
{
    private static readonly DateTime T0 = new(2026, 10, 18, 12, 0, 0, DateTimeKind.Utc);
    private const string Token = "0123456789abcdef0123456789abcdef";

    private static GameHandoff New()
    {
        var h = new GameHandoff(Token);
        h.ProcessStarted(T0);
        return h;
    }
    private static string Line(string stage, string token = Token) =>
        $"[12:00:01] [Render thread/INFO]: origin-handoff {token} {stage}";

    [Fact]
    public void FreshTokensAreRandomHex()
    {
        var a = GameHandoff.NewToken();
        Assert.Equal(32, a.Length);
        Assert.All(a, c => Assert.True(char.IsAsciiHexDigitLower(c)));
        Assert.NotEqual(a, GameHandoff.NewToken());
    }

    [Fact]
    public void JvmArgumentCarriesTheToken() =>
        Assert.Equal($"-Dorigin.handoff={Token}", New().JvmArgument);

    [Fact]
    public void StartsWaitingWithTheSceneUp() =>
        Assert.Equal(new HandoffView(HandoffStage.Starting, false, false), New().Poll(T0.AddSeconds(5)));

    [Fact]
    public void LoadingMarkerReleasesTheSceneButIsNotReady()
    {
        var h = New();
        Assert.True(h.Observe(Line("hello")));
        h.WindowSeen(T0.AddSeconds(4));
        Assert.Equal(new HandoffView(HandoffStage.Opening, false, false), h.Poll(T0.AddSeconds(5)));
        Assert.True(h.Observe(Line("loading")));
        Assert.Equal(new HandoffView(HandoffStage.Loading, true, false), h.Poll(T0.AddSeconds(5)));
    }

    [Fact]
    public void ReadyMarkerMeansReady()
    {
        var h = New();
        h.Observe(Line("hello"));
        h.Observe(Line("loading"));
        Assert.True(h.Observe(Line("ready")));
        Assert.Equal(new HandoffView(HandoffStage.Ready, true, true), h.Poll(T0.AddSeconds(20)));
    }

    [Fact]
    public void StagesNeverGoBackwards()
    {
        var h = New();
        h.Observe(Line("ready"));
        Assert.False(h.Observe(Line("loading")));
        Assert.Equal(HandoffStage.Ready, h.Poll(T0).Stage);
    }

    [Fact]
    public void RepeatedMarkersAreNotNews()
    {
        var h = New();
        Assert.True(h.Observe(Line("hello")));
        Assert.False(h.Observe(Line("hello")));
        Assert.True(h.Observe(Line("loading")));
        Assert.False(h.Observe(Line("loading")));
    }

    [Theory]
    // another launch's token, a chat line quoting a marker, a truncated token, junk
    [InlineData("[12:00] [Render thread/INFO]: origin-handoff ffffffffffffffffffffffffffffffff ready")]
    [InlineData("[12:00] [Render thread/INFO]: [CHAT] <griefer> origin-handoff ready")]
    [InlineData("origin-handoff 0123456789abcdef ready")]
    [InlineData("origin-handoff")]
    [InlineData("")]
    public void LinesWithoutThisTokenAreIgnored(string line)
    {
        var h = New();
        Assert.False(h.Observe(line));
        Assert.Equal(HandoffStage.Starting, h.Poll(T0).Stage);
    }

    [Fact]
    public void UnknownStageWordsAreIgnored() => Assert.False(New().Observe(Line("launch")));

    [Fact]
    public void ABuildThatNeverSaysHelloFallsBackToTheWindow()
    {
        // 1.8.9 / older jars: exactly the old launcher — the window is the signal.
        var h = New();
        h.WindowSeen(T0.AddSeconds(10));
        Assert.False(h.Poll(T0.AddSeconds(12)).ReleaseScene);
        var view = h.Poll(T0.AddSeconds(10) + GameHandoff.HelloGrace);
        Assert.True(view.ReleaseScene);
        Assert.True(view.GameReady);
    }

    [Fact]
    public void HelloWithoutALoadingFrameStillReleasesAfterTheCeiling()
    {
        var h = New();
        h.Observe(Line("hello"));
        h.WindowSeen(T0.AddSeconds(5));
        Assert.False(h.Poll(T0.AddSeconds(5) + GameHandoff.LoadingCeiling - TimeSpan.FromSeconds(1)).ReleaseScene);
        var view = h.Poll(T0.AddSeconds(5) + GameHandoff.LoadingCeiling);
        Assert.True(view.ReleaseScene);
        Assert.False(view.GameReady); // still loading for real: the Play button keeps spinning
    }

    [Fact]
    public void NothingWaitsPastTheReadyCeiling()
    {
        // No window, no marker (window detection broken, headless): never spin forever.
        var h = New();
        var view = h.Poll(T0 + GameHandoff.ReadyCeiling);
        Assert.True(view.ReleaseScene);
        Assert.True(view.GameReady);
    }

    [Fact]
    public void TheCeilingWaitsForTheProcess()
    {
        // Provisioning can take minutes (first install): no ceiling before the game exists.
        var h = new GameHandoff(Token);
        Assert.False(h.Poll(T0 + GameHandoff.ReadyCeiling * 3).ReleaseScene);
    }

    [Fact]
    public void FirstWindowSightingCounts()
    {
        var h = New();
        h.WindowSeen(T0.AddSeconds(2));
        h.WindowSeen(T0.AddSeconds(30));
        Assert.True(h.Poll(T0.AddSeconds(2) + GameHandoff.HelloGrace).ReleaseScene);
    }
}

public class StageProgressTests
{
    [Theory]
    [InlineData("Preparing game files (12/340)", "Preparing game files", 12.0 / 340)]
    [InlineData("Downloading libraries (340/340)", "Downloading libraries", 1.0)]
    [InlineData("Verifying game files (0/10)", "Verifying game files", 0.0)]
    [InlineData("Copying (12/10)", "Copying", 1.0)]
    public void CountedStagesFillTheBar(string text, string label, double fraction)
    {
        var p = StageProgress.Parse(text);
        Assert.Equal(label, p.Label);
        Assert.Equal(fraction, p.Fraction!.Value, 6);
    }

    [Theory]
    [InlineData("Signing in")]
    [InlineData("Starting Minecraft")]
    [InlineData("Installing Fabric loader...")]
    [InlineData("Waiting (for) things")]
    [InlineData("Odd (3/0)")]
    [InlineData("Odd (a/b)")]
    [InlineData("")]
    [InlineData(null)]
    public void EverythingElseIsIndeterminate(string? text) => Assert.Null(StageProgress.Parse(text).Fraction);
}
