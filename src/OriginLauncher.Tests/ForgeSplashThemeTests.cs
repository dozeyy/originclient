using OriginLauncher.App.Core.Loaders;
using Xunit;

namespace OriginLauncher.Tests;

public class ForgeSplashThemeTests : IDisposable
{
    // Every key Forge 11.15.1.2318's SplashProgress reads (javap on forge-universal.jar,
    // 2026-10-19). A key outside this set is silently ignored by FML.
    private static readonly HashSet<string> ForgeKeys = new()
    {
        "enabled", "rotate", "logoOffset", "background", "font", "barBorder", "bar", "barBackground",
        "fontTexture", "logoTexture", "forgeTexture", "resourcePackPath",
    };

    private readonly string dir = Path.Combine(Path.GetTempPath(), "origin-splash-" + Guid.NewGuid().ToString("N"));
    private string File_ => Path.Combine(dir, "splash.properties");

    public void Dispose()
    {
        try { Directory.Delete(dir, true); } catch { }
    }

    [Fact]
    public void EveryKeyIsOneForgeReads()
    {
        var keys = ForgeSplashTheme.Content.Split('\n', StringSplitOptions.RemoveEmptyEntries).Select(l => l.Split('=')[0]).ToList();
        Assert.Contains("background", keys);
        Assert.Contains("bar", keys);
        Assert.All(keys, k => Assert.Contains(k, ForgeKeys));
    }

    [Fact]
    public void ColoursDecodeLikeIntegerDecode()
    {
        foreach (var line in ForgeSplashTheme.Content.Split('\n', StringSplitOptions.RemoveEmptyEntries).Skip(1))
        {
            var v = line.Split('=')[1];
            Assert.StartsWith("0x", v);
            Assert.True(int.TryParse(v[2..], System.Globalization.NumberStyles.HexNumber, null, out _), line);
        }
    }

    [Fact]
    public void WritesWhenAbsent()
    {
        ForgeSplashTheme.Seed(dir);
        Assert.Equal(ForgeSplashTheme.Content, File.ReadAllText(File_));
    }

    // What FML leaves on disk after one launch with the v1.0.55 seed: Properties.store
    // adds a comment header, every key it read (defaults included) and keeps our wrong keys.
    private const string BrokenSeedAfterFmlLaunch =
        "#Splash screen properties\r\n#Sun Oct 19 12:00:00 UTC 2026\r\n" +
        "barColor=0xE0E0E0\r\nbarBorderColor=0x3A3A3A\r\nbackground=0xFFFFFF\r\nbarBorder=0xC0C0C0\r\n" +
        "resourcePackPath=resources\r\nfontTexture=textures/font/ascii.png\r\nshowMemory=false\r\n" +
        "barBackgroundColor=0x161616\r\nlogoTexture=textures/gui/title/mojang.png\r\nrotate=false\r\n" +
        "forgeTexture=fml\\:textures/gui/forge.gif\r\nfont=0x0\r\nbar=0xCB3D35\r\nlogoOffset=0\r\n" +
        "enabled=true\r\nbackgroundColor=0x050505\r\nfontColor=0xF5F5F5\r\nbarBackground=0xFFFFFF\r\n";

    private static Dictionary<string, string> Props(string text) =>
        ForgeSplashTheme.Parse(text.Replace("\r\n", "\n").Split('\n'));

    private static void AssertCurrentColours(string text)
    {
        var want = Props(ForgeSplashTheme.Content);
        var got = Props(text);
        foreach (var k in ForgeSplashTheme.ForgeDefaults.Keys) Assert.Equal(want[k], got[k]);
    }

    [Fact]
    public void RepairsTheBrokenFirstSeedEvenWithCrlf()
    {
        Directory.CreateDirectory(dir);
        File.WriteAllText(File_, ForgeSplashTheme.BrokenFirstSeed.Replace("\n", "\r\n"));
        ForgeSplashTheme.Seed(dir);
        var text = File.ReadAllText(File_);
        AssertCurrentColours(text);
        Assert.DoesNotContain("backgroundColor", text);
        Assert.Contains("enabled=true", text);
    }

