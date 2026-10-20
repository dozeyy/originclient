using OriginLauncher.App.Core.Import;
using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.Tests.Import;

// A launcher whose setups are all in Origin already (and unchanged) has no tile at all; one with only some of them left
// shows just those (Will, 2026-10-20). The scan hands the model the setups still on offer and the ones already imported.
public class ImportFlowAlreadyImportedTests
{
    private static readonly string[] Supported = { "1.21.1", "1.20.1" };

    private static DetectedInstance Setup(string name, string mc, SourceKind kind) => new()
    {
        Kind = kind, Name = name, McVersion = mc, GameDir = Path.Combine(Path.GetTempPath(), "origin-flow-never-read", kind.ToString(), name),
        Confidence = Confidence.Exact,
    };

    private static ImportFlowModel Model(DetectedInstance[] offered, DetectedInstance[] done) =>
        new(offered, Supported) { AlreadyImported = done, Installed = new HashSet<SourceKind> { SourceKind.Lunar, SourceKind.Prism } };

    [Fact]
    public void A_fully_imported_launcher_has_no_tile_not_even_an_empty_one()
    {
        var model = Model(new[] { Setup("Pack", "1.21.1", SourceKind.Prism) },
            new[] { Setup("Lunar 1.21.1", "1.21.1", SourceKind.Lunar), Setup("Lunar 1.20.1", "1.20.1", SourceKind.Lunar) });

        var tiles = model.Tiles(scanning: false);

        Assert.DoesNotContain(tiles, t => t.Kind == SourceKind.Lunar);
        Assert.Equal(LauncherState.Found, tiles.Single(t => t.Kind == SourceKind.Prism).State);
        Assert.False(model.EverythingImported);
    }

    [Fact]
    public void A_launcher_with_one_changed_setup_shows_only_that_setup()
    {
        var model = Model(new[] { Setup("Lunar 1.20.1", "1.20.1", SourceKind.Lunar) },
            new[] { Setup("Lunar 1.21.1", "1.21.1", SourceKind.Lunar) });

        var lunar = model.Tiles(false).Single(t => t.Kind == SourceKind.Lunar);

        Assert.Equal(LauncherState.Found, lunar.State);
        Assert.Equal("1 version", lunar.Caption);
        Assert.Equal(new[] { "Lunar 1.20.1" }, model.Setups.Select(s => s.Name));
    }

    [Fact]
    public void When_everything_found_is_imported_the_page_can_say_so()
    {
        var model = Model(Array.Empty<DetectedInstance>(), new[] { Setup("Lunar 1.21.1", "1.21.1", SourceKind.Lunar) });

        Assert.True(model.EverythingImported);
        Assert.Empty(model.LaunchersWithSetups());
        Assert.DoesNotContain(model.Tiles(false), t => t.State == LauncherState.Found);
    }

    [Fact]
    public void Nothing_imported_and_nothing_found_is_not_everything_imported()
    {
        var model = Model(Array.Empty<DetectedInstance>(), Array.Empty<DetectedInstance>());
        Assert.False(model.EverythingImported);
        Assert.Equal(LauncherState.Empty, model.Tiles(false).Single(t => t.Kind == SourceKind.Lunar).State);
    }

    [Fact]
    public void While_the_scan_runs_every_tile_still_reads_Looking()
    {
        var model = Model(Array.Empty<DetectedInstance>(), new[] { Setup("Lunar 1.21.1", "1.21.1", SourceKind.Lunar) });
        Assert.All(model.Tiles(scanning: true), t => Assert.Equal(LauncherState.Looking, t.State));
    }
}
