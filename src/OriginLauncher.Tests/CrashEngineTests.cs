using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Models;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// Crash Report V3 (Core/Launch/CrashEngine + CrashRules): crash vs normal close, cause, confidence word, the safe-fix
/// policy (a fix button only at strong or confirmed), the memory bump and the one-retry guard. The real-log cases live
/// in the corpus (CrashCorpusTests); these pin each rule's edges with short snippets.
/// </summary>
public class CrashEngineTests
{
    private static readonly CrashModEntry Added = new("somemod", "somemod-1.0.0.jar", true, ["com.example.somemod"]);
    private static readonly CrashModEntry Other = new("othermod", "othermod-2.0.jar", true, ["net.other"]);
    private static readonly CrashModEntry Origin = new("originclient", "originclient.jar", false);

    private static CrashVerdict V(string? log = null, string? crash = null, string? hsErr = null, int exit = 1,
        bool starting = true, bool stopped = false, int ram = 4096, int total = 16384,
        IReadOnlyList<CrashModEntry>? mods = null, bool tried = false, string? latest = null) =>
        CrashEngine.Diagnose(new CrashEvidence(exit, stopped, starting, log, latest, crash, hsErr,
            mods ?? [Added, Origin], ram, total), tried);

    // ---- crash vs normal close (decided first) ---------------------------------------------------------------

    [Fact]
    public void A_normal_quit_or_a_player_stop_is_never_a_crash()
    {
        Assert.False(V("java.lang.OutOfMemoryError", exit: 0).IsCrash);
        Assert.False(V("java.lang.OutOfMemoryError", crash: "---- Minecraft Crash Report ----", stopped: true).IsCrash);
    }

    [Fact]
    public void A_boot_exit_needs_evidence_and_a_late_exit_needs_a_strong_cause()
    {
        Assert.False(V("[main/INFO]: Loading Minecraft 1.21.1").IsCrash);                  // boot killed, nothing logged
        Assert.True(V("Exception in thread \"main\" java.lang.IllegalStateException: x").IsCrash); // boot, error logged
        Assert.False(V("Exception in thread \"main\" java.lang.IllegalStateException: x", starting: false).IsCrash);
        Assert.True(V(crash: "---- Minecraft Crash Report ----\nDescription: x", starting: false).IsCrash);
        Assert.True(V("java.lang.OutOfMemoryError: Java heap space", starting: false).IsCrash);
        Assert.True(V(exit: unchecked((int)0xC0000005), starting: false).IsCrash);
    }

    [Fact]
    public void Minecraft_logging_its_own_quit_is_not_a_crash_whatever_the_exit_code()
    {
        var v = V(latest: "[Render thread/INFO]: Stopping!\n[Render thread/INFO]: Saving worlds", exit: 1, starting: false);
        Assert.False(v.IsCrash);
        Assert.Contains("normal quit", v.CloseReason);
    }

    [Fact]
    public void A_confirmed_cause_wins_over_a_stopping_line()
    {
        var v = V("Error occurred during initialization of VM\nCould not create the Java Virtual Machine.",
            latest: "[Render thread/INFO]: Stopping!");
        Assert.True(v.IsCrash);
        Assert.Equal("jvm-start", v.Finding.Rule);
    }

    // ---- the JVM and memory ------------------------------------------------------------------------------------

    [Fact]
    public void Heap_out_of_memory_is_confirmed_and_offers_two_more_gigabytes()
    {
        var v = V("java.lang.OutOfMemoryError: Java heap space");
        Assert.Equal(CrashCause.OutOfMemory, v.Finding.Cause);
        Assert.Equal(CrashConfidence.Confirmed, v.Finding.Confidence);
        Assert.Equal(CrashFixKind.RaiseMemory, v.SafeFix?.Kind);
        Assert.Equal(6144, v.SafeFix?.NewRamMb);
    }

    [Theory]
    [InlineData("java.lang.OutOfMemoryError: GC overhead limit exceeded", "out-of-memory", true)]
    [InlineData("There is insufficient memory for the Java Runtime Environment to continue.", "out-of-memory", false)]
    [InlineData("# Native memory allocation (mmap) failed to map 65536 bytes", "out-of-memory", false)]
    [InlineData("Error occurred during initialization of VM\nCould not reserve enough space for 8388608KB object heap", "jvm-start", false)]
    public void Memory_wording_variants(string text, string rule, bool raisesMemory)
    {
        var v = V(text);
        Assert.Equal(rule, v.Finding.Rule);
        Assert.Equal(CrashConfidence.Confirmed, v.Finding.Confidence);
        Assert.Equal(raisesMemory, v.SafeFix?.Kind == CrashFixKind.RaiseMemory);
    }

