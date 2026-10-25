using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Import.Mods;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Reading mod jars (D10) and deciding what happens to each one (D11-D13).
// All jars are zips built in code with a fabric.mod.json (or the other loaders' files).
public class ModResolverTests : IDisposable
{
    private readonly string _root = Kit.Temp("mods");

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private static ModInfo Read(byte[] jar, string name = "x.jar") => ModMetadataReader.Read(new MemoryStream(jar), name);

    private static string Quilt(string id) =>
        "{\"schema_version\":1,\"quilt_loader\":{\"id\":\"" + id + "\",\"version\":\"1.2.0\",\"metadata\":{\"name\":\"Quilt " + id + "\"}}}";

    // ------------------------------------------------------------ metadata reader (criterion 23)

    [Fact]
    public void FabricJarGivesIdVersionDependenciesAndNestedJars()
    {
        var jar = Kit.FabricJar("outer", "1.4.0", minecraft: new[] { "1.21", "1.21.1" },
            depends: new() { ["fabricloader"] = ">=0.15.0", ["fabric-api"] = "*" },
            breaks: new() { ["badmod"] = "<2.0" }, provides: new[] { "outer-alias" }, name: "Outer Mod",
            nested: new[] { ("META-INF/jars/inner.jar", Kit.FabricJar("inner", "2.5.0", name: "Inner")) });

        var info = Read(jar);

        Assert.Equal(ModLoader.Fabric, info.Loader);
        Assert.Equal("outer", info.Id);
        Assert.Equal("1.4.0", info.Version);
        Assert.Equal("Outer Mod", info.Name);
        Assert.Contains(info.Depends, d => d.Id == "fabricloader" && d.Ranges.SequenceEqual(new[] { ">=0.15.0" }));
        Assert.Contains(info.Breaks, d => d.Id == "badmod");
        Assert.Equal(new[] { "outer-alias" }, info.Provides);
        var inner = Assert.Single(info.Nested);
        Assert.Equal(("inner", "2.5.0"), (inner.Id, inner.Version));
        Assert.True(info.MinecraftRange!.IsSatisfiedBy("1.21.1"));
        Assert.False(info.MinecraftRange!.IsSatisfiedBy("1.20.4"));
    }

    [Fact]
    public void QuiltOnlyJarIsQuilt()
    {
        var info = Read(Kit.Zip(Kit.Text("quilt.mod.json", Quilt("qmod"))));
        Assert.Equal(ModLoader.Quilt, info.Loader);
        Assert.Equal("qmod", info.Id);
        Assert.Equal("Quilt qmod", info.Name);
    }

    [Fact]
    public void ForgeNeoForgeAndLegacyForgeJarsAreRecognisedFromTheirOwnFiles()
    {
        var forge = Read(Kit.Zip(Kit.Text("META-INF/mods.toml",
            "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n[[mods]]\nmodId=\"forgemod\"\nversion=\"3.1\"\ndisplayName=\"Forge Mod\"\n")));
        Assert.Equal((ModLoader.Forge, "forgemod", "3.1", "Forge Mod"), (forge.Loader, forge.Id, forge.Version, forge.Name));

        var neo = Read(Kit.Zip(Kit.Text("META-INF/neoforge.mods.toml", "[[mods]]\nmodId = \"neomod\"\nversion = \"2.0\"\n")));
        Assert.Equal((ModLoader.NeoForge, "neomod"), (neo.Loader, neo.Id));

        var legacy = Read(Kit.Zip(Kit.Text("mcmod.info", """[{"modid":"oldmod","name":"Old Mod","version":"1.0","mcversion":"1.8.9"}]""")));
        Assert.Equal((ModLoader.LegacyForge, "oldmod", "1.8.9"), (legacy.Loader, legacy.Id, legacy.McVersion));

        var wrapped = Read(Kit.Zip(Kit.Text("mcmod.info", """{"modListVersion":2,"modList":[{"modid":"wrapped","version":"1"}]}""")));
        Assert.Equal("wrapped", wrapped.Id);
    }

