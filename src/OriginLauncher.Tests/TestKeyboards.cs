using System.Runtime.CompilerServices;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.Tests;

/// <summary>
/// Keyboard layouts for the Settings Sync keybind tests (1.8.9 on Windows numbers letter keys by character, see
/// Core/Sync/LegacyKeyLayout.cs), given as Windows scancode -> virtual key tables, what MapVirtualKeyEx returns for
/// KBDUS / KBDFR / KBDGR / KBDUK. The module initializer pins US for the whole test run, so no test reads the
/// keyboard of the machine running it (Will's PC, a CI runner); a test wanting another layout pins it itself
/// (<c>using var _ = LegacyKeyLayout.Pin(TestKeyboards.Azerty)</c>) or hands it to <c>new OptionsTxtFormat(...)</c>.
/// </summary>
internal static class TestKeyboards
{
    [ModuleInitializer]
    internal static void PinUsForTheTestRun() => LegacyKeyLayout.TestDefault = LegacyKeyLayout.UsPlaces;

    private static readonly Dictionary<int, int> UsTable = BuildUs();

    private static Dictionary<int, int> BuildUs()
    {
        var t = new Dictionary<int, int>
        {
            [12] = 0xBD, [13] = 0xBB, [26] = 0xDB, [27] = 0xDD, [39] = 0xBA, [40] = 0xDE, [41] = 0xC0,
            [43] = 0xDC, [51] = 0xBC, [52] = 0xBE, [53] = 0xBF, [86] = 0xE2,
        };
        foreach (var (first, keys) in new[] { (2, "1234567890"), (16, "QWERTYUIOP"), (30, "ASDFGHJKL"), (44, "ZXCVBNM") })
            for (var i = 0; i < keys.Length; i++) t[first + i] = keys[i];
        return t;
    }

    /// <summary>A Windows layout: US with <paramref name="overrides"/> (scancode -> virtual key) on top.</summary>
    internal static LegacyKeyLayout Windows(Dictionary<int, int> overrides) =>
        LegacyKeyLayout.FromWindows(sc => overrides.TryGetValue(sc, out var vk) ? vk : UsTable.GetValueOrDefault(sc));

    /// <summary>Windows, US layout.</summary>
    internal static readonly LegacyKeyLayout Us = Windows(new());

    /// <summary>French AZERTY: A/Q and Z/W swapped, M where US has ';', and the punctuation keys move.</summary>
    internal static readonly LegacyKeyLayout Azerty = Windows(new()
    {
        [12] = 0xDB, [16] = 'A', [17] = 'Z', [26] = 0xDD, [27] = 0xBA, [30] = 'Q', [39] = 'M', [40] = 0xC0,
        [41] = 0xDE, [44] = 'W', [50] = 0xBC, [51] = 0xBE, [52] = 0xBF, [53] = 0xDF,
    });

    /// <summary>German QWERTZ: Y and Z swapped.</summary>
    internal static readonly LegacyKeyLayout Qwertz = Windows(new() { [21] = 'Z', [44] = 'Y' });

    /// <summary>UK: the ' and ` keys both read as LWJGL 2's KEY_GRAVE (VK_OEM_3 and VK_OEM_8).</summary>
    internal static readonly LegacyKeyLayout Uk = Windows(new() { [40] = 0xC0, [41] = 0xDF, [43] = 0xDE, [86] = 0xDC });
}