    [Fact]
    public void Out_of_memory_at_the_cap_offers_no_fix()
    {
        var v = V("java.lang.OutOfMemoryError: Java heap space", ram: 14336, total: 16384);
        Assert.Null(v.SafeFix);
        Assert.Contains("most this PC", v.Finding.RecommendedFix);
    }

    // ---- "Raise memory and relaunch": the chain HomePage runs, through CrashMemory --------------------------------

    private const int Pc32Gb = 32768;

    // HomePage's chain for one launch: settings -> the launch's heap plan -> CrashMemory.ForLaunch -> the crash check
    // -> CrashMemory.ApplyRaise on the offered amount. Returns the verdict and the settings after the fix.
    private static (CrashVerdict Verdict, LauncherSettings After) RaiseAfterOom(
        LauncherSettings settings, bool legacy, int total = Pc32Gb)
    {
        var plan = HeapPlan.For(settings, total, legacy, heavyPack: false);
        var memory = CrashMemory.ForLaunch(plan, total);
        var verdict = CrashEngine.Diagnose(new CrashEvidence(1, false, false, "java.lang.OutOfMemoryError: Java heap space",
            null, null, null, [Added, Origin], memory.UsedMb, total) { RaiseFloorMb = memory.RaiseFloorMb }, false);
        if (verdict.SafeFix?.NewRamMb is int ram) CrashMemory.ApplyRaise(settings, ram);
        return (verdict, settings);
    }

    private static int HeapFor(LauncherSettings s, bool legacy) =>
        HeapPlan.For(s, Pc32Gb, legacy, heavyPack: false).MaxMb;

    // The bug: a 32 GB PC on Auto stores the slider's 16 GB suggestion while 1.8.9 really gets 4 GB. The old wiring
    // told the check the stored 16 GB ("needed more than 16 GB", offered 18 GB) and wrote only RamMb, which Auto
    // ignores, so the relaunch got 4 GB again.
    [Fact]
    public void Raise_on_auto_names_the_heap_used_and_the_relaunch_really_gets_the_amount_offered()
    {
        var (v, after) = RaiseAfterOom(RamPolicy.Fresh(Pc32Gb), legacy: true);

        Assert.Contains("the 4 GB it was given", v.Finding.LikelyCause);
        Assert.Equal(CrashFixKind.RaiseMemory, v.SafeFix?.Kind);
        var offered = v.SafeFix!.NewRamMb!.Value;
        Assert.Contains($"{offered / 1024} GB", v.SafeFix.Label);
        Assert.True(RamPolicy.IsManual(after));
        Assert.Equal(offered, HeapFor(after, legacy: true)); // what the window named is what the relaunch gets
        Assert.True(offered > 4096);
    }

    // Manual applies to every version: a 1.8.9 crash on Auto's 4 GB must not drop the newest versions from Auto's
    // 8 GB. The offer is lifted to at least that, and the window says the lifted number.
    [Fact]
    public void Raise_on_auto_never_lowers_any_version()
    {
        var before = RamPolicy.Fresh(Pc32Gb);
        var legacyBefore = HeapFor(before, legacy: true);
        var modernBefore = HeapFor(before, legacy: false);

        var (v, after) = RaiseAfterOom(before, legacy: true);

        Assert.Equal(8192, v.SafeFix?.NewRamMb);
        Assert.Contains("8 GB", v.Finding.RecommendedFix);
        Assert.True(HeapFor(after, legacy: true) > legacyBefore);
        Assert.True(HeapFor(after, legacy: false) >= modernBefore);
    }

    [Fact]
    public void Raise_on_manual_is_two_more_gigabytes_with_no_floor()
    {
        var s = RamPolicy.Fresh(Pc32Gb);
        RamPolicy.SetManual(s, 4096);
        Assert.Equal(new CrashMemory.Facts(4096, 0), CrashMemory.ForLaunch(HeapPlan.For(s, Pc32Gb, false, false), Pc32Gb));

        var (v, after) = RaiseAfterOom(s, legacy: false);

        Assert.Equal(6144, v.SafeFix?.NewRamMb);
        Assert.Equal(6144, HeapFor(after, legacy: false));
    }

