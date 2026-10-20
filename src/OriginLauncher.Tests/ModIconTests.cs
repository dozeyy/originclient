using System.IO.Compression;
using OriginLauncher.App.Core.Mods.Icons;
using Xunit;

namespace OriginLauncher.Tests;

// The Mods page's real icons: reading the icon path out of a jar's manifest (string and size-map forms),
// pulling the PNG from a real zip, the cache keys, the negative-result cache and the Modrinth URL
// allow-list. Nothing here touches the network.
public class ModIconTests
{
    private readonly string _dir = Directory.CreateTempSubdirectory("mod-icons").FullName;

    [Fact]
    public void Fabric_icon_string_form()
    {
        Assert.Equal("assets/x/icon.png", JarIconReader.ParseIconPath("""{"schemaVersion":1,"id":"x","icon":"assets/x/icon.png"}"""));
    }

    [Fact]
    public void Fabric_icon_size_map_takes_the_largest()
    {
        const string json = """{"icon":{"32":"a/32.png","128x128":"a/128.png","64":"a/64.png"}}""";
        Assert.Equal("a/128.png", JarIconReader.ParseIconPath(json));
    }

    [Fact]
    public void Icon_path_is_normalized_and_cannot_climb_out()
    {
        Assert.Equal("a/b.png", JarIconReader.ParseIconPath("""{"icon":"/a\\b.png"}"""));
        Assert.Null(JarIconReader.ParseIconPath("""{"icon":"../evil.png"}"""));
    }

    [Theory]
    [InlineData("""{"id":"x"}""")]
    [InlineData("""{"icon":5}""")]
    [InlineData("""{"icon":""}""")]
    [InlineData("""{"icon":{}}""")]
    [InlineData("not json")]
    [InlineData("")]
    public void No_icon_is_null_never_a_throw(string json)
    {
        Assert.Null(JarIconReader.ParseIconPath(json));
    }

    [Fact]
    public void Quilt_and_mods_toml_forms()
    {
        Assert.Equal("q.png", JarIconReader.ParseIconPath("""{"quilt_loader":{"metadata":{"icon":"q.png"}}}"""));
        Assert.Equal("logo.png", JarIconReader.ParseLogoFile("modLoader=\"javafml\"\n[[mods]]\n logoFile=\"logo.png\"\n"));
        Assert.Null(JarIconReader.ParseLogoFile("[[mods]]\nmodId=\"x\"\n"));
    }

    [Fact]
    public void ReadIcon_pulls_the_png_out_of_a_real_jar()
    {
        var png = new byte[] { 0x89, 0x50, 0x4E, 0x47, 1, 2, 3 };
        var jar = Path.Combine(_dir, "m.jar");
        using (var zip = ZipFile.Open(jar, ZipArchiveMode.Create))
        {
            Put(zip, "fabric.mod.json", """{"icon":{"16":"i16.png","64":"assets/m/i64.png"}}"""u8.ToArray());
            Put(zip, "assets/m/i64.png", png);
            Put(zip, "i16.png", new byte[] { 9 });
        }
        Assert.Equal(png, JarIconReader.ReadIcon(jar));
    }

    [Fact]
    public void ReadIcon_is_null_for_a_jar_without_one_or_a_broken_file()
    {
        var jar = Path.Combine(_dir, "none.jar");
        using (var zip = ZipFile.Open(jar, ZipArchiveMode.Create))
            Put(zip, "fabric.mod.json", """{"id":"x"}"""u8.ToArray());
        Assert.Null(JarIconReader.ReadIcon(jar));

        var broken = Path.Combine(_dir, "broken.jar");
        File.WriteAllText(broken, "this is not a zip");
        Assert.Null(JarIconReader.ReadIcon(broken));
        Assert.Null(JarIconReader.ReadIcon(Path.Combine(_dir, "missing.jar")));
    }

    [Fact]
    public void An_oversized_icon_inside_a_jar_is_ignored()
    {
        var jar = Path.Combine(_dir, "big.jar");
        using (var zip = ZipFile.Open(jar, ZipArchiveMode.Create))
        {
            Put(zip, "fabric.mod.json", """{"icon":"big.png"}"""u8.ToArray());
            Put(zip, "big.png", new byte[JarIconReader.MaxIconBytes + 1]);
        }
        Assert.Null(JarIconReader.ReadIcon(jar));
    }

