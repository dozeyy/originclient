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
            .File(ReleaseFeed.AssetUrl("launcher-v1.0.41"), zip);
        var dir = Path.Combine(TestKit.Temp("inst"), "OriginLauncher");
        var staged = Installer.Stage(dir, null, null, _ => { }, CancellationToken.None, server.Client());
        Assert.Equal("1.0.41", staged.Version);
        TestKit.AssertSameTree(tree, staged.FilesDir);
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
}
