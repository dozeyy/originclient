using OriginLauncher.App.Core.Launch;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>Crash cause -> headline -> action, the memory bump, the show/no-show gate, the one-retry guard.</summary>
public class CrashClassifierTests
{
    private static readonly CrashAnalysis NoBlame = new([], "Minecraft crashed.", null);

    private static CrashDiagnosis Classify(string evidence, int exit = 1, int ramMb = 4096, int totalMb = 16384,
        CrashAnalysis? analysis = null, Func<CrashAction, bool>? tried = null) =>
        CrashClassifier.Classify(analysis ?? NoBlame, evidence, exit, ramMb, totalMb, tried);

    [Fact]
    public void OutOfMemory_offers_raising_memory_by_two_gigabytes()
    {
        var d = Classify("Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space");
        Assert.Equal(CrashCause.OutOfMemory, d.Cause);
        Assert.Equal(CrashAction.RaiseMemory, d.Action);
        Assert.Equal(6144, d.NewRamMb);
        Assert.Contains("ran out of memory", d.Headline);
        Assert.Contains("6 GB", d.Explanation);
    }

    [Theory]
    [InlineData("Error occurred during initialization of VM\nCould not reserve enough space for 8388608KB object heap")]
    [InlineData("There is insufficient memory for the Java Runtime Environment to continue.")]
    [InlineData("java.lang.OutOfMemoryError: GC overhead limit exceeded")]
    public void OutOfMemory_wording_variants(string text) =>
        Assert.Equal(CrashCause.OutOfMemory, Classify(text).Cause);

    [Fact]
    public void OutOfMemory_at_the_cap_does_not_offer_more_memory()
    {
        // 16 GB PC: cap = (16384 - 2048) = 14336
        var d = Classify("java.lang.OutOfMemoryError", ramMb: 14336, totalMb: 16384);
        Assert.Equal(CrashCause.OutOfMemory, d.Cause);
        Assert.Equal(CrashAction.OpenLogs, d.Action);
        Assert.Null(d.NewRamMb);
    }

    [Fact]
    public void Java_version_mismatch_is_a_java_problem()
    {
        var d = Classify("java.lang.UnsupportedClassVersionError: net/fabricmc/loader/impl/launch/knot/KnotClient has been compiled by a more recent version of the Java Runtime");
        Assert.Equal(CrashCause.JavaProblem, d.Cause);
        Assert.Equal(CrashAction.OpenLogs, d.Action);
    }

    [Theory]
    [InlineData("GLFW error 65542: WGL: The driver does not appear to support OpenGL")]
    [InlineData("# Problematic frame:\n# C  [nvoglv64.dll+0xabc]")]
    [InlineData("Pixel format not accelerated")]
    public void Graphics_driver_signals(string text)
    {
        var d = Classify(text);
        Assert.Equal(CrashCause.GraphicsDriver, d.Cause);
        Assert.Equal("Your graphics driver rejected something", d.Headline);
        Assert.Equal(CrashAction.Relaunch, d.Action);
    }

    [Fact]
    public void System_details_mentioning_opengl_are_not_a_graphics_crash()
    {
        // Every crash report lists "OpenGL version" in System Details.
        var d = Classify("-- System Details --\n\tOpenGL version: 4.6.0 NVIDIA 551.23\n\tGL Caps: Using framebuffer");
        Assert.NotEqual(CrashCause.GraphicsDriver, d.Cause);
    }

    [Fact]
    public void Corrupt_jar_asks_for_a_repair_relaunch()
    {
        var d = Classify("Error: Invalid or corrupt jarfile C:\\x\\client.jar");
        Assert.Equal(CrashCause.DamagedFiles, d.Cause);
        Assert.Equal(CrashAction.Relaunch, d.Action);
    }

    [Fact]
    public void One_external_mod_names_it_and_offers_to_relaunch_without_it()
    {
        var blame = new CrashAnalysis([new CrashCulprit("shiny_mod", "shiny-mod-1.0.jar", true)],
            "Minecraft couldn't start because of an incompatible mod.", "x");
        var d = Classify("", analysis: blame);
        Assert.Equal(CrashCause.ModProblem, d.Cause);
        Assert.Equal("A mod didn't load: shiny mod", d.Headline);
        Assert.Equal(CrashAction.DisableMod, d.Action);
        Assert.Equal("shiny-mod-1.0.jar", d.ModJar);
        Assert.StartsWith("Relaunch without", d.ActionLabel);
    }