    [Fact]
    public void Cache_keys_accept_only_a_sha1_or_a_plain_slug()
    {
        var sha = "ABCDEF0123456789abcdef0123456789ABCDEF01";
        Assert.Equal(sha.ToLowerInvariant(), ModIconCache.KeyForSha1(sha));
        Assert.Null(ModIconCache.KeyForSha1("abc"));
        Assert.Null(ModIconCache.KeyForSha1("../../etc/passwd" + new string('a', 30)));
        Assert.Null(ModIconCache.KeyForSha1(null));
        Assert.Equal("slug-sodium", ModIconCache.KeyForSlug("Sodium"));
        Assert.Null(ModIconCache.KeyForSlug("../x"));
        Assert.Null(ModIconCache.KeyForSlug(""));
    }

    [Fact]
    public void Cache_hit_roundtrip_and_unknown_by_default()
    {
        var cache = new ModIconCache(_dir, TimeSpan.FromDays(7));
        var key = new string('a', 40);
        Assert.Equal(IconLookup.Unknown, cache.Lookup(key));
        cache.StoreHit(key, new byte[] { 1, 2, 3 });
        Assert.Equal(IconLookup.Hit, cache.Lookup(key));
        Assert.Equal(new byte[] { 1, 2, 3 }, cache.ReadHit(key));
    }

    [Fact]
    public void Negative_result_is_cached_then_expires()
    {
        var now = new DateTime(2026, 10, 19, 12, 0, 0, DateTimeKind.Utc);
        var cache = new ModIconCache(_dir, TimeSpan.FromDays(7), () => now);
        var key = new string('b', 40);

        cache.StoreMiss(key);
        File.SetLastWriteTimeUtc(Path.Combine(_dir, key + ".none"), now);
        Assert.Equal(IconLookup.Miss, cache.Lookup(key));

        now = now.AddDays(6);
        Assert.Equal(IconLookup.Miss, cache.Lookup(key));

        now = now.AddDays(2); // past the 7-day TTL: look again
        Assert.Equal(IconLookup.Unknown, cache.Lookup(key));
    }

    [Fact]
    public void A_stored_icon_replaces_a_miss_and_a_miss_replaces_a_bad_icon()
    {
        var cache = new ModIconCache(_dir, TimeSpan.FromDays(7));
        var key = new string('c', 40);
        cache.StoreMiss(key);
        cache.StoreHit(key, new byte[] { 7 });
        Assert.Equal(IconLookup.Hit, cache.Lookup(key));
        cache.StoreMiss(key); // the icon would not decode
        Assert.Equal(IconLookup.Miss, cache.Lookup(key));
        Assert.Null(cache.ReadHit(key));
    }

    [Theory]
    [InlineData("https://cdn.modrinth.com/data/AANobbMI/icon.png", true)]
    [InlineData("https://CDN.MODRINTH.COM/data/x/icon.png", true)]
    [InlineData("http://cdn.modrinth.com/data/x/icon.png", false)]
    [InlineData("https://cdn.modrinth.com.evil.com/x.png", false)]
    [InlineData("https://evil.com/cdn.modrinth.com/x.png", false)]
    [InlineData("https://user@cdn.modrinth.com/x.png", false)]
    [InlineData("https://cdn.modrinth.com:8443/x.png", false)]
    [InlineData("file:///C:/x.png", false)]
    [InlineData("", false)]
    public void Only_https_cdn_modrinth_com_is_an_allowed_icon_host(string url, bool allowed)
    {
        Assert.Equal(allowed, ModIconSource.IsAllowedIconUrl(url));
    }

    [Fact]
    public void Modrinth_answers_parse_and_reject_hostile_values()
    {
        Assert.Equal("AANobbMI", ModIconSource.ParseProjectId("""{"project_id":"AANobbMI","id":"zzz"}"""));
        Assert.Null(ModIconSource.ParseProjectId("""{"project_id":"../../x"}"""));
        Assert.Null(ModIconSource.ParseProjectId("{}"));
        Assert.Equal("https://cdn.modrinth.com/data/a/icon.png", ModIconSource.ParseIconUrl("""{"icon_url":"https://cdn.modrinth.com/data/a/icon.png"}"""));
        Assert.Null(ModIconSource.ParseIconUrl("""{"icon_url":"https://evil.com/i.png"}"""));
        Assert.Null(ModIconSource.ParseIconUrl("""{"icon_url":null}"""));
    }

    [Fact]
    public void Built_in_slugs()
    {
        Assert.Equal("sodium", ModIconSource.BuiltInSlug("sodium-fabric-0.6.13+mc1.21.1.jar"));
        Assert.Equal("iris", ModIconSource.BuiltInSlug("iris-fabric-1.8.8+mc1.21.1.jar"));
        Assert.Null(ModIconSource.BuiltInSlug("originclient-1.21.1.jar"));
    }

    private static void Put(ZipArchive zip, string name, byte[] bytes)
    {
        using var s = zip.CreateEntry(name).Open();
        s.Write(bytes);
    }
}
