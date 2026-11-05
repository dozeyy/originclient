using OriginLauncher.App.Core.Sync;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// Bug run 10, INPUT-1 / INPUT-14: Settings Sync keybinds between 1.8.9 (LWJGL 2: on Windows a key's code is the
/// CHARACTER its layout gives it) and 1.13+ (GLFW names: a key's PLACE), on non-US keyboards; and GLFW raw
/// scancodes, which 26.3 (SDL3) can't read. The Windows layouts are given as scancode -> virtual key tables (what
/// MapVirtualKeyEx returns for that layout), so these run on any OS.
/// </summary>
public sealed class LegacyKeyLayoutTests : IDisposable
{
    private readonly string _root = Directory.CreateTempSubdirectory("legacykeys").FullName;

    public void Dispose()
    {
        try { Directory.Delete(_root, true); } catch { /* best effort */ }
    }

    // ---- Windows layouts (KBDUS / KBDFR / KBDGR / KBDUK), scancode -> virtual key ----

    private static readonly Dictionary<int, int> Us = BuildUs();

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

    private static LegacyKeyLayout Windows(Dictionary<int, int> overrides) =>
        LegacyKeyLayout.FromWindows(sc => overrides.TryGetValue(sc, out var vk) ? vk : Us.GetValueOrDefault(sc));

    // French AZERTY: A/Q and Z/W swapped, M where US has ';', and the punctuation keys move.
    private static readonly LegacyKeyLayout Azerty = Windows(new()
    {
        [12] = 0xDB, [16] = 'A', [17] = 'Z', [26] = 0xDD, [27] = 0xBA, [30] = 'Q', [39] = 'M', [40] = 0xC0,
        [41] = 0xDE, [44] = 'W', [50] = 0xBC, [51] = 0xBE, [52] = 0xBF, [53] = 0xDF,
    });

    // German QWERTZ: Y and Z swapped.
    private static readonly LegacyKeyLayout Qwertz = Windows(new() { [21] = 'Z', [44] = 'Y' });

    // UK: the ' and ` keys both read as LWJGL 2's KEY_GRAVE (VK_OEM_3 and VK_OEM_8).
    private static readonly LegacyKeyLayout Uk = Windows(new() { [40] = 0xC0, [41] = 0xDF, [43] = 0xDE, [86] = 0xDC });

    private string? WriteTo(OptionsTxtFormat format, string version, string key, string value)
    {
        var dir = Directory.CreateDirectory(Path.Combine(_root, version + "-" + Guid.NewGuid().ToString("N"))).FullName;
        var path = Path.Combine(dir, "options.txt");
        File.WriteAllText(path, (version == "1.8.9" ? "" : "version:3955\n") + "fov:0.0\n");
        var map = format.Apply(path, new Dictionary<string, string?> { [key] = value }, new SyncInstance(version, dir));
        Assert.NotNull(map);
        return map!.GetValueOrDefault(key);
    }

    [Fact]
    public void A_US_Windows_layout_reads_every_key_at_its_US_place()
    {
        var us = Windows(new());
        for (var place = 2; place <= 53; place++)
        {
            if (!LegacyKeyLayout.DependsOnLayout(place)) continue;
            Assert.Equal(place, us.ToLive(place));
            Assert.Equal(place, us.ToPlace(place));
        }
        Assert.Null(us.ToLive(86)); // the ISO key: LWJGL 2 on Windows has no code for VK_OEM_102
    }

    [Fact]
    public void AZERTY_movement_keys_mean_the_same_keys_on_1_8_9_and_1_21_1()
    {
        var format = new OptionsTxtFormat(Azerty);

        // 1.21.1's ZQSD (key.keyboard.w / a = the places of US W and A) is 1.8.9's Z (44) and Q (16).
        Assert.Equal("44", WriteTo(format, "1.8.9", "key_key.forward", "key.keyboard.w"));
        Assert.Equal("16", WriteTo(format, "1.8.9", "key_key.left", "key.keyboard.a"));
        // ... and back: 1.8.9's Z / Q / M are the places 1.21.1 calls w / a / semicolon.
        Assert.Equal("key.keyboard.w", WriteTo(format, "1.21.1", "key_key.forward", "44"));
        Assert.Equal("key.keyboard.a", WriteTo(format, "1.21.1", "key_key.left", "16"));
        Assert.Equal("key.keyboard.semicolon", WriteTo(format, "1.21.1", "key_key.drop", "50"));
        // The fixed pair compares equal, so neither file is rewritten again (no ping-pong).
        Assert.True(format.Equivalent("key_key.forward", "44", "key.keyboard.w"));
        Assert.False(format.Equivalent("key_key.forward", "17", "key.keyboard.w"));
        // Keys that never depend on the layout are untouched.
        Assert.Equal("57", WriteTo(format, "1.8.9", "key_key.jump", "key.keyboard.space"));
        Assert.Equal("-100", WriteTo(format, "1.8.9", "key_key.attack", "key.mouse.left"));
        Assert.Equal("key.keyboard.left.shift", WriteTo(format, "1.21.1", "key_key.sneak", "42"));
    }

