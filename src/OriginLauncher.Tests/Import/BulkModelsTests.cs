using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using Xunit;

namespace OriginLauncher.Tests.Import;

// "Import everything": the selection rules of the E0 skeleton (the engine behind it is not built yet).
public class BulkModelsTests
{
    private static DetectedInstance I(string name, SourceKind kind) => new()
    {
        Kind = kind, Name = name, GameDir = "/x/" + name + "/", McVersion = "1.21.1",
    };

    [Fact]
    public void LabelNamesTheLauncherOnce()
    {
        Assert.Equal("Prism: Survival", I("Survival", SourceKind.Prism).BulkLabel());
        // A setup already named after its launcher is not "Lunar: Lunar 1.21.1".
        Assert.Equal("Lunar 1.21.1", I("Lunar 1.21.1", SourceKind.Lunar).BulkLabel());
    }

    [Fact]
    public void AllCoversEverythingExceptUnticked()
    {
        var a = I("a", SourceKind.Prism);
        var b = I("b", SourceKind.Modrinth);
        var sel = BulkSelection.All().Without(b.BulkId());
        Assert.True(sel.Includes(a));
        Assert.False(sel.Includes(b));
    }

    [Fact]
    public void LauncherCoversOnlyThatLauncher()
    {
        var sel = BulkSelection.OfLauncher(SourceKind.Prism);
        Assert.True(sel.Includes(I("a", SourceKind.Prism)));
        Assert.False(sel.Includes(I("b", SourceKind.Modrinth)));
    }

    [Fact]
    public void ExplicitCoversExactlyTheNamedSetups()
    {
        var a = I("a", SourceKind.Prism);
        var b = I("b", SourceKind.Prism);
        var sel = BulkSelection.Of(new[] { a.BulkId() });
        Assert.True(sel.Includes(a));
        Assert.False(sel.Includes(b));
    }

    [Fact]
    public void IdIgnoresATrailingSlash()
    {
        var withSlash = I("a", SourceKind.Prism);
        var without = withSlash with { GameDir = "/x/a" };
        Assert.Equal(withSlash.BulkId(), without.BulkId());
    }

    [Fact]
    public void AnEmptyPlanCannotApply()
    {
        var plan = new BulkImportPlan { RunId = "r" };
        Assert.False(plan.CanApply);
    }
}
