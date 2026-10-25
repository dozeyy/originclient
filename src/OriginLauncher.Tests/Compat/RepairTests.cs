using System.Security.Cryptography;
using System.Text;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Compat;
using Xunit;
using static OriginLauncher.Tests.Compat.CompatKit;

namespace OriginLauncher.Tests.Compat;

// RepairPlanner (pure) and RepairExecutor / CrashRepair (the only I/O, run on temp folders, never the real instance).
public sealed class RepairTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "origin-repair-" + Guid.NewGuid().ToString("N"));

    public RepairTests() => Directory.CreateDirectory(_dir);

    public void Dispose()
    {
        try { Directory.Delete(_dir, true); } catch (IOException) { /* temp folder; the OS cleans up */ }
    }

    private RepairContext Context(Func<ModFile, bool>? isProtected = null, IDependencyCatalog? catalog = null) =>
        new(Fabric121, _dir, new[] { _dir }, isProtected, catalog);

    private static byte[] ModJson(string id, string version) =>
        Encoding.UTF8.GetBytes("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\"}");

    // A real jar on disk plus the entry the scanner would give for it.
    private ModFile OnDisk(string id, string version = "1.0.0", ModRole role = ModRole.Profile)
    {
        var info = Info(id, version);
        var path = Path.Combine(_dir, info.FileName);
        System.IO.File.WriteAllBytes(path, Zip(("fabric.mod.json", ModJson(id, version))));
        return new ModFile(path, info.FileName, true, role, info);
    }

    private sealed class Catalog : IDependencyCatalog
    {
        public readonly Dictionary<string, DependencyOffer> Offers = new();
        public DependencyOffer? Find(string id, VersionPredicate range, CompatPlatform platform) =>
            Offers.TryGetValue(id, out var o) && range.IsSatisfiedBy(o.Version) ? o : null;
    }

    private static DependencyOffer Offer(string id, string version = "1.0.0", string? sha = null) =>
        new(id, version, $"{id}-{version}.jar", Info(id, version), "source:" + id, sha);

    private sealed class FakeFetcher : IDependencyFetcher
    {
        public Func<DependencyOffer, byte[]?> Make = o => Zip(("fabric.mod.json", ModJson(o.Id, o.Version)));
        public int Calls;
        public bool Fetch(DependencyOffer offer, string destinationPath, out string? error)
        {
            Calls++;
            var data = Make(offer);
            if (data == null) { error = "offline"; return false; }
            System.IO.File.WriteAllBytes(destinationPath, data);
            error = null;
            return true;
        }
    }

    private static RepairPlan PlanOf(params RepairStep[] steps) => new(steps, Array.Empty<UnfixableFinding>());

    // ---- planner ----

    [Fact]
    public void ACleanSetNeedsNoPlan()
    {
        var plan = RepairPlanner.Plan(Check(Mod("a"), Mod("b")), Context());
        Assert.True(plan.IsEmpty);
        Assert.Empty(plan.Unfixable);
    }

    [Fact]
    public void WarningsAndInfoAreNeverRepaired()
    {
        var report = Check(File(Info("hud", env: ModEnvironment.Server)), File(Info("addon", recommends: new[] { ("friend", "*") })));
        Assert.NotEmpty(report.Findings);
        Assert.True(RepairPlanner.Plan(report, Context()).IsEmpty);
    }

    [Fact]
    public void AMissingDependencyTurnsTheModOffWhenNothingCanSupplyIt()
    {
        var addon = Mod("addon", needs: new[] { ("lib", "*") });
        var plan = RepairPlanner.Plan(Check(addon), Context());
        var step = Assert.IsType<DisableModStep>(Assert.Single(plan.Steps));
        Assert.Same(addon, step.Mod);
        Assert.EndsWith(".jar.disabled", step.To);
    }

    [Fact]
    public void AMissingDependencyIsAddedWhenTheCatalogHasIt()
    {
        var catalog = new Catalog();
        catalog.Offers["lib"] = Offer("lib", "2.0.0");
        var addon = Mod("addon", needs: new[] { ("lib", ">=1") });
        var plan = RepairPlanner.Plan(Check(addon), Context(catalog: catalog));
        var step = Assert.IsType<AddModStep>(Assert.Single(plan.Steps));
        Assert.Equal("lib", step.Offer.Id);
        Assert.Equal(Path.Combine(_dir, "lib-2.0.0.jar"), step.DestinationPath);
        Assert.Empty(plan.Unfixable);
    }

    [Fact]
    public void AnOfferOfTheWrongVersionIsNotUsed()
    {
        var catalog = new Catalog();
        catalog.Offers["lib"] = Offer("lib", "1.0.0");
        var addon = Mod("addon", needs: new[] { ("lib", ">=2") });
        Assert.IsType<DisableModStep>(Assert.Single(RepairPlanner.Plan(Check(addon), Context(catalog: catalog)).Steps));
    }

    [Fact]
    public void ADependencyOnAnOriginModIsNeverAdded()
    {
        var catalog = new Catalog();
        catalog.Offers["sodium"] = Offer("sodium", "0.8.0");
        var addon = Mod("addon", needs: new[] { ("sodium", ">=0.8") });
        var plan = RepairPlanner.Plan(Check(Mod("sodium", "0.6.13", ModRole.Managed), addon), Context(catalog: catalog));
        Assert.IsType<DisableModStep>(Assert.Single(plan.Steps));
    }

    [Fact]
    public void ARootCauseIsFixedAndItsKnockOnsGoWithIt()
    {
        var a = Mod("a", needs: new[] { ("absent", "*") });
        var b = Mod("b", needs: new[] { ("a", "*") });
        var c = Mod("c", needs: new[] { ("b", "*") });
        var report = Check(a, b, c);
        var plan = RepairPlanner.Plan(report, Context());
        Assert.Contains(plan.Steps.OfType<DisableModStep>(), s => ReferenceEquals(s.Mod, a));
        Assert.Empty(plan.Unfixable);
    }

    [Fact]
    public void OriginsOwnBreaksTurnOffThePlayersModNotOrigins()
    {
        var origin = Mod("sodium", "0.6.13", ModRole.Managed, breaks: new[] { ("iris", "<1.8.7") });
        var iris = Mod("iris", "1.8.5");
        var plan = RepairPlanner.Plan(Check(origin, iris), Context());
        Assert.Same(iris, Assert.IsType<DisableModStep>(Assert.Single(plan.Steps)).Mod);
    }

    [Fact]
    public void AProtectedModIsReportedAsUnfixable()
    {
        var addon = Mod("addon", needs: new[] { ("lib", "*") });
        var plan = RepairPlanner.Plan(Check(addon), Context(isProtected: m => m.Id == "addon"));
        Assert.Empty(plan.Steps);
        var u = Assert.Single(plan.Unfixable);
        Assert.Same(addon, u.Finding.Subject);
        Assert.Contains("protected", u.Reason);
    }

    [Fact]
    public void ADuplicateKeepsTheNewerAndTurnsTheOlderOff()
    {
        var old = Mod("lib", "1.0.0");
        var newer = Mod("lib", "2.0.0");
        var plan = RepairPlanner.Plan(Check(old, newer), Context());
        Assert.Same(old, Assert.IsType<DisableModStep>(Assert.Single(plan.Steps)).Mod);
    }

    [Fact]
    public void ACopyOfAnOriginModIsTurnedOffNotOrigins()
    {
        var mine = Mod("sodium", "0.5.0");
        var plan = RepairPlanner.Plan(Check(Mod("sodium", "0.6.13", ModRole.Managed), mine), Context());
        Assert.Same(mine, Assert.IsType<DisableModStep>(Assert.Single(plan.Steps)).Mod);
    }

    [Fact]
    public void PlanningDoesNotDependOnInputOrder()
    {
        var mods = Enumerable.Range(0, 20)
            .Select(i => Mod("m" + i, needs: i % 3 == 0 ? new[] { ("absent", "*") } : null)).ToArray();
        static IEnumerable<string> Names(RepairPlan p) =>
            p.Steps.OfType<DisableModStep>().Select(s => s.Mod.FileName).OrderBy(x => x, StringComparer.Ordinal);
        var a = RepairPlanner.Plan(Check(mods), Context());
        var b = RepairPlanner.Plan(Check(mods.Reverse().ToArray()), Context());
        Assert.Equal(Names(a), Names(b));
    }

    // The planner's contract: apply the plan, check again, nothing blocking is left except what it listed as unfixable
    // (or what only Origin's own mods cause).
    [Fact]
    public void ApplyingAPlanLeavesNothingBlockingForAnyMixOfProblems()
    {
        var rng = new Random(20261025);
        for (var round = 0; round < 200; round++)
        {
            var ids = Enumerable.Range(0, 12).Select(i => "m" + i).ToArray();
            var mods = new List<ModFile>();
            foreach (var id in ids)
            {
                if (rng.Next(5) == 0) continue; // some ids simply absent
                var needs = new List<(string, string)>();
                var breaks = new List<(string, string)>();
                for (var k = 0; k < rng.Next(3); k++) needs.Add((ids[rng.Next(ids.Length)], rng.Next(4) == 0 ? ">=2" : "*"));
                if (rng.Next(6) == 0) breaks.Add((ids[rng.Next(ids.Length)], "*"));
                var role = rng.Next(8) == 0 ? ModRole.Managed : ModRole.Profile;
                mods.Add(Mod(id, rng.Next(3) == 0 ? "2.0.0" : "1.0.0", role, rng.Next(10) != 0, needs.ToArray(), breaks.ToArray(),
                    rng.Next(15) == 0 ? "1.20.x" : null));
                if (rng.Next(12) == 0) mods.Add(Mod(id, "0.9.0")); // a duplicate
            }
            var report = CompatEngine.Check(mods, Fabric121);
            var plan = RepairPlanner.Plan(report, Context());

            var turnedOff = plan.Steps.OfType<DisableModStep>().Select(s => s.Mod).ToHashSet(ReferenceEqualityComparer.Instance);
            Assert.DoesNotContain(turnedOff, m => ((ModFile)m).Role == ModRole.Managed);
            var after = mods.Select(m => turnedOff.Contains(m)
                ? m with { Path = m.Path + ".disabled", FileName = m.FileName + ".disabled", Enabled = false } : m).ToList();
            var again = CompatEngine.Check(after, Fabric121);

            var listed = plan.Unfixable.Select(u => u.Finding.Subject.Path).ToHashSet();
            foreach (var f in again.Findings.Where(f => f.Severity == Severity.Blocking && f.Excludes && f.Subject.Enabled))
            {
                var excused = listed.Contains(f.Subject.Path)
                    || f.Related?.Role == ModRole.Managed
                    || f.CausedBy.Any(c => listed.Contains(c.Path) || c.Role == ModRole.Managed);
                Assert.True(excused, $"round {round}: {f.Subject.FileName} still blocking after the plan: {f.Summary}");
            }
        }
    }

    // ---- executor ----

    [Fact]
    public void DisableRenamesAndNeverDeletes()
    {
        var mod = OnDisk("addon");
        var bytes = System.IO.File.ReadAllBytes(mod.Path);
        var result = RepairExecutor.Apply(PlanOf(new DisableModStep(mod, "why", Certainty.Confirmed)), Context());
        Assert.True(result.AllOk);
        Assert.False(System.IO.File.Exists(mod.Path));
        Assert.Equal(bytes, System.IO.File.ReadAllBytes(mod.Path + ".disabled")); // same content, new name
        Assert.Equal(mod.Path, result.Steps[0].From);
    }

    [Fact]
    public void DisableRefusesToOverwriteAnExistingDisabledFile()
    {
        var mod = OnDisk("addon");
        System.IO.File.WriteAllText(mod.Path + ".disabled", "precious");
        var result = RepairExecutor.Apply(PlanOf(new DisableModStep(mod, "why", Certainty.Confirmed)), Context());
        Assert.False(result.AllOk);
        Assert.Equal("precious", System.IO.File.ReadAllText(mod.Path + ".disabled"));
        Assert.True(System.IO.File.Exists(mod.Path));
    }

    [Fact]
    public void DisableRefusesManagedAndProtectedMods()
    {
        var managed = OnDisk("origin-thing", role: ModRole.Managed);
        var prot = OnDisk("keepme");
        var plan = PlanOf(new DisableModStep(managed, "why", Certainty.Confirmed), new DisableModStep(prot, "why", Certainty.Confirmed));
        var result = RepairExecutor.Apply(plan, Context(isProtected: m => m.Id == "keepme"));
        Assert.Empty(result.Done);
        Assert.True(System.IO.File.Exists(managed.Path));
        Assert.True(System.IO.File.Exists(prot.Path));
    }

    [Fact]
    public void AStepOutsideTheAllowedFoldersIsRefused()
    {
        var other = Path.Combine(_dir, "other");
        Directory.CreateDirectory(other);
        var path = Path.Combine(other, "x.jar");
        System.IO.File.WriteAllText(path, "x");
        var mod = new ModFile(path, "x.jar", true, ModRole.Profile, Info("x"));
        var result = RepairExecutor.Apply(PlanOf(new DisableModStep(mod, "why", Certainty.Confirmed)), Context());
        Assert.False(result.AllOk);
        Assert.True(System.IO.File.Exists(path));
    }

    [Fact]
    public void OneStuckStepDoesNotStopTheOthers()
    {
        var gone = new ModFile(Path.Combine(_dir, "gone.jar"), "gone.jar", true, ModRole.Profile, Info("gone"));
        var there = OnDisk("there");
        var result = RepairExecutor.Apply(
            PlanOf(new DisableModStep(gone, "why", Certainty.Confirmed), new DisableModStep(there, "why", Certainty.Confirmed)), Context());
        Assert.False(result.Steps[0].Ok);
        Assert.True(result.Steps[1].Ok);
    }

    [Fact]
    public void UndoPutsTheNamesBack()
    {
        var mod = OnDisk("addon");
        var ctx = Context();
        var result = RepairExecutor.Apply(PlanOf(new DisableModStep(mod, "why", Certainty.Confirmed)), ctx);
        var undone = RepairExecutor.Undo(result, ctx);
        Assert.True(undone.AllOk);
        Assert.True(System.IO.File.Exists(mod.Path));
        Assert.False(System.IO.File.Exists(mod.Path + ".disabled"));
    }

    [Fact]
    public void AddFetchesChecksAndMovesIntoPlace()
    {
        var offer = Offer("lib", "2.0.0");
        var step = new AddModStep(offer, Path.Combine(_dir, offer.FileName), "needed");
        var result = RepairExecutor.Apply(PlanOf(step), Context(), new FakeFetcher());
        Assert.True(result.AllOk, result.Steps[0].Error);
        Assert.True(System.IO.File.Exists(step.DestinationPath));
        Assert.False(System.IO.File.Exists(step.DestinationPath + ".part"));
        Assert.Equal("lib", ModFolderScanner.ReadInfo(step.DestinationPath)!.Id);
    }

    [Fact]
    public void AddWithoutAFetcherDoesNothing()
    {
        var offer = Offer("lib");
        var result = RepairExecutor.Apply(PlanOf(new AddModStep(offer, Path.Combine(_dir, offer.FileName), "n")), Context());
        Assert.False(result.AllOk);
        Assert.Empty(Directory.GetFiles(_dir));
    }

    [Fact]
    public void AddRejectsAWrongHash()
    {
        var offer = Offer("lib", sha: new string('0', 64));
        var step = new AddModStep(offer, Path.Combine(_dir, offer.FileName), "n");
        var result = RepairExecutor.Apply(PlanOf(step), Context(), new FakeFetcher());
        Assert.False(result.AllOk);
        Assert.Empty(Directory.GetFiles(_dir)); // not even a .part left behind
    }

    [Fact]
    public void AddAcceptsTheRightHash()
    {
        var fetcher = new FakeFetcher();
        var sha = Convert.ToHexString(SHA256.HashData(fetcher.Make(Offer("lib"))!));
        var offer = Offer("lib", sha: sha);
        var step = new AddModStep(offer, Path.Combine(_dir, offer.FileName), "n");
        Assert.True(RepairExecutor.Apply(PlanOf(step), Context(), fetcher).AllOk);
    }

    [Fact]
    public void AddRejectsAFileThatIsNotTheModOffered()
    {
        var fetcher = new FakeFetcher { Make = _ => Zip(("fabric.mod.json", ModJson("imposter", "1"))) };
        var offer = Offer("lib");
        var step = new AddModStep(offer, Path.Combine(_dir, offer.FileName), "n");
        var result = RepairExecutor.Apply(PlanOf(step), Context(), fetcher);
        Assert.False(result.AllOk);
        Assert.Empty(Directory.GetFiles(_dir));
    }

    [Fact]
    public void AddRejectsNotAJarAndAFailedFetch()
    {
        var offer = Offer("lib");
        var plan = PlanOf(new AddModStep(offer, Path.Combine(_dir, offer.FileName), "n"));
        Assert.False(RepairExecutor.Apply(plan, Context(), new FakeFetcher { Make = _ => Encoding.UTF8.GetBytes("<html>") }).AllOk);
        Assert.False(RepairExecutor.Apply(plan, Context(), new FakeFetcher { Make = _ => null }).AllOk);
        Assert.Empty(Directory.GetFiles(_dir));
    }

    [Fact]
    public void AddNeverOverwritesAnExistingFile()
    {
        var offer = Offer("lib");
        var dest = Path.Combine(_dir, offer.FileName);
        System.IO.File.WriteAllText(dest, "existing");
        var fetcher = new FakeFetcher();
        var result = RepairExecutor.Apply(PlanOf(new AddModStep(offer, dest, "n")), Context(), fetcher);
        Assert.False(result.AllOk);
        Assert.Equal("existing", System.IO.File.ReadAllText(dest));
        Assert.Equal(0, fetcher.Calls);
    }

    [Fact]
    public void AddRefusesAPathOutsideTheAllowedFolders()
    {
        var offer = Offer("lib");
        var outside = Path.Combine(_dir, "sub", offer.FileName);
        var fetcher = new FakeFetcher();
        var result = RepairExecutor.Apply(PlanOf(new AddModStep(offer, outside, "n")), Context(), fetcher);
        Assert.False(result.AllOk);
        Assert.Equal(0, fetcher.Calls);
    }

    [Fact]
    public void UndoOfAnAddTurnsTheFileOffInsteadOfDeletingIt()
    {
        var offer = Offer("lib");
        var step = new AddModStep(offer, Path.Combine(_dir, offer.FileName), "n");
        var ctx = Context();
        var result = RepairExecutor.Apply(PlanOf(step), ctx, new FakeFetcher());
        Assert.True(RepairExecutor.Undo(result, ctx).AllOk);
        Assert.False(System.IO.File.Exists(step.DestinationPath));
        Assert.True(System.IO.File.Exists(step.DestinationPath + ".disabled"));
    }

    [Fact]
    public void ScanCheckPlanApplyCheckEndsCleanAndDeletesNothing()
    {
        // End to end on real files: scan -> check -> plan -> apply -> scan -> check.
        WriteJar("lib-user", "{\"schemaVersion\":1,\"id\":\"lib-user\",\"version\":\"1.0.0\",\"depends\":{\"lib\":\"*\"}}");
        WriteJar("fine", "{\"schemaVersion\":1,\"id\":\"fine\",\"version\":\"1.0.0\"}");
        WriteJar("also-broken", "{\"schemaVersion\":1,\"id\":\"also-broken\",\"version\":\"1.0.0\",\"depends\":{\"nothing\":\"*\"}}");
        var catalog = new Catalog();
        catalog.Offers["lib"] = Offer("lib");

        var ctx = Context(catalog: catalog);
        var first = CompatEngine.Check(ModFolderScanner.Scan(_dir, ModRole.Profile), Fabric121);
        Assert.True(first.HasBlocking);
        var plan = RepairPlanner.Plan(first, ctx);
        var result = RepairExecutor.Apply(plan, ctx, new FakeFetcher());
        Assert.True(result.AllOk);
        var second = CompatEngine.Check(ModFolderScanner.Scan(_dir, ModRole.Profile), Fabric121);
        Assert.False(second.HasBlocking, string.Join("; ", second.Findings.Select(f => f.Summary)));
        Assert.True(System.IO.File.Exists(Path.Combine(_dir, "also-broken.jar.disabled")));
        Assert.True(System.IO.File.Exists(Path.Combine(_dir, "lib-user.jar")));
        Assert.True(System.IO.File.Exists(Path.Combine(_dir, "lib-1.0.0.jar")));
        Assert.True(System.IO.File.Exists(Path.Combine(_dir, "fine.jar")));
    }

    private void WriteJar(string name, string json) =>
        System.IO.File.WriteAllBytes(Path.Combine(_dir, name + ".jar"), Zip(("fabric.mod.json", Encoding.UTF8.GetBytes(json))));

    // ---- crash repair ----

    [Fact]
    public void CrashRepairTurnsOffTheBlamedJarInTheFolderItIsGiven()
    {
        var mod = OnDisk("culprit");
        var r = CrashRepair.DisableBlamed(_dir, mod.FileName, _ => false);
        Assert.Equal(CrashRepairOutcome.Disabled, r.Outcome);
        Assert.True(System.IO.File.Exists(mod.Path + ".disabled"));
        Assert.False(System.IO.File.Exists(mod.Path));
    }

    [Fact]
    public void CrashRepairNeverTouchesOriginsOwnJars()
    {
        var mod = OnDisk("origin-client");
        var r = CrashRepair.DisableBlamed(_dir, mod.FileName, n => n == mod.FileName);
        Assert.Equal(CrashRepairOutcome.Protected, r.Outcome);
        Assert.True(System.IO.File.Exists(mod.Path));
    }

    [Theory]
    [InlineData("missing.jar")]
    [InlineData("")]
    [InlineData("notes.txt")]
    public void CrashRepairReportsWhatItCouldNotFind(string name) =>
        Assert.Equal(CrashRepairOutcome.NotFound, CrashRepair.DisableBlamed(_dir, name, _ => false).Outcome);

    [Fact]
    public void CrashRepairUsesTheNameOnlyNeverAPath()
    {
        var mod = OnDisk("culprit");
        var outside = Path.Combine(Path.GetTempPath(), "origin-repair-outside-" + Guid.NewGuid().ToString("N") + ".jar");
        System.IO.File.WriteAllText(outside, "x");
        try
        {
            // A report that names a file one level up must not reach it.
            var r = CrashRepair.DisableBlamed(_dir, Path.Combine("..", Path.GetFileName(outside)), _ => false);
            Assert.Equal(CrashRepairOutcome.NotFound, r.Outcome);
            Assert.True(System.IO.File.Exists(outside));
        }
        finally { System.IO.File.Delete(outside); }
        Assert.True(System.IO.File.Exists(mod.Path));
    }

    [Fact]
    public void CrashRepairFailsCleanlyWhenTheTurnedOffNameIsTaken()
    {
        var mod = OnDisk("culprit");
        System.IO.File.WriteAllText(mod.Path + ".disabled", "old");
        var r = CrashRepair.DisableBlamed(_dir, mod.FileName, _ => false);
        Assert.Equal(CrashRepairOutcome.Failed, r.Outcome);
        Assert.Equal("old", System.IO.File.ReadAllText(mod.Path + ".disabled"));
        Assert.True(System.IO.File.Exists(mod.Path));
    }

    [Fact]
    public void CrashRepairLooksInTheProfileFolderForAProfileGameAndTheSharedOneOtherwise()
    {
        // Paths only: a version that has no folder, so nothing exists and nothing is changed.
        const string version = "0.0-test";
        var profile = CrashRepair.DisableBlamed(version, "p1", "x.jar", _ => false);
        var shared = CrashRepair.DisableBlamed(version, null, "x.jar", _ => false);
        Assert.Equal(OriginPaths.ProfileModsDir(version, "p1"), profile.Folder);
        Assert.Equal(OriginPaths.ModsDir(version), shared.Folder);
        Assert.NotEqual(profile.Folder, shared.Folder);
        Assert.Equal(CrashRepairOutcome.NotFound, profile.Outcome);
    }
}
