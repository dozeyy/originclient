using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Updates;
using Xunit;

namespace OriginLauncher.Tests;

// The Linux launcher's player-facing words for missing system pieces and for a copy that can't update itself. Pure, so
// they run in every mode (build-check on Windows, the Linux "Core tests" step).
public sealed class LinuxPlatformWordsTests
{
    [Theory]
    [InlineData("ID=ubuntu\nID_LIKE=debian\n", LinuxPackages.Family.Debian)]
    [InlineData("NAME=\"Linux Mint\"\nID=linuxmint\nID_LIKE=\"ubuntu debian\"\n", LinuxPackages.Family.Debian)]
    [InlineData("ID=fedora\nVERSION_ID=45\n", LinuxPackages.Family.Fedora)]
    [InlineData("ID=\"almalinux\"\nID_LIKE=\"rhel centos fedora\"\n", LinuxPackages.Family.Fedora)]
    [InlineData("ID=arch\n", LinuxPackages.Family.Arch)]
    [InlineData("ID=cachyos\nID_LIKE=arch\n", LinuxPackages.Family.Arch)]
    [InlineData("ID=\"opensuse-tumbleweed\"\nID_LIKE=\"opensuse suse\"\n", LinuxPackages.Family.Suse)]
    [InlineData("ID=nixos\n", LinuxPackages.Family.Unknown)]
    [InlineData("", LinuxPackages.Family.Unknown)]
    public void The_distro_family_comes_from_os_release(string osRelease, LinuxPackages.Family family) =>
        Assert.Equal(family, LinuxPackages.FamilyOf(osRelease));

    [Fact]
    public void The_xrandr_command_is_in_the_players_own_distro_words()
    {
        Assert.Equal("sudo apt install x11-xserver-utils", LinuxPackages.InstallCommand(LinuxPackages.Xrandr, LinuxPackages.Family.Debian));
        Assert.Equal("sudo dnf install xrandr", LinuxPackages.InstallCommand(LinuxPackages.Xrandr, LinuxPackages.Family.Fedora));
        Assert.Equal("sudo pacman -S xorg-xrandr", LinuxPackages.InstallCommand(LinuxPackages.Xrandr, LinuxPackages.Family.Arch));
        Assert.Equal("sudo dnf install openssl3-libs", LinuxPackages.InstallCommand(LinuxPackages.OpenSsl3, LinuxPackages.Family.Fedora));
        // An unknown distro still gets a command: every family's.
        var unknown = LinuxPackages.InstallCommand(LinuxPackages.Xrandr, LinuxPackages.Family.Unknown);
        Assert.Contains("x11-xserver-utils", unknown);
        Assert.Contains("xorg-xrandr", unknown);
    }

    // LINUX-3: Play refused for a missing xrandr says what to install, with no "Retry" line (Retry can't help).
    [Fact]
    public void A_missing_xrandr_is_one_install_line_on_the_launch_screen()
    {
        var failure = PlayFailure.From(new SystemPieceMissingException("1.8.9", LinuxPackages.Xrandr, "sudo apt install x11-xserver-utils"), "1.8.9");
        Assert.Equal("Minecraft 1.8.9 needs xrandr", failure.Title);
        Assert.Equal("Install it once, then press Play: sudo apt install x11-xserver-utils", failure.Message);
        Assert.DoesNotContain(PlayFailure.RetryLine, failure.Message);
    }

    [Fact]
    public void Only_programs_on_path_count()
    {
        var dir = Path.Combine(Path.GetTempPath(), "origin-path-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            var tool = Path.Combine(dir, "xrandr");
            File.WriteAllText(tool, "#!/bin/sh\n");
            if (!OperatingSystem.IsWindows())
            {
                File.SetUnixFileMode(tool, UnixFileMode.UserRead | UnixFileMode.UserWrite);
                Assert.False(LinuxPackages.OnPath("xrandr", dir)); // not executable: exec would fail too
                File.SetUnixFileMode(tool, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            }
            Assert.True(LinuxPackages.OnPath("xrandr", dir));
            Assert.False(LinuxPackages.OnPath("xrandr", Path.Combine(dir, "nowhere")));
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }

    // LINUX-14: what is missing becomes one Settings line with what each costs and one command for all of them.
    [Fact]
    public void Missing_optional_pieces_make_one_note()
    {
        var none = LinuxOptionalPieces.Missing(_ => true, _ => true);
        Assert.Empty(none);
        Assert.Null(LinuxOptionalPieces.Note(none, LinuxPackages.Family.Debian));

        var missing = LinuxOptionalPieces.Missing(p => p != "xrandr", lib => !lib.StartsWith("libwebkit2gtk", StringComparison.Ordinal));
        Assert.Equal(new[] { "WebKitGTK", "xrandr" }, missing.Select(p => p.Name));
        var note = LinuxOptionalPieces.Note(missing, LinuxPackages.Family.Debian)!;
        Assert.Contains("Minecraft 1.8.9 can't start", note);
        Assert.EndsWith("sudo apt install libwebkit2gtk-4.1-0 x11-xserver-utils", note);
        Assert.EndsWith("sudo dnf install webkit2gtk4.1 xrandr", LinuxOptionalPieces.Note(missing, LinuxPackages.Family.Fedora));
    }

    // LINUX-7: a copy that can't replace itself says how to get the update; one that can says nothing.
    [Fact]
    public void A_copy_that_cant_update_itself_says_where_to_move_it()
    {
        Assert.Null(LinuxUpdateWords.Blocked(null, appImage: true));
        Assert.Contains("Move the AppImage to a folder you own", LinuxUpdateWords.Blocked(LinuxUpdateWords.ReadOnlyAppImage, appImage: true));
        Assert.Contains("Move the OriginLauncher folder", LinuxUpdateWords.Blocked(LinuxUpdateWords.ReadOnlyFolder, appImage: false));
        Assert.Contains("Flatpak", LinuxUpdateWords.Blocked(LinuxUpdateWords.FlatpakInstall, appImage: false));
        Assert.Contains("originclient.org", LinuxUpdateWords.Blocked("not started as OriginLauncher from its own folder", appImage: false));
    }
}
