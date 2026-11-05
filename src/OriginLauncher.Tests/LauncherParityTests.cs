using System.Text.RegularExpressions;
using OriginLauncher.App.Core.Auth;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// The Windows, Mac and Linux launchers keep the same rules (DECISIONS 2026-11-02: the Mac launcher has every screen and
/// feature of the Windows one). A source scan, because the three UIs can't all build on one OS: each fix of bug run 5
/// that lives in UI wiring is checked on every launcher here.
/// </summary>
public sealed class LauncherParityTests
{
    private static readonly string Src = Path.Combine(TestRepo.Root, "src");

    private static string Read(params string[] parts) => File.ReadAllText(Path.Combine(new[] { Src }.Concat(parts).ToArray()));

    // Every UI source file of the three launchers (render-shot scenes and headless startup tools left out).
    private static IEnumerable<string> UiFiles()
    {
        string[] roots =
        [
            Path.Combine(Src, "OriginLauncher.App", "UI"),
            Path.Combine(Src, "OriginLauncher.Mac", "UI"),
            Path.Combine(Src, "OriginLauncher.Desktop", "Pages"),
            Path.Combine(Src, "OriginLauncher.Desktop", "Overlays"),
            Path.Combine(Src, "OriginLauncher.Desktop", "Shell"),
        ];
        var files = roots.SelectMany(r => Directory.EnumerateFiles(r, "*.cs", SearchOption.AllDirectories))
            .Concat(Directory.EnumerateFiles(Path.Combine(Src, "OriginLauncher.App"), "MainWindow*.cs"))
            .Concat(Directory.EnumerateFiles(Path.Combine(Src, "OriginLauncher.Mac"), "MainWindow*.cs"));
        return files.Where(f => !Path.GetFileName(f).Contains("Shots", StringComparison.Ordinal)
                                && !f.Contains(Path.DirectorySeparatorChar + "Shots" + Path.DirectorySeparatorChar)
                                && Path.GetFileName(f) != "DesktopEntry.cs"); // startup, before any window: already guarded
    }

    [Fact]
    public void No_click_writes_settings_with_the_throwing_Update()
    {
        // JL-10: a busy settings.json must never reach the crash window from a click. UI code calls TryUpdate (or the
        // Linux views' LauncherState.Write, which uses it); passing SettingsStore.Update as a delegate to Core code that
        // catches the IOException itself (RepairExecutor) is not a call and stays allowed.
        var offenders = UiFiles()
            .SelectMany(f => File.ReadLines(f).Select((line, i) => (f, i, line)))
            .Where(x => x.line.Contains("SettingsStore.Update(", StringComparison.Ordinal)
                        && !x.line.TrimStart().StartsWith("//", StringComparison.Ordinal))
            .Select(x => $"{Path.GetFileName(x.f)}:{x.i + 1}")
            .ToList();
        Assert.True(offenders.Count == 0, "SettingsStore.Update( in UI code: " + string.Join(", ", offenders));
    }

    [Fact]
    public void Every_launcher_sets_a_neutral_line_when_a_game_exits()
    {
        // JL-1: the exit code is never a red status line by itself; only the crash verdict may turn it red.
        foreach (var file in new[]
                 {
                     Read("OriginLauncher.App", "UI", "Pages", "HomePage.xaml.cs"),
                     Read("OriginLauncher.Mac", "UI", "Pages", "HomePage.Play.cs"),
                     Read("OriginLauncher.Desktop", "Shell", "LaunchController.cs"),
                 })
        {
            Assert.Contains("GameExitStatus.OnExit()", file);
            Assert.Contains("GameExitStatus.AfterVerdict(", file);
            Assert.DoesNotMatch(new Regex(@"Note\([^;]*exited with code"), file);
        }
    }

    [Fact]
    public void Every_launcher_offers_the_update_when_Play_is_refused_for_one()
    {
        // JL-4: Windows' Update required prompt (v1.0.85) on Mac and Linux too, with the same words.
        Assert.Contains("UpdateRequiredRefused?.Invoke(new PlayAfterUpdate(", Read("OriginLauncher.Mac", "UI", "Pages", "HomePage.Play.cs"));
        Assert.Contains("Home.UpdateRequiredRefused +=", Read("OriginLauncher.Mac", "MainWindow.Update.cs"));
        Assert.Contains("UpdatePrompt.AskAsync(", Read("OriginLauncher.Desktop", "Shell", "LaunchController.cs"));
        foreach (var file in new[]
                 {
                     Read("OriginLauncher.App", "UI", "Pages", "HomePage.xaml.cs"),
                     Read("OriginLauncher.Mac", "UI", "Pages", "HomePage.Play.cs"),
                     Read("OriginLauncher.Desktop", "Shell", "LaunchController.cs"),
                 })
            Assert.Contains("UpdateRequiredWords.Status", file);
    }

