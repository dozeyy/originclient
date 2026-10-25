using System.Net.Http;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Versions;
using Xunit;

namespace OriginLauncher.Tests;

// Phase 3 U3.3: Play on a missing or partly installed version installs it first. The real provisioning is
// CmlLib + the network, so these tests stand in for it with a delegate that writes the same pieces
// InstalledVersions looks for (client, loader, Origin jar) into temp folders, and run the REAL InstallSession,
// InstallJournal, InstalledVersions and AccountLaunchBook around it. Never touches %LocalAppData%.
public class PlayInstallTests : IDisposable
{
    private const string Fabric = "1.21.1";
    private const string Other = "1.20.1";
    private const string Forge = "1.8.9";
    private const string ForgeId = "1.8.9-forge1.8.9-11.15.1.2318-1.8.9";
    private const string ForgeLib = "net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9.jar";

    private readonly string _tmp = Directory.CreateTempSubdirectory("play-install").FullName;
    private readonly InstalledVersions _installed;
    private readonly InstallJournal _journal;

    public PlayInstallTests()
    {
        _journal = new InstallJournal(Root);
        _installed = new InstalledVersions(
            [Fabric, Other, Forge],
            Root,
            v => Path.Combine(Root(v), "mods"),
            _ => null, // no bundled Origin jar to require: the pieces are client + loader
            v => v == Forge ? new LegacyLoaderLayout(ForgeId, ForgeLib.Replace('/', Path.DirectorySeparatorChar)) : null,
            installOpen: _journal.IsOpen);
    }

    public void Dispose()
    {
        try { Directory.Delete(_tmp, true); } catch { /* best effort */ }
    }

    private string Root(string version) => Path.Combine(_tmp, "instances", version);

