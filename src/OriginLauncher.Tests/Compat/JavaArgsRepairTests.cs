using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Models;
using Xunit;

namespace OriginLauncher.Tests.Compat;

// "Reset Java arguments": the crash rule offers it only for the version's OWN arguments, the planner/executor reset
// them through the settings update alone, keep the old value in the same write, and Undo puts it back exactly.
public sealed class JavaArgsRepairTests
{
    private const string Mc = "1.21.1";
    private readonly LauncherSettings _settings = new();
    private int _writes;

    private void Update(Action<LauncherSettings> mutate)
    {
        _writes++;
        mutate(_settings);
    }

    private void Own(string args, bool replace = false) =>
        _settings.JvmArgsByVersion[Mc] = new VersionJvmOptions { Arguments = args, ReplacePresets = replace };

    private RepairResult Reset() =>
        RepairExecutor.ApplySettings(RepairPlanner.ResetJavaArgs(Mc, _settings.JvmArgsFor(Mc), "Java refused them."), Update);

    // ---- planner + executor ----

    [Fact]
    public void Nothing_custom_means_nothing_to_reset()
    {
        Assert.True(RepairPlanner.ResetJavaArgs(Mc, null, "x").IsEmpty);
        Assert.True(RepairPlanner.ResetJavaArgs(Mc, new VersionJvmOptions(), "x").IsEmpty);
        Assert.True(Reset().AllOk);
        Assert.Empty(_settings.JvmArgsBeforeReset);
    }

    [Fact]
    public void Reset_goes_back_to_Origins_own_in_one_write_and_keeps_the_old_value()
    {
        Own("-XX:+UseZGCx\n-Xss2m", replace: true);
        _settings.JvmArgsByVersion["1.20.1"] = new VersionJvmOptions { Arguments = "-Xss4m" };

        var result = Reset();

        Assert.True(result.AllOk);
        Assert.Equal(1, _writes); // the reset and the Undo copy land together
        Assert.Null(_settings.JvmArgsFor(Mc));
        var kept = _settings.JvmArgsBeforeReset[Mc];
        Assert.Equal("-XX:+UseZGCx\n-Xss2m", kept.Arguments);
        Assert.True(kept.ReplacePresets);
        Assert.Equal("-Xss4m", _settings.JvmArgsFor("1.20.1")!.Arguments); // another version is never touched
        Assert.True(RepairExecutor.CanUndoJavaArgsReset(_settings, Mc));
    }

    [Fact]
    public void Undo_restores_the_old_arguments_exactly()
    {
        Own("-XX:+UseZGCx -Xss2m", replace: true);
        Reset();

        Assert.Equal(JavaArgsUndoOutcome.Restored, RepairExecutor.UndoJavaArgsReset(Mc, Update));

        var back = _settings.JvmArgsFor(Mc)!;
        Assert.Equal("-XX:+UseZGCx -Xss2m", back.Arguments);
        Assert.True(back.ReplacePresets);
        Assert.Empty(_settings.JvmArgsBeforeReset);
        Assert.False(RepairExecutor.CanUndoJavaArgsReset(_settings, Mc));
        Assert.Equal(JavaArgsUndoOutcome.NothingToUndo, RepairExecutor.UndoJavaArgsReset(Mc, Update));
    }

    [Fact]
    public void Undo_never_overwrites_arguments_typed_since()
    {
        Own("-XX:+UseZGCx");
        Reset();
        Own("-Xss8m"); // the player typed new ones

        Assert.False(RepairExecutor.CanUndoJavaArgsReset(_settings, Mc));
        Assert.Equal(JavaArgsUndoOutcome.ChangedSince, RepairExecutor.UndoJavaArgsReset(Mc, Update));
        Assert.Equal("-Xss8m", _settings.JvmArgsFor(Mc)!.Arguments);
    }

