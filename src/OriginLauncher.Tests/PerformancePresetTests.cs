using OriginLauncher.App.Core.Performance;
using Xunit;

namespace OriginLauncher.Tests;

// "Recommended for this PC": tier selection from hardware (pure) and the era-aware options.txt write
// (real files in a temp folder).
public class PerformancePresetTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("preset").FullName;

    public void Dispose()
    {
        try { Directory.Delete(_dir, true); } catch { /* best effort */ }
    }

    private static HardwareProfile Hw(int cores, int ramGb, string? gpu, long? vramMb) =>
        new(cores, ramGb * 1024 - 200, gpu, vramMb, TierPicker.LooksIntegrated(gpu));

    [Theory]
    [InlineData(4, 8, "Intel(R) UHD Graphics 620", 128L, PerformanceTier.Low)]
    [InlineData(2, 16, "NVIDIA GeForce RTX 3060", 12288L, PerformanceTier.Low)] // too few cores
    [InlineData(8, 4, "NVIDIA GeForce GTX 1650", 4096L, PerformanceTier.Low)] // too little RAM
    [InlineData(8, 16, "NVIDIA GeForce GT 710", 1024L, PerformanceTier.Low)] // 1 GB card
    [InlineData(8, 16, "AMD Radeon(TM) Graphics", 512L, PerformanceTier.Balanced)] // strong APU PC
    [InlineData(6, 8, "AMD Radeon(TM) Graphics", 512L, PerformanceTier.Low)] // weak APU PC
    [InlineData(8, 16, "NVIDIA GeForce RTX 3060", 12288L, PerformanceTier.High)]
    [InlineData(12, 32, "AMD Radeon RX 9070 XT", 16384L, PerformanceTier.High)]
    [InlineData(6, 16, "NVIDIA GeForce GTX 1660", 6144L, PerformanceTier.Balanced)] // few cores for High
    [InlineData(8, 8, "NVIDIA GeForce GTX 1060", 6144L, PerformanceTier.Balanced)] // 8 GB RAM for High
    [InlineData(8, 16, null, null, PerformanceTier.Balanced)] // unknown GPU never gets High
    public void TierFollowsTheMachine(int cores, int ramGb, string? gpu, long? vram, PerformanceTier expected) =>
        Assert.Equal(expected, TierPicker.Pick(Hw(cores, ramGb, gpu, vram)));

    [Theory]
    [InlineData("Intel(R) UHD Graphics 630", true)]
    [InlineData("Intel(R) Iris(R) Xe Graphics", true)]
    [InlineData("Intel(R) Arc(TM) Graphics", true)]
    [InlineData("Intel(R) Arc(TM) A770 Graphics", false)]
    [InlineData("AMD Radeon(TM) Graphics", true)]
    [InlineData("AMD Radeon(TM) Vega 8 Graphics", true)]
    [InlineData("AMD Radeon 780M", true)]
    [InlineData("AMD Radeon RX 6600", false)]
    [InlineData("NVIDIA GeForce RTX 4070", false)]
    [InlineData("Microsoft Basic Render Driver", true)]
    public void IntegratedDetection(string name, bool integrated) =>
        Assert.Equal(integrated, TierPicker.LooksIntegrated(name));

    [Fact]
    public void NoGpuNameIsUnknown() => Assert.Null(TierPicker.LooksIntegrated(" "));

    [Fact]
    public void LowIsAlwaysLighterThanHigh()
    {
        foreach (var version in new[] { "1.8.9", "1.16.5", "1.19.2", "1.21.1", "1.21.11" })
        {
            var low = PresetOptions.For(PerformanceTier.Low, version);
            var high = PresetOptions.For(PerformanceTier.High, version);
            Assert.True(int.Parse(low["renderDistance"]!) < int.Parse(high["renderDistance"]!), version);
            Assert.True(int.Parse(low["maxFps"]!) < int.Parse(high["maxFps"]!), version);
            Assert.Equal("2", low["particles"]);
        }
    }

    [Fact]
    public void KeysFollowTheEra()
    {
        var legacy = PresetOptions.For(PerformanceTier.Balanced, "1.8.9");
        Assert.True(legacy.ContainsKey("fancyGraphics"));
        Assert.False(legacy.ContainsKey("graphicsMode"));
        Assert.False(legacy.ContainsKey("simulationDistance"));
        Assert.False(legacy.ContainsKey("entityDistanceScaling"));

        var modern = PresetOptions.For(PerformanceTier.Balanced, "1.21.1");
        Assert.True(modern.ContainsKey("graphicsMode"));
        Assert.True(modern.ContainsKey("simulationDistance"));
        Assert.False(modern.ContainsKey("fancyGraphics"));

        var oldest = PresetOptions.For(PerformanceTier.Balanced, "1.16.5");
        Assert.False(oldest.ContainsKey("simulationDistance")); // added in 1.18

        var split = PresetOptions.For(PerformanceTier.Low, "1.21.11");
        Assert.Equal("\"fast\"", split["graphicsPreset"]);
        Assert.False(split.ContainsKey("graphicsMode"));
    }

    [Fact]
    public void NewInstanceGetsTheDataVersionAndPresetKeys()
    {
        Assert.Equal(PresetOutcome.Applied, PresetWriter.ApplyToNewInstance(_dir, "1.21.1", PerformanceTier.Low));
        var text = File.ReadAllText(Path.Combine(_dir, "options.txt"));
        Assert.Contains("version:3955", text);
        Assert.Contains("renderDistance:6", text);
        Assert.Contains("graphicsMode:0", text);
        Assert.Contains("renderClouds:false", text);
    }

    [Fact]
    public void Legacy189HasNoVersionLine()
    {
        Assert.Equal(PresetOutcome.Applied, PresetWriter.ApplyToNewInstance(_dir, "1.8.9", PerformanceTier.Low));
        var text = File.ReadAllText(Path.Combine(_dir, "options.txt"));
        Assert.DoesNotContain("version:", text);
        Assert.Contains("fancyGraphics:false", text);
        Assert.DoesNotContain("graphicsMode", text);
    }

    [Fact]
    public void ExistingOptionsAreNeverTouchedForANewInstance()
    {
        var path = Path.Combine(_dir, "options.txt");
        File.WriteAllText(path, "renderDistance:32\nfov:0.5\n");
        Assert.Equal(PresetOutcome.SkippedExisting, PresetWriter.ApplyToNewInstance(_dir, "1.21.1", PerformanceTier.Low));
        Assert.Equal("renderDistance:32\nfov:0.5\n", File.ReadAllText(path));
    }

    [Fact]
    public void ApplyNowChangesOnlyPresetKeys()
    {
        var path = Path.Combine(_dir, "options.txt");
        File.WriteAllText(path, "version:3955\nrenderDistance:32\nfov:0.5\nkey_key.forward:key.keyboard.w\nmouseSensitivity:0.7\n");
        Assert.Equal(PresetOutcome.Applied, PresetWriter.ApplyNow(_dir, "1.21.1", PerformanceTier.Low));
        var text = File.ReadAllText(path);
        Assert.Contains("renderDistance:6", text);
        Assert.Contains("fov:0.5", text);
        Assert.Contains("key_key.forward:key.keyboard.w", text);
        Assert.Contains("mouseSensitivity:0.7", text);
        Assert.DoesNotContain("renderDistance:32", text);
    }

    [Fact]
    public void LowTurnsIrisOffButOnlyWhenTheKeyExists()
    {
        File.WriteAllText(Path.Combine(_dir, "options.txt"), "version:3955\n");
        Directory.CreateDirectory(Path.Combine(_dir, "config"));
        var iris = Path.Combine(_dir, "config", "iris.properties");
        File.WriteAllText(iris, "shaderPack=BSL\nenableShaders=true\n");
        PresetWriter.ApplyNow(_dir, "1.21.1", PerformanceTier.Low);
        Assert.Contains("enableShaders=false", File.ReadAllText(iris));
        Assert.Contains("shaderPack=BSL", File.ReadAllText(iris));

        File.WriteAllText(iris, "enableShaders=true\n");
        PresetWriter.ApplyNow(_dir, "1.21.1", PerformanceTier.Balanced);
        Assert.Contains("enableShaders=true", File.ReadAllText(iris)); // Balanced leaves shaders alone
    }

    [Fact]
    public void UnstampableVersionIsSkippedNotCorrupted()
    {
        // 26.x has no known data version to stamp: leave the folder alone rather than write a version-less file.
        Assert.Equal(PresetOutcome.SkippedUnsupported, PresetWriter.ApplyToNewInstance(_dir, "26.2", PerformanceTier.Low));
        Assert.False(File.Exists(Path.Combine(_dir, "options.txt")));
    }

    [Fact]
    public void MissingFolderFailsSoft() =>
        Assert.Equal(PresetOutcome.Failed, PresetWriter.ApplyToNewInstance(Path.Combine(_dir, "nope"), "1.21.1", PerformanceTier.Low));
}