    [Fact]
    public void An_unrecognised_java_option_is_named()
    {
        var v = V("Unrecognized VM option 'UseFancyGc'\nError: Could not create the Java Virtual Machine.");
        Assert.Equal("jvm-start", v.Finding.Rule);
        Assert.Contains("UseFancyGc", v.Finding.LikelyCause);
        Assert.Null(v.SafeFix); // no repair step resets Java arguments; the text says where to remove it
    }

    [Fact]
    public void Java_version_mismatch_names_the_mod_that_owns_the_class()
    {
        var v = V("java.lang.UnsupportedClassVersionError: com/example/somemod/Main has been compiled by a more recent version of the Java Runtime (class file version 65.0)");
        Assert.Equal("java-version", v.Finding.Rule);
        Assert.Equal(CrashConfidence.Confirmed, v.Finding.Confidence);
        Assert.Equal("somemod", Assert.Single(v.Finding.Culprits).ModId);
        // The player's own mod gets the turn-off button, not just a sentence pointing at the Mods tab.
        Assert.Equal(CrashFixKind.DisableMods, v.SafeFix?.Kind);
        Assert.Equal(["somemod-1.0.0.jar"], v.SafeFix!.Jars);
    }

    [Fact]
    public void Java_version_mismatch_in_an_unknown_class_offers_no_turn_off_button()
    {
        var v = V("java.lang.UnsupportedClassVersionError: org/unknown/Thing has been compiled by a more recent version of the Java Runtime (class file version 65.0)");
        Assert.Equal("java-version", v.Finding.Rule);
        Assert.NotEqual(CrashFixKind.DisableMods, v.SafeFix?.Kind);
    }

    [Fact]
    public void Out_of_memory_beats_a_mod_named_in_the_stack()
    {
        var v = V(crash: "Description: x\n\njava.lang.OutOfMemoryError: Java heap space\n\tat com.example.somemod.Big.alloc(Big.java:1)\nCaused by: java.lang.OutOfMemoryError: Java heap space\n\tat com.example.somemod.Big.alloc(Big.java:1)\n");
        Assert.Equal("out-of-memory", v.Finding.Rule);
        Assert.Contains(v.Others, o => o.Rule == "deepest-cause");
    }

    // ---- files ---------------------------------------------------------------------------------------------------

    [Fact]
    public void A_damaged_added_jar_is_turned_off_and_a_damaged_game_file_is_repaired()
    {
        var mod = V("Error analyzing /games/mods/somemod-1.0.0.jar: java.util.zip.ZipException: zip END header not found");
        Assert.Equal(CrashConfidence.Confirmed, mod.Finding.Confidence);
        Assert.Equal(CrashFixKind.DisableMods, mod.SafeFix?.Kind);
        Assert.Equal(["somemod-1.0.0.jar"], mod.SafeFix!.Jars);

        var game = V("Error: Invalid or corrupt jarfile /games/libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar");
        Assert.Equal(CrashFixKind.RepairFiles, game.SafeFix?.Kind);
        Assert.Equal(CrashConfidence.Confirmed, game.Finding.Confidence);

        var unnamed = V("java.util.zip.ZipException: invalid CEN header");
        Assert.Equal(CrashConfidence.Strong, unnamed.Finding.Confidence);
        Assert.Equal(CrashFixKind.RepairFiles, unnamed.SafeFix?.Kind);
    }

    // ---- mods ----------------------------------------------------------------------------------------------------

    private const string MissingDep = """
net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!
A potential solution has been determined, this may resolve your problem:
	 - Install neededlib, any version.
More details:
	 - Mod 'Some Mod' (somemod) 1.0.0 requires any version of neededlib, which is missing!
""";

    [Fact]
    public void A_missing_dependency_is_confirmed_and_names_both_mods()
    {
        var v = V(MissingDep);
        Assert.Equal("fabric-dependency", v.Finding.Rule);
        Assert.Equal(CrashConfidence.Confirmed, v.Finding.Confidence);
        Assert.Contains("neededlib", v.Finding.LikelyCause);
        Assert.Equal(CrashFixKind.DisableMods, v.SafeFix?.Kind);
        Assert.Equal(["somemod-1.0.0.jar"], v.SafeFix!.Jars);
    }