    [Fact]
    public void A_reset_of_the_launched_arguments_leaves_arguments_typed_since_the_launch_alone()
    {
        // HomePage plans from RunningGame.LaunchedJvmArgs (a copy taken at launch), not from Settings at click time.
        Own("-XX:+UseZGCx");
        var launched = new VersionJvmOptions { Arguments = _settings.JvmArgsFor(Mc)!.Arguments };
        Own("-Xss8m"); // typed in Settings while the game was starting
        var plan = RepairPlanner.ResetJavaArgs(Mc, launched, "x");

        var result = RepairExecutor.ApplySettings(plan, Update);

        Assert.False(result.AllOk);
        Assert.Equal("-Xss8m", _settings.JvmArgsFor(Mc)!.Arguments);
        Assert.Empty(_settings.JvmArgsBeforeReset);
    }

    [Fact]
    public void A_file_step_is_refused_by_the_settings_writer_and_a_setting_step_by_the_file_writer()
    {
        var mod = new ModFile(@"C:\x\a.jar", "a.jar", true, ModRole.Profile, new ModInfo { FileName = "a.jar" });
        var files = new RepairPlan(new RepairStep[] { new DisableModStep(mod, "x", Certainty.Confirmed) }, Array.Empty<UnfixableFinding>());
        Assert.False(RepairExecutor.ApplySettings(files, Update).AllOk);
        Assert.Equal(0, _writes);

        Own("-Xss2m");
        var setting = RepairPlanner.ResetJavaArgs(Mc, _settings.JvmArgsFor(Mc), "x");
        var context = new RepairContext(new CompatPlatform(Mc, LoaderFamily.Fabric, null, ""), @"C:\x", new[] { @"C:\x" });
        Assert.False(RepairExecutor.Apply(setting, context).AllOk);
        Assert.Equal("-Xss2m", _settings.JvmArgsFor(Mc)!.Arguments);
    }

    // ---- the crash rule decides when the button is offered ----

    private static CrashVerdict Crash(string log, string? custom) =>
        CrashEngine.Diagnose(new CrashEvidence(1, false, true, log, null, null, null, [], 4096, 16384) { CustomJvmArgs = custom }, false);

    private const string UnknownOption =
        "Unrecognized VM option 'UseZGCx'\nError: Could not create the Java Virtual Machine.\nError: A fatal exception has occurred. Program will exit.";

    [Fact]
    public void An_unknown_option_from_the_players_own_arguments_offers_the_reset()
    {
        var verdict = Crash(UnknownOption, "-XX:+UseZGCx -Xss2m");
        Assert.Equal("jvm-start", verdict.Finding.Rule);
        Assert.Equal(CrashFixKind.ResetJavaArgs, verdict.SafeFix?.Kind);
        Assert.Contains("your Java arguments", verdict.Finding.LikelyCause);
    }

    [Theory]
    [InlineData(null)]           // no arguments of their own: Origin's must never be "reset"
    [InlineData("-Xss2m")]        // their arguments don't contain the refused option
    public void An_unknown_option_that_is_not_the_players_offers_no_button(string? custom) =>
        Assert.Null(Crash(UnknownOption, custom).SafeFix);

    [Fact]
    public void A_bad_heap_size_from_the_players_own_arguments_offers_the_reset()
    {
        const string log = "Invalid maximum heap size: -Xmx4\nError: Could not create the Java Virtual Machine.";
        Assert.Equal(CrashFixKind.ResetJavaArgs, Crash(log, "-Xmx4").SafeFix?.Kind);
        Assert.Null(Crash(log, null).SafeFix); // Origin's memory setting: lowering memory is the advice, no reset
    }

    [Fact]
    public void A_missing_Java_is_not_blamed_on_the_arguments()
    {
        var verdict = Crash("'javaw' is not recognized as an internal or external command", "-Xss2m");
        Assert.Equal("jvm-start", verdict.Finding.Rule);
        Assert.Null(verdict.SafeFix);
    }

    [Fact]
    public void A_Java_start_failure_with_arguments_of_their_own_offers_the_reset()
    {
        var verdict = Crash("Error occurred during initialization of VM\nMultiple garbage collectors selected", "-XX:+UseZGC");
        Assert.Equal(CrashFixKind.ResetJavaArgs, verdict.SafeFix?.Kind);
        // Only likely, not certain: a broken Java install fails the same way.
        Assert.Equal(CrashConfidence.Strong, verdict.Finding.Confidence);
        Assert.Equal(CrashConfidence.Confirmed, Crash(UnknownOption, "-XX:+UseZGCx").Finding.Confidence);
    }
}
