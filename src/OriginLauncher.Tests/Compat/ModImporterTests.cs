using System.Text;
using OriginLauncher.App.Core.Compat;
using Xunit;
using static OriginLauncher.Tests.Compat.CompatKit;

namespace OriginLauncher.Tests.Compat;

// Adding a jar goes through the compatibility engine first (Phase 5.1 follow-up): refuse what cannot work with a
// reason, ask before replacing the same mod, never overwrite silently, never delete the old file.
public sealed class ModImporterTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "origin-importer-" + Guid.NewGuid().ToString("N"));
    private readonly string _mods;
    private readonly string _profile;
    private readonly string _incoming;

    public ModImporterTests()
    {
        _mods = Path.Combine(_root, "mods");
        _profile = Path.Combine(_root, "profile");
        _incoming = Path.Combine(_root, "incoming");
        Directory.CreateDirectory(_mods);
        Directory.CreateDirectory(_profile);
        Directory.CreateDirectory(_incoming);
        ModFolderScanner.ClearCache();
    }

    public void Dispose()
    {
        try { Directory.Delete(_root, true); } catch (IOException) { /* temp folder */ }
    }

    // A real jar with a fabric.mod.json made from the pieces the test cares about.
    internal static string WriteJar(
        string folder, string fileName, string id, string version = "1.0.0", string? mc = null,
        (string Id, string Range)[]? needs = null, (string Id, string Range)[]? breaks = null)
    {
        var depends = new List<string>();
        if (mc != null) depends.Add($"\"minecraft\":\"{mc}\"");
        foreach (var (depId, range) in needs ?? Array.Empty<(string, string)>()) depends.Add($"\"{depId}\":\"{range}\"");
        var breakList = (breaks ?? Array.Empty<(string, string)>()).Select(b => $"\"{b.Id}\":\"{b.Range}\"");
        var json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"name\":\"" + id + "\""
            + ",\"depends\":{" + string.Join(",", depends) + "},\"breaks\":{" + string.Join(",", breakList) + "}}";
        var path = Path.Combine(folder, fileName);
        System.IO.File.WriteAllBytes(path, Zip(("fabric.mod.json", Encoding.UTF8.GetBytes(json))));
        return path;
    }

    private ImportContext Context(string? profile = null, params string[] confirmed) =>
        profile == null
            ? new ImportContext(_mods, Fabric121, n => n.StartsWith("fabric-api-"), ConfirmedReplace: confirmed.ToHashSet(StringComparer.OrdinalIgnoreCase))
            : new ImportContext(profile, Fabric121, n => n.StartsWith("fabric-api-") || n.StartsWith("voicechat-"), IsProfile: true,
                OriginFolder: _mods, ConfirmedReplace: confirmed.ToHashSet(StringComparer.OrdinalIgnoreCase));

    [Fact]
    public void ACompatibleJarIsCopiedIn()
    {
        var src = WriteJar(_incoming, "cool-1.0.jar", "cool", mc: "~1.21.1");
        var report = ModImporter.Import(new[] { src }, Context());
        Assert.True(report.Ok);
        Assert.Equal(1, report.Added);
        Assert.Empty(report.NeedsReplace);
        Assert.True(System.IO.File.Exists(Path.Combine(_mods, "cool-1.0.jar")));
        Assert.Empty(Directory.GetFiles(_mods, "*.download"));
    }

    [Fact]
    public void AJarForAnotherMinecraftVersionIsRefusedWithTheReason()
    {
        var src = WriteJar(_incoming, "old-1.0.jar", "old", mc: "~1.20.4");
        var report = ModImporter.Import(new[] { src }, Context());
        Assert.False(report.Ok);
        Assert.Contains("old-1.0.jar wasn't added", report.Error);
        Assert.Contains("1.20.4", report.Error);
        Assert.False(System.IO.File.Exists(Path.Combine(_mods, "old-1.0.jar")));
    }

    [Fact]
    public void AJarForAnotherLoaderIsRefused()
    {
        var path = Path.Combine(_incoming, "forgey-1.0.jar");
        var toml = "modLoader=\"javafml\"\n[[mods]]\nmodId=\"forgey\"\nversion=\"1.0\"\n";
        System.IO.File.WriteAllBytes(path, Zip(("META-INF/mods.toml", Encoding.UTF8.GetBytes(toml))));
        var report = ModImporter.Import(new[] { path }, Context());
        Assert.False(report.Ok);
        Assert.Contains("wasn't added", report.Error);
        Assert.False(System.IO.File.Exists(Path.Combine(_mods, "forgey-1.0.jar")));
    }

    [Fact]
    public void AFileThatIsNotAModIsRefused()
    {
        var path = Path.Combine(_incoming, "junk.jar");
        System.IO.File.WriteAllText(path, "not a zip at all");
        var report = ModImporter.Import(new[] { path }, Context());
        Assert.False(report.Ok);
        Assert.Contains("junk.jar wasn't added", report.Error);
    }

    [Fact]
    public void ASecondVersionOfAnInstalledModIsAQuestionNotAnOverwrite()
    {
        var old = WriteJar(_mods, "sodium-0.5.0.jar", "sodium", "0.5.0");
        var before = System.IO.File.ReadAllBytes(old);
        var src = WriteJar(_incoming, "sodium-0.6.13.jar", "sodium", "0.6.13");

        var report = ModImporter.Import(new[] { src }, Context());

        Assert.True(report.Ok);
        Assert.Equal(0, report.Added);
        var offer = Assert.Single(report.NeedsReplace);
        Assert.Equal("sodium-0.6.13.jar", offer.FileName);
        Assert.Equal("sodium-0.5.0.jar", offer.ExistingFileName);
        Assert.Equal("0.5.0", offer.ExistingVersion);
        Assert.Equal("0.6.13", offer.NewVersion);
        Assert.Contains("already installed", offer.Question);
        Assert.Contains("0.6.13", offer.Question);
        // nothing changed on disk
        Assert.Equal(before, System.IO.File.ReadAllBytes(old));
        Assert.False(System.IO.File.Exists(Path.Combine(_mods, "sodium-0.6.13.jar")));
    }

    [Fact]
    public void ConfirmedReplaceKeepsTheOldFileTurnedOffAndAddsTheNewOne()
    {
        WriteJar(_mods, "sodium-0.5.0.jar", "sodium", "0.5.0");
        var src = WriteJar(_incoming, "sodium-0.6.13.jar", "sodium", "0.6.13");

        var report = ModImporter.Import(new[] { src }, Context(null, "sodium-0.6.13.jar"));

        Assert.True(report.Ok);
        Assert.Equal(1, report.Added);
        Assert.Empty(report.NeedsReplace);
        Assert.True(System.IO.File.Exists(Path.Combine(_mods, "sodium-0.6.13.jar")));
        Assert.False(System.IO.File.Exists(Path.Combine(_mods, "sodium-0.5.0.jar")));
        Assert.True(System.IO.File.Exists(Path.Combine(_mods, "sodium-0.5.0.jar.disabled")), "the replaced jar is kept, turned off");
    }

    [Fact]
    public void ConfirmedReplaceOfTheSameFileNameSwapsTheContentsWithoutALeftoverTwin()
    {
        WriteJar(_mods, "same.jar", "same", "1.0.0");
        var src = WriteJar(_incoming, "same.jar", "same", "2.0.0");
        var report = ModImporter.Import(new[] { src }, Context(null, "same.jar"));
        Assert.True(report.Ok);
        Assert.Equal(1, report.Added);
        ModFolderScanner.ClearCache();
        var mod = Assert.Single(ModFolderScanner.Scan(_mods, ModRole.SharedLoose));
        Assert.Equal("2.0.0", mod.Info.Version);
    }

    [Fact]
    public void TheIdenticalFileAlreadyThereIsNotAQuestion()
    {
        var existing = WriteJar(_mods, "same.jar", "same", "1.0.0");
        var src = Path.Combine(_incoming, "same.jar");
        System.IO.File.Copy(existing, src);
        var report = ModImporter.Import(new[] { src }, Context());
        Assert.True(report.Ok);
        Assert.Empty(report.NeedsReplace);
        Assert.Equal(1, report.Added);
    }

    [Fact]
    public void ATurnedOffTwinIsStillTheSameMod()
    {
        var off = WriteJar(_mods, "tw-1.0.jar", "tw", "1.0.0");
        System.IO.File.Move(off, off + ".disabled");
        var src = WriteJar(_incoming, "tw-2.0.jar", "tw", "2.0.0");
        var report = ModImporter.Import(new[] { src }, Context());
        Assert.Single(report.NeedsReplace);
    }

    [Fact]
    public void AMissingRequirementIsANoteNotARefusal()
    {
        var src = WriteJar(_incoming, "addon-1.0.jar", "addon", needs: new[] { ("somelib", ">=1.0") });
        var report = ModImporter.Import(new[] { src }, Context());
        Assert.True(report.Ok);
        Assert.Equal(1, report.Added);
        Assert.True(System.IO.File.Exists(Path.Combine(_mods, "addon-1.0.jar")));
        Assert.Contains(report.Notes, n => n.Contains("somelib"));
        Assert.Contains("somelib", report.Message);
    }

    [Fact]
    public void ADroppedBatchAddsTheGoodOnesAndSaysWhyNotTheOthers()
    {
        var good = WriteJar(_incoming, "good-1.0.jar", "good");
        var bad = WriteJar(_incoming, "bad-1.0.jar", "bad", mc: "~1.16.5");
        var notJar = Path.Combine(_incoming, "readme.txt");
        System.IO.File.WriteAllText(notJar, "x");
        var report = ModImporter.Import(new[] { good, bad, notJar }, Context());
        Assert.True(report.Ok);
        Assert.Equal(1, report.Added);
        Assert.Single(report.Refused);
        Assert.Contains("Added 1 mod.", report.Message);
        Assert.Contains("bad-1.0.jar wasn't added", report.Message);
    }

    [Fact]
    public void TwoJarsOfTheSameModInOneDropAddOnlyTheFirst()
    {
        var a = WriteJar(_incoming, "dup-1.0.jar", "dup", "1.0.0");
        var b = WriteJar(_incoming, "dup-2.0.jar", "dup", "2.0.0");
        var report = ModImporter.Import(new[] { a, b }, Context());
        Assert.Equal(1, report.Added);
        Assert.Single(report.Refused);
        Assert.True(System.IO.File.Exists(Path.Combine(_mods, "dup-1.0.jar")));
        Assert.False(System.IO.File.Exists(Path.Combine(_mods, "dup-2.0.jar")));
    }

    [Fact]
    public void AProfileNeverTakesAJarOriginAlreadyProvides()
    {
        var src = WriteJar(_incoming, "fabric-api-0.116.0.jar", "fabric-api", "0.116.0");
        var report = ModImporter.Import(new[] { src }, Context(_profile));
        Assert.False(report.Ok);
        Assert.Contains("Origin already includes", report.Error);
        Assert.Empty(Directory.GetFiles(_profile));
    }

    [Fact]
    public void AProfileJarIsJudgedBesideOriginsOwnJars()
    {
        // Origin's Fabric API is in the shared folder; a profile mod that needs a newer one is added with a note.
        WriteJar(_mods, "fabric-api-0.100.0.jar", "fabric-api", "0.100.0");
        var src = WriteJar(_incoming, "needs-api-1.0.jar", "needs-api", needs: new[] { ("fabric-api", ">=0.116.0") });
        var report = ModImporter.Import(new[] { src }, Context(_profile));
        Assert.True(report.Ok);
        Assert.Equal(1, report.Added);
        Assert.True(System.IO.File.Exists(Path.Combine(_profile, "needs-api-1.0.jar")));
        Assert.Contains(report.Notes, n => n.Contains("fabric-api"));
    }

    [Fact]
    public void ASecondCopyOfAnOriginManagedModIsRefused()
    {
        WriteJar(_mods, "fabric-api-0.100.0.jar", "fabric-api", "0.100.0");
        var src = WriteJar(_incoming, "fabric-api-fork-1.0.jar", "fabric-api", "1.0.0");
        var report = ModImporter.Import(new[] { src }, Context());
        Assert.Empty(report.NeedsReplace);
        Assert.Equal(0, report.Added);
        Assert.False(System.IO.File.Exists(Path.Combine(_mods, "fabric-api-fork-1.0.jar")));
        Assert.False(report.Ok);
    }

    [Fact]
    public void AMissingSourceFileIsSkippedNotAnException()
    {
        var report = ModImporter.Import(new[] { Path.Combine(_incoming, "gone.jar") }, Context());
        Assert.False(report.Ok);
        Assert.Equal("Only .jar mod files can be added.", report.Error);
    }
}
