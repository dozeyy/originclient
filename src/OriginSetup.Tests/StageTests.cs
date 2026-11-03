using System;
using System.IO;
using System.Net;
using System.Threading;
using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

// Installer.Stage: everything up to the point of no return (download, verify,
// unpack into _setup-staging). The commit after it touches the registry and
// shortcuts, which only CI's Windows smoke test exercises.
[Collection("download")]
public class StageTests
{
    private const string Url = "https://example.test/launcher.zip";

    public StageTests() => Download.RetryDelay = TimeSpan.Zero;

    [Fact]
    public void DownloadsAndStagesTheRelease()
    {
        var tree = TestKit.MakeLauncherTree();
        var zip = File.ReadAllBytes(TestKit.Zip(tree));
        var server = new FakeServer().File(Url, zip, dropAfter: zip.Length / 2, drops: 1);
        var dir = Path.Combine(TestKit.Temp("inst"), "OriginLauncher");
        InstallProgress? last = null;
        bool sawDownload = false;
        var staged = Installer.Stage(dir, null, new LauncherRelease("1.0.40", Url, zip.Length),
            p => { last = p; sawDownload |= p.Stage == InstallStage.Downloading && p.Bytes > 0; }, CancellationToken.None, server.Client());

        Assert.Equal("1.0.40", staged.Version);
        TestKit.AssertSameTree(tree, staged.FilesDir);
        Assert.True(sawDownload);
        Assert.Equal(InstallStage.Unpacking, last!.Stage);
        Assert.InRange(last.Fraction, 0.94, 0.951);
    }

    [Fact]
    public void LooksTheReleaseUpWhenWelcomeCouldNot()
    {
        var tree = TestKit.MakeLauncherTree();
        var zip = File.ReadAllBytes(TestKit.Zip(tree));
        var server = new FakeServer()
            .Status(ReleaseFeed.ApiUrl, HttpStatusCode.Forbidden)
            .Redirect(ReleaseFeed.LatestPageUrl, "https://github.com/dozeyy/originclient/releases/tag/launcher-v1.0.41")
            .Json(ReleaseFeed.HashUrl("launcher-v1.0.41"), Sha256(zip) + "  OriginLauncher-win-x64.zip\n")
            .File(ReleaseFeed.AssetUrl("launcher-v1.0.41"), zip);
        var dir = Path.Combine(TestKit.Temp("inst"), "OriginLauncher");
        var staged = Installer.Stage(dir, null, null, _ => { }, CancellationToken.None, server.Client());
        Assert.Equal("1.0.41", staged.Version);
        TestKit.AssertSameTree(tree, staged.FilesDir);
    }

    [Fact]
    public void ADownloadThatDoesNotMatchTheReleaseHashFileIsRefused()
    {
        // IN-6: with the API rate-limited, a swapped zip (its CRCs all consistent) is caught by the .sha256 file.
        var zip = File.ReadAllBytes(TestKit.Zip(TestKit.MakeLauncherTree()));
        var server = new FakeServer()
            .Status(ReleaseFeed.ApiUrl, HttpStatusCode.Forbidden)
            .Redirect(ReleaseFeed.LatestPageUrl, "https://github.com/dozeyy/originclient/releases/tag/launcher-v1.0.41")
            .Json(ReleaseFeed.HashUrl("launcher-v1.0.41"), new string('a', 64) + "  OriginLauncher-win-x64.zip\n")
            .File(ReleaseFeed.AssetUrl("launcher-v1.0.41"), zip);
        var dir = Path.Combine(TestKit.Temp("inst"), "OriginLauncher");
        var ex = Assert.Throws<DownloadException>(() => Installer.Stage(dir, null, null, _ => { }, CancellationToken.None, server.Client()));
        Assert.Equal(DownloadException.Damaged, ex.Message);
        Assert.False(Directory.Exists(dir), "the folder Setup created is removed again");
    }

    [Fact]
    public void InstallsFromALocalZipWithoutTheNetwork()
    {
        var tree = TestKit.MakeLauncherTree();
        var dir = Path.Combine(TestKit.Temp("inst"), "OriginLauncher");
        var staged = Installer.Stage(dir, TestKit.Zip(tree), null, _ => { }, CancellationToken.None, new FakeServer().Client());
        TestKit.AssertSameTree(tree, staged.FilesDir);
        Assert.Equal("0.0.0", staged.Version); // the stand-in exe has no version resource
    }