    [Fact]
    public void AMultiLoaderJarWithAFabricModJsonCountsAsFabric()
    {
        var info = Read(Kit.Zip(
            Kit.Text("fabric.mod.json", """{"schemaVersion":1,"id":"multi","version":"1.0"}"""),
            Kit.Text("META-INF/mods.toml", "[[mods]]\nmodId=\"multi\"\n"),
            Kit.Text("META-INF/neoforge.mods.toml", "[[mods]]\nmodId=\"multi\"\n"),
            Kit.Text("quilt.mod.json", Quilt("multi"))));
        Assert.Equal(ModLoader.Fabric, info.Loader);
        Assert.Equal("multi", info.Id);
    }

    [Fact]
    public void ABrokenOrEmptyJarNeverThrows()
    {
        Assert.True(Read(new byte[] { 1, 2, 3, 4, 5 }).ReadFailed);
        Assert.Equal(ModLoader.Unknown, Read(Kit.Zip(Kit.Text("readme.txt", "hi"))).Loader);
        var brokenJson = Read(Kit.Zip(Kit.Text("fabric.mod.json", "{ not json")));
        Assert.Equal(ModLoader.Fabric, brokenJson.Loader);
        Assert.Null(brokenJson.Id);
    }

    // ------------------------------------------------------------ the resolver

    private Dictionary<string, ModDecision> Resolve(Dictionary<string, byte[]> jars, string target = "1.21.1",
        BuiltInModSet? builtIns = null)
    {
        var dir = Path.Combine(_root, "mods-" + Guid.NewGuid().ToString("N")[..6]);
        foreach (var (name, bytes) in jars) Kit.WriteBytes(dir, name, bytes);
        builtIns ??= BuiltInModSet.Build(target, new MemoryStream(Kit.OriginClientJar()), Array.Empty<CatalogMod>());
        var mods = ModSetResolver.ReadSourceMods(dir, new SourceFs(), CancellationToken.None);
        return ModSetResolver.Resolve(mods, new ResolveContext(target, builtIns, "21")).ToDictionary(d => d.FileName);
    }

    [Fact]
    public void ModsOriginAlreadyIncludesAreBuiltInByIdEvenWhenRenamedAndAtAnotherVersion()
    {
        var d = Resolve(new()
        {
            ["sodium-fabric-0.5.11+mc1.21.jar"] = Kit.FabricJar("sodium", "0.5.11", minecraft: "1.21"),
            ["totally-renamed.jar"] = Kit.FabricJar("iris", "1.7.0"),
            ["modmenu-11.0.0.jar"] = Kit.FabricJar("modmenu", "11.0.0"),
            ["fabric-api-0.100.jar"] = Kit.FabricJar("fabric-api", "0.100.0"),
            ["voicechat-fabric.jar"] = Kit.FabricJar("voicechat", "2.5.0"),
            ["mymod.jar"] = Kit.FabricJar("mymod", "1.0"),
        });

        Assert.Equal(ModOutcome.BuiltIn, d["sodium-fabric-0.5.11+mc1.21.jar"].Outcome);
        Assert.Contains("Origin includes Sodium 0.6.13", d["sodium-fabric-0.5.11+mc1.21.jar"].Reason);
        Assert.Equal(ModOutcome.BuiltIn, d["totally-renamed.jar"].Outcome);
        Assert.Contains("Origin includes Iris 1.8.8", d["totally-renamed.jar"].Reason);
        Assert.Equal(ModOutcome.BuiltIn, d["modmenu-11.0.0.jar"].Outcome);
        Assert.Equal(ModOutcome.BuiltIn, d["fabric-api-0.100.jar"].Outcome);
        Assert.Equal(ModOutcome.BuiltIn, d["voicechat-fabric.jar"].Outcome);
        Assert.Equal(ModOutcome.Copied, d["mymod.jar"].Outcome);
        Assert.All(new[] { "sodium-fabric-0.5.11+mc1.21.jar", "totally-renamed.jar" }, n => Assert.Null(d[n].DestFileName));
    }