    [Fact]
    public void A_mod_for_another_minecraft_version_is_a_wrong_version_problem()
    {
        var v = V("Incompatible mods found!\n - Mod 'Some Mod' (somemod) 1.0.0 requires version 1.20.1 of mod 'Minecraft' (minecraft), but only the wrong version is present: 1.21.1!");
        Assert.Contains("different Minecraft version", v.Finding.LikelyCause);
        Assert.Equal(CrashConfidence.Confirmed, v.Finding.Confidence);
    }

    [Fact]
    public void A_dependency_problem_in_Origins_own_mod_offers_no_fix()
    {
        var v = V(MissingDep.Replace("'Some Mod' (somemod)", "'Origin' (originclient)"));
        Assert.Null(v.SafeFix);
        Assert.StartsWith("Update Origin", v.Finding.RecommendedFix);
    }

    // Bug run 6, KC-7: by plain text "Patcher-1.9.2" sorts after "Patcher-1.10.0", so the older copy was kept.
    [Theory]
    [InlineData("Patcher-1.10.0.jar", "Patcher-1.9.2.jar")]
    [InlineData("sodium-fabric-0.6.13+mc1.21.1.jar", "sodium-fabric-0.6.9+mc1.21.1.jar")]
    [InlineData("somemod-2.0.0-beta.10.jar", "somemod-2.0.0-beta.9.jar")]
    [InlineData("somemod-v10.jar", "somemod-v9.jar")] // no version after a dash: digit runs compare as numbers
    public void A_duplicate_mod_keeps_the_newer_version_not_the_later_name(string newer, string older)
    {
        var v = V($"Mods share ID with builtin mod somemod: {newer}, {older}",
            mods: [new CrashModEntry("somemod", newer, true), new CrashModEntry("somemod", older, true)]);
        Assert.Equal([older], v.SafeFix!.Jars);
        Assert.True(CrashRules.NewerJarName.Compare(newer, older) > 0);
    }

    [Fact]
    public void A_duplicate_mod_keeps_one_copy()
    {
        var copyA = new CrashModEntry("somemod", "somemod-1.0.0.jar", true);
        var copyB = new CrashModEntry("somemod", "somemod-1.1.0.jar", true);
        var v = V("Mods share ID with builtin mod somemod: somemod-1.0.0.jar, somemod-1.1.0.jar", mods: [copyA, copyB]);
        Assert.Equal("duplicate-mod", v.Finding.Rule);
        Assert.Equal(["somemod-1.0.0.jar"], v.SafeFix!.Jars); // the newest by name stays
    }

    [Fact]
    public void One_mods_mixin_failure_is_strong_and_offers_to_turn_it_off()
    {
        var v = V("[main/ERROR]: Mixin apply for mod somemod failed somemod.mixins.json:FooMixin from mod somemod -> net.minecraft.class_310: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: Critical injection failure");
        Assert.Equal(CrashConfidence.Strong, v.Finding.Confidence);
        Assert.Equal(CrashFixKind.DisableMods, v.SafeFix?.Kind);
        Assert.False(v.OfferTryWithoutAddedMods);
    }

    [Fact]
    public void A_mixin_failure_in_Origins_own_mod_never_blames_the_players_files()
    {
        var v = V("Mixin apply for mod originclient failed originclient.mixins.json:TitleMixin from mod originclient -> x");
        Assert.Equal("ORIGIN MOD", v.Finding.Tag);
        Assert.Null(v.SafeFix);
    }

    [Fact]
    public void Mixin_failures_in_two_mods_are_only_possible()
    {
        var v = V("Mixin apply for mod somemod failed somemod.mixins.json:A from mod somemod -> x\nMixin apply for mod othermod failed othermod.mixins.json:B from mod othermod -> y",
            mods: [Added, Other, Origin]);
        Assert.Equal(CrashConfidence.Possible, v.Finding.Confidence);
        Assert.Null(v.SafeFix);
        Assert.True(v.OfferTryWithoutAddedMods);
    }

    [Fact]
    public void One_suspected_mod_in_the_launch_set_is_strong_and_two_are_possible()
    {
        var one = V(crash: "Description: Ticking entity\n\nSuspected Mod: \n\tSome Mod (somemod), Version: 1.0.0\n");
        Assert.Equal(CrashConfidence.Strong, one.Finding.Confidence);
        var two = V(crash: "Description: Ticking entity\n\nSuspected Mods: Some Mod (somemod), Other (othermod)\n", mods: [Added, Other]);
        Assert.Equal(CrashConfidence.Possible, two.Finding.Confidence);
        Assert.Null(two.SafeFix);
    }