    private static void Put(string path, int bytes = 16)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, new byte[bytes]);
    }

    private void WriteClient(string v)
    {
        Put(Path.Combine(Root(v), "versions", v, v + ".json"));
        Put(Path.Combine(Root(v), "versions", v, v + ".jar"), 100);
    }

    private void WriteLoader(string v)
    {
        if (v == Forge)
        {
            Put(Path.Combine(Root(v), "versions", ForgeId, ForgeId + ".json"));
            Put(Path.Combine(Root(v), "libraries", ForgeLib.Replace('/', Path.DirectorySeparatorChar)), 50);
        }
        else
        {
            var name = $"fabric-loader-0.17.3-{v}";
            Put(Path.Combine(Root(v), "versions", name, name + ".json"));
        }
    }

    private void WriteEverything(string v)
    {
        WriteClient(v);
        WriteLoader(v);
    }

    private Task<T> Play<T>(string v, Func<Task<T>> provision, IProgress<string>? progress = null) =>
        InstallSession.RunAsync(_installed, _journal, v, progress, provision);

    private sealed class Lines : IProgress<string>
    {
        public List<string> Seen { get; } = [];
        public void Report(string value) => Seen.Add(value);
    }

    [Theory]
    [InlineData(Fabric)]
    [InlineData(Forge)]
    public async Task PlayOnAMissingVersion_InstallsThenFinishesInstalled(string version)
    {
        Assert.Equal(InstallState.NotInstalled, _installed.Check(version).State);
        var lines = new Lines();
        var statesDuringInstall = new List<InstallState>();

        var process = await Play(version, async () =>
        {
            Assert.True(_journal.IsOpen(version)); // open before the first byte is written
            WriteClient(version);
            statesDuringInstall.Add(_installed.Check(version).State);
            await Task.Yield();
            WriteLoader(version);
            // Every file is on disk and the install is still not done: it must not read as installed yet.
            statesDuringInstall.Add(_installed.Check(version).State);
            return "process";
        }, lines);

        Assert.Equal("process", process);
        Assert.All(statesDuringInstall, s => Assert.Equal(InstallState.Partial, s));
        Assert.False(_journal.IsOpen(version));
        Assert.Equal(InstallState.Installed, _installed.Check(version).State);
        Assert.Equal("Installing", lines.Seen[0]);
    }

    [Fact]
    public async Task PlayOnAPartVersion_SaysItIsFinishingItAndKeepsWhatIsThere()
    {
        WriteClient(Fabric); // loader missing
        Assert.Equal(InstallState.Partial, _installed.Check(Fabric).State);
        var clientJar = Path.Combine(Root(Fabric), "versions", Fabric, Fabric + ".jar");
        var lines = new Lines();

        await Play(Fabric, () => { WriteLoader(Fabric); return Task.FromResult(1); }, lines);

        Assert.Equal("Finishing", lines.Seen[0]);
        Assert.True(File.Exists(clientJar));
        Assert.Equal(InstallState.Installed, _installed.Check(Fabric).State);
    }

    [Fact]
    public async Task PlayOnAnInstalledVersion_NeverOpensTheJournal()
    {
        WriteEverything(Fabric);
        var openedDuring = true;
        var lines = new Lines();

        await Play(Fabric, () => { openedDuring = _journal.IsOpen(Fabric); return Task.FromResult(0); }, lines);

        Assert.False(openedDuring);
        Assert.Empty(lines.Seen);
        Assert.Equal(InstallState.Installed, _installed.Check(Fabric).State);
    }

    [Fact]
    public async Task CancelMidInstall_LeavesTheFilesAndNeverReadsAsInstalled()
    {
        using var cts = new CancellationTokenSource();
        var written = new TaskCompletionSource();

        var run = Play(Fabric, async () =>
        {
            WriteClient(Fabric);
            WriteLoader(Fabric); // every piece is down, the install itself is not finished
            written.SetResult();
            await Task.Delay(Timeout.Infinite, cts.Token);
            return 0;
        });
        await written.Task;
        cts.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => run);

        Assert.True(_journal.IsOpen(Fabric));
        var info = _installed.Check(Fabric);
        Assert.Equal(InstallState.Partial, info.State);
        Assert.True(info.Missing.HasFlag(InstallPieces.Unfinished));
        // Files intact: nothing was deleted, so the next Play resumes instead of starting over.
        Assert.True(File.Exists(Path.Combine(Root(Fabric), "versions", Fabric, Fabric + ".jar")));
    }

    [Fact]
    public async Task FailureThenRetry_NeverMarksTheHalfInstallInstalled_AndTheRetryFinishesIt()
    {
        // First press: the client downloads, then the network drops.
        var first = Play(Fabric, () =>
        {
            WriteClient(Fabric);
            throw new HttpRequestException("connection reset");
#pragma warning disable CS0162
            return Task.FromResult(0);
#pragma warning restore CS0162
        });
        await Assert.ThrowsAsync<HttpRequestException>(() => first);

        var afterFailure = _installed.Check(Fabric);
        Assert.NotEqual(InstallState.Installed, afterFailure.State);
        Assert.Equal(InstallState.Partial, afterFailure.State);
        Assert.True(_journal.IsOpen(Fabric));
        Assert.True(File.Exists(Path.Combine(Root(Fabric), "versions", Fabric, Fabric + ".json"))); // left intact

        // Retry: it knows it is finishing an earlier install, resumes, and only then reads as installed.
        var lines = new Lines();
        var statesOnRetry = new List<InstallState>();
        await Play(Fabric, () =>
        {
            statesOnRetry.Add(_installed.Check(Fabric).State);
            WriteLoader(Fabric);
            statesOnRetry.Add(_installed.Check(Fabric).State);
            return Task.FromResult(0);
        }, lines);

        Assert.All(statesOnRetry, s => Assert.Equal(InstallState.Partial, s));
        Assert.Equal("Finishing", lines.Seen[0]);
        Assert.False(_journal.IsOpen(Fabric));
        Assert.Equal(InstallState.Installed, _installed.Check(Fabric).State);
    }

    [Fact]
    public async Task AnUnfinishedInstallWithEveryFilePresent_StillReadsPartialUntilPlayFinishesIt()
    {
        WriteEverything(Fabric);
        _journal.Open(Fabric); // e.g. the launcher was killed after the last file landed
        Assert.Equal(InstallState.Partial, _installed.Check(Fabric).State);

        await Play(Fabric, () => Task.FromResult(0));

        Assert.Equal(InstallState.Installed, _installed.Check(Fabric).State);
    }

    [Fact]
    public async Task NewestPlayForTheSameAccountCancelsTheOlderInstall_OtherAccountsAreUntouched()
    {
        var book = new AccountLaunchBook();
        var steveOld = book.BeginPrepare("steve", Fabric)!;
        var alex = book.BeginPrepare("alex", Other)!;

        Task<int> Install(LaunchTicket t) => Play(t.Version, async () =>
        {
            WriteClient(t.Version);
            await Task.Delay(Timeout.Infinite, t.Token);
            return 0;
        });
        var steveOldRun = Install(steveOld);
        var alexRun = Install(alex);

        // Steve presses Play again, on another version: the newest action wins for Steve only.
        var steveNew = book.BeginPrepare("steve", Other)!;
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => steveOldRun);

        Assert.True(steveOld.Token.IsCancellationRequested);
        Assert.False(alex.Token.IsCancellationRequested);
        Assert.False(steveNew.Token.IsCancellationRequested);
        Assert.False(alexRun.IsCompleted); // Alex's install is still running
        Assert.False(book.IsCurrent(steveOld));
        Assert.True(book.IsCurrent(steveNew));
        Assert.True(book.IsCurrent(alex));
        // The cancelled install left its files and an open journal, never an "installed" version.
        Assert.Equal(InstallState.Partial, _installed.Check(Fabric).State);

        book.CancelPreparation("alex");
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => alexRun);
    }

    // ---- PlayFailure: what the launch screen says ----

    [Fact]
    public void OfflineFailure_NamesTheCauseAndPromisesNothingWasLost()
    {
        var f = PlayFailure.From(new OfflineInstallException("1.8.9", new HttpRequestException("x")), "1.8.9");
        Assert.Contains("1.8.9", f.Title);
        Assert.Contains("internet", f.Message);
        Assert.EndsWith(PlayFailure.RetryLine, f.Message);
    }

    [Fact]
    public void DiskFullFailure_SaysSo_WhateverTheWrapper()
    {
        var disk = new IOException("There is not enough space on the disk.", unchecked((int)0x80070070));
        var f = PlayFailure.From(new InvalidOperationException("install failed", disk), "1.21.1");
        Assert.Contains("disk is full", f.Message);
    }

    [Fact]
    public void UnknownFailure_NeverShowsRawExceptionText()
    {
        var f = PlayFailure.From(new InvalidDataException("0xDEADBEEF stack at Foo.Bar()"), "1.21.1");
        Assert.DoesNotContain("0xDEADBEEF", f.Message);
        Assert.DoesNotContain("Foo.Bar", f.Message);
        Assert.EndsWith(PlayFailure.RetryLine, f.Message);
    }

    [Fact]
    public void ADownloadTimeout_IsAFailureWithRetry_NotACancel()
    {
        // HttpClient timeouts throw TaskCanceledException while nobody cancelled the launch token.
        using var cts = new CancellationTokenSource();
        var timeout = new TaskCanceledException("timed out", new TimeoutException());
        Assert.False(PlayFailure.IsUserCancel(timeout, cts.Token));
        var f = PlayFailure.From(timeout, "1.21.1");
        Assert.Contains("took too long", f.Message);
        Assert.EndsWith(PlayFailure.RetryLine, f.Message);
    }

    [Fact]
    public void ARealUserCancel_StillReadsCancelled()
    {
        using var cts = new CancellationTokenSource();
        cts.Cancel();
        Assert.True(PlayFailure.IsUserCancel(new OperationCanceledException(cts.Token), cts.Token));
        Assert.True(PlayFailure.IsUserCancel(new TaskCanceledException("x", null, cts.Token), cts.Token));
        Assert.False(PlayFailure.IsUserCancel(new InvalidOperationException("x"), cts.Token));
    }

    [Fact]
    public void ServerRefusal_NamesTheStatus()
    {
        var f = PlayFailure.From(new HttpRequestException("x", null, System.Net.HttpStatusCode.Forbidden), "1.21.1");
        Assert.Contains("403", f.Message);
    }

    // ---- GameLogMarks: the harness reads Forge like Fabric ----

    [Fact]
    public void FabricLines_AreMarked()
    {
        Assert.Equal(["game-fabric-start"], GameLogMarks.Of("[main/INFO] (FabricLoader) Loading Minecraft 1.21.1 with Fabric Loader 0.17.3"));
        Assert.Equal(["game-mods-listed"], GameLogMarks.Of("[main/INFO] (FabricLoader) Loading 9 mods:"));
    }

    [Fact]
    public void ForgeLines_AreMarked_SoVersion189HasGameMarks()
    {
        Assert.Equal(["game-forge-start"],
            GameLogMarks.Of("[19:04:11] [main/INFO] [FML]: Forge Mod Loader version 11.15.1.2318 for Minecraft 1.8.9 loading"));
        Assert.Equal(["game-mods-listed"],
            GameLogMarks.Of("[19:04:14] [Client thread/INFO] [FML]: Forge Mod Loader has identified 5 mods to load"));
    }

    [Fact]
    public void BootAndWindowLines_AreMarked_AndOtherLinesAreNot()
    {
        Assert.Equal(["game-title"], GameLogMarks.Of("[Render thread/INFO]: [OriginBoot] mark title 1234"));
        Assert.Equal(["game-window"], GameLogMarks.Of("[Sound/INFO]: Backend library: LWJGL version 2.9.4"));
        Assert.Empty(GameLogMarks.Of("[main/INFO]: Setting user: OriginTester"));
        Assert.Empty(GameLogMarks.Of(null));
        Assert.Empty(GameLogMarks.Of(""));
    }

    // ---- Security review fixes (U3.3) ----

    [Fact]
    public void TheMarkerLivesOutsideEveryVersionFolder_SoMigrationCanNeverTreatItAsGameData()
    {
        var marker = OriginPaths.InstallState(Fabric);
        Assert.False(marker.StartsWith(OriginPaths.Instances + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase));
        Assert.False(marker.StartsWith(OriginPaths.VersionRoot(Fabric), StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void AnOpenMarker_SurvivesTheOldLayoutMigrationThatRunsOnEveryLauncherStart()
    {
        // The same shape as production: the marker folder sits beside instances/, not inside a version folder.
        var journal = new InstallJournal(v => Path.Combine(_tmp, "state", "installing", v));
        var root = Root(Fabric);
        Put(Path.Combine(root, "versions", Fabric, Fabric + ".json"));
        Put(Path.Combine(root, "options.txt")); // old-layout account data: this one IS moved
        journal.Open(Fabric);

        InstanceMigration.MigrateVersion(root, "acct");

        Assert.True(journal.IsOpen(Fabric));
        Assert.False(File.Exists(Path.Combine(root, "options.txt")));
    }

    [Theory]
    [InlineData(false, 1)] // missing: opens, drops the stale stamp
    [InlineData(true, 0)]  // installed: nothing opens, the stamp stays
    public async Task AnInstallThatOpens_DropsTheVerifyStamp_SoTheNextVerifyIsFull(bool complete, int drops)
    {
        if (complete) WriteEverything(Fabric);
        var dropped = new List<string>();
        await InstallSession.RunAsync(_installed, _journal, Fabric, null, () => Task.FromResult(0), dropped.Add);
        Assert.Equal(drops, dropped.Count);
    }

    [Fact]
    public async Task APartlyInstalledVersion_AlsoDropsTheVerifyStamp()
    {
        WriteClient(Fabric); // loader missing: Partial
        var dropped = new List<string>();
        await InstallSession.RunAsync(_installed, _journal, Fabric, null, () => Task.FromResult(0), dropped.Add);
        Assert.Equal([Fabric], dropped);
    }

    [Fact]
    public void AFailedJournalWriteOrRemoval_IsLoggedOnce_AndNeverThrows()
    {
        var lines = new List<string>();
        var blocker = Path.Combine(_tmp, "blocker");
        File.WriteAllText(blocker, "a file where a folder is needed");
        var journal = new InstallJournal(v => Path.Combine(blocker, v), lines.Add);
        journal.Open(Fabric); // cannot create a folder under a file
        Assert.Single(lines);
        Assert.Contains("could not write", lines[0]);
        Assert.Contains(Fabric, lines[0]);

        var stuck = new InstallJournal(v => Path.Combine(_tmp, "stuck", v), lines.Add);
        Directory.CreateDirectory(Path.Combine(_tmp, "stuck", Fabric, InstallJournal.MarkerFileName)); // a folder, so Delete throws
        stuck.Close(Fabric);
        Assert.Equal(2, lines.Count);
        Assert.Contains("could not remove", lines[1]);
    }
}