    [Fact]
    public void TheBuiltInSetComesFromTheBundledJarAndTheCatalogWithNoHandKeptList()
    {
        var bundled = BuiltInModSet.Build("1.21.1", new MemoryStream(Kit.OriginClientJar()), Array.Empty<CatalogMod>());
        Assert.False(bundled.Incomplete);
        Assert.Equal("0.6.13+mc1.21.1", bundled.Find("sodium")!.Version);
        Assert.NotNull(bundled.Find("originclient"));
        Assert.NotNull(bundled.Find("fabric-api"));
        Assert.NotNull(bundled.Find("fabric-resource-loader-v0"));   // a Fabric API module
        Assert.Null(bundled.Find("fabric-language-kotlin"));         // a different mod
        Assert.Null(bundled.Find("indium"));                         // not bundled, not in the catalog here

        var catalogOnly = BuiltInModSet.Build("1.21.11", null, new[]
        {
            new CatalogMod("sodium", "sodium-fabric-0.8.12+mc1.21.11.jar"),
            new CatalogMod("modmenu", "17.0.0"), new CatalogMod("ferritecore", "ferritecore-8.2.0-fabric.jar"),
        });
        Assert.True(catalogOnly.Incomplete);                         // no bundled jar: flagged
        Assert.Equal("0.8.12", catalogOnly.Find("sodium")!.Version);
        Assert.Equal("8.2.0", catalogOnly.Find("ferritecore")!.Version);

        Assert.Equal("fabric", BuiltInModSet.Build("1.16.5", null, Array.Empty<CatalogMod>()).Mods.Single(m => m.DisplayName == "Fabric API").Id);
    }

    [Fact]
    public void AMissingDependencyOrUnmetRangeCopiesTheJarDisabledWithWhatItNeeds()
    {
        var d = Resolve(new()
        {
            ["needs-cloth.jar"] = Kit.FabricJar("needscloth", "1.0", depends: new() { ["cloth-config"] = ">=15.0" }),
            ["needs-newer-sodium.jar"] = Kit.FabricJar("newersodium", "1.0", depends: new() { ["sodium"] = ">=0.7.0" }),
            ["ok-with-sodium.jar"] = Kit.FabricJar("oksodium", "1.0", depends: new() { ["sodium"] = ">=0.6.0", ["fabricloader"] = ">=0.15", ["java"] = ">=21" }),
            ["needs-java-25.jar"] = Kit.FabricJar("java25", "1.0", depends: new() { ["java"] = ">=25" }),
        });

        Assert.Equal(ModOutcome.CopiedDisabled, d["needs-cloth.jar"].Outcome);
        Assert.Contains("Needs cloth-config >=15.0", d["needs-cloth.jar"].Reason);
        Assert.Equal("needs-cloth.jar.disabled", d["needs-cloth.jar"].DestFileName);
        Assert.Equal(ModOutcome.CopiedDisabled, d["needs-newer-sodium.jar"].Outcome);
        Assert.Contains("Needs sodium >=0.7.0", d["needs-newer-sodium.jar"].Reason);
        Assert.Equal(ModOutcome.Copied, d["ok-with-sodium.jar"].Outcome);       // a built-in at a satisfying version
        Assert.Equal(ModOutcome.CopiedDisabled, d["needs-java-25.jar"].Outcome);
    }

    [Fact]
    public void ADependencyMetByAnotherImportedModPassesAndADisabledOneCannotSatisfyAnother()
    {
        var d = Resolve(new()
        {
            ["lib.jar"] = Kit.FabricJar("lib", "2.0"),
            ["uses-lib.jar"] = Kit.FabricJar("useslib", "1.0", depends: new() { ["lib"] = ">=1.5" }),
            ["bad-lib.jar"] = Kit.FabricJar("badlib", "1.0", depends: new() { ["ghost"] = "*" }),
            ["uses-bad-lib.jar"] = Kit.FabricJar("usesbadlib", "1.0", depends: new() { ["badlib"] = "*" }),
        });

        Assert.Equal(ModOutcome.Copied, d["uses-lib.jar"].Outcome);
        Assert.Equal(ModOutcome.CopiedDisabled, d["bad-lib.jar"].Outcome);
        Assert.Equal(ModOutcome.CopiedDisabled, d["uses-bad-lib.jar"].Outcome);   // its dependency got disabled
    }

    [Fact]
    public void ABreaksConflictCopiesTheDeclaringModDisabled()
    {
        var d = Resolve(new()
        {
            ["a.jar"] = Kit.FabricJar("moda", "1.0", breaks: new() { ["modb"] = "<2.0" }),
            ["b.jar"] = Kit.FabricJar("modb", "1.5"),
            ["c.jar"] = Kit.FabricJar("modc", "1.0", breaks: new() { ["modb"] = ">=3.0" }),
        });

        Assert.Equal(ModOutcome.CopiedDisabled, d["a.jar"].Outcome);
        Assert.Contains("Conflicts with modb", d["a.jar"].Reason);
        Assert.Equal(ModOutcome.Copied, d["b.jar"].Outcome);
        Assert.Equal(ModOutcome.Copied, d["c.jar"].Outcome);                       // the range doesn't reach 1.5
    }

