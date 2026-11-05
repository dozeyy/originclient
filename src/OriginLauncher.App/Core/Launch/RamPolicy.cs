using OriginLauncher.App.Core.Models;

namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// Auto vs Manual memory (pure; the callers do the SettingsStore.Update). Auto is the default
/// and follows the PC and version; Manual is the player's number and is never touched again.
/// </summary>
public static class RamPolicy
{
    /// <summary>
    /// Settles a settings.json from before the Auto/Manual field. The OLD auto logic stored
    /// exactly RecommendedMb(total) and nothing else ever wrote that number except the player,
    /// so a stored value equal to it is Auto and any other value is a number the player chose
    /// (Manual). Returns true when it changed the settings (the caller then saves them).
    /// </summary>
    public static bool Migrate(LauncherSettings s, int totalPhysicalMb)
    {
        if (s.RamManual != null) return false;
        s.RamManual = s.RamMb != RamAdvice.RecommendedMb(totalPhysicalMb);
        return true;
    }

    /// <summary>A brand-new install: Auto, with the suggestion stored for the Settings slider.</summary>
    public static LauncherSettings Fresh(int totalPhysicalMb) =>
        new() { RamMb = RamAdvice.RecommendedMb(totalPhysicalMb), RamManual = false };

    public static bool IsManual(LauncherSettings s) => s.RamManual == true;

    /// <summary>The player picked a value (Settings slider, installer Customize page). It is the number for every
    /// version again: a crash window's per-version raise is dropped.</summary>
    public static void SetManual(LauncherSettings s, int ramMb)
    {
        s.RamMb = ramMb;
        s.RamManual = true;
        s.RamRaiseByVersion.Clear();
    }

    /// <summary>"Back to Auto": the only way out of Manual. RamMb is reset to the suggestion for the slider, and every
    /// per-version raise is dropped (Auto sizes each version again).</summary>
    public static void SetAuto(LauncherSettings s, int totalPhysicalMb)
    {
        s.RamMb = RamAdvice.RecommendedMb(totalPhysicalMb);
        s.RamManual = false;
        s.RamRaiseByVersion.Clear();
    }

    /// <summary>The crash window's "Raise memory to X" for the version that ran out: that version only, exactly X.
    /// Auto stays Auto and every other version keeps its heap (KC-10).</summary>
    public static void RaiseFor(LauncherSettings s, string version, int ramMb) => s.RamRaiseByVersion[version] = ramMb;
}
