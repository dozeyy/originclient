using System.IO;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Sync;

/// <summary>
/// How 1.8.9 numbers the keys whose meaning depends on the keyboard layout (letters, digits, punctuation), so Settings
/// Sync can carry them between 1.8.9 and the modern versions without moving them.
///
/// 1.13+ (GLFW; SDL on 26.3) names a key by its PLACE: <c>key.keyboard.w</c> is the key where W sits on a US keyboard,
/// whatever is printed on it. 1.8.9 (LWJGL 2) does the same on the Mac, but on Windows it numbers a key by the
/// CHARACTER the layout gives it (WindowsKeyboard.handleKey -> WindowsKeycodes.mapVirtualKeyToLWJGLCode(virtual key))
/// and on Linux by the X keysym. On a French (AZERTY) keyboard 1.8.9's "Z" (code 44) is the key 1.21.1 calls
/// <c>key.keyboard.w</c>. Matching the two by name, as Settings Sync used to (and vanilla's one-time 1.13 upgrade
/// does), is right only on a US layout: fixing forward on 1.8.9 broke it on 1.21.1 and back again (bug run 10, INPUT-1).
///
/// A "place" here is the code a key has on a US layout - its DirectInput scancode, i.e. where it physically is - which
/// is what OptionsTxtFormat's GLFW name table is keyed by. <see cref="ToLive"/> / <see cref="ToPlace"/> convert between
/// a place and what 1.8.9 really stores on this PC; null means "no counterpart here": that key is then not synced
/// (each version keeps its own bind), never written as a different key. Keys outside <see cref="DependsOnLayout"/>
/// (F-keys, arrows, modifiers, keypad, mouse) are the same on every layout and pass through unchanged.
/// The 1.8.9 mod does the same for Origin's own hotkeys (versions/1.8.9 util/KeyCodes).
/// </summary>
public sealed class LegacyKeyLayout
{
    // Places (US-layout LWJGL 2 codes) that can carry a different character on another layout: the digit row with
    // - and =, the three letter rows with their punctuation, ` \ and 86, the extra ISO key next to left Shift.
    private static readonly HashSet<int> LayoutPlaces = BuildLayoutPlaces();

    private static HashSet<int> BuildLayoutPlaces()
    {
        var places = new HashSet<int> { 86 };
        foreach (var (first, last) in new[] { (2, 13), (16, 27), (30, 41), (43, 53) })
            for (var code = first; code <= last; code++) places.Add(code);
        return places;
    }

    // Windows virtual key -> the LWJGL 2 code 1.8.9 reports for it: LWJGL 2's own WindowsKeycodes table, limited to
    // the virtual keys a layout can put on a LayoutPlaces key (anything else reads as KEY_NONE there too).
    private static readonly Dictionary<int, int> VirtualKeyToLegacy = BuildVirtualKeyTable();

    private static Dictionary<int, int> BuildVirtualKeyTable()
    {
        var table = new Dictionary<int, int>
        {
            [0xBA] = 39, // VK_OEM_1      -> KEY_SEMICOLON
            [0xBB] = 13, // VK_OEM_PLUS   -> KEY_EQUALS
            [0xBC] = 51, // VK_OEM_COMMA  -> KEY_COMMA
            [0xBD] = 12, // VK_OEM_MINUS  -> KEY_MINUS
            [0xBE] = 52, // VK_OEM_PERIOD -> KEY_PERIOD
            [0xBF] = 53, // VK_OEM_2      -> KEY_SLASH
            [0xC0] = 41, // VK_OEM_3      -> KEY_GRAVE
            [0xDB] = 26, // VK_OEM_4      -> KEY_LBRACKET
            [0xDC] = 43, // VK_OEM_5      -> KEY_BACKSLASH
            [0xDD] = 27, // VK_OEM_6      -> KEY_RBRACKET
            [0xDE] = 40, // VK_OEM_7      -> KEY_APOSTROPHE
            [0xDF] = 41, // VK_OEM_8      -> KEY_GRAVE (LWJGL 2 folds it into the same code)
        };
        // Digits and letters: the virtual key is the character itself, the LWJGL 2 code is its US place.
        foreach (var (first, keys) in new[] { (2, "1234567890"), (16, "QWERTYUIOP"), (30, "ASDFGHJKL"), (44, "ZXCVBNM") })
            for (var i = 0; i < keys.Length; i++) table[keys[i]] = first + i;
        return table;
    }

