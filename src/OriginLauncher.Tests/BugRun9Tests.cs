using System.Text;
using OriginLauncher.App.Core.Auth.Sessions;
using OriginLauncher.App.Core.Components;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Updates;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// Bug run 9's launcher fixes that are pure logic: the Windows swap script's bounded waits and lock (UPG-1), a small
/// update that appears after its release was offered (UPG-3), a Home line that survives a tab or account switch
/// (MULTI-2), an older Sodium's settings left behind by a launcher closed mid-game (MULTI-3) and the offline line kept
/// beside the without-mods line (N94-3). Temp folders only.
/// </summary>
public sealed class BugRun9Tests : IDisposable
{
    private readonly string _root = Directory.CreateTempSubdirectory("bugrun9").FullName;

    public void Dispose()
    {
        try { Directory.Delete(_root, true); } catch { /* best effort */ }
    }

    // ---- UPG-1: the swap script never waits forever, and holds its lock while it copies ----------------------------

    [Fact]
    public void The_swap_script_bounds_every_wait()
    {
        var text = WindowsApplyScript.Text;
        var robocopy = text.Split("\r\n").Where(l => l.StartsWith("robocopy ", StringComparison.Ordinal)).ToList();
        Assert.Equal(2, robocopy.Count); // one pass, and one more only after a failed one
        Assert.All(robocopy, l => Assert.Contains(" /R:5 /W:1", l)); // robocopy's defaults are 1,000,000 x 30 s
        Assert.Contains("if not errorlevel 8 exit /b 0\r\n", text); // the second pass only after a failure (8+)
        Assert.Contains("if %waited% geq " + WindowsApplyScript.QuitWaitSeconds + " exit /b 1\r\n", text);

        // The lock is held around the whole wait + copy and released before the launcher is started again, so the new
        // launcher doesn't see a swap running and bow out.
        var call = text.IndexOf("call :swap 9>\"" + WindowsApplyScript.LockFileName + "\"\r\n", StringComparison.Ordinal);
        var start = text.IndexOf("start \"\" \"%ORIGIN_UPDATE_EXE%\"", StringComparison.Ordinal);
        var label = text.IndexOf("\r\n:swap\r\n", StringComparison.Ordinal);
        Assert.True(call >= 0 && start > call && label > start, "lock -> start -> subroutine order");
        // Still no path and no if-block in the script (SEC-1).
        Assert.DoesNotContain("(", text.Replace("2>nul", ""));
        Assert.All(Encoding.ASCII.GetBytes(text), b => Assert.True(b < 0x80));
    }

    [Fact]
    public void A_held_lock_reads_as_a_swap_running_and_a_leftover_one_does_not()
    {
        Assert.False(WindowsApplyScript.IsSwapRunning(_root)); // no lock file
        Assert.False(WindowsApplyScript.IsSwapRunning(Path.Combine(_root, "missing"))); // no folder
        var path = Path.Combine(_root, WindowsApplyScript.LockFileName);
        File.WriteAllText(path, "");
        Assert.False(WindowsApplyScript.IsSwapRunning(_root)); // left over, nobody holds it

        // Windows only: Linux file sharing is advisory, and the script only ever runs on Windows.
        if (!OperatingSystem.IsWindows()) return;
        using (new FileStream(path, FileMode.Open, FileAccess.Write, FileShare.Read)) // what cmd's 9> holds
            Assert.True(WindowsApplyScript.IsSwapRunning(_root));
        Assert.False(WindowsApplyScript.IsSwapRunning(_root));
    }

    // ---- UPG-3: a small update that shows up after its release was offered ---------------------------------------

    private static readonly Version V95 = new(1, 0, 95);
    private static readonly UpdatePrefetch.Conditions Free = new(GameRunning: false, Online: true, Metered: false);

