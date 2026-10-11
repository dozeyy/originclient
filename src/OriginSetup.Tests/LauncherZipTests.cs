using System;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Threading;
using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

public class LauncherZipTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)] // Windows PowerShell's Compress-Archive writes '\' separators
    public void UnpacksIdentically(bool backslashes)
    {
        var tree = TestKit.MakeLauncherTree();
        var zip = LauncherZip.Open(TestKit.Zip(tree, backslashes));
        Assert.Equal(4, zip.Files.Count);
        long last = 0;
        var target = Path.Combine(TestKit.Temp("dst"), "files");
        zip.ExtractTo(target, n => last = n, CancellationToken.None);
        TestKit.AssertSameTree(tree, target);
        Assert.Equal(zip.TotalSize, last);
    }

    [Fact]
    public void CorruptedBytesAreCaughtByTheChecksum()
    {
        var tree = TestKit.MakeLauncherTree();
        // Store (not deflate) the entry so a flipped byte decodes "fine" and
        // only the CRC can notice.
        var path = Path.Combine(TestKit.Temp("stored"), "stored.zip");
        using (var a = ZipFile.Open(path, ZipArchiveMode.Create))
            a.CreateEntryFromFile(Path.Combine(tree, "OriginLauncher.App.exe"), "OriginLauncher.App.exe", CompressionLevel.NoCompression);
        var bytes = File.ReadAllBytes(path);
        bytes[100_000] ^= 0xFF; // inside the stored data
        File.WriteAllBytes(path, bytes);
        var zip = LauncherZip.Open(path);
        Assert.Throws<InvalidDataException>(() => zip.ExtractTo(TestKit.Temp("bad"), null, CancellationToken.None));
    }

    [Fact]
    public void TruncatedDownloadIsRejected()
    {
        var path = TestKit.Zip(TestKit.MakeLauncherTree());
        var bytes = File.ReadAllBytes(path);
        File.WriteAllBytes(path, bytes.Take(bytes.Length / 2).ToArray());
        Assert.Throws<InvalidDataException>(() => LauncherZip.Open(path));
    }

    [Fact]
    public void NotAZipIsRejected()
    {
        var path = Path.Combine(TestKit.Temp("html"), "OriginLauncher-win-x64.zip");
        File.WriteAllText(path, "<html>Not Found</html>");
        Assert.Throws<InvalidDataException>(() => LauncherZip.Open(path));
    }

    [Fact]
    public void AZipWithoutTheLauncherIsRejected()
    {
        var tree = TestKit.MakeLauncherTree();
        File.Delete(Path.Combine(tree, "OriginLauncher.App.exe"));
        Assert.Throws<InvalidDataException>(() => LauncherZip.Open(TestKit.Zip(tree)));
    }

    [Theory]
    [InlineData("../evil.exe")]
    [InlineData("Bundled/../../evil.exe")]
    [InlineData("/etc/evil")]
    [InlineData("C:/Windows/evil.dll")]
    public void PathsThatEscapeTheInstallFolderAreRejected(string name)
    {
        var path = Path.Combine(TestKit.Temp("slip"), "slip.zip");
        using (var a = ZipFile.Open(path, ZipArchiveMode.Create))
        {
            using (var w = new StreamWriter(a.CreateEntry("OriginLauncher.App.exe").Open())) w.Write("x");
            using (var w = new StreamWriter(a.CreateEntry(name).Open())) w.Write("x");
        }
        Assert.Throws<InvalidDataException>(() => LauncherZip.Open(path));
    }

    [Fact]
    public void CancellationStopsUnpacking()
    {
        var zip = LauncherZip.Open(TestKit.Zip(TestKit.MakeLauncherTree()));
        using var cts = new CancellationTokenSource();
        cts.Cancel();
        Assert.ThrowsAny<OperationCanceledException>(() => zip.ExtractTo(TestKit.Temp("cancel"), null, cts.Token));
    }

    [Fact]
    public void Crc32MatchesZlib()
    {
        // zlib.crc32(b"123456789") == 0xCBF43926 (the standard check value)
        var b = System.Text.Encoding.ASCII.GetBytes("123456789");
        Assert.Equal(0xCBF43926u, Crc32.Compute(b, 0, b.Length));
        Assert.Equal(0xCBF43926u, Crc32.Append(Crc32.Compute(b, 0, 4), b, 4, 5));
    }
}