    [Fact]
    public void Every_launcher_continues_play_after_the_update_opens_sign_in_when_signed_out_and_keeps_known_crashes_fast()
    {
        // JL-5 "Update and play", JL-7 signed-out Play, JL-13 the verify stamp after a known crash: all three launchers.
        foreach (var (home, window) in new[]
                 {
                     (Read("OriginLauncher.App", "UI", "Pages", "HomePage.xaml.cs"), Read("OriginLauncher.App", "MainWindow.xaml.cs")),
                     (Read("OriginLauncher.Mac", "UI", "Pages", "HomePage.Play.cs"), Read("OriginLauncher.Mac", "MainWindow.Update.cs")),
                     (Read("OriginLauncher.Desktop", "Shell", "LaunchController.cs"), Read("OriginLauncher.Desktop", "Shell", "MainWindow.cs")),
                 })
        {
            Assert.Contains("new PlayAfterUpdate(key, version", home);
            Assert.Contains("ResumeAfterUpdateAsync(PlayAfterUpdate play)", home);
            Assert.Contains("UpdateService.TakePlayAfterUpdate()", window);
            Assert.Contains("AccountLaunchBook.SignedOutPlayOpensSignIn(", home);
            Assert.Contains("crashedWhileStarting && verdict.InstallFilesSuspect", home);
            Assert.Contains("CrashMemory.ApplyRaise(s, game.Version, ram)", home);
            Assert.Contains("CrashMemory.ForLaunch(heap)", home);
        }
    }

    [Fact]
    public void Every_launcher_offers_sign_in_again_and_none_names_an_account_switcher()
    {
        // JL-6: there is no "account switcher" (the drawer is Accounts), removing the account isn't needed, and
        // every launcher's Play goes straight to the sign-in card from the prompt.
        Assert.Contains("SignInAgainRequested?.Invoke(", Read("OriginLauncher.App", "UI", "Pages", "HomePage.xaml.cs"));
        Assert.Contains("SignInAgainRequested?.Invoke(", Read("OriginLauncher.Mac", "UI", "Pages", "HomePage.Play.cs"));
        Assert.Contains("AskToSignInAgainAsync(", Read("OriginLauncher.Desktop", "Shell", "LaunchController.cs"));
        var said = Directory.EnumerateFiles(Src, "*.cs", SearchOption.AllDirectories)
            .Where(f => f.Contains("OriginLauncher.", StringComparison.Ordinal) && !f.Contains("OriginLauncher.Tests", StringComparison.Ordinal))
            .Where(f => File.ReadLines(f).Any(line => !line.TrimStart().StartsWith("//", StringComparison.Ordinal)
                && Regex.IsMatch(line, "\"[^\"]*(account switcher|re-add)[^\"]*\"", RegexOptions.IgnoreCase)))
            .Select(Path.GetFileName)
            .ToList();
        Assert.True(said.Count == 0, "a player-facing line still says account switcher / re-add: " + string.Join(", ", said));
    }

    [Fact]
    public void Only_a_refused_saved_sign_in_asks_to_sign_in_again()
    {
        Assert.True(SignInAgain.Needed(new MicrosoftAuthException(MicrosoftAuthException.TokenRefreshStage, SignInAgain.ExpiredLine)));
        // Microsoft limiting sign-ins or being down is never fixed by signing in again.
        Assert.False(SignInAgain.Needed(MicrosoftAuthException.Limited(MicrosoftAuthException.TokenRefreshStage, null)));
        Assert.False(SignInAgain.Needed(new MicrosoftAuthException(MicrosoftAuthException.TokenRefreshStage + "_unavailable", "down")));
        Assert.False(SignInAgain.Needed(new MicrosoftAuthException("xsts", "no Xbox profile")));
        Assert.Contains("Steve", SignInAgain.Prompt("Steve"));
    }

    [Fact]
    public void No_launcher_writes_the_dead_install_folder_setting()
    {
        // WP-1: nothing reads InstallPath (game files always live under OriginPaths.Instances). Windows dropped its
        // picker in f4d3d3bf, Mac and Linux kept a card that saved a folder nothing used. Never again on any OS.
        var writers = Directory.EnumerateFiles(Src, "*.cs", SearchOption.AllDirectories)
            .Where(f => f.Contains("OriginLauncher.", StringComparison.Ordinal) && !f.Contains("OriginLauncher.Tests", StringComparison.Ordinal)
                        && !f.Contains(Path.DirectorySeparatorChar + "obj" + Path.DirectorySeparatorChar)
                        && !f.Contains(Path.DirectorySeparatorChar + "bin" + Path.DirectorySeparatorChar))
            .SelectMany(f => File.ReadLines(f).Select((line, i) => (f, i, line)))
            .Where(x => !x.line.TrimStart().StartsWith("//", StringComparison.Ordinal)
                        && Regex.IsMatch(x.line, @"\.InstallPath\s*=[^=]"))
            .Select(x => $"{Path.GetFileName(x.f)}:{x.i + 1}")
            .ToList();
        Assert.True(writers.Count == 0, "InstallPath is written (nothing reads it): " + string.Join(", ", writers));
    }

