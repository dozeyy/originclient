using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace OriginLauncher.App.Core.Loaders;

// FML's boot splash (1.8.9, the earliest thing a player sees) is configured by
// <gameDir>/config/splash.properties, i.e. the ACCOUNT's own game folder. Seeded
// to an Origin grey + white palette instead of Forge's default white + red.
//
// Facts from Forge 11.15.1.2318's SplashProgress bytecode (javap, 2026-10-19):
// - Keys: enabled, rotate, logoOffset, background, font, barBorder, bar,
//   barBackground, fontTexture, logoTexture, forgeTexture, resourcePackPath.
//   Colours go through Integer.decode, so "0x" hex is right.
// - The Mojang logo AND the Forge anvil are drawn tinted by `background`
//   (setColor(backgroundColor) before each quad, GL modulate). Nothing on the
//   splash can be brighter than the background, so a black background makes the
//   logo vanish and no custom logo texture can fix that. Hence a dark GREY: at
//   0x5A the logo's black MOJANG wordmark sits at ~3:1 contrast (measured on the
//   real mojang.png; 0x3A was ~1.8:1, 0x05 ~1:1, i.e. invisible).
// - FML rewrites the whole file on every launch (Properties.store: a comment
//   header, every key, defaults added for missing ones). So a file Origin wrote
//   is never byte-identical after the first launch; repairs match by colour
//   values, not bytes.
//
// History of what Origin wrote (each still repaired on the next launch):
// - launcher-v1.0.55 and earlier: backgroundColor/fontColor/... -- keys FML never
//   reads, so players got the red Forge splash.
// - 2026-10-19 (claude/ci-hardening, unreleased): the right keys on 0x050505, which
//   hid the Mojang logo.
//
// The file is player-ownable config: written when absent; when it still carries
// one of Origin's earlier themes only the colour lines are replaced (every other
// line, e.g. a player's enabled=false, is kept). A player's own colours are never
// touched.
public static class ForgeSplashTheme
{
    public const string Content =
        "enabled=true\n" +
        "background=0x5A5A5A\n" +
        "font=0xF5F5F5\n" +
        "bar=0xE0E0E0\n" +
        "barBorder=0x1E1E1E\n" +
        "barBackground=0x3A3A3A\n";

    // The keys FML colours the splash with, and Forge's defaults for them
    // (bytecode ldc values: 16777215, 0, 12632256, 13319477, 16777215).
    internal static readonly IReadOnlyDictionary<string, int> ForgeDefaults = new Dictionary<string, int>
    {
        ["background"] = 0xFFFFFF,
        ["font"] = 0x000000,
        ["barBorder"] = 0xC0C0C0,
        ["bar"] = 0xCB3D35,
        ["barBackground"] = 0xFFFFFF,
    };

    // The wrong keys the first seed wrote (launcher-v1.0.55 and earlier).
    private static readonly string[] LegacyKeys =
        { "showMemory", "backgroundColor", "fontColor", "barColor", "barBorderColor", "barBackgroundColor" };

    // Colour sets Origin itself wrote before the current one.
    private static readonly IReadOnlyDictionary<string, int>[] PreviousThemes =
    {
        new Dictionary<string, int>
        {
            ["background"] = 0x050505, ["font"] = 0xF5F5F5, ["bar"] = 0xE0E0E0,
            ["barBorder"] = 0x3A3A3A, ["barBackground"] = 0x161616,
        },
    };

    // The first seed, exactly as it was written (wrong keys). Kept for the tests.
    internal const string BrokenFirstSeed =
        "enabled=true\n" +
        "showMemory=false\n" +
        "backgroundColor=0x050505\n" +
        "fontColor=0xF5F5F5\n" +
        "barColor=0xE0E0E0\n" +
        "barBorderColor=0x3A3A3A\n" +
        "barBackgroundColor=0x161616\n";

    /// <summary>Seeds (or repairs an earlier Origin seed of) config/splash.properties. Never throws.</summary>
    public static void Seed(string configFolder)
    {
        try
        {
            var path = Path.Combine(configFolder, "splash.properties");
            if (!File.Exists(path))
            {
                Directory.CreateDirectory(configFolder);
                File.WriteAllText(path, Content);
                return;
            }
            var lines = File.ReadAllText(path).Replace("\r\n", "\n").Split('\n');
            if (!IsEarlierOriginTheme(Parse(lines))) return;

            // Keep every non-colour line (comments, enabled, textures, ...); swap the colours.
            var kept = lines.Where(l => l.Length > 0)
                .Where(l => { var k = KeyOf(l); return k == null || (!ForgeDefaults.ContainsKey(k) && !LegacyKeys.Contains(k)); });
            var colours = Content.Split('\n', StringSplitOptions.RemoveEmptyEntries).Where(l => ForgeDefaults.ContainsKey(KeyOf(l)!));
            var hasEnabled = kept.Any(l => KeyOf(l) == "enabled");
            var output = (hasEnabled ? kept : kept.Append("enabled=true")).Concat(colours);
            File.WriteAllText(path, string.Join("\n", output) + "\n");
        }
        catch
        {
            // Cosmetic only -- never block a launch over it.
        }
    }

    // True when the file's colours are ones Origin wrote earlier, not a player's own.
    internal static bool IsEarlierOriginTheme(IReadOnlyDictionary<string, string> props)
    {
        int? Colour(string key) => props.TryGetValue(key, out var v) ? Decode(v) : null;

        // First seed: its wrong key is present and FML's real keys are absent or still at Forge's defaults.
        if (props.TryGetValue("backgroundColor", out var bc) && Decode(bc) == 0x050505
            && ForgeDefaults.All(d => Colour(d.Key) is null || Colour(d.Key) == d.Value))
            return true;

        return PreviousThemes.Any(t => t.All(c => Colour(c.Key) == c.Value));
    }

    internal static Dictionary<string, string> Parse(IEnumerable<string> lines)
    {
        var map = new Dictionary<string, string>();
        foreach (var line in lines)
        {
            var key = KeyOf(line);
            if (key == null) continue;
            var sep = line.IndexOfAny(new[] { '=', ':' });
            map[key] = line[(sep + 1)..].Trim();
        }
        return map;
    }

    // java.util.Properties-style key ("key=value" or "key:value"); null for comments/blank lines.
    private static string? KeyOf(string line)
    {
        var t = line.TrimStart();
        if (t.Length == 0 || t[0] == '#' || t[0] == '!') return null;
        var sep = line.IndexOfAny(new[] { '=', ':' });
        return sep < 0 ? null : line[..sep].Trim();
    }

    // Integer.decode for the forms FML writes back ("0x..", "#..", decimal).
    private static int? Decode(string v)
    {
        v = v.Trim();
        var style = System.Globalization.NumberStyles.HexNumber;
        if (v.StartsWith("0x", StringComparison.OrdinalIgnoreCase) && int.TryParse(v[2..], style, null, out var h)) return h;
        if (v.StartsWith("#") && int.TryParse(v[1..], style, null, out var h2)) return h2;
        return int.TryParse(v, out var d) ? d : null;
    }
}