    [Fact]
    public void AFailedDownloadLeavesAFreshFolderUntouched()
    {
        var parent = TestKit.Temp("inst");
        var dir = Path.Combine(parent, "OriginLauncher");
        var ex = Assert.Throws<DownloadException>(() =>
            Installer.Stage(dir, null, new LauncherRelease("1.0.40", Url), _ => { }, CancellationToken.None, new FakeServer().Client()));
        Assert.Equal(DownloadException.CantConnect, ex.Message);
        Assert.False(Directory.Exists(dir), "the folder Setup created is removed again");
    }

    [Fact]
    public void ADamagedDownloadLeavesAnExistingInstallAlone()
    {
        var dir = TestKit.Temp("existing");
        File.WriteAllText(Path.Combine(dir, "OriginLauncher.App.exe"), "the old launcher");
        var server = new FakeServer().File(Url, System.Text.Encoding.ASCII.GetBytes("<html>not a zip</html>"));
        var ex = Assert.Throws<DownloadException>(() =>
            Installer.Stage(dir, null, new LauncherRelease("1.0.40", Url), _ => { }, CancellationToken.None, server.Client()));
        Assert.Equal(DownloadException.Damaged, ex.Message);
        Assert.Equal("the old launcher", File.ReadAllText(Path.Combine(dir, "OriginLauncher.App.exe")));
        Assert.False(Directory.Exists(Path.Combine(dir, "_setup-staging")));
    }

    [Fact]
    public void TheRunningSetupBecomesTheUninstaller()
    {
        var setup = Path.Combine(TestKit.Temp("setup"), "OriginLauncher-Setup.exe");
        File.WriteAllText(setup, "the setup app");
        var dir = Path.Combine(TestKit.Temp("inst"), "OriginLauncher");
        var staged = Installer.Stage(dir, TestKit.Zip(TestKit.MakeLauncherTree()), null, _ => { }, CancellationToken.None, new FakeServer().Client(), setup);
        Assert.Equal("the setup app", File.ReadAllText(Path.Combine(staged.FilesDir, "uninstall.exe")));
    }

    [Fact]
    public void TheSetupStillRunningWithItsFileOpenBecomesTheUninstaller()
    {
        var setup = Path.Combine(TestKit.Temp("setup"), "OriginLauncher-Setup.exe");
        File.WriteAllText(setup, "the setup app");
        using var running = new FileStream(setup, FileMode.Open, FileAccess.Read, FileShare.Read | FileShare.Delete);
        var dir = Path.Combine(TestKit.Temp("inst"), "OriginLauncher");
        var staged = Installer.Stage(dir, TestKit.Zip(TestKit.MakeLauncherTree()), null, _ => { }, CancellationToken.None, new FakeServer().Client(), setup);
        Assert.Equal("the setup app", File.ReadAllText(Path.Combine(staged.FilesDir, "uninstall.exe")));
    }

    [Fact]
    public void ASetupThatCantReadItselfDownloadsTheUninstaller()
    {
        var zip = File.ReadAllBytes(TestKit.Zip(TestKit.MakeLauncherTree()));
        var setup = System.Text.Encoding.ASCII.GetBytes("MZ the released setup app");
        var server = new FakeServer().File(Url, zip).File(ReleaseFeed.SetupAssetUrl("launcher-v1.0.40"), setup);
        var dir = Path.Combine(TestKit.Temp("inst"), "OriginLauncher");
        var staged = Installer.Stage(dir, null, new LauncherRelease("1.0.40", Url, setupSha256: Sha256(setup)), _ => { }, CancellationToken.None, server.Client(), UnreadableSetup());
        Assert.Equal(setup, File.ReadAllBytes(Path.Combine(staged.FilesDir, "uninstall.exe")));
    }

    [Fact]
    public void AnUninstallerTheFeedGaveNoHashForIsNeverDownloaded()
    {
        // IN-6: what Add/Remove Programs runs later must be checked against the release, so no hash = no download.
        var zip = File.ReadAllBytes(TestKit.Zip(TestKit.MakeLauncherTree()));
        var server = new FakeServer().File(Url, zip).File(ReleaseFeed.SetupAssetUrl("launcher-v1.0.40"), System.Text.Encoding.ASCII.GetBytes("MZ anything"));
        var dir = Path.Combine(TestKit.Temp("inst"), "OriginLauncher");
        var ex = Assert.Throws<SetupException>(() =>
            Installer.Stage(dir, null, new LauncherRelease("1.0.40", Url), _ => { }, CancellationToken.None, server.Client(), UnreadableSetup()));
        Assert.Contains("Move it to a folder on this PC", ex.Message);
        Assert.DoesNotContain(server.Requests, r => r.RequestUri!.ToString() == ReleaseFeed.SetupAssetUrl("launcher-v1.0.40"));
        Assert.False(Directory.Exists(dir), "the folder Setup created is removed again");
    }

