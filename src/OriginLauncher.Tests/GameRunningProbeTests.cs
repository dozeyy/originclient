using System.Diagnostics;
using OriginLauncher.App.Core.GameFolders;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// GameRunningProbe: "is a game open in this folder" for a game this launcher run didn't start. The
/// matching rules are pure and run everywhere; the live test starts a real process whose arguments
/// name a temp folder (macOS/Linux, where the probe reads the process list: build-check on Linux,
/// mac-launcher's test leg on macOS). Windows keeps the latest.log lock probe, tested by the old
/// GameFolders tests.
/// </summary>
public sealed class GameRunningProbeTests
{
    private const StringComparison Exact = StringComparison.Ordinal;

    [Fact]
    public void The_game_dir_argument_is_matched_as_one_exact_argument()
    {
        // The probe compares against an already-normalized folder (its caller normalizes once), so the
        // test does too: on Windows GetFullPath turns this into C:\home\..., which is what argv matches.
        var dir = GameRunningProbe.Normalize("/home/p/OriginLauncher/instances/1.21.1/accounts/abc");
        string[] argv = ["/usr/bin/java", "-Xmx4G", "net.fabricmc.loader.impl.launch.knot.KnotClient", "--gameDir", dir, "--assetsDir", "x"];
        Assert.True(GameRunningProbe.ArgvNamesGameDir(argv, dir, Exact));
        Assert.True(GameRunningProbe.ArgvNamesGameDir(["java", "--gameDir", dir + "/"], dir, Exact));
        Assert.True(GameRunningProbe.ArgvNamesGameDir(["java", "--gameDir=" + dir], dir, Exact));
        Assert.False(GameRunningProbe.ArgvNamesGameDir(["java", "--gameDir", dir + "-2"], dir, Exact));
        Assert.False(GameRunningProbe.ArgvNamesGameDir(["java", "--assetsDir", dir], dir, Exact));
        Assert.False(GameRunningProbe.ArgvNamesGameDir(["java", "--gameDir"], dir, Exact));
    }

    [Fact]
    public void A_joined_command_line_matches_a_folder_with_spaces_but_not_a_longer_name()
    {
        // macOS: ps joins the arguments with spaces, and the Mac folder itself holds one.
        var dir = "/Users/p/Library/Application Support/OriginLauncher/instances/1.21.1/accounts/abc";
        var line = $"/opt/java/bin/java -Xmx4G net.minecraft.client.main.Main --username p --gameDir {dir} --assetsDir /x";
        Assert.True(GameRunningProbe.CommandLineNamesGameDir(line, dir, Exact));
        Assert.True(GameRunningProbe.CommandLineNamesGameDir($"java --gameDir {dir}", dir, Exact));
        Assert.True(GameRunningProbe.CommandLineNamesGameDir($"java --gameDir {dir}/ --x", dir, Exact));
        Assert.False(GameRunningProbe.CommandLineNamesGameDir($"java --gameDir {dir}-2 --x", dir, Exact));
        Assert.False(GameRunningProbe.CommandLineNamesGameDir($"java --gameDir {dir}x", dir, Exact));
        Assert.False(GameRunningProbe.CommandLineNamesGameDir($"java --assetsDir {dir}", dir, Exact));
        // the Mac's default volume is case-insensitive
        Assert.True(GameRunningProbe.CommandLineNamesGameDir(line.Replace("Application Support", "application support"), dir, StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void Only_java_processes_count_by_their_working_folder()
    {
        Assert.True(GameRunningProbe.IsJava("/usr/lib/jvm/java-21/bin/java"));
        Assert.True(GameRunningProbe.IsJava("javaw.exe"));
        Assert.False(GameRunningProbe.IsJava("/bin/bash")); // a terminal open in ~/.minecraft is not a game
    }

    // Bug run 11 PROF-10: on the Mac a Prism/MultiMC game (folder passed over stdin, not as --gameDir) is found by its
    // working folder, read from lsof's field output.
    [Fact]
    public void The_mac_working_folder_list_reads_lsof_field_output()
    {
        var output = "p812\nfcwd\nn/Users/p/Library/Application Support/PrismLauncher/instances/Survival/.minecraft\np913\nfcwd\nn/Users/p\r\n";
        Assert.Equal(
            new[] { "/Users/p/Library/Application Support/PrismLauncher/instances/Survival/.minecraft", "/Users/p" },
            GameRunningProbe.LsofCwds(output));
        Assert.Empty(GameRunningProbe.LsofCwds(""));
        Assert.Empty(GameRunningProbe.LsofCwds("p1\nfcwd\nn\n"));
    }

    [Fact]
    public void A_folder_that_does_not_exist_is_never_running()
    {
        Assert.False(GameRunningProbe.IsRunningIn(Path.Combine(Path.GetTempPath(), "origin-probe-missing-" + Guid.NewGuid().ToString("N"))));
        Assert.False(GameRunningProbe.IsRunningIn(""));
    }

    [Fact]
    public void A_live_process_naming_the_folder_is_seen_until_it_exits()
    {
        if (OperatingSystem.IsWindows()) return; // Windows uses the latest.log lock, not the process list

        var dir = Path.Combine(Path.GetTempPath(), "origin probe " + Guid.NewGuid().ToString("N")); // a space, like the Mac's folder
        Directory.CreateDirectory(dir);
        try
        {
            GameRunningProbe.Refresh();
            Assert.False(GameRunningProbe.IsRunningIn(dir));

            // sh -c '<script>' $0 $1 $2: the extra words stay in sh's own argv; "; true" stops sh from
            // exec'ing sleep (which would drop them).
            var start = new ProcessStartInfo("/bin/sh") { UseShellExecute = false };
            foreach (var arg in new[] { "-c", "sleep 30; true", "probe", "--gameDir", dir }) start.ArgumentList.Add(arg);
            using var game = Process.Start(start)!;
            try
            {
                GameRunningProbe.Refresh();
                Assert.True(GameRunningProbe.IsRunningIn(dir));
                Assert.True(GameRunningProbe.IsRunningIn(dir + Path.DirectorySeparatorChar));
                Assert.False(GameRunningProbe.IsRunningIn(Path.GetTempPath()));
            }
            finally
            {
                game.Kill(entireProcessTree: true);
                game.WaitForExit();
            }
            GameRunningProbe.Refresh();
            Assert.False(GameRunningProbe.IsRunningIn(dir));
        }
        finally
        {
            GameRunningProbe.Refresh();
            Directory.Delete(dir, recursive: true);
        }
    }
}
