using System.Runtime.InteropServices;
using OriginLauncher.App.Core.Updates;
using Xunit;

namespace OriginLauncher.Tests;

// The Mac self-update picks its own architecture's zip and .sha256 from a release that also carries the
// Windows files and the two disk images (MacReleaseAssets; the names launcher-release.yml publishes).
public sealed class MacReleaseAssetsTests : IDisposable
{
    // Every file a release carries once the Mac jobs ran (docs/RELEASING.md -> Mac).
    private static readonly string[] ReleaseFiles =
    {
        "OriginLauncher-win-x64.zip", "OriginLauncher-win-x64.zip.sha256", "OriginLauncher-Setup.exe",
        "OriginLauncher-osx-arm64.zip", "OriginLauncher-osx-arm64.zip.sha256",
        "OriginLauncher-osx-x64.zip", "OriginLauncher-osx-x64.zip.sha256",
        "Origin-Mac-AppleSilicon.dmg", "Origin-Mac-Intel.dmg",
    };

    private readonly string _dir = Path.Combine(Path.GetTempPath(), "origin-mac-assets-" + Guid.NewGuid().ToString("N"));

    public MacReleaseAssetsTests() => Directory.CreateDirectory(_dir);

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch (IOException) { }
    }

    // The same match UpdateService.CheckAsync does (exact name, case-insensitive), so a name that matches
    // two files, or the other arch's file, fails here.
    private static string[] Matches(string wanted) =>
        ReleaseFiles.Where(f => string.Equals(f, wanted, StringComparison.OrdinalIgnoreCase)).ToArray();

    [Fact]
    public void Apple_silicon_takes_the_arm64_zip_and_its_hash()
    {
        Assert.Equal("OriginLauncher-osx-arm64.zip", MacReleaseAssets.ZipNameFor(Architecture.Arm64));
        Assert.Equal("OriginLauncher-osx-arm64.zip.sha256", MacReleaseAssets.Sha256NameFor(Architecture.Arm64));
        Assert.Single(Matches(MacReleaseAssets.ZipNameFor(Architecture.Arm64)));
        Assert.Single(Matches(MacReleaseAssets.Sha256NameFor(Architecture.Arm64)));
    }

    [Fact]
    public void Intel_takes_the_x64_zip_and_its_hash()
    {
        Assert.Equal("OriginLauncher-osx-x64.zip", MacReleaseAssets.ZipNameFor(Architecture.X64));
        Assert.Equal("OriginLauncher-osx-x64.zip.sha256", MacReleaseAssets.Sha256NameFor(Architecture.X64));
        Assert.Single(Matches(MacReleaseAssets.ZipNameFor(Architecture.X64)));
        Assert.Single(Matches(MacReleaseAssets.Sha256NameFor(Architecture.X64)));
    }

    [Fact]
    public void The_two_architectures_never_share_a_file()
    {
        Assert.NotEqual(MacReleaseAssets.ZipNameFor(Architecture.Arm64), MacReleaseAssets.ZipNameFor(Architecture.X64), StringComparer.OrdinalIgnoreCase);
        Assert.DoesNotContain("win", MacReleaseAssets.ZipNameFor(Architecture.Arm64), StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("win", MacReleaseAssets.ZipNameFor(Architecture.X64), StringComparison.OrdinalIgnoreCase);
    }

    [Theory]
    [InlineData(Architecture.X86)]
    [InlineData(Architecture.Arm)]
    [InlineData(Architecture.Wasm)]
    public void An_architecture_without_a_mac_build_is_offered_nothing(Architecture arch)
    {
        Assert.Empty(Matches(MacReleaseAssets.ZipNameFor(arch)));
        Assert.Empty(Matches(MacReleaseAssets.Sha256NameFor(arch)));
    }

    // Stale-download cleanup works by prefix: cleaning one arch's old zips must never touch the other arch's file
    // or the Windows one (a Mac and a Parallels Windows install can share a synced folder; cheap to rule out).
    [Fact]
    public void Cleaning_one_architectures_old_downloads_leaves_the_others_alone()
    {
        var armOld = Touch("OriginLauncher-osx-arm64-1.0.80.zip");
        var x64Old = Touch("OriginLauncher-osx-x64-1.0.80.zip");
        var winOld = Touch("OriginLauncher-win-x64-1.0.80.zip");

        Assert.Equal(1, UpdateVerifier.DeleteStaleDownloads(_dir, MacReleaseAssets.ZipPrefixFor(Architecture.Arm64), new Version(1, 0, 81)));
        Assert.False(File.Exists(armOld));
        Assert.True(File.Exists(x64Old));
        Assert.True(File.Exists(winOld));
    }

    // MAC-4: the Intel build under Rosetta (process X64, machine Arm64) updates to the Apple Silicon zip and says so
    // once; a build that matches its machine keeps its own zip and shows nothing.
    [Theory]
    [InlineData(Architecture.Arm64, Architecture.Arm64, "OriginLauncher-osx-arm64")]
    [InlineData(Architecture.X64, Architecture.X64, "OriginLauncher-osx-x64")]
    [InlineData(Architecture.X64, Architecture.Arm64, "OriginLauncher-osx-arm64")]
    public void An_update_takes_the_zip_for_the_machines_cpu(Architecture process, Architecture machine, string prefix)
    {
        Assert.Equal(prefix, MacReleaseAssets.ZipPrefixForUpdate(process, machine));
        Assert.Single(Matches(MacReleaseAssets.ZipPrefixForUpdate(process, machine) + ".zip"));
        Assert.Equal(process == Architecture.X64 && machine == Architecture.Arm64,
            MacReleaseAssets.NativeBuildNotice(process, machine) != null);
    }

    [Fact]
    public void The_apple_silicon_download_is_a_released_disk_image()
    {
        Assert.Single(Matches(MacReleaseAssets.AppleSiliconDmg));
        Assert.Single(Matches(MacReleaseAssets.IntelDmg));
        Assert.EndsWith("/releases/latest/download/" + MacReleaseAssets.AppleSiliconDmg, MacReleaseAssets.AppleSiliconDownload.AbsoluteUri);
    }

    private string Touch(string name)
    {
        var p = Path.Combine(_dir, name);
        File.WriteAllText(p, "x");
        return p;
    }
}
