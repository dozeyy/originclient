using System.Text;
using System.Text.Json;

namespace OriginLauncher.App.Core.Skins;

/// <summary>
/// The pure half of player heads (no WPF, no network, no files — linked into
/// OriginLauncher.Tests): which cache key a player gets, how a Minecraft skin
/// becomes an 8x8 head (face + hat layer, the way the game draws it), which
/// default head a player without a skin gets, and where Mojang's profile
/// answer says the skin lives. Core/Skins/PlayerHeads.cs does the fetching,
/// decoding and caching around it.
/// </summary>
public static class SkinFace
{
    /// <summary>A head is 8x8 pixels, 4 bytes each (BGRA, straight alpha).</summary>
    public const int Size = 8;

    /// <summary>A cached skin older than this is fetched again (skins rarely change).</summary>
    public static readonly TimeSpan MaxAge = TimeSpan.FromHours(24);

    /// <summary>
    /// The player's cache key: their uuid as 32 lowercase hex digits (dashes dropped), or null
    /// when the value is not a real uuid ("pending-..." accounts, offline mode, blanks) — those
    /// players only ever get a default head and never cause a fetch or a file name.
    /// </summary>
    public static string? CacheKey(string? uuid)
    {
        var key = (uuid ?? "").Replace("-", "").Trim().ToLowerInvariant();
        if (key.Length != 32) return null;
        foreach (var c in key)
            if (!(c is >= '0' and <= '9' or >= 'a' and <= 'f')) return null;
        return key;
    }

    /// <summary>True once a cached skin written at <paramref name="writtenUtc"/> should be fetched again.</summary>
    public static bool IsStale(DateTime writtenUtc, DateTime nowUtc) =>
        nowUtc - writtenUtc > MaxAge || writtenUtc > nowUtc + TimeSpan.FromMinutes(5);

    /// <summary>
    /// The head from a decoded skin: the face (8x8 at 8,8) with the hat layer (8x8 at 40,8)
    /// alpha-blended over it, as 8x8 BGRA bytes. The face is forced opaque (the game draws
    /// it that way). Accepts the two skin layouts (64x64 and legacy 64x32) at any whole
    /// multiple of 64 wide up to MaxSkinWidth (HD skins); anything else returns null. On a legacy skin whose
    /// whole overlay area has no transparent pixel the hat is ignored, exactly like the game
    /// (old editors filled it with solid colour).
    /// </summary>
    public static byte[]? FaceWithHat(byte[] bgra, int width, int height)
    {
        if (!IsSkinSize(width, height)) return null;
        var legacy = height == width / 2;
        if (bgra.Length < width * height * 4) return null;
        var scale = width / 64;

        int At(int x, int y) => ((y * scale) * width + x * scale) * 4;

        var useHat = true;
        if (legacy)
        {
            // Vanilla's "Notch transparency hack": overlay x 32..63, y 0..31 all opaque = no overlay.
            useHat = false;
            for (var y = 0; y < 32 * scale && !useHat; y++)
            for (var x = 32 * scale; x < 64 * scale; x++)
                if (bgra[(y * width + x) * 4 + 3] < 128) { useHat = true; break; }
        }

        var head = new byte[Size * Size * 4];
        for (var y = 0; y < Size; y++)
        for (var x = 0; x < Size; x++)
        {
            var f = At(8 + x, 8 + y);
            int b = bgra[f], g = bgra[f + 1], r = bgra[f + 2];
            if (useHat)
            {
                var h = At(40 + x, 8 + y);
                var a = bgra[h + 3];
                b = Blend(bgra[h], b, a);
                g = Blend(bgra[h + 1], g, a);
                r = Blend(bgra[h + 2], r, a);
            }
            var o = (y * Size + x) * 4;
            head[o] = (byte)b;
            head[o + 1] = (byte)g;
            head[o + 2] = (byte)r;
            head[o + 3] = 255;
        }
        return head;
    }

    private static int Blend(int over, int under, int alpha) => (over * alpha + under * (255 - alpha) + 127) / 255;

    /// <summary>
    /// The classic default skin a player gets without one: Alex when Java's UUID.hashCode()
    /// is odd, Steve otherwise (the game's own rule before 1.19.3). No/invalid uuid = Steve.
    /// </summary>
    public static bool IsAlexDefault(string? uuid)
    {
        var key = CacheKey(uuid);
        if (key == null) return false;
        var msb = Convert.ToUInt64(key[..16], 16);
        var lsb = Convert.ToUInt64(key[16..], 16);
        var hilo = msb ^ lsb;
        var hash = (int)(hilo >> 32) ^ (int)hilo;
        return (hash & 1) == 1;
    }

    /// <summary>The default head for <paramref name="uuid"/> as 8x8 BGRA bytes (see <see cref="IsAlexDefault"/>).</summary>
    public static byte[] DefaultHead(string? uuid) => IsAlexDefault(uuid) ? Alex : Steve;

    /// <summary>What a session-server profile answer says about the player's skin.</summary>
    public enum ProfileAnswer
    {
        /// <summary>Not a Mojang profile for this player (captive portal, wrong player, garbage): try again later.</summary>
        Invalid,
        /// <summary>A real profile with no custom skin: the default head, safe to remember.</summary>
        NoSkin,
        /// <summary>A real profile with a skin on Mojang's texture host.</summary>
        Skin,
    }

