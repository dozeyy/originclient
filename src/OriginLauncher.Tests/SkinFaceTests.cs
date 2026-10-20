using System.Text;
using OriginLauncher.App.Core.Skins;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>Player heads (Core/Skins/SkinFace): skin -> head, default heads, cache key, profile parsing.</summary>
public class SkinFaceTests
{
    // A blank skin (BGRA, all transparent) of the given size.
    private static byte[] Skin(int w, int h) => new byte[w * h * 4];

    private static void Put(byte[] skin, int w, int x, int y, byte b, byte g, byte r, byte a)
    {
        var o = (y * w + x) * 4;
        skin[o] = b; skin[o + 1] = g; skin[o + 2] = r; skin[o + 3] = a;
    }

    private static void Fill(byte[] skin, int w, int x0, int y0, int x1, int y1, byte b, byte g, byte r, byte a)
    {
        for (var y = y0; y < y1; y++)
        for (var x = x0; x < x1; x++)
            Put(skin, w, x, y, b, g, r, a);
    }

    private static (int B, int G, int R, int A) Px(byte[] head, int x, int y)
    {
        var o = (y * 8 + x) * 4;
        return (head[o], head[o + 1], head[o + 2], head[o + 3]);
    }

    [Fact]
    public void Face_is_cropped_from_8_8_and_forced_opaque()
    {
        var skin = Skin(64, 64);
        Fill(skin, 64, 8, 8, 16, 16, 10, 20, 30, 0); // face area, even with alpha 0
        Put(skin, 64, 8, 8, 1, 2, 3, 255);           // face top-left
        Put(skin, 64, 15, 15, 4, 5, 6, 255);         // face bottom-right
        Fill(skin, 64, 0, 0, 8, 8, 200, 200, 200, 255); // neighbour area (must not leak in)

        var head = SkinFace.FaceWithHat(skin, 64, 64)!;

        Assert.Equal(8 * 8 * 4, head.Length);
        Assert.Equal((1, 2, 3, 255), Px(head, 0, 0));
        Assert.Equal((4, 5, 6, 255), Px(head, 7, 7));
        Assert.Equal((10, 20, 30, 255), Px(head, 3, 4));
    }

    [Fact]
    public void Hat_layer_from_40_8_covers_the_face_where_opaque_and_blends_where_translucent()
    {
        var skin = Skin(64, 64);
        Fill(skin, 64, 8, 8, 16, 16, 0, 0, 0, 255);   // black face
        Put(skin, 64, 40, 8, 255, 255, 255, 255);      // hat pixel (0,0): opaque white
        Put(skin, 64, 41, 8, 255, 255, 255, 128);      // hat pixel (1,0): half white
        // hat pixel (2,0) stays transparent

        var head = SkinFace.FaceWithHat(skin, 64, 64)!;

        Assert.Equal((255, 255, 255, 255), Px(head, 0, 0));
        Assert.Equal((128, 128, 128, 255), Px(head, 1, 0));
        Assert.Equal((0, 0, 0, 255), Px(head, 2, 0));
    }

    [Fact]
    public void Legacy_skin_with_a_solid_overlay_area_ignores_the_hat_like_the_game()
    {
        var skin = Skin(64, 32);
        Fill(skin, 64, 8, 8, 16, 16, 0, 0, 0, 255);      // black face
        Fill(skin, 64, 32, 0, 64, 32, 0, 0, 255, 255);   // whole overlay area solid red (old editors did this)

        Assert.Equal((0, 0, 0, 255), Px(SkinFace.FaceWithHat(skin, 64, 32)!, 0, 0));

        // One transparent pixel anywhere in the area = a real overlay: the hat is drawn.
        Put(skin, 64, 63, 31, 0, 0, 0, 0);
        Assert.Equal((0, 0, 255, 255), Px(SkinFace.FaceWithHat(skin, 64, 32)!, 0, 0));
    }

