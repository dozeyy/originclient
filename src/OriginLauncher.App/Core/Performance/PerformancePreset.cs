using System.IO;
using System.Text.RegularExpressions;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Performance;

// "Recommended for this PC": a Low / Balanced / High video preset picked from the
// machine's CPU, RAM and GPU, written into the game's options.txt. Pure logic -
// the hardware read (registry) lives in HardwareProbe; nothing here touches
// OriginPaths, so the tests drive it on temp folders.
//
// Memory is NOT part of this (auto-RAM is separate: RamAdvice).
//
// Never overwrites a player silently: ApplyToNewInstance writes only when the
// game folder has no options.txt yet; ApplyNow (the Settings button) is the
// player's explicit click and only touches the preset's own keys, leaving
// keybinds, sensitivity, FOV, sounds and everything else exactly as they were.
public enum PerformanceTier
{
    Low,
    Balanced,
    High,
}

/// <param name="GpuVramMb">Dedicated video memory when known; null when unknown.</param>
/// <param name="GpuIntegrated">True/false when the GPU is classified, null when no GPU was found.</param>
public sealed record HardwareProfile(int LogicalCores, int TotalRamMb, string? GpuName, long? GpuVramMb, bool? GpuIntegrated);

public static class TierPicker
{
    private const int LowRamMb = 6 * 1024;
    private const int BigRamMb = 15 * 1024; // a "16 GB" PC reports ~15.8 GB

    public static PerformanceTier Pick(HardwareProfile hw)
    {
        var integrated = hw.GpuIntegrated == true;
        var tinyVram = hw.GpuVramMb is { } vram && vram > 0 && vram < 2048 && !integrated;

        // Anything that would make the game struggle at default settings.
        if (hw.TotalRamMb < LowRamMb || hw.LogicalCores <= 3 || tinyVram)
            return PerformanceTier.Low;
        // An integrated GPU shares system RAM and bandwidth: Low, unless the
        // rest of the PC is strong enough to carry Balanced.
        if (integrated)
            return hw.LogicalCores >= 8 && hw.TotalRamMb >= BigRamMb ? PerformanceTier.Balanced : PerformanceTier.Low;

        // Unknown GPU: never promise High.
        if (hw.GpuIntegrated == null)
            return PerformanceTier.Balanced;

        var strongGpu = hw.GpuVramMb is >= 6 * 1024;
        return strongGpu && hw.LogicalCores >= 8 && hw.TotalRamMb >= BigRamMb
            ? PerformanceTier.High
            : PerformanceTier.Balanced;
    }