    /// <summary>
    /// Reads Mojang's session-server profile answer
    /// (sessionserver.mojang.com/session/minecraft/profile/&lt;uuid&gt;) for player
    /// <paramref name="key"/>: the base64 "textures" property's textures.SKIN.url. Only an
    /// answer whose "id" is that player and whose textures decode counts as a profile; only
    /// Mojang's own texture host is accepted, always over https.
    /// </summary>
    public static ProfileAnswer ReadProfile(string? profileJson, string key, out string? skinUrl)
    {
        skinUrl = null;
        if (string.IsNullOrWhiteSpace(profileJson)) return ProfileAnswer.Invalid;
        try
        {
            using var profile = JsonDocument.Parse(profileJson);
            var root = profile.RootElement;
            if (root.ValueKind != JsonValueKind.Object
                || !root.TryGetProperty("id", out var id) || id.ValueKind != JsonValueKind.String
                || CacheKey(id.GetString()) != key
                || !root.TryGetProperty("properties", out var props) || props.ValueKind != JsonValueKind.Array)
                return ProfileAnswer.Invalid;
            foreach (var p in props.EnumerateArray())
            {
                if (!p.TryGetProperty("name", out var n) || n.GetString() != "textures") continue;
                if (!p.TryGetProperty("value", out var v) || v.GetString() is not { } b64) return ProfileAnswer.Invalid;
                using var tex = JsonDocument.Parse(Encoding.UTF8.GetString(Convert.FromBase64String(b64)));
                if (!tex.RootElement.TryGetProperty("textures", out var t) || t.ValueKind != JsonValueKind.Object)
                    return ProfileAnswer.Invalid;
                if (!t.TryGetProperty("SKIN", out var skin)) return ProfileAnswer.NoSkin;
                if (!skin.TryGetProperty("url", out var u)
                    || u.GetString() is not { } url
                    || !Uri.TryCreate(url, UriKind.Absolute, out var uri)
                    || !uri.Host.Equals("textures.minecraft.net", StringComparison.OrdinalIgnoreCase)
                    || uri.Scheme is not ("http" or "https"))
                    return ProfileAnswer.Invalid;
                skinUrl = "https://textures.minecraft.net" + uri.AbsolutePath;
                return ProfileAnswer.Skin;
            }
        }
        catch (Exception e) when (e is JsonException or FormatException or InvalidOperationException)
        {
        }
        return ProfileAnswer.Invalid;
    }

    /// <summary>The largest skin width taken (16x HD); bigger files are refused unread.</summary>
    public const int MaxSkinWidth = 1024;

    /// <summary>True for a skin size <see cref="FaceWithHat"/> can use, within <see cref="MaxSkinWidth"/>.</summary>
    public static bool IsSkinSize(int width, int height) =>
        width >= 64 && width <= MaxSkinWidth && width % 64 == 0 && (height == width || height == width / 2);

    /// <summary>
    /// A skin file's size from its PNG header (IHDR), read before anything is decoded, so an
    /// oversized or fake image is turned away without allocating its pixels. Null when the
    /// bytes are not a PNG.
    /// </summary>
    public static (int Width, int Height)? PngSize(byte[] png)
    {
        ReadOnlySpan<byte> signature = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
        if (png.Length < 24 || !png.AsSpan(0, 8).SequenceEqual(signature)) return null;
        if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') return null;
        var w = (png[16] << 24) | (png[17] << 16) | (png[18] << 8) | png[19];
        var h = (png[20] << 24) | (png[21] << 16) | (png[22] << 8) | png[23];
        return w <= 0 || h <= 0 ? null : (w, h);
    }

    // ---- default heads ----------------------------------------------------------------
    // Hand-drawn 8x8 stand-ins in the spirit of the two classic default skins (not copies
    // of Mojang's art): one letter per pixel, mapped through a small palette.

    private static readonly Dictionary<char, uint> Palette = new()
    {
        ['h'] = 0xFF2B1E10, // Steve: dark brown hair
        ['k'] = 0xFF3A2814, // Steve: hair, lighter strand
        ['s'] = 0xFFB98A6E, // Steve: skin
        ['t'] = 0xFFA7785D, // Steve: skin, shade
        ['n'] = 0xFF8E5F47, // Steve: nose / beard shadow
        ['m'] = 0xFF5A3420, // Steve: mouth / beard
        ['w'] = 0xFFF2F2F2, // eye white
        ['e'] = 0xFF4A3C8E, // Steve: iris
        ['o'] = 0xFFD9752B, // Alex: orange hair
        ['p'] = 0xFFB85B1E, // Alex: hair, shade
        ['a'] = 0xFFF0C9A5, // Alex: skin
        ['b'] = 0xFFE2B791, // Alex: skin, shade
        ['g'] = 0xFF3C8A4A, // Alex: iris
        ['l'] = 0xFFC9877A, // Alex: mouth
    };

    private static readonly byte[] Steve = Draw(
        "hhkhhhhh",
        "hhhhhkhh",
        "hsssssth",
        "ssssssss",
        "swessewt",
        "sssnnsss",
        "ssmmmmss",
        "stmssmts");

    private static readonly byte[] Alex = Draw(
        "oooooooo",
        "opoooooo",
        "oaaaooop",
        "aaaaaaao",
        "awgaagwa",
        "aaaaaaab",
        "aballaab",
        "aaaaaaaa");

    private static byte[] Draw(params string[] rows)
    {
        var px = new byte[Size * Size * 4];
        for (var y = 0; y < Size; y++)
        for (var x = 0; x < Size; x++)
        {
            var c = Palette[rows[y][x]];
            var o = (y * Size + x) * 4;
            px[o] = (byte)c;
            px[o + 1] = (byte)(c >> 8);
            px[o + 2] = (byte)(c >> 16);
            px[o + 3] = (byte)(c >> 24);
        }
        return px;
    }
}
