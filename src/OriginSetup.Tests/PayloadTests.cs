using System;
using System.IO;
using System.Linq;
using System.Threading;
using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

public class PayloadTests
{
    private static string Pack(string source, int chunkMb = 1, byte[]? fakeSetup = null)
    {
        var dir = Directory.CreateTempSubdirectory("opk").FullName;
        var setup = Path.Combine(dir, "OriginSetup.exe");
        File.WriteAllBytes(setup, fakeSetup ?? new byte[] { 0x4D, 0x5A, 1, 2, 3, 4, 5, 6, 7 });
        var outExe = Path.Combine(dir, "OriginLauncher-Setup.exe");
        Python.Run(Path.Combine(Python.Repo, "tools", "installer", "pack_payload.py"),
            "--setup-exe", setup, "--source", source, "--version", "9.8.7", "--out", outExe,
            "--level", "1", "--chunk-mb", chunkMb.ToString());
        return outExe;
    }

    private static string MakeSource()
    {
        var src = Directory.CreateTempSubdirectory("src").FullName;
        var rnd = new Random(11);
        void Put(string rel, int size)
        {
            var p = Path.Combine(src, rel.Replace('/', Path.DirectorySeparatorChar));
            Directory.CreateDirectory(Path.GetDirectoryName(p)!);
            var b = new byte[size];
            rnd.NextBytes(b);
            for (int i = 0; i < size; i += 3) b[i] = (byte)(i % 7); // part-compressible
            File.WriteAllBytes(p, b);
        }
        Put("OriginLauncher.App.exe", 3_500_000);  // spans several 1 MB chunks
        Put("Bundled/OriginClient/originclient-1.21.1.jar", 700_000);
        Put("Bundled/OriginClient/originclient-1.20.jar", 1);
        Put("ThirdParty/Monocraft-LICENSE.txt", 0);   // empty file
        Put("ThirdParty/notes.txt", 1_048_576);       // exactly one chunk long
        return src;
    }

    private static void AssertSameTree(string expected, string actual)
    {
        var a = Directory.GetFiles(expected, "*", SearchOption.AllDirectories).Select(p => p.Substring(expected.Length)).OrderBy(p => p).ToList();
        var b = Directory.GetFiles(actual, "*", SearchOption.AllDirectories).Select(p => p.Substring(actual.Length)).OrderBy(p => p).ToList();
        Assert.Equal(a, b);
        foreach (var rel in a)
            Assert.True(File.ReadAllBytes(expected + rel).AsSpan().SequenceEqual(File.ReadAllBytes(actual + rel)), rel);
    }

    [Fact]
    public void PackThenExtractIsIdentical()
    {
        var src = MakeSource();
        var exe = Pack(src);
        var payload = Payload.TryOpen(exe)!;
        Assert.Equal("9.8.7", payload.AppVersion);
        Assert.Equal(5, payload.Files.Count);
        Assert.Equal(9, payload.PayloadOffset); // = fake setup app length -> uninstaller copy

        long last = 0;
        var target = Path.Combine(Directory.CreateTempSubdirectory("dst").FullName, "OriginLauncher");
        payload.ExtractTo(target, n => Interlocked.Exchange(ref last, Math.Max(last, n)), CancellationToken.None);
        AssertSameTree(src, target);
        Assert.Equal(payload.TotalSize, last);
    }

    [Fact]
    public void ExtractsWithOneThreadToo()
    {
        var src = MakeSource();
        var payload = Payload.TryOpen(Pack(src))!;
        var target = Directory.CreateTempSubdirectory("dst1").FullName;
        payload.ExtractTo(target, null, CancellationToken.None, maxParallel: 1);
        AssertSameTree(src, target);
    }

    [Fact]
    public void NoTrailerMeansNoPayload()
    {
        var f = Path.GetTempFileName();
        File.WriteAllBytes(f, new byte[100]);
        Assert.Null(Payload.TryOpen(f));
    }

    [Fact]
    public void DamagedChunkIsRejected()
    {
        var src = MakeSource();
        var exe = Pack(src);
        var bytes = File.ReadAllBytes(exe);
        bytes[bytes.Length - 5000] ^= 0xFF; // inside the last chunk's data
        File.WriteAllBytes(exe, bytes);
        var payload = Payload.TryOpen(exe)!;
        var target = Directory.CreateTempSubdirectory("bad").FullName;
        var ex = Assert.ThrowsAny<Exception>(() => payload.ExtractTo(target, null, CancellationToken.None));
        var inner = ex is AggregateException ag ? ag.Flatten().InnerExceptions.First() : ex;
        Assert.IsType<InvalidDataException>(inner);
    }

    [Fact]
    public void TruncatedDownloadIsRejected()
    {
        var exe = Pack(MakeSource());
        var bytes = File.ReadAllBytes(exe);
        // Lose the middle of the file but keep a valid-looking trailer.
        var cut = bytes.Take(bytes.Length / 2).Concat(bytes.Skip(bytes.Length - 16)).ToArray();
        File.WriteAllBytes(exe, cut);
        Assert.ThrowsAny<Exception>(() => Payload.TryOpen(exe));
    }

    [Fact]
    public void CancellationStopsExtraction()
    {
        var payload = Payload.TryOpen(Pack(MakeSource()))!;
        using var cts = new CancellationTokenSource();
        cts.Cancel();
        var target = Directory.CreateTempSubdirectory("cancel").FullName;
        Assert.ThrowsAny<OperationCanceledException>(() => payload.ExtractTo(target, null, cts.Token));
    }

    // Opt-in: ORIGIN_REAL_PAYLOAD=<folder with the real launcher files> runs the
    // packer at release settings over the real launcher and checks every byte.
    [Fact]
    public void RealLauncherPayload()
    {
        var real = Environment.GetEnvironmentVariable("ORIGIN_REAL_PAYLOAD");
        if (string.IsNullOrEmpty(real)) return;
        var dir = Directory.CreateTempSubdirectory("real").FullName;
        var setup = Path.Combine(dir, "OriginSetup.exe");
        File.WriteAllBytes(setup, new byte[] { 0x4D, 0x5A });
        var exe = Path.Combine(dir, "OriginLauncher-Setup.exe");
        Python.Run(Path.Combine(Python.Repo, "tools", "installer", "pack_payload.py"),
            "--setup-exe", setup, "--source", real, "--version", "1.0.39", "--out", exe);
        var sw = System.Diagnostics.Stopwatch.StartNew();
        var target = Path.Combine(dir, "installed");
        Payload.TryOpen(exe)!.ExtractTo(target, null, CancellationToken.None);
        Console.WriteLine($"real payload: {new FileInfo(exe).Length / 1e6:F1} MB, extracted in {sw.Elapsed.TotalSeconds:F1}s");
        AssertSameTree(real, target);
    }
}
