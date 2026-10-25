using OriginLauncher.App.Core.Compat;
using Xunit;
using static OriginLauncher.Tests.Compat.CompatKit;

namespace OriginLauncher.Tests.Compat;

// The engine against metadata copied out of real mod jars (Fixtures/*.json, each the mod's fabric.mod.json plus its
// nested jars'), read through the same ModMetadataReader the launcher uses. These are the cases that matter in the
// field: Sodium/Iris pairs, Sodium 0.8 vs old Fabric API, and Fabric API's jar-in-jar modules.
public class CompatFixtureTests
{
    private static readonly CompatPlatform Fabric1211 = new("1.21.11", LoaderFamily.Fabric, "0.18.1", "21");

    [Fact]
    public void FixturesReadLikeRealJars()
    {
        var sodium = FixtureMod("sodium-0.6.13+1.21.1.json");
        Assert.Equal("sodium", sodium.Info.Id);
        Assert.Equal(ModLoader.Fabric, sodium.Info.Loader);
        Assert.Contains("indium", sodium.Info.Provides);

        var api = FixtureMod("fabric-api-0.116.13+1.21.1.json");
        Assert.Equal("fabric-api", api.Info.Id);
        Assert.True(api.Info.Nested.Count > 10, "Fabric API bundles its modules as jar-in-jar");
    }

    [Fact]
    public void RealModsOnTheirOwnVersionAreClean()
    {
        var report = Check(
            FixtureMod("fabric-api-0.116.13+1.21.1.json"),
            FixtureMod("sodium-0.6.13+1.21.1.json"),
            FixtureMod("iris-1.8.8+1.21.1.json"),
            FixtureMod("lithium-0.15.4+1.21.1.json"),
            FixtureMod("modmenu-11.0.4.json"));
        Assert.True(report.IsClean, string.Join("; ", report.Findings.Select(f => f.Subject.FileName + ": " + f.Summary)));
    }

    [Fact]
    public void IrisNeedsTheSodiumItWasBuiltFor()
    {
        // Iris 1.10.8 (for Sodium 0.8.x) next to Origin's Sodium 0.6.13.
        var sodium = FixtureMod("sodium-0.6.13+1.21.1.json", ModRole.Managed);
        var iris = FixtureMod("iris-1.10.8+1.21.11.json");
        var report = Check(Fabric121, sodium, iris, FixtureMod("fabric-api-0.116.13+1.21.1.json"));
        var f = Assert.Single(report.For(iris), x => x.Kind == FindingKind.WrongVersion && x.TargetId == "sodium");
        Assert.Equal("0.6.13+mc1.21.1", f.Found);
        Assert.True(f.TargetIsOrigin);
        Assert.Equal(Severity.Blocking, f.Severity);
    }

    [Fact]
    public void OldIrisAgainstTheNewSodiumIsAConfirmedBreak()
    {
        // Sodium 0.8.12 declares breaks iris <=1.10.6; Iris 1.8.8 is below that and also wants Sodium 0.6.x.
        var sodium = FixtureMod("sodium-0.8.12+1.21.11.json", ModRole.Managed);
        var iris = FixtureMod("iris-1.8.8+1.21.1.json");
        var report = Check(Fabric1211, sodium, iris);
        var kinds = report.For(iris).Select(f => f.Kind).ToHashSet();
        Assert.Contains(FindingKind.Breaks, kinds);
        Assert.All(report.For(iris), f => Assert.Equal(Certainty.Confirmed, f.Certainty));
    }

    [Fact]
    public void TheMatchingIrisAndSodiumPairIsClean()
    {
        var report = Check(Fabric1211,
            FixtureMod("sodium-0.8.12+1.21.11.json", ModRole.Managed),
            FixtureMod("iris-1.10.8+1.21.11.json"));
        Assert.DoesNotContain(report.Findings, f => f.Kind is FindingKind.Breaks or FindingKind.WrongVersion);
    }

    [Fact]
    public void Sodium08BreaksAnOlderFabricApi()
    {
        var sodium = FixtureMod("sodium-0.8.12+1.21.11.json", ModRole.Managed);
        // The real 0.116.13 jar is also for 1.21.1 only (a WrongMinecraft that ends the check first), so the break is
        // shown with the same id and version on a descriptor that does not carry that range.
        var api = Mod("fabric-api", "0.116.13+1.21.1");
        var report = Check(Fabric1211, sodium, api);
        var f = Assert.Single(report.For(api), x => x.Kind == FindingKind.Breaks);
        Assert.Same(sodium, f.Related);
        Assert.True(f.TargetIsOrigin);
    }

    [Fact]
    public void FabricApisNestedModulesAnswerForTheirIds()
    {
        // A mod needing one Fabric API module is satisfied by the Fabric API jar's jar-in-jar.
        var api = FixtureMod("fabric-api-0.116.13+1.21.1.json");
        var nestedId = api.Info.Nested.First().Id;
        var user = Mod("uses-module", needs: new[] { (nestedId, "*") });
        Assert.True(Check(api, user).IsClean);
        Assert.Equal(FindingKind.MissingDependency, Assert.Single(Check(user).Findings).Kind);
    }

    [Fact]
    public void TheRealLoaderRangesParse()
    {
        // Every depends/breaks range in the fixtures is a valid Fabric range (none degrade to "matches nothing").
        foreach (var file in Directory.GetFiles(FixtureDir, "*.json"))
        {
            var mod = FixtureMod(Path.GetFileName(file));
            foreach (var dep in mod.Info.Depends.Concat(mod.Info.Breaks).Concat(mod.Info.Recommends).Concat(mod.Info.Conflicts))
                Assert.True(dep.Predicate.IsValid, $"{mod.FileName}: {dep.Id} {dep.Predicate.Error}");
        }
    }
}
