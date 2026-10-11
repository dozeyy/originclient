using System;
using System.Diagnostics;
using System.IO;

namespace OriginSetup.Tests;

// Runs python3 for the reference encoder (liblzma) and the real payload packer.
internal static class Python
{
    public static string Repo { get; } = FindRepo();

    private static string FindRepo()
    {
        var dir = AppContext.BaseDirectory;
        while (dir != null && !File.Exists(Path.Combine(dir, "OriginLauncher.sln")))
            dir = Path.GetDirectoryName(dir);
        return dir ?? throw new InvalidOperationException("repo root not found");
    }

    public static void Run(params string[] args)
    {
        var psi = new ProcessStartInfo("python3") { RedirectStandardError = true, RedirectStandardOutput = true, UseShellExecute = false };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var p = Process.Start(psi)!;
        string stdout = p.StandardOutput.ReadToEnd();
        string stderr = p.StandardError.ReadToEnd();
        p.WaitForExit();
        if (p.ExitCode != 0) throw new InvalidOperationException($"python3 failed: {stderr}\n{stdout}");
    }

    /// <summary>Compresses data to a .lzma (alone) stream with liblzma.</summary>
    public static byte[] LzmaAlone(byte[] data, string filters = "preset=6")
    {
        var dir = Directory.CreateTempSubdirectory("lzma").FullName;
        var src = Path.Combine(dir, "in.bin");
        var dst = Path.Combine(dir, "out.lzma");
        File.WriteAllBytes(src, data);
        Run("-c",
            "import lzma,sys\n" +
            $"kw=dict({filters})\n" +
            "f=kw.pop('filters',None)\n" +
            "d=open(sys.argv[1],'rb').read()\n" +
            "out=lzma.compress(d,format=lzma.FORMAT_ALONE,filters=f) if f else lzma.compress(d,format=lzma.FORMAT_ALONE,**kw)\n" +
            "open(sys.argv[2],'wb').write(out)",
            src, dst);
        return File.ReadAllBytes(dst);
    }
}