    /// <summary>The US layout and the Mac: a key's 1.8.9 code is its place.</summary>
    public static readonly LegacyKeyLayout UsPlaces = new(null);

    /// <summary>A layout we can't read (Linux, not US): no layout-dependent key is synced with 1.8.9.</summary>
    public static readonly LegacyKeyLayout Unknown = new(new Dictionary<int, int>());

    private static readonly Lazy<LegacyKeyLayout> ThisPcLayout = new(Detect);

    /// <summary>
    /// The layout 1.8.9 will see on this PC: read once per launcher run, so one Settings Sync pass never compares
    /// with two different tables.
    /// </summary>
    public static LegacyKeyLayout ThisPc => ThisPcLayout.Value;

    // Tests only, so no test depends on the keyboard of the machine running it. TestDefault replaces the live
    // reading for a whole test run (the test project's module initializer sets US); Pin() replaces it for the code
    // one test runs (AsyncLocal: tests running in parallel never see each other's pin). The launcher sets neither.
    internal static LegacyKeyLayout? TestDefault;
    private static readonly AsyncLocal<LegacyKeyLayout?> Pinned = new();

    /// <summary>The layout Settings Sync uses: <see cref="ThisPc"/>, unless a test pinned one.</summary>
    public static LegacyKeyLayout Current => Pinned.Value ?? TestDefault ?? ThisPc;

    /// <summary>Tests: use <paramref name="layout"/> until the returned handle is disposed.</summary>
    internal static IDisposable Pin(LegacyKeyLayout layout)
    {
        var before = Pinned.Value;
        Pinned.Value = layout;
        return new Unpin(before);
    }

    private sealed class Unpin(LegacyKeyLayout? before) : IDisposable
    {
        public void Dispose() => Pinned.Value = before;
    }

    // place -> live code; null = identity (UsPlaces). Places missing from the map have no counterpart.
    private readonly Dictionary<int, int>? _placeToLive;
    private readonly Dictionary<int, int>? _liveToPlace;

    private LegacyKeyLayout(Dictionary<int, int>? placeToLive)
    {
        _placeToLive = placeToLive;
        _liveToPlace = placeToLive?.ToDictionary(kv => kv.Value, kv => kv.Key);
    }

    /// <summary>Does this LWJGL 2 code (a place or a live code) depend on the layout?</summary>
    public static bool DependsOnLayout(int code) => LayoutPlaces.Contains(code);

    /// <summary>The code 1.8.9 stores for the key at <paramref name="place"/>, or null when this layout has none.</summary>
    public int? ToLive(int place)
    {
        if (_placeToLive == null || !DependsOnLayout(place)) return place;
        return _placeToLive.TryGetValue(place, out var live) ? live : null;
    }

    /// <summary>Where the key 1.8.9 stores as <paramref name="live"/> sits, or null when no key on this layout gives it.</summary>
    public int? ToPlace(int live)
    {
        if (_liveToPlace == null || !DependsOnLayout(live)) return live;
        return _liveToPlace.TryGetValue(live, out var place) ? place : null;
    }

