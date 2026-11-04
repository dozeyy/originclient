using OriginLauncher.App.Core.Loaders;
using Xunit;

namespace OriginLauncher.Tests;

// 1.8.9: FML's own splash is switched off in every account's config/splash.properties, so
// Origin's loading screen is what shows (ForgeSplashOff).
public class ForgeSplashOffTests : IDisposable
{
    private readonly string dir = Path.Combine(Path.GetTempPath(), "origin-splash-" + Guid.NewGuid().ToString("N"));
    private string File_ => Path.Combine(dir, "splash.properties");

    public void Dispose()
    {
        try { Directory.Delete(dir, true); } catch { }
    }

    private static string? Enabled(string text) =>
        text.Replace("\r\n", "\n").Split('\n').Where(l => ForgeSplashOff.KeyOf(l) == "enabled")
            .Select(l => l[(l.IndexOf('=') + 1)..]).SingleOrDefault();

    [Fact]
    public void WritesOffWhenAbsent()
    {
        ForgeSplashOff.Apply(dir);
        Assert.Equal("enabled=false\n", File.ReadAllText(File_));
    }

    // What FML leaves on disk after a launch with the theme Origin used to write
    // (launcher-v1.0.56 .. v1.0.88): Properties.store's header, every key, CRLF.
    private const string OldOriginThemeAfterFml =
        "#Splash screen properties\r\n#Sun Oct 19 12:00:00 UTC 2026\r\n" +
        "background=0x5A5A5A\r\nbarBorder=0x1E1E1E\r\nresourcePackPath=resources\r\n" +
        "fontTexture=textures/font/ascii.png\r\nlogoTexture=textures/gui/title/mojang.png\r\nrotate=false\r\n" +
        "forgeTexture=fml\\:textures/gui/forge.gif\r\nfont=0xF5F5F5\r\nbar=0xE0E0E0\r\nlogoOffset=0\r\n" +
        "enabled=true\r\nbarBackground=0x3A3A3A\r\n";

    [Fact]
    public void TurnsOffAnExistingPlayersSplashAndKeepsEveryOtherLine()
    {
        Directory.CreateDirectory(dir);
        File.WriteAllText(File_, OldOriginThemeAfterFml);
        ForgeSplashOff.Apply(dir);
        var text = File.ReadAllText(File_);
        Assert.Equal("false", Enabled(text));
        Assert.Equal(OldOriginThemeAfterFml.Replace("enabled=true", "enabled=false"), text);
    }

    [Fact]
    public void AddsTheLineWhenTheFileHasNone()
    {
        Directory.CreateDirectory(dir);
        File.WriteAllText(File_, "#Splash screen properties\nrotate=false\n");
        ForgeSplashOff.Apply(dir);
        Assert.Equal("#Splash screen properties\nrotate=false\nenabled=false\n", File.ReadAllText(File_));
    }

    [Theory]
    [InlineData("enabled=false\n")]
    [InlineData("#Splash screen properties\r\nenabled=false\r\nrotate=false\r\n")]
    [InlineData("enabled = false")]
    public void AlreadyOffIsLeftByteForByte(string text)
    {
        Assert.Same(text, ForgeSplashOff.Disabled(text));
    }

    [Theory]
    [InlineData("enabled=TRUE\n")]
    [InlineData("enabled:true\n")]
    [InlineData("  enabled = true")]
    public void EveryWayOfSayingTrueIsTurnedOff(string text)
    {
        Assert.Equal("false", Enabled(ForgeSplashOff.Disabled(text)));
    }

    [Fact]
    public void ADuplicateEnabledLineCannotSneakTheSplashBackOn()
    {
        // java.util.Properties keeps the LAST value of a repeated key.
        var text = ForgeSplashOff.Disabled("enabled=false\nenabled=true\n");
        Assert.DoesNotContain("enabled=true", text);
    }

    [Fact]
    public void NeverThrowsOnAnUnwritableFolder()
    {
        // A path under a FILE can't be created: Apply must swallow it (never blocks a launch).
        Directory.CreateDirectory(dir);
        var blocker = Path.Combine(dir, "not-a-folder");
        File.WriteAllText(blocker, "x");
        ForgeSplashOff.Apply(Path.Combine(blocker, "config"));
    }
}