    [Fact]
    public void Every_launcher_saves_typed_settings_on_close_and_has_the_page_keys_and_Enter_to_Play()
    {
        // WP-5: the Java arguments / resolution boxes save on focus loss, which closing the window never raises.
        Assert.Contains("FlushEdits()", Read("OriginLauncher.App", "MainWindow.xaml.cs"));
        Assert.Contains("Closing += (_, _) => Settings.FlushEdits()", Read("OriginLauncher.Mac", "MainWindow.Keys.cs"));
        Assert.Contains("FlushEdits()", Read("OriginLauncher.Desktop", "Shell", "MainWindow.cs"));
        Assert.Contains("public void FlushEdits()", Read("OriginLauncher.Desktop", "Pages", "SettingsPage.cs"));
        Assert.Contains("internal void FlushEdits()", Read("OriginLauncher.Mac", "UI", "Pages", "SettingsPage.axaml.cs"));
        // WP-6: Ctrl (Cmd on the Mac) + 1-4 open the four pages, and Enter on Home plays.
        foreach (var keys in new[]
                 {
                     Read("OriginLauncher.App", "MainWindow.xaml.cs"),
                     Read("OriginLauncher.Mac", "MainWindow.Keys.cs"),
                     Read("OriginLauncher.Desktop", "Shell", "MainWindow.cs"),
                 })
        {
            foreach (var key in new[] { "Key.D1", "Key.D2", "Key.D3", "Key.D4" }) Assert.Contains(key, keys);
            Assert.Contains("TryLaunchFromKeyboard()", keys);
        }
    }

    [Fact]
    public void Every_launcher_plays_offline_and_never_waits_on_github_for_the_failure_line()
    {
        // Bug run 8 (NET-1, NET-2, NET-3, LOC-2): the same offline Play, the same failure text and the same plain stops.
        foreach (var home in new[]
                 {
                     Read("OriginLauncher.App", "UI", "Pages", "HomePage.xaml.cs"),
                     Read("OriginLauncher.Mac", "UI", "Pages", "HomePage.Play.cs"),
                     Read("OriginLauncher.Desktop", "Shell", "LaunchController.cs"),
                 })
        {
            Assert.Contains("playingOffline = signedIn.Offline;", home);
            // N94-3: offline AND without the player's mods says both (one shared rule, MinecraftSessions.PlayingLine).
            Assert.Contains("MinecraftSessions.PlayingLine(runningMessage, playingOffline, withoutModsLine);", home);
            // MULTI-3: an older Sodium's settings left by a game that ended with no launcher listening go back first.
            Assert.Contains("ConfigOverlay.RestoreLeftover(gameDir, OriginPaths.ConfigOverlays(version, key))", home);
            Assert.Contains("LaunchFailureText.ForAsync(ex, upToDateMessage,", home);
            Assert.Contains("RequiredModMissingException or JavaPathUnreadableException)", home);
            Assert.DoesNotContain("await UpdateService.CheckAsync();", home);
        }
    }

    [Fact]
    public void Every_mods_page_checks_whose_jars_they_are_before_showing_them()
    {
        // X-1: a player's own Sodium/Iris/JEI must not read as Origin's built-in until the next Play.
        foreach (var page in new[]
                 {
                     Read("OriginLauncher.App", "UI", "Pages", "ModsPage.xaml.cs"),
                     Read("OriginLauncher.Mac", "UI", "Pages", "ModsPage.axaml.cs"),
                     Read("OriginLauncher.Desktop", "Pages", "ModsPage.cs"),
                 })
        {
            Assert.Contains("ModManager.WarmOwnership(folder)", page);
            Assert.Contains("mods.Where(m => !m.IsManaged && !m.Checking)", page);
        }
    }

    [Fact]
    public void The_mac_backdrop_pauses_and_decodes_like_windows()
    {
        // PERF-2 / PERF-3: off-thread decode with a small cache, and the drift held while nobody can see it.
        var stage = Read("OriginLauncher.Mac", "UI", "Controls", "ArtStage.cs");
        Assert.Contains("Task.Run(() => DecodeBest(key, tier))", stage);
        Assert.DoesNotContain("new TransformGroup\n", stage.Replace("\r\n", "\n"));
        Assert.Contains("Stage.SetWindowAway(", Read("OriginLauncher.Mac", "MainWindow.axaml.cs"));
        Assert.Contains("Task.Run(() => Load(key, tier))", Read("OriginLauncher.Desktop", "Shell", "ArtStage.cs"));
    }
}