    /// <summary>
    /// A Windows layout, from its scancode -> virtual key table (MapVirtualKeyEx with MAPVK_VSC_TO_VK; 0 = none).
    /// Two places giving the same 1.8.9 code (UK: ' and ` both read as KEY_GRAVE) can't be told apart, so neither syncs.
    /// </summary>
    public static LegacyKeyLayout FromWindows(Func<int, int> scancodeToVirtualKey)
    {
        var placeToLive = new Dictionary<int, int>();
        var seen = new Dictionary<int, int>(); // live code -> how many places give it
        foreach (var place in LayoutPlaces)
        {
            if (!VirtualKeyToLegacy.TryGetValue(scancodeToVirtualKey(place), out var live)) continue;
            placeToLive[place] = live;
            seen[live] = seen.GetValueOrDefault(live) + 1;
        }
        foreach (var place in placeToLive.Where(kv => seen[kv.Value] > 1).Select(kv => kv.Key).ToList())
            placeToLive.Remove(place);
        return new LegacyKeyLayout(placeToLive);
    }

    /// <summary>
    /// A Linux (X11) layout from its XKB layout + variant. LWJGL 2 numbers keys by keysym there and the keysym table
    /// isn't readable from here, so only plain US is trusted; anything else syncs no layout key with 1.8.9.
    /// Nothing configured (null) keeps the US reading, as before this check existed.
    /// </summary>
    public static LegacyKeyLayout FromXkb(string? layout, string? variant)
    {
        if (string.IsNullOrWhiteSpace(layout)) return UsPlaces;
        // A multi-layout setup ("us,ru") starts in the first one.
        var first = layout.Split(',')[0].Trim();
        var firstVariant = (variant ?? "").Split(',')[0].Trim();
        return first == "us" && (firstVariant is "" or "basic") ? UsPlaces : Unknown;
    }

    private static readonly Regex XorgOption = new(@"Option\s+""Xkb(Layout|Variant)""\s+""([^""]*)""", RegexOptions.CultureInvariant);
    private static readonly Regex DebianLine = new(@"^\s*XKB(LAYOUT|VARIANT)\s*=\s*""?([^""\r\n]*)""?", RegexOptions.CultureInvariant | RegexOptions.Multiline);

    /// <summary>XKB layout and variant from /etc/X11/xorg.conf.d/00-keyboard.conf (localectl) or /etc/default/keyboard (Debian).</summary>
    internal static (string? Layout, string? Variant) ParseXkb(string text)
    {
        string? layout = null, variant = null;
        foreach (Match m in XorgOption.Matches(text).Concat(DebianLine.Matches(text)))
        {
            if (m.Groups[1].Value.Equals("layout", StringComparison.OrdinalIgnoreCase)) layout ??= m.Groups[2].Value;
            else variant ??= m.Groups[2].Value;
        }
        return (layout, variant);
    }

    private static LegacyKeyLayout Detect()
    {
        try
        {
            if (OperatingSystem.IsWindows())
            {
                // The layout of the calling thread: the player's default input language, which is what the game
                // window starts with too.
                var hkl = GetKeyboardLayout(0);
                return FromWindows(scancode => (int)MapVirtualKeyEx((uint)scancode, MapvkVscToVk, hkl));
            }
            if (OperatingSystem.IsLinux())
            {
                var env = Environment.GetEnvironmentVariable("XKB_DEFAULT_LAYOUT");
                if (!string.IsNullOrWhiteSpace(env))
                    return FromXkb(env, Environment.GetEnvironmentVariable("XKB_DEFAULT_VARIANT"));
                foreach (var file in new[] { "/etc/X11/xorg.conf.d/00-keyboard.conf", "/etc/default/keyboard" })
                {
                    if (!File.Exists(file)) continue;
                    var (layout, variant) = ParseXkb(File.ReadAllText(file));
                    if (layout != null) return FromXkb(layout, variant);
                }
                return UsPlaces;
            }
            return UsPlaces; // macOS: LWJGL 2 numbers keys by place (MacOSXNativeKeyboard)
        }
        catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException or IOException or UnauthorizedAccessException)
        {
            return Unknown; // can't tell: sync no layout key with 1.8.9 rather than a wrong one
        }
    }

    private const uint MapvkVscToVk = 1;

    [DllImport("user32.dll")]
    private static extern IntPtr GetKeyboardLayout(uint threadId);

    [DllImport("user32.dll")]
    private static extern uint MapVirtualKeyEx(uint code, uint mapType, IntPtr hkl);
}
