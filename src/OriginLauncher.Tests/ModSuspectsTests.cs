using OriginLauncher.App.Core.Launch;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// After "Try once without my mods" starts the game (Core/Launch/ModSuspects): which of the player's mods Home names
/// to check first, and the line it shows.
/// </summary>
public class ModSuspectsTests
{
    private static readonly DateTime T0 = new(2026, 11, 1, 0, 0, 0, DateTimeKind.Utc);

    private static CrashFinding Finding(params CrashCulprit[] culprits) =>
        new("unknown", CrashCause.Unknown, CrashConfidence.Possible, culprits, "CRASH", "h", "w", "c", "f", null, []);

    private static CrashVerdict Verdict(CrashFinding finding, params CrashFinding[] others) =>
        new(true, "crash", finding, others) { OfferTryWithoutAddedMods = true };

    private static CrashModEntry Mine(string jar) => new(jar.Replace(".jar", ""), jar, true);

    [Fact]
    public void Named_mods_come_first_then_the_newest_added()
    {
        var mods = new[] { Mine("old.jar"), Mine("newer.jar"), Mine("newest.jar"), Mine("named.jar"), Mine("mid.jar"),
            new CrashModEntry("originclient", "originclient.jar", false) };
        var arrived = new Dictionary<string, DateTime>
        {
            ["old.jar"] = T0, ["mid.jar"] = T0.AddDays(1), ["newer.jar"] = T0.AddDays(2), ["newest.jar"] = T0.AddDays(3),
            ["named.jar"] = T0,
        };
        var verdict = Verdict(Finding(), Finding(new CrashCulprit("named", "named.jar", true)));

        var (jars, added) = ModSuspects.Pick(verdict, mods, j => arrived[j]);

        Assert.Equal(["named.jar", "newest.jar", "newer.jar"], jars);
        Assert.Equal(5, added);
        Assert.Equal("Running without your mods - one of them caused the crash. Try turning off named, newest or newer first, on the Mods tab.",
            ModSuspects.BootedLine(jars, added));
    }

    [Fact]
    public void Origin_jars_and_jars_the_launch_did_not_load_are_never_named()
    {
        var mods = new[] { Mine("mine.jar"), new CrashModEntry("sodium", "originclient.jar", false) };
        var verdict = Verdict(Finding(new CrashCulprit("sodium", "originclient.jar", false),
            new CrashCulprit("ghost", "ghost.jar", true)));

        var (jars, added) = ModSuspects.Pick(verdict, mods, _ => T0);

        Assert.Equal(["mine.jar"], jars);
        Assert.Equal("Running without your mods - mine caused the crash. Turn it off on the Mods tab.",
            ModSuspects.BootedLine(jars, added));
    }

    [Fact]
    public void A_short_list_names_every_mod_and_no_mods_falls_back_to_one_at_a_time()
    {
        Assert.Equal("Running without your mods - one of them caused the crash: a or b. Turn them off one at a time on the Mods tab.",
            ModSuspects.BootedLine(["a.jar", "b.jar"], 2));
        Assert.Equal("Running without your mods - one of them caused the crash. Turn them off one at a time on the Mods tab.",
            ModSuspects.BootedLine([], 0));
    }
}
