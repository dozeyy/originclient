using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;
using Xunit;

namespace OriginLauncher.Tests.Import;

// The pure parts of the launcher glue (Core/Import/Launcher): where the launchers live, what an import
// does to the launcher settings and how it is undone, when the one-time first-run card shows, and how
// the Done page reads summary.txt. The Windows parts (backend, HTTP, startup, dry run) are verified by
// running the app (--import-plan) and by the CI screen render.
public sealed class LauncherGlueTests
{
    // ---- roots ----

    private static readonly MachineFolders Win = new(
        AppData: Path.Combine("C:", "Users", "p", "AppData", "Roaming"),
        LocalAppData: Path.Combine("C:", "Users", "p", "AppData", "Local"),
        UserProfile: Path.Combine("C:", "Users", "p"),
        Documents: Path.Combine("C:", "Users", "p", "Documents"));

    [Fact]
    public void Roots_a_launcher_that_is_not_installed_is_null()
    {
        var roots = ImportRoots.Sources(Win, _ => false);
        Assert.Null(roots.OfficialMinecraftDir);
        Assert.Null(roots.PrismDataDir);
        Assert.Null(roots.ModrinthDir);
        Assert.Null(roots.CurseForgeDir);
        var files = ImportRoots.GameFiles(Win, "inst", _ => false);
        Assert.Null(files.OfficialMinecraftDir);
        Assert.Empty(files.OfficialRuntimeRoots);
        Assert.Equal("inst", files.InstancesRoot);
    }

    [Fact]
    public void Roots_find_each_launcher_in_its_usual_place()
    {
        var present = new HashSet<string>
        {
            Path.Combine(Win.AppData, ".minecraft"),
            Path.Combine(Win.AppData, "PrismLauncher"),
            Path.Combine(Win.AppData, "ModrinthApp"),
            Path.Combine(Win.AppData, "ModrinthApp", "meta"),
            Path.Combine(Win.UserProfile, "curseforge", "minecraft"),
            Path.Combine(Win.LocalAppData, "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe", "LocalCache", "Local", "runtime"),
        };
        var roots = ImportRoots.Sources(Win, present.Contains);
        Assert.Equal(Path.Combine(Win.AppData, ".minecraft"), roots.OfficialMinecraftDir);
        Assert.Equal(Path.Combine(Win.AppData, "PrismLauncher"), roots.PrismDataDir);
        Assert.Equal(Path.Combine(Win.AppData, "ModrinthApp"), roots.ModrinthDir);
        Assert.Equal(Path.Combine(Win.UserProfile, "curseforge", "minecraft", "Instances"), roots.CurseForgeDir);

        var files = ImportRoots.GameFiles(Win, "inst", present.Contains);
        Assert.Equal(Path.Combine(Win.AppData, "ModrinthApp", "meta"), files.ModrinthMetaDir);
        Assert.Equal(Path.Combine(Win.UserProfile, "curseforge", "minecraft"), files.CurseForgeRoot);
        Assert.Single(files.OfficialRuntimeRoots);
    }

    [Fact]
    public void Roots_fall_back_to_the_older_modrinth_folder_and_the_documents_curseforge_folder()
    {
        var present = new HashSet<string>
        {
            Path.Combine(Win.AppData, "com.modrinth.theseus"),
            Path.Combine(Win.Documents, "curseforge", "minecraft"),
        };
        var roots = ImportRoots.Sources(Win, present.Contains);
        Assert.Equal(Path.Combine(Win.AppData, "com.modrinth.theseus"), roots.ModrinthDir);
        Assert.Equal(Path.Combine(Win.Documents, "curseforge", "minecraft", "Instances"), roots.CurseForgeDir);
    }

    // ---- settings delta ----

