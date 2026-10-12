using System.Globalization;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Sync;

// Vanilla options.txt: one `key:value` per line, split at the FIRST ':' (so
// `lastServer:mc.example.net:25565` and `fullscreenResolution:1920x1080@60:24`
// keep their colons). Holds keybinds, mouse, FOV, sounds, GUI scale, chat and
// the video settings.
//
// How the loaders treat what we write (checked in decompiled vanilla):
//  * 1.16.5 (Options.load, an if-chain per key): an unknown key matches
//    nothing and is ignored; a bad value throws inside a per-key try/catch
//    ("Skipping bad option") and only that key keeps its default.
//  * 1.18.x (processOptions) and 1.19+ up to 1.21.11 (OptionInstance codecs):
//    unknown keys are never looked up, so they are ignored too. BUT there is
//    no per-key try/catch any more: keybind values go through
//    InputConstants.getKey ("Unknown key name"), 1.18's ao through
//    Integer.parseInt, and 1.19+ codec values through a lenient JSON parser
//    whose syntax errors throw — each aborts the REST of the load ("Failed to
//    load options") and the game then saves defaults over everything after
//    that line. So every value we write must be a single well-formed token
//    (IsSafeValue) and keybinds must be real key names (the GLFW name table
//    is identical, 131 names, in 1.16.4 and 1.21.11).
//
// Per-key format differences between eras, and what we do about each:
//  * ao: 0/1/2 (off/min/max) up to 1.19.2, true/false from 1.19.3 (vanilla's
//    OptionsAmbientOcclusionFix, data version 3214: 0 -> false, 1/2 -> true).
//    Compared in that boolean form; written as true/false, which every era
//    reads (old eras: "true" -> MAX, "false" -> OFF).
//  * mainHand, renderClouds: written bare (left / fast / true) by 1.16.5-1.18.2,
//    as JSON strings ("left" / "fast" / "true") by 1.19+. An old-era loader
//    reading the quoted form silently falls back to the default, so values are
//    compared unquoted and written bare into old eras (new eras read both).
//  * resourcePacks / incompatibleResourcePacks: NOT synced. Pack FILES aren't
//    synced, vanilla prunes any listed pack that isn't in the instance, and
//    the pruned list would then win as "newest" and wipe packs elsewhere. Also
//    built-in pack ids differ by era ("programer_art" -> "programmer_art",
//    vanilla's OptionsProgrammerArtFix at data version 3201).
//  * version: never synced — vanilla's DataFixer upgrades the file from it.
//  * startedCleanly: per-instance crash bookkeeping (1.21.2+), not a setting.
//  * graphicsMode (<= 1.21.10) vs graphicsPreset & co (1.21.11, vanilla's
//    OptionsGraphicsModeSplitFix) and showNowPlayingToast (1.21.6-1.21.10) vs
//    musicToast (1.21.11): different keys, each syncs between the versions
//    that have it and is ignored by the rest — never translated.
public sealed class OptionsTxtFormat : ISyncFormat
{
    public static readonly OptionsTxtFormat Instance = new();

    private static readonly HashSet<string> ExcludedKeys = new(StringComparer.Ordinal)
    {
        "version", "resourcePacks", "incompatibleResourcePacks", "startedCleanly",
    };

    // Read raw (no JSON parsing) by every era — free text is fine.
    private static readonly HashSet<string> RawTextKeys = new(StringComparer.Ordinal)
    {
        "lang", "lastServer", "tutorialStep", "fullscreenResolution",
    };

    // Free text that 1.17/1.18 store raw (`soundDevice:Speakers (Realtek)`)
    // but 1.19+ store as a JSON string (`soundDevice:"Speakers (Realtek)"`).
    // Written raw into old eras and always quoted into 1.19+, where a bare
    // value with spaces would throw in the JSON parser.
    private static readonly HashSet<string> TextKeysQuotedInCodecEra = new(StringComparer.Ordinal)
    {
        "soundDevice",
    };

    // 0..1 sliders. 1.19+ codecs reject anything outside [0,1] and fall back to
    // the default, which would then sync back as the "newest" value.
    private static readonly HashSet<string> UnitIntervalKeys = new(StringComparer.Ordinal)
    {
        "gamma", "mouseSensitivity", "chatOpacity", "textBackgroundOpacity", "chatScale", "chatWidth",
        "chatHeightFocused", "chatHeightUnfocused", "chatLineSpacing", "screenEffectScale", "fovEffectScale",
    };

