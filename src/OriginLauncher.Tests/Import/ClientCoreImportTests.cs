using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Mods;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Another client's core jar (Feather, Lunar, Badlion, LabyMod) never lands in Origin: it needs libraries only its own
// launcher ships and hooks every class, so it stopped the whole game at launch (2026-10-20, feather-fabric ...).
public class ClientCoreImportTests : IDisposable
{
    private readonly string _root = Kit.Temp("clientcore");

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private Dictionary<string, ModDecision> Resolve(Dictionary<string, byte[]> jars)
    {
        var dir = Path.Combine(_root, "mods-" + Guid.NewGuid().ToString("N")[..6]);
        foreach (var (name, bytes) in jars) Kit.WriteBytes(dir, name, bytes);
        var builtIns = BuiltInModSet.Build("1.21.1", new MemoryStream(Kit.OriginClientJar()), Array.Empty<CatalogMod>());
        var mods = ModSetResolver.ReadSourceMods(dir, new SourceFs(), CancellationToken.None);
        return ModSetResolver.Resolve(mods, new ResolveContext("1.21.1", builtIns, "21")).ToDictionary(d => d.FileName);
    }

    [Fact]
    public void Feathers_core_jar_is_skipped_with_a_plain_reason()
    {
        // The real file: feather-fabric-1.21.1-1.0.0-SNAPSHOT.temp.jar, mod id "feather".
        var d = Resolve(new()
        {
            ["feather-fabric-1.21.1-1.0.0-SNAPSHOT.temp.jar"] = Kit.FabricJar("feather", "release/743f0ee9", name: "Feather Client"),
            ["fine.jar"] = Kit.FabricJar("fine"),
        });

        var feather = d["feather-fabric-1.21.1-1.0.0-SNAPSHOT.temp.jar"];
        Assert.False(feather.IsCopied);
        Assert.Null(feather.DestFileName);
        Assert.Equal("Feather's own client files only work inside Feather, not in Origin, so they are not imported.", feather.Reason);
        Assert.Equal(ModOutcome.Copied, d["fine.jar"].Outcome);
    }

    [Theory]
    [InlineData("lunar", "lunar-client-core.jar")]
    [InlineData("ichor", "ichor-1.21.1.jar")]
    [InlineData("badlion", "badlion-core.jar")]
    [InlineData("labymod", "labymod-4.jar")]
    public void Other_clients_core_jars_are_skipped_by_id(string id, string file)
    {
        var d = Resolve(new() { [file] = Kit.FabricJar(id) });
        Assert.False(d[file].IsCopied);
        Assert.Contains("only work inside", d[file].Reason);
    }

    [Fact]
    public void A_core_jar_is_skipped_by_file_name_when_it_carries_no_readable_id()
    {
        var d = Resolve(new() { ["badlionclient-1.21.jar"] = Kit.Zip(Kit.Text("readme.txt", "x")) });
        Assert.False(d["badlionclient-1.21.jar"].IsCopied);
        Assert.Contains("Badlion Client", d["badlionclient-1.21.jar"].Reason);
    }

    [Fact]
    public void A_core_jar_turned_off_in_the_old_setup_is_still_not_imported()
    {
        var d = Resolve(new() { ["feather-fabric-1.21.1.jar.disabled"] = Kit.FabricJar("feather") });
        Assert.False(d["feather-fabric-1.21.1.jar.disabled"].IsCopied);
    }

    [Theory]
    [InlineData("essential")]       // a real Fabric mod, not another client's core
    [InlineData("featherweight")]   // an unrelated mod that merely starts the same way
    [InlineData("lunarlib")]
    public void Real_mods_with_similar_names_are_not_caught(string id)
    {
        var d = Resolve(new() { [id + ".jar"] = Kit.FabricJar(id) });
        Assert.Equal(ModOutcome.Copied, d[id + ".jar"].Outcome);
    }

    [Fact]
    public void Class_names_map_to_their_client()
    {
        Assert.Equal("feather", ClientCoreMods.FindByClassName("net.digitalingot.feather.mixin.FeatherMixinPlugin")?.ModId);
        Assert.Equal("lunar", ClientCoreMods.FindByClassName("com.moonsworth.lunar.client.Main")?.ModId);
        Assert.Null(ClientCoreMods.FindByClassName("org.embeddedt.modernfix.ModernFixPreLaunchFabric"));
    }
}