    [Fact]
    public void OptiFabricAndOldDistantHorizonsWithSodium08AreFlaggedWithTheKnownReasons()
    {
        var sodium08 = BuiltInModSet.Build("1.21.11", null, new[] { new CatalogMod("sodium", "sodium-fabric-0.8.12+mc1.21.11.jar") });
        var d = Resolve(new()
        {
            ["optifabric.jar"] = Kit.FabricJar("optifabric", "1.14"),
        }, "1.21.11", sodium08);
        Assert.Equal(ModOutcome.CopiedDisabled, d["optifabric.jar"].Outcome);
        Assert.Contains("Origin uses Sodium + Iris", d["optifabric.jar"].Reason);

        var dhOnly = Resolve(new() { ["dh.jar"] = Kit.FabricJar("distanthorizons", "2.3.2") }, "1.21.11", sodium08);
        Assert.Equal(ModOutcome.CopiedDisabled, dhOnly["dh.jar"].Outcome);
        Assert.Contains("Sodium 0.8", dhOnly["dh.jar"].Reason);

        var newer = Resolve(new() { ["dh.jar"] = Kit.FabricJar("distanthorizons", "2.3.3") }, "1.21.11", sodium08);
        Assert.Equal(ModOutcome.Copied, newer["dh.jar"].Outcome);

        var oldSodium = Resolve(new() { ["dh.jar"] = Kit.FabricJar("distanthorizons", "2.3.2") });   // 1.21.1: Sodium 0.6.13
        Assert.Equal(ModOutcome.Copied, oldSodium["dh.jar"].Outcome);

        var optifine = Resolve(new() { ["OptiFine_1.21.1_HD_U_J1.jar"] = Kit.Zip(Kit.Text("optifine/Installer.class", "x")) });
        Assert.Equal(ModOutcome.WrongLoader, optifine["OptiFine_1.21.1_HD_U_J1.jar"].Outcome);
        Assert.Contains("Origin uses Sodium + Iris", optifine["OptiFine_1.21.1_HD_U_J1.jar"].Reason);
    }

    [Fact]
    public void AModBuiltForAnotherMinecraftOrLoaderIsNotCopiedAndSaysWhy()
    {
        var d = Resolve(new()
        {
            ["old.jar"] = Kit.FabricJar("oldmod", "1.0", minecraft: "~1.20.4"),
            ["forge.jar"] = Kit.Zip(Kit.Text("META-INF/mods.toml", "[[mods]]\nmodId=\"forgemod\"\n")),
            ["neo.jar"] = Kit.Zip(Kit.Text("META-INF/neoforge.mods.toml", "[[mods]]\nmodId=\"neomod\"\n")),
            ["quilt.jar"] = Kit.Zip(Kit.Text("quilt.mod.json", Quilt("qmod"))),
            ["legacy.jar"] = Kit.Zip(Kit.Text("mcmod.info", """[{"modid":"legacy"}]""")),
            ["plain.jar"] = Kit.Zip(Kit.Text("a.txt", "x")),
            ["broken.jar"] = new byte[] { 9, 9, 9 },
        });

        Assert.Equal(ModOutcome.WrongMinecraft, d["old.jar"].Outcome);
        Assert.Contains("~1.20.4", d["old.jar"].Reason);
        Assert.Contains("1.21.1", d["old.jar"].Reason);
        Assert.Equal(ModOutcome.WrongLoader, d["forge.jar"].Outcome);
        Assert.Contains("Forge", d["forge.jar"].Reason);
        Assert.Contains("NeoForge", d["neo.jar"].Reason);
        Assert.Contains("Quilt", d["quilt.jar"].Reason);
        Assert.Equal(ModOutcome.WrongLoader, d["legacy.jar"].Outcome);
        Assert.Equal(ModOutcome.WrongLoader, d["plain.jar"].Outcome);
        Assert.Equal(ModOutcome.Unreadable, d["broken.jar"].Outcome);
        Assert.All(d.Values, x => Assert.Null(x.DestFileName));
    }