    private static SettingsDelta Delta(string profileId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", string version = "1.21.1") => new()
    {
        AddProfile = new ModProfile { Id = profileId, Name = "Prism: Survival", Version = version },
        SetSelectedVersion = version, SetSelectedProfileId = profileId,
        PreviousSelectedVersion = "1.20.1", PreviousSelectedProfileId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    };

    private static LauncherSettings Before() => new()
    {
        SelectedVersion = "1.20.1", SelectedProfileId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", PlayWithExternalMods = false,
        ModProfiles = { new ModProfile { Id = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", Name = "Mine", Version = "1.20.1" } },
    };

    [Fact]
    public void Apply_adds_the_profile_and_selects_it_without_touching_other_settings()
    {
        var s = Before();
        ImportSettingsRules.Apply(s, Delta());
        Assert.Equal(2, s.ModProfiles.Count);
        Assert.Equal("1.21.1", s.SelectedVersion);
        Assert.Equal("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", s.SelectedProfileId);
        Assert.False(s.PlayWithExternalMods); // never flipped by an import
    }

    [Fact]
    public void Apply_twice_adds_the_profile_once()
    {
        var s = Before();
        ImportSettingsRules.Apply(s, Delta());
        ImportSettingsRules.Apply(s, Delta());
        Assert.Equal(2, s.ModProfiles.Count);
    }

    [Fact]
    public void Revert_after_apply_restores_the_old_settings_exactly()
    {
        var s = Before();
        ImportSettingsRules.Apply(s, Delta());
        ImportSettingsRules.Revert(s, Delta());
        Assert.Single(s.ModProfiles);
        Assert.Equal("1.20.1", s.SelectedVersion);
        Assert.Equal("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", s.SelectedProfileId);
    }

    [Fact]
    public void Revert_is_safe_when_apply_never_ran()
    {
        var s = Before();
        ImportSettingsRules.Revert(s, Delta());
        Assert.Single(s.ModProfiles);
        Assert.Equal("1.20.1", s.SelectedVersion);
        Assert.Equal("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", s.SelectedProfileId);
    }

    [Fact]
    public void Revert_keeps_a_choice_the_player_made_after_the_import()
    {
        var s = Before();
        ImportSettingsRules.Apply(s, Delta());
        s.SelectedVersion = "1.8.9"; // the player picked another version meanwhile
        ImportSettingsRules.Revert(s, Delta());
        Assert.Equal("1.8.9", s.SelectedVersion);
        Assert.Equal("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", s.SelectedProfileId);
    }

    [Fact]
    public void Apply_without_a_profile_only_changes_the_version()
    {
        var s = Before();
        ImportSettingsRules.Apply(s, new SettingsDelta { SetSelectedVersion = "1.21.1", PreviousSelectedVersion = "1.20.1" });
        Assert.Single(s.ModProfiles);
        Assert.Equal("1.21.1", s.SelectedVersion);
        Assert.Equal("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", s.SelectedProfileId);
        ImportSettingsRules.Revert(s, new SettingsDelta { SetSelectedVersion = "1.21.1", PreviousSelectedVersion = "1.20.1" });
        Assert.Equal("1.20.1", s.SelectedVersion);
    }

    // ---- Home's one-click bring-over button (ImportPrompt) ----

    [Theory]
    [InlineData(false, false, true, 1, true)]
    [InlineData(true, false, true, 1, false)]   // answered (No thanks, a transfer, the Import page): never again
    [InlineData(false, true, true, 1, false)]   // opened with --import: the page is already open
    [InlineData(false, false, false, 1, false)] // nobody can import yet: keep the offer for later
    [InlineData(false, false, true, 0, false)]  // nothing found: nothing to offer
    public void The_button_shows_only_when_everything_allows_it(bool answered, bool flag, bool canImport, int found, bool expected) =>
        Assert.Equal(expected, ImportPrompt.ShouldShow(answered, flag, canImport, found));

    private static DetectedInstance Inst(SourceKind k, string? version = "1.21.1", string dir = "x") =>
        new() { Kind = k, Name = "x", GameDir = dir, McVersion = version };

    private static readonly string[] Supported = { "1.21.1", "1.20.1", "1.8.9" };

    [Fact]
    public void The_button_counts_only_qualifying_setups()
    {
        var found = new[] { Inst(SourceKind.Prism, "1.21.1"), Inst(SourceKind.Prism, "1.21.5"), Inst(SourceKind.Modrinth, null), Inst(SourceKind.CurseForge, "1.8.9") };
        var qualifying = ImportPrompt.Qualifying(found, Supported);
        Assert.Equal(new[] { "1.21.1", "1.8.9" }, qualifying.Select(i => i.McVersion));
        Assert.True(ImportPrompt.ShouldShow(false, false, true, qualifying.Count));
        // The Modrinth and 1.21.5 setups don't qualify, so they make no source either: CurseForge and Prism, one each.
        Assert.Equal(new[] { SourceKind.CurseForge, SourceKind.Prism }, ImportPrompt.Sources(qualifying).Select(s => s.Kind));
    }

    [Fact]
    public void The_button_does_not_show_when_every_setup_is_on_an_unsupported_version()
    {
        var found = new[] { Inst(SourceKind.Prism, "1.21.5"), Inst(SourceKind.Prism, "1.12.2"), Inst(SourceKind.Modrinth, null) };
        var qualifying = ImportPrompt.Qualifying(found, Supported);
        Assert.Empty(qualifying);
        Assert.False(ImportPrompt.ShouldShow(false, false, true, qualifying.Count));
        Assert.Empty(ImportPrompt.Sources(qualifying));
    }

    [Fact]
    public void One_launcher_is_one_click_however_many_setups_it_has()
    {
        var lunar = new[] { Inst(SourceKind.Lunar, "1.21.1", "a"), Inst(SourceKind.Lunar, "1.8.9", "b"), Inst(SourceKind.Lunar, "1.20.1", "c") };

        var source = Assert.Single(ImportPrompt.Sources(lunar));
        Assert.Equal(new ImportSourceChoice(SourceKind.Lunar, "Lunar", 3), source);
        Assert.Equal("Bring over your Lunar setup", ImportPrompt.Label(lunar));
        // The click brings that launcher's setups only, with the Import page's "Import everything" defaults.
        var selection = ImportPrompt.Selection(source.Kind);
        Assert.Equal(BulkSelectionKind.Launcher, selection.Kind);
        Assert.Equal(SourceKind.Lunar, selection.Launcher);
    }

    [Fact]
    public void Several_launchers_are_a_choice_most_setups_first_with_All()
    {
        var found = new[] { Inst(SourceKind.Prism, dir: "p1"), Inst(SourceKind.Lunar, dir: "l1"), Inst(SourceKind.Prism, dir: "p2"), Inst(SourceKind.Modrinth, dir: "m1") };

        var sources = ImportPrompt.Sources(found);

        Assert.Equal(new[] { SourceKind.Prism, SourceKind.Lunar, SourceKind.Modrinth }, sources.Select(s => s.Kind));
        Assert.Equal(new[] { 2, 1, 1 }, sources.Select(s => s.Setups));
        Assert.Equal(ImportPrompt.ManyLabel, ImportPrompt.Label(found));
        Assert.Equal("Bring over your Minecraft setups", ImportPrompt.ManyLabel);
        Assert.Equal(BulkSelectionKind.All, ImportPrompt.Selection(null).Kind); // "All of them"
    }

    [Fact]
    public void The_button_names_each_launcher_by_its_short_name()
    {
        Assert.Equal("Bring over your Prism setup", ImportPrompt.Label(new[] { Inst(SourceKind.Prism) }));
        Assert.Equal("Bring over your Minecraft Launcher setup", ImportPrompt.Label(new[] { Inst(SourceKind.OfficialLauncher) }));
        Assert.Equal("Bring over your Modrinth setup", ImportPrompt.Label(new[] { Inst(SourceKind.Modrinth) }));
        Assert.Equal("Bring over your Feather setup", ImportPrompt.Label(new[] { Inst(SourceKind.Feather) }));
    }

    [Fact]
    public void One_click_brings_everything_that_qualifies()
    {
        var options = ImportPrompt.Options();
        Assert.True(options.CopyWorlds);
        Assert.True(options.CopyScreenshots);
        Assert.True(options.CopyUnusedPacks);
        Assert.Equal(ImportCategories.None, options.LeaveOut);
        Assert.Null(options.BringByVersion);
    }

    [Fact]
    public void Existing_players_start_with_the_button_unanswered()
    {
        Assert.False(new LauncherSettings().ImportPromptSeen);
        Assert.True(ImportPrompt.ShouldShow(new LauncherSettings().ImportPromptSeen, false, true, 1));
    }

    // ---- the button's background scan: every start while an offer waits, weekly when there is nothing ----

    private static readonly DateTime Now = new(2026, 10, 19, 12, 0, 0, DateTimeKind.Utc);

    [Fact]
    public void The_scan_runs_when_never_checked_and_after_finding_nothing_again_only_after_a_week()
    {
        Assert.True(ImportPrompt.ShouldScan(answered: false, canImport: true, lastCheckedUtc: null, Now));
        Assert.False(ImportPrompt.ShouldScan(false, true, Now.AddMinutes(-5), Now));            // the next start: no scan
        Assert.False(ImportPrompt.ShouldScan(false, true, Now.AddDays(-6.9), Now));
        Assert.True(ImportPrompt.ShouldScan(false, true, Now.AddDays(-7), Now));                // a week on: look again
        Assert.True(ImportPrompt.ShouldScan(false, true, Now.AddDays(-30), Now));
        Assert.True(ImportPrompt.ShouldScan(false, true, Now.AddDays(1), Now));                 // clock moved back: stale
    }

    [Fact]
    public void Only_a_check_that_found_nothing_is_stamped_so_an_unanswered_offer_comes_back_next_start()
    {
        Assert.True(ImportPrompt.StampCheck(0));
        Assert.False(ImportPrompt.StampCheck(1));
        Assert.False(ImportPrompt.StampCheck(5));
    }

    [Fact]
    public void The_scan_never_runs_once_answered_or_while_nobody_can_import()
    {
        Assert.False(ImportPrompt.ShouldScan(answered: true, canImport: true, lastCheckedUtc: null, Now));
        Assert.False(ImportPrompt.ShouldScan(answered: false, canImport: false, lastCheckedUtc: null, Now));
        Assert.Null(new LauncherSettings().ImportPromptCheckedUtc); // older settings files: never checked
    }

    // ---- the one-click transfer's words (HomeImport) ----

    private static SkippedSetup Skip(string name, string reason, SkipKind kind = SkipKind.Unsupported) =>
        new(new DetectedInstance { Kind = SourceKind.Lunar, Name = name, GameDir = "g-" + name, McVersion = "1.21.5" }, reason, kind);

    [Fact]
    public void The_running_line_checks_then_counts_up()
    {
        Assert.Equal("Checking what can come over...", HomeImport.RunningLine("Lunar", null));
        Assert.Equal("Checking what can come over...", HomeImport.RunningLine("Lunar", new HomeImportProgress(HomeImportStep.Checking, 0)));
        Assert.Equal("Bringing over your Lunar setup, 42 %", HomeImport.RunningLine("Lunar", new HomeImportProgress(HomeImportStep.Copying, 0.42)));
        Assert.Equal("Bringing over your setups, 100 %", HomeImport.RunningLine(null, new HomeImportProgress(HomeImportStep.Copying, 1.7)));
    }

    [Fact]
    public void A_finished_transfer_is_one_plain_line_and_says_what_did_not_come_over()
    {
        var clean = new BulkImportResult { RunId = "r", State = ImportState.Committed };
        var done = HomeImport.Outcome(clean, "Lunar");
        Assert.True(done.Succeeded);
        Assert.Equal("Your Lunar setup is in Origin.", done.Line);
        Assert.Null(done.LeftOut);
        Assert.Equal("Your setups are in Origin.", HomeImport.Outcome(clean, null).Line);

        var one = new BulkImportResult
        {
            RunId = "r", State = ImportState.Committed,
            Skipped = new[] { Skip("Snapshot", "Origin can't run 1.21.5"), Skip("Other", "picked another launcher", SkipKind.NotSelected) },
        };
        Assert.Equal("Didn't come over: Lunar: Snapshot. Origin can't run 1.21.5.", HomeImport.Outcome(one, "Lunar").LeftOut);

        var many = new BulkImportResult
        {
            RunId = "r", State = ImportState.Committed,
            Skipped = new[] { Skip("A", "x"), Skip("B", "y"), Skip("C", "x"), Skip("D", "x") },
        };
        Assert.Equal("Didn't come over: Lunar: A, Lunar: B and 2 more.", HomeImport.Outcome(many, "Lunar").LeftOut);
    }

    // Bug run 11 PROF-1: with Lunar still open every setup is skipped, and Home used to say "The list below says why"
    // with no list on Home. It now names what stayed behind and the one thing that fixes it.
    private static VersionGroup Group()
    {
        var setup = new DetectedInstance { Kind = SourceKind.Lunar, Name = "1.21.1", GameDir = "g", McVersion = "1.21.1" };
        return new VersionGroup { Version = "1.21.1", TargetGameDir = "t", Winner = setup, Pick = new PickResult(PickMode.None, Array.Empty<DetectedInstance>(), null) };
    }

    private static BulkImportPlan PlanWith(params SkippedSetup[] skipped)
    {
        var plan = new BulkImportPlan { RunId = "r" };
        plan.Skipped.AddRange(skipped);
        plan.Blocked.Add(BulkRules.NoneCanComeOver);
        return plan;
    }

    [Fact]
    public void Nothing_to_copy_on_Home_says_why_instead_of_pointing_at_a_list_Home_lacks()
    {
        const string running = "Minecraft is running from this setup. Close it, then plan the import again to bring it over.";
        var one = HomeImport.BeforeCopy(PlanWith(Skip("1.21.1", running, SkipKind.SourceRunning)));
        Assert.NotNull(one);
        Assert.False(one!.Succeeded);
        Assert.DoesNotContain("list below", one.Line + one.LeftOut);
        Assert.Equal("Didn't come over: Lunar: 1.21.1. " + running, one.LeftOut);

        var two = HomeImport.BeforeCopy(PlanWith(Skip("1.21.1", running, SkipKind.SourceRunning), Skip("1.20.1", running, SkipKind.SourceRunning)));
        Assert.Equal("Didn't come over: Lunar: 1.21.1, Lunar: 1.20.1. " + running, two!.LeftOut);

        var targets = HomeImport.BeforeCopy(PlanWith(
            Skip("a", "Close Minecraft first: Origin's Minecraft 1.21.1 is still running.", SkipKind.TargetRunning),
            Skip("b", "Close Minecraft first: Origin's Minecraft 1.20.1 is still running.", SkipKind.TargetRunning)));
        Assert.Equal("Didn't come over: Lunar: a, Lunar: b. Close Minecraft first: Origin's game for these versions is still running.", targets!.LeftOut);
    }

    // Bug run 11 PROF-12: a 26.1.x setup brought over before that version was ever played keeps its keybinds and video
    // settings behind (no data version yet). The Import page said so; Home's one-click now does too.
    [Fact]
    public void Home_says_when_settings_wait_for_the_first_play()
    {
        var setup = new DetectedInstance { Kind = SourceKind.Lunar, Name = "26.1", GameDir = "g", McVersion = "26.1" };
        var waiting = new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.Setting, "options.txt", SettingsMigrator.OptionsNotYet("26.1"));
        var result = new BulkImportResult
        {
            RunId = "r", State = ImportState.Committed,
            Versions = new[]
            {
                new VersionOutcome("26.1", new[] { new SetupOutcome(setup, SetupRole.Full, SetupResultKind.Imported, Lines: new[] { waiting }) }),
                new VersionOutcome("1.21.1", Array.Empty<SetupOutcome>()),
            },
        };
        var done = HomeImport.Outcome(result, "Lunar");
        Assert.True(done.Succeeded);
        Assert.Equal("Your settings for Minecraft 26.1 come over once you've played it: press Play once, then import again from Profiles.", done.LeftOut);

        var clean = new BulkImportResult { RunId = "r", State = ImportState.Committed, Versions = new[] { new VersionOutcome("1.21.1", Array.Empty<SetupOutcome>()) } };
        Assert.Null(HomeImport.Outcome(clean, "Lunar").LeftOut);
    }

    [Fact]
    public void Real_blockers_still_stop_Home_and_the_disk_line_says_where_worlds_can_be_left_out()
    {
        var disk = new BulkImportPlan { RunId = "r" };
        disk.Groups.Add(Group());
        disk.Blocked.Add("Not enough free disk space: importing everything needs about 900 MB and 10 MB is free. " + BulkRules.LeaveWorldsOutHint);
        var refused = HomeImport.BeforeCopy(disk);
        Assert.EndsWith(BulkRules.LeaveWorldsOutHint + " Choose what comes over lets you leave worlds out.", refused!.Line);
        Assert.StartsWith("Nothing changed. Not enough free disk space", refused.Line);

        var ready = new BulkImportPlan { RunId = "r" };
        ready.Groups.Add(Group());
        Assert.Null(HomeImport.BeforeCopy(ready));
    }

    [Fact]
    public void A_failed_or_stopped_transfer_says_nothing_changed()
    {
        var failed = HomeImport.Outcome(new BulkImportResult { RunId = "r", State = ImportState.RolledBack, Error = "Minecraft is running from Lunar: 1.21" }, "Lunar");
        Assert.False(failed.Succeeded);
        Assert.Equal("Nothing changed. Minecraft is running from Lunar: 1.21.", failed.Line);

        var stopped = HomeImport.Outcome(new BulkImportResult { RunId = "r", State = ImportState.RolledBack, Cancelled = true }, "Lunar");
        Assert.True(stopped.Cancelled);
        Assert.Equal("Stopped. Nothing changed.", stopped.Line);

        Assert.Equal("Nothing changed. Wait for Minecraft to finish starting.", HomeImport.Refused("Wait for Minecraft to finish starting.").Line);
        var nothing = HomeImport.NothingToCopy(new[] { Skip("Snapshot", "Origin can't run 1.21.5") });
        Assert.Equal("Nothing here can come over yet.", nothing.Line);
        Assert.Equal("Didn't come over: Lunar: Snapshot. Origin can't run 1.21.5.", nothing.LeftOut);
    }

    [Fact]
    public void An_account_switch_while_checking_stops_the_transfer()
    {
        Assert.True(HomeImport.SameAccount("a", "a", "a"));
        Assert.False(HomeImport.SameAccount("a", "b", "b")); // the plan was made for the account switched to
        Assert.False(HomeImport.SameAccount("a", "a", "b")); // switched after planning, before copying
        Assert.False(HomeImport.SameAccount("a", null, "a")); // no account in the plan
        Assert.Equal("Nothing changed. The account changed while Origin was checking. Try again.", HomeImport.Refused(HomeImport.AccountChanged).Line);
    }

    [Fact]
    public void The_copy_fraction_follows_bytes_else_setups()
    {
        var step = new ImportProgress(ImportStage.Scanning, 1, 4);
        Assert.Equal(0.25, HomeImport.Fraction(new BulkProgress(0, 2, "a", step, 25, 100)));
        Assert.Equal(0.625, HomeImport.Fraction(new BulkProgress(1, 2, "a", step, 0, 0)));
        Assert.Equal(1.0, HomeImport.Fraction(new BulkProgress(5, 2, "a", step, 0, 0)));
    }

    // ---- bulk profiles in the settings delta ----

    private static ModProfile Prof(char c, string version) =>
        new() { Id = new string(c, 32), Name = "Imported " + version, Version = version };

    private static SettingsDelta BulkDelta() => new()
    {
        AddProfiles = new List<ModProfile> { Prof('c', "1.21.1"), Prof('d', "1.20.1"), Prof('e', "1.8.9") },
        SetSelectedVersion = "1.21.1", SetSelectedProfileId = new string('c', 32),
        PreviousSelectedVersion = "1.20.1", PreviousSelectedProfileId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    };

    [Fact]
    public void Bulk_apply_adds_every_profile_and_revert_removes_every_one()
    {
        var delta = BulkDelta();
        var s = Before();
        ImportSettingsRules.Apply(s, delta);
        Assert.Equal(4, s.ModProfiles.Count);
        Assert.Equal("1.21.1", s.SelectedVersion);
        Assert.Equal(new string('c', 32), s.SelectedProfileId);

        ImportSettingsRules.Apply(s, delta); // twice: still once each
        Assert.Equal(4, s.ModProfiles.Count);

        ImportSettingsRules.Revert(s, delta);
        Assert.Single(s.ModProfiles);
        Assert.Equal("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", s.ModProfiles[0].Id);
        Assert.Equal("1.20.1", s.SelectedVersion);
        Assert.Equal("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", s.SelectedProfileId);
    }

    [Fact]
    public void Bulk_revert_keeps_a_selection_the_player_changed_after_the_import()
    {
        var delta = BulkDelta();
        var s = Before();
        ImportSettingsRules.Apply(s, delta);
        s.SelectedVersion = "1.8.9";
        ImportSettingsRules.Revert(s, delta);
        Assert.Equal("1.8.9", s.SelectedVersion);
        Assert.Single(s.ModProfiles);
    }

    [Fact]
    public void An_old_journal_with_only_AddProfile_still_reverts()
    {
        // The shape a journal written before AddProfiles existed has: no AddProfiles field at all.
        var json = "{\"AddProfile\":{\"Id\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"Name\":\"Imported\",\"Version\":\"1.21.1\"},"
                   + "\"SetSelectedVersion\":\"1.21.1\",\"SetSelectedProfileId\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                   + "\"PreviousSelectedVersion\":\"1.20.1\",\"PreviousSelectedProfileId\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"}";
        var delta = System.Text.Json.JsonSerializer.Deserialize<SettingsDelta>(json)!;
        Assert.Null(delta.AddProfiles);
        var s = Before();
        ImportSettingsRules.Apply(s, delta);
        Assert.Equal(2, s.ModProfiles.Count);
        ImportSettingsRules.Revert(s, delta);
        Assert.Single(s.ModProfiles);
        Assert.Equal("1.20.1", s.SelectedVersion);
    }

    [Fact]
    public void A_delta_with_both_fields_adds_a_shared_profile_once()
    {
        var shared = Prof('c', "1.21.1");
        var delta = new SettingsDelta { AddProfile = shared, AddProfiles = new List<ModProfile> { shared, Prof('d', "1.20.1") } };
        var s = Before();
        ImportSettingsRules.Apply(s, delta);
        Assert.Equal(3, s.ModProfiles.Count);
        ImportSettingsRules.Revert(s, delta);
        Assert.Single(s.ModProfiles);
    }

    // ---- the --import-plan report ----

    private static DetectedInstance Setup(SourceKind kind, string name, string? version, LoaderKind loader = LoaderKind.Fabric,
        long? playtime = null, bool pinned = false) =>
        new()
        {
            Kind = kind, Name = name, McVersion = version, Loader = loader, PlaytimeSeconds = playtime, Pinned = pinned,
            GameDir = @"C:\Users\Will Henry\AppData\Roaming\PrismLauncher\instances\" + name + @"\minecraft",
        };

    [Fact]
    public void Bulk_report_groups_by_version_and_skips_with_the_exact_reasons()
    {
        var found = new[]
        {
            Setup(SourceKind.Prism, "Survival", "1.21.1", playtime: 7200),
            Setup(SourceKind.Modrinth, "Fresh", "1.21.1", playtime: 600),
            Setup(SourceKind.CurseForge, "Forge pack", "1.21.1", LoaderKind.Forge),
            Setup(SourceKind.Prism, "Old", "1.20.1"),
            Setup(SourceKind.Prism, "Too new", "1.21.5"),
            Setup(SourceKind.Prism, "Mystery", null),
            Setup(SourceKind.CurseForge, "Forge 189", "1.8.9", LoaderKind.Forge),
        };
        var report = ImportBulkReport.Group(found, Supported);

        Assert.Equal(new[] { "1.21.1", "1.20.1", "1.8.9" }, report.Groups.Select(g => g.Version));
        var main = report.Groups[0];
        Assert.Equal("Survival", main.Winner.Name); // clear playtime winner (1.5x rule)
        Assert.False(main.NeedsChoice);
        Assert.Equal(SetupRole.Full, main.Setups[0].Role);
        Assert.Equal(SetupRole.ModsAndPacks, main.Setups.Single(s => s.Name == "Fresh").Role);
        var forge = main.Setups.Single(s => s.Name == "Forge pack");
        Assert.Equal(SetupRole.PacksOnly, forge.Role);
        Assert.Equal("Its mods were made for a different kind of Minecraft mod setup, so only its packs come over.", forge.Note);
        // Forge on 1.8.9 is Origin's legacy Forge path: its mods do come over.
        Assert.Null(report.Groups[2].Setups[0].Note);

        Assert.Equal(2, report.Skipped.Count);
        Assert.Equal("Origin doesn't support Minecraft 1.21.5, so this setup can't come over.", report.Skipped.Single(s => s.Name == "Too new").Reason);
        Assert.Contains("couldn't tell which Minecraft version", report.Skipped.Single(s => s.Name == "Mystery").Reason);
    }

    [Fact]
    public void Bulk_report_asks_for_a_choice_when_the_winner_is_unclear()
    {
        var report = ImportBulkReport.Group(new[]
        {
            Setup(SourceKind.Prism, "A", "1.21.1"), Setup(SourceKind.Prism, "B", "1.21.1"),
        }, Supported);
        Assert.True(report.Groups.Single().NeedsChoice);
    }

    private static string WriteBulk(ImportBulkReport.Report report)
    {
        using var stream = new MemoryStream();
        using (var w = new System.Text.Json.Utf8JsonWriter(stream, new System.Text.Json.JsonWriterOptions { Indented = true }))
        {
            w.WriteStartObject();
            ImportBulkReport.Write(w, report);
            w.WriteEndObject();
        }
        return System.Text.Encoding.UTF8.GetString(stream.ToArray());
    }

    [Fact]
    public void Bulk_report_json_has_no_paths_and_names_a_path_like_name_by_its_last_segment()
    {
        var found = new[]
        {
            Setup(SourceKind.Prism, "Survival", "1.21.1"),
            Setup(SourceKind.Folder, @"C:\Users\Will Henry\Games\My Pack", "1.21.1"),
            Setup(SourceKind.Prism, "Too new", "1.21.5"),
        };
        var json = WriteBulk(ImportBulkReport.Group(found, Supported));
        foreach (var forbidden in new[] { "C:", @"Users\\", "Will Henry", "AppData", "PrismLauncher", "instances", "/home" })
            Assert.DoesNotContain(forbidden, json);
        using var doc = System.Text.Json.JsonDocument.Parse(json);
        var bulk = doc.RootElement.GetProperty("bulk");
        Assert.Equal("scan-grouping", bulk.GetProperty("source").GetString());
        Assert.Equal(1, bulk.GetProperty("groups").GetArrayLength());
        Assert.Equal(1, bulk.GetProperty("skipped").GetArrayLength());
        Assert.Contains("My Pack", json); // cut down to the folder's name, not dropped
    }

    [Fact]
    public void The_failure_note_is_the_plain_reason_without_the_exception_message_or_a_path()
    {
        var ex = new UnauthorizedAccessException(@"Access to the path 'C:\Users\Will Henry\secret\accounts.json' is denied.");
        var json = ImportBulkReport.ErrorJson(ex);
        Assert.DoesNotContain(ex.Message, json);
        foreach (var forbidden in new[] { "C:", "Users", "Will Henry", "secret" })
            Assert.DoesNotContain(forbidden, json);
        using var doc = System.Text.Json.JsonDocument.Parse(json);
        Assert.Contains("accounts.json", doc.RootElement.GetProperty("error").GetString());
    }

    // ---- the Done page's reading of summary.txt ----

    [Fact]
    public void Summary_view_reads_every_section_and_the_outcome()
    {
        var text = string.Join("\n",
            "Origin import", "From: Prism: Survival (Minecraft 1.21.1, Fabric)", "Into: Minecraft 1.21.1", "Cancelled: nothing was changed.", "",
            "Imported", "  - Xaero's Minimap [0.6 MB]", "  - options.txt", "",
            "Reused game files", "  3 files, 2.9 MB", "",
            "Reused caches", "  (none)", "",
            "Needs attention", "  - Create [4 MB]: Imported but turned off: needs Fabric Kotlin", "",
            "Not imported", "  - Sodium (sodium.jar): Origin includes Sodium 0.6.13", "",
            "Settings files", "  3 new, 1 replaced", "");
        var view = ImportSummaryView.Parse(text);
        Assert.Equal("Cancelled: nothing was changed.", view.Outcome);
        var titles = view.Blocks.Select(b => b.Title).ToList();
        Assert.Equal(new[] { "Imported", "Reused game files", "Reused caches", "Needs attention", "Not imported", "Settings files" }, titles);
        Assert.Equal(new[] { "Xaero's Minimap [0.6 MB]", "options.txt" }, view.Blocks[0].Lines);
        Assert.Equal(new[] { "3 files, 2.9 MB" }, view.Blocks[1].Lines);
        Assert.Empty(view.Blocks[2].Lines);
        Assert.Equal(SummarySection.NeedsAttention, view.Blocks[3].Section);
        Assert.Equal("Sodium (sodium.jar): Origin includes Sodium 0.6.13", view.Blocks[4].Lines[0]);
    }

    [Fact]
    public void Summary_view_of_nothing_is_empty()
    {
        Assert.Empty(ImportSummaryView.Parse(null).Blocks);
        Assert.Empty(ImportSummaryView.Parse("").Blocks);
        Assert.Null(ImportSummaryView.Parse("Origin import\nFrom: x\n").Outcome);
    }
}