    [Fact]
    public void A_late_delta_is_taken_until_the_stage_is_done_or_the_click_has_it()
    {
        var p = new UpdatePrefetch();
        Assert.True(p.MayTakeLateDelta(V95)); // waiting (game running, metered): just take it

        Assert.Equal(UpdatePrefetch.Start.Go, p.TryStart(V95, Free, DateTime.UtcNow, out _));
        Assert.True(p.MayTakeLateDelta(V95)); // the full zip is downloading: stopped, restarts from the delta

        Assert.True(p.Staged(V95));
        Assert.False(p.MayTakeLateDelta(V95)); // already staged: nothing left to download

        var clicked = new UpdatePrefetch();
        clicked.BeginInstall(V95, out _);
        Assert.False(clicked.MayTakeLateDelta(V95)); // the click owns it
    }

    // ---- MULTI-2: an idle account's line comes back for the version it was about ---------------------------------

    [Fact]
    public void An_idle_note_returns_only_for_its_own_version_and_only_while_idle()
    {
        var book = new AccountLaunchBook();
        book.SetNote("steve", new LaunchNote("Sodium 0.5 won't start on 1.21.1", LaunchTone.Warning, "1.21.1"));
        Assert.Equal("Sodium 0.5 won't start on 1.21.1", book.IdleNoteOf("steve", "1.21.1")?.Text);
        Assert.Null(book.IdleNoteOf("steve", "1.20.1")); // another version selected: the plain line
        Assert.Null(book.IdleNoteOf("steve", null));
        Assert.Null(book.IdleNoteOf("alex", "1.21.1"));

        book.SetNote("alex", new LaunchNote("no version", LaunchTone.Neutral));
        Assert.Null(book.IdleNoteOf("alex", "1.21.1")); // a note with no version never comes back

        Assert.NotNull(book.BeginPrepare("steve", "1.21.1")); // a new launch clears it
        Assert.Null(book.IdleNoteOf("steve", "1.21.1"));
    }

    // ---- MULTI-3: an older Sodium's settings left in place go back before anything reads them -------------------

    [Fact]
    public void A_leftover_configuration_is_switched_back_and_kept()
    {
        const string alt = "sodium-iris-0.6.5+1.8.1";
        var game = Path.Combine(_root, "game");
        var config = Path.Combine(game, "config");
        var overlays = Path.Combine(_root, "overlays");
        Directory.CreateDirectory(config);
        File.WriteAllText(Path.Combine(config, "sodium-options.json"), "{\"mine\":1}");

        Assert.False(ConfigOverlay.RestoreLeftover(game, overlays)); // the usual case: nothing to do

        // A profile on the older Sodium started; the launcher was closed, so nothing switched back at game exit.
        ConfigOverlay.Switch(config, overlays, alt);
        File.WriteAllText(Path.Combine(config, "sodium-options.json"), "{\"old\":1}");

        Assert.True(ConfigOverlay.RestoreLeftover(game, overlays));
        Assert.Equal("default", ConfigOverlay.Active(overlays));
        Assert.Equal("{\"mine\":1}", File.ReadAllText(Path.Combine(config, "sodium-options.json")));
        Assert.False(ConfigOverlay.RestoreLeftover(game, overlays));

        ConfigOverlay.Switch(config, overlays, alt); // the old Sodium's own changes were saved, not lost
        Assert.Equal("{\"old\":1}", File.ReadAllText(Path.Combine(config, "sodium-options.json")));
    }

    // ---- N94-3: offline and without the player's mods says both -------------------------------------------------

    [Fact]
    public void The_offline_line_survives_the_without_mods_line()
    {
        const string without = "Started without your mods - check Foo first";
        Assert.Equal("Running - Steve", MinecraftSessions.PlayingLine("Running - Steve", offline: false, null));
        Assert.Equal(MinecraftSessions.OfflinePlayLine, MinecraftSessions.PlayingLine("Running - Steve", offline: true, null));
        Assert.Equal(without, MinecraftSessions.PlayingLine("Running - Steve", offline: false, without));
        var both = MinecraftSessions.PlayingLine("Running - Steve", offline: true, without);
        Assert.StartsWith(MinecraftSessions.OfflinePlayLine, both, StringComparison.Ordinal);
        Assert.EndsWith(without, both, StringComparison.Ordinal);
    }
}
