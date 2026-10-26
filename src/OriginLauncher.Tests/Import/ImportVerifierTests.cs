using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Step 4 of the guided import (decision 04): the report re-reads what landed and sorts it into succeeded / skipped /
// unsupported / failed, each with its reason; every profile made is checked like a launch; and an import whose
// profile was saved to the settings but whose journal never committed is put back whole by the next start.
public class ImportVerifierTests : IDisposable
{
    private readonly BulkFixture _f = new();

    public void Dispose() => _f.Dispose();

    private async Task<(BulkImportPlan Plan, BulkImportResult Result)> ImportAll()
    {
        var engine = _f.Engine();
        var plan = await _f.Plan(engine, _f.Request(options: new ImportOptions { CopyWorlds = true }));
        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);
        return (plan, result);
    }

    [Fact]
    public async Task TheReportNamesEverythingThatDidNotComeOverAndWhy()
    {
        var a = _f.Add("A", "1.21.1", playtimeHours: 30);
        var old = _f.Add("Old", "1.7.10", playtimeHours: 2);          // a Minecraft Origin has no build for
        var (plan, result) = await ImportAll();

        var report = ImportVerifier.Verify(plan, result);

        var unsupported = report.In(VerifyBucket.Unsupported).ToList();
        // The standing list: named every time, never implied.
        foreach (var (name, _) in ImportVerifier.NeverImported) Assert.Contains(unsupported, i => i.Name == name && i.Reason.Length > 0);
        Assert.Contains(unsupported, i => i.Name.Contains("Accounts") && i.Reason.Contains("Sign in again"));
        Assert.Contains(unsupported, i => i.Name.Contains("other mod setups") && i.Reason == ImportVerifier.WrongLoaderReason + " They stay behind.");
        // Mandate 1: nothing on the report names a mod loader (the Forge jar's line included); file names are the
        // player's own and may say anything, so only the names Origin wrote and every reason are checked.
        Assert.All(report.Items, i => Assert.False(ImportVerifier.NamesLoader(i.Reason), i.Reason));
        Assert.All(ImportVerifier.NeverImported, n => Assert.False(ImportVerifier.NamesLoader(n.Name + " " + n.Reason), n.Name));
        Assert.Contains(unsupported, i => i.Name.Contains("forge", StringComparison.OrdinalIgnoreCase) && i.Reason == ImportVerifier.WrongLoaderReason);
        Assert.Contains(unsupported, i => i.Name.Contains("ATLauncher") && i.Name.Contains("GDLauncher") && i.Name.Contains("Technic"));
        Assert.Contains(unsupported, i => i.Name.Contains(".mrpack"));
        // This run's own: the credential-looking file, the Forge jar, the setup on an unsupported Minecraft.
        Assert.Contains(unsupported, i => i.Setup == a.BulkLabel() && i.Name == "discord-token.json" && i.Reason.Contains("account"));
        Assert.Contains(unsupported, i => i.Setup == a.BulkLabel() && i.Name.Contains("forge", StringComparison.OrdinalIgnoreCase));
        Assert.Contains(unsupported, i => i.Setup == old.BulkLabel() && i.Name == "Whole setup" && i.Reason.Length > 0);
        // Skipped on purpose, with the engine's reason: built for another Minecraft.
        Assert.Contains(report.In(VerifyBucket.Skipped), i => i.Setup == a.BulkLabel() && i.Name.Contains("oldthing") && i.Reason.Length > 0);
        // What landed was found on disk again.
        Assert.Contains(report.In(VerifyBucket.Succeeded), i => i.Setup == a.BulkLabel() && i.Name.EndsWith("mods") && i.Reason.Contains("profile"));
        Assert.Contains(report.In(VerifyBucket.Succeeded), i => i.Name.Contains("world"));
        Assert.Empty(report.In(VerifyBucket.Failed));
        Assert.All(report.Items, i => Assert.False(string.IsNullOrWhiteSpace(i.Reason)));
    }

    [Fact]
    public void NoPlayerFacingImportLineNamesAModLoader()
    {
        // Mandate 1 (the player never sees which loader a version uses; "Origin runs Fabric" is also false on 1.8.9).
        var lines = new[]
        {
            ImportVerifier.WrongLoaderReason, ImportVerifier.NotAModReason, BulkRules.ForgeNote, BulkRules.ForgeCaption,
            OriginLauncher.App.UI.Controls.ImportGridModel.PacksOnlyReason,
        };
        Assert.All(lines, l => Assert.False(ImportVerifier.NamesLoader(l), l));
        // The detector itself still sees loaders: the check is a real one.
        Assert.True(ImportVerifier.NamesLoader("This is a Forge mod. Origin runs Fabric, so it can't load it."));
        Assert.True(ImportVerifier.NamesLoader("1.8.9 runs on Forge in Origin"));
        Assert.False(ImportVerifier.NamesLoader("CurseForge: Skyblock"));
    }

    [Fact]
    public async Task SomethingMissingWhenCheckedAgainIsAFailureNotASuccess()
    {
        var a = _f.Add("A", "1.21.1");
        var (plan, result) = await ImportAll();

        // The re-read finds World1 gone (an antivirus took it, the player deleted it): the report must not say it came over.
        var report = ImportVerifier.Verify(plan, result, exists: p => !p.Contains("World1") && ImportVerifier.OnDisk(p));

        Assert.Contains(report.In(VerifyBucket.Failed), i => i.Setup == a.BulkLabel() && i.Name.Contains("World1") && i.Reason.Contains("checked"));
        Assert.DoesNotContain(report.In(VerifyBucket.Succeeded), i => i.Name.Contains("world"));
    }

    [Fact]
    public async Task EverySetupWithModsIsAProfileAndEachProfileIsChecked()
    {
        _f.Add("A", "1.21.1", playtimeHours: 30);
        _f.Add("B", "1.21.1", playtimeHours: 20);
        var (plan, result) = await ImportAll();
        var checkedDirs = new List<string>();

        var report = ImportVerifier.Verify(plan, result, checkProfile: (version, dir) =>
        {
            checkedDirs.Add(dir);
            return dir == result.Versions.SelectMany(v => v.Setups).First(s => s.ProfileId != null).ProfileModsDir
                ? new[] { "Needs Lib: needs ghostlib 2.0 or newer" } : Array.Empty<string>();
        });

        var made = result.Versions.SelectMany(v => v.Setups).Where(s => s.ProfileId != null).ToList();
        Assert.Equal(made.Select(s => s.ProfileId), report.Profiles.Select(p => p.ProfileId));
        Assert.Equal(made.Select(s => s.ProfileModsDir), checkedDirs);
        Assert.Equal(_f.Rig.Settings.Load().Profiles.Select(p => p.Id).OrderBy(i => i), report.Profiles.Select(p => p.ProfileId).OrderBy(i => i));
        var broken = report.Profiles[0];
        Assert.False(broken.CanStart);
        Assert.Equal(ImportVerifier.FixCompatibilityLine, broken.FixLine);
        Assert.Contains("Fix Compatibility", broken.FixLine);
        Assert.All(report.Profiles.Skip(1), p => Assert.True(p.CanStart));
    }

    [Fact]
    public void AProfileWithAModMissingWhatItNeedsCantStartAndOneThatFitsCan()
    {
        var shared = Path.Combine(_f.Rig.Root, "shared-mods");
        var good = Path.Combine(_f.Rig.Root, "profile-good");
        var bad = Path.Combine(_f.Rig.Root, "profile-bad");
        Kit.WriteBytes(shared, "origin-own.jar", Kit.FabricJar("originown", "1.0", minecraft: "~1.21.1"));
        Kit.WriteBytes(good, "fits.jar", Kit.FabricJar("fits", "1.0", minecraft: "~1.21.1"));
        Kit.WriteBytes(bad, "needs-lib.jar", Kit.FabricJar("needslib", "1.0", minecraft: "~1.21.1", depends: new() { ["ghostlib"] = ">=2.0" }));
        bool IsOrigin(string name) => name == "origin-own.jar";

        Assert.Empty(ImportProfileCheck.Check("1.21.1", shared, good, IsOrigin));
        var problems = ImportProfileCheck.Check("1.21.1", shared, bad, IsOrigin);
        Assert.Contains(problems, p => p.Contains("needslib", StringComparison.OrdinalIgnoreCase) || p.Contains("ghostlib"));
    }

    [Fact]
    public async Task AnImportWhoseProfileWasSavedButNeverCommittedIsPutBackWholeOnTheNextStart()
    {
        using var rig = new Rig();
        TypicalSource.Populate(rig.Source);
        var engine = rig.Engine();
        var plan = await engine.PlanAsync(rig.Request(), null, CancellationToken.None);
        Assert.NotNull(plan.SettingsDelta?.AddProfile);
        var instances = Kit.TreeHash(rig.Instances, mtimes: true);
        var settings = rig.Settings.FileHash();

        // The launcher dies right after the settings save, before the journal says Committed.
        await Assert.ThrowsAsync<OutOfMemoryException>(() => engine.ApplyAsync(plan, new DiesAfterSaving(rig.Settings), null, CancellationToken.None));
        Assert.Single(rig.Settings.Load().Profiles);
        Assert.Equal(ImportState.Applying, ImportJournalStore.Load(ImportLayout.Journal(rig.Imports, plan.ImportId))!.State);

        var recovered = ImportRecovery.Recover(rig.Imports, Path.Combine(rig.Root, "OriginLauncher"), rig.Settings, null, CancellationToken.None);

        Assert.Equal(1, recovered);
        Assert.Empty(rig.Settings.Load().Profiles);
        Assert.Equal(settings, rig.Settings.FileHash());
        Assert.Equal(instances, Kit.TreeHash(rig.Instances, mtimes: true));   // the profile folder is gone too
        Assert.Equal(ImportState.RolledBack, ImportJournalStore.Load(ImportLayout.Journal(rig.Imports, plan.ImportId))!.State);
    }

    private sealed class DiesAfterSaving : IImportSettings
    {
        private readonly IImportSettings _inner;
        public DiesAfterSaving(IImportSettings inner) => _inner = inner;
        public void Apply(SettingsDelta delta)
        {
            _inner.Apply(delta);
            throw new OutOfMemoryException("simulated crash after the settings save");
        }
        public void Revert(SettingsDelta delta) => _inner.Revert(delta);
    }
}
