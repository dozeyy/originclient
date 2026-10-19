using System.Text;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.GameFiles;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Game-file reuse: what the target needs (from Mojang's metadata), what is copied from other launchers
// and how it is verified, and what is never touched. All folders and every "Mojang" document is a fixture.
public class GameFileTests : IDisposable
{
    private readonly Rig _rig = new();
    private readonly Mojang _mojang = new();

    public void Dispose() => _rig.Dispose();

    private string Official => Path.Combine(_rig.Root, "dotminecraft");
    private string Prism => Path.Combine(_rig.Root, "Prism");
    private string VersionRoot => Path.Combine(_rig.Instances, "1.21.1");

    private GameFileRoots Roots() => new()
    {
        InstancesRoot = _rig.Instances, OfficialMinecraftDir = Official, PrismDataDir = Prism,
    };

    private ImportEngine Engine(GameFileRoots? roots = null, IVersionGate? gate = null, Func<string, long>? freeSpace = null) =>
        EngineBKit.Engine(_rig, new IImportPlanContributor[] { new GameFileContributor(roots ?? Roots(), _mojang.Http, gate) }, freeSpace);

    private void MinimalSource() => Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");

    private async Task<(ImportPlan Plan, ImportResult Result)> Run(ImportEngine? engine = null, ImportRequest? request = null)
    {
        engine ??= Engine();
        var plan = await engine.PlanAsync(request ?? _rig.Request(), null, CancellationToken.None);
        Assert.True(plan.CanApply, string.Join("; ", plan.Blocked));
        return (plan, await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None));
    }

    private string Dest(string rel) => Path.Combine(VersionRoot, rel.Replace('/', Path.DirectorySeparatorChar));

    // ------------------------------------------------------------ the required set (criterion 14)

    [Fact]
    public async Task TheRequiredSetIsBuiltFromTheVersionJsonTheAssetIndexAndTheRuntimeManifest()
    {
        var set = await new MojangMetadata(new MetadataClient(_mojang.Http)).GetRequiredSetAsync("1.21.1", CancellationToken.None);

        Assert.False(set.Unavailable);
        Assert.False(set.JavaSkipped);
        // Everything in the fixture except what Windows x64 doesn't use (the macOS-only and x86 libraries, the link entry).
        Assert.Equal(_mojang.Files.Keys.OrderBy(k => k, StringComparer.Ordinal),
            set.Files.Select(f => f.RelPath).OrderBy(k => k, StringComparer.Ordinal));
        Assert.All(set.Files, f => Assert.Equal(_mojang.Files[f.RelPath].Length, f.Size));
        Assert.All(set.Files, f => Assert.Equal(EngineBKit.Sha1(_mojang.Files[f.RelPath]), f.Sha1));
        Assert.Equal(Mojang.Component, set.RuntimeComponent);
        Assert.Contains(set.Files, f => f.Kind == RequiredKind.Native && f.RelPath.EndsWith("native-1-natives-windows.jar"));   // classifier-style
        Assert.DoesNotContain(set.Files, f => f.RelPath.Contains("mac/only") || f.RelPath.Contains("natives-windows-x86"));
    }

    [Fact]
    public async Task FilesOriginAlreadyHasAreNeverTouched()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        var existing = Dest("libraries/com/a/b/1/b-1.jar");
        Kit.WriteBytes(VersionRoot, "libraries/com/a/b/1/b-1.jar", Encoding.UTF8.GetBytes("ORIGIN'S OWN COPY"));
        var old = new DateTime(2021, 3, 4, 5, 6, 7, DateTimeKind.Utc);
        File.SetLastWriteTimeUtc(existing, old);

        var (plan, result) = await Run();

        Assert.True(result.Succeeded);
        Assert.Equal("ORIGIN'S OWN COPY", File.ReadAllText(existing));
        Assert.Equal(old, File.GetLastWriteTimeUtc(existing));
        Assert.Equal(1, plan.GameFiles!.AlreadyPresentCount);
        Assert.True(File.Exists(Dest("libraries/org/c/d/1/d-1.jar")));                  // the rest still comes across
    }

    // ------------------------------------------------------------ verify, copy, corrupt (15, 16)

    [Fact]
    public async Task GoodCandidatesAreCopiedAndACorruptOneIsNotCopiedAndCountsAsToDownload()
    {
        MinimalSource();
        var corruptAsset = _mojang.AssetPath(3);
        _mojang.WriteOfficial(Official);
        File.WriteAllBytes(Path.Combine(Official, corruptAsset.Replace('/', Path.DirectorySeparatorChar)), new byte[_mojang.Files[corruptAsset].Length]); // same size, wrong bytes
        var truncated = "libraries/org/c/d/1/d-1.jar";
        File.WriteAllBytes(Path.Combine(Official, truncated.Replace('/', Path.DirectorySeparatorChar)), _mojang.Files[truncated][..10]);

        var (plan, result) = await Run();

        Assert.True(result.Succeeded);
        foreach (var rel in _mojang.Files.Keys.Where(k => k != corruptAsset && k != truncated))
            Assert.Equal(_mojang.Files[rel], File.ReadAllBytes(Dest(rel)));             // byte-identical, verified
        Assert.False(File.Exists(Dest(corruptAsset)));
        Assert.False(File.Exists(Dest(truncated)));
        var stats = plan.GameFiles!;
        Assert.Equal(2, stats.ToDownloadCount);
        Assert.Equal(_mojang.Files[corruptAsset].Length + _mojang.Files[truncated].Length, stats.ToDownloadBytes);
        Assert.Equal(_mojang.Files.Count - 2, stats.ReusedCount);
        Assert.Contains("2 game files not found on this PC", result.SummaryText);
        Assert.Contains($"about {ImportSummary.Megabytes(stats.ToDownloadBytes)} will download the first time you press Play", result.SummaryText);
        // copied through a temp file and renamed: no temp left anywhere in the version folder
        Assert.DoesNotContain(Directory.EnumerateFiles(VersionRoot, "*", SearchOption.AllDirectories), f => Path.GetFileName(f).StartsWith("tmp-"));
    }

    [Fact]
    public async Task ACorruptCandidateInOneLauncherIsSkippedForAGoodOneInAnother()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        var lib = "libraries/com/a/b/1/b-1.jar";
        File.WriteAllBytes(Path.Combine(Official, lib.Replace('/', Path.DirectorySeparatorChar)), new byte[_mojang.Files[lib].Length]);
        _mojang.WritePrism(Prism);

        var (plan, _) = await Run();

        Assert.Equal(_mojang.Files[lib], File.ReadAllBytes(Dest(lib)));                 // from Prism
        Assert.Equal(0, plan.GameFiles!.ToDownloadCount);
        Assert.Contains(_rig.Opened, p => p.StartsWith(Prism));
    }

    [Fact]
    public async Task PrismsClientJarLocationAndOriginsOwnOtherVersionsAreReadAsSources()
    {
        MinimalSource();
        _mojang.WritePrism(Prism);
        // Another Origin version has the runtime and the assets-with-the-same-hashes; the target's own folder is not a source.
        _mojang.WriteOriginVersion(_rig.Instances, "1.20.4");

        var (plan, _) = await Run(Engine(new GameFileRoots { InstancesRoot = _rig.Instances, PrismDataDir = Prism }));

        Assert.Equal(_mojang.Files["versions/1.21.1/1.21.1.jar"], File.ReadAllBytes(Dest("versions/1.21.1/1.21.1.jar")));
        Assert.Equal(_mojang.Files["runtime/windows-x64/java-runtime-delta/bin/javaw.exe"], File.ReadAllBytes(Dest("runtime/windows-x64/java-runtime-delta/bin/javaw.exe")));
        Assert.Equal(0, plan.GameFiles!.ToDownloadCount);
    }

    [Fact]
    public async Task ACopiedFileIsIndependentOfItsSourceNoLinksAndTheSourceTreeIsUntouched()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        _mojang.WritePrism(Prism);
        var before = (Kit.TreeHash(Official, mtimes: true), Kit.TreeHash(Prism, mtimes: true));
        var lib = "libraries/com/a/b/1/b-1.jar";
        var sourceCopy = Path.Combine(Official, lib.Replace('/', Path.DirectorySeparatorChar));
        var sourceHash = EngineBKit.Sha1(File.ReadAllBytes(sourceCopy));

        var (_, result) = await Run();
        Assert.True(result.Succeeded);

        using (var write = new FileStream(Dest(lib), FileMode.Append, FileAccess.Write)) write.WriteByte(0x42); // change the destination copy
        Assert.Equal(sourceHash, EngineBKit.Sha1(File.ReadAllBytes(sourceCopy)));      // the source did not move: not a hardlink
        Assert.NotEqual(sourceHash, EngineBKit.Sha1(File.ReadAllBytes(Dest(lib))));
        Assert.Equal(before, (Kit.TreeHash(Official, mtimes: true), Kit.TreeHash(Prism, mtimes: true)));
    }

    [Fact]
    public async Task OriginVerifiedJsonIsNeverCreatedOrChanged()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        var marker = Dest("origin-verified.json");

        var (_, first) = await Run();
        Assert.True(first.Succeeded);
        Assert.False(File.Exists(marker));                                              // not created

        Kit.Write(VersionRoot, "origin-verified.json", """{"verified":"yesterday"}""");
        var stamp = new DateTime(2022, 2, 2, 2, 2, 2, DateTimeKind.Utc);
        File.SetLastWriteTimeUtc(marker, stamp);
        Directory.Delete(Path.Combine(VersionRoot, "assets"), recursive: true);        // a second import has something to copy again

        var (_, second) = await Run();
        Assert.True(second.Succeeded);
        Assert.Equal("""{"verified":"yesterday"}""", File.ReadAllText(marker));        // not changed
        Assert.Equal(stamp, File.GetLastWriteTimeUtc(marker));
    }

    [Fact]
    public async Task FabricLoaderLibrariesAndProfileJsonAreNeverCopied()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        Kit.Write(Official, "versions/fabric-loader-0.16.10-1.21.1/fabric-loader-0.16.10-1.21.1.json", """{"inheritsFrom":"1.21.1"}""");
        Kit.WriteBytes(Official, "libraries/net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar", new byte[] { 1, 2, 3 });
        Kit.WriteBytes(Official, "libraries/net/fabricmc/intermediary/1.21.1/intermediary-1.21.1.jar", new byte[] { 4, 5, 6 });

        var (_, result) = await Run();

        Assert.True(result.Succeeded);
        Assert.False(Directory.Exists(Dest("versions/fabric-loader-0.16.10-1.21.1")));
        Assert.False(Directory.Exists(Dest("libraries/net/fabricmc")));
        Assert.DoesNotContain(Directory.EnumerateFiles(VersionRoot, "*", SearchOption.AllDirectories), f => f.Contains("fabric"));
        Assert.DoesNotContain(Directory.EnumerateFiles(VersionRoot, "*.json", SearchOption.AllDirectories).Select(Path.GetFileName), n => n != "17.json");
    }

    [Fact]
    public async Task AMetadataPathThatClimbsOutOfTheVersionFolderIsNeverWritten()
    {
        MinimalSource();
        // Re-serve the version json with a hostile library path (with a correct SHA-1 chain so only the path check can stop it).
        var versionUrl = "https://piston-meta.mojang.com/v1/packages/aaaa/1.21.1.json";
        var json = Encoding.UTF8.GetString(_mojang.Http.Docs[versionUrl]!)
            .Replace("com/a/b/1/b-1.jar", "../../x.jar");
        _mojang.Http.Docs[versionUrl] = Encoding.UTF8.GetBytes(json);
        var manifest = Encoding.UTF8.GetString(_mojang.Http.Docs[MetadataClient.VersionManifestUrl]!);
        _mojang.Http.Docs[MetadataClient.VersionManifestUrl] = Encoding.UTF8.GetBytes(
            System.Text.RegularExpressions.Regex.Replace(manifest, "\"sha1\":\"[0-9a-f]{40}\"", $"\"sha1\":\"{EngineBKit.Sha1(_mojang.Http.Docs[versionUrl]!)}\""));
        Kit.WriteBytes(_rig.Root, "x.jar", _mojang.Files["libraries/com/a/b/1/b-1.jar"]);  // the "source" a hostile path would reach
        _mojang.WriteOfficial(Official);

        await Run();

        Assert.False(File.Exists(Path.Combine(_rig.Instances, "x.jar")));
    }

    // ------------------------------------------------------------ the Java runtime (19)

    [Fact]
    public async Task AnOfficialLayoutRuntimeIsMappedIntoOriginsLayoutAndEveryFileIsVerifiedAgainstTheManifest()
    {
        MinimalSource();
        var store = Path.Combine(_rig.Root, "StoreRuntime");
        _mojang.WriteOfficial(Official, runtimeRoot: store, skip: rel => rel.StartsWith("runtime/"));
        _mojang.WriteOfficial(Path.Combine(_rig.Root, "unused"), runtimeRoot: store, skip: rel => !rel.StartsWith("runtime/"));
        // The source's own listings are not proof: a tampered file beside a matching .sha1 must still be refused.
        Kit.Write(store, $"{Mojang.Component}/windows-x64/{Mojang.Component}/.version", "21.0.4");
        Kit.Write(store, $"{Mojang.Component}/{Mojang.Component}.sha1", "bin/javaw.exe /#// 0000000000000000000000000000000000000000");
        File.WriteAllText(Path.Combine(store, Mojang.Component, "windows-x64", Mojang.Component, "release"), "TAMPERED");

        var roots = new GameFileRoots { InstancesRoot = _rig.Instances, OfficialMinecraftDir = Official, OfficialRuntimeRoots = new[] { store } };
        var (plan, result) = await Run(Engine(roots));

        Assert.True(result.Succeeded);
        Assert.Equal(_mojang.Files["runtime/windows-x64/java-runtime-delta/bin/javaw.exe"], File.ReadAllBytes(Dest("runtime/windows-x64/java-runtime-delta/bin/javaw.exe")));
        Assert.Equal(_mojang.Files["runtime/windows-x64/java-runtime-delta/lib/modules"], File.ReadAllBytes(Dest("runtime/windows-x64/java-runtime-delta/lib/modules")));
        Assert.False(File.Exists(Dest("runtime/windows-x64/java-runtime-delta/release")));        // wrong bytes: not copied
        Assert.False(File.Exists(Dest("runtime/windows-x64/java-runtime-delta/.version")));       // not in the manifest: not copied
        Assert.False(File.Exists(Dest($"runtime/windows-x64/java-runtime-delta/{Mojang.Component}.sha1")));
        Assert.Equal(1, plan.GameFiles!.ToDownloadCount);
    }

    [Fact]
    public async Task WithoutAnAvailableRuntimeManifestJavaReuseIsSkippedAndTheSummarySaysSo()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        _mojang.Http.Docs.Remove(MetadataClient.JavaRuntimeListUrl);                    // offline for the Java list only

        var (plan, result) = await Run();

        Assert.True(result.Succeeded);
        Assert.True(plan.GameFiles!.JavaSkipped);
        Assert.False(Directory.Exists(Dest("runtime")));
        Assert.True(File.Exists(Dest("libraries/com/a/b/1/b-1.jar")));                  // everything else still reused
        Assert.Contains("Java will download on first Play", result.SummaryText);
    }

    [Fact]
    public async Task ATamperedRuntimeManifestIsRejectedByItsSha1AndJavaIsSkipped()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        var url = "https://piston-meta.mojang.com/v1/packages/cccc/manifest.json";
        _mojang.Http.Docs[url] = Encoding.UTF8.GetBytes("""{"files":{"bin/javaw.exe":{"type":"file","downloads":{"raw":{"sha1":"00","size":1}}}}}""");

        var (plan, _) = await Run();

        Assert.True(plan.GameFiles!.JavaSkipped);
        Assert.False(Directory.Exists(Dest("runtime")));
    }

    [Fact]
    public async Task UnreachableMetadataSkipsAllReuseAndSaysSo()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        _mojang.Http.Docs.Clear();

        var (plan, result) = await Run();

        Assert.True(result.Succeeded);
        Assert.True(plan.GameFiles!.MetadataUnavailable);
        Assert.False(Directory.Exists(Dest("libraries")));
        Assert.Contains("Couldn't reach Mojang's version list", result.SummaryText);
    }

    // ------------------------------------------------------------ network (20)

    [Fact]
    public async Task OnlyAllowlistedSmallMetadataIsEverRequestedAndThePlanNumbersEqualTheFixtureSums()
    {
        MinimalSource();
        var corruptAsset = _mojang.AssetPath(2);
        _mojang.WriteOfficial(Official);
        File.WriteAllBytes(Path.Combine(Official, corruptAsset.Replace('/', Path.DirectorySeparatorChar)), new byte[_mojang.Files[corruptAsset].Length]);
        File.Delete(Path.Combine(Official, "versions", "1.21.1", "1.21.1.jar"));      // and a missing one

        var plan = await Engine().PlanAsync(_rig.Request(), null, CancellationToken.None);

        Assert.NotEmpty(_mojang.Http.Requests);
        Assert.All(_mojang.Http.Requests, r =>
        {
            Assert.True(MetadataClient.IsAllowed(r.Url), r.Url);
            Assert.True(r.Length < 1024 * 1024, r.Url);
        });
        Assert.DoesNotContain(_mojang.Http.Requests, r => r.Url.Contains("libraries.minecraft.net") || r.Url.Contains("piston-data") || r.Url.EndsWith(".jar"));
        Assert.Equal(5, _mojang.Http.Requests.Count);   // manifest, version json, asset index, runtime list, runtime manifest: nothing else
        var stats = plan.GameFiles!;
        var missing = new[] { corruptAsset, "versions/1.21.1/1.21.1.jar" };
        Assert.Equal(_mojang.Files.Where(f => !missing.Contains(f.Key)).Sum(f => (long)f.Value.Length), stats.ReusedBytes);
        Assert.Equal(_mojang.Files.Where(f => missing.Contains(f.Key)).Sum(f => (long)f.Value.Length), stats.ToDownloadBytes);
        Assert.Equal(missing.Length, stats.ToDownloadCount);
        Assert.Equal(_mojang.Files.Count - missing.Length, stats.ReusedCount);
        var reusedLines = plan.Lines.Where(l => l.Section == SummarySection.ReusedGameFiles).ToList();
        Assert.Equal(stats.ReusedCount, reusedLines.Count);
        Assert.Equal(stats.ReusedBytes, reusedLines.Sum(l => l.Bytes));
        Assert.Contains($"about {ImportSummary.Megabytes(stats.ToDownloadBytes)} will download", ImportSummary.Render(plan));
    }

    [Fact]
    public async Task TheMetadataClientRefusesOtherHostsPlainHttpBigBodiesAndAWrongSha1()
    {
        Assert.True(MetadataClient.IsAllowed("https://piston-meta.mojang.com/v1/packages/x/y.json"));
        Assert.True(MetadataClient.IsAllowed("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"));
        foreach (var bad in new[]
                 {
                     "https://piston-data.mojang.com/v1/objects/x/client.jar", "https://libraries.minecraft.net/a.jar",
                     "https://resources.download.minecraft.net/ab/abcd", "http://piston-meta.mojang.com/x.json",
                     "https://piston-meta.mojang.com.evil.example/x.json", "https://piston-meta.mojang.com:8443/x.json", "file:///c:/x", "not a url",
                 })
            Assert.False(MetadataClient.IsAllowed(bad), bad);

        var http = new RecordingHttp();
        http.Docs["https://piston-meta.mojang.com/small.json"] = Encoding.UTF8.GetBytes("{}");
        http.Docs["https://piston-meta.mojang.com/big.json"] = new byte[1024 * 1024];
        var client = new MetadataClient(http);
        Assert.NotNull(await client.GetAsync("https://piston-meta.mojang.com/small.json", EngineBKit.Sha1(Encoding.UTF8.GetBytes("{}")), CancellationToken.None));
        Assert.Null(await client.GetAsync("https://piston-meta.mojang.com/small.json", new string('0', 40), CancellationToken.None));
        Assert.Null(await client.GetAsync("https://piston-meta.mojang.com/big.json", null, CancellationToken.None));
        Assert.Null(await client.GetAsync("https://libraries.minecraft.net/a.jar", null, CancellationToken.None));
        Assert.DoesNotContain(http.Requests, r => r.Url.Contains("libraries.minecraft.net"));  // refused before any request was made
    }

    [Fact]
    public async Task AVersionJsonWhoseSha1DoesNotMatchTheManifestIsNotTrusted()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        var url = "https://piston-meta.mojang.com/v1/packages/aaaa/1.21.1.json";
        _mojang.Http.Docs[url] = Encoding.UTF8.GetBytes(Encoding.UTF8.GetString(_mojang.Http.Docs[url]!) + " ");

        var (plan, _) = await Run();

        Assert.True(plan.GameFiles!.MetadataUnavailable);
        Assert.False(Directory.Exists(Dest("libraries")));
    }

    // ------------------------------------------------------------ gate, space, cancel (21, 22, extend 50)

    [Fact]
    public async Task TheVersionGateIsHeldForTheWholeApplyAndAConcurrentAcquireWaits()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        var gate = new FakeGate();
        var engine = Engine(gate: gate);
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);
        Assert.Same(gate, plan.ProvisioningGate);

        Task<IAsyncDisposable>? waiter = null;
        var waitedWhileHeld = false;
        var progress = new SyncProgress(p =>
        {
            if (waiter != null || p.Stage != ImportStage.GameFiles || p.Item?.StartsWith("assets/objects") != true) return;
            waiter = gate.AcquireAsync("1.21.1", CancellationToken.None);   // another launch/install of the same version
            Thread.Sleep(150);
            waitedWhileHeld = !waiter.IsCompleted;
        });

        var result = await engine.ApplyAsync(plan, _rig.Settings, progress, CancellationToken.None);

        Assert.True(result.Succeeded);
        Assert.NotNull(waiter);
        Assert.True(waitedWhileHeld);                                      // it waited while the import wrote files
        await using (await waiter!.WaitAsync(TimeSpan.FromSeconds(5))) { } // and got the gate once the import released it
        Assert.Equal(2, gate.Acquired);
        Assert.Equal(2, gate.Released);
    }

    [Fact]
    public async Task TheGateIsReleasedWhenTheImportIsCancelled()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        var gate = new FakeGate();
        using var cts = new CancellationTokenSource();
        var engine = Engine(gate: gate);
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);

        var result = await engine.ApplyAsync(plan, _rig.Settings,
            new SyncProgress(p => { if (p.Stage == ImportStage.GameFiles && p.Item?.StartsWith("libraries") == true) cts.Cancel(); }), cts.Token);

        Assert.True(result.Cancelled);
        Assert.Equal(1, gate.Acquired);
        Assert.Equal(1, gate.Released);
    }

    [Fact]
    public async Task FreeSpaceUnderTheNeedIncludingGameFilesBlocksTheImport()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        var without = await _rig.Engine().PlanAsync(_rig.Request(), null, CancellationToken.None);
        var with = await Engine().PlanAsync(_rig.Request(), null, CancellationToken.None);
        Assert.True(with.BytesToCopy >= without.BytesToCopy + with.GameFiles!.ReusedBytes);   // game-file bytes are in the total
        var needed = (long)(with.BytesToCopy * 1.1);
        var between = (long)(without.BytesToCopy * 1.1) + 1;
        Assert.True(between < needed);

        var blocked = await Engine(freeSpace: _ => between).PlanAsync(_rig.Request(), null, CancellationToken.None);   // enough for everything but the game files
        Assert.False(blocked.CanApply);
        Assert.Contains("Not enough free disk space", blocked.Blocked[0]);
        Assert.True((await Engine(freeSpace: _ => needed).PlanAsync(_rig.Request(), null, CancellationToken.None)).CanApply);
    }

    [Fact]
    public async Task CancellingMidGameFilesRollsEverythingBackToAnIdenticalDestination()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        Kit.WriteBytes(VersionRoot, "libraries/pre-existing.jar", new byte[] { 9, 9, 9 });
        var before = Kit.TreeHash(_rig.Instances, mtimes: true);
        var settingsBefore = _rig.Settings.FileHash();
        using var cts = new CancellationTokenSource();
        var copied = 0;
        var engine = Engine();
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);

        var result = await engine.ApplyAsync(plan, _rig.Settings,
            new SyncProgress(p => { if (p.Stage == ImportStage.GameFiles && p.Item != null && ++copied == 6) cts.Cancel(); }), cts.Token);

        Assert.True(result.Cancelled);
        Assert.Equal(ImportState.RolledBack, result.State);
        Assert.Equal(before, Kit.TreeHash(_rig.Instances, mtimes: true));
        Assert.Equal(settingsBefore, _rig.Settings.FileHash());
        Assert.Empty(ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, plan.ImportId))!.RollbackProblems);
        Assert.False(Directory.Exists(ImportLayout.Staging(_rig.Imports, plan.ImportId)));
    }

    // Review round 2, finding 2: undoing a FINISHED import leaves the reused game files (Mojang-verified files
    // Origin would download anyway; their mtimes are the source's, so a later use can't be seen), refuses while
    // Origin's game runs, and holds the version's gate while it changes the folder.
    [Fact]
    public async Task UndoingAFinishedImportKeepsReusedGameFilesRefusesWhileTheGameRunsAndHoldsTheVersionGate()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        var gate = new FakeGate();
        var engine = EngineBKit.Engine(_rig, new IImportPlanContributor[] { new GameFileContributor(Roots(), _mojang.Http) }, versionGate: gate);
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);
        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded);
        var options = Path.Combine(plan.TargetGameDir, "options.txt");
        Assert.True(File.Exists(options));

        _rig.RunningDirs.Add(plan.TargetGameDir);
        var refused = await engine.RollbackAsync(plan.ImportId, _rig.Settings);
        Assert.Equal(ImportState.Planned, refused.State);
        Assert.StartsWith("Close Minecraft first", refused.Error);
        Assert.True(File.Exists(options));                                              // nothing touched
        _rig.RunningDirs.Clear();

        var held = await gate.AcquireAsync(Mojang.Version, CancellationToken.None);   // someone is installing 1.21.1
        var undo = engine.RollbackAsync(plan.ImportId, _rig.Settings);
        await Task.Delay(300);
        Assert.False(undo.IsCompleted);                                                 // the undo waits for the gate
        Assert.True(File.Exists(options));
        await held.DisposeAsync();
        var undone = await undo;

        Assert.Null(undone.Error);
        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.False(File.Exists(options));                                             // the import's own files are undone
        foreach (var rel in _mojang.Required)
            Assert.True(File.Exists(Dest(rel)), rel);                                   // the reused game files stay
        // 3: the refused undo takes the gate too, and only then looks for a running game (review round 3, F1).
        Assert.Equal(3, gate.Acquired);
        Assert.Equal(3, gate.Released);
    }

    [Fact]
    public async Task ACancelledApplyStillRemovesTheGameFilesItCopied()
    {
        MinimalSource();
        _mojang.WriteOfficial(Official);
        var (plan, result) = await Run();
        Assert.True(result.Succeeded);
        // A crash-recovery style rollback of the same journal (not a finished-import undo) removes everything.
        var journal = ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, plan.ImportId))!;
        Assert.Contains(journal.Entries, e => e.GameFile && e.Path == Dest("assets/indexes/17.json"));
        ImportUndo.Run(journal, _rig.Settings, _ => { }, _rig.Root);
        Assert.False(File.Exists(Dest("assets/indexes/17.json")));
    }
}
