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
//  * 1.8.9 (GameSettings.loadOptions, Forge + OptiFine) and
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
//    that have it and is ignored by the rest — never translated. Same for
//    1.8.9's own keys (fancyGraphics, clouds, stream*): nothing else has them.
//
// 1.8.9 (the one pre-1.13 version, on Forge — decided 2026-10-13): synced,
// with the two translations its values need, so a PvP player's keybinds
// follow them between 1.8.9 and the modern versions:
//  * keybinds: 1.8.9 stores LWJGL 2 key codes (`key_key.forward:17`, mouse
//    buttons as -100 + button), 1.13+ GLFW names (`key.keyboard.w`). Compared
//    as the GLFW name (LegacyKeyNames — the same table as vanilla 1.13's
//    OptionsKeyLwjgl3Fix) and written as a code into 1.8.9. A key with no
//    counterpart in the other era (a raw scancode, F20, an odd code) is never
//    written there: that version keeps its own binding.
//  * lang: `en_US` up to 1.10, `en_us` from 1.11 (vanilla's
//    OptionsLowerCaseLanguageFix). Compared lower-case, written xx_YY into 1.8.9.
//  * no `version:` line: 1.8.9 predates data versions (1.9 introduced them), so
//    a brand-new 1.8.9 options.txt is created without one — correct there.
// Everything else 1.8.9 shares with the later eras is already the same format
// (fov/gamma/sensitivity as 0..1 fractions, ao 0/1/2 — 1.8.9 also reads
// true/false — soundCategory_*, chat sliders, guiScale, renderDistance), and
// its loader skips any bad line on its own ("Skipping bad option").
// servers.dat and Origin's originclient*.json are the same files on 1.8.9.
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

    // The era cliffs above, as the first version on the new side.
    private const string GlfwKeyNamesSince = "1.13";
    private const string LowerCaseLangSince = "1.11";
    private const string DataVersionsSince = "1.9";

    // The options 1.8.9 (GameSettings) reads, besides its key_ / soundCategory_ / modelPart_ / stream* families.
    private static readonly HashSet<string> LegacyKeys = new(StringComparer.Ordinal)
    {
        "mouseSensitivity", "fov", "gamma", "saturation", "invertYMouse", "renderDistance", "guiScale", "particles",
        "bobView", "anaglyph3d", "maxFps", "fboEnable", "difficulty", "fancyGraphics", "ao", "renderClouds",
        "lastServer", "lang", "chatVisibility", "chatColors", "chatLinks", "chatLinksPrompt", "chatOpacity",
        "snooperEnabled", "fullscreen", "enableVsync", "useVbo", "hideServerAddress", "advancedItemTooltips",
        "pauseOnLostFocus", "touchscreen", "overrideWidth", "overrideHeight", "heldItemTooltips", "chatHeightFocused",
        "chatHeightUnfocused", "chatScale", "chatWidth", "showInventoryAchievementHint", "mipmapLevels",
        "forceUnicodeFont", "allowBlockAlternatives", "reducedDebugInfo", "useNativeTransport", "entityShadows",
        "realmsNotifications",
    };

    // 1.8.9 options no later version (1.16.5 and up) has.
    private static readonly HashSet<string> LegacyOnlyKeys = new(StringComparer.Ordinal)
    {
        "fancyGraphics", "anaglyph3d", "fboEnable", "useVbo", "allowBlockAlternatives", "showInventoryAchievementHint",
    };

    /// <summary>
    /// Does <paramref name="version"/> know this options.txt key? For an import that carries settings to ANOTHER
    /// version (SettingsMigrator.CarryOptions): 1.8.9 gets only the keys it reads; the modern eras drop 1.8.9's own keys
    /// and the keys that exist only in some modern versions (the graphicsMode / graphicsPreset split at 1.21.11, the
    /// now-playing toast of 1.21.6-1.21.10). Settings Sync doesn't use this: every loader ignores a key it doesn't know.
    /// </summary>
    /// Boundaries read from each version's Options class (javap, 2026-10-19): graphicsMode 1.16.5-1.21.10;
    /// graphicsPreset + musicToast 1.21.11 and 26.1; showNowPlayingToast 1.21.6-1.21.10 (absent on 1.21.1/4/5).
    public static bool KnownIn(string key, string version)
    {
        if (!McVersion.AtLeast(version, GlfwKeyNamesSince))
            return LegacyKeys.Contains(key) || key.StartsWith("key_", StringComparison.Ordinal)
                || key.StartsWith("soundCategory_", StringComparison.Ordinal) || key.StartsWith("modelPart_", StringComparison.Ordinal)
                || key.StartsWith("stream", StringComparison.Ordinal);
        if (LegacyOnlyKeys.Contains(key) || key.StartsWith("stream", StringComparison.Ordinal)) return false;
        return key switch
        {
            "graphicsMode" => !McVersion.AtLeast(version, "1.21.11"),
            "graphicsPreset" or "musicToast" => McVersion.AtLeast(version, "1.21.11"),
            "showNowPlayingToast" => McVersion.AtLeast(version, "1.21.6") && !McVersion.AtLeast(version, "1.21.11"),
            _ => true,
        };
    }

    // LWJGL 2 key code (1.8.9's options.txt) -> GLFW key name (1.13+), as in
    // vanilla 1.13's OptionsKeyLwjgl3Fix. 0 is "unbound"; mouse button b is
    // stored as -100 + b.
    private static readonly Dictionary<int, string> LegacyKeyNames = BuildLegacyKeyNames();
    private static readonly Dictionary<string, int> LegacyKeyCodes =
        LegacyKeyNames.ToDictionary(kv => kv.Value, kv => kv.Key, StringComparer.Ordinal);

    private static Dictionary<int, string> BuildLegacyKeyNames()
    {
        var names = new Dictionary<int, string>
        {
            [0] = "unknown", [1] = "escape", [11] = "0", [12] = "minus", [13] = "equal",
            [14] = "backspace", [15] = "tab", [26] = "left.bracket", [27] = "right.bracket",
            [28] = "enter", [29] = "left.control", [39] = "semicolon", [40] = "apostrophe",
            [41] = "grave.accent", [42] = "left.shift", [43] = "backslash", [51] = "comma",
            [52] = "period", [53] = "slash", [54] = "right.shift", [55] = "keypad.multiply",
            [56] = "left.alt", [57] = "space", [58] = "caps.lock", [69] = "num.lock",
            [70] = "scroll.lock", [71] = "keypad.7", [72] = "keypad.8", [73] = "keypad.9",
            [74] = "keypad.subtract", [75] = "keypad.4", [76] = "keypad.5", [77] = "keypad.6",
            [78] = "keypad.add", [79] = "keypad.1", [80] = "keypad.2", [81] = "keypad.3",
            [82] = "keypad.0", [83] = "keypad.decimal", [87] = "f11", [88] = "f12",
            [100] = "f13", [101] = "f14", [102] = "f15", [103] = "f16", [104] = "f17",
            [105] = "f18", [113] = "f19", [141] = "keypad.equal", [156] = "keypad.enter",
            [157] = "right.control", [181] = "keypad.divide", [183] = "print.screen",
            [184] = "right.alt", [197] = "pause", [199] = "home", [200] = "up",
            [201] = "page.up", [203] = "left", [205] = "right", [207] = "end", [208] = "down",
            [209] = "page.down", [210] = "insert", [211] = "delete", [219] = "left.win",
            [220] = "right.win", [221] = "menu",
        };
        for (var digit = 1; digit <= 9; digit++) names[1 + digit] = digit.ToString(CultureInfo.InvariantCulture);
        for (var f = 1; f <= 10; f++) names[58 + f] = "f" + f.ToString(CultureInfo.InvariantCulture);
        // Letter rows, in LWJGL 2 (scancode) order.
        foreach (var (first, letters) in new[] { (16, "qwertyuiop"), (30, "asdfghjkl"), (44, "zxcvbnm") })
            for (var i = 0; i < letters.Length; i++) names[first + i] = letters[i].ToString();

        var keys = names.ToDictionary(kv => kv.Key, kv => "key.keyboard." + kv.Value);
        keys[-100] = "key.mouse.left";
        keys[-99] = "key.mouse.right";
        keys[-98] = "key.mouse.middle";
        for (var button = 3; button <= 7; button++)
            keys[-100 + button] = "key.mouse." + (button + 1).ToString(CultureInfo.InvariantCulture);
        return keys;
    }

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

        if (!ToMap(lines).ContainsKey("version") && McVersion.AtLeast(target.Version, DataVersionsSince))
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
        // A 1.8.9 code counts in its GLFW form; one with no GLFW name is rejected.
        if (key.StartsWith("key_", StringComparison.Ordinal)) return KeybindValue.IsMatch(Canonical(key, raw));
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
        else if (key.StartsWith("key_", StringComparison.Ordinal)
                 && int.TryParse(value, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var code)
                 && LegacyKeyNames.TryGetValue(code, out var glfwName))
        {
            value = glfwName; // 1.8.9's LWJGL 2 code
        }
        else if (key == "lang")
        {
            value = value.ToLowerInvariant(); // en_US (1.8.9) == en_us
        }
        return value;
    }

    // The stored value in the form the TARGET version's loader reads, or null
    // when it must not be written there.
    private static string? Encode(string key, string raw, string targetVersion)
    {
        var canonical = Canonical(key, raw);
        var codecEra = McVersion.AtLeast(targetVersion, "1.19");

        if (key.StartsWith("key_", StringComparison.Ordinal) && !McVersion.AtLeast(targetVersion, GlfwKeyNamesSince))
            return LegacyKeyCodes.TryGetValue(canonical, out var code) ? code.ToString(CultureInfo.InvariantCulture) : null;
        if (key == "lang" && !McVersion.AtLeast(targetVersion, LowerCaseLangSince))
            return LegacyLanguageCode(canonical);

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

    // "en_us" -> "en_US": the language code form before 1.11 (region upper-case).
    private static string LegacyLanguageCode(string code)
    {
        var underscore = code.IndexOf('_');
        return underscore < 0 ? code : code[..underscore] + code[underscore..].ToUpperInvariant();
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
