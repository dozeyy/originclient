using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Components;
using OriginLauncher.App.Core.Loaders;
using Xunit;
using static OriginLauncher.Tests.Compat.CompatKit;

namespace OriginLauncher.Tests.Compat;

/// <summary>
/// The player's own mods win over the ones Origin manages (Will, 2026-10-25): one profile launch's plan. Origin's
/// copy is kept out of that launch (never rewritten), Sodium and Iris stay a pair, and Origin's own core never yields.
/// </summary>
public class PlayerOverridesTests
{
    // 1.21.1's shape: Origin's mod nests Sodium + Iris; Fabric API ships top-level.
    private static readonly ModFile OriginClient = File(Info("originclient", "1.0.0", nested: new[]
    {
        new NestedMod("sodium", "0.6.13+mc1.21.1", "Sodium"),
        new NestedMod("iris", "1.8.8+mc1.21.1", "Iris") { Depends = new[] { Dep("sodium", "0.6.13+mc1.21.1") } },
        new NestedMod("modmenu", "11.0.3", "Mod Menu"),
    }), ModRole.Managed);

    private static readonly ModFile OriginApi = File(Info("fabric-api", "0.116.6+1.21.1", name: "Fabric API"), ModRole.Managed);

    private static readonly IReadOnlyList<VettedConfiguration> Vetted = new[]
    {
        new VettedConfiguration("sodium-iris-0.6.5+1.8.1", "1.21.1", 1, "0.6.5+1.8.1", new[]
        {
            new ComponentFile("sodium", "sodium", "0.6.5+mc1.21.1", new PerfMod("0.6.5+mc1.21.1", "u", "sodium-fabric-0.6.5+mc1.21.1.jar")),
            new ComponentFile("iris", "iris", "1.8.1+mc1.21.1", new PerfMod("1.8.1+mc1.21.1", "u", "iris-fabric-1.8.1+mc1.21.1.jar")),
        }, "test"),
    };

    private static PlayerOverridePlan Plan(params ModFile[] player) =>
        PlayerOverrides.Plan("1.21.1", new[] { OriginClient, OriginApi }, player, Vetted);

    [Fact]
    public void ThePlayersNewerModOverATopLevelOneOfOriginsWinsAndOriginsFileIsLeftOutOfTheLaunch()
    {
        var api = Mod("fabric-api", "0.117.0+1.21.1", mc: "~1.21.1");
        var plan = Plan(api);

        Assert.Equal(new[] { "fabric-api" }, plan.OverriddenIds);
        Assert.True(plan.Skips(OriginApi.Path));
        Assert.False(plan.Skips(api.Path));
        Assert.Null(plan.JvmArgument);                       // a top-level copy is simply not linked into the launch
    }

    [Fact]
    public void ThePlayersOwnSodiumAndIrisBothWinAndOriginsNestedPairIsOutranked()
    {
        var plan = Plan(Mod("sodium", "0.6.0+mc1.21.1"), Mod("iris", "1.8.0+mc1.21.1"));

        Assert.Equal(new[] { "iris", "sodium" }, plan.OverriddenIds);
        Assert.Contains("sodium", plan.StrippedIds);         // the loader takes the top-level copy over the nested one
        Assert.Contains("iris", plan.StrippedIds);
        Assert.Null(plan.PairId);
        Assert.True(plan.PlayerDecidesPair);                 // Play does not swap in a vetted pair over the player's
        Assert.Null(plan.JvmArgument);
        Assert.False(plan.Skips(OriginClient.Path));         // Origin's own mod always loads
    }

    [Fact]
    public void ASodiumWithAVettedIrisPartnerSelectsThatPairAndKeepsOriginsMismatchedIrisOut()
    {
        var plan = Plan(Mod("sodium", "0.6.5+mc1.21.1"));

        Assert.Equal("0.6.5+1.8.1", plan.PairId);
        Assert.Contains("iris", plan.DisabledIds);           // never Origin's 1.8.8 next to the player's 0.6.5
        Assert.Contains(plan.Log, l => l.Contains("tested partner"));
    }

    [Fact]
    public void WhenOriginsSideAlreadyIsTheVettedPartnerNothingIsKeptOut()
    {
        // The launch after the swap: the vetted pair's Iris is one of Origin's top-level files.
        var vettedIris = File(Info("iris", "1.8.1+mc1.21.1"), ModRole.Managed);
        var plan = PlayerOverrides.Plan("1.21.1", new[] { OriginClient, OriginApi, vettedIris },
            new[] { Mod("sodium", "0.6.5+mc1.21.1") }, Vetted);

        Assert.Null(plan.PairId);
        Assert.Empty(plan.DisabledIds);
        Assert.False(plan.Skips(vettedIris.Path));
    }