    // The vanilla data version of each Origin-supported Minecraft version —
    // written as `version:` when Settings Sync creates a brand-new options.txt,
    // so vanilla runs NO data fixers over values that are already in its own
    // format (a missing version line means "version 0": every fixer runs).
    // 1.19.4 and up are from each version's own version.json; the four older
    // ones are the published data versions.
    private static readonly Dictionary<string, int> DataVersions = new(StringComparer.Ordinal)
    {
        ["1.16.5"] = 2586, ["1.17.1"] = 2730, ["1.18.2"] = 2975, ["1.19.2"] = 3120,
        ["1.19.3"] = 3218, ["1.19.4"] = 3337, ["1.20"] = 3463, ["1.20.1"] = 3465,
        ["1.20.2"] = 3578, ["1.20.4"] = 3700, ["1.21"] = 3953, ["1.21.1"] = 3955,
        ["1.21.2"] = 4080, ["1.21.3"] = 4082, ["1.21.4"] = 4189, ["1.21.6"] = 4435,
        ["1.21.7"] = 4438, ["1.21.8"] = 4440, ["1.21.10"] = 4556, ["1.21.11"] = 4671,
    };

    // Keybind values: GLFW key names, mouse buttons (named or numbered) and raw scancodes.
    private static readonly Regex KeybindValue = new(@"^(key\.(keyboard|mouse)\.[a-z0-9_.]+|scancode\.\d+)$", RegexOptions.CultureInvariant);
    private static readonly Regex Number = new(@"^-?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$", RegexOptions.CultureInvariant);
    private static readonly Regex BareWord = new(@"^[A-Za-z0-9_.+\-]*$", RegexOptions.CultureInvariant);

    public bool AllowsDeletions => false;

    public Dictionary<string, string>? Read(string path)
    {
        var file = ReadLines(path);
        return file == null ? null : ToMap(file.Value.Lines);
    }

    public bool Rejects(string key, string raw) => ExcludedKeys.Contains(key) || !IsSafeValue(key, raw);

    public bool Equivalent(string key, string a, string b)
    {
        var ca = Canonical(key, a);
        var cb = Canonical(key, b);
        if (string.Equals(ca, cb, StringComparison.Ordinal)) return true;
        // 0.5 vs 0.50, 1 vs 1.0: the same number however an era printed it.
        return TryNumber(ca, out var x) && TryNumber(cb, out var y) && Math.Abs(x - y) <= 1e-6;
    }

    public string BaselineOf(string raw) => raw;

    public bool MatchesBaseline(string key, string raw, string baseline) => Equivalent(key, raw, baseline);

    public Dictionary<string, string>? Apply(string path, IReadOnlyDictionary<string, string?> desired, SyncInstance target)
    {
        List<string> lines;
        var newline = Environment.NewLine;
        if (File.Exists(path))
        {
            var read = ReadLines(path);
            if (read == null) return null; // corrupt: leave it alone
            (lines, newline) = read.Value;
        }
        else
        {
            lines = new List<string>();
        }

        var changed = false;
        foreach (var (key, raw) in desired)
        {
            if (raw == null || Rejects(key, raw)) continue;
            var encoded = Encode(key, raw, target.Version);
            if (encoded == null) continue;

            var index = LastIndexOfKey(lines, key);
            if (index >= 0)
            {
                if (Equivalent(key, ValueOf(lines[index]), encoded)) continue;
                lines[index] = key + ":" + encoded;
            }
            else
            {
                lines.Add(key + ":" + encoded);
            }
            changed = true;
        }

        if (!changed) return ToMap(lines);

        if (!ToMap(lines).ContainsKey("version"))
        {
            // Without a version line vanilla treats the file as version 0 and runs
            // every data fixer over it (1.13's keybind rewrite included). Only
            // write when we can stamp the instance's own data version — e.g. a
            // brand-new instance, whose options.txt Settings Sync creates so its
            // very first launch already has the player's settings.
            if (!DataVersions.TryGetValue(target.Version, out var dataVersion)) return null;
            lines.Insert(0, $"version:{dataVersion}");
        }

        AtomicFile.WriteAllText(path, string.Join(newline, lines) + newline);
        return ToMap(lines);
    }