    [Fact]
    public void ADamagedUninstallerDownloadChangesNothing()
    {
        var zip = File.ReadAllBytes(TestKit.Zip(TestKit.MakeLauncherTree()));
        var server = new FakeServer().File(Url, zip).File(ReleaseFeed.SetupAssetUrl("launcher-v1.0.40"), new byte[] { 1, 2, 3 });
        var dir = TestKit.Temp("existing");
        File.WriteAllText(Path.Combine(dir, "OriginLauncher.App.exe"), "the old launcher");
        var release = new LauncherRelease("1.0.40", Url, setupSha256: new string('0', 64));
        var ex = Assert.Throws<DownloadException>(() =>
            Installer.Stage(dir, null, release, _ => { }, CancellationToken.None, server.Client(), UnreadableSetup()));
        Assert.Equal(DownloadException.Damaged, ex.Message);
        Assert.Equal("the old launcher", File.ReadAllText(Path.Combine(dir, "OriginLauncher.App.exe")));
        Assert.False(Directory.Exists(Path.Combine(dir, "_setup-staging")));
    }

    [Fact]
    public void ADownloadedUninstallerMustBeAProgram()
    {
        var zip = File.ReadAllBytes(TestKit.Zip(TestKit.MakeLauncherTree()));
        var server = new FakeServer().File(Url, zip)
            .File(ReleaseFeed.SetupAssetUrl("launcher-v1.0.40"), System.Text.Encoding.ASCII.GetBytes("<html>not a program</html>"));
        var dir = Path.Combine(TestKit.Temp("inst"), "OriginLauncher");
        var html = Sha256(System.Text.Encoding.ASCII.GetBytes("<html>not a program</html>"));
        var ex = Assert.Throws<DownloadException>(() =>
            Installer.Stage(dir, null, new LauncherRelease("1.0.40", Url, setupSha256: html), _ => { }, CancellationToken.None, server.Client(), UnreadableSetup()));
        Assert.Equal(DownloadException.Damaged, ex.Message);
        Assert.False(Directory.Exists(dir), "the folder Setup created is removed again");
    }

    [Fact]
    public void RunningAsTheInstalledUninstallerLeavesItInPlace()
    {
        var dir = TestKit.Temp("existing");
        var self = Path.Combine(dir, "uninstall.exe");
        File.WriteAllText(self, "the installed uninstaller");
        var staged = Installer.Stage(dir, TestKit.Zip(TestKit.MakeLauncherTree()), null, _ => { }, CancellationToken.None, new FakeServer().Client(), self);
        Assert.False(File.Exists(Path.Combine(staged.FilesDir, "uninstall.exe")));
        Assert.Equal("the installed uninstaller", File.ReadAllText(self));
    }

    [Fact]
    public void CancellingWhileStagingTheUninstallerChangesNothing()
    {
        var setup = Path.Combine(TestKit.Temp("setup"), "OriginLauncher-Setup.exe");
        File.WriteAllText(setup, "the setup app");
        var dir = Path.Combine(TestKit.Temp("inst"), "OriginLauncher");
        using var cts = new CancellationTokenSource();
        // The bar reaching the end of unpacking is the last report before the uninstaller is staged.
        Assert.ThrowsAny<OperationCanceledException>(() =>
            Installer.Stage(dir, TestKit.Zip(TestKit.MakeLauncherTree()), null, p => { if (p.Fraction >= 0.94) cts.Cancel(); }, cts.Token, new FakeServer().Client(), setup));
        Assert.False(Directory.Exists(dir), "the folder Setup created is removed again");
    }

    [Fact]
    public void AnOfflineInstallThatCantReadSetupSaysWhatToDo()
    {
        var dir = Path.Combine(TestKit.Temp("inst"), "OriginLauncher");
        var ex = Assert.Throws<SetupException>(() =>
            Installer.Stage(dir, TestKit.Zip(TestKit.MakeLauncherTree()), null, _ => { }, CancellationToken.None, new FakeServer().Client(), UnreadableSetup()));
        Assert.Contains("Move it to a folder on this PC", ex.Message);
        Assert.False(Directory.Exists(dir), "the folder Setup created is removed again");
    }

    private static string Sha256(byte[] data) =>
        Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(data)).ToLowerInvariant();

    private static string UnreadableSetup() => Path.Combine(TestKit.Temp("setup"), "not-there", "OriginLauncher-Setup.exe");
}