    [Fact]
    public void ASodiumWithNoTestedIrisTurnsShadersOffForTheProfileAndSaysSo()
    {
        var plan = Plan(Mod("sodium", "0.6.9+mc1.21.1"));

        Assert.Equal("-Dfabric.debug.disableModIds=iris", plan.JvmArgument);
        Assert.Contains(plan.Notices, n => n.StartsWith("Shaders are off for this profile"));
        Assert.Equal(new[] { "sodium" }, plan.OverriddenIds);
    }

    [Fact]
    public void AnIrisAloneIsSetAsideBecauseItNeedsTheSodiumItWasMadeFor()
    {
        var iris = Mod("iris", "1.8.0+mc1.21.1");
        var plan = Plan(iris);

        Assert.Empty(plan.OverriddenIds);
        Assert.True(plan.Skips(iris.Path));
        Assert.Contains(plan.Notices, n => n.Contains("needs the Sodium it was made for"));
    }

    [Fact]
    public void ACopyMadeForAnotherMinecraftIsSetAsideAndOriginsIsUsed()
    {
        var other = Mod("modmenu", "7.2.2", mc: "1.20.1");
        var plan = Plan(other);

        Assert.True(plan.Skips(other.Path));
        Assert.Empty(plan.OverriddenIds);
        Assert.Contains(plan.Notices, n => n.Contains("made for Minecraft"));
    }

    [Fact]
    public void TheVerySameVersionIsSetAsideQuietly()
    {
        var same = Mod("modmenu", "11.0.3");
        var plan = Plan(same);

        Assert.True(plan.Skips(same.Path));
        Assert.Empty(plan.Notices);
        Assert.Empty(plan.OverriddenIds);
    }

    [Fact]
    public void AnOlderFabricApiOrAnyOriginClientNeverReplacesOrigins()
    {
        var oldApi = Mod("fabric-api", "0.100.0+1.21.1");
        var fake = Mod("originclient", "9.9.9");
        var plan = Plan(oldApi, fake);

        Assert.Empty(plan.OverriddenIds);
        Assert.True(plan.Skips(oldApi.Path));
        Assert.True(plan.Skips(fake.Path));
        Assert.False(plan.Skips(OriginApi.Path));
        Assert.False(PlayerOverrides.MayReplace("fabric-api", "0.100.0", null));   // an unknown version is never "newer"
        Assert.True(PlayerOverrides.MayReplace("modmenu", "11.0.2", "11.0.3"));
    }

    [Fact]
    public void AnOriginModThatRefusesThePlayersVersionIsKeptOutOfTheLaunchAndNamed()
    {
        var hud = File(Info("originhud", "1.0", name: "Origin HUD", needs: new[] { ("modmenu", ">=11.0.0") }), ModRole.Managed);
        var plan = PlayerOverrides.Plan("1.21.1", new[] { OriginClient, OriginApi, hud },
            new[] { Mod("modmenu", "10.0.0") }, Vetted);

        Assert.Equal(new[] { "modmenu" }, plan.OverriddenIds);
        Assert.True(plan.Skips(hud.Path));
        Assert.Contains(plan.Notices, n => n.Contains("Origin HUD") && n.Contains("modmenu 10.0.0"));
    }

    [Fact]
    public void WhenOriginsCoreRefusesThePlayersVersionOriginsCopyStays()
    {
        var core = File(Info("originclient", "1.0.0", needs: new[] { ("cloth-config", ">=15.0.0") }), ModRole.Managed);
        var cloth = File(Info("cloth-config", "15.0.140"), ModRole.Managed);
        var player = Mod("cloth-config", "13.0.0");
        var plan = PlayerOverrides.Plan("1.21.1", new[] { core, cloth }, new[] { player }, null);

        Assert.Empty(plan.OverriddenIds);
        Assert.True(plan.Skips(player.Path));
        Assert.False(plan.Skips(cloth.Path));
    }

    [Fact]
    public void ForgeOneEightNineHasNoPlan() =>
        Assert.True(PlayerOverrides.Plan("1.8.9", new[] { OriginApi }, new[] { Mod("fabric-api", "9.0.0") }).IsEmpty);
}
