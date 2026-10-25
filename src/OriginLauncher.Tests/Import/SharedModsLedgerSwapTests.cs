using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Import;
using Xunit;

namespace OriginLauncher.Tests.Import;

// A swap puts an imported newer copy in place of Origin's older one. Whether another of Origin's mods still has what
// it needs after the swap is decided by the shared dependency rules (DependencyRules.Evaluate), not a loop of its own.
public sealed class SharedModsLedgerSwapTests : IDisposable
{
    private readonly string _mods = Path.Combine(Path.GetTempPath(), "origin-ledger-" + Guid.NewGuid().ToString("N"));

    public SharedModsLedgerSwapTests()
    {
        Directory.CreateDirectory(_mods);
        ModFolderScanner.ClearCache();
    }

    public void Dispose()
    {
        try { Directory.Delete(_mods, true); } catch (IOException) { /* temp */ }
    }

    private void Origin(string file, string id, string version, Dictionary<string, object>? depends = null, string[]? provides = null) =>
        File.WriteAllBytes(Path.Combine(_mods, file), Kit.FabricJar(id, version, depends: depends, provides: provides));

    private static ModDecision Import(string id, string version) =>
        new($"{id}-{version}.jar", id, id, version, ModOutcome.Copied, null, $"{id}-{version}.jar", null);

    private static ModDependency[] Needs(string id, string range) => new[] { new ModDependency(id, new[] { range }) };

    [Fact]
    public void ASwapGoesAheadWhenNothingElseInOriginsFolderObjects()
    {
        Origin("lib-1.0.jar", "lib", "1.0.0");
        Origin("other-1.0.jar", "other", "1.0", depends: new() { ["lib"] = "*" });
        var landing = SharedModsLedger.Read(_mods).Decide(Import("lib", "2.0.0"), Needs("lib", ">=2.0.0"));
        Assert.Null(landing.Holder);
        Assert.NotNull(landing.Replaces);
    }

    [Fact]
    public void ASwapIsRefusedWhenAnotherOriginModNeedsTheOlderCopy()
    {
        Origin("lib-1.0.jar", "lib", "1.0.0");
        Origin("other-1.0.jar", "other", "1.0", depends: new() { ["lib"] = "<2.0.0" });
        var landing = SharedModsLedger.Read(_mods).Decide(Import("lib", "2.0.0"), Needs("lib", ">=2.0.0"));
        Assert.Equal(SharedModsLedger.InOrigin, landing.Holder);
        Assert.Contains("another of Origin's mods needs that one", landing.Note);
    }

    [Fact]
    public void ASwapIsNotBlamedForAProblemTheFolderAlreadyHad()
    {
        // "broken" already misses a mod that nothing here provides; the swap neither causes nor cures that.
        Origin("lib-1.0.jar", "lib", "1.0.0");
        Origin("broken-1.0.jar", "broken", "1.0", depends: new() { ["lib"] = ">=1.0.0", ["gone"] = ">=1.0.0" });
        var landing = SharedModsLedger.Read(_mods).Decide(Import("lib", "2.0.0"), Needs("lib", ">=2.0.0"));
        Assert.Null(landing.Holder);
    }

    [Fact]
    public void ATurnedOffDependantDoesNotHoldASwapBack()
    {
        Origin("lib-1.0.jar", "lib", "1.0.0");
        File.WriteAllBytes(Path.Combine(_mods, "other-1.0.jar.disabled"),
            Kit.FabricJar("other", "1.0", depends: new() { ["lib"] = "<2.0.0" }));
        var landing = SharedModsLedger.Read(_mods).Decide(Import("lib", "2.0.0"), Needs("lib", ">=2.0.0"));
        Assert.Null(landing.Holder);
    }

    [Fact]
    public void AnIdTheOlderCopyProvidedAndTheNewOneDoesNotStillCountsAsHeldBack()
    {
        // other needs "lib-core"; Origin's lib provides it; the swapped-in lib (no provides) would drop it.
        File.WriteAllBytes(Path.Combine(_mods, "lib-1.0.jar"), Kit.FabricJar("lib", "1.0.0", provides: new[] { "lib-core" }));
        Origin("other-1.0.jar", "other", "1.0", depends: new() { ["lib-core"] = "*" });
        var landing = SharedModsLedger.Read(_mods).Decide(Import("lib", "2.0.0"), Needs("lib", ">=2.0.0"));
        Assert.Equal(SharedModsLedger.InOrigin, landing.Holder);
    }
}
