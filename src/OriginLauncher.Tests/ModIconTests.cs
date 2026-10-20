using System.IO.Compression;
using OriginLauncher.App.Core.Mods.Icons;
using Xunit;

namespace OriginLauncher.Tests;

// The Mods page's icons come only from inside the jar: reading the icon path out of a jar's manifest
// (string and size-map forms), pulling the PNG from a real zip, and the in-memory result cache
// (one load per unchanged file, "no icon" remembered, over-cap icons included).
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
    public async Task Memo_loads_once_per_unchanged_file_and_remembers_no_icon()
    {
        var f = Path.Combine(_dir, "a.jar");
        File.WriteAllText(f, "x");
        var memo = new IconMemo<string>();
        var loads = 0;
        string? Load(string p) { Interlocked.Increment(ref loads); return null; } // a jar with no icon

        Assert.Null(await memo.GetAsync(f, Load));
        Assert.Null(await memo.GetAsync(f, Load));
        Assert.Equal(1, loads); // "no icon" is a cached answer, not re-read on every refresh
    }

    [Fact]
    public async Task Over_cap_icon_is_a_cached_no_icon()
    {
        var jar = Path.Combine(_dir, "bigicon.jar");
        using (var zip = ZipFile.Open(jar, ZipArchiveMode.Create))
        {
            Put(zip, "fabric.mod.json", """{"icon":"big.png"}"""u8.ToArray());
            Put(zip, "big.png", new byte[JarIconReader.MaxIconBytes + 1]);
        }
        var memo = new IconMemo<byte[]>();
        var loads = 0;
        byte[]? Load(string p) { Interlocked.Increment(ref loads); return JarIconReader.ReadIcon(p); }

        Assert.Null(await memo.GetAsync(jar, Load));
        Assert.Null(await memo.GetAsync(jar, Load));
        Assert.Equal(1, loads);
    }

    [Fact]
    public async Task Concurrent_callers_share_one_load()
    {
        var f = Path.Combine(_dir, "shared.jar");
        File.WriteAllText(f, "x");
        var memo = new IconMemo<string>();
        var loads = 0;
        var gate = new ManualResetEventSlim();
        string? Load(string p) { Interlocked.Increment(ref loads); gate.Wait(); return "icon"; }

        var tasks = Enumerable.Range(0, 8).Select(_ => memo.GetAsync(f, Load)).ToArray();
        gate.Set();
        var results = await Task.WhenAll(tasks);
        Assert.All(results, r => Assert.Equal("icon", r));
        Assert.Equal(1, loads);
    }

    [Fact]
    public async Task A_changed_file_is_read_again_and_the_old_entry_is_dropped()
    {
        var f = Path.Combine(_dir, "changes.jar");
        File.WriteAllText(f, "x");
        var memo = new IconMemo<string>();
        var loads = 0;
        string? Load(string p) => "v" + Interlocked.Increment(ref loads);

        Assert.Equal("v1", await memo.GetAsync(f, Load));
        File.WriteAllText(f, "longer content"); // size changes
        Assert.Equal("v2", await memo.GetAsync(f, Load));
        File.SetLastWriteTimeUtc(f, DateTime.UtcNow.AddMinutes(5)); // mtime changes
        Assert.Equal("v3", await memo.GetAsync(f, Load));
        Assert.Equal(1, memo.Count);
    }

    [Fact]
    public async Task Missing_file_is_no_icon_and_not_remembered_and_a_throwing_loader_is_no_icon()
    {
        var memo = new IconMemo<string>();
        Assert.Null(await memo.GetAsync(Path.Combine(_dir, "nope.jar"), _ => "never"));
        Assert.Equal(0, memo.Count);

        var f = Path.Combine(_dir, "throws.jar");
        File.WriteAllText(f, "x");
        Assert.Null(await memo.GetAsync(f, _ => throw new InvalidDataException()));
    }

    private static void Put(ZipArchive zip, string name, byte[] bytes)
    {
        using var s = zip.CreateEntry(name).Open();
        s.Write(bytes);
    }
}
