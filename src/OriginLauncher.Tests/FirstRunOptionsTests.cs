using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Performance;
using Xunit;

namespace OriginLauncher.Tests;

// A fresh game folder opens on Origin's title, not vanilla's first-run prompts: the keys per era, and
// that a value already in options.txt (the player's own, synced or imported) is never overwritten.
public class FirstRunOptionsTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("firstrun").FullName;
    private string Options => Path.Combine(_dir, "options.txt");

    public void Dispose()
    {
        try { Directory.Delete(_dir, true); } catch { /* best effort */ }
    }

    [Fact]
    public void Legacy189GetsNothing()
    {
        Assert.Empty(FirstRunOptions.For("1.8.9"));
        Assert.Equal(0, FirstRunOptions.Seed(_dir, "1.8.9"));
        Assert.False(File.Exists(Options));
    }

    [Theory]
    [InlineData("1.16.5")]
    [InlineData("1.19.2")]
    [InlineData("1.19.3")]
    public void BeforeOnboardingThereIsNoOnboardingKey(string version)
    {
        var keys = FirstRunOptions.For(version);
        Assert.Equal("none", keys["tutorialStep"]);
        Assert.Equal("true", keys["skipMultiplayerWarning"]);
        Assert.Equal("true", keys["joinedFirstServer"]);
        Assert.False(keys.ContainsKey("onboardAccessibility"));
    }

    [Theory]
    [InlineData("1.19.4")]
    [InlineData("1.20")]
    [InlineData("1.21.1")]
    [InlineData("1.21.11")]
    [InlineData("26.3")]
    public void OnboardingIsSkippedFrom1194(string version) =>
        Assert.Equal("false", FirstRunOptions.For(version)["onboardAccessibility"]);

    [Fact]
    public void BrandNewFolderGetsAStampedFileWithEveryKey()
    {
        Assert.Equal(4, FirstRunOptions.Seed(_dir, "1.21.1"));
        var text = File.ReadAllText(Options);
        Assert.Contains("version:3955", text);
        Assert.Contains("onboardAccessibility:false", text);
        Assert.Contains("skipMultiplayerWarning:true", text);
        Assert.Contains("tutorialStep:none", text);
        Assert.Contains("joinedFirstServer:true", text);
    }

    [Fact]
    public void RunsAfterThePresetAndKeepsItsKeys()
    {
        // The launch order: Settings Sync, then the preset (creates the file), then this.
        Assert.Equal(PresetOutcome.Applied, PresetWriter.ApplyToNewInstance(_dir, "1.21.1", PerformanceTier.Low));
        Assert.Equal(4, FirstRunOptions.Seed(_dir, "1.21.1"));
        var text = File.ReadAllText(Options);
        Assert.Contains("renderDistance:6", text);
        Assert.Contains("onboardAccessibility:false", text);
        Assert.Single(text.Split('\n'), l => l.StartsWith("version:", StringComparison.Ordinal));
    }

    [Fact]
    public void NeverOverwritesAValueThePlayerHas()
    {
        // A player mid-tutorial who also turned the onboarding back on: both stay; only the missing keys are added.
        File.WriteAllText(Options, "version:3955\ntutorialStep:punch_tree\nonboardAccessibility:true\nfov:0.5\n");
        Assert.Equal(2, FirstRunOptions.Seed(_dir, "1.21.1"));
        var text = File.ReadAllText(Options);
        Assert.Contains("tutorialStep:punch_tree", text);
        Assert.Contains("onboardAccessibility:true", text);
        Assert.Contains("fov:0.5", text);
        Assert.Contains("skipMultiplayerWarning:true", text);
        Assert.Contains("joinedFirstServer:true", text);
        Assert.DoesNotContain("tutorialStep:none", text);
        Assert.DoesNotContain("onboardAccessibility:false", text);
    }

    [Fact]
    public void ASavedFileIsLeftByteForByte()
    {
        const string saved = "version:3955\nonboardAccessibility:false\nskipMultiplayerWarning:false\ntutorialStep:none\njoinedFirstServer:true\n";
        File.WriteAllText(Options, saved);
        Assert.Equal(0, FirstRunOptions.Seed(_dir, "1.21.1"));
        Assert.Equal(saved, File.ReadAllText(Options));
    }

    [Fact]
    public void CorruptFileIsLeftAlone()
    {
        File.WriteAllText(Options, "\0\0\0\0");
        Assert.Equal(0, FirstRunOptions.Seed(_dir, "1.21.1"));
        Assert.Equal("\0\0\0\0", File.ReadAllText(Options));
    }

    [Fact]
    public void UnstampableVersionWritesNothing()
    {
        Assert.Equal(0, FirstRunOptions.Seed(_dir, "26.9"));
        Assert.False(File.Exists(Options));
    }

    [Fact]
    public void MissingFolderFailsSoft() =>
        Assert.Equal(0, FirstRunOptions.Seed(Path.Combine(_dir, "nope"), "1.21.1"));
}
