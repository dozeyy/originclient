using System.Diagnostics;
using System.Text;
using System.Text.Json;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Caches;
using OriginLauncher.App.Core.Import.GameFiles;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Regression tests for the second review of the import engine: one (or more) per finding, named after what
// must stay true. (Finding 2's test lives in GameFileTests beside the game-file fixture.)
public class ImportReviewRound2Tests : IDisposable
{
    private readonly Rig _rig = new();
    private readonly Mojang _mojang = new();
    private readonly List<string> _restore = new();

    public void Dispose()
    {
        foreach (var path in _restore) try { RestoreAccess(path); } catch (Exception) { }
        _rig.Dispose();
    }

    private string Src(string rel) => Path.Combine(_rig.Source, rel.Replace('/', Path.DirectorySeparatorChar));
    private string Dst(string rel) => Path.Combine(_rig.GameDir(), rel.Replace('/', Path.DirectorySeparatorChar));
    private string Outside(string name) => Path.Combine(_rig.Root, "outside-" + name);

    private ImportEngine CacheEngine() => EngineBKit.Engine(_rig, new IImportPlanContributor[] { new CacheContributor() });

    private async Task<(ImportPlan Plan, ImportResult Result)> Run(ImportEngine engine, ImportOptions? options = null, Action? betweenPlanAndApply = null)
    {
        if (!File.Exists(Src("options.txt"))) Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        var plan = await engine.PlanAsync(_rig.Request(options: options), null, CancellationToken.None);
        Assert.True(plan.CanApply, string.Join("; ", plan.Blocked));
        betweenPlanAndApply?.Invoke();
        return (plan, await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None));
    }

    private void CacheMods()
    {
        Kit.WriteBytes(_rig.Source, "mods/dh.jar", Kit.FabricJar("distanthorizons", "2.3.3-b", name: "Distant Horizons"));
        Kit.WriteBytes(_rig.Source, "mods/voxy.jar", Kit.FabricJar("voxy", "0.1.5", name: "Voxy"));
    }

    private void MatchingWorld(string name)
    {
        Kit.WriteBytes(_rig.Source, $"saves/{name}/level.dat", EngineBKit.LevelDat(name, 1));
        Kit.WriteBytes(_rig.GameDir(), $"saves/{name}/level.dat", EngineBKit.LevelDat(name, 1));
    }

    private void Deny(string path)
    {
        ImportFixRoundTests.DenyAccess(path);
        _restore.Add(path);
    }

    private static void RestoreAccess(string path) => ImportFixRoundTests.RestoreAccess(path);

    // A single file nobody may read (the folder above stays listable).
    private void DenyFile(string file)
    {
        if (OperatingSystem.IsWindows())
        {
            using var process = Process.Start(new ProcessStartInfo("icacls", $"\"{file}\" /deny \"*S-1-1-0:(R)\"")
                { CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true })!;
            process.WaitForExit();
            Assert.True(process.ExitCode == 0, "couldn't deny access for the test");
        }
        else
        {
            File.SetUnixFileMode(file, UnixFileMode.None);
        }
        _restore.Add(file);
    }

    private static string ProfileId() => OriginLauncher.App.Core.Profiles.ProfileRules.NewId();

    // ------------------------------------------------------------ 1: every unreadable hit counts, not each short name once

    [Fact]
    public void ASecondTreeWithAnUnreadableFolderOfTheSameShortNameIsAlsoRefused()
    {
        foreach (var world in new[] { "W1", "W2" })
        {
            Kit.Write(_rig.Source, $"saves/{world}/level.dat", "LEVEL");
            Kit.Write(_rig.Source, $"saves/{world}/DIM-1/region/r.0.0.mca", "REGION");
            Deny(Src($"saves/{world}/DIM-1/region"));
        }
        var fs = new SourceFs();
        using var tx = new ImportTransaction(_rig.Imports, _rig.Root, ProfileId(), () => DateTime.UtcNow, "x", "1.21.1");
        tx.Begin();

        Assert.Throws<IOException>(() => tx.CopyTree(fs, Src("saves/W1"), Dst("saves/W1"), CancellationToken.None));
        // "DIM-1/region" is already in UnreadablePaths: the second world must still be refused, not placed half-empty.
        Assert.Throws<IOException>(() => tx.CopyTree(fs, Src("saves/W2"), Dst("saves/W2"), CancellationToken.None));
        Assert.False(Directory.Exists(Dst("saves/W2")));
        Assert.Equal(2, fs.UnreadableHits);
        tx.Rollback(null);
    }

    [Fact]
    public async Task AVoxyFolderWithAnUnreadablePartIsLeftToRebuildEvenWhenItsShortNameWasSeenBefore()
    {
        CacheMods();
        foreach (var server in new[] { "a.example.net_25565", "b.example.net_25565" })
        {
            Kit.Write(_rig.Source, $".voxy/saves/{server}/config.json", "{}");
            Kit.WriteBytes(_rig.Source, $".voxy/saves/{server}/w1/storage/000012.sst", new byte[] { 1, 2, 3 });
            Deny(Src($".voxy/saves/{server}/w1/storage"));
        }

        var (plan, result) = await Run(CacheEngine());

        Assert.True(result.Succeeded, result.Error);
        Assert.False(Directory.Exists(Dst(".voxy/saves/a.example.net_25565")));         // the lock-probe walk already met
        Assert.False(Directory.Exists(Dst(".voxy/saves/b.example.net_25565")));         // "w1/storage" before each copy walk
        Assert.Contains("couldn't be read", result.SummaryText);
    }

    // ------------------------------------------------------------ 3: authlib is a game file, not a credential

    [Fact]
    public async Task AuthlibIsReusedByItsSha1AndNeverReportedAsSkippedForSafety()
    {
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        var official = Path.Combine(_rig.Root, "dotminecraft");
        _mojang.WriteOfficial(official);
        var roots = new GameFileRoots { InstancesRoot = _rig.Instances, OfficialMinecraftDir = official };
        var engine = EngineBKit.Engine(_rig, new IImportPlanContributor[] { new GameFileContributor(roots, _mojang.Http) });

        var (plan, result) = await Run(engine);

        Assert.True(result.Succeeded, result.Error);
        Assert.True(File.Exists(Path.Combine(_rig.Instances, "1.21.1", "libraries", "com", "mojang", "authlib", "6.0.54", "authlib-6.0.54.jar")));
        Assert.Empty(plan.SkippedForSafety);
        Assert.DoesNotContain(plan.Lines, l => l.Name.Contains("authlib") && l.Section == SummarySection.NotImported);
        Assert.Equal(0, plan.GameFiles!.ToDownloadCount);
    }

    [Fact]
    public void TheGameFileGateStillNeverOpensALauncherCredentialStore()
    {
        var opened = new List<string>();
        var fs = new SourceFs(onOpen: opened.Add);
        var dir = Path.Combine(_rig.Root, "store");
        foreach (var name in new[] { "launcher_accounts_microsoft_store.json", "launcher_msa_credentials.bin", "accounts.json", "app.db" })
        {
            var path = Kit.Write(dir, name, "SECRET");
            Assert.False(fs.AllowVerifiedGameFile(path));
            Assert.Null(fs.OpenVerifiedGameFile(path));
        }
        Assert.Empty(opened);
        Assert.Empty(fs.SkippedForSafety);
    }

    // ------------------------------------------------------------ 4: a cache that can't be copied never fails the import

    [Fact]
    public async Task AWalCheckpointedAwayAfterThePlanStillCopiesTheGroupAndTheImportSucceeds()
    {
        CacheMods();
        MatchingWorld("W");
        Kit.WriteBytes(_rig.Source, "saves/W/data/DistantHorizons.sqlite", EngineBKit.Sqlite("main"));
        Kit.WriteBytes(_rig.Source, "saves/W/data/DistantHorizons.sqlite-wal", Encoding.ASCII.GetBytes("WAL"));
        Kit.WriteBytes(_rig.Source, "saves/W/data/DistantHorizons.sqlite-shm", Encoding.ASCII.GetBytes("SHM"));

        var (_, result) = await Run(CacheEngine(), betweenPlanAndApply: () => File.Delete(Src("saves/W/data/DistantHorizons.sqlite-wal")));

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(EngineBKit.Sqlite("main"), File.ReadAllBytes(Dst("saves/W/data/DistantHorizons.sqlite")));
        Assert.False(File.Exists(Dst("saves/W/data/DistantHorizons.sqlite-wal")));
    }

    [Fact]
    public async Task ACacheFileThatCantBeReadLeavesThatCacheToRebuildAndTheImportStillSucceeds()
    {
        CacheMods();
        Kit.Write(_rig.Source, ".voxy/saves/play.example.net_25565/config.json", "{}");
        var sst = Kit.WriteBytes(_rig.Source, ".voxy/saves/play.example.net_25565/1a2b/storage/000012.sst", new byte[] { 1, 2, 3 });
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        DenyFile(sst);                                                                   // e.g. held by an antivirus scan

        var (plan, result) = await Run(CacheEngine());

        Assert.True(result.Succeeded, result.Error);
        Assert.False(Directory.Exists(Dst(".voxy/saves/play.example.net_25565")));
        Assert.Contains("it will rebuild", result.SummaryText);
        Assert.Contains("fov:0.5", File.ReadAllText(Dst("options.txt")));               // the rest of the import landed
    }

    // ------------------------------------------------------------ 5: links on built paths are never followed

    [Fact]
    public void OpenReadRefusesAFileReachedThroughALinkBelowTheInstanceButFollowsTheFirstLevelFolders()
    {
        Kit.Write(Outside("private"), "level.dat", "PRIVATE");
        Kit.Write(Outside("shared-saves"), "W/level.dat", "SHARED");
        ImportFixRoundTests.Link(Src("saves"), Outside("shared-saves"));               // a first-level folder: followed
        ImportFixRoundTests.Link(Src("saves/W/escape"), Outside("private"));           // anything deeper: never
        ImportFixRoundTests.Link(Src("Distant_Horizons_server_data"), Outside("private")); // not one of the followed five
        var fs = new SourceFs { InstanceRoot = _rig.Source };

        using (var shared = fs.OpenRead(Src("saves/W/level.dat"))) Assert.NotNull(shared);
        Assert.Null(fs.OpenRead(Src("saves/W/escape/level.dat")));
        Assert.Null(fs.OpenRead(Src("Distant_Horizons_server_data/level.dat")));
        Assert.Null(LevelDat.Read(Src("saves/W/escape/level.dat"), fs));
        Assert.Contains(fs.SkippedLinks, l => l.Contains("level.dat"));
    }

    [Fact]
    public async Task ALinkedVoxyFolderOrDhDimensionInsideAWorldIsNeverFollowed()
    {
        CacheMods();
        MatchingWorld("W");
        Kit.Write(Outside("voxy"), "config.json", "{\"elsewhere\":true}");
        Kit.WriteBytes(Outside("voxy"), "w/storage/000001.sst", new byte[] { 9 });
        Kit.WriteBytes(Outside("dim"), "data/DistantHorizons.sqlite", EngineBKit.Sqlite("elsewhere"));
        ImportFixRoundTests.Link(Src("saves/W/voxy"), Outside("voxy"));
        ImportFixRoundTests.Link(Src("saves/W/DIM-1"), Outside("dim"));

        var (plan, result) = await Run(CacheEngine());

        Assert.True(result.Succeeded, result.Error);
        Assert.False(Directory.Exists(Dst("saves/W/voxy")));
        Assert.False(File.Exists(Dst("saves/W/DIM-1/data/DistantHorizons.sqlite")));
        Assert.DoesNotContain(plan.Caches, c => c.WillImport);
        Assert.Contains(plan.Lines, l => l.Name.Contains("Linked item left out") && l.Name.Contains("voxy"));
        Assert.Contains(plan.Lines, l => l.Name.Contains("Linked item left out") && l.Name.Contains("DIM-1"));
    }

    [Fact]
    public async Task ASelectedResourcePackThatIsALinkIsNeverCopied()
    {
        Kit.Write(Outside("pack"), "pack.mcmeta", "{\"pack\":{\"pack_format\":34,\"description\":\"x\"}}");
        Kit.Write(Outside("pack"), "secret.txt", "NOT-PART-OF-THE-INSTANCE");
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\nresourcePacks:[\"vanilla\",\"file/Linked\"]\n");
        Kit.Write(_rig.Source, "resourcepacks/Real/pack.mcmeta", "{\"pack\":{\"pack_format\":34,\"description\":\"y\"}}");
        ImportFixRoundTests.Link(Src("resourcepacks/Linked"), Outside("pack"));

        var (_, result) = await Run(_rig.Engine());

        Assert.True(result.Succeeded, result.Error);
        Assert.False(Directory.Exists(Dst("resourcepacks/Linked")));
        Assert.DoesNotContain(Directory.EnumerateFiles(_rig.Instances, "secret.txt", SearchOption.AllDirectories), _ => true);
    }

    // ------------------------------------------------------------ 6: quoted paths with spaces are trimmed too

    [Theory]
    [InlineData("Access to the path 'C:\\Users\\Will Henry\\AppData\\Local\\Origin Launcher\\instances\\options.txt' is denied.", "options.txt", "Will Henry")]
    [InlineData("Could not find a part of the path '/home/will henry/.local/share/origin launcher/journal.json'.", "journal.json", "will henry")]
    [InlineData("The file '\\\\nas\\Shared Stuff\\Minecraft Saves\\level.dat' is in use.", "level.dat", "Shared Stuff")]
    public void AQuotedPathWithSpacesIsCutToItsFileName(string message, string mustKeep, string mustDrop)
    {
        var plain = ImportErrors.Plain(message);
        Assert.Contains(mustKeep, plain);
        Assert.DoesNotContain(mustDrop, plain);
        Assert.DoesNotContain("Users", plain);
        Assert.DoesNotContain("AppData", plain);
        Assert.DoesNotContain("/home", plain);
    }

    // ------------------------------------------------------------ 7: the address that answered is checked too

    [Theory]
    [InlineData("https://evil.example.com/version_manifest_v2.json")]
    [InlineData("http://launchermeta.mojang.com/mc/game/version_manifest_v2.json")]
    [InlineData("https://launchermeta.mojang.com:8443/mc/game/version_manifest_v2.json")]
    public async Task ADocumentWhoseFinalAddressIsNotAllowedIsRefused(string finalUrl)
    {
        var http = _mojang.Http;
        http.FinalUrls[MetadataClient.VersionManifestUrl] = finalUrl;                     // a transport that followed a redirect

        Assert.Null(await new MetadataClient(http).GetAsync(MetadataClient.VersionManifestUrl, null, CancellationToken.None));
        var set = await new MojangMetadata(new MetadataClient(http)).GetRequiredSetAsync("1.21.1", CancellationToken.None);
        Assert.True(set.Unavailable);
        Assert.Empty(set.Files);
    }

    // ------------------------------------------------------------ 8: metadata values that would form bad paths are dropped

    private const string VersionUrl = "https://piston-meta.mojang.com/v1/packages/x/9.9.json";
    private const string IndexUrl = "https://piston-meta.mojang.com/v1/packages/y/9.json";
    private const string RuntimeUrl = "https://piston-meta.mojang.com/v1/packages/z/manifest.json";

    private static RecordingHttp HostileMetadata(string component, out string good)
    {
        var http = new RecordingHttp();
        var ok = EngineBKit.Sha1(Encoding.UTF8.GetBytes("GOOD"));
        good = ok;
        var index = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new
        {
            objects = new Dictionary<string, object>
            {
                ["ok"] = new { hash = ok, size = 4 },
                ["climb"] = new { hash = "../../../../../../../../../../../../../../../../../../../../x", size = 1 },
                ["upper"] = new { hash = new string('A', 40), size = 1 },
                ["short"] = new { hash = "abcd", size = 1 },
            },
        }));
        object Lib(string path, string sha1) => new { name = path, downloads = new { artifact = new { path, sha1, size = 4, url = "https://libraries.minecraft.net/x.jar" } } };
        var version = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new
        {
            id = "9.9",
            downloads = new { client = new { sha1 = "not-a-sha1", size = 4, url = "https://piston-data.mojang.com/c.jar" } },
            libraries = new[]
            {
                Lib("ok/ok/1/ok-1.jar", ok),
                Lib("../../escape.jar", ok),
                Lib("C:/Windows/evil.jar", ok),
                Lib("/abs/evil.jar", ok),
                Lib("\\\\server\\share\\evil.jar", ok),
                Lib("ok/badsha/1/x.jar", "1234"),
            },
            assetIndex = new { id = "9", sha1 = EngineBKit.Sha1(index), size = index.Length, url = IndexUrl },
            javaVersion = new { component, majorVersion = 21 },
        }));
        var runtime = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new
        {
            files = new Dictionary<string, object>
            {
                ["bin/java.exe"] = new { type = "file", downloads = new { raw = new { sha1 = ok, size = 4, url = "https://piston-data.mojang.com/j" } } },
                ["C:/evil.exe"] = new { type = "file", downloads = new { raw = new { sha1 = ok, size = 4, url = "https://piston-data.mojang.com/j" } } },
                ["/abs.exe"] = new { type = "file", downloads = new { raw = new { sha1 = ok, size = 4, url = "https://piston-data.mojang.com/j" } } },
                ["lib/badsha"] = new { type = "file", downloads = new { raw = new { sha1 = "ZZ", size = 4, url = "https://piston-data.mojang.com/j" } } },
            },
        }));
        http.Docs[MetadataClient.VersionManifestUrl] = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new
        {
            versions = new[] { new { id = "9.9", url = VersionUrl, sha1 = EngineBKit.Sha1(version) } },
        }));
        http.Docs[VersionUrl] = version;
        http.Docs[IndexUrl] = index;
        http.Docs[RuntimeUrl] = runtime;
        http.Docs[MetadataClient.JavaRuntimeListUrl] = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["windows-x64"] = new Dictionary<string, object>
            {
                [component] = new[] { new { manifest = new { sha1 = EngineBKit.Sha1(runtime), size = runtime.Length, url = RuntimeUrl } } },
            },
        }));
        return http;
    }

    [Fact]
    public async Task MetadataHashesAndPathsThatWouldFormBadFileNamesAreDropped()
    {
        var http = HostileMetadata("java-runtime-delta", out var good);

        var set = await new MojangMetadata(new MetadataClient(http)).GetRequiredSetAsync("9.9", CancellationToken.None);

        Assert.False(set.Unavailable);
        Assert.False(set.JavaSkipped);
        Assert.Equal(new[]
        {
            "assets/indexes/9.json",
            $"assets/objects/{good[..2]}/{good}",
            "libraries/ok/ok/1/ok-1.jar",
            "runtime/windows-x64/java-runtime-delta/bin/java.exe",
        }, set.Files.Select(f => f.RelPath).OrderBy(p => p, StringComparer.Ordinal));
    }

    [Fact]
    public async Task ARuntimeComponentThatIsNotAPlainNameSkipsJava()
    {
        var http = HostileMetadata("../../evil", out _);

        var set = await new MojangMetadata(new MetadataClient(http)).GetRequiredSetAsync("9.9", CancellationToken.None);

        Assert.False(set.Unavailable);
        Assert.True(set.JavaSkipped);
        Assert.DoesNotContain(set.Files, f => f.Kind == RequiredKind.Runtime);
        Assert.DoesNotContain(http.Requests, r => r.Url == RuntimeUrl);
    }

    // ------------------------------------------------------------ 9: a gate that throws on release can't undo a commit

    private sealed class ThrowingGate : IVersionGate
    {
        public Task<IAsyncDisposable> AcquireAsync(string version, CancellationToken ct) => Task.FromResult<IAsyncDisposable>(new Lease());

        private sealed class Lease : IAsyncDisposable
        {
            public ValueTask DisposeAsync() => throw new InvalidOperationException("gate release failed");
        }
    }

    [Fact]
    public async Task AGateThatThrowsOnReleaseNeverTurnsACommittedImportIntoRolledBack()
    {
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        var official = Path.Combine(_rig.Root, "dotminecraft");
        _mojang.WriteOfficial(official);
        var roots = new GameFileRoots { InstancesRoot = _rig.Instances, OfficialMinecraftDir = official };
        var engine = EngineBKit.Engine(_rig, new IImportPlanContributor[] { new GameFileContributor(roots, _mojang.Http, new ThrowingGate()) });

        var (plan, result) = await Run(engine);

        Assert.NotNull(plan.ProvisioningGate);
        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(ImportState.Committed, result.State);
        Assert.Equal(ImportState.Committed, ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, plan.ImportId))!.State);
        Assert.Contains(_rig.LogLines, l => l.Contains("releasing the version gate failed"));
    }
}