    // ---- value rules ----

    // Values that can't break any era's loader (see the class comment).
    private static bool IsSafeValue(string key, string raw)
    {
        if (raw.IndexOfAny(new[] { '\r', '\n', '\0' }) >= 0) return false;
        if (key.StartsWith("key_", StringComparison.Ordinal)) return KeybindValue.IsMatch(raw.Trim());
        if (RawTextKeys.Contains(key) || TextKeysQuotedInCodecEra.Contains(key)) return true;
        var value = raw.Trim();
        return value is "true" or "false"
            || Number.IsMatch(value)
            || BareWord.IsMatch(value)
            || IsJsonString(value);
    }

    // The era-neutral form two values are compared in.
    private static string Canonical(string key, string raw)
    {
        var value = raw.Trim();
        if (IsJsonString(value)) value = JsonSerializer.Deserialize<string>(value) ?? "";
        if (key == "ao")
        {
            value = value switch
            {
                "0" => "false",
                "1" or "2" => "true",
                _ => value,
            };
        }
        return value;
    }

    // The stored value in the form the TARGET version's loader reads, or null
    // when it must not be written there.
    private static string? Encode(string key, string raw, string targetVersion)
    {
        var canonical = Canonical(key, raw);
        var codecEra = McVersion.AtLeast(targetVersion, "1.19");

        if (codecEra && (UnitIntervalKeys.Contains(key) || key.StartsWith("soundCategory_", StringComparison.Ordinal))
            && TryNumber(canonical, out var unit) && (unit < 0 || unit > 1))
            return null;

        if (key == "ao" || RawTextKeys.Contains(key) || key.StartsWith("key_", StringComparison.Ordinal))
            return canonical;
        if (!codecEra)
            return canonical; // old eras only understand the bare form
        if (TextKeysQuotedInCodecEra.Contains(key))
            return JsonSerializer.Serialize(canonical);
        // Codec era: keep a JSON string as JSON (soundDevice may hold spaces);
        // a bare token is read the same way by the lenient parser.
        var trimmed = raw.Trim();
        return IsJsonString(trimmed) ? trimmed : canonical;
    }

    private static bool IsJsonString(string value)
    {
        if (value.Length < 2 || value[0] != '"' || value[^1] != '"') return false;
        try
        {
            using var document = JsonDocument.Parse(value);
            return document.RootElement.ValueKind == JsonValueKind.String;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    private static bool TryNumber(string value, out double number)
    {
        number = 0;
        return Number.IsMatch(value)
            && double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out number);
    }

    // ---- file shape ----

    // The file's lines plus the newline it uses (Minecraft writes the OS one;
    // a rewrite keeps whatever the file already has).
    private static (List<string> Lines, string Newline)? ReadLines(string path)
    {
        try
        {
            var text = File.ReadAllText(path, Encoding.UTF8);
            // A crash mid-write can leave a file full of NUL bytes.
            if (text.Contains('\0')) return null;
            var newline = text.Contains("\r\n") ? "\r\n" : text.Contains('\n') ? "\n" : Environment.NewLine;
            var lines = text.Replace("\r\n", "\n").Split('\n').ToList();
            if (lines.Count > 0 && lines[^1].Length == 0) lines.RemoveAt(lines.Count - 1);
            return (lines, newline);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    private static Dictionary<string, string> ToMap(List<string> lines)
    {
        // Later lines win, exactly like vanilla's load (it fills a map line by line).
        var map = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var line in lines)
        {
            var colon = line.IndexOf(':');
            if (colon <= 0) continue; // vanilla skips these too ("Skipping bad option")
            map[line[..colon]] = line[(colon + 1)..];
        }
        return map;
    }

    private static int LastIndexOfKey(List<string> lines, string key)
    {
        for (var i = lines.Count - 1; i >= 0; i--)
        {
            if (lines[i].IndexOf(':') == key.Length && lines[i].StartsWith(key, StringComparison.Ordinal))
                return i;
        }
        return -1;
    }

    private static string ValueOf(string line) => line[(line.IndexOf(':') + 1)..];
}
