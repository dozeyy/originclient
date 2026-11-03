using System.Formats.Tar;
using System.IO;
using System.IO.Compression;

namespace OriginLauncher.App.Core.Runtime;

/// <summary>
/// Eclipse Temurin 8 for 1.8.9-era versions on a Mac. Adoptium builds Java 8 for macOS on x64 only
/// (its aarch64 Java 8 list is empty), so Apple Silicon runs this one under Rosetta 2, which is also
/// what LWJGL 2's x64-only natives in 1.8.9 need. Same build as Windows, pinned, never "latest".
/// Source (2026-11-02): Adoptium API
///   api.adoptium.net/v3/assets/release_name/eclipse/jdk8u504-b01?architecture=x64&amp;image_type=jre&amp;os=mac
/// and the release's own .sha256.txt asset; both gave the same hash.
/// </summary>
public static class TemurinJava8
{
    public static readonly JavaRuntimePin Pin = new(
        Folder: "temurin-8u504-b01",
        Url: "https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u504-b01/OpenJDK8U-jre_x64_mac_hotspot_8u504b01.tar.gz",
        Sha256: "f06abadad0fa97e04866d7229a2baad53261579ab5d44875ffa8846a28f28d86",
        SizeBytes: 44361038);
}

public static partial class JavaRuntimeProvisioner
{
    private const string ArchiveFileName = "java.tar.gz";

    // A Mac JRE is a bundle: <root>/Contents/Home is the Java home, and there is no javaw (no console
    // window to hide on a Mac).
    internal static string JavaIn(string runtimeRoot) => Path.Combine(runtimeRoot, "Contents", "Home", "bin", "java");

    // tar.gz, not zip: the tar keeps each file's Unix mode, so bin/java stays executable. .NET's
    // TarFile refuses entries and link targets that would land outside the destination folder.
    internal static void ExtractArchive(string archive, string destination)
    {
        Directory.CreateDirectory(destination);
        using var file = File.OpenRead(archive);
        using var gzip = new GZipStream(file, CompressionMode.Decompress);
        TarFile.ExtractToDirectory(gzip, destination, overwriteFiles: false);
    }
}