    [Fact]
    public void TwoVersionsOfOneModImportTheHigherAndListTheOther()
    {
        var d = Resolve(new()
        {
            ["cool-1.2.0.jar"] = Kit.FabricJar("cool", "1.2.0"),
            ["cool-1.10.0.jar"] = Kit.FabricJar("cool", "1.10.0"),
            ["cool-0.9.jar"] = Kit.FabricJar("cool", "0.9"),
        });

        Assert.Equal(ModOutcome.Copied, d["cool-1.10.0.jar"].Outcome);          // 1.10 > 1.2, numerically
        Assert.Equal(ModOutcome.Duplicate, d["cool-1.2.0.jar"].Outcome);
        Assert.Equal(ModOutcome.Duplicate, d["cool-0.9.jar"].Outcome);
        Assert.Contains("Duplicate", d["cool-1.2.0.jar"].Reason);
    }

    [Fact]
    public void AJarDisabledInTheSourceArrivesDisabled()
    {
        var d = Resolve(new()
        {
            ["off.jar.disabled"] = Kit.FabricJar("off", "1.0"),
            ["on.jar"] = Kit.FabricJar("on", "1.0"),
        });

        Assert.Equal(ModOutcome.CopiedAsBefore, d["off.jar.disabled"].Outcome);
        Assert.Equal("off.jar.disabled", d["off.jar.disabled"].DestFileName);
        Assert.Equal("on.jar", d["on.jar"].DestFileName);
    }

    [Fact]
    public void On189EveryFabricModIsListedAsNeedingForgeAndNotCopied()
    {
        var d = Resolve(new()
        {
            ["fabric-a.jar"] = Kit.FabricJar("a", "1.0"),
            ["fabric-b.jar"] = Kit.FabricJar("b", "1.0", minecraft: "1.8.9"),
            ["quilt.jar"] = Kit.Zip(Kit.Text("quilt.mod.json", Quilt("q"))),
            ["forge189.jar"] = Kit.Zip(Kit.Text("mcmod.info", """[{"modid":"old","mcversion":"1.8.9"}]""")),
            ["forge112.jar"] = Kit.Zip(Kit.Text("mcmod.info", """[{"modid":"old2","mcversion":"1.12.2"}]""")),
        }, "1.8.9");

        foreach (var name in new[] { "fabric-a.jar", "fabric-b.jar", "quilt.jar" })
        {
            Assert.Equal(ModOutcome.WrongLoader, d[name].Outcome);
            Assert.Contains("1.8.9 runs on Forge in Origin", d[name].Reason);
            Assert.Null(d[name].DestFileName);
        }
        Assert.Equal(ModOutcome.Copied, d["forge189.jar"].Outcome);              // a real 1.8.9 Forge mod goes across
        Assert.Equal(ModOutcome.WrongMinecraft, d["forge112.jar"].Outcome);
    }

    [Fact]
    public void ModsAreIdentifiedByMetadataNotByFileNameSoAnAddonIsNotTheBuiltInItNamesAfter()
    {
        var d = Resolve(new() { ["sodium-extra-fabric-0.5.jar"] = Kit.FabricJar("sodium-extra", "0.5.0") });
        Assert.Equal(ModOutcome.Copied, d["sodium-extra-fabric-0.5.jar"].Outcome);
    }

    [Fact]
    public void ModsInSubfoldersAndCredentialNamedJarsAreLeftOutAndTheLatterReportedByNameOnly()
    {
        var dir = Path.Combine(_root, "mods-guard");
        Kit.WriteBytes(dir, "authme-1.0.jar", Kit.FabricJar("authme", "1.0"));
        Kit.WriteBytes(dir, "sub/hidden.jar", Kit.FabricJar("hidden", "1.0"));
        Kit.WriteBytes(dir, "fine.jar", Kit.FabricJar("fine", "1.0"));
        var fs = new SourceFs();

        var mods = ModSetResolver.ReadSourceMods(dir, fs, CancellationToken.None);

        Assert.Equal(new[] { "fine.jar" }, mods.Select(m => m.FileName));
        Assert.Contains("authme-1.0.jar", fs.SkippedForSafety);
    }
}