    [Fact]
    public void Forge_errored_and_missing_mods_are_read_from_FMLs_own_wording()
    {
        var errored = V(crash: "\tStates: 'U' = Unloaded\n\tUCHE\tsomemod{1.0} [Some Mod] (somemod-1.0.0.jar) \n");
        Assert.Equal("forge-errored", errored.Finding.Rule);
        Assert.Equal(CrashConfidence.Strong, errored.Finding.Confidence);
        var missing = V("The mod somemod (Some Mod) requires mods [neededlib] to be available");
        Assert.Equal("forge-missing", missing.Finding.Rule);
        Assert.Equal(CrashConfidence.Confirmed, missing.Finding.Confidence);
    }

    [Fact]
    public void A_stack_inside_one_added_mod_is_only_possible()
    {
        var v = V(crash: "Description: x\n\njava.lang.NullPointerException: boom\n\tat com.example.somemod.Thing.tick(Thing.java:3)\n\tat net.minecraft.world.level.Level.tick(Level.java:1)\n");
        Assert.Equal(CrashConfidence.Possible, v.Finding.Confidence);
        Assert.Equal("somemod", Assert.Single(v.Finding.Culprits).ModId);
        Assert.Null(v.SafeFix);
    }

    // ---- below the JVM -------------------------------------------------------------------------------------------

    [Theory]
    [InlineData("GLFW error 65542: WGL: The driver does not appear to support OpenGL", null)]
    [InlineData(null, "# Problematic frame:\n# C  [nvoglv64.dll+0xabc]")]
    [InlineData(null, "# Problematic frame:\n# C  [ig9icd64.dll+0x1234]")]
    // macOS: Apple's OpenGL over Metal (Apple Silicon) and an Intel Mac's AMD GL driver.
    [InlineData(null, "# Problematic frame:\n# C  [AppleMetalOpenGLRenderer+0x2f1a4]")]
    [InlineData(null, "# Problematic frame:\n# C  [AMDRadeonX6000GLDriver+0x51c2]")]
    [InlineData("Pixel format not accelerated", null)]
    public void Graphics_driver_signals_are_possible_with_no_fix(string? log, string? hsErr)
    {
        var v = V(log, hsErr: hsErr);
        Assert.Equal(CrashCause.GraphicsDriver, v.Finding.Cause);
        Assert.Equal(CrashConfidence.Possible, v.Finding.Confidence);
        Assert.Null(v.SafeFix);
    }

    [Fact]
    public void System_details_mentioning_opengl_are_not_a_graphics_crash()
    {
        var v = V(crash: "Description: x\n\n-- System Details --\n\tBackend API: OpenGL 4.6 Core\n\tGL Caps: Using framebuffer using OpenGL 3.2\n");
        Assert.NotEqual(CrashCause.GraphicsDriver, v.Finding.Cause);
    }

    [Fact]
    public void A_native_exit_code_with_no_log_is_a_possible_native_crash()
    {
        var v = V(exit: unchecked((int)0xC0000005));
        Assert.Equal("native-crash", v.Finding.Rule);
        Assert.Equal(CrashConfidence.Possible, v.Finding.Confidence);
    }

    [Fact]
    public void Nothing_recognised_is_unknown_and_offers_only_the_one_time_try()
    {
        var v = V(crash: "Description: Unexpected error\n\njava.lang.IllegalStateException: x\n\tat net.minecraft.client.Minecraft.run(Minecraft.java:1)\n");
        Assert.Equal(CrashConfidence.Unknown, v.Finding.Confidence);
        Assert.Equal("unknown", v.Finding.Confidence.Word());
        Assert.Null(v.SafeFix);
        Assert.True(v.OfferTryWithoutAddedMods);
        Assert.False(V(crash: "Description: x", mods: [Origin]).OfferTryWithoutAddedMods); // nothing added to leave out
    }

    // ---- the fix policy --------------------------------------------------------------------------------------------

    [Fact]
    public void A_spent_retry_removes_the_fix_and_the_try()
    {
        var v = V("java.lang.OutOfMemoryError: Java heap space", tried: true);
        Assert.Null(v.SafeFix);
        Assert.Contains("already tried", v.Finding.RecommendedFix);
        Assert.False(V(crash: "Description: x", tried: true).OfferTryWithoutAddedMods);
    }