    [Fact]
    public void RepairsTheBrokenFirstSeedAfterFmlRewroteIt()
    {
        Directory.CreateDirectory(dir);
        File.WriteAllText(File_, BrokenSeedAfterFmlLaunch);
        ForgeSplashTheme.Seed(dir);
        var text = File.ReadAllText(File_);
        AssertCurrentColours(text);
        Assert.DoesNotContain("backgroundColor", text);
        Assert.DoesNotContain("showMemory", text);
        // Non-colour lines FML wrote survive verbatim.
        Assert.Contains("forgeTexture=fml\\:textures/gui/forge.gif", text);
        Assert.Contains("rotate=false", text);
        Assert.Single(text.Split('\n'), l => l.StartsWith("background="));
    }

    [Fact]
    public void RepairsThePreviousCharcoalThemeAndKeepsThePlayersToggle()
    {
        // The 2026-10-19 charcoal seed (hid the Mojang logo), FML-rewritten, splash turned off by the player.
        Directory.CreateDirectory(dir);
        File.WriteAllText(File_, "#Splash screen properties\nenabled=false\nbackground=0x050505\nfont=0xF5F5F5\n" +
            "bar=0xE0E0E0\nbarBorder=0x3A3A3A\nbarBackground=0x161616\nrotate=false\n");
        ForgeSplashTheme.Seed(dir);
        var text = File.ReadAllText(File_);
        AssertCurrentColours(text);
        Assert.Contains("enabled=false", text);
        Assert.DoesNotContain("enabled=true", text);
    }

    [Fact]
    public void CurrentThemeIsLeftAsIs()
    {
        Directory.CreateDirectory(dir);
        var fmlRewritten = "#Splash screen properties\n" + ForgeSplashTheme.Content + "rotate=false\n";
        File.WriteAllText(File_, fmlRewritten);
        ForgeSplashTheme.Seed(dir);
        Assert.Equal(fmlRewritten, File.ReadAllText(File_));
    }

    [Fact]
    public void LeavesAPlayersOwnFileAlone()
    {
        Directory.CreateDirectory(dir);
        const string mine = "enabled=false\n";
        File.WriteAllText(File_, mine);
        ForgeSplashTheme.Seed(dir);
        Assert.Equal(mine, File.ReadAllText(File_));
    }

    [Fact]
    public void LeavesAPlayersOwnColoursAlone()
    {
        // The broken seed's leftover key is there, but the player has since picked real colours.
        Directory.CreateDirectory(dir);
        const string mine = "backgroundColor=0x050505\nbackground=0x123456\nbar=0xCB3D35\n";
        File.WriteAllText(File_, mine);
        ForgeSplashTheme.Seed(dir);
        Assert.Equal(mine, File.ReadAllText(File_));
    }

    [Fact]
    public void BackgroundIsGreyNotBlackSoTheTintedLogoShows()
    {
        // FML tints the Mojang logo by `background`; its black wordmark needs ~3:1 against it.
        var bg = Convert.ToInt32(Props(ForgeSplashTheme.Content)["background"][2..], 16);
        static double Lin(int c) { var s = c / 255.0; return s <= 0.04045 ? s / 12.92 : Math.Pow((s + 0.055) / 1.055, 2.4); }
        static double L(int rgb) => 0.2126 * Lin(rgb >> 16 & 0xFF) + 0.7152 * Lin(rgb >> 8 & 0xFF) + 0.0722 * Lin(rgb & 0xFF);
        var contrast = (L(bg) + 0.05) / 0.05;
        Assert.True(contrast >= 2.9, $"logo contrast {contrast:F2}");
        Assert.True(L(bg) < 0.2, "background must stay dark grey");
    }
}
