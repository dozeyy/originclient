using OriginLauncher.App.Core.Loaders;
using Xunit;

namespace OriginLauncher.Tests;

// FabricApiInstaller asks Modrinth for every Fabric API build of a Minecraft version and installs the
// newest. Modrinth lists alphas and betas next to releases, so the pick must skip them
// (Will, 2026-10-25). This holds the rule on fake rows, never the network.
public class ModrinthReleasePickerTests
{
    private sealed record Row(string Id, string? Type, DateTimeOffset Published);

    private static readonly DateTimeOffset T0 = new(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);

    private static Row? Pick(params Row[] rows) =>
        ModrinthReleasePicker.PickNewestRelease(rows, r => r.Type, r => r.Published);

    [Fact]
    public void A_newer_alpha_or_beta_never_beats_an_older_release()
    {
        var picked = Pick(
            new Row("alpha", "alpha", T0.AddDays(30)),
            new Row("beta", "beta", T0.AddDays(20)),
            new Row("release", "release", T0.AddDays(10)));
        Assert.Equal("release", picked?.Id);
    }

    [Fact]
    public void Newest_release_wins_among_releases_whatever_the_list_order()
    {
        var picked = Pick(
            new Row("old", "release", T0),
            new Row("new", "release", T0.AddDays(5)),
            new Row("mid", "release", T0.AddDays(2)));
        Assert.Equal("new", picked?.Id);
    }

    [Fact]
    public void No_release_means_nothing_is_picked()
    {
        Assert.Null(Pick(new Row("a", "alpha", T0), new Row("b", "beta", T0.AddDays(1))));
        Assert.Null(Pick());
        Assert.Null(ModrinthReleasePicker.PickNewestRelease<Row>(null, r => r.Type, r => r.Published));
    }

    [Fact]
    public void A_missing_or_oddly_cased_type_is_handled()
    {
        Assert.Null(Pick(new Row("untyped", null, T0)));
        Assert.Equal("shouty", Pick(new Row("shouty", "RELEASE", T0))?.Id);
    }
}
