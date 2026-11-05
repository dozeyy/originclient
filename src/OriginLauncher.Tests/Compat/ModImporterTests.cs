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
        var mods = ModFolderScanner.Scan(_mods, ModRole.SharedLoose);
        Assert.Equal("2.0.0", Assert.Single(mods, m => m.Enabled).Info.Version);
        // the old jar was renamed, not overwritten or deleted
        Assert.Equal("1.0.0", Assert.Single(mods, m => !m.Enabled).Info.Version);
    }

    [Fact]
    public void SameNameReplaceNeverOverwritesOrDeletesATurnedOffTwinOfThatName()
    {
        // review 4: foo.jar replaced while a foo.jar.disabled already exists. The old code moved over / deleted files.
        var old = WriteJar(_mods, "same.jar", "same", "1.0.0");
        var twin = WriteJar(_mods, "same.jar.disabled", "same", "0.5.0");
        var oldBytes = System.IO.File.ReadAllBytes(old);
        var twinBytes = System.IO.File.ReadAllBytes(twin);
        var src = WriteJar(_incoming, "same.jar", "same", "2.0.0");

        var report = ModImporter.Import(new[] { src }, Context(null, "same.jar"));

        Assert.True(report.Ok);
        Assert.Equal(1, report.Added);
        Assert.Equal(twinBytes, System.IO.File.ReadAllBytes(twin)); // untouched
        var kept = Directory.GetFiles(_mods, "same-replaced-*.jar.disabled");
        Assert.Single(kept);
        Assert.Equal(oldBytes, System.IO.File.ReadAllBytes(kept[0])); // the replaced jar is still there, byte for byte
        Assert.NotEqual(oldBytes, System.IO.File.ReadAllBytes(old)); // and the new one is in place
        Assert.Empty(Directory.GetFiles(_mods, "*.download"));
    }

    [Fact]
    public void AFailedCopyLeavesTheOldJarExactlyAsItWas()
    {
        // review 3: the old jar used to be renamed BEFORE the copy, so a failed copy left the player with no mod.
        var old = WriteJar(_mods, "sodium-0.5.0.jar", "sodium", "0.5.0");
        var oldBytes = System.IO.File.ReadAllBytes(old);
        var src = WriteJar(_incoming, "sodium-0.6.13.jar", "sodium", "0.6.13");
        Directory.CreateDirectory(Path.Combine(_mods, "sodium-0.6.13.jar.download")); // the copy target cannot be written

        var report = ModImporter.Import(new[] { src }, Context(null, "sodium-0.6.13.jar"));

        Assert.False(report.Ok);
        Assert.Contains("Couldn't add that mod", report.Error);
        Assert.Equal(oldBytes, System.IO.File.ReadAllBytes(old));
        Assert.False(System.IO.File.Exists(old + ".disabled"));
    }

    [Fact]
    public void AFailedMoveIntoPlaceRollsTheRenameBack()
    {
        var old = WriteJar(_mods, "sodium-0.5.0.jar", "sodium", "0.5.0");
        var oldBytes = System.IO.File.ReadAllBytes(old);
        var src = WriteJar(_incoming, "sodium-0.6.13.jar", "sodium", "0.6.13");
        Directory.CreateDirectory(Path.Combine(_mods, "sodium-0.6.13.jar")); // the final move cannot land

        var report = ModImporter.Import(new[] { src }, Context(null, "sodium-0.6.13.jar"));

        Assert.False(report.Ok);
        Assert.Equal(oldBytes, System.IO.File.ReadAllBytes(old)); // renamed back to its enabled name
        Assert.False(System.IO.File.Exists(old + ".disabled"));
        Assert.False(System.IO.File.Exists(Path.Combine(_mods, "sodium-0.6.13.jar.download")), "the half-copy is cleaned up");
    }

    [Fact]
    public void ADifferentModWithTheSameFileNameIsRefusedNotOverwritten()
    {
        var existing = WriteJar(_mods, "x.jar", "mod-a", "1.0.0");
        var before = System.IO.File.ReadAllBytes(existing);
        var src = WriteJar(_incoming, "x.jar", "mod-b", "1.0.0");
        var report = ModImporter.Import(new[] { src }, Context());
        Assert.False(report.Ok);
        Assert.Contains("different mod with that file name", report.Error);
        Assert.Equal(before, System.IO.File.ReadAllBytes(existing));
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
        // review 8: it was not added, so it is not counted or announced as added
        Assert.Equal(0, report.Added);
        Assert.Equal(1, report.AlreadyInstalled);
        Assert.Equal("1 mod was already installed.", report.Message);
    }

    [Fact]
    public void AMixedDropReportsAddedAndAlreadyInstalledSeparately()
    {
        var existing = WriteJar(_mods, "same.jar", "same", "1.0.0");
        var dupe = Path.Combine(_incoming, "same.jar");
        System.IO.File.Copy(existing, dupe);
        var fresh = WriteJar(_incoming, "new-1.0.jar", "new");
        var report = ModImporter.Import(new[] { dupe, fresh }, Context());
        Assert.Equal(1, report.Added);
        Assert.Equal(1, report.AlreadyInstalled);
        Assert.Equal("Added 1 mod. 1 mod was already installed.", report.Message);
    }

    // Bug run 11 PROF-9: dropping the very jar of a mod that is switched off asked "Replace?" and then kept an identical
    // second copy as "appleskin-replaced-<ticks>.jar.disabled". It now just turns the mod back on.
    [Fact]
    public void DroppingTheIdenticalJarOfATurnedOffModTurnsItBackOn()
    {
        var off = WriteJar(_mods, "appleskin.jar", "appleskin", "3.0.0");
        var src = Path.Combine(_incoming, "appleskin.jar");
        System.IO.File.Copy(off, src);
        System.IO.File.Move(off, off + ".disabled");

        var report = ModImporter.Import(new[] { src }, Context());

        Assert.True(report.Ok);
        Assert.Empty(report.NeedsReplace);
        Assert.Equal(0, report.Added);
        Assert.Equal(new[] { "appleskin" }, report.TurnedOn);
        Assert.Equal("Turned appleskin back on.", report.Message);
        Assert.True(System.IO.File.Exists(Path.Combine(_mods, "appleskin.jar")));
        Assert.Equal(new[] { "appleskin.jar" }, Directory.GetFiles(_mods).Select(Path.GetFileName).ToArray());
    }

    // Bug run 11 PROF-5: the Mods page showed a jar's file name twice ("litematica-fabric-1.21.1-0.19.50" over the
    // .jar). The mod's own name and version come from inside the jar, read off the UI thread (WarmNames); until then,
    // and for a jar that says nothing, the file name stands in.
    [Fact]
    public void TheModsPageShowsTheModsOwnNameOnceItsJarIsRead()
    {
        WriteJar(_mods, "XaerosWorldMap_1.39.2_Fabric_1.21.jar", "xaeroworldmap", "1.39.2");
        System.IO.File.WriteAllBytes(Path.Combine(_mods, "broken.jar.disabled"), new byte[] { 1, 2, 3 });

        var before = OriginLauncher.App.Core.Mods.ModManager.EnumerateIn(_mods, isProfile: false);
        var unread = before.Single(m => m.FileName == "XaerosWorldMap_1.39.2_Fabric_1.21.jar");
        Assert.True(unread.NameUnread);
        Assert.Equal("XaerosWorldMap 1.39.2 Fabric 1.21", unread.DisplayName);
        Assert.Equal(unread.FileName, unread.FileLine);

        // Other test classes clear the shared read cache in their constructors (they run in parallel): read again if so.
        IReadOnlyList<OriginLauncher.App.Core.Mods.ModEntry> after = before;
        for (var attempt = 0; attempt < 3 && after.Any(m => m.NameUnread); attempt++)
        {
            OriginLauncher.App.Core.Mods.ModManager.WarmNames(_mods);
            after = OriginLauncher.App.Core.Mods.ModManager.EnumerateIn(_mods, isProfile: false);
        }
        var named = after.Single(m => m.FileName == "XaerosWorldMap_1.39.2_Fabric_1.21.jar");
        Assert.Equal("xaeroworldmap", named.DisplayName); // WriteJar's name is its id
        Assert.Equal("1.39.2", named.Version);
        Assert.Equal("1.39.2 - XaerosWorldMap_1.39.2_Fabric_1.21.jar", named.FileLine);
        var broken = after.Single(m => m.FileName == "broken.jar");
        Assert.False(broken.NameUnread); // read once, said nothing: never read again
        Assert.Equal("broken", broken.DisplayName);
    }

    // Review of v1.0.97: the identical bytes under another name ("sodium-0.6.13 (1).jar") must turn the switched-off
    // jar on by ITS name, never look for a "sodium-0.6.13 (1).jar.disabled" that isn't there.
    [Fact]
    public void TheIdenticalJarUnderAnotherNameTurnsTheSwitchedOffOneBackOn()
    {
        var off = WriteJar(_mods, "coolmod-0.6.13.jar", "coolmod", "0.6.13");
        var src = Path.Combine(_incoming, "coolmod-0.6.13 (1).jar");
        System.IO.File.Copy(off, src);
        System.IO.File.Move(off, off + ".disabled");

        var report = ModImporter.Import(new[] { src }, Context());

        Assert.True(report.Ok, report.Error);
        Assert.Empty(report.Refused);
        Assert.Empty(report.NeedsReplace);
        Assert.Equal(new[] { "coolmod" }, report.TurnedOn);
        Assert.Equal(new[] { "coolmod-0.6.13.jar" }, Directory.GetFiles(_mods).Select(Path.GetFileName).ToArray());
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

    // Bug run 11 PROF-3: before a version's first Play its folder has none of Origin's jars, and adding mods said
    // "Needs fabric-api" / "Needs sodium" about mods Origin installs itself. What Origin will install answers those
    // notes; a requirement Origin's copy doesn't meet, or a library Origin doesn't ship, is still said.
    [Fact]
    public void BeforeTheFirstPlayOriginsOwnModsAnswerTheNeedsNotes()
    {
        var willInstall = BuiltInModSet.Build("1.21.1", null, new[] { new CatalogMod("sodium", "sodium-fabric-0.6.13+mc1.21.1.jar") });
        var context = Context() with { OriginWillInstall = willInstall };
        var src = WriteJar(_incoming, "extra-1.0.jar", "extra",
            needs: new[] { ("fabric-api", "*"), ("sodium", ">=0.6"), ("fabric-resource-loader-v0", "*"), ("somelib", ">=1.0") });

        var report = ModImporter.Import(new[] { src }, context);

        Assert.Equal(1, report.Added);
        Assert.DoesNotContain(report.Notes, n => n.Contains("fabric-api") || n.Contains("sodium") || n.Contains("fabric-resource-loader"));
        Assert.Contains(report.Notes, n => n.Contains("somelib"));

        var tooNew = WriteJar(_incoming, "picky-1.0.jar", "picky", needs: new[] { ("sodium", ">=0.9") });
        var picky = ModImporter.Import(new[] { tooNew }, context);
        Assert.Contains(picky.Notes, n => n.Contains("sodium"));
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
    public void AProfileNeverTakesOriginsOwnVersionOrAnOlderFabricApi()
    {
        WriteJar(_mods, "fabric-api-0.116.0.jar", "fabric-api", "0.116.0");
        foreach (var version in new[] { "0.116.0", "0.100.0" })
        {
            var src = WriteJar(_incoming, $"fabric-api-{version}.jar", "fabric-api", version);
            var report = ModImporter.Import(new[] { src }, Context(_profile));
            Assert.False(report.Ok);
            Assert.Contains("Origin already includes", report.Error);
        }
        Assert.Empty(Directory.GetFiles(_profile));
    }

    // Will (2026-10-25): "if their mods list has a different sodium or anything it should replace ours". Dropping the
    // player's own version of a mod Origin ships into a profile adds it; at Play it loads instead of Origin's
    // (PlayerOverrides), and Origin's copy in the shared folder is never touched.
    [Fact]
    public void AProfileTakesThePlayersOwnVersionOfAModOriginShips()
    {
        var origins = WriteJar(_mods, "voicechat-fabric-2.5.0.jar", "voicechat", "2.5.0");
        var before = System.IO.File.ReadAllBytes(origins);
        var src = WriteJar(_incoming, "voicechat-fabric-2.4.0.jar", "voicechat", "2.4.0", mc: "1.21.1");
        var report = ModImporter.Import(new[] { src }, Context(_profile));
        Assert.True(report.Ok, report.Error);
        Assert.Equal(1, report.Added);
        Assert.True(System.IO.File.Exists(Path.Combine(_profile, "voicechat-fabric-2.4.0.jar")));
        Assert.Equal(before, System.IO.File.ReadAllBytes(origins));

        // ...but not a copy made for another Minecraft version: Origin's stays the one that loads.
        var wrong = WriteJar(_incoming, "voicechat-fabric-2.3.0.jar", "voicechat", "2.3.0", mc: "1.20.1");
        var refused = ModImporter.Import(new[] { wrong }, Context(_profile));
        Assert.False(refused.Ok);
        Assert.False(System.IO.File.Exists(Path.Combine(_profile, "voicechat-fabric-2.3.0.jar")));
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
    public void AnUpdateThatWouldStopTheNextPlayIsFoundBeforeItGoesIn()
    {
        // N92-2: the Mods page's Update must not swap a working jar for one that needs a newer Fabric API / Loader
        // than Origin installs for the version.
        WriteJar(_mods, "fabric-api-0.100.0.jar", "fabric-api", "0.100.0");
        WriteJar(_mods, "extra-1.0.jar", "extra", "1.0.0", needs: new[] { ("fabric-api", ">=0.90.0"), ("lib", "*") });
        var newerApi = WriteJar(_incoming, "extra-2.0.jar", "extra", "2.0.0", needs: new[] { ("fabric-api", ">=0.116.0"), ("lib", "*") });
        var problem = Assert.Single(ModImporter.UpdateProblems(newerApi, "extra-1.0.jar", Context()));
        Assert.Equal(FindingKind.WrongVersion, problem.Kind);
        Assert.Equal("fabric-api", problem.TargetId);

        var newerLoader = WriteJar(_incoming, "extra-3.0.jar", "extra", "3.0.0", needs: new[] { ("fabricloader", ">=0.99.0"), ("lib", "*") });
        Assert.Equal("fabricloader", Assert.Single(ModImporter.UpdateProblems(newerLoader, "extra-1.0.jar", Context())).TargetId);

        // What the installed copy already lacked (lib) is not the update's fault; a fitting update has no problem.
        var fits = WriteJar(_incoming, "extra-1.5.jar", "extra", "1.5.0", needs: new[] { ("fabric-api", ">=0.95.0"), ("lib", "*") });
        Assert.Empty(ModImporter.UpdateProblems(fits, "extra-1.0.jar", Context()));
    }

    // The shared folder follows the same rule as a profile (DECISIONS 2026-10-25, "a player's own mods win"): the
    // player's own version of a mod Origin ships is added and loads instead of Origin's; Origin's own version (or an
    // older Fabric API) is "already included" and never becomes a second copy.
    [Fact]
    public void TheSharedFolderTakesThePlayersOwnVersionButNeverASecondCopyOfOrigins()
    {
        var origins = WriteJar(_mods, "fabric-api-0.100.0.jar", "fabric-api", "0.100.0");
        var before = System.IO.File.ReadAllBytes(origins);

        var newer = WriteJar(_incoming, "fabric-api-fork-1.0.jar", "fabric-api", "1.0.0");
        var added = ModImporter.Import(new[] { newer }, Context());
        Assert.True(added.Ok, added.Error);
        Assert.Equal(1, added.Added);
        Assert.True(System.IO.File.Exists(Path.Combine(_mods, "fabric-api-fork-1.0.jar")));
        Assert.Equal(before, System.IO.File.ReadAllBytes(origins)); // Origin's copy is untouched

        foreach (var version in new[] { "0.100.0", "0.90.0" })
        {
            var src = WriteJar(_incoming, $"fabric-api-same-{version}.jar", "fabric-api", version);
            var report = ModImporter.Import(new[] { src }, Context());
            Assert.Empty(report.NeedsReplace);
            Assert.Equal(0, report.Added);
            Assert.False(report.Ok);
            Assert.False(System.IO.File.Exists(Path.Combine(_mods, $"fabric-api-same-{version}.jar")));
        }
    }

    [Fact]
    public void AMissingSourceFileIsSkippedNotAnException()
    {
        var report = ModImporter.Import(new[] { Path.Combine(_incoming, "gone.jar") }, Context());
        Assert.False(report.Ok);
        Assert.Equal("Only .jar mod files can be added.", report.Error);
    }
}
