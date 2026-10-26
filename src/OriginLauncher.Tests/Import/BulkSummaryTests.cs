using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Caches;
using OriginLauncher.App.Core.Import.GameFiles;
using Xunit;
using Xunit.Abstractions;

namespace OriginLauncher.Tests.Import;

// "Import everything", the result: summary.txt and the Done view are one structure (B20). Every scanned setup appears
// exactly once, versions newest first, and the player only ever reads player words.
public class BulkSummaryTests : IDisposable
{
    private readonly BulkFixture _f = new();
    private readonly ITestOutputHelper _out;

    public BulkSummaryTests(ITestOutputHelper output) => _out = output;

    public void Dispose() => _f.Dispose();

    // A mixed scan: two versions imported (one with a packs-only Forge setup), and one of each kind of skip.
    private async Task<(BulkImportPlan Plan, BulkImportResult Result)> MixedRun(bool rich = false)
    {
        var a = _f.Add("Survival", "1.21.1", playtimeHours: 30);
        _f.Add("Creative", "1.21.1", playtimeHours: 5);
        _f.Add("Forgey", "1.20.1", loader: LoaderKind.Forge);
        _f.Add("Fabric 1.20.1", "1.20.1");
        _f.Add("Too new", "1.21.5");
        _f.Add("Mystery", null);
        var unticked = _f.Add("Left out", "1.21.1", kind: SourceKind.Modrinth);
        var contributors = new List<IImportPlanContributor> { new CacheContributor() };
        if (rich)
        {
            var mojang = new Mojang();
            var official = Path.Combine(_f.Rig.Root, "official-mc");
            mojang.WriteOfficial(official);
            contributors.Add(new GameFileContributor(new GameFileRoots { InstancesRoot = _f.Rig.Instances, OfficialMinecraftDir = official }, mojang.Http));
            Kit.WriteBytes(a.GameDir, "mods/dh.jar", Kit.FabricJar("distanthorizons", "2.3.3-b", name: "Distant Horizons"));
            Kit.WriteBytes(a.GameDir, "Distant_Horizons_server_data/Hyp/aaa@minecraft@@overworld/DistantHorizons.sqlite", EngineBKit.Sqlite("dh"));
        }
        var engine = _f.Engine(contributors);
        var plan = await _f.Plan(engine, _f.Request(BulkSelection.All().Without(unticked.BulkId()),
            options: new ImportOptions { CopyWorlds = rich }, playWithExternalMods: !rich));
        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);
        return (plan, result);
    }

    [Fact]
    public async Task EveryScannedSetupAppearsExactlyOnce()
    {
        var (_, result) = await MixedRun();
        var view = BulkSummary.ToView(result);

        var listed = view.Versions.SelectMany(v => v.Setups).Select(s => s.Label)
            .Concat(view.DidntComeOver.Select(d => d.Label.Split(" (Minecraft ")[0]))
            .ToList();
        Assert.Equal(_f.Setups.Count, listed.Count);
        foreach (var setup in _f.Setups)
            Assert.Single(listed, l => l == setup.BulkLabel());
    }

    [Fact]
    public async Task TheTextAndTheViewHaveTheSameStructure()
    {
        var (_, result) = await MixedRun();
        var view = BulkSummary.ToView(result);
        var text = result.SummaryText;

        Assert.Equal("Imported 4 setups into 2 Minecraft versions.", view.Header);
        Assert.Equal(new[] { "1.21.1", "1.20.1" }, view.Versions.Select(v => v.Version));
        Assert.Equal("Minecraft 1.21.1: settings from Prism: Survival", view.Versions[0].SettingsLine);
        Assert.Equal("Minecraft 1.20.1: settings from Prism: Fabric 1.20.1", view.Versions[1].SettingsLine);
        var forge = view.Versions[1].Setups.Single(s => s.Label == "Prism: Forgey");
        Assert.Equal(SetupResultKind.ImportedPartly, forge.Kind);
        Assert.Contains("Its mods were made for a different kind of Minecraft mod setup, so only its packs come over.", forge.NeedsAttention);
        var creative = view.Versions[0].Setups.Single(s => s.Label == "Prism: Creative");
        Assert.Contains("Settings for Minecraft 1.21.1 come from Prism: Survival", creative.NotImported);
        Assert.Contains(view.DidntComeOver, d => d.Reason == "Origin doesn't support Minecraft 1.21.5, so this setup can't come over.");
        Assert.Contains(view.DidntComeOver, d => d.Reason == "Origin couldn't tell which Minecraft version this setup is, so it can't come over.");
        Assert.Contains(view.DidntComeOver, d => d.Reason == "You left this setup out.");

        // The text is the view written out, in the same order.
        Assert.StartsWith("Origin import: everything" + Environment.NewLine + view.Header, text);
        var order = new[] { view.Versions[0].SettingsLine, "Prism: Survival:", "Prism: Creative:", view.Versions[1].SettingsLine, "Didn't come over" }
            .Select(s => text.IndexOf(s, StringComparison.Ordinal)).ToList();
        Assert.All(order, i => Assert.True(i >= 0));
        Assert.Equal(order.OrderBy(i => i), order);
        Assert.Equal(text, File.ReadAllText(ImportLayout.Summary(_f.Rig.Imports, result.RunId)));
        Assert.Equal(text, BulkSummary.Render(result));
    }

    [Fact]
    public async Task ThePlayerNeverReadsJargon()
    {
        var (_, result) = await MixedRun(rich: true);
        _out.WriteLine(result.SummaryText); // what a player reads, for whoever reviews this
        var view = BulkSummary.ToView(result);
        var strings = new List<string> { result.SummaryText, view.Header };
        strings.AddRange(view.Notes);
        foreach (var v in view.Versions)
        {
            strings.Add(v.SettingsLine);
            strings.AddRange(v.Files);
            foreach (var s in v.Setups)
                strings.AddRange(new[] { s.Label, s.Status }.Concat(s.Imported).Concat(s.Reused).Concat(s.NeedsAttention).Concat(s.NotImported));
        }
        strings.AddRange(view.DidntComeOver.Select(d => d.Label + " " + d.Reason));

        Assert.Contains(BulkSummary.FilesTitle, result.SummaryText);                // the rich run did reuse files...
        Assert.Contains(BulkSummary.TerrainTitle, result.SummaryText);              // ...and a Distant Horizons folder
        foreach (var s in strings)
            foreach (var word in new[] { "instance", "cache", "game file" })
                Assert.DoesNotContain(word, s, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task ARolledBackRunSaysSoAndListsEverySetupAsPutBack()
    {
        _f.Add("A", "1.21.1");
        _f.Add("B", "1.20.4");
        var engine = _f.Engine(new IImportPlanContributor[] { new StepFor("B", _ => throw new IOException("no")) });
        var plan = await _f.Plan(engine);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
        var view = BulkSummary.ToView(result);

        Assert.Equal("The import didn't finish: nothing was changed.", view.Header);
        Assert.All(view.Versions.SelectMany(v => v.Setups), s => Assert.Equal("put back", s.Status));
        Assert.NotNull(view.Error);
    }
}
