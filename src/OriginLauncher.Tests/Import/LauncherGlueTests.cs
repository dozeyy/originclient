using OriginLauncher.App.Core.Import;
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

    [Fact]
    public void First_run_question_names_the_launcher()
    {
        DetectedInstance Inst(SourceKind k) => new() { Kind = k, Name = "x", GameDir = "x" };
        Assert.Equal("Bring over your mods and settings from Prism Launcher?", ImportPrompt.Question(new[] { Inst(SourceKind.Prism), Inst(SourceKind.Prism) }));
        Assert.Equal("Bring over your mods and settings from the Minecraft launcher?", ImportPrompt.Question(new[] { Inst(SourceKind.OfficialLauncher) }));
        Assert.Equal("Bring over your mods and settings from your other launchers?", ImportPrompt.Question(new[] { Inst(SourceKind.Prism), Inst(SourceKind.CurseForge) }));
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
