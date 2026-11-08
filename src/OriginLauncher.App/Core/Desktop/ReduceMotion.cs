namespace OriginLauncher.App.Core.Desktop;

/// <summary>
/// Whether the desktop asks apps to turn animations off: the Linux twin of Windows' "Show animations" setting
/// (SystemParameters.ClientAreaAnimation, read by the WPF App) and macOS "Reduce motion". The launcher then sets
/// Motion.Off, so every transition lands at once (no other change: same screens, same states).
///
/// Pure readers of what the desktop stores, so OriginLauncher.Tests covers them on every OS; LinuxDesktopPrefs.Linux.cs
/// fetches the text. null = the desktop didn't say (not that desktop, nothing set): animations stay on.
/// </summary>
public static class ReduceMotion
{
    /// <summary>`gsettings get org.gnome.desktop.interface enable-animations` (GNOME, and the GTK desktops that share
    /// the key: Cinnamon, Budgie, Pantheon, Unity): "false" = animations off.</summary>
    public static bool? FromGnome(string? gsettingsOutput) => gsettingsOutput?.Trim() switch
    {
        "false" => true,
        "true" => false,
        _ => null,
    };

    /// <summary>KDE Plasma's kdeglobals: [KDE] AnimationDurationFactor=0 is "Animation speed: Instant".</summary>
    public static bool? FromKdeGlobals(string? kdeglobals)
    {
        if (string.IsNullOrEmpty(kdeglobals)) return null;
        var inKde = false;
        foreach (var raw in kdeglobals.Split('\n'))
        {
            var line = raw.Trim();
            if (line.StartsWith('['))
            {
                inKde = line == "[KDE]";
                continue;
            }
            if (!inKde) continue;
            var eq = line.IndexOf('=');
            if (eq < 0 || line[..eq].Trim() != "AnimationDurationFactor") continue;
            return double.TryParse(line[(eq + 1)..].Trim(), System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var factor)
                ? factor <= 0
                : null;
        }
        return null;
    }

    /// <summary>The answer from both: either desktop saying "off" wins (only one of them is the running desktop, and
    /// the other's setting is normally absent).</summary>
    public static bool Combine(bool? gnome, bool? kde) => gnome == true || kde == true;
}
