using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Loaders;

// 1.8.9: FML's own boot splash (SplashProgress: Mojang logo, Forge anvil, red/grey bars) is
// switched OFF, so Origin's loading screen is what the player sees from the first frame to the
// title (Will, 2026-11-04: "remove the forge loading screen ... change it to our custom one").
// The mod draws that screen itself (render/StartupLoading.java in the 1.8.9 module) and also
// forces FML's splash off in memory (mixin/menus/SplashProgressOffMixin), so this file is the
// second lock: it keeps FML from even creating its splash thread if the mixin ever can't apply.
//
// Facts from Forge 11.15.1.2318's SplashProgress (javap, 2026-10-19):
// - It reads <gameDir>/config/splash.properties, i.e. the ACCOUNT's own game folder, and the
//   key `enabled` (default true except on macOS). With OptiFine it runs only when OptiFine
//   puts "optifine.ForgeSplashCompatible" on the launch blackboard (HD_U_M5 does).
// - FML rewrites the whole file on every launch (Properties.store: a comment header, every
//   key, defaults added for missing ones), keeping the values it read. So a file carries
//   `enabled=false` from then on, and a repair matches the key, never bytes.
//
// What Origin wrote before (launcher-v1.0.55 .. v1.0.88): a grey "Origin splash theme" with
// enabled=true. Every such file -- and a player's own enabled=true -- is turned off on the
// next launch; only the `enabled` line changes, every other line is kept as it is.
public static class ForgeSplashOff
{
    public const string Content = "enabled=false\n";

    /// <summary>Makes config/splash.properties say enabled=false (writes it when absent). Never throws.</summary>
    public static void Apply(string configFolder)
    {
        try
        {
            var path = Path.Combine(configFolder, "splash.properties");
            // Atomic writes: a launcher killed mid-write must not leave a truncated file
            // (FML would read its defaults from it: the splash back on).
            if (!File.Exists(path))
            {
                AtomicFile.WriteAllText(path, Content);
                return;
            }
            var text = File.ReadAllText(path);
            var fixedText = Disabled(text);
            if (fixedText != text) AtomicFile.WriteAllText(path, fixedText);
        }
        catch
        {
            // Never block a launch over it: the mod turns FML's splash off in memory anyway.
        }
    }

    /// <summary>
    /// The file's text with every `enabled` line set to false (one appended when there is none).
    /// Returned unchanged (same string) when it already says false, so FML's own layout and line
    /// endings stay untouched.
    /// </summary>
    internal static string Disabled(string text)
    {
        var newline = text.Contains("\r\n") ? "\r\n" : "\n";
        var lines = text.Replace("\r\n", "\n").Split('\n').ToList();
        // A trailing newline leaves one empty last element: keep it out of the edit, put it back after.
        var trailing = lines.Count > 0 && lines[^1].Length == 0;
        if (trailing) lines.RemoveAt(lines.Count - 1);

        var enabledAt = lines.Select((l, i) => (l, i)).Where(p => KeyOf(p.l) == "enabled").Select(p => p.i).ToList();
        if (enabledAt.Count > 0 && enabledAt.All(i => IsFalse(ValueOf(lines[i])))) return text;

        foreach (var i in enabledAt) lines[i] = "enabled=false";
        if (enabledAt.Count == 0) lines.Add("enabled=false");
        return string.Join(newline, lines) + newline;
    }

    // java.util.Properties-style key ("key=value", "key:value" or "key value"); null for comments/blank lines.
    internal static string? KeyOf(string line)
    {
        var t = line.TrimStart();
        if (t.Length == 0 || t[0] == '#' || t[0] == '!') return null;
        var sep = t.IndexOfAny(new[] { '=', ':', ' ', '\t' });
        return sep < 0 ? t : t[..sep];
    }

    private static string ValueOf(string line)
    {
        var t = line.TrimStart();
        var sep = t.IndexOfAny(new[] { '=', ':', ' ', '\t' });
        return sep < 0 ? "" : t[(sep + 1)..].Trim().TrimStart('=', ':').Trim();
    }

    // A Java boolean parse: only "true" (any case) is true, anything else leaves the splash off.
    private static bool IsFalse(string value) => !string.Equals(value, "true", StringComparison.OrdinalIgnoreCase);
}
