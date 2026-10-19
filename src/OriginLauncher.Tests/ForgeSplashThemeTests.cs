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

    [Fact]
    public void RepairsTheBrokenFirstSeedEvenWithCrlf()
    {
        Directory.CreateDirectory(dir);
        File.WriteAllText(File_, ForgeSplashTheme.BrokenFirstSeed.Replace("\n", "\r\n"));
        ForgeSplashTheme.Seed(dir);
        Assert.Equal(ForgeSplashTheme.Content, File.ReadAllText(File_));
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
}