    [Fact]
    public void A_fix_below_strong_is_never_offered()
    {
        var finding = CrashRules.Unknown() with
        {
            Confidence = CrashConfidence.Possible,
            Fix = new CrashFix(CrashFixKind.DisableMods, "x", ["a.jar"]),
        };
        Assert.Null(new CrashVerdict(true, "", finding, []).SafeFix);
        Assert.NotNull(new CrashVerdict(true, "", finding with { Confidence = CrashConfidence.Strong }, []).SafeFix);
    }

    [Theory]
    [InlineData(CrashConfidence.Confirmed, "confirmed")]
    [InlineData(CrashConfidence.Strong, "strong")]
    [InlineData(CrashConfidence.Possible, "possible")]
    [InlineData(CrashConfidence.Unknown, "unknown")]
    public void Confidence_words_are_verbatim_and_round_trip(CrashConfidence c, string word)
    {
        Assert.Equal(word, c.Word());
        Assert.Equal(c, CrashConfidenceWords.Parse(word));
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
    public void Retry_guard_allows_one_retry_until_cleared_or_expired()
    {
        var guard = new CrashRetryGuard();
        var t = new DateTime(2026, 10, 20, 12, 0, 0, DateTimeKind.Utc);
        Assert.False(guard.AnyTried("steve", "1.21.1", t));
        Assert.True(guard.TryBegin("steve", "1.21.1", CrashFixKind.RaiseMemory, t));
        Assert.True(guard.AnyTried("steve", "1.21.1", t.AddMinutes(1)));
        Assert.False(guard.TryBegin("steve", "1.21.1", CrashFixKind.None, t.AddMinutes(1)));
        Assert.True(guard.TryBegin("alex", "1.21.1", CrashFixKind.None, t)); // other accounts are independent

        guard.Clear("steve", "1.21.1"); // the game booted
        Assert.False(guard.AnyTried("steve", "1.21.1", t.AddMinutes(2)));
        Assert.True(guard.TryBegin("steve", "1.21.1", CrashFixKind.DisableMods, t.AddMinutes(2)));

        Assert.False(guard.AnyTried("steve", "1.21.1", t.AddMinutes(2) + CrashRetryGuard.Window)); // expired
    }

    // Bug run 6, KC-4: a fix spent on 1.21.1 hid 1.20.1's own fix ("Origin already tried a fix once") for 30 minutes.
    [Fact]
    public void Retry_guard_keeps_each_version_of_an_account_apart()
    {
        var guard = new CrashRetryGuard();
        var t = new DateTime(2026, 11, 4, 12, 0, 0, DateTimeKind.Utc);
        Assert.True(guard.TryBegin("steve", "1.21.1", CrashFixKind.DisableMods, t));

        Assert.False(guard.AnyTried("steve", "1.20.1", t.AddMinutes(1)));
        Assert.True(guard.TryBegin("steve", "1.20.1", CrashFixKind.RaiseMemory, t.AddMinutes(1)));

        guard.Clear("steve", "1.20.1"); // 1.20.1 booted: 1.21.1's chain is still spent
        Assert.True(guard.AnyTried("steve", "1.21.1", t.AddMinutes(2)));
    }

    [Fact]
    public void Mixin_config_belongs_to_the_exact_or_longest_matching_mod_id()
    {
        var mods = new List<CrashModEntry>
        {
            new("carpet", "carpet.jar", true), new("carpet-extra", "carpet-extra.jar", true),
            new("sodium", "sodium.jar", false), new("sodium-extra", "sodium-extra.jar", true),
        };
        Assert.Equal("carpet-extra", CrashFactsParser.ModOfConfig("carpet-extra.mixins.json", mods));
        Assert.Equal("carpet", CrashFactsParser.ModOfConfig("carpet.mixins.json", mods));
        Assert.Equal("carpet-extra", CrashFactsParser.ModOfConfig("carpet-extra-client.mixins.json", mods));
        Assert.Equal("sodium-extra", CrashFactsParser.ModOfConfig("sodium-extra.mixins.json", mods));
        Assert.Equal("sodium", CrashFactsParser.ModOfConfig("sodium-client.mixins.json", mods));
        Assert.Null(CrashFactsParser.ModOfConfig("other.mixins.json", mods));

        // Order of the launch set must not matter: the longer id wins even when listed first or last.
        mods.Reverse();
        Assert.Equal("carpet-extra", CrashFactsParser.ModOfConfig("carpet-extra.mixins.json", mods));
        Assert.Equal("sodium-extra", CrashFactsParser.ModOfConfig("sodium-extra.mixins.json", mods));
    }
}