    [Fact]
    public void HD_skins_sample_the_same_regions_at_their_scale()
    {
        var skin = Skin(128, 128);
        Fill(skin, 128, 16, 16, 32, 32, 9, 9, 9, 255);  // face at (8,8)x2
        Fill(skin, 128, 16, 16, 18, 18, 1, 1, 1, 255);  // top-left face pixel
        var head = SkinFace.FaceWithHat(skin, 128, 128)!;
        Assert.Equal((1, 1, 1, 255), Px(head, 0, 0));
        Assert.Equal((9, 9, 9, 255), Px(head, 1, 0));
    }

    [Theory]
    [InlineData(32, 32)]
    [InlineData(64, 48)]
    [InlineData(100, 100)]
    public void Odd_sizes_are_rejected(int w, int h) => Assert.Null(SkinFace.FaceWithHat(Skin(w, h), w, h));

    [Fact]
    public void Truncated_pixel_data_is_rejected() => Assert.Null(SkinFace.FaceWithHat(new byte[100], 64, 64));

    [Theory]
    [InlineData("069a79f4-44e9-4726-a5be-fca90e38aaf5", "069a79f444e94726a5befca90e38aaf5")]
    [InlineData("069A79F444E94726A5BEFCA90E38AAF5", "069a79f444e94726a5befca90e38aaf5")]
    [InlineData(" 069a79f444e94726a5befca90e38aaf5 ", "069a79f444e94726a5befca90e38aaf5")]
    public void Cache_key_is_the_normalised_uuid(string uuid, string key) => Assert.Equal(key, SkinFace.CacheKey(uuid));

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("pending-1234")]
    [InlineData("Offline")]
    [InlineData("../../../../evil0000000000000000000")]
    [InlineData("069a79f444e94726a5befca90e38aaz5")]
    public void Non_uuids_have_no_cache_key(string? uuid) => Assert.Null(SkinFace.CacheKey(uuid));

    [Fact]
    public void Default_head_follows_javas_uuid_hash_parity()
    {
        // UUID.hashCode() = (int)(hilo >> 32) ^ (int)hilo, hilo = msb ^ lsb.
        Assert.False(SkinFace.IsAlexDefault("00000000000000000000000000000000")); // hash 0 -> Steve
        Assert.True(SkinFace.IsAlexDefault("00000000000000000000000000000001"));  // hash 1 -> Alex
        Assert.True(SkinFace.IsAlexDefault("00000000000000010000000000000000"));  // msb low bit -> 1 -> Alex
        Assert.False(SkinFace.IsAlexDefault("00000001000000000000000000000001")); // 1 ^ 1 = 0 -> Steve
        Assert.False(SkinFace.IsAlexDefault(null));
        Assert.False(SkinFace.IsAlexDefault("pending-x"));
        Assert.NotEqual(SkinFace.DefaultHead(null), SkinFace.DefaultHead("00000000000000000000000000000001"));
    }

    [Fact]
    public void Default_heads_are_full_opaque_8x8_images()
    {
        foreach (var head in new[] { SkinFace.DefaultHead(null), SkinFace.DefaultHead("00000000000000000000000000000001") })
        {
            Assert.Equal(8 * 8 * 4, head.Length);
            for (var i = 3; i < head.Length; i += 4) Assert.Equal(255, head[i]);
        }
    }

    private static string Profile(string texturesJson) =>
        "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\",\"properties\":[{\"name\":\"textures\",\"value\":\""
        + Convert.ToBase64String(Encoding.UTF8.GetBytes(texturesJson)) + "\"}]}";

    private const string NotchKey = "069a79f444e94726a5befca90e38aaf5";

    [Fact]
    public void Skin_url_is_read_from_the_profile_and_upgraded_to_https()
    {
        var json = Profile("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc123\"}}}");
        Assert.Equal(SkinFace.ProfileAnswer.Skin, SkinFace.ReadProfile(json, NotchKey, out var url));
        Assert.Equal("https://textures.minecraft.net/texture/abc123", url);
    }

    [Fact]
    public void A_real_profile_without_a_skin_is_NoSkin()
    {
        Assert.Equal(SkinFace.ProfileAnswer.NoSkin, SkinFace.ReadProfile(Profile("{\"textures\":{}}"), NotchKey, out var url));
        Assert.Null(url);
    }

    [Theory]
    [InlineData("{\"textures\":{\"SKIN\":{\"url\":\"https://evil.example/texture/abc\"}}}")]  // foreign host
    [InlineData("{\"textures\":{\"SKIN\":{\"url\":\"file://textures.minecraft.net/x\"}}}")]   // bad scheme
    [InlineData("{\"nottextures\":1}")]                                                      // textures missing
    public void Odd_textures_are_Invalid_not_NoSkin(string textures)
    {
        Assert.Equal(SkinFace.ProfileAnswer.Invalid, SkinFace.ReadProfile(Profile(textures), NotchKey, out var url));
        Assert.Null(url);
    }

    [Fact]
    public void Another_players_profile_is_Invalid() =>
        Assert.Equal(SkinFace.ProfileAnswer.Invalid,
            SkinFace.ReadProfile(Profile("{\"textures\":{}}"), "853c80ef3c3749fdaa49938b674adae6", out _));

    // A captive portal or proxy page answering 200 must never be remembered as "no custom skin".
    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("<html><body>Sign in to the hotel Wi-Fi</body></html>")]
    [InlineData("not json")]
    [InlineData("[]")]
    [InlineData("{}")]
    [InlineData("{\"id\":\"x\"}")]
    [InlineData("{\"id\":\"069a79f444e94726a5befca90e38aaf5\"}")]
    [InlineData("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"properties\":[{\"name\":\"textures\",\"value\":\"%%%\"}]}")]
    public void Garbage_answers_are_Invalid(string? json) =>
        Assert.Equal(SkinFace.ProfileAnswer.Invalid, SkinFace.ReadProfile(json, NotchKey, out _));

    private static byte[] PngHeader(uint w, uint h)
    {
        var b = new byte[33];
        new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, (byte)'I', (byte)'H', (byte)'D', (byte)'R' }.CopyTo(b, 0);
        b[16] = (byte)(w >> 24); b[17] = (byte)(w >> 16); b[18] = (byte)(w >> 8); b[19] = (byte)w;
        b[20] = (byte)(h >> 24); b[21] = (byte)(h >> 16); b[22] = (byte)(h >> 8); b[23] = (byte)h;
        return b;
    }

    [Fact]
    public void Png_size_is_read_from_the_header_without_decoding()
    {
        Assert.Equal((64, 64), SkinFace.PngSize(PngHeader(64, 64)));
        Assert.Equal((60000, 60000), SkinFace.PngSize(PngHeader(60000, 60000))); // a bomb's claim, read in 24 bytes
        Assert.Null(SkinFace.PngSize(PngHeader(0x80000000, 64)));                // negative as int
        Assert.Null(SkinFace.PngSize(Encoding.ASCII.GetBytes("<html>not a png at all, sorry</html>")));
        Assert.Null(SkinFace.PngSize(new byte[10]));
    }

    [Theory]
    [InlineData(64, 64, true)]
    [InlineData(64, 32, true)]
    [InlineData(1024, 1024, true)]
    [InlineData(2048, 2048, false)]   // past MaxSkinWidth: refused before decoding
    [InlineData(60000, 60000, false)]
    [InlineData(64, 48, false)]
    [InlineData(32, 32, false)]
    public void Only_skin_sizes_within_the_cap_are_decoded(int w, int h, bool ok) =>
        Assert.Equal(ok, SkinFace.IsSkinSize(w, h));

    [Fact]
    public void Cached_skins_go_stale_after_a_day()
    {
        var now = new DateTime(2026, 10, 20, 12, 0, 0, DateTimeKind.Utc);
        Assert.False(SkinFace.IsStale(now.AddHours(-23), now));
        Assert.True(SkinFace.IsStale(now.AddHours(-25), now));
        Assert.True(SkinFace.IsStale(now.AddDays(2), now)); // clock moved back: refetch
    }
}
