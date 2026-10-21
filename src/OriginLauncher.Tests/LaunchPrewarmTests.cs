using OriginLauncher.App.Core.Versions;
using Xunit;

namespace OriginLauncher.Tests;

// The launch warm-up is read-only: it must touch what a launch needs, change nothing, respect its cap
// and cancellation, and survive missing folders.
public class LaunchPrewarmTests : IDisposable
{
    private readonly string _root = Directory.CreateTempSubdirectory("prewarm").FullName;
    private string Mods => Path.Combine(_root, "mods");

    public void Dispose()
    {
        try { Directory.Delete(_root, true); } catch { /* best effort */ }
    }

    private string Make(string relative, int bytes)
    {
        var path = Path.Combine(_root, relative.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, new byte[bytes]);
        return path;
    }

    [Fact]
    public void ReadsJarsAndStatsEverythingElse()
    {
        Make("versions/1.21.1/1.21.1.jar", 1000);
        Make("versions/1.21.1/1.21.1.json", 10);
        Make("mods/originclient.jar", 2000);
        Make("libraries/org/x/x.jar", 3000);
        Make("assets/objects/ab/abcd", 50);
        Make("runtime/java/bin/java.exe", 70);

        var result = LaunchPrewarm.Warm(_root, Mods, "1.21.1", CancellationToken.None);

        Assert.Equal(3, result.FilesRead); // client jar, mod jar, library jar
        Assert.Equal(6000, result.BytesRead);
        Assert.True(result.Entries >= 6);
        Assert.False(result.Cancelled);
    }

    [Fact]
    public void ChangesNothingOnDisk()
    {
        var jar = Make("versions/1.21.1/1.21.1.jar", 4096);
        var before = (File.GetLastWriteTimeUtc(jar), new FileInfo(jar).Length);
        var names = Directory.GetFileSystemEntries(_root, "*", SearchOption.AllDirectories).OrderBy(x => x).ToArray();

        LaunchPrewarm.Warm(_root, Mods, "1.21.1", CancellationToken.None);

        Assert.Equal(before, (File.GetLastWriteTimeUtc(jar), new FileInfo(jar).Length));
        Assert.Equal(names, Directory.GetFileSystemEntries(_root, "*", SearchOption.AllDirectories).OrderBy(x => x).ToArray());
    }

    [Fact]
    public void MissingFoldersAreFine()
    {
        var result = LaunchPrewarm.Warm(Path.Combine(_root, "nope"), Path.Combine(_root, "nomods"), "1.21.1", CancellationToken.None);
        Assert.Equal(0, result.Entries);
        Assert.Equal(0, result.FilesRead);
    }

    [Fact]
    public void CancelledBeforeStartReadsNothing()
    {
        Make("versions/1.21.1/1.21.1.jar", 1000);
        using var cts = new CancellationTokenSource();
        cts.Cancel();
        var result = LaunchPrewarm.Warm(_root, Mods, "1.21.1", cts.Token);
        Assert.True(result.Cancelled);
        Assert.Equal(0, result.BytesRead);
    }

    [Fact]
    public void AJarHeldOpenByAGameIsStillReadable()
    {
        var jar = Make("mods/held.jar", 500);
        // Another account's running game holds its jars open (read sharing).
        using var held = new FileStream(jar, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        var result = LaunchPrewarm.Warm(_root, Mods, "1.21.1", CancellationToken.None);
        Assert.Equal(500, result.BytesRead);
    }
}