    private static readonly Regex DiscreteArc = new(@"\bArc\b.*\bA\d{3}\b", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex IntegratedRadeon = new(
        @"Radeon(\(TM\))?\s*(Graphics|Vega\b.*Graphics|\d{3,4}M)\s*$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    /// <summary>True for an integrated GPU, false for a discrete card, null for no usable GPU name.</summary>
    public static bool? LooksIntegrated(string? gpuName)
    {
        if (string.IsNullOrWhiteSpace(gpuName)) return null;
        var name = gpuName.Trim();
        if (name.Contains("Microsoft Basic", StringComparison.OrdinalIgnoreCase)) return true; // no driver: software path
        if (DiscreteArc.IsMatch(name)) return false;
        if (name.Contains("GeForce", StringComparison.OrdinalIgnoreCase)
            || name.Contains("NVIDIA", StringComparison.OrdinalIgnoreCase)
            || name.Contains("Quadro", StringComparison.OrdinalIgnoreCase)
            || name.Contains("Radeon RX", StringComparison.OrdinalIgnoreCase)
            || name.Contains("Radeon Pro", StringComparison.OrdinalIgnoreCase)
            || name.Contains("Radeon(TM) RX", StringComparison.OrdinalIgnoreCase))
            return false;
        if (name.Contains("Intel", StringComparison.OrdinalIgnoreCase)) return true; // UHD / HD / Iris Xe / Arc iGPU
        if (IntegratedRadeon.IsMatch(name)) return true;
        return false; // an unrecognised named card: assume discrete
    }
}

public static class PresetOptions
{
    /// <summary>
    /// The options.txt keys of a tier, for one Minecraft version. Era-aware: keys a version
    /// doesn't have are left out (1.8.9 has fancyGraphics where later versions have
    /// graphicsMode; 1.21.11 swapped graphicsMode for graphicsPreset).
    /// </summary>
    public static Dictionary<string, string?> For(PerformanceTier tier, string version)
    {
        var low = tier == PerformanceTier.Low;
        var high = tier == PerformanceTier.High;
        var legacy = !McVersion.AtLeast(version, "1.13"); // 1.8.9: a different set of keys

        var options = new Dictionary<string, string?>(StringComparer.Ordinal)
        {
            ["renderDistance"] = Pick(tier, "6", "10", "16"),
            ["particles"] = Pick(tier, "2", "1", "0"), // minimal / decreased / all
            ["maxFps"] = Pick(tier, "60", "120", "260"), // 260 = unlimited
            ["entityShadows"] = low ? "false" : "true",
            ["ao"] = low ? "false" : "true",
        };

        // 1.8.9's loader reads renderClouds as true / anything-else; the modern eras know "fast" too.
        options["renderClouds"] = legacy
            ? (low ? "false" : "true")
            : Pick(tier, "false", "fast", "true");

        if (legacy)
        {
            options["fancyGraphics"] = low ? "false" : "true";
        }
        else if (McVersion.AtLeast(version, "1.21.11"))
        {
            options["graphicsPreset"] = Pick(tier, "\"fast\"", "\"normal\"", "\"fancy\"");
        }
        else
        {
            options["graphicsMode"] = low ? "0" : "1"; // fast / fancy (never "fabulous": it is the heavy one)
        }

        if (!legacy)
        {
            options["entityDistanceScaling"] = Pick(tier, "0.5", "1.0", "1.0");
            options["biomeBlendRadius"] = Pick(tier, "1", "3", "5");
        }
        if (McVersion.AtLeast(version, "1.18"))
            options["simulationDistance"] = Pick(tier, "5", "8", "12");

        return options;
    }

    private static string Pick(PerformanceTier tier, string low, string balanced, string high) =>
        tier switch { PerformanceTier.Low => low, PerformanceTier.High => high, _ => balanced };
}

public enum PresetOutcome
{
    /// <summary>The preset keys were written.</summary>
    Applied,
    /// <summary>The folder already has an options.txt - the player's own - and the call was "new instance only".</summary>
    SkippedExisting,
    /// <summary>The version can't be written safely (no data version to stamp, or a corrupt file).</summary>
    SkippedUnsupported,
    /// <summary>The file could not be written (locked, read-only). Nothing was changed.</summary>
    Failed,
}

public static class PresetWriter
{
    /// <summary>
    /// First launch of a game folder: writes the preset only when options.txt does not exist yet.
    /// Called after Settings Sync has pushed the player's settings, so a folder that already got
    /// them is never touched. Never throws.
    /// </summary>
    public static PresetOutcome ApplyToNewInstance(string gameDir, string version, PerformanceTier tier)
    {
        try
        {
            if (File.Exists(Path.Combine(gameDir, "options.txt"))) return PresetOutcome.SkippedExisting;
            return Write(gameDir, version, tier, shadersOff: false);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return PresetOutcome.Failed;
        }
    }

    /// <summary>
    /// The player clicked "Recommended for this PC": merge the preset's keys into the existing
    /// options.txt (everything else stays) and, on Low, switch Iris shaders off. Never throws.
    /// </summary>
    public static PresetOutcome ApplyNow(string gameDir, string version, PerformanceTier tier)
    {
        try
        {
            return Write(gameDir, version, tier, shadersOff: tier == PerformanceTier.Low);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return PresetOutcome.Failed;
        }
    }

    private static PresetOutcome Write(string gameDir, string version, PerformanceTier tier, bool shadersOff)
    {
        if (!Directory.Exists(gameDir)) return PresetOutcome.Failed;
        var options = PresetOptions.For(tier, version);
        var result = OptionsTxtFormat.Instance.Apply(
            Path.Combine(gameDir, "options.txt"), options, new SyncInstance(version, gameDir));
        if (result == null) return PresetOutcome.SkippedUnsupported;

        if (shadersOff)
        {
            // Iris only: edits the key when the file has it, never creates one.
            PropertiesFormat.Iris.Apply(
                Path.Combine(gameDir, "config", "iris.properties"),
                new Dictionary<string, string?> { ["enableShaders"] = "false" },
                new SyncInstance(version, gameDir));
        }
        return PresetOutcome.Applied;
    }
}