    [Fact]
    public void QWERTZ_Y_and_Z_land_on_the_same_keys_in_both_eras()
    {
        var format = new OptionsTxtFormat(Qwertz);
        Assert.Equal("44", WriteTo(format, "1.8.9", "key_key.chat", "key.keyboard.y"));     // US-Y place = the Z key
        Assert.Equal("21", WriteTo(format, "1.8.9", "key_key.chat", "key.keyboard.z"));
        Assert.Equal("key.keyboard.z", WriteTo(format, "1.21.1", "key_key.chat", "21")); // 1.8.9's Y = US-Z place
        Assert.Equal("54", WriteTo(format, "1.8.9", "key_key.sprint", "key.keyboard.right.shift"));
    }

    [Fact]
    public void A_character_two_keys_share_on_1_8_9_is_never_synced()
    {
        var format = new OptionsTxtFormat(Uk);
        // ' and ` both arrive as KEY_GRAVE (41): which key the player meant can't be known.
        Assert.Null(WriteTo(format, "1.8.9", "key_key.drop", "key.keyboard.apostrophe"));
        Assert.True(format.Rejects("key_key.drop", "41"));
        // The ISO key carries \ on UK: 1.8.9's KEY_BACKSLASH is GLFW's world.2.
        Assert.Equal("key.keyboard.world.2", WriteTo(format, "1.21.1", "key_key.drop", "43"));
    }

    [Fact]
    public void An_unreadable_layout_syncs_no_letter_key_with_1_8_9_but_keeps_the_rest()
    {
        var format = new OptionsTxtFormat(LegacyKeyLayout.Unknown);
        Assert.Null(WriteTo(format, "1.8.9", "key_key.forward", "key.keyboard.w"));
        Assert.True(format.Rejects("key_key.forward", "17"));
        Assert.Equal("57", WriteTo(format, "1.8.9", "key_key.jump", "key.keyboard.space"));
        Assert.Equal("key.keyboard.f5", WriteTo(format, "1.21.1", "key_key.togglePerspective", "63"));
    }

    [Fact]
    public void A_raw_scancode_never_reaches_26_3()
    {
        var format = new OptionsTxtFormat(LegacyKeyLayout.UsPlaces);
        Assert.Null(WriteTo(format, "26.3", "key_key.jump", "scancode.121"));
        Assert.Equal("scancode.121", WriteTo(format, "26.2", "key_key.jump", "scancode.121")); // GLFW still reads it
        Assert.Equal("key.keyboard.space", WriteTo(format, "26.3", "key_key.jump", "key.keyboard.space"));
    }

    [Theory]
    [InlineData("Section \"InputClass\"\n  Option \"XkbLayout\" \"us\"\n  Option \"XkbModel\" \"pc105\"\nEndSection\n", "us", null)]
    [InlineData("Section \"InputClass\"\n  Option \"XkbLayout\" \"fr\"\n  Option \"XkbVariant\" \"azerty\"\nEndSection\n", "fr", "azerty")]
    [InlineData("XKBMODEL=\"pc105\"\nXKBLAYOUT=\"de\"\nXKBVARIANT=\"\"\nXKBOPTIONS=\"\"\n", "de", "")]
    [InlineData("# nothing set\n", null, null)]
    public void Linux_layout_files_are_read(string text, string? layout, string? variant)
    {
        Assert.Equal((layout, variant), LegacyKeyLayout.ParseXkb(text));
    }

    [Theory]
    [InlineData("us", "", true)]
    [InlineData("us,ru", ",", true)]
    [InlineData(null, null, true)]   // nothing configured: the US reading, as before
    [InlineData("us", "dvorak", false)]
    [InlineData("fr", "", false)]
    [InlineData("de", null, false)]
    public void Only_plain_US_is_trusted_on_Linux(string? layout, string? variant, bool us)
    {
        var read = LegacyKeyLayout.FromXkb(layout, variant);
        Assert.Equal(us, ReferenceEquals(read, LegacyKeyLayout.UsPlaces));
    }
}
