using OriginLauncher.App.Core.Desktop;
using Xunit;

namespace OriginLauncher.Tests;

// The Linux launcher's desktop rules (Core/Desktop), pure so they run on every OS: which window "Switch to Minecraft"
// brings forward, the desktop's "animations off" setting, and when the window can't open at all.
public class LinuxDesktopTests
{
    private static GameWindowPick.Candidate W(nint id, int? pid, string? title) => new(id, pid, title);

    [Fact]
    public void The_games_own_process_id_picks_its_window_among_several_games()
    {
        // Two accounts playing at once (GLFW sets _NET_WM_PID): each Play brings its own game, never the other.
        var windows = new[] { W(1, 400, "Firefox"), W(2, 1234, "Minecraft* 1.21.1"), W(3, 5678, "Minecraft* 1.21.1") };
        Assert.Equal((nint)3, GameWindowPick.For(windows, 5678));
        Assert.Equal((nint)2, GameWindowPick.For(windows, 1234));
    }

    [Fact]
    public void A_189_window_without_a_process_id_is_found_by_its_title()
    {
        // LWJGL 2 (1.8.9) sets no _NET_WM_PID; a browser tab about Minecraft carries its browser's pid and is skipped.
        var windows = new[] { W(1, 400, "Minecraft Wiki - Firefox"), W(2, null, "Minecraft 1.8.9"), W(3, null, "Terminal") };
        Assert.Equal((nint)2, GameWindowPick.For(windows, 999));
    }

    [Fact]
    public void Two_untagged_Minecraft_windows_are_never_guessed_between()
    {
        var windows = new[] { W(1, null, "Minecraft 1.8.9"), W(2, null, "Minecraft 1.8.9") };
        Assert.Null(GameWindowPick.For(windows, 999));
        Assert.Null(GameWindowPick.For([], 999));
    }

    [Theory]
    [InlineData("false\n", true)]
    [InlineData("true\n", false)]
    [InlineData("", null)]
    [InlineData(null, null)]
    [InlineData("No such schema", null)]
    public void Gnome_enable_animations_reads_as_reduce_motion(string? output, bool? reduce) =>
        Assert.Equal(reduce, ReduceMotion.FromGnome(output));

    [Fact]
    public void Kde_instant_animation_speed_reads_as_reduce_motion()
    {
        Assert.True(ReduceMotion.FromKdeGlobals("[General]\nfoo=1\n\n[KDE]\nAnimationDurationFactor=0\n"));
        Assert.False(ReduceMotion.FromKdeGlobals("[KDE]\nAnimationDurationFactor=0.5\n"));
        // The same key under another group is not KDE's animation speed.
        Assert.Null(ReduceMotion.FromKdeGlobals("[Other]\nAnimationDurationFactor=0\n[KDE]\nSingleClick=false\n"));
        Assert.Null(ReduceMotion.FromKdeGlobals(null));
    }

    [Fact]
    public void Either_desktop_saying_off_turns_motion_off()
    {
        Assert.True(ReduceMotion.Combine(true, null));
        Assert.True(ReduceMotion.Combine(null, true));
        Assert.False(ReduceMotion.Combine(false, null));
        Assert.False(ReduceMotion.Combine(null, null));
    }

    [Fact]
    public void A_session_without_an_X_display_is_explained_not_silent()
    {
        Assert.Null(WindowSession.WindowBlocker(":0", "wayland-0")); // Wayland desktop with XWayland: opens
        Assert.Null(WindowSession.WindowBlocker(":1", null));        // X11 desktop
        Assert.Contains("XWayland", WindowSession.WindowBlocker(null, "wayland-0"));
        Assert.Contains("desktop session", WindowSession.WindowBlocker("", ""));
    }
}
