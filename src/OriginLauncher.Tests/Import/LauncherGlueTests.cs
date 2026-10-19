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

    // ---- first-run card ----

    [Theory]
    [InlineData(false, false, true, 1, true)]
    [InlineData(true, false, true, 1, false)]   // already shown once: never again
    [InlineData(false, true, true, 1, false)]   // opened with --import: the panel is already open
    [InlineData(false, false, false, 1, false)] // nobody can import yet: keep the one-time card for later
    [InlineData(false, false, true, 0, false)]  // nothing found: nothing to offer
    public void First_run_card_shows_only_when_everything_allows_it(bool seen, bool flag, bool canImport, int found, bool expected) =>
        Assert.Equal(expected, ImportPrompt.ShouldShow(seen, flag, canImport, found));

    private static DetectedInstance Inst(SourceKind k, string? version = "1.21.1") =>
        new() { Kind = k, Name = "x", GameDir = "x", McVersion = version };

    private static readonly string[] Supported = { "1.21.1", "1.20.1", "1.8.9" };

    [Fact]
    public void First_run_card_counts_only_qualifying_setups()
    {
        var found = new[] { Inst(SourceKind.Prism, "1.21.1"), Inst(SourceKind.Prism, "1.21.5"), Inst(SourceKind.Modrinth, null), Inst(SourceKind.CurseForge, "1.8.9") };
        var qualifying = ImportPrompt.Qualifying(found, Supported);
        Assert.Equal(new[] { "1.21.1", "1.8.9" }, qualifying.Select(i => i.McVersion));
        Assert.True(ImportPrompt.ShouldShow(false, false, true, qualifying.Count));
    }

    [Fact]
    public void First_run_card_does_not_show_when_every_setup_is_on_an_unsupported_version()
    {
        var found = new[] { Inst(SourceKind.Prism, "1.21.5"), Inst(SourceKind.Prism, "1.12.2"), Inst(SourceKind.Modrinth, null) };
        var qualifying = ImportPrompt.Qualifying(found, Supported);
        Assert.Empty(qualifying);
        Assert.False(ImportPrompt.ShouldShow(false, false, true, qualifying.Count));
    }

    [Fact]
    public void First_run_card_copy_names_the_launcher_for_one_setup_and_is_generic_for_several()
    {
        Assert.Equal("Bring over your setup from Prism Launcher?", ImportPrompt.Question(new[] { Inst(SourceKind.Prism) }));
        Assert.Equal("Bring over your setup from the Minecraft launcher?", ImportPrompt.Question(new[] { Inst(SourceKind.OfficialLauncher) }));
        Assert.Equal("Bring over your setup from the Modrinth App?", ImportPrompt.Question(new[] { Inst(SourceKind.Modrinth) }));
        Assert.Equal("Bring over your Minecraft setups?", ImportPrompt.Question(new[] { Inst(SourceKind.Prism), Inst(SourceKind.Prism) }));
        Assert.Equal("Bring over your Minecraft setups?", ImportPrompt.Question(new[] { Inst(SourceKind.Prism), Inst(SourceKind.CurseForge) }));
        Assert.Equal("Bring over your Minecraft setups?", ImportPrompt.Title);
        Assert.Equal("Import", ImportPrompt.ImportLabel);
        Assert.Equal("Not now", ImportPrompt.DismissLabel);
    }

    [Fact]
    public void Existing_players_start_with_the_card_unseen_so_they_see_it_once()
    {
        Assert.False(new LauncherSettings().ImportPromptSeen);
        Assert.True(ImportPrompt.ShouldShow(new LauncherSettings().ImportPromptSeen, false, true, 1));
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
        Assert.Equal("Forge mods can't come over. Origin runs Fabric, except on 1.8.9.", forge.Note);
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