    [Fact]
    public void Several_external_mods_fall_back_to_origin_only()
    {
        var blame = new CrashAnalysis([new CrashCulprit("a", "a.jar", true), new CrashCulprit("b", "b.jar", true)], "r", null);
        var d = Classify("", analysis: blame);
        Assert.Equal(CrashAction.OriginOnly, d.Action);
        Assert.Equal("Relaunch with Origin only", d.ActionLabel);
    }

    [Fact]
    public void A_built_in_mod_failure_does_not_blame_the_players_files()
    {
        var blame = new CrashAnalysis([new CrashCulprit("originclient", "originclient.jar", false)], "r", null);
        var d = Classify("", analysis: blame);
        Assert.Equal(CrashAction.OpenLogs, d.Action);
        Assert.Contains("part of Origin", d.Explanation);
    }

    [Fact]
    public void Out_of_memory_beats_a_blamed_mod()
    {
        var blame = new CrashAnalysis([new CrashCulprit("a", "a.jar", true)], "r", null);
        Assert.Equal(CrashCause.OutOfMemory, Classify("java.lang.OutOfMemoryError", analysis: blame).Cause);
    }

    [Fact]
    public void Native_exit_code_without_a_log_is_a_native_crash()
    {
        var d = Classify("", exit: unchecked((int)0xC0000005));
        Assert.Equal(CrashCause.NativeCrash, d.Cause);
        Assert.Equal(CrashAction.OpenLogs, d.Action);
    }

    [Fact]
    public void Nothing_recognised_is_unknown_with_origin_only()
    {
        var d = Classify("some unrelated output");
        Assert.Equal(CrashCause.Unknown, d.Cause);
        Assert.Equal(CrashAction.OriginOnly, d.Action);
    }

    [Fact]
    public void A_spent_retry_turns_the_main_button_into_open_logs()
    {
        var d = Classify("java.lang.OutOfMemoryError", tried: _ => true);
        Assert.Equal(CrashCause.OutOfMemory, d.Cause);
        Assert.Equal(CrashAction.OpenLogs, d.Action);
        Assert.Null(d.NewRamMb);
        Assert.Contains("already tried", d.Explanation);
    }

    [Theory]
    [InlineData(4096, 16384, 6144)]
    [InlineData(1024, 16384, 3072)]
    [InlineData(13000, 16384, 14336)] // clamped to the cap
    [InlineData(14336, 16384, null)]  // at the cap
    [InlineData(2048, 4096, null)]    // tiny PC: cap is the 2 GB floor
    public void Memory_bump(int current, int total, int? expected) =>
        Assert.Equal(expected, MemoryBump.Next(current, total));

    [Fact]
    public void Gate_never_shows_for_a_normal_quit_or_a_player_stop()
    {
        var d = Classify("java.lang.OutOfMemoryError");
        Assert.False(CrashGate.ShouldShow(0, false, true, true, d));
        Assert.False(CrashGate.ShouldShow(1, true, true, true, d));
    }

    [Fact]
    public void Gate_shows_boot_crashes_but_late_exits_need_evidence()
    {
        var unknown = Classify("nothing");
        Assert.True(CrashGate.ShouldShow(1, false, crashedWhileStarting: true, hasFreshCrashFile: false, unknown));
        Assert.False(CrashGate.ShouldShow(1, false, crashedWhileStarting: false, hasFreshCrashFile: false, unknown));
        Assert.True(CrashGate.ShouldShow(1, false, false, hasFreshCrashFile: true, unknown));
        Assert.True(CrashGate.ShouldShow(1, false, false, false, Classify("java.lang.OutOfMemoryError")));
    }

    [Fact]
    public void Retry_guard_allows_one_retry_until_cleared_or_expired()
    {
        var guard = new CrashRetryGuard();
        var t = new DateTime(2026, 10, 20, 12, 0, 0, DateTimeKind.Utc);
        Assert.False(guard.AnyTried("steve", t));
        Assert.True(guard.TryBegin("steve", CrashAction.RaiseMemory, t));
        Assert.True(guard.AnyTried("steve", t.AddMinutes(1)));
        Assert.False(guard.TryBegin("steve", CrashAction.OriginOnly, t.AddMinutes(1)));
        Assert.True(guard.TryBegin("alex", CrashAction.OriginOnly, t)); // other accounts are independent

        guard.Clear("steve"); // the game booted
        Assert.False(guard.AnyTried("steve", t.AddMinutes(2)));
        Assert.True(guard.TryBegin("steve", CrashAction.OriginOnly, t.AddMinutes(2)));

        Assert.False(guard.AnyTried("steve", t.AddMinutes(2) + CrashRetryGuard.Window)); // expired
    }
}
