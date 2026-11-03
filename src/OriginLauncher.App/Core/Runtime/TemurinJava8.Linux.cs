using System.Formats.Tar;
using System.IO;
using System.IO.Compression;
using System.Runtime.InteropServices;

namespace OriginLauncher.App.Core.Runtime;

/// <summary>
/// Eclipse Temurin 8 for 1.8.9-era versions on Linux: the x64 or aarch64 JRE matching this machine,
/// the same 8u504-b01 build as Windows and the Mac, pinned, never "latest".
/// Source (2026-11-02): Adoptium API
///   api.adoptium.net/v3/assets/release_name/eclipse/jdk8u504-b01?architecture=x64&amp;image_type=jre&amp;os=linux
///   (and architecture=aarch64)
/// and each release file's own .sha256.txt asset; both gave the same hashes.
/// An aarch64 Java runs 1.8.9's game code, but 1.8.9's LWJGL 2 natives are x64-only, so the game
/// window itself needs arm64 LWJGL 2 natives that Mojang does not publish (see the Linux port notes).
/// </summary>
public static class TemurinJava8
{
    public static readonly JavaRuntimePin Pin = RuntimeInformation.OSArchitecture == Architecture.Arm64
        ? new(
            Folder: "temurin-8u504-b01",
            Url: "https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u504-b01/OpenJDK8U-jre_aarch64_linux_hotspot_8u504b01.tar.gz",
            Sha256: "9ae9c4dd80fc8f3c4081b480c7d42346e9e4cbee5ae58198fca11e0fc1a19163",
            SizeBytes: 40809549)
        : new(
            Folder: "temurin-8u504-b01",
            Url: "https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u504-b01/OpenJDK8U-jre_x64_linux_hotspot_8u504b01.tar.gz",
            Sha256: "52dcd578baca1d3e449ea86768a9129c0ee04d7b22565695498353cc66940c61",
            SizeBytes: 41844148);
}

public static partial class JavaRuntimeProvisioner
{
    private const string ArchiveFileName = "java.tar.gz";

    // A Linux JRE is <root>/bin/java; there is no javaw (no console window to hide).
    internal static string JavaIn(string runtimeRoot) => Path.Combine(runtimeRoot, "bin", "java");

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
